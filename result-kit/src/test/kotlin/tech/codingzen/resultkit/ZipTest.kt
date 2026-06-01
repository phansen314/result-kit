package tech.codingzen.resultkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZipTest {

    // -- zip arity 2 --

    @Test
    fun `zip2 all Ok transforms values`() {
        val result = zip(
            { Res.ok(1) },
            { Res.ok("a") },
        ) { a, b -> "$a$b" }
        assertTrue(result.isOk)
        assertEquals("1a", result.getOrNull())
    }

    @Test
    fun `zip2 first Fail short-circuits`() {
        var secondCalled = false
        val result = zip(
            { Res.failure("err1") },
            { secondCalled = true; Res.ok(2) },
        ) { a: Int, b: Int -> a + b }
        assertTrue(result.isFail)
        assertEquals("err1", result.errorOrNull())
        assertFalse(secondCalled)
    }

    @Test
    fun `zip2 second Fail returns second error`() {
        val result = zip(
            { Res.ok(1) },
            { Res.failure("err2") },
        ) { a: Int, b: Int -> a + b }
        assertTrue(result.isFail)
        assertEquals("err2", result.errorOrNull())
    }

    // -- zip arity 3 --

    @Test
    fun `zip3 all Ok transforms values`() {
        val result = zip(
            { Res.ok(1) },
            { Res.ok(2) },
            { Res.ok(3) },
        ) { a, b, c -> a + b + c }
        assertTrue(result.isOk)
        assertEquals(6, result.getOrNull())
    }

    @Test
    fun `zip3 middle Fail short-circuits`() {
        var thirdCalled = false
        val result = zip(
            { Res.ok(1) },
            { Res.failure("err2") },
            { thirdCalled = true; Res.ok(3) },
        ) { a: Int, b: Int, c: Int -> a + b + c }
        assertTrue(result.isFail)
        assertEquals("err2", result.errorOrNull())
        assertFalse(thirdCalled)
    }

    // -- zip arity 4 --

    @Test
    fun `zip4 all Ok transforms values`() {
        val result = zip(
            { Res.ok(1) },
            { Res.ok(2) },
            { Res.ok(3) },
            { Res.ok(4) },
        ) { a, b, c, d -> a + b + c + d }
        assertTrue(result.isOk)
        assertEquals(10, result.getOrNull())
    }

    @Test
    fun `zip4 first Fail short-circuits all remaining`() {
        var count = 0
        val result = zip(
            { Res.failure("err1") },
            { count++; Res.ok(2) },
            { count++; Res.ok(3) },
            { count++; Res.ok(4) },
        ) { a: Int, b: Int, c: Int, d: Int -> a + b + c + d }
        assertTrue(result.isFail)
        assertEquals("err1", result.errorOrNull())
        assertEquals(0, count)
    }
}
