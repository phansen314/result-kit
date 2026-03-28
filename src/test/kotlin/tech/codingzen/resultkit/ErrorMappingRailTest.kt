package tech.codingzen.resultkit

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ErrorMappingRailTest {

    // -- Member extension: inside rail {} --

    @Test
    fun `invoke unwraps Ok value`() {
        val result = rail<Int, String> {
            val http = errorMapping<Int> { code -> "HTTP $code" }
            val x: Int = http(Res.ok(42))
            x + 1
        }
        assertEquals(43, result.getOrNull())
    }

    @Test
    fun `invoke maps error on Fail and short-circuits`() {
        val result = rail<Int, String> {
            val http = errorMapping<Int> { code -> "HTTP $code" }
            http(Res.failure(404))
            @Suppress("UNREACHABLE_CODE")
            999
        }
        assertTrue(result.isFail)
        assertEquals("HTTP 404", result.errorOrThrow())
    }

    @Test
    fun `errorMapping is reusable across multiple calls`() {
        val result = rail<Int, String> {
            val http = errorMapping<Int> { code -> "HTTP $code" }
            val a = http(Res.ok(10))
            val b = http(Res.ok(20))
            a + b
        }
        assertEquals(30, result.getOrNull())
    }

    @Test
    fun `short-circuits on first error`() {
        var secondCalled = false
        val result = rail<Int, String> {
            val http = errorMapping<Int> { code -> "HTTP $code" }
            http(Res.failure(500))
            @Suppress("UNREACHABLE_CODE")
            secondCalled = true
            @Suppress("UNREACHABLE_CODE")
            0
        }
        assertTrue(result.isFail)
        assertEquals("HTTP 500", result.errorOrThrow())
        assertEquals(false, secondCalled)
    }

    @Test
    fun `works with different error types in same rail block`() {
        val result = rail<String, String> {
            val http = errorMapping<Int> { code -> "HTTP $code" }
            val db = errorMapping<String> { msg -> "DB: $msg" }
            val userId = http(Res.ok(42))
            val name = db(Res.ok("Alice"))
            "$name (id=$userId)"
        }
        assertEquals("Alice (id=42)", result.getOrNull())
    }

    @Test
    fun `different error mappers map their respective errors`() {
        val result = rail<String, String> {
            val http = errorMapping<Int> { code -> "HTTP $code" }
            val db = errorMapping<String> { msg -> "DB: $msg" }
            http(Res.ok(42))
            db(Res.failure("connection lost"))
            @Suppress("UNREACHABLE_CODE")
            ""
        }
        assertTrue(result.isFail)
        assertEquals("DB: connection lost", result.errorOrThrow())
    }

    @Test
    fun `works alongside failMapping in same rail block`() {
        val result = rail<Int, String> {
            val http = errorMapping<Int> { code -> "HTTP $code" }
            val io = failMapping { e -> "IO: ${e.message}" }
            val x = http(Res.ok(10))
            val y: Int = io { x + 5 }
            y
        }
        assertEquals(15, result.getOrNull())
    }

    @Test
    fun `failMapping catches exception while errorMapping maps typed error`() {
        val result = rail<Int, String> {
            val io = failMapping { e -> "IO: ${e.message}" }
            io { throw RuntimeException("disk full") }
            @Suppress("UNREACHABLE_CODE")
            0
        }
        assertTrue(result.isFail)
        assertEquals("IO: disk full", result.errorOrThrow())
    }

    @Test
    fun `works alongside orFail in same rail block`() {
        val result = rail<Int, String> {
            val http = errorMapping<Int> { code -> "HTTP $code" }
            val a = http(Res.ok(10))
            val b: Int = Res.ok(20).orFail()
            a + b
        }
        assertEquals(30, result.getOrNull())
    }

    @Test
    fun `orFail with inline mapping alongside errorMapping`() {
        val result = rail<Int, String> {
            val http = errorMapping<Int> { code -> "HTTP $code" }
            http(Res.ok(10))
            Res.failure(99).orFail { n -> "Direct: $n" }
            @Suppress("UNREACHABLE_CODE")
            0
        }
        assertTrue(result.isFail)
        assertEquals("Direct: 99", result.errorOrThrow())
    }

    @Test
    fun `does NOT catch exceptions — exception propagates through unmapped`() {
        assertFailsWith<RuntimeException> {
            rail<Int, String> {
                val http = errorMapping<Int> { code -> "HTTP $code" }
                http(Res.ok(Unit))
                throw RuntimeException("boom")
            }
        }
    }

    @Test
    fun `works with suspend lambdas`() = runTest {
        suspend fun fetchUser(id: Int): Res<String, Int> {
            delay(1)
            return Res.ok("User-$id")
        }

        val result = rail<String, String> {
            val http = errorMapping<Int> { code -> "HTTP $code" }
            http(fetchUser(1))
        }
        assertEquals("User-1", result.getOrNull())
    }

    // -- Top-level invoke: outside rail {} --

    @Test
    fun `top-level invoke returns Ok on success`() {
        val http = Rail.errorMapping<Int, String> { code -> "HTTP $code" }
        val result = http {
            val a = Res.ok(10).orFail()
            val b = Res.ok(20).orFail()
            a + b
        }
        assertEquals(30, result.getOrNull())
    }

    @Test
    fun `top-level invoke maps error on fail`() {
        val http = Rail.errorMapping<Int, String> { code -> "HTTP $code" }
        val result = http {
            Res.ok(10).orFail()
            Res.failure(404).orFail()
            @Suppress("UNREACHABLE_CODE")
            0
        }
        assertTrue(result.isFail)
        assertEquals("HTTP 404", result.errorOrThrow())
    }

    @Test
    fun `top-level invoke does not catch exceptions`() {
        val http = Rail.errorMapping<Int, String> { code -> "HTTP $code" }
        assertFailsWith<RuntimeException>("boom") {
            http {
                throw RuntimeException("boom")
            }
        }
    }

    @Test
    fun `top-level invoke with suspend`() = runTest {
        val http = Rail.errorMapping<Int, String> { code -> "HTTP $code" }
        val result = http {
            delay(1)
            Res.ok(42).orFail()
        }
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `top-level invoke scope isolation — nested FailException propagates`() {
        val outer = Rail.errorMapping<String, String> { "outer: $it" }
        val inner = Rail.errorMapping<Int, String> { "inner: $it" }

        val result = outer {
            val innerResult: Res<Int, String> = inner {
                fail(404)
                @Suppress("UNREACHABLE_CODE")
                0
            }
            // inner fail should be caught by inner scope and mapped
            innerResult.orFail()
        }
        assertTrue(result.isFail)
        assertEquals("outer: inner: 404", result.errorOrThrow())
    }
}
