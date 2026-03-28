package tech.codingzen.resultkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResExtensionsTest {

    // -- toResOr --

    @Test
    fun `toResOr returns Ok for non-null value`() {
        val result: Res<Int, String> = 42.toResOr { "missing" }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `toResOr returns Fail for null`() {
        val result: Res<Int, String> = null.toResOr { "missing" }
        assertTrue(result.isFail)
        assertEquals("missing", result.errorOrNull())
    }

    @Test
    fun `toResOr does not invoke error lambda for non-null`() {
        var called = false
        42.toResOr { called = true; "err" }
        assertFalse(called)
    }

    // -- component1 / component2 (destructuring) --

    @Test
    fun `destructuring Ok gives value and null error`() {
        val (value, error) = Res.ok(42)
        assertEquals(42, value)
        assertNull(error)
    }

    @Test
    fun `destructuring Fail gives null value and error`() {
        val (value, error) = Res.failure("oops")
        assertNull(value)
        assertEquals("oops", error)
    }

    @Test
    fun `destructuring Ok with null value`() {
        val (value, error) = Res.ok<Nothing?>(null)
        assertNull(value)
        assertNull(error)
    }

    @Test
    fun `destructuring Fail with null error`() {
        val (value, error) = Res.failure<Nothing?>(null)
        assertNull(value)
        assertNull(error)
    }

    // -- toFailIf --

    @Test
    fun `toFailIf converts Ok to Fail when predicate matches`() {
        val result = Res.ok(0).toFailIf({ it == 0 }) { "cannot be zero" }
        assertTrue(result.isFail)
        assertEquals("cannot be zero", result.errorOrNull())
    }

    @Test
    fun `toFailIf keeps Ok when predicate does not match`() {
        val result = Res.ok(42).toFailIf({ it == 0 }) { "cannot be zero" }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `toFailIf passes through Fail unchanged`() {
        val result: Res<Int, String> = Res.failure("original").toFailIf({ true }) { "replaced" }
        assertTrue(result.isFail)
        assertEquals("original", result.errorOrNull())
    }

    // -- toFailUnless --

    @Test
    fun `toFailUnless converts Ok to Fail when predicate does not match`() {
        val result = Res.ok(-1).toFailUnless({ it > 0 }) { "must be positive" }
        assertTrue(result.isFail)
        assertEquals("must be positive", result.errorOrNull())
    }

    @Test
    fun `toFailUnless keeps Ok when predicate matches`() {
        val result = Res.ok(5).toFailUnless({ it > 0 }) { "must be positive" }
        assertTrue(result.isOk)
        assertEquals(5, result.getOrNull())
    }

    @Test
    fun `toFailUnless passes through Fail unchanged`() {
        val result: Res<Int, String> = Res.failure("original").toFailUnless({ false }) { "replaced" }
        assertTrue(result.isFail)
        assertEquals("original", result.errorOrNull())
    }

    // -- transpose --

    @Test
    fun `transpose Ok with non-null value returns Ok`() {
        val res: Res<Int?, String> = Res.ok(42)
        val transposed: Res<Int, String>? = res.transpose()
        assertFalse(transposed == null)
        assertTrue(transposed.isOk)
        assertEquals(42, transposed.getOrNull())
    }

    @Test
    fun `transpose Ok with null returns null`() {
        val res: Res<Int?, String> = Res.ok(null)
        val transposed = res.transpose()
        assertNull(transposed)
    }

    @Test
    fun `transpose Fail returns Fail`() {
        val res: Res<Int?, String> = Res.failure("err")
        val transposed = res.transpose()
        assertFalse(transposed == null)
        assertTrue(transposed.isFail)
        assertEquals("err", transposed.errorOrNull())
    }

    // -- flatten --

    @Test
    fun `flatten Ok of Ok returns inner Ok`() {
        val nested: Res<Res<Int, String>, String> = Res.ok(Res.ok(42))
        val flat = nested.flatten()
        assertTrue(flat.isOk)
        assertEquals(42, flat.getOrNull())
    }

    @Test
    fun `flatten Ok of Fail returns inner Fail`() {
        val nested: Res<Res<Int, String>, String> = Res.ok(Res.failure("inner"))
        val flat = nested.flatten()
        assertTrue(flat.isFail)
        assertEquals("inner", flat.errorOrNull())
    }

    @Test
    fun `flatten Fail returns outer Fail`() {
        val nested: Res<Res<Int, String>, String> = Res.failure("outer")
        val flat = nested.flatten()
        assertTrue(flat.isFail)
        assertEquals("outer", flat.errorOrNull())
    }
}
