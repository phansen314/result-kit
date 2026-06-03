package tech.codingzen.resultkit

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for real structured-concurrency cancellation.
 *
 * The other suites assert cancellation handling with a *synthetic* `throw CancellationException(..)`.
 * These tests instead drive cancellation through real coroutine machinery — a `delay` parked at a
 * true suspension point that is cut short by `withTimeoutOrNull` or `Job.cancel()` — so the runtime
 * throws its internal `TimeoutCancellationException` / `JobCancellationException` (subtypes of the
 * caught `CancellationException`). This proves cancellation propagates cleanly out of every scope
 * and is never converted into a `Res.Fail` or routed through a mapper (Design Invariant #3).
 *
 * Two assertions catch a dropped guard:
 *  - `withTimeoutOrNull(..) { scope }` returns **null** — if the scope swallowed the cancellation and
 *    produced a `Res`, the timeout block would complete normally and return that non-null `Res`.
 *  - the mapper spy (`mapperCalled`) stays **false** — a swallowed cancellation would be fed to
 *    `mapError` / `onException` / `onError`.
 *
 * Negative control: temporarily turn any scope's `catch (CancellationException) { throw e }` into a
 * swallow and the matching test below fails.
 */
class CoroutineCancellationTest {

    private val timeoutMs = 100L
    private val foreverMs = 60_000L // > timeoutMs; safe under runTest's virtual clock

    // -- plain rail {} builder --

    @Test
    fun `rail builder does not swallow real cancellation`() = runTest {
        val result = withTimeoutOrNull(timeoutMs) {
            rail<Int, String> {
                delay(foreverMs)
                1
            }
        }
        assertNull(result)
    }

    @Test
    fun `nested rail propagates real cancellation through outer rail`() = runTest {
        val result = withTimeoutOrNull(timeoutMs) {
            rail<Int, String> {
                val inner = rail<Int, String> {
                    delay(foreverMs)
                    1
                }
                inner.orFail()
            }
        }
        assertNull(result)
    }

    // -- ExceptionMappingRail --

    @Test
    fun `ExceptionMappingRail member does not map real cancellation`() = runTest {
        var mapperCalled = false
        val result = withTimeoutOrNull(timeoutMs) {
            rail<Int, String> {
                val io = catching { _: Exception -> mapperCalled = true; "mapped" }
                io {
                    delay(foreverMs)
                    1
                }
            }
        }
        assertNull(result)
        assertFalse(mapperCalled)
    }

    @Test
    fun `ExceptionMappingRail top-level invoke does not map real cancellation`() = runTest {
        var mapperCalled = false
        val io = Rail.catching<String> { mapperCalled = true; "mapped" }
        val result: Res<Int, String>? = withTimeoutOrNull(timeoutMs) {
            io {
                delay(foreverMs)
                1
            }
        }
        assertNull(result)
        assertFalse(mapperCalled)
    }

    // -- MappingRail (disjoint onException / onError mappers) --

    @Test
    fun `MappingRail member does not map real cancellation`() = runTest {
        var onException = false
        var onError = false
        val result = withTimeoutOrNull(timeoutMs) {
            rail<Int, String> {
                val http = catchingMapping<Int>(
                    onError = { onError = true; "code $it" },
                    onException = { onException = true; "exc" },
                )
                http {
                    delay(foreverMs)
                    Res.ok(1)
                }
            }
        }
        assertNull(result)
        assertFalse(onException)
        assertFalse(onError)
    }

    @Test
    fun `MappingRail top-level invoke does not map real cancellation`() = runTest {
        var onException = false
        var onError = false
        val http = Rail.catchingMapping<Int, String>(
            onError = { onError = true; "code $it" },
            onException = { onException = true; "exc" },
        )
        val result = withTimeoutOrNull(timeoutMs) {
            http {
                delay(foreverMs)
                Res.ok(1)
            }
        }
        assertNull(result)
        assertFalse(onException)
        assertFalse(onError)
    }

    // -- ErrorMappingRail (closes the gap: this scope had no cancellation test) --

    @Test
    fun `ErrorMappingRail top-level invoke does not map real cancellation`() = runTest {
        var mapperCalled = false
        val mapper = Rail.mapping<Int, String> { mapperCalled = true; "mapped $it" }
        val result = withTimeoutOrNull(timeoutMs) {
            mapper {
                delay(foreverMs)
                1
            }
        }
        assertNull(result)
        assertFalse(mapperCalled)
    }

    @Test
    fun `ErrorMappingRail orFail mapping path does not map real cancellation`() = runTest {
        var mapperCalled = false
        val result = withTimeoutOrNull(timeoutMs) {
            rail<Int, String> {
                val mapper = mapping<Int> { mapperCalled = true; "mapped $it" }
                val inner: Res<Int, Int> = rail<Int, Int> {
                    delay(foreverMs)
                    1
                }
                inner.orFail(mapper)
            }
        }
        assertNull(result)
        assertFalse(mapperCalled)
    }

    // -- Rail.attempt --

    @Test
    fun `attempt does not swallow real cancellation`() = runTest {
        val result = withTimeoutOrNull(timeoutMs) {
            Rail.attempt {
                delay(foreverMs)
                1
            }
        }
        assertNull(result)
    }

    // -- Job.cancel() shape: strongest regression guard --
    //
    // Cancel the parent job while the body is parked at delay() inside an ExceptionMappingRail.
    // If the member invoke's CancellationException rethrow were removed, catch(Exception) would map
    // the cancellation to a Res.Fail: the mapper would fire and the coroutine would complete
    // NORMALLY — flipping both assertions below.

    @Test
    fun `Job cancel is not swallowed by ExceptionMappingRail member`() = runTest {
        var mapperCalled = false
        var resumedAfterDelay = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            rail<Int, String> {
                val io = catching { _: Exception -> mapperCalled = true; "mapped" }
                io {
                    delay(foreverMs)
                    resumedAfterDelay = true
                    1
                }
            }
            Unit
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(resumedAfterDelay)
        assertFalse(mapperCalled)
    }

    @Test
    fun `Job cancel propagates out of plain rail`() = runTest {
        var resumedAfterDelay = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            rail<Int, String> {
                delay(foreverMs)
                resumedAfterDelay = true
                1
            }
            Unit
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(resumedAfterDelay)
    }
}
