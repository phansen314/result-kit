package tech.codingzen.resultkit

import tech.codingzen.resultkit.context.context
import tech.codingzen.resultkit.context.contextChain
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReifiedOpsTest {

    sealed interface AppError {
        data class NotFound(val id: Int) : AppError
        data class Timeout(val ms: Int) : AppError
        data object Unknown : AppError
    }

    // -- mapErrorIf --

    @Test
    fun `mapErrorIf maps the matching subtype`() {
        val res: Res<Int, AppError> = Res.failure(AppError.NotFound(7))
        val mapped = res.mapErrorIf { _: AppError.NotFound -> AppError.Unknown }
        assertEquals(AppError.Unknown, mapped.errorOrNull())
    }

    @Test
    fun `mapErrorIf passes a non-matching subtype through unchanged`() {
        val res: Res<Int, AppError> = Res.failure(AppError.Timeout(100))
        val mapped = res.mapErrorIf { _: AppError.NotFound -> AppError.Unknown }
        assertEquals(AppError.Timeout(100), mapped.errorOrNull())
    }

    @Test
    fun `mapErrorIf is a no-op on Ok`() {
        val res: Res<Int, AppError> = Res.ok(42)
        val mapped = res.mapErrorIf { _: AppError.NotFound -> AppError.Unknown }
        assertEquals(42, mapped.getOrNull())
    }

    @Test
    fun `mapErrorIf preserves frames`() {
        val res: Res<Int, AppError> = Res.failure<AppError>(AppError.NotFound(7)).context { "loading" }
        val mapped = res.mapErrorIf { _: AppError.NotFound -> AppError.Unknown }
        assertEquals(listOf("loading"), mapped.contextChain().map { it.message })
    }

    // -- recoverIf --

    @Test
    fun `recoverIf recovers the matching subtype`() {
        val res: Res<Int, AppError> = Res.failure(AppError.Timeout(100))
        val recovered = res.recoverIf { _: AppError.Timeout -> -1 }
        assertTrue(recovered.isOk)
        assertEquals(-1, recovered.getOrNull())
    }

    @Test
    fun `recoverIf leaves a non-matching subtype as Fail`() {
        val res: Res<Int, AppError> = Res.failure(AppError.NotFound(7))
        val recovered = res.recoverIf { _: AppError.Timeout -> -1 }
        assertTrue(recovered.isFail)
        assertEquals(AppError.NotFound(7), recovered.errorOrNull())
    }

    @Test
    fun `recoverIf is a no-op on Ok`() {
        val res: Res<Int, AppError> = Res.ok(42)
        val recovered = res.recoverIf { _: AppError.Timeout -> -1 }
        assertEquals(42, recovered.getOrNull())
    }

    // -- catchingOnly --

    @Test
    fun `catchingOnly catches the matching exception type`() {
        val result = rail<Int, String> {
            catchingOnly({ e: IOException -> "io: ${e.message}" }) {
                throw IOException("disk")
            }
        }
        assertTrue(result.isFail)
        assertEquals("io: disk", result.errorOrThrow())
    }

    @Test
    fun `catchingOnly rethrows a non-matching exception`() {
        assertFailsWith<IllegalStateException> {
            rail<Int, String> {
                catchingOnly({ _: IOException -> "io" }) {
                    throw IllegalStateException("programming bug")
                }
            }
        }
    }

    @Test
    fun `catchingOnly does not intercept fail short-circuit`() {
        val result = rail<Int, String> {
            catchingOnly({ _: IOException -> "io" }) {
                fail("explicit") // FailException is a Throwable, not an Exception — passes through
            }
        }
        assertTrue(result.isFail)
        assertEquals("explicit", result.errorOrThrow())
    }

    @Test
    fun `catchingOnly rethrows CancellationException even when T would match`() {
        assertFailsWith<CancellationException> {
            rail<Int, String> {
                // CancellationException is a RuntimeException, but the guard rethrows it first
                catchingOnly({ _: RuntimeException -> "rt" }) {
                    throw CancellationException("cancelled")
                }
            }
        }
    }

    @Test
    fun `catchingOnly wraps a throwing mapError in ErrorMapperException`() {
        val ex = assertFailsWith<ErrorMapperException> {
            rail<Int, String> {
                catchingOnly({ _: IOException -> throw RuntimeException("mapper boom") }) {
                    throw IOException("disk")
                }
            }
        }
        assertTrue(ex.originalException is IOException)
    }
}
