package tech.codingzen.resultkit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CatchingTest {

    @Test
    fun `catching returns Ok on success`() {
        val result = catching { 42 }
        assertIs<Res.Ok<Int>>(result)
        assertEquals(42, result.value)
    }

    @Test
    fun `catching wraps exception in Fail`() {
        val result = catching { throw RuntimeException("boom") }
        assertIs<Res.Fail<Exception>>(result)
        assertIs<RuntimeException>(result.error)
        assertEquals("boom", result.error.message)
    }

    @Test
    fun `catching rethrows CancellationException`() {
        assertFailsWith<CancellationException> {
            catching { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `catching works with suspend lambdas`() = runTest {
        val result = catching {
            delay(1)
            42
        }
        assertIs<Res.Ok<Int>>(result)
        assertEquals(42, result.value)
    }
}
