package tech.codingzen.resultkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ResTest {

    // -- ok / failure factories --

    @Test
    fun `ok creates Ok`() {
        val result = ok(42)
        assertTrue(result.isOk)
        assertEquals(42, result.value)
    }

    @Test
    fun `failure creates Fail`() {
        val result = failure("err")
        assertTrue(result.isFail)
        assertEquals("err", result.error)
    }

    // -- fold --

    @Test
    fun `fold calls onOk for Ok`() {
        val result = ok(10).fold({ it * 2 }, { -1 })
        assertEquals(20, result)
    }

    @Test
    fun `fold calls onFail for Fail`() {
        val result = failure("err").fold({ -1 }, { it.length })
        assertEquals(3, result)
    }

    // -- map --

    @Test
    fun `map transforms Ok value`() {
        val result = ok(5).map { it * 3 }
        assertTrue(result.isOk)
        assertEquals(15, result.value)
    }

    @Test
    fun `map passes through Fail`() {
        val original = failure("err")
        val result = original.map { 42 }
        assertTrue(result.isFail)
        assertEquals("err", result.error)
        assertEquals(original, result)
    }

    // -- mapError --

    @Test
    fun `mapError transforms Fail error`() {
        val result = failure("err").mapError { it.length }
        assertTrue(result.isFail)
        assertEquals(3, result.error)
    }

    @Test
    fun `mapError passes through Ok`() {
        val original = ok(42)
        val result = original.mapError { "mapped" }
        assertTrue(result.isOk)
        assertEquals(42, result.value)
        assertEquals(original, result)
    }

    // -- getOrElse --

    @Test
    fun `getOrElse returns value for Ok`() {
        val result = ok(42).getOrElse { -1 }
        assertEquals(42, result)
    }

    @Test
    fun `getOrElse returns default for Fail`() {
        val result = failure("err").getOrElse { it.length }
        assertEquals(3, result)
    }

    // -- onOk --

    @Test
    fun `onOk calls action for Ok and returns self`() {
        var called = false
        val original = ok(42)
        val result = original.onOk { called = true }
        assertTrue(called)
        assertEquals(original, result)
    }

    @Test
    fun `onOk does not call action for Fail`() {
        var called = false
        val original = failure("err")
        val result = original.onOk { called = true }
        assertFalse(called)
        assertEquals(original, result)
    }

    // -- onFail --

    @Test
    fun `onFail calls action for Fail and returns self`() {
        var called = false
        val original = failure("err")
        val result = original.onFail { called = true }
        assertTrue(called)
        assertEquals(original, result)
    }

    @Test
    fun `onFail does not call action for Ok`() {
        var called = false
        val original = ok(42)
        val result = original.onFail { called = true }
        assertFalse(called)
        assertEquals(original, result)
    }

    // -- getOrThrow --

    @Test
    fun `getOrThrow returns value for Ok`() {
        val result: Res<Int, RuntimeException> = ok(42)
        assertEquals(42, result.getOrThrow())
    }

    @Test
    fun `getOrThrow throws error for Fail`() {
        val result: Res<Int, RuntimeException> = failure(RuntimeException("boom"))
        val ex = assertFailsWith<RuntimeException> { result.getOrThrow() }
        assertEquals("boom", ex.message)
    }

    // -- getOrThrow with transform --

    @Test
    fun `getOrThrow with transform returns value for Ok`() {
        val result = ok(42).getOrThrow { RuntimeException(it.toString()) }
        assertEquals(42, result)
    }

    @Test
    fun `getOrThrow with transform throws transformed error for Fail`() {
        val result = failure("err")
        val ex = assertFailsWith<IllegalStateException> {
            result.getOrThrow { IllegalStateException(it) }
        }
        assertEquals("err", ex.message)
    }

    // -- valueOrNull / errorOrNull --

    @Test
    fun `valueOrNull returns value for Ok`() {
        assertEquals(42, ok(42).valueOrNull)
    }

    @Test
    fun `valueOrNull returns null for Fail`() {
        assertEquals(null, failure("err").valueOrNull)
    }

    @Test
    fun `errorOrNull returns error for Fail`() {
        assertEquals("err", failure("err").errorOrNull)
    }

    @Test
    fun `errorOrNull returns null for Ok`() {
        assertEquals(null, ok(42).errorOrNull)
    }

    // -- recover --

    @Test
    fun `recover transforms Fail to Ok`() {
        val result = failure("err").recover { it.length }
        assertTrue(result.isOk)
        assertEquals(3, result.value)
    }

    @Test
    fun `recover passes through Ok`() {
        val result = ok(42).recover { -1 }
        assertTrue(result.isOk)
        assertEquals(42, result.value)
    }

    // -- null edge cases (tagged union invariants) --

    @Test
    fun `ok null is Ok`() {
        val result = ok(null)
        assertTrue(result.isOk)
        assertFalse(result.isFail)
        assertEquals(null, result.value)
    }

    @Test
    fun `failure null is Fail`() {
        val result = failure(null)
        assertTrue(result.isFail)
        assertFalse(result.isOk)
        assertEquals(null, result.error)
    }

    @Test
    fun `ok null not equal to failure null`() {
        assertFalse(ok(null).equals(failure(null)))
    }

    @Test
    fun `ok null toString`() {
        assertEquals("Ok(null)", ok(null).toString())
    }

    @Test
    fun `failure null toString`() {
        assertEquals("Fail(null)", failure(null).toString())
    }

    // -- equals / hashCode / toString --

    @Test
    fun `Ok equals and hashCode`() {
        assertEquals(ok(42), ok(42))
        assertEquals(ok(42).hashCode(), ok(42).hashCode())
        assertFalse(ok(42).equals(ok(99)))
        assertFalse(ok(42).equals(failure(42)))
    }

    @Test
    fun `Fail equals and hashCode`() {
        assertEquals(failure("err"), failure("err"))
        assertEquals(failure("err").hashCode(), failure("err").hashCode())
        assertFalse(failure("a").equals(failure("b")))
        assertFalse(failure(42).equals(ok(42)))
    }

    @Test
    fun `toString formats correctly`() {
        assertEquals("Ok(42)", ok(42).toString())
        assertEquals("Fail(err)", failure("err").toString())
    }
}
