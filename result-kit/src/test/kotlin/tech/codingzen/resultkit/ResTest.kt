package tech.codingzen.resultkit

import tech.codingzen.resultkit.context.context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ResTest {

    // -- ok / failure factories --

    @Test
    fun `ok creates Ok`() {
        val result = Res.ok(42)
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `failure creates Fail`() {
        val result = Res.failure("err")
        assertTrue(result.isFail)
        assertEquals("err", result.errorOrThrow())
    }

    @Test
    fun `ok rejects Failure sentinel`() {
        assertFailsWith<IllegalStateException> {
            Res.ok(Failure("sneaky"))
        }
    }

    // -- fold --

    @Test
    fun `fold calls onOk for Ok`() {
        val result = Res.ok(10).fold({ it * 2 }, { -1 })
        assertEquals(20, result)
    }

    @Test
    fun `fold calls onFail for Fail`() {
        val result = Res.failure("err").fold({ -1 }, { it.length })
        assertEquals(3, result)
    }

    // -- map --

    @Test
    fun `map transforms Ok value`() {
        val result = Res.ok(5).map { it * 3 }
        assertTrue(result.isOk)
        assertEquals(15, result.getOrNull())
    }

    @Test
    fun `map producing null returns Ok null`() {
        val result = Res.ok(42).map { null }
        assertTrue(result.isOk)
        assertEquals(null, result.getOrNull())
    }

    @Test
    fun `map passes through Fail`() {
        val original = Res.failure("err")
        val result = original.map { 42 }
        assertTrue(result.isFail)
        assertEquals("err", result.errorOrThrow())
        assertEquals(original, result)
    }

    // -- mapError --

    @Test
    fun `mapError transforms Fail error`() {
        val result = Res.failure("err").mapError { it.length }
        assertTrue(result.isFail)
        assertEquals(3, result.errorOrThrow())
    }

    @Test
    fun `mapError passes through Ok`() {
        val original = Res.ok(42)
        val result = original.mapError { "mapped" }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
        assertEquals(original, result)
    }

    // -- getOrElse --

    @Test
    fun `getOrElse returns value for Ok`() {
        val result = Res.ok(42).getOrElse { -1 }
        assertEquals(42, result)
    }

    @Test
    fun `getOrElse returns default for Fail`() {
        val result = Res.failure("err").getOrElse { it.length }
        assertEquals(3, result)
    }

    // -- onOk --

    @Test
    fun `onOk calls action for Ok and returns self`() {
        var called = false
        val original = Res.ok(42)
        val result = original.onOk { called = true }
        assertTrue(called)
        assertEquals(original, result)
    }

    @Test
    fun `onOk does not call action for Fail`() {
        var called = false
        val original = Res.failure("err")
        val result = original.onOk { called = true }
        assertFalse(called)
        assertEquals(original, result)
    }

    // -- onFail --

    @Test
    fun `onFail calls action for Fail and returns self`() {
        var called = false
        val original = Res.failure("err")
        val result = original.onFail { called = true }
        assertTrue(called)
        assertEquals(original, result)
    }

    @Test
    fun `onFail does not call action for Ok`() {
        var called = false
        val original = Res.ok(42)
        val result = original.onFail { called = true }
        assertFalse(called)
        assertEquals(original, result)
    }

    // -- getOrThrow --

    @Test
    fun `getOrThrow returns value for Ok`() {
        val result: Res<Int, RuntimeException> = Res.ok(42)
        assertEquals(42, result.getOrThrow())
    }

    @Test
    fun `getOrThrow throws error for Fail`() {
        val result: Res<Int, RuntimeException> = Res.failure(RuntimeException("boom"))
        val ex = assertFailsWith<RuntimeException> { result.getOrThrow() }
        assertEquals("boom", ex.message)
    }

    // -- getOrThrow with transform --

    @Test
    fun `getOrThrow with transform returns value for Ok`() {
        val result = Res.ok(42).getOrThrow { RuntimeException(it.toString()) }
        assertEquals(42, result)
    }

    @Test
    fun `getOrThrow with transform throws transformed error for Fail`() {
        val result = Res.failure("err")
        val ex = assertFailsWith<IllegalStateException> {
            result.getOrThrow { IllegalStateException(it) }
        }
        assertEquals("err", ex.message)
    }

    // -- getOrNull / errorOrNull --

    @Test
    fun `getOrNull returns value for Ok`() {
        assertEquals(42, Res.ok(42).getOrNull())
    }

    @Test
    fun `getOrNull returns null for Fail`() {
        assertEquals(null, Res.failure("err").getOrNull())
    }

    @Test
    fun `errorOrNull returns error for Fail`() {
        assertEquals("err", Res.failure("err").errorOrNull())
    }

    @Test
    fun `errorOrNull returns null for Ok`() {
        assertEquals(null, Res.ok(42).errorOrNull())
    }

    // -- errorOrThrow --

    @Test
    fun `errorOrThrow throws on Ok`() {
        assertFailsWith<IllegalStateException> {
            Res.ok(42).errorOrThrow()
        }
    }

    // -- recover --

    @Test
    fun `recover transforms Fail to Ok`() {
        val result = Res.failure("err").recover { it.length }
        assertTrue(result.isOk)
        assertEquals(3, result.getOrNull())
    }

    @Test
    fun `recover passes through Ok`() {
        val result = Res.ok(42).recover { -1 }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `recover producing null returns ok null`() {
        val result = Res.failure("err").recover { null }
        assertTrue(result.isOk)
        assertEquals(null, result.getOrNull())
    }

    // -- orElse --

    @Test
    fun `orElse transforms Fail with fallback that succeeds`() {
        val result = Res.failure("err").orElse { Res.ok(it.length) }
        assertTrue(result.isOk)
        assertEquals(3, result.getOrNull())
    }

    @Test
    fun `orElse transforms Fail with fallback that fails`() {
        val result = Res.failure("err").orElse { Res.failure(it.length) }
        assertTrue(result.isFail)
        assertEquals(3, result.errorOrThrow())
    }

    @Test
    fun `orElse passes through Ok`() {
        val result = Res.ok(42).orElse { Res.failure("fallback") }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `orElse changes error type`() {
        val result: Res<Int, Int> = Res.failure("err").orElse { Res.failure(it.length) }
        assertTrue(result.isFail)
        assertEquals(3, result.errorOrThrow())
    }

    // -- flatMap --

    @Test
    fun `flatMap transforms Ok with function returning Ok`() {
        val result = Res.ok(5).flatMap { Res.ok(it * 3) }
        assertTrue(result.isOk)
        assertEquals(15, result.getOrNull())
    }

    @Test
    fun `flatMap transforms Ok with function returning Fail`() {
        val result = Res.ok(5).flatMap { Res.failure("nope") }
        assertTrue(result.isFail)
        assertEquals("nope", result.errorOrThrow())
    }

    @Test
    fun `flatMap passes through Fail`() {
        val result = Res.failure("err").flatMap { Res.ok(42) }
        assertTrue(result.isFail)
        assertEquals("err", result.errorOrThrow())
    }

    @Test
    fun `flatMap changes value type`() {
        val result: Res<String, String> = Res.ok(42).flatMap { Res.ok(it.toString()) }
        assertTrue(result.isOk)
        assertEquals("42", result.getOrNull())
    }

    // -- map null edge cases --

    @Test
    fun `map on ok null passes null to transform`() {
        val result = Res.ok(null).map { it }
        assertTrue(result.isOk)
        assertEquals(null, result.getOrNull())
    }

    // -- null edge cases (tagged union invariants) --

    @Test
    fun `ok null is Ok`() {
        val result = Res.ok(null)
        assertTrue(result.isOk)
        assertFalse(result.isFail)
        assertEquals(null, result.getOrNull())
    }

    @Test
    fun `failure null is Fail`() {
        val result = Res.failure(null)
        assertTrue(result.isFail)
        assertFalse(result.isOk)
        assertEquals(null, result.errorOrNull())
    }

    @Test
    fun `ok null not equal to failure null`() {
        assertFalse(Res.ok(null).equals(Res.failure(null)))
    }

    @Test
    fun `ok null and failure null have different hashCodes`() {
        assertNotEquals(Res.ok(null).hashCode(), Res.failure(null).hashCode())
    }

    @Test
    fun `ok null toString`() {
        assertEquals("Ok(null)", Res.ok(null).toString())
    }

    @Test
    fun `failure null toString`() {
        assertEquals("Fail(null)", Res.failure(null).toString())
    }

    // -- nested Res (boxing invariants) --

    @Test
    fun `ok wrapping a failure is still Ok`() {
        val inner: Res<Int, String> = Res.failure("inner")
        val outer: Res<Res<Int, String>, Nothing> = Res.ok(inner)
        assertTrue(outer.isOk)
        assertTrue(outer.getOrNull()!!.isFail)
        assertEquals("inner", outer.getOrNull()!!.errorOrNull())
    }

    @Test
    fun `failure wrapping an ok is still Fail`() {
        val inner: Res<Int, Nothing> = Res.ok(42)
        val outer: Res<Nothing, Res<Int, Nothing>> = Res.failure(inner)
        assertTrue(outer.isFail)
        assertTrue(outer.errorOrThrow().isOk)
        assertEquals(42, outer.errorOrThrow().getOrNull())
    }

    @Test
    fun `getOrNull on nested ok-wrapping-failure returns inner Res`() {
        val inner: Res<Int, String> = Res.failure("inner")
        val outer: Res<Res<Int, String>, Nothing> = Res.ok(inner)
        val unwrapped = outer.getOrNull()
        assertTrue(unwrapped!!.isFail)
        assertEquals("inner", unwrapped.errorOrNull())
    }

    @Test
    fun `errorOrNull on nested failure-wrapping-ok returns inner Res`() {
        val inner: Res<Int, Nothing> = Res.ok(42)
        val outer: Res<Nothing, Res<Int, Nothing>> = Res.failure(inner)
        val unwrapped = outer.errorOrNull()
        assertTrue(unwrapped!!.isOk)
        assertEquals(42, unwrapped.getOrNull())
    }

    // -- equals / hashCode / toString --

    @Test
    fun `Ok equals and hashCode`() {
        assertEquals(Res.ok(42), Res.ok(42))
        assertEquals(Res.ok(42).hashCode(), Res.ok(42).hashCode())
        assertFalse(Res.ok(42).equals(Res.ok(99)))
        assertFalse(Res.ok(42).equals(Res.failure(42)))
    }

    @Test
    fun `Fail equals and hashCode`() {
        assertEquals(Res.failure("err"), Res.failure("err"))
        assertEquals(Res.failure("err").hashCode(), Res.failure("err").hashCode())
        assertFalse(Res.failure("a").equals(Res.failure("b")))
        assertFalse(Res.failure(42).equals(Res.ok(42)))
    }

    @Test
    fun `Fail equality is unaffected by context frames`() {
        val base = Res.failure("err")
        val withCtx = base.context { "some context" }
        assertEquals(base, withCtx)
        assertEquals(base.hashCode(), withCtx.hashCode())
    }

    @Test
    fun `toString formats correctly`() {
        assertEquals("Ok(42)", Res.ok(42).toString())
        assertEquals("Fail(err)", Res.failure("err").toString())
    }

    // -- kotlin.Result interop --

    @Test
    fun `Result success toRes returns Ok`() {
        val result = Result.success(42).toRes()
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `Result failure toRes returns Fail`() {
        val ex = RuntimeException("boom")
        val result = Result.failure<Int>(ex).toRes()
        assertTrue(result.isFail)
        assertEquals(ex, result.errorOrThrow())
    }

    // -- toResOr --

    @Test
    fun `toResOr on non-null returns Ok`() {
        val result: Res<Int, String> = 42.toResOr { "was null" }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `toResOr on null returns Fail with error`() {
        val result: Res<Int, String> = null.toResOr { "was null" }
        assertTrue(result.isFail)
        assertEquals("was null", result.errorOrNull())
    }

    // -- toFailIf --

    @Test
    fun `toFailIf converts Ok to Fail when predicate matches`() {
        val result: Res<Int, String> = Res.ok(18).toFailIf({ it < 21 }) { "too young: $it" }
        assertTrue(result.isFail)
        assertEquals("too young: 18", result.errorOrNull())
    }

    @Test
    fun `toFailIf leaves Ok unchanged when predicate does not match`() {
        val result: Res<Int, String> = Res.ok(25).toFailIf({ it < 21 }) { "too young" }
        assertTrue(result.isOk)
        assertEquals(25, result.getOrNull())
    }

    @Test
    fun `toFailIf passes through Fail unchanged`() {
        val result: Res<Int, String> = Res.failure<String>("already failed").toFailIf({ true }) { "overwrite" }
        assertTrue(result.isFail)
        assertEquals("already failed", result.errorOrNull())
    }

    // -- flatten --

    @Test
    fun `flatten unwraps Ok of Ok`() {
        val nested: Res<Res<Int, String>, String> = Res.ok(Res.ok(42))
        val flat: Res<Int, String> = nested.flatten()
        assertTrue(flat.isOk)
        assertEquals(42, flat.getOrNull())
    }

    @Test
    fun `flatten unwraps Ok of Fail`() {
        val nested: Res<Res<Int, String>, String> = Res.ok(Res.failure("inner"))
        val flat: Res<Int, String> = nested.flatten()
        assertTrue(flat.isFail)
        assertEquals("inner", flat.errorOrNull())
    }

    @Test
    fun `flatten passes through outer Fail`() {
        val nested: Res<Res<Int, String>, String> = Res.failure("outer")
        val flat: Res<Int, String> = nested.flatten()
        assertTrue(flat.isFail)
        assertEquals("outer", flat.errorOrNull())
    }

    // -- toResult --

    @Test
    fun `Res Ok toResult returns success`() {
        val result: Res<Int, RuntimeException> = Res.ok(42)
        assertEquals(Result.success(42), result.toResult())
    }

    @Test
    fun `Res Fail toResult returns failure`() {
        val ex = RuntimeException("boom")
        val result: Res<Int, RuntimeException> = Res.failure(ex)
        assertTrue(result.toResult().isFailure)
        assertEquals(ex, result.toResult().exceptionOrNull())
    }
}
