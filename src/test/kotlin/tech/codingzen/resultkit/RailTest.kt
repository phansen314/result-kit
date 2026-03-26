package tech.codingzen.resultkit

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RailTest {

    // -- rail basics --

    @Test
    fun `rail returns Ok on success`() = runTest {
        val result = rail<Int, String> { 42 }
        assertIs<Res.Ok<Int>>(result)
        assertEquals(42, result.value)
    }

    @Test
    fun `rail returns Fail on fail`() = runTest {
        val result = rail<Int, String> { fail("failed") }
        assertIs<Res.Fail<String>>(result)
        assertEquals("failed", result.error)
    }

    // -- orFail --

    @Test
    fun `orFail unwraps Ok value`() = runTest {
        val result = rail<Int, String> {
            val x = Res.Ok(10).orFail()
            x + 5
        }
        assertIs<Res.Ok<Int>>(result)
        assertEquals(15, result.value)
    }

    @Test
    fun `orFail short-circuits on Fail`() = runTest {
        val result = rail<Int, String> {
            val x: Int = Res.Fail("fail").orFail()
            x + 1
        }
        assertIs<Res.Fail<String>>(result)
        assertEquals("fail", result.error)
    }

    // -- orFail with mapError --

    @Test
    fun `orFail with mapError converts error type`() = runTest {
        val result = rail<Int, String> {
            val x: Int = Res.Fail(404).orFail { code -> "HTTP $code" }
            x + 1
        }
        assertIs<Res.Fail<String>>(result)
        assertEquals("HTTP 404", result.error)
    }

    // -- ensure --

    @Test
    fun `ensure passes on true condition`() = runTest {
        val result = rail<Int, String> {
            ensure(true) { "should not happen" }
            42
        }
        assertIs<Res.Ok<Int>>(result)
        assertEquals(42, result.value)
    }

    @Test
    fun `ensure fails on false condition`() = runTest {
        val result = rail<Int, String> {
            ensure(false) { "validation failed" }
            42
        }
        assertIs<Res.Fail<String>>(result)
        assertEquals("validation failed", result.error)
    }

    // -- ensureNotNull --

    @Test
    fun `ensureNotNull returns value when not null`() = runTest {
        val result = rail<Int, String> {
            val x = ensureNotNull(42) { "was null" }
            x + 1
        }
        assertIs<Res.Ok<Int>>(result)
        assertEquals(43, result.value)
    }

    @Test
    fun `ensureNotNull fails on null`() = runTest {
        val result = rail<Int, String> {
            val x = ensureNotNull<Int>(null) { "was null" }
            x + 1
        }
        assertIs<Res.Fail<String>>(result)
        assertEquals("was null", result.error)
    }

    // -- suspend --

    @Test
    fun `rail works with suspend calls`() = runTest {
        suspend fun fetchValue(): Res<Int, String> {
            delay(1)
            return ok(42)
        }

        val result = rail<Int, String> {
            val x = fetchValue().orFail()
            x * 2
        }
        assertIs<Res.Ok<Int>>(result)
        assertEquals(84, result.value)
    }

    @Test
    fun `rail works with suspend calls that return errors`() = runTest {
        suspend fun fetchValue(): Res<Int, String> {
            delay(1)
            return failure("remote failure")
        }

        val result = rail<Int, String> {
            val x = fetchValue().orFail()
            x * 2
        }
        assertIs<Res.Fail<String>>(result)
        assertEquals("remote failure", result.error)
    }

    @Test
    fun `chained orFail calls short-circuit on first error`() = runTest {
        val result = rail<Int, String> {
            val a = Res.Ok(1).orFail()
            val b: Int = Res.Fail("fail at b").orFail()
            @Suppress("UNREACHABLE_CODE")
            a + b
        }
        assertIs<Res.Fail<String>>(result)
        assertEquals("fail at b", result.error)
    }

    // -- non-local returns --

    private fun syncNonLocalReturn(id: Int): Res<Int, String> {
        val result = rail<Int, String> {
            if (id < 0) return Res.Fail("negative")
            id * 2
        }
        return result
    }

    @Test
    fun `non-local return works in sync context - early return`() {
        val result = syncNonLocalReturn(-1)
        assertIs<Res.Fail<String>>(result)
        assertEquals("negative", result.error)
    }

    @Test
    fun `non-local return works in sync context - normal path`() {
        val result = syncNonLocalReturn(5)
        assertIs<Res.Ok<Int>>(result)
        assertEquals(10, result.value)
    }

    private suspend fun suspendNonLocalReturn(id: Int): Res<Int, String> {
        val result = rail<Int, String> {
            if (id < 0) return Res.Fail("negative")
            delay(1)
            id * 2
        }
        return result
    }

    @Test
    fun `non-local return works in suspend context - early return`() = runTest {
        val result = suspendNonLocalReturn(-1)
        assertIs<Res.Fail<String>>(result)
        assertEquals("negative", result.error)
    }

    @Test
    fun `non-local return works in suspend context - normal path`() = runTest {
        val result = suspendNonLocalReturn(5)
        assertIs<Res.Ok<Int>>(result)
        assertEquals(10, result.value)
    }

    private fun failMappingRailNonLocalReturn(id: Int): Res<Int, String> {
        val appRail = FailMappingRail<String> { e -> "Error: ${e.message}" }
        val result = appRail {
            if (id < 0) return Res.Fail("negative")
            id * 2
        }
        return result
    }

    @Test
    fun `non-local return works in FailMappingRail top-level invoke`() {
        val result = failMappingRailNonLocalReturn(-1)
        assertIs<Res.Fail<String>>(result)
        assertEquals("negative", result.error)
    }

}
