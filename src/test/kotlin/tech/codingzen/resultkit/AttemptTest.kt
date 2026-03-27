package tech.codingzen.resultkit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AttemptTest {

    @Test
    fun `attempt returns Ok on success`() {
        val result = attempt { 42 }
        assertTrue(result.isOk)
        assertEquals(42, result.value)
    }

    @Test
    fun `attempt wraps exception in Fail`() {
        val result = attempt { throw RuntimeException("boom") }
        assertTrue(result.isFail)
        assertIs<RuntimeException>(result.error)
        assertEquals("boom", result.error.message)
    }

    @Test
    fun `attempt rethrows CancellationException`() {
        assertFailsWith<CancellationException> {
            attempt { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `attempt works with suspend lambdas`() = runTest {
        val result = attempt {
            delay(1)
            42
        }
        assertTrue(result.isOk)
        assertEquals(42, result.value)
    }
}
