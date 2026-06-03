package tech.codingzen.resultkit

import tech.codingzen.resultkit.context.context
import tech.codingzen.resultkit.context.contextChain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SequenceTest {

    // -- allOk --

    @Test
    fun `allOk returns true when all Ok`() {
        assertTrue(sequenceOf(Res.ok(1), Res.ok(2), Res.ok(3)).allOk())
    }

    @Test
    fun `allOk returns false when any Fail`() {
        assertFalse(sequenceOf(Res.ok(1), Res.failure("err"), Res.ok(3)).allOk())
    }

    @Test
    fun `allOk returns true for empty sequence`() {
        assertTrue(emptySequence<Res<Int, String>>().allOk())
    }

    // -- anyOk --

    @Test
    fun `anyOk returns true when at least one Ok`() {
        assertTrue(sequenceOf(Res.failure("a"), Res.ok(1)).anyOk())
    }

    @Test
    fun `anyOk returns false when all Fail`() {
        assertFalse(sequenceOf(Res.failure("a"), Res.failure("b")).anyOk())
    }

    @Test
    fun `anyOk returns false for empty sequence`() {
        assertFalse(emptySequence<Res<Int, String>>().anyOk())
    }

    // -- anyFail --

    @Test
    fun `anyFail returns true when at least one Fail`() {
        assertTrue(sequenceOf(Res.ok(1), Res.failure("a")).anyFail())
    }

    @Test
    fun `anyFail returns false when all Ok`() {
        assertFalse(sequenceOf(Res.ok(1), Res.ok(2)).anyFail())
    }

    @Test
    fun `anyFail returns false for empty sequence`() {
        assertFalse(emptySequence<Res<Int, String>>().anyFail())
    }

    // -- filterOk --

    @Test
    fun `filterOk extracts Ok values`() {
        val seq = sequenceOf(Res.ok(1), Res.failure("a"), Res.ok(3))
        assertEquals(listOf(1, 3), seq.filterOk().toList())
    }

    @Test
    fun `filterOk returns empty for all Fail`() {
        val seq = sequenceOf(Res.failure("a"), Res.failure("b"))
        assertEquals(emptyList(), seq.filterOk().toList())
    }

    @Test
    fun `filterOk returns empty for empty sequence`() {
        assertEquals(emptyList(), emptySequence<Res<Int, String>>().filterOk().toList())
    }

    // -- filterFail --

    @Test
    fun `filterFail extracts Fail errors`() {
        val seq = sequenceOf(Res.ok(1), Res.failure("a"), Res.failure("b"))
        assertEquals(listOf("a", "b"), seq.filterFail().toList())
    }

    @Test
    fun `filterFail returns empty for all Ok`() {
        val seq = sequenceOf(Res.ok(1), Res.ok(2))
        assertEquals(emptyList(), seq.filterFail().toList())
    }

    // -- combine --

    @Test
    fun `combine all Ok returns Ok list`() {
        val result = sequenceOf(Res.ok(1), Res.ok(2), Res.ok(3)).combine()
        assertTrue(result.isOk)
        assertEquals(listOf(1, 2, 3), result.getOrNull())
    }

    @Test
    fun `combine returns first Fail`() {
        val result = sequenceOf(Res.ok(1), Res.failure("first"), Res.ok(3), Res.failure("second")).combine()
        assertTrue(result.isFail)
        assertEquals("first", result.errorOrNull())
    }

    @Test
    fun `combine empty sequence returns Ok of empty list`() {
        val result = emptySequence<Res<Int, String>>().combine()
        assertTrue(result.isOk)
        assertEquals(emptyList(), result.getOrNull())
    }

    // -- partition --

    @Test
    fun `partition splits into values and errors`() {
        val (oks, fails) = sequenceOf(Res.ok(1), Res.failure("a"), Res.ok(3), Res.failure("b")).partition()
        assertEquals(listOf(1, 3), oks)
        assertEquals(listOf("a", "b"), fails)
    }

    @Test
    fun `partition all Ok`() {
        val (oks, fails) = sequenceOf(Res.ok(1), Res.ok(2)).partition()
        assertEquals(listOf(1, 2), oks)
        assertEquals(emptyList(), fails)
    }

    @Test
    fun `partition empty sequence`() {
        val (oks, fails) = emptySequence<Res<Int, String>>().partition()
        assertEquals(emptyList(), oks)
        assertEquals(emptyList(), fails)
    }

    // -- tryMap --

    @Test
    fun `tryMap all succeed returns Ok list`() {
        val result = sequenceOf(1, 2, 3).tryMap { Res.ok(it * 2) }
        assertTrue(result.isOk)
        assertEquals(listOf(2, 4, 6), result.getOrNull())
    }

    @Test
    fun `tryMap short-circuits on first Fail`() {
        var count = 0
        val result = sequenceOf(1, 2, 3).tryMap { v ->
            count++
            if (v == 2) Res.failure("err") else Res.ok(v)
        }
        assertTrue(result.isFail)
        assertEquals("err", result.errorOrNull())
        assertEquals(2, count) // stopped after the failing element
    }

    @Test
    fun `tryMap empty sequence returns Ok of empty list`() {
        val result = emptySequence<Int>().tryMap { Res.ok(it) }
        assertTrue(result.isOk)
        assertEquals(emptyList(), result.getOrNull())
    }

    // -- tryForEach --

    @Test
    fun `tryForEach all succeed returns Ok Unit`() {
        val collected = mutableListOf<Int>()
        val result = sequenceOf(1, 2, 3).tryForEach { collected.add(it); Res.ok(Unit) }
        assertTrue(result.isOk)
        assertEquals(listOf(1, 2, 3), collected)
    }

    @Test
    fun `tryForEach short-circuits on first Fail`() {
        var count = 0
        val result = sequenceOf(1, 2, 3).tryForEach { v ->
            count++
            if (v == 2) Res.failure("err") else Res.ok(Unit)
        }
        assertTrue(result.isFail)
        assertEquals("err", result.errorOrNull())
        assertEquals(2, count)
    }

    // -- null edge cases --

    @Test
    fun `filterOk preserves null Ok values`() {
        val seq: Sequence<Res<Int?, String>> = sequenceOf(Res.ok(null), Res.ok(1))
        assertEquals(listOf(null, 1), seq.filterOk().toList())
    }

    @Test
    fun `filterFail preserves null Fail errors`() {
        val seq: Sequence<Res<Int, String?>> = sequenceOf(Res.failure(null), Res.failure("a"))
        assertEquals(listOf(null, "a"), seq.filterFail().toList())
    }

    @Test
    fun `combine preserves null Ok values`() {
        val seq: Sequence<Res<Int?, String>> = sequenceOf(Res.ok(null), Res.ok(1))
        val result = seq.combine()
        assertTrue(result.isOk)
        assertEquals(listOf(null, 1), result.getOrNull())
    }

    // -- laziness (the reason Sequence exists; impossible to assert on Iterable) --

    @Test
    fun `combine does not evaluate elements after the first Fail`() {
        var evaluatedPastFailure = 0
        val seq = sequence<Res<Int, String>> {
            yield(Res.ok(1))
            yield(Res.failure("boom"))
            evaluatedPastFailure++ // must never run
            yield(Res.ok(3))
        }
        val result = seq.combine()
        assertTrue(result.isFail)
        assertEquals("boom", result.errorOrNull())
        assertEquals(0, evaluatedPastFailure)
    }

    @Test
    fun `tryForEach does not pull elements after the first Fail`() {
        var pulledPastFailure = 0
        val seq = sequence {
            yield(1)
            yield(2)
            pulledPastFailure++ // must never run — failure is raised on element 2
            yield(3)
        }
        val result = seq.tryForEach { v -> if (v == 2) Res.failure("err") else Res.ok(Unit) }
        assertTrue(result.isFail)
        assertEquals(0, pulledPastFailure)
    }

    @Test
    fun `filterOk is lazy - take(n) evaluates only n upstream elements`() {
        var evaluated = 0
        val seq = generateSequence(1) { it + 1 }
            .map { evaluated++; Res.ok(it) }
        val taken = seq.filterOk().take(3).toList()
        assertEquals(listOf(1, 2, 3), taken)
        assertEquals(3, evaluated) // not the whole (infinite) source
    }

    // -- frame preservation (consistent with Iterable + CLAUDE.md frame-flow table) --

    @Test
    fun `combine preserves frames of the failing element`() {
        val failing: Res<Int, String> = Res.failure<String>("boom").context { "while loading" }
        val result = sequenceOf(Res.ok(1), failing, Res.ok(3)).combine()
        assertTrue(result.isFail)
        assertEquals(listOf("while loading"), result.contextChain().map { it.message })
    }

    @Test
    fun `tryMap preserves frames of the failing element`() {
        val result = sequenceOf(1, 2, 3).tryMap { v ->
            if (v == 2) Res.failure<String>("err").context { "mapping $v" } else Res.ok(v)
        }
        assertTrue(result.isFail)
        assertEquals(listOf("mapping 2"), result.contextChain().map { it.message })
    }

    @Test
    fun `filterFail drops frames`() {
        val failing: Res<Int, String> = Res.failure<String>("boom").context { "while loading" }
        val errors = sequenceOf(Res.ok(1), failing).filterFail().toList()
        assertEquals(listOf("boom"), errors) // only E survives; frames are not carried by a bare E
    }
}
