package tech.codingzen.resultkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValidatorTest {

    // -- ensure --

    @Test
    fun `ensure with passing condition adds no errors`() {
        val v = Validator.validator<String>()
        v.ensure(true) { "should not appear" }
        assertFalse(v.hasErrors)
        assertEquals(emptyList(), v.errors())
    }

    @Test
    fun `ensure with failing condition adds the error`() {
        val v = Validator.validator<String>()
        v.ensure(false) { "name required" }
        assertTrue(v.hasErrors)
        assertEquals(listOf("name required"), v.errors())
    }

    @Test
    fun `multiple failed ensures accumulate all errors in order`() {
        val v = Validator.validator<String>()
        v.ensure(false) { "first" }
        v.ensure(true) { "should not appear" }
        v.ensure(false) { "second" }
        v.ensure(false) { "third" }
        assertEquals(listOf("first", "second", "third"), v.errors())
    }

    // -- ensureNotNull --

    @Test
    fun `ensureNotNull with non-null value adds no errors`() {
        val v = Validator.validator<String>()
        v.ensureNotNull("hello") { "should not appear" }
        assertFalse(v.hasErrors)
    }

    @Test
    fun `ensureNotNull with null value adds the error`() {
        val v = Validator.validator<String>()
        v.ensureNotNull(null) { "value required" }
        assertTrue(v.hasErrors)
        assertEquals(listOf("value required"), v.errors())
    }

    // -- fail --

    @Test
    fun `fail adds error directly`() {
        val v = Validator.validator<String>()
        v.fail("direct error")
        assertEquals(listOf("direct error"), v.errors())
    }

    // -- addAll --

    @Test
    fun `addAll adds multiple errors`() {
        val v = Validator.validator<String>()
        v.addAll(listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), v.errors())
    }

    @Test
    fun `addAll with empty iterable adds nothing`() {
        val v = Validator.validator<String>()
        v.addAll(emptyList())
        assertFalse(v.hasErrors)
    }

    // -- check --

    @Test
    fun `check on Ok adds nothing`() {
        val v = Validator.validator<String>()
        with(v) { Res.ok(42).check() }
        assertFalse(v.hasErrors)
    }

    @Test
    fun `check on Fail adds the error`() {
        val v = Validator.validator<String>()
        with(v) { Res.failure("bad").check() }
        assertEquals(listOf("bad"), v.errors())
    }

    @Test
    fun `check with mapError on Ok adds nothing`() {
        val v = Validator.validator<String>()
        with(v) { Res.ok(42).check { it: Int -> "mapped: $it" } }
        assertFalse(v.hasErrors)
    }

    @Test
    fun `check with mapError on Fail adds the mapped error`() {
        val v = Validator.validator<String>()
        with(v) { Res.failure(404).check { code -> "HTTP $code" } }
        assertEquals(listOf("HTTP 404"), v.errors())
    }

    // -- checkOrNull --

    @Test
    fun `checkOrNull on Ok returns value and adds nothing`() {
        val v = Validator.validator<String>()
        val value = with(v) { Res.ok(42).checkOrNull() }
        assertEquals(42, value)
        assertFalse(v.hasErrors)
    }

    @Test
    fun `checkOrNull on Fail returns null and adds error`() {
        val v = Validator.validator<String>()
        val res: Res<Int, String> = Res.failure("bad")
        val value = with(v) { res.checkOrNull() }
        assertNull(value)
        assertEquals(listOf("bad"), v.errors())
    }

    @Test
    fun `checkOrNull with mapError on Ok returns value and adds nothing`() {
        val v = Validator.validator<String>()
        val value = with(v) { Res.ok(42).checkOrNull { it: Int -> "mapped: $it" } }
        assertEquals(42, value)
        assertFalse(v.hasErrors)
    }

    @Test
    fun `checkOrNull with mapError on Fail returns null and adds mapped error`() {
        val v = Validator.validator<String>()
        val res: Res<Int, Int> = Res.failure(404)
        val value = with(v) { res.checkOrNull { code -> "HTTP $code" } }
        assertNull(value)
        assertEquals(listOf("HTTP 404"), v.errors())
    }

    // -- check/checkOrNull member overloads (imperative, no with()) --

    @Test
    fun `check member on Ok adds nothing`() {
        val v = Validator.validator<String>()
        v.check(Res.ok(42))
        assertFalse(v.hasErrors)
    }

    @Test
    fun `check member on Fail adds the error`() {
        val v = Validator.validator<String>()
        v.check(Res.failure("bad"))
        assertEquals(listOf("bad"), v.errors())
    }

    @Test
    fun `check member with mapError on Fail adds mapped error`() {
        val v = Validator.validator<String>()
        v.check(Res.failure(404)) { code -> "HTTP $code" }
        assertEquals(listOf("HTTP 404"), v.errors())
    }

    @Test
    fun `checkOrNull member on Ok returns value`() {
        val v = Validator.validator<String>()
        val value = v.checkOrNull(Res.ok(42))
        assertEquals(42, value)
        assertFalse(v.hasErrors)
    }

    @Test
    fun `checkOrNull member on Fail returns null and adds error`() {
        val v = Validator.validator<String>()
        val value = v.checkOrNull(Res.failure("bad"))
        assertNull(value)
        assertEquals(listOf("bad"), v.errors())
    }

    @Test
    fun `checkOrNull member with mapError on Fail returns null and adds mapped error`() {
        val v = Validator.validator<String>()
        val value = v.checkOrNull(Res.failure(404)) { code -> "HTTP $code" }
        assertNull(value)
        assertEquals(listOf("HTTP 404"), v.errors())
    }

    // -- hasErrors --

    @Test
    fun `hasErrors is false when empty`() {
        assertFalse(Validator.validator<String>().hasErrors)
    }

    @Test
    fun `hasErrors is true when non-empty`() {
        val v = Validator.validator<String>()
        v.fail("err")
        assertTrue(v.hasErrors)
    }

    // -- errors snapshot --

    @Test
    fun `errors returns snapshot not mutable reference`() {
        val v = Validator.validator<String>()
        v.fail("first")
        val snapshot = v.errors()
        v.fail("second")
        assertEquals(listOf("first"), snapshot)
        assertEquals(listOf("first", "second"), v.errors())
    }

    // -- toRes --

    @Test
    fun `toRes returns Ok when no errors`() {
        val result = Validator.validator<String>().toRes()
        assertTrue(result.isOk)
        assertEquals(Unit, result.getOrNull())
    }

    @Test
    fun `toRes returns Fail with error list when errors present`() {
        val v = Validator.validator<String>()
        v.fail("a")
        v.fail("b")
        val result = v.toRes()
        assertTrue(result.isFail)
        assertEquals(listOf("a", "b"), result.errorOrThrow())
    }

    // -- validation {} block --

    @Test
    fun `validation block returns Ok when all ensures pass`() {
        val result = validation<String> {
            ensure(true) { "should not appear" }
            ensure(true) { "should not appear" }
        }
        assertTrue(result.isOk)
    }

    @Test
    fun `validation block returns Fail with all errors when some fail`() {
        val result = validation<String> {
            ensure(false) { "first" }
            ensure(true) { "skip" }
            ensure(false) { "second" }
        }
        assertTrue(result.isFail)
        assertEquals(listOf("first", "second"), result.errorOrThrow())
    }

    @Test
    fun `validation block supports check and checkOrNull`() {
        val result = validation<String> {
            Res.failure("from check").check()
            val value = Res.ok(42).checkOrNull()
            assertEquals(42, value)
            val failRes: Res<Int, String> = Res.failure("from checkOrNull")
            failRes.checkOrNull()
        }
        assertTrue(result.isFail)
        assertEquals(listOf("from check", "from checkOrNull"), result.errorOrThrow())
    }

    // -- addAll inside validation {} block --

    @Test
    fun `addAll works inside validation block`() {
        val result = validation<String> {
            addAll(listOf("a", "b"))
            ensure(false) { "c" }
        }
        assertTrue(result.isFail)
        assertEquals(listOf("a", "b", "c"), result.errorOrThrow())
    }

    // -- Validator reuse after toRes() --

    @Test
    fun `toRes snapshot is independent of subsequent mutations`() {
        val v = Validator.validator<String>()
        v.fail("first")
        val snap1 = v.toRes()
        v.fail("second")
        val snap2 = v.toRes()

        assertTrue(snap1.isFail)
        assertEquals(listOf("first"), snap1.errorOrThrow())
        assertTrue(snap2.isFail)
        assertEquals(listOf("first", "second"), snap2.errorOrThrow())
    }

    // -- validator() factory --

    @Test
    fun `validator factory creates empty validator`() {
        val v = Validator.validator<String>()
        assertFalse(v.hasErrors)
        assertEquals(emptyList(), v.errors())
    }
}
