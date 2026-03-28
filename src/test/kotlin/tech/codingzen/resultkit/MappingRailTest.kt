package tech.codingzen.resultkit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MappingRailTest {

    // -- top-level invoke (returns Res) --

    @Test
    fun `top-level invoke returns Ok on block success with Res ok`() {
        val httpRail = MappingRail<Int, String>(
            onError = { "Code: $it" },
            onException = { "Exception: ${it.message}" },
        )
        val result = httpRail { Res.ok(42) }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `top-level invoke catches exception and maps via onException`() {
        val httpRail = MappingRail<Int, String>(
            onError = { "Code: $it" },
            onException = { "Exception: ${it.message}" },
        )
        val result: Res<Int, String> = httpRail { throw RuntimeException("timeout") }
        assertTrue(result.isFail)
        assertEquals("Exception: timeout", result.errorOrThrow())
    }

    @Test
    fun `top-level invoke maps Fail error via onError`() {
        val httpRail = MappingRail<Int, String>(
            onError = { "Code: $it" },
            onException = { "Exception: ${it.message}" },
        )
        val result: Res<Int, String> = httpRail { Res.failure(404) }
        assertTrue(result.isFail)
        assertEquals("Code: 404", result.errorOrThrow())
    }

    @Test
    fun `top-level invoke fail short-circuits with unmapped error`() {
        val httpRail = MappingRail<Int, String>(
            onError = { "Code: $it" },
            onException = { "Exception: ${it.message}" },
        )
        val result: Res<Int, String> = httpRail {
            fail("explicit failure")
        }
        assertTrue(result.isFail)
        assertEquals("explicit failure", result.errorOrThrow())
    }

    @Test
    fun `top-level invoke orFail works inside block`() {
        val httpRail = MappingRail<Int, String>(
            onError = { "Code: $it" },
            onException = { "Exception: ${it.message}" },
        )
        val result = httpRail {
            val x = Res.ok(10).orFail()
            Res.ok(x + 5)
        }
        assertTrue(result.isOk)
        assertEquals(15, result.getOrNull())
    }

    @Test
    fun `top-level invoke ensure works inside block`() {
        val httpRail = MappingRail<Int, String>(
            onError = { "Code: $it" },
            onException = { "Exception: ${it.message}" },
        )
        val result: Res<Int, String> = httpRail {
            ensure(false) { "validation failed" }
            Res.ok(42)
        }
        assertTrue(result.isFail)
        assertEquals("validation failed", result.errorOrThrow())
    }

    @Test
    fun `top-level invoke rethrows CancellationException`() {
        val httpRail = MappingRail<Int, String>(
            onError = { "Code: $it" },
            onException = { "Exception: ${it.message}" },
        )
        assertFailsWith<CancellationException> {
            @Suppress("UNUSED_VARIABLE")
            val unused: Res<Int, String> = httpRail { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `top-level invoke Error propagates through without being caught`() {
        val error = object : Error("fatal") {}
        val httpRail = MappingRail<Int, String>(
            onError = { "Code: $it" },
            onException = { "Exception: ${it.message}" },
        )
        assertFailsWith<Error> {
            val unused: Res<Int, String> = httpRail { throw error }
        }
    }

    @Test
    fun `top-level invoke reusable across multiple calls`() {
        val httpRail = Rail.mapping<Int, String>(
            onError = { "Code: $it" },
            onException = { "Exception: ${it.message}" },
        )

        val r1 = httpRail { Res.ok(1) }
        val r2: Res<Int, String> = httpRail { Res.failure(404) }
        val r3: Res<Int, String> = httpRail { throw RuntimeException("fail") }
        val r4 = httpRail { Res.ok(4) }

        assertTrue(r1.isOk)
        assertEquals(1, r1.getOrNull())
        assertTrue(r2.isFail)
        assertEquals("Code: 404", r2.errorOrThrow())
        assertTrue(r3.isFail)
        assertEquals("Exception: fail", r3.errorOrThrow())
        assertTrue(r4.isOk)
        assertEquals(4, r4.getOrNull())
    }

    @Test
    fun `top-level invoke works with suspend lambdas`() = runTest {
        val httpRail = MappingRail<Int, String>(
            onError = { "Code: $it" },
            onException = { "Exception: ${it.message}" },
        )
        val result = httpRail {
            delay(1)
            Res.ok(42)
        }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `top-level invoke ErrorMapperException wraps when onException mapper throws`() {
        val httpRail = MappingRail<Int, String>(
            onError = { "Code: $it" },
            onException = { throw IllegalStateException("mapper broke") },
        )
        val ex = assertFailsWith<ErrorMapperException> {
            val unused: Res<Int, String> = httpRail { throw RuntimeException("original") }
        }
        assertIs<IllegalStateException>(ex.cause)
        assertEquals("mapper broke", ex.cause!!.message)
        assertIs<RuntimeException>(ex.originalException)
        assertEquals("original", ex.originalException.message)
    }

    @Test
    fun `top-level invoke onError throw falls into Exception catch and maps via onException`() {
        val httpRail = MappingRail<Int, String>(
            onError = { throw IllegalStateException("mapper broke") },
            onException = { "Exception: ${it.message}" },
        )
        // onError throws inside mapError {}, falls into catch(Exception), gets mapped by onException
        val result = httpRail { Res.failure(404) }
        assertTrue(result.isFail)
        assertEquals("Exception: mapper broke", result.errorOrThrow())
    }

    @Test
    fun `top-level invoke ErrorMapperException when both onError and onException throw`() {
        val httpRail = MappingRail<Int, String>(
            onError = { throw IllegalStateException("onError broke") },
            onException = { throw IllegalArgumentException("onException broke") },
        )
        val ex = assertFailsWith<ErrorMapperException> {
            httpRail { Res.failure(404) }
        }
        assertIs<IllegalArgumentException>(ex.cause)
        assertEquals("onException broke", ex.cause!!.message)
        assertIs<IllegalStateException>(ex.originalException)
        assertEquals("onError broke", ex.originalException.message)
    }

    @Test
    fun `top-level invoke scope isolation — inner fail does not leak to outer rail`() {
        val httpRail = MappingRail<Int, String>(
            onError = { "Code: $it" },
            onException = { "Exception: ${it.message}" },
        )
        val result = rail<Int, String> {
            val inner: Res<Int, String> = httpRail { fail("inner") }
            // inner rail's fail("inner") is caught by httpRail's scope, not the outer rail
            inner.orFail()
        }
        assertTrue(result.isFail)
        assertEquals("inner", result.errorOrThrow())
    }

    private fun topLevelMappingNonLocalReturn(id: Int): Res<Int, String> {
        val httpRail = MappingRail<Int, String>(
            onError = { "Code: $it" },
            onException = { "Exception: ${it.message}" },
        )
        val result = httpRail {
            if (id < 0) return Res.failure("negative")
            Res.ok(id * 2)
        }
        return result
    }

    @Test
    fun `non-local return works in top-level MappingRail invoke`() {
        val result = topLevelMappingNonLocalReturn(-1)
        assertTrue(result.isFail)
        assertEquals("negative", result.errorOrThrow())
    }

    // -- member extension invoke (inside rail {}, returns V) --

    @Test
    fun `member extension catches exception and maps via onException`() {
        val result = rail<Int, String> {
            val http = mapping<Int>(
                onError = { "Code: $it" },
                onException = { "Exception: ${it.message}" },
            )
            http { throw RuntimeException("boom") }
        }
        assertTrue(result.isFail)
        assertEquals("Exception: boom", result.errorOrThrow())
    }

    @Test
    fun `member extension maps Fail error via onError`() {
        val result = rail<Int, String> {
            val http = mapping<Int>(
                onError = { "Code: $it" },
                onException = { "Exception: ${it.message}" },
            )
            http { Res.failure(404) }
        }
        assertTrue(result.isFail)
        assertEquals("Code: 404", result.errorOrThrow())
    }

    @Test
    fun `member extension unwraps Ok value`() {
        val result = rail<Int, String> {
            val http = mapping<Int>(
                onError = { "Code: $it" },
                onException = { "Exception: ${it.message}" },
            )
            http { Res.ok(42) }
        }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `member extension fail bypasses both mappers`() {
        val result = rail<Int, String> {
            val http = mapping<Int>(
                onError = { "Mapped: $it" },
                onException = { "Caught: ${it.message}" },
            )
            http { fail("raw error") }
        }
        assertTrue(result.isFail)
        assertEquals("raw error", result.errorOrThrow())
    }

    @Test
    fun `member extension rethrows CancellationException`() {
        assertFailsWith<CancellationException> {
            rail<Int, String> {
                val http = mapping<Int>(
                    onError = { "Code: $it" },
                    onException = { "Exception: ${it.message}" },
                )
                http { throw CancellationException("cancelled") }
            }
        }
    }

    @Test
    fun `member extension Error propagates through`() {
        val error = object : Error("fatal") {}
        assertFailsWith<Error> {
            rail<Int, String> {
                val http = mapping<Int>(
                    onError = { "Code: $it" },
                    onException = { "Exception: ${it.message}" },
                )
                http { throw error }
            }
        }
    }

    @Test
    fun `member extension reusable across multiple calls`() {
        val result = rail<Int, String> {
            val http = mapping<Int>(
                onError = { "Code: $it" },
                onException = { "Exception: ${it.message}" },
            )
            val a = http { Res.ok(10) }
            val b = http { Res.ok(20) }
            a + b
        }
        assertTrue(result.isOk)
        assertEquals(30, result.getOrNull())
    }

    @Test
    fun `member extension works with suspend lambdas`() = runTest {
        val result = rail<Int, String> {
            val http = mapping<Int>(
                onError = { "Code: $it" },
                onException = { "Exception: ${it.message}" },
            )
            http {
                delay(1)
                Res.ok(42)
            }
        }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `member extension ErrorMapperException wraps when onException mapper throws`() {
        val ex = assertFailsWith<ErrorMapperException> {
            rail<Int, String> {
                val http = mapping<Int>(
                    onError = { "Code: $it" },
                    onException = { throw IllegalStateException("mapper broke") },
                )
                http { throw RuntimeException("original") }
            }
        }
        assertIs<IllegalStateException>(ex.cause)
        assertEquals("mapper broke", ex.cause!!.message)
        assertIs<RuntimeException>(ex.originalException)
        assertEquals("original", ex.originalException.message)
    }

    @Test
    fun `member extension onError throw falls into Exception catch and maps via onException`() {
        val result = rail<Int, String> {
            val http = mapping<Int>(
                onError = { throw IllegalStateException("mapper broke") },
                onException = { "Exception: ${it.message}" },
            )
            http { Res.failure(404) }
        }
        // onError throws inside orFail {}, falls into catch(Exception), gets mapped by onException via fail()
        assertTrue(result.isFail)
        assertEquals("Exception: mapper broke", result.errorOrThrow())
    }

    @Test
    fun `member extension ErrorMapperException when both onError and onException throw`() {
        val ex = assertFailsWith<ErrorMapperException> {
            rail<Int, String> {
                val http = mapping<Int>(
                    onError = { throw IllegalStateException("onError broke") },
                    onException = { throw IllegalArgumentException("onException broke") },
                )
                http { Res.failure(404) }
            }
        }
        assertIs<IllegalArgumentException>(ex.cause)
        assertEquals("onException broke", ex.cause!!.message)
        assertIs<IllegalStateException>(ex.originalException)
        assertEquals("onError broke", ex.originalException.message)
    }

    // -- mappingWith factory --

    @Test
    fun `factory creates MappingRail that catches exceptions`() {
        val result = rail<Int, String> {
            val apis = mappingWith { "Caught: ${it.message}" }
            val http = apis.error<Int> { "HTTP: $it" }
            http { throw RuntimeException("timeout") }
        }
        assertTrue(result.isFail)
        assertEquals("Caught: timeout", result.errorOrThrow())
    }

    @Test
    fun `factory creates MappingRail that maps typed errors`() {
        val result = rail<Int, String> {
            val apis = mappingWith { "Caught: ${it.message}" }
            val http = apis.error<Int> { "HTTP: $it" }
            http { Res.failure(404) }
        }
        assertTrue(result.isFail)
        assertEquals("HTTP: 404", result.errorOrThrow())
    }

    @Test
    fun `multiple MappingRails from same factory share exception handler`() {
        val result = rail<Int, String> {
            val apis = mappingWith { "Caught: ${it.message}" }
            val http = apis.error<Int> { "HTTP: $it" }
            val db = apis.error<Long> { "DB: $it" }

            http { throw RuntimeException("timeout") }
        }
        assertTrue(result.isFail)
        assertEquals("Caught: timeout", result.errorOrThrow())
    }

    @Test
    fun `different error domains from same factory work in same rail block`() {
        val result = rail<Int, String> {
            val apis = mappingWith { "Exception: ${it.message}" }
            val http = apis.error<Int> { "HTTP: $it" }
            val db = apis.error<Long> { "DB: $it" }

            val x = http { Res.ok(10) }
            val y = db { Res.ok(20L) }
            x + y.toInt()
        }
        assertTrue(result.isOk)
        assertEquals(30, result.getOrNull())
    }

    @Test
    fun `factory-produced MappingRail behaves identically to direct mapping`() {
        val factory = mappingFactory()
        val directRail = MappingRail<Int, String>(
            onError = { "Code: $it" },
            onException = { "Exception: ${it.message}" },
        )

        val directResult: Res<Int, String> = directRail { Res.failure(404) }
        val factoryResult: Res<Int, String> = factory { Res.failure(404) }

        assertEquals(directResult.errorOrThrow(), factoryResult.errorOrThrow())
    }

    private fun mappingFactory(): MappingRail<Int, String> {
        val apis = MappingFactory<String> { "Exception: ${it.message}" }
        return apis.error { "Code: $it" }
    }

    @Test
    fun `companion Rail mappingWith creates factory for top-level usage`() {
        val apis = Rail.mappingWith<String> { "Caught: ${it.message}" }
        val httpRail = apis.error<Int> { "HTTP: $it" }

        val result: Res<Int, String> = httpRail { Res.ok(42) }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `factory-produced MappingRail works as top-level invoke`() {
        val apis = Rail.mappingWith<String> { "Caught: ${it.message}" }
        val httpRail = apis.error<Int> { "HTTP: $it" }

        val r1 = httpRail { Res.ok(1) }
        val r2: Res<Int, String> = httpRail { Res.failure(500) }
        val r3: Res<Int, String> = httpRail { throw RuntimeException("fail") }

        assertTrue(r1.isOk)
        assertEquals(1, r1.getOrNull())
        assertTrue(r2.isFail)
        assertEquals("HTTP: 500", r2.errorOrThrow())
        assertTrue(r3.isFail)
        assertEquals("Caught: fail", r3.errorOrThrow())
    }

    // -- combined scenarios --

    @Test
    fun `mapping and failMapping in the same rail block`() {
        val result = rail<String, String> {
            val io = failMapping { "IO: ${it.message}" }
            val http = mapping<Int>(
                onError = { "HTTP: $it" },
                onException = { "Net: ${it.message}" },
            )
            val config: String = io { "config-data" }
            val user = http { Res.ok("user-1") }
            "$config:$user"
        }
        assertTrue(result.isOk)
        assertEquals("config-data:user-1", result.getOrNull())
    }

    @Test
    fun `mapping and errorMapping in the same rail block`() {
        val result = rail<String, String> {
            val validate = errorMapping<Int> { "Validation: $it" }
            val http = mapping<Int>(
                onError = { "HTTP: $it" },
                onException = { "Net: ${it.message}" },
            )
            val code = validate(Res.ok(200))
            val user = http { Res.ok("user-1") }
            "$code:$user"
        }
        assertTrue(result.isOk)
        assertEquals("200:user-1", result.getOrNull())
    }

    @Test
    fun `all four rail types work together`() {
        val result = rail<String, String> {
            val io = failMapping { "IO: ${it.message}" }
            val validate = errorMapping<Int> { "Validation: $it" }
            val http = mapping<Int>(
                onError = { "HTTP: $it" },
                onException = { "Net: ${it.message}" },
            )

            val config = io { "config-data" }
            val code = validate(Res.ok(200))
            val user = http { Res.ok("user-1") }
            val extra = Res.ok("direct").orFail()
            "$config:$code:$user:$extra"
        }
        assertTrue(result.isOk)
        assertEquals("config-data:200:user-1:direct", result.getOrNull())
    }

    @Test
    fun `nested rail with inner mapping does not leak to outer rail`() {
        val result = rail<String, String> {
            val inner = rail<Int, String> {
                val http = mapping<Int>(
                    onError = { "HTTP: $it" },
                    onException = { "Net: ${it.message}" },
                )
                http { Res.failure(404) }
            }
            // inner rail caught the error, outer rail sees it as a Res
            inner.orFail()
            @Suppress("UNREACHABLE_CODE")
            "ok"
        }
        assertTrue(result.isFail)
        assertEquals("HTTP: 404", result.errorOrThrow())
    }

    @Test
    fun `top-level MappingRail nested inside rail isolates scopes`() {
        val httpRail = MappingRail<Int, String>(
            onError = { "Code: $it" },
            onException = { "Exception: ${it.message}" },
        )
        val result = rail<Int, String> {
            val inner: Res<Int, String> = httpRail { fail("inner") }
            // inner rail's fail("inner") is caught by httpRail's scope, not the outer rail
            inner.orFail()
        }
        assertTrue(result.isFail)
        assertEquals("inner", result.errorOrThrow())
    }

    @Test
    fun `mappingWith shares exception handler across domains`() {
        val result = rail<Int, String> {
            val apis = mappingWith { "Caught: ${it.message}" }
            val http = apis.error<Int> { "HTTP: $it" }

            http { throw RuntimeException("timeout") }
        }
        assertTrue(result.isFail)
        assertEquals("Caught: timeout", result.errorOrThrow())
    }

    @Test
    fun `mappingWith factory produces working MappingRails`() {
        val result = rail<Int, String> {
            val apis = mappingWith { "Exception: ${it.message}" }
            val http = apis.error<Int> { "HTTP: $it" }
            val db = apis.error<Long> { "DB: $it" }

            val x = http { Res.ok(10) }
            val y = db { Res.ok(20L) }
            x + y.toInt()
        }
        assertTrue(result.isOk)
        assertEquals(30, result.getOrNull())
    }
}
