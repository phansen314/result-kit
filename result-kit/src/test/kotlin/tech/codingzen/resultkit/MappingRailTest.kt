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
        val httpRail = Rail.catchingMapping<Int, String>(
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
    fun `top-level invoke onError throw surfaces as ErrorMapperException`() {
        val httpRail = MappingRail<Int, String>(
            onError = { throw IllegalStateException("mapper broke") },
            onException = { "Exception: ${it.message}" },
        )
        // A throwing onError is a mapper bug, not a block exception — it must NOT be rerouted
        // through onException. It surfaces as ErrorMapperException carrying the domain error.
        val ex = assertFailsWith<ErrorMapperException> {
            val unused: Res<Int, String> = httpRail { Res.failure(404) }
        }
        assertIs<IllegalStateException>(ex.cause)
        assertEquals("mapper broke", ex.cause!!.message)
        assertTrue(ex.originalException.message!!.contains("404"))
    }

    @Test
    fun `top-level invoke onError throw does not consult onException`() {
        val httpRail = MappingRail<Int, String>(
            onError = { throw IllegalStateException("onError broke") },
            onException = { error("onException must not be called when onError throws") },
        )
        val ex = assertFailsWith<ErrorMapperException> {
            httpRail { Res.failure(404) }
        }
        // cause is the onError throw itself; onException was never consulted.
        assertIs<IllegalStateException>(ex.cause)
        assertEquals("onError broke", ex.cause!!.message)
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
            val http = catchingMapping<Int>(
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
            val http = catchingMapping<Int>(
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
            val http = catchingMapping<Int>(
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
            val http = catchingMapping<Int>(
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
                val http = catchingMapping<Int>(
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
                val http = catchingMapping<Int>(
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
            val http = catchingMapping<Int>(
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
            val http = catchingMapping<Int>(
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
                val http = catchingMapping<Int>(
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
    fun `member extension onError throw surfaces as ErrorMapperException`() {
        // A throwing onError escapes the rail{} as ErrorMapperException — not rerouted to
        // onException, and not swallowed as a Fail.
        val ex = assertFailsWith<ErrorMapperException> {
            rail<Int, String> {
                val http = catchingMapping<Int>(
                    onError = { throw IllegalStateException("mapper broke") },
                    onException = { "Exception: ${it.message}" },
                )
                http { Res.failure(404) }
            }
        }
        assertIs<IllegalStateException>(ex.cause)
        assertEquals("mapper broke", ex.cause!!.message)
        assertTrue(ex.originalException.message!!.contains("404"))
    }

    @Test
    fun `member extension onError throw does not consult onException`() {
        val ex = assertFailsWith<ErrorMapperException> {
            rail<Int, String> {
                val http = catchingMapping<Int>(
                    onError = { throw IllegalStateException("onError broke") },
                    onException = { error("onException must not be called when onError throws") },
                )
                http { Res.failure(404) }
            }
        }
        assertIs<IllegalStateException>(ex.cause)
        assertEquals("onError broke", ex.cause!!.message)
    }

    // -- combined scenarios --

    @Test
    fun `mapping and catching in the same rail block`() {
        val result = rail<String, String> {
            val io = catching { "IO: ${it.message}" }
            val http = catchingMapping<Int>(
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
    fun `mapping and mapping in the same rail block`() {
        val result = rail<String, String> {
            val validate = mapping<Int> { "Validation: $it" }
            val http = catchingMapping<Int>(
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
            val io = catching { "IO: ${it.message}" }
            val validate = mapping<Int> { "Validation: $it" }
            val http = catchingMapping<Int>(
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
                val http = catchingMapping<Int>(
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

}
