package tech.codingzen.resultkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IterableTest {

    // -- allOk --

    @Test
    fun `allOk returns true when all Ok`() {
        assertTrue(listOf(Res.ok(1), Res.ok(2), Res.ok(3)).allOk())
    }

    @Test
    fun `allOk returns false when any Fail`() {
        assertFalse(listOf(Res.ok(1), Res.failure("err"), Res.ok(3)).allOk())
    }

    @Test
    fun `allOk returns true for empty list`() {
        assertTrue(emptyList<Res<Int, String>>().allOk())
    }

    // -- allFail --

    @Test
    fun `allFail returns true when all Fail`() {
        assertTrue(listOf(Res.failure("a"), Res.failure("b")).allFail())
    }

    @Test
    fun `allFail returns false when any Ok`() {
        assertFalse(listOf(Res.failure("a"), Res.ok(1)).allFail())
    }

    @Test
    fun `allFail returns true for empty list`() {
        assertTrue(emptyList<Res<Int, String>>().allFail())
    }

    // -- anyOk --

    @Test
    fun `anyOk returns true when at least one Ok`() {
        assertTrue(listOf(Res.failure("a"), Res.ok(1)).anyOk())
    }

    @Test
    fun `anyOk returns false when all Fail`() {
        assertFalse(listOf(Res.failure("a"), Res.failure("b")).anyOk())
    }

    @Test
    fun `anyOk returns false for empty list`() {
        assertFalse(emptyList<Res<Int, String>>().anyOk())
    }

    // -- anyFail --

    @Test
    fun `anyFail returns true when at least one Fail`() {
        assertTrue(listOf(Res.ok(1), Res.failure("a")).anyFail())
    }

    @Test
    fun `anyFail returns false when all Ok`() {
        assertFalse(listOf(Res.ok(1), Res.ok(2)).anyFail())
    }

    @Test
    fun `anyFail returns false for empty list`() {
        assertFalse(emptyList<Res<Int, String>>().anyFail())
    }

    // -- filterOk --

    @Test
    fun `filterOk extracts Ok values`() {
        val list = listOf(Res.ok(1), Res.failure("a"), Res.ok(3))
        assertEquals(listOf(1, 3), list.filterOk())
    }

    @Test
    fun `filterOk returns empty for all Fail`() {
        val list = listOf(Res.failure("a"), Res.failure("b"))
        assertEquals(emptyList(), list.filterOk())
    }

    @Test
    fun `filterOk returns empty for empty list`() {
        assertEquals(emptyList(), emptyList<Res<Int, String>>().filterOk())
    }

    // -- filterFail --

    @Test
    fun `filterFail extracts Fail errors`() {
        val list = listOf(Res.ok(1), Res.failure("a"), Res.failure("b"))
        assertEquals(listOf("a", "b"), list.filterFail())
    }

    @Test
    fun `filterFail returns empty for all Ok`() {
        val list = listOf(Res.ok(1), Res.ok(2))
        assertEquals(emptyList(), list.filterFail())
    }

    // -- combine --

    @Test
    fun `combine all Ok returns Ok list`() {
        val result = listOf(Res.ok(1), Res.ok(2), Res.ok(3)).combine()
        assertTrue(result.isOk)
        assertEquals(listOf(1, 2, 3), result.getOrNull())
    }

    @Test
    fun `combine returns first Fail`() {
        val result = listOf(Res.ok(1), Res.failure("first"), Res.ok(3), Res.failure("second")).combine()
        assertTrue(result.isFail)
        assertEquals("first", result.errorOrNull())
    }

    @Test
    fun `combine empty list returns Ok of empty list`() {
        val result = emptyList<Res<Int, String>>().combine()
        assertTrue(result.isOk)
        assertEquals(emptyList(), result.getOrNull())
    }

    // -- partition --

    @Test
    fun `partition splits into values and errors`() {
        val (oks, fails) = listOf(Res.ok(1), Res.failure("a"), Res.ok(3), Res.failure("b")).partition()
        assertEquals(listOf(1, 3), oks)
        assertEquals(listOf("a", "b"), fails)
    }

    @Test
    fun `partition all Ok`() {
        val (oks, fails) = listOf(Res.ok(1), Res.ok(2)).partition()
        assertEquals(listOf(1, 2), oks)
        assertEquals(emptyList(), fails)
    }

    @Test
    fun `partition empty list`() {
        val (oks, fails) = emptyList<Res<Int, String>>().partition()
        assertEquals(emptyList(), oks)
        assertEquals(emptyList(), fails)
    }

    // -- tryMap --

    @Test
    fun `tryMap all succeed returns Ok list`() {
        val result = listOf(1, 2, 3).tryMap { Res.ok(it * 2) }
        assertTrue(result.isOk)
        assertEquals(listOf(2, 4, 6), result.getOrNull())
    }

    @Test
    fun `tryMap short-circuits on first Fail`() {
        var count = 0
        val result = listOf(1, 2, 3).tryMap { v ->
            count++
            if (v == 2) Res.failure("err") else Res.ok(v)
        }
        assertTrue(result.isFail)
        assertEquals("err", result.errorOrNull())
        assertEquals(2, count) // stopped after the failing element
    }

    @Test
    fun `tryMap empty list returns Ok of empty list`() {
        val result = emptyList<Int>().tryMap { Res.ok(it) }
        assertTrue(result.isOk)
        assertEquals(emptyList(), result.getOrNull())
    }

    // -- tryForEach --

    @Test
    fun `tryForEach all succeed returns Ok Unit`() {
        val collected = mutableListOf<Int>()
        val result = listOf(1, 2, 3).tryForEach { collected.add(it); Res.ok(Unit) }
        assertTrue(result.isOk)
        assertEquals(listOf(1, 2, 3), collected)
    }

    @Test
    fun `tryForEach short-circuits on first Fail`() {
        var count = 0
        val result = listOf(1, 2, 3).tryForEach { v ->
            count++
            if (v == 2) Res.failure("err") else Res.ok(Unit)
        }
        assertTrue(result.isFail)
        assertEquals("err", result.errorOrNull())
        assertEquals(2, count)
    }

    // -- tryFilter --

    @Test
    fun `tryFilter returns filtered list on all Ok predicates`() {
        val result = listOf(1, 2, 3, 4).tryFilter { Res.ok(it % 2 == 0) }
        assertTrue(result.isOk)
        assertEquals(listOf(2, 4), result.getOrNull())
    }

    @Test
    fun `tryFilter short-circuits on Fail predicate`() {
        var count = 0
        val result = listOf(1, 2, 3).tryFilter { v ->
            count++
            if (v == 2) Res.failure("err") else Res.ok(true)
        }
        assertTrue(result.isFail)
        assertEquals("err", result.errorOrNull())
        assertEquals(2, count)
    }

    @Test
    fun `tryFilter empty list returns Ok of empty list`() {
        val result = emptyList<Int>().tryFilter { Res.ok(true) }
        assertTrue(result.isOk)
        assertEquals(emptyList(), result.getOrNull())
    }

    // -- null edge cases --

    @Test
    fun `filterOk preserves null Ok values`() {
        val list: List<Res<Int?, String>> = listOf(Res.ok(null), Res.ok(1))
        assertEquals(listOf(null, 1), list.filterOk())
    }

    @Test
    fun `filterFail preserves null Fail errors`() {
        val list: List<Res<Int, String?>> = listOf(Res.failure(null), Res.failure("a"))
        assertEquals(listOf(null, "a"), list.filterFail())
    }

    @Test
    fun `combine preserves null Ok values`() {
        val list: List<Res<Int?, String>> = listOf(Res.ok(null), Res.ok(1))
        val result = list.combine()
        assertTrue(result.isOk)
        assertEquals(listOf(null, 1), result.getOrNull())
    }
}
