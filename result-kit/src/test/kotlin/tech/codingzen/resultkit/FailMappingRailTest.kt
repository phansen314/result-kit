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

    // -- Rail.failMapping companion factory --

    @Test
    fun `Rail companion failMapping creates equivalent FailMappingRail`() {
        val appRes = Rail.failMapping { e -> "Error: ${e.message}" }
        val result: Res<Int, String> = appRes { throw RuntimeException("boom") }
        assertTrue(result.isFail)
        assertEquals("Error: boom", result.errorOrThrow())
    }

    // -- top-level invoke (returns Res) --

    @Test
    fun `invoke catches exception and maps to error type`() {
        val appRes = FailMappingRail<String> { e -> "Error: ${e.message}" }
        val result: Res<Int, String> = appRes { throw RuntimeException("boom") }
        assertTrue(result.isFail)
        assertEquals("Error: boom", result.errorOrThrow())
    }

    @Test
    fun `invoke returns Ok on success`() {
        val appRes = FailMappingRail<String> { e -> "Error: ${e.message}" }
        val result = appRes { 42 }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `railway operations work inside invoke`() {
        val appRes = FailMappingRail<String> { e -> "Error: ${e.message}" }

        val result = appRes {
            val x = Res.ok(10).orFail()
            ensure(x > 0) { "must be positive" }
            x + 5
        }
        assertTrue(result.isOk)
        assertEquals(15, result.getOrNull())
    }

    @Test
    fun `fail short-circuits inside invoke`() {
        val appRes = FailMappingRail<String> { e -> "Error: ${e.message}" }

        val result: Res<Int, String> = appRes {
            fail("explicit failure")
        }
        assertTrue(result.isFail)
        assertEquals("explicit failure", result.errorOrThrow())
    }

    @Test
    fun `orFail short-circuits inside invoke`() {
        val appRes = FailMappingRail<String> { e -> "Error: ${e.message}" }

        val result = appRes {
            val x: Int = Res.failure("not found").orFail()
            x + 1
        }
        assertTrue(result.isFail)
        assertEquals("not found", result.errorOrThrow())
    }

    @Test
    fun `Error propagates through without being caught`() {
        val error = object : Error("fatal") {}
        assertFailsWith<Error> {
            val appRes = FailMappingRail<String> { e -> "Error: ${e.message}" }
            appRes { throw error }
        }
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
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `reusable across multiple calls`() {
        val appRes = FailMappingRail<String> { e -> "Error: ${e.message}" }

        val r1 = appRes { 1 }
        val r2: Res<Int, String> = appRes { throw IllegalStateException("fail") }
        val r3 = appRes { 3 }

        assertTrue(r1.isOk)
        assertEquals(1, r1.getOrNull())

        assertTrue(r2.isFail)
        assertEquals("Error: fail", r2.errorOrThrow())

        assertTrue(r3.isOk)
        assertEquals(3, r3.getOrNull())
    }

    @Test
    fun `constructor creates FailMappingRail`() {
        val appRail = FailMappingRail<String> { e -> "Error: ${e.message}" }

        val result: Res<Int, String> = appRail { throw RuntimeException("boom") }
        assertTrue(result.isFail)
        assertEquals("Error: boom", result.errorOrThrow())
    }

    // -- member extension invoke (inside res {}, returns V) --

    @Test
    fun `member extension catches exception and short-circuits outer scope`() {
        val result = rail<Int, String> {
            val io = failMapping { e -> "Error: ${e.message}" }
            io { throw RuntimeException("boom") }
        }
        assertTrue(result.isFail)
        assertEquals("Error: boom", result.errorOrThrow())
    }

    @Test
    fun `member extension returns unwrapped value on success`() {
        val result = rail<Int, String> {
            val io = failMapping { e -> "Error: ${e.message}" }
            val x: Int = io { 42 }
            x + 1
        }
        assertTrue(result.isOk)
        assertEquals(43, result.getOrNull())
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
        assertEquals(30, result.getOrNull())
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
        assertEquals("Error: fail at b", result.errorOrThrow())
    }

    @Test
    fun `member extension does not map fail calls`() {
        val result = rail<Int, String> {
            val io = failMapping { e -> "Mapped: ${e.message}" }
            io { fail("raw error") }
        }
        assertTrue(result.isFail)
        assertEquals("raw error", result.errorOrThrow())
    }

    @Test
    fun `member extension Error propagates through without being caught`() {
        val error = object : Error("fatal") {}
        assertFailsWith<Error> {
            rail<Int, String> {
                val io = failMapping { e -> "Error: ${e.message}" }
                io { throw error }
            }
        }
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
        assertEquals(42, result.getOrNull())
    }

    // -- ErrorMapperException --

    @Test
    fun `top-level invoke wraps mapper exception in ErrorMapperException`() {
        val badMapper = FailMappingRail<String> { throw IllegalStateException("mapper broke") }
        val ex = assertFailsWith<ErrorMapperException> {
            badMapper { throw RuntimeException("original") }
        }
        assertIs<IllegalStateException>(ex.cause)
        assertEquals("mapper broke", ex.cause!!.message)
        assertIs<RuntimeException>(ex.originalException)
        assertEquals("original", ex.originalException.message)
    }

    @Test
    fun `member extension wraps mapper exception in ErrorMapperException`() {
        val ex = assertFailsWith<ErrorMapperException> {
            rail<Int, String> {
                val io = failMapping { throw IllegalStateException("mapper broke") }
                io { throw RuntimeException("original") }
            }
        }
        assertIs<IllegalStateException>(ex.cause)
        assertEquals("mapper broke", ex.cause!!.message)
        assertIs<RuntimeException>(ex.originalException)
        assertEquals("original", ex.originalException.message)
    }
}
