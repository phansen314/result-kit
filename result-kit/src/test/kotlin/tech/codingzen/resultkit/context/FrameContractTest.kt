package tech.codingzen.resultkit.context

import tech.codingzen.resultkit.*
import kotlin.test.*

/**
 * Locks the frame contract across the whole API: which operations PRESERVE / APPEND / MERGE / DROP /
 * ATTACH context frames. The "drop" cases are asserted deliberately — locking them guards against an
 * operation silently starting to preserve (or vice versa) in a future refactor.
 *
 * Also covers the [FramedError] accumulation APIs that retain per-error frames through the
 * many-to-one collapse where the plain `List<E>` paths drop them.
 */
class FrameContractTest {

    /** A Fail seeded with [error] and one frame per [msgs] (frame order = append, so msgs[0] is index 0). */
    private fun framedFail(error: String, vararg msgs: String): Res<Int, String> {
        var r: Res<Int, String> = Res.failure(error)
        for (m in msgs) r = r.context { m }
        return r
    }

    // ===================== PRESERVE =====================

    @Test
    fun `flatMap passes a Fail through with frames intact`() {
        val result = framedFail("boom", "f0", "f1").flatMap { Res.ok<Int>(it + 1) }
        assertTrue(result.isFail)
        assertEquals(listOf("f0", "f1"), result.contextChain().map { it.message })
    }

    @Test
    fun `flatMap carries frames from an inner Fail produced by the transform`() {
        val result: Res<Int, String> = Res.ok<Int>(1).flatMap { framedFail("inner", "g0") }
        assertEquals(listOf("g0"), result.contextChain().map { it.message })
    }

    @Test
    fun `flatten preserves outer Fail frames`() {
        val outer: Res<Res<Int, String>, String> = Res.failure("boom")
        assertEquals(listOf("f0"), outer.context { "f0" }.flatten().contextChain().map { it.message })
    }

    @Test
    fun `flatten preserves inner Fail frames`() {
        val inner: Res<Int, String> = framedFail("inner", "g0", "g1")
        val outer: Res<Res<Int, String>, String> = Res.ok(inner)
        assertEquals(listOf("g0", "g1"), outer.flatten().contextChain().map { it.message })
    }

    @Test
    fun `zip2 preserves the failing branch frames`() {
        val r1 = zip({ framedFail("e", "f0") }, { Res.ok("x") }) { a, b -> "$a$b" }
        assertEquals(listOf("f0"), r1.contextChain().map { it.message })
        val r2 = zip({ Res.ok(1) }, { framedFail("e", "g0", "g1") }) { a: Int, b: Int -> a + b }
        assertEquals(listOf("g0", "g1"), r2.contextChain().map { it.message })
    }

    @Test
    fun `zip3 and zip4 preserve the failing branch frames`() {
        val r3 = zip({ Res.ok(1) }, { Res.ok(2) }, { framedFail("e", "h0") }) { a: Int, b: Int, c: Int -> a + b + c }
        assertEquals(listOf("h0"), r3.contextChain().map { it.message })
        val r4 = zip({ Res.ok(1) }, { Res.ok(2) }, { Res.ok(3) }, { framedFail("e", "i0") }) { a: Int, _: Int, _: Int, _: Int -> a }
        assertEquals(listOf("i0"), r4.contextChain().map { it.message })
    }

    @Test
    fun `combine preserves the first failing element frames`() {
        val list = listOf(Res.ok(1), framedFail("e", "f0", "f1"), Res.ok(3))
        assertEquals(listOf("f0", "f1"), list.combine().contextChain().map { it.message })
    }

    @Test
    fun `tryMap preserves the failing element frames and short-circuits`() {
        var seen = 0
        val result = listOf(1, 2, 3).tryMap { n ->
            seen++
            if (n == 2) framedFail("e", "f0") else Res.ok(n)
        }
        assertEquals(listOf("f0"), result.contextChain().map { it.message })
        assertEquals(2, seen, "must short-circuit on the failing element")
    }

    @Test
    fun `tryForEach preserves the failing element frames`() {
        val result = listOf(1, 2, 3).tryForEach { n -> if (n == 1) framedFail("e", "f0") else Res.ok(n) }
        assertEquals(listOf("f0"), result.contextChain().map { it.message })
    }

