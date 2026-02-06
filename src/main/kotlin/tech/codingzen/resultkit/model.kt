package tech.codingzen.resultkit

/**
 * A sealed result type representing either a successful value or an error.
 *
 * [Res] is the core type of result-kit, used with the [res] and [suspendRes] DSL
 * entry points for railway-oriented error handling.
 *
 * ```
 * val result: Res<Int> = res {
 *   val x = fetchValue().ok()
 *   x + 1
 * }
 * ```
 *
 * @param T the type of the success value
 */
sealed class Res<out T> {
  companion object {
    /** Creates a successful [Res] containing [value]. */
    fun <T> value(value: T): Res<T> = Ok(value)

    /** Creates an error [Res] with a descriptive [message]. */
    fun error(message: String): Err = Err.Message(message)

    /**
     * Creates an error [Res] wrapping an [exception] with an optional [message].
     *
     * @param exception the caught exception
     * @param message additional context describing the failure, or null to use just the exception
     */
    fun error(exception: Exception, message: String? = null): Err = Err.Thrown(exception, message)
  }

  /**
   * A successful result containing [value].
   *
   * @param T the type of the success value
   */
  class Ok<out T> internal constructor(val value: T): Res<T>()

  /**
   * A failed result. Subtypes distinguish between message-only errors,
   * exception-based errors, and uncaught exceptions that escaped the DSL.
   */
  sealed class Err: Res<Nothing>() {
    /** An error described by a [message] string. */
    class Message internal constructor(val message: String): Err()

    /**
     * An error wrapping a caught [exception] with an optional contextual [message].
     */
    class Thrown internal constructor(val exception: Exception, val message: String? = null): Err()

    /**
     * An exception that was not caught by a [CatchScope] within the DSL block.
     *
     * This indicates an unexpected exception that escaped both user-defined [ResDsl.catch]
     * blocks and the DSL itself. Typically signals a programming error or an exception
     * type that should have been handled.
     */
    class UncaughtThrown @PublishedApi internal constructor(val uncaught: Exception): Err()
  }
}
