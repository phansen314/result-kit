package tech.codingzen.resultkit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ScopeIsolationTest {

    // -- nested rail {} --

    @Test
    fun `inner rail fail does not leak to outer rail`() {
        val result = rail<Int, String> {
            val inner = rail<Int, String> { fail("inner") }
            assertIs<Res.Fail<String>>(inner)
            assertEquals("inner", inner.error)
            42
        }
        assertIs<Res.Ok<Int>>(result)
        assertEquals(42, result.value)
    }

    @Test
    fun `inner rail fail propagated via orFail short-circuits outer`() {
        val result = rail<Int, String> {
            val inner = rail<Int, String> { fail("inner") }
            inner.orFail()
        }
        assertIs<Res.Fail<String>>(result)
        assertEquals("inner", result.error)
    }

    @Test
    fun `inner rail Ok is accessible in outer`() {
        val result = rail<Int, String> {
            val inner = rail<Int, String> { 10 }
            inner.orFail() + 5
        }
        assertIs<Res.Ok<Int>>(result)
        assertEquals(15, result.value)
    }

    // -- FailMappingRail top-level invoke (outside rail {}) --

    @Test
    fun `FailMappingRail top-level invoke captures fail as Res Fail`() {
        val appRail = FailMappingRail<String> { e -> "Error: ${e.message}" }
        val result: Res<Int, String> = appRail { fail("inner") }
        assertIs<Res.Fail<String>>(result)
        assertEquals("inner", result.error)
    }

    @Test
    fun `FailMappingRail top-level invoke captures exception as Res Fail`() {
        val appRail = FailMappingRail<String> { e -> "Caught: ${e.message}" }
        val result: Res<Int, String> = appRail { throw RuntimeException("boom") }
        assertIs<Res.Fail<String>>(result)
        assertEquals("Caught: boom", result.error)
    }

    // -- FailMappingRail inside rail {} uses member extension (short-circuits outer) --

    @Test
    fun `FailMappingRail inside rail - fail short-circuits outer rail`() {
        val appRail = FailMappingRail<String> { e -> "Error: ${e.message}" }
        val result = rail<Int, String> {
            // member extension wins — fail() short-circuits outer rail
            appRail { fail("inner") }
        }
        assertIs<Res.Fail<String>>(result)
        assertEquals("inner", result.error)
    }

    @Test
    fun `FailMappingRail inside rail - exception short-circuits outer rail`() {
        val appRail = FailMappingRail<String> { e -> "Caught: ${e.message}" }
        val result = rail<Int, String> {
            // member extension wins — exception mapped and short-circuits outer rail
            appRail { throw RuntimeException("boom") }
        }
        assertIs<Res.Fail<String>>(result)
        assertEquals("Caught: boom", result.error)
    }

    // -- nested FailMappingRail member extension invoke --

    @Test
    fun `failMapping exception short-circuits outer rail not inner`() {
        val result = rail<Int, String> {
            val io = failMapping { e -> "IO: ${e.message}" }
            io { throw RuntimeException("disk fail") }
        }
        assertIs<Res.Fail<String>>(result)
        assertEquals("IO: disk fail", result.error)
    }

    @Test
    fun `failMapping success returns value in outer rail`() {
        val result = rail<Int, String> {
            val io = failMapping { e -> "IO: ${e.message}" }
            val x = io { 10 }
            x + 5
        }
        assertIs<Res.Ok<Int>>(result)
        assertEquals(15, result.value)
    }

    // -- suspend context --

    @Test
    fun `nested rail works in suspend context`() = runTest {
        val result = rail<Int, String> {
            val inner = rail<Int, String> { fail("inner") }
            inner.orFail()
        }
        assertIs<Res.Fail<String>>(result)
        assertEquals("inner", result.error)
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

    @Test
    fun `CancellationException in FailMappingRail propagates through`() {
        val appRail = FailMappingRail<String> { e -> "Error: ${e.message}" }
        assertFailsWith<CancellationException> {
            rail<Int, String> {
                val inner: Res<Int, String> = appRail { throw CancellationException("cancelled") }
                42
            }
        }
    }
}
