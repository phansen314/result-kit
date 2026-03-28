package tech.codingzen.resultkit

import kotlin.test.Test
import kotlin.test.assertEquals

class ContractTest {

    @Test
    fun `fold contract enables smart usage`() {
        val res: Res<Int, String> = Res.ok(42)
        var x: Any = Unit
        res.fold(
            onOk = { x = it },
            onFail = { x = it }
        )
        assertEquals(42, x)
    }

    @Test
    fun `rail contract enables definite assignment`() {
        val x: Res<Int, String>
        x = rail<Int, String> { 42 }
        assertEquals(42, x.getOrNull())
    }

    @Test
    fun `getOrElse contract enables definite assignment`() {
        val res: Res<Int, String> = Res.ok(42)
        val x: Int
        x = res.getOrElse { -1 }
        assertEquals(42, x)
    }

    @Test
    fun `toResOr contract enables definite assignment`() {
        val value: Int? = 42
        val x: Res<Int, String>
        x = value.toResOr { "was null" }
        assertEquals(42, x.getOrNull())
    }

    @Test
    fun `getOrThrow transform contract enables definite assignment`() {
        val res: Res<Int, String> = Res.ok(42)
        val x: Int
        x = res.getOrThrow { IllegalStateException(it) }
        assertEquals(42, x)
    }
}
