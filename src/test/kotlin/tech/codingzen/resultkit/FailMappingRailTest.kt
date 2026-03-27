package tech.codingzen.resultkit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FailMappingRailTest {

    // -- top-level invoke (returns Res) --

    @Test
    fun `invoke catches exception and maps to error type`() {
        val appRes = FailMappingRail<String> { e -> "Error: ${e.message}" }
        val result: Res<Int, String> = appRes { throw RuntimeException("boom") }
        assertTrue(result.isFail)
        assertEquals("Error: boom", result.error)
    }

    @Test
    fun `invoke returns Ok on success`() {
        val appRes = FailMappingRail<String> { e -> "Error: ${e.message}" }
        val result = appRes { 42 }
        assertTrue(result.isOk)
        assertEquals(42, result.value)
    }

    @Test
    fun `railway operations work inside invoke`() {
        val appRes = FailMappingRail<String> { e -> "Error: ${e.message}" }

        val result = appRes {
            val x = ok(10).orFail()
            ensure(x > 0) { "must be positive" }
            x + 5
        }
        assertTrue(result.isOk)
        assertEquals(15, result.value)
    }

    @Test
    fun `fail short-circuits inside invoke`() {
        val appRes = FailMappingRail<String> { e -> "Error: ${e.message}" }

        val result: Res<Int, String> = appRes {
            fail("explicit failure")
        }
        assertTrue(result.isFail)
        assertEquals("explicit failure", result.error)
    }

    @Test
    fun `orFail short-circuits inside invoke`() {
        val appRes = FailMappingRail<String> { e -> "Error: ${e.message}" }

        val result = appRes {
            val x: Int = failure("not found").orFail()
            x + 1
        }
        assertTrue(result.isFail)
        assertEquals("not found", result.error)
    }

    @Test
    fun `rethrows CancellationException`() {
        val appRes = FailMappingRail<String> { e -> "Error: ${e.message}" }
        assertFailsWith<CancellationException> {
            @Suppress("UNUSED_VARIABLE")
            val unused: Res<Int, String> = appRes { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `works with suspend lambdas`() = runTest {
        val appRes = FailMappingRail<String> { e -> "Error: ${e.message}" }

        val result = appRes {
            delay(1)
            42
        }
        assertTrue(result.isOk)
        assertEquals(42, result.value)
    }

    @Test
    fun `reusable across multiple calls`() {
        val appRes = FailMappingRail<String> { e -> "Error: ${e.message}" }

        val r1 = appRes { 1 }
        val r2: Res<Int, String> = appRes { throw IllegalStateException("fail") }
        val r3 = appRes { 3 }

        assertTrue(r1.isOk)
        assertEquals(1, r1.value)

        assertTrue(r2.isFail)
        assertEquals("Error: fail", r2.error)

        assertTrue(r3.isOk)
        assertEquals(3, r3.value)
    }

    @Test
    fun `constructor creates FailMappingRail`() {
        val appRail = FailMappingRail<String> { e -> "Error: ${e.message}" }

        val result: Res<Int, String> = appRail { throw RuntimeException("boom") }
        assertTrue(result.isFail)
        assertEquals("Error: boom", result.error)
    }

    // -- member extension invoke (inside res {}, returns V) --

    @Test
    fun `member extension catches exception and short-circuits outer scope`() {
        val result = rail<Int, String> {
            val io = failMapping { e -> "Error: ${e.message}" }
            io { throw RuntimeException("boom") }
        }
        assertTrue(result.isFail)
        assertEquals("Error: boom", result.error)
    }

    @Test
    fun `member extension returns unwrapped value on success`() {
        val result = rail<Int, String> {
            val io = failMapping { e -> "Error: ${e.message}" }
            val x: Int = io { 42 }
            x + 1
        }
        assertTrue(result.isOk)
        assertEquals(43, result.value)
    }

    @Test
    fun `member extension is reusable across multiple calls`() {
        val result = rail<Int, String> {
            val io = failMapping { e -> "Error: ${e.message}" }
            val a: Int = io { 10 }
            val b: Int = io { 20 }
            a + b
        }
        assertTrue(result.isOk)
        assertEquals(30, result.value)
    }

    @Test
    fun `member extension short-circuits on first exception`() {
        val result = rail<Int, String> {
            val io = failMapping { e -> "Error: ${e.message}" }
            val a: Int = io { 10 }
            io { throw RuntimeException("fail at b") }
            @Suppress("UNREACHABLE_CODE")
            a + 1
        }
        assertTrue(result.isFail)
        assertEquals("Error: fail at b", result.error)
    }

    @Test
    fun `member extension rethrows CancellationException`() {
        assertFailsWith<CancellationException> {
            rail<Int, String> {
                val io = failMapping { e -> "Error: ${e.message}" }
                io { throw CancellationException("cancelled") }
            }
        }
    }

    @Test
    fun `member extension works with suspend lambdas`() = runTest {
        val result = rail<Int, String> {
            val io = failMapping { e -> "Error: ${e.message}" }
            val x: Int = io {
                delay(1)
                42
            }
            x
        }
        assertTrue(result.isOk)
        assertEquals(42, result.value)
    }
}