    @Test
    fun `ExceptionMappingRail top-level preserves frames from an orFail short-circuit`() {
        val catcher = Rail.catching<String> { it.message ?: "ex" }
        val result: Res<Int, String> = catcher { framedFail("boom", "f0", "f1").orFail() }
        assertEquals(listOf("f0", "f1"), result.contextChain().map { it.message })
    }

    @Test
    fun `ExceptionMappingRail top-level exception-caught path has no frames`() {
        val catcher = Rail.catching<String> { "ex:${it.message}" }
        val result: Res<Int, String> = catcher { throw RuntimeException("kaboom") }
        assertTrue(result.isFail)
        assertEquals("ex:kaboom", result.errorOrNull())
        assertEquals(emptyList(), result.contextChain())
    }

    @Test
    fun `ErrorMappingRail top-level maps the error and preserves frames`() {
        val mapper = Rail.mapping<String, String> { "mapped:$it" }
        val result: Res<Int, String> = mapper { framedFail("boom", "f0").orFail() }
        assertEquals("mapped:boom", result.errorOrNull())
        assertEquals(listOf("f0"), result.contextChain().map { it.message })
    }

    @Test
    fun `MappingRail top-level preserves frames from the returned Res`() {
        val mr = Rail.catchingMapping<String, String>(onError = { "mapped:$it" }, onException = { "ex:$it" })
        val result: Res<Int, String> = mr { framedFail("boom", "f0", "f1") }
        assertEquals("mapped:boom", result.errorOrNull())
        assertEquals(listOf("f0", "f1"), result.contextChain().map { it.message })
    }

    @Test
    fun `MappingRail top-level exception-caught path has no frames`() {
        val mr = Rail.catchingMapping<String, String>(onError = { "mapped:$it" }, onException = { "ex:${it.message}" })
        val result: Res<Int, String> = mr { throw RuntimeException("kaboom") }
        assertEquals("ex:kaboom", result.errorOrNull())
        assertEquals(emptyList(), result.contextChain())
    }

    @Test
    fun `MappingRail member-extension onError path preserves frames`() {
        val result: Res<Int, String> = rail {
            val mr = catchingMapping<String>(onError = { "mapped:$it" }, onException = { "ex:$it" })
            mr { framedFail("boom", "f0") }
        }
        assertEquals("mapped:boom", result.errorOrNull())
        assertEquals(listOf("f0"), result.contextChain().map { it.message })
    }

    @Test
    fun `a null error value keeps its frames`() {
        val res: Res<Int, String?> = Res.failure<String?>(null).context { "f0" }
        assertNull(res.errorOrNull())
        assertEquals(listOf("f0"), res.contextChain().map { it.message })
    }

    // ===================== ATTACH (getOrThrow) =====================

    @Test
    fun `getOrThrow attachFrames attaches frames as suppressed FrameTrace`() {
        val ex = RuntimeException("boom")
        val res: Res<Int, RuntimeException> = (Res.failure(ex) as Res<Int, RuntimeException>).context { "f0" }.context { "f1" }
        val thrown = assertFailsWith<RuntimeException> { res.getOrThrow(attachFrames = true) }
        assertSame(ex, thrown)
        val suppressed = thrown.suppressed.toList()
        assertEquals(2, suppressed.size)
        assertTrue(suppressed.all { it is FrameTrace })
    }

    @Test
    fun `getOrThrow with transform attachFrames attaches frames to the fresh throwable`() {
        val res = framedFail("boom", "f0")
        val thrown = assertFailsWith<IllegalStateException> {
            res.getOrThrow(attachFrames = true) { IllegalStateException(it) }
        }
        assertEquals(1, thrown.suppressed.size)
        assertTrue(thrown.suppressed[0] is FrameTrace)
    }

    // ===================== DROP (locked, plain List<E> paths) =====================

    @Test
    fun `zipOrAccumulate drops frames, keeps raw errors`() {
        val result = zipOrAccumulate({ framedFail("e1", "f0") }, { framedFail("e2", "g0") }) { a: Int, b: Int -> a + b }
        assertEquals(listOf("e1", "e2"), result.errorOrNull())
        assertEquals(emptyList(), result.contextChain())
    }

    @Test
    fun `validation check drops frames into a plain error list`() {
        val result = validation<String> { check(framedFail("e", "f0", "f1")) }
        assertEquals(listOf("e"), result.errorOrNull())
        assertEquals(emptyList(), result.contextChain())
    }

