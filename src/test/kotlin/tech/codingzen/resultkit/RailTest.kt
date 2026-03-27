package tech.codingzen.resultkit

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RailTest {

    // -- rail basics --

    @Test
    fun `rail returns Ok on success`() = runTest {
        val result = rail<Int, String> { 42 }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull)
    }

    @Test
    fun `rail returns Fail on fail`() = runTest {
        val result = rail<Int, String> { fail("failed") }
        assertTrue(result.isFail)
        assertEquals("failed", result.errorOrThrow())
    }

    // -- orFail --

    @Test
    fun `orFail unwraps Ok value`() = runTest {
        val result = rail<Int, String> {
            val x = Res.ok(10).orFail()
            x + 5
        }
        assertTrue(result.isOk)
        assertEquals(15, result.getOrNull)
    }

    @Test
    fun `orFail unwraps null Ok value`() = runTest {
        val result = rail<Int?, String> {
            val x: Int? = Res.ok(null).orFail()
            x
        }
        assertTrue(result.isOk)
        assertEquals(null, result.getOrNull)
    }

    @Test
    fun `orFail short-circuits on Fail`() = runTest {
        val result = rail<Int, String> {
            val x: Int = Res.failure("fail").orFail()
            x + 1
        }
        assertTrue(result.isFail)
        assertEquals("fail", result.errorOrThrow())
    }

    // -- orFail with mapError --

    @Test
    fun `orFail with mapError converts error type`() = runTest {
        val result = rail<Int, String> {
            val x: Int = Res.failure(404).orFail { code -> "HTTP $code" }
            x + 1
        }
        assertTrue(result.isFail)
        assertEquals("HTTP 404", result.errorOrThrow())
    }

    // -- ensure --

    @Test
    fun `ensure passes on true condition`() = runTest {
        val result = rail<Int, String> {
            ensure(true) { "should not happen" }
            42
        }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull)
    }

    @Test
    fun `ensure fails on false condition`() = runTest {
        val result = rail<Int, String> {
            ensure(false) { "validation failed" }
            42
        }
        assertTrue(result.isFail)
        assertEquals("validation failed", result.errorOrThrow())
    }

    // -- ensureNotNull --

    @Test
    fun `ensureNotNull returns value when not null`() = runTest {
        val result = rail<Int, String> {
            val x = ensureNotNull(42) { "was null" }
            x + 1
        }
        assertTrue(result.isOk)
        assertEquals(43, result.getOrNull)
    }

    @Test
    fun `ensureNotNull fails on null`() = runTest {
        val result = rail<Int, String> {
            val x = ensureNotNull<Int>(null) { "was null" }
            x + 1
        }
        assertTrue(result.isFail)
        assertEquals("was null", result.errorOrThrow())
    }

    // -- suspend --

    @Test
    fun `rail works with suspend calls`() = runTest {
        suspend fun fetchValue(): Res<Int, String> {
            delay(1)
            return Res.ok(42)
        }

        val result = rail<Int, String> {
            val x = fetchValue().orFail()
            x * 2
        }
        assertTrue(result.isOk)
        assertEquals(84, result.getOrNull)
    }

    @Test
    fun `rail works with suspend calls that return errors`() = runTest {
        suspend fun fetchValue(): Res<Int, String> {
            delay(1)
            return Res.failure("remote failure")
        }

        val result = rail<Int, String> {
            val x = fetchValue().orFail()
            x * 2
        }
        assertTrue(result.isFail)
        assertEquals("remote failure", result.errorOrThrow())
    }

    @Test
    fun `chained orFail calls short-circuit on first error`() = runTest {
        val result = rail<Int, String> {
            val a = Res.ok(1).orFail()
            val b: Int = Res.failure("fail at b").orFail()
            @Suppress("UNREACHABLE_CODE")
            a + b
        }
        assertTrue(result.isFail)
        assertEquals("fail at b", result.errorOrThrow())
    }

    // -- non-local returns --

    private fun syncNonLocalReturn(id: Int): Res<Int, String> {
        val result = rail<Int, String> {
            if (id < 0) return Res.failure("negative")
            id * 2
        }
        return result
    }

    @Test
    fun `non-local return works in sync context - early return`() {
        val result = syncNonLocalReturn(-1)
        assertTrue(result.isFail)
        assertEquals("negative", result.errorOrThrow())
    }

    @Test
    fun `non-local return works in sync context - normal path`() {
        val result = syncNonLocalReturn(5)
        assertTrue(result.isOk)
        assertEquals(10, result.getOrNull)
    }

    private suspend fun suspendNonLocalReturn(id: Int): Res<Int, String> {
        val result = rail<Int, String> {
            if (id < 0) return Res.failure("negative")
            delay(1)
            id * 2
        }
        return result
    }

    @Test
    fun `non-local return works in suspend context - early return`() = runTest {
        val result = suspendNonLocalReturn(-1)
        assertTrue(result.isFail)
        assertEquals("negative", result.errorOrThrow())
    }

    @Test
    fun `non-local return works in suspend context - normal path`() = runTest {
        val result = suspendNonLocalReturn(5)
        assertTrue(result.isOk)
        assertEquals(10, result.getOrNull)
    }

    private fun failMappingRailNonLocalReturn(id: Int): Res<Int, String> {
        val appRail = FailMappingRail<String> { e -> "Error: ${e.message}" }
        val result = appRail {
            if (id < 0) return Res.failure("negative")
            id * 2
        }
        return result
    }

    @Test
    fun `non-local return works in FailMappingRail top-level invoke`() {
        val result = failMappingRailNonLocalReturn(-1)
        assertTrue(result.isFail)
        assertEquals("negative", result.errorOrThrow())
    }

}
