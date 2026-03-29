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
        val result = Rail.attempt { 42 }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `attempt wraps exception in Fail`() {
        val result = Rail.attempt { throw RuntimeException("boom") }
        assertTrue(result.isFail)
        assertIs<RuntimeException>(result.errorOrThrow())
        assertEquals("boom", result.errorOrThrow().message)
    }

    @Test
    fun `attempt rethrows CancellationException`() {
        assertFailsWith<CancellationException> {
            Rail.attempt { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `attempt works with suspend lambdas`() = runTest {
        val result = Rail.attempt {
            delay(1)
            42
        }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
    }
}