    @Test
    fun `filterFail and partition drop frames`() {
        val list = listOf(Res.ok(1), framedFail("e", "f0"))
        assertEquals(listOf("e"), list.filterFail())
        assertEquals(listOf("e"), list.partition().second)
    }

    @Test
    fun `toResult drops frames but keeps the throwable`() {
        val ex = RuntimeException("boom")
        val res: Res<Int, RuntimeException> = (Res.failure(ex) as Res<Int, RuntimeException>).context { "f0" }
        val result = res.toResult()
        assertTrue(result.isFailure)
        assertSame(ex, result.exceptionOrNull())
    }

    // ===================== FramedError retention =====================

    @Test
    fun `validationFramed pairs each error with its frames`() {
        val result = validationFramed<String> {
            check(framedFail("e", "f0", "f1"))
            ensure(false) { "plain" }
        }
        val errs = result.errorOrNull()!!
        assertEquals(2, errs.size)
        assertEquals(FramedError("e", listOf(Frame("f0"), Frame("f1"))), errs[0])
        assertEquals(FramedError("plain", emptyList()), errs[1])
    }

    @Test
    fun `validationFramed with only ensure errors carries empty frames`() {
        val result = validationFramed<String> {
            ensure(false) { "a" }
            ensure(false) { "b" }
        }
        assertEquals(listOf(FramedError("a", emptyList()), FramedError("b", emptyList())), result.errorOrNull())
    }

    @Test
    fun `Validator errorsFramed preserves order across mixed framed and frameless adds`() {
        val v = Validator.validator<String>()
        v.ensure(false) { "first" }
        v.check(framedFail("second", "ctx"))
        v.fail("third")
        assertEquals(
            listOf(
                FramedError("first", emptyList()),
                FramedError("second", listOf(Frame("ctx"))),
                FramedError("third", emptyList()),
            ),
            v.errorsFramed(),
        )
        // plain read-back is unchanged
        assertEquals(listOf("first", "second", "third"), v.errors())
    }

    @Test
    fun `Validator check with mapError retains source frames`() {
        val v = Validator.validator<String>()
        val src: Res<Int, Int> = (Res.failure(42) as Res<Int, Int>).context { "ctx" }
        v.check(src) { "code=$it" }
        assertEquals(listOf(FramedError("code=42", listOf(Frame("ctx")))), v.errorsFramed())
    }

    @Test
    fun `zipOrAccumulateFramed pairs each failing branch with its frames`() {
        val result = zipOrAccumulateFramed(
            { framedFail("e1", "f0") },
            { Res.ok("ok") },
            { framedFail("e3", "h0", "h1") },
        ) { a: Int, b: String, c: Int -> "$a$b$c" }
        assertEquals(
            listOf(
                FramedError("e1", listOf(Frame("f0"))),
                FramedError("e3", listOf(Frame("h0"), Frame("h1"))),
            ),
            result.errorOrNull(),
        )
    }

    @Test
    fun `orFailFramed flushes framed errors into the rail error`() {
        val result: Res<Int, String> = rail {
            val v = Validator.validator<String>()
            v.check(framedFail("bad", "ctx"))
            v.ensure(false) { "also bad" }
            v.orFailFramed { framed -> framed.joinToString("; ") { "${it.error}/${it.frames.size}" } }
            1
        }
        assertEquals("bad/1; also bad/0", result.errorOrNull())
    }

    @Test
    fun `filterFailFramed and partitionFramed retain frames`() {
        val list = listOf(Res.ok(1), framedFail("e", "f0", "f1"))
        assertEquals(listOf(FramedError("e", listOf(Frame("f0"), Frame("f1")))), list.filterFailFramed())
        val (oks, fails) = list.partitionFramed()
        assertEquals(listOf(1), oks)
        assertEquals(listOf(FramedError("e", listOf(Frame("f0"), Frame("f1")))), fails)
    }

    @Test
    fun `FramedError equality includes frames`() {
        assertEquals(FramedError("e", listOf(Frame("a"))), FramedError("e", listOf(Frame("a"))))
        assertNotEquals(FramedError("e", listOf(Frame("a"))), FramedError("e", emptyList()))
    }
}
