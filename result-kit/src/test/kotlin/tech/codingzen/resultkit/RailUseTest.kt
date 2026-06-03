package tech.codingzen.resultkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RailUseTest {

    private class TestResource(val name: String, val log: MutableList<String>) : AutoCloseable {
        var closed = false
            private set

        override fun close() {
            closed = true
            log.add("close $name")
        }
    }

    @Test
    fun `use closes the resource on Ok completion`() {
        val r = TestResource("a", mutableListOf())
        val result = rail<Int, String> {
            use(r) { 42 }
        }
        assertEquals(42, result.getOrNull())
        assertTrue(r.closed)
    }

    @Test
    fun `use closes the resource on fail short-circuit`() {
        val r = TestResource("a", mutableListOf())
        val result = rail<Int, String> {
            use(r) { fail("boom") }
        }
        assertTrue(result.isFail)
        assertEquals("boom", result.errorOrThrow())
        assertTrue(r.closed)
    }

    @Test
    fun `use closes the resource on orFail short-circuit`() {
        val r = TestResource("a", mutableListOf())
        val failing: Res<Int, String> = Res.failure("nope")
        val result = rail<Int, String> {
            use(r) { failing.orFail() }
        }
        assertTrue(result.isFail)
        assertTrue(r.closed)
    }

    @Test
    fun `use closes the resource on a thrown exception, then rethrows`() {
        val r = TestResource("a", mutableListOf())
        assertFailsWith<IllegalStateException> {
            rail<Int, String> {
                use(r) { throw IllegalStateException("x") }
            }
        }
        assertTrue(r.closed)
    }

    @Test
    fun `use returns the block value`() {
        val r = TestResource("a", mutableListOf())
        val result = rail<Int, String> {
            val x = use(r) { 10 }
            x + 5
        }
        assertEquals(15, result.getOrNull())
    }

    @Test
    fun `use exposes the resource to the block`() {
        val r = TestResource("named", mutableListOf())
        val result = rail<String, String> {
            use(r) { res -> res.name }
        }
        assertEquals("named", result.getOrNull())
    }

    @Test
    fun `nested use closes in reverse order, innermost first`() {
        val log = mutableListOf<String>()
        val outer = TestResource("outer", log)
        val inner = TestResource("inner", log)
        rail<Int, String> {
            use(outer) {
                use(inner) { 1 }
            }
        }
        assertEquals(listOf("close inner", "close outer"), log)
    }
}
