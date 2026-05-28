package tech.codingzen.resultkit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScopeIsolationTest {

    // -- nested rail {} --

    @Test
    fun `inner rail fail does not leak to outer rail`() {
        val result = rail<Int, String> {
            val inner = rail<Int, String> { fail("inner") }
            assertTrue(inner.isFail)
            assertEquals("inner", inner.errorOrThrow())
            42
        }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `inner rail fail propagated via orFail short-circuits outer`() {
        val result = rail<Int, String> {
            val inner = rail<Int, String> { fail("inner") }
            inner.orFail()
        }
        assertTrue(result.isFail)
        assertEquals("inner", result.errorOrThrow())
    }

    @Test
    fun `inner rail Ok is accessible in outer`() {
        val result = rail<Int, String> {
            val inner = rail<Int, String> { 10 }
            inner.orFail() + 5
        }
        assertTrue(result.isOk)
        assertEquals(15, result.getOrNull())
    }

    // -- ExceptionMappingRail top-level invoke (outside rail {}) --

    @Test
    fun `ExceptionMappingRail top-level invoke captures fail as Res Fail`() {
        val appRail = ExceptionMappingRail<String> { e -> "Error: ${e.message}" }
        val result: Res<Int, String> = appRail { fail("inner") }
        assertTrue(result.isFail)
        assertEquals("inner", result.errorOrThrow())
    }

    @Test
    fun `ExceptionMappingRail top-level invoke captures exception as Res Fail`() {
        val appRail = ExceptionMappingRail<String> { e -> "Caught: ${e.message}" }
        val result: Res<Int, String> = appRail { throw RuntimeException("boom") }
        assertTrue(result.isFail)
        assertEquals("Caught: boom", result.errorOrThrow())
    }

    // -- ExceptionMappingRail inside rail {} uses member extension (short-circuits outer) --

    @Test
    fun `ExceptionMappingRail inside rail - fail short-circuits outer rail`() {
        val appRail = ExceptionMappingRail<String> { e -> "Error: ${e.message}" }
        val result = rail<Int, String> {
            // member extension wins — fail() short-circuits outer rail
            appRail { fail("inner") }
        }
        assertTrue(result.isFail)
        assertEquals("inner", result.errorOrThrow())
    }

    @Test
    fun `ExceptionMappingRail inside rail - exception short-circuits outer rail`() {
        val appRail = ExceptionMappingRail<String> { e -> "Caught: ${e.message}" }
        val result = rail<Int, String> {
            // member extension wins — exception mapped and short-circuits outer rail
            appRail { throw RuntimeException("boom") }
        }
        assertTrue(result.isFail)
        assertEquals("Caught: boom", result.errorOrThrow())
    }

    // -- nested ExceptionMappingRail member extension invoke --

    @Test
    fun `catching exception short-circuits outer rail not inner`() {
        val result = rail<Int, String> {
            val io = catching { e -> "IO: ${e.message}" }
            io { throw RuntimeException("disk fail") }
        }
        assertTrue(result.isFail)
        assertEquals("IO: disk fail", result.errorOrThrow())
    }

    @Test
    fun `catching success returns value in outer rail`() {
        val result = rail<Int, String> {
            val io = catching { e -> "IO: ${e.message}" }
            val x = io { 10 }
            x + 5
        }
        assertTrue(result.isOk)
        assertEquals(15, result.getOrNull())
    }

    // -- inner rail FailException passthrough in member extension --

    @Test
    fun `inner rail FailException passes through catching member extension`() {
        val result = rail<Int, String> {
            val io = catching { e -> "IO: ${e.message}" }
            io {
                // nested rail's FailException is a Throwable, not Exception —
                // must pass through the member extension's catch(Exception) unimpeded
                val inner = rail<Int, String> { fail("inner error") }
                inner.orFail()
            }
        }
        assertTrue(result.isFail)
        assertEquals("inner error", result.errorOrThrow())
    }

    // -- suspend context --

    @Test
    fun `nested rail works in suspend context`() = runTest {
        val result = rail<Int, String> {
            val inner = rail<Int, String> { fail("inner") }
            inner.orFail()
        }
        assertTrue(result.isFail)
        assertEquals("inner", result.errorOrThrow())
    }

    // -- CancellationException passes through all scopes --

    @Test
    fun `CancellationException in nested rail propagates through`() {
        assertFailsWith<CancellationException> {
            rail<Int, String> {
                rail<Int, String> {
                    throw CancellationException("cancelled")
                }
                42
            }
        }
    }

    // -- cross-scope error type isolation --

    @Test
    fun `nested rail with different error types isolates scopes`() {
        val result = rail<Int, String> {
            val inner: Res<Int, Int> = rail<Int, Int> { fail(404) }
            inner.orFail { code -> "HTTP $code" }
        }
        assertTrue(result.isFail)
        assertEquals("HTTP 404", result.errorOrThrow())
    }

    @Test
    fun `ExceptionMappingRail top-level invoke nested inside rail isolates scopes`() {
        val appRail = ExceptionMappingRail<Int> { e -> e.message?.length ?: 0 }
        val result = rail<Int, String> {
            val inner: Res<Int, Int> = appRail { fail(99) }
            // inner rail's fail(99) is caught by appRail's scope, not the outer rail
            inner.orFail { code -> "code: $code" }
        }
        assertTrue(result.isFail)
        assertEquals("code: 99", result.errorOrThrow())
    }

    @Test
    fun `CancellationException in ExceptionMappingRail propagates through`() {
        val appRail = ExceptionMappingRail<String> { e -> "Error: ${e.message}" }
        assertFailsWith<CancellationException> {
            rail<Int, String> {
                val inner: Res<Int, String> = appRail { throw CancellationException("cancelled") }
                42
            }
        }
    }
}
