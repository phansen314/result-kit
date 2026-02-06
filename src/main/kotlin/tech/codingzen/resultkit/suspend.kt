package tech.codingzen.resultkit

import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resumeWithException

/**
 * DSL scope for suspend railway-oriented error handling.
 *
 * The suspend counterpart to [ResDsl], providing the same operations for use
 * within coroutines. Uses [suspendCoroutineUninterceptedOrReturn] for [ok] to
 * properly short-circuit suspend contexts.
 *
 * @see suspendRes the entry point for this DSL
 * @see ResDsl the synchronous equivalent
 */
class SuspendResDsl internal constructor() {
  companion object {
    val instance = SuspendResDsl()
  }

  /**
   * Unwraps a successful [Res] and returns its value, or short-circuits
   * the enclosing [suspendRes] block with the error.
   *
   * Uses [suspendCoroutineUninterceptedOrReturn] to resume the coroutine
   * with an [ErrException] on the error path.
   */
  suspend fun <T> Res<T>.ok(): T =
    suspendCoroutineUninterceptedOrReturn { cont ->
      when (this@ok) {
        is Res.Ok -> value
        is Res.Err -> COROUTINE_SUSPENDED.apply {
          cont.resumeWithException(ErrException(this@ok));
        }
      }
    }

  /**
   * Unwraps a successful [Res] and returns its value, or returns [default]
   * if this is an error.
   */
  fun <T> Res<T>.or(default: T): T = when (this) {
    is Res.Ok -> value
    is Res.Err -> default
  }

  /**
   * Short-circuits the enclosing [suspendRes] block with a [Res.Err.Message].
   *
   * @param message a description of the error
   */
  fun err(message: String): Nothing = throw ErrException(Res.error(message))

  /**
   * Short-circuits the enclosing [suspendRes] block with a [Res.Err.Thrown].
   *
   * @param thrown the exception that caused the error
   * @param message a description providing context for the failure
   */
  fun err(thrown: Exception, message: String): Nothing = throw ErrException(Res.error(thrown, message))

  /**
   * Executes [block] and catches any non-[ErrException] exception, converting it
   * to a [Res.Err.Thrown] that short-circuits the enclosing [suspendRes] block.
   *
   * Use [CatchScope.message] within the block to attach a lazy contextual message
   * to the error. The message is only evaluated if an exception occurs.
   *
   * @param block the code to execute within a [CatchScope]
   * @return the result of [block] if no exception was thrown
   * @see ResDsl.catch the synchronous equivalent
   */
  suspend inline fun <T> catch(block: CatchScope.() -> T): T {
    val scope = CatchScope()
    return try {
      scope.block()
    } catch (exc: ErrException) {
      throw exc
    } catch (exc: Exception) {
      val msg = scope._message?.invoke()
      throw ErrException(if (msg != null) Res.error(exc, msg) else Res.error(exc))
    }
  }
}

/**
 * Entry point for suspend railway-oriented error handling.
 *
 * The suspend counterpart to [res]. Executes [block] within a [SuspendResDsl] scope
 * and returns a [Res] that is either [Res.Ok] with the block's return value, or a
 * [Res.Err] if an error was raised.
 *
 * ```
 * val result: Res<User> = suspendRes {
 *   val id = parseId(input).ok()
 *   val user = catch {
 *     message { "failed to fetch user $id" }
 *     userService.fetch(id)  // suspend call
 *   }
 *   user
 * }
 * ```
 *
 * @param block the suspend DSL block to execute
 * @return [Res.Ok] on success, or a [Res.Err] subtype on failure
 * @see res the synchronous equivalent
 */
suspend inline fun <T> suspendRes(crossinline block: suspend SuspendResDsl.() -> T): Res<T> =
  try {
    Res.value(SuspendResDsl.instance.block())
  } catch (exc: ErrException) {
    exc.err
  } catch (exc: Exception) {
    Res.Err.UncaughtThrown(exc)
  }

/**
 * Suspend counterpart to the top-level [catch] function.
 *
 * Wraps a single suspend throwing operation into a [Res], with [CatchScope] message support.
 * Every exception becomes [Res.Err.Thrown] — there is no [Res.Err.UncaughtThrown] case since
 * there is no outer DSL to escape from.
 *
 * ```
 * val user: Res<User> = suspendCatch {
 *   message { "failed to fetch user $id" }
 *   userService.fetch(id)
 * }
 * ```
 *
 * @param block the suspend code to execute within a [CatchScope]
 * @return [Res.Ok] with the block's result, or [Res.Err.Thrown] if an exception was thrown
 * @see suspendRes for the full suspend DSL with chaining support
 * @see catch the synchronous equivalent
 */
suspend inline fun <T> suspendCatch(crossinline block: suspend CatchScope.() -> T): Res<T> {
  val scope = CatchScope()
  return try {
    Res.value(scope.block())
  } catch (exc: Exception) {
    val msg = scope._message?.invoke()
    if (msg != null) Res.error(exc, msg) else Res.error(exc)
  }
}
