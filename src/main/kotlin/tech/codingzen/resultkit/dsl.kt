package tech.codingzen.resultkit

/**
 * Internal exception used to transport [Res.Err] through the call stack within [res] blocks.
 *
 * This is never exposed to callers. The [res] entry point catches all [ErrException]s and
 * extracts the contained [err] to return as a [Res.Err]. Stack trace filling is skipped
 * as a performance optimization since this exception is used purely for control flow.
 */
class ErrException @PublishedApi internal constructor(val err: Res.Err) : Exception(null as String?) {
  override fun fillInStackTrace(): Throwable = this
}

/**
 * Receiver scope for [ResDsl.catch] blocks, providing the ability to set a
 * lazy error message that is evaluated only when an exception occurs.
 *
 * The [message] can be set or updated at any point during the block to reflect
 * the current operation context. If an exception is thrown, the most recently
 * set message is included in the resulting [Res.Err.Thrown].
 *
 * ```
 * res {
 *   val parsed = catch {
 *     val raw = readFile(path)
 *     message { "failed to parse config from $path" }
 *     parse(raw)
 *   }
 * }
 * ```
 */
class CatchScope @PublishedApi internal constructor() {
  @PublishedApi
  internal var _message: (() -> String)? = null

  /**
   * Sets a lazy message provider that is evaluated only if an exception occurs.
   *
   * Can be called multiple times to update the message as the block progresses,
   * allowing the message to reflect the most recent operation context.
   *
   * @param provider a function returning the error message string
   */
  fun message(provider: () -> String) { _message = provider }
}

/**
 * DSL scope for synchronous railway-oriented error handling.
 *
 * Provides operations to unwrap [Res] values, raise errors, and catch exceptions
 * within a [res] block. All errors are propagated as [Res.Err] values rather than
 * thrown exceptions.
 *
 * @see res the entry point for this DSL
 */
class ResDsl internal constructor() {
  companion object {
    val instance = ResDsl()
  }

  /**
   * Unwraps a successful [Res] and returns its value, or short-circuits
   * the enclosing [res] block with the error.
   *
   * ```
   * res {
   *   val x: Int = fetchValue().ok()
   * }
   * ```
   */
  fun <T> Res<T>.ok(): T = when (this) {
    is Res.Ok -> value
    is Res.Err -> throw ErrException(this)
  }

  /**
   * Unwraps a successful [Res] and returns its value, or returns [default]
   * if this is an error.
   *
   * ```
   * res {
   *   val x: Int = fetchValue().or(0)
   * }
   * ```
   */
  fun <T> Res<T>.or(default: T): T = when (this) {
    is Res.Ok -> value
    is Res.Err -> default
  }

  /**
   * Short-circuits the enclosing [res] block with a [Res.Err.Message].
   *
   * @param message a description of the error
   */
  fun err(message: String): Nothing = throw ErrException(Res.error(message))

  /**
   * Short-circuits the enclosing [res] block with a [Res.Err.Thrown].
   *
   * @param thrown the exception that caused the error
   * @param message a description providing context for the failure
   */
  fun err(thrown: Exception, message: String): Nothing = Res.error(thrown, message).ok()

  /**
   * Executes [block] and catches any non-[ErrException] exception, converting it
   * to a [Res.Err.Thrown] that short-circuits the enclosing [res] block.
   *
   * Use [CatchScope.message] within the block to attach a lazy contextual message
   * to the error. The message is only evaluated if an exception occurs.
   *
   * ```
   * res {
   *   val conn = catch {
   *     message { "failed to connect to $url" }
   *     openConnection(url)
   *   }
   *   val data = catch {
   *     message { "query failed on ${conn.id}" }
   *     conn.query(sql)
   *   }
   * }
   * ```
   *
   * @param block the code to execute within a [CatchScope]
   * @return the result of [block] if no exception was thrown
   */
  inline fun <T> catch(block: CatchScope.() -> T): T {
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
 * Entry point for synchronous railway-oriented error handling.
 *
 * Executes [block] within a [ResDsl] scope and returns a [Res] that is either
 * [Res.Ok] with the block's return value, or a [Res.Err] if an error was raised.
 *
 * Exceptions that are not caught by a [ResDsl.catch] block are wrapped in
 * [Res.Err.UncaughtThrown].
 *
 * ```
 * val result: Res<String> = res {
 *   val id = parseId(input).ok()
 *   val user = catch {
 *     message { "failed to fetch user $id" }
 *     fetchUser(id)
 *   }
 *   user.name
 * }
 * ```
 *
 * @param block the DSL block to execute
 * @return [Res.Ok] on success, or a [Res.Err] subtype on failure
 */
inline fun <T> res(block: ResDsl.() -> T): Res<T> =
  try {
    Res.value(ResDsl.instance.block())
  } catch (exc: ErrException) {
    exc.err
  } catch (exc: Exception) {
    Res.Err.UncaughtThrown(exc)
  }

/**
 * Wraps a single throwing operation into a [Res], with [CatchScope] message support.
 *
 * Unlike [res]`{ catch { ... } }`, this avoids the outer DSL scope when all you need
 * is exception-to-[Res] conversion. Every exception becomes [Res.Err.Thrown] — there
 * is no [Res.Err.UncaughtThrown] case since there is no outer DSL to escape from.
 *
 * ```
 * val config: Res<Config> = catch {
 *   message { "failed to load config from $path" }
 *   loadConfig(path)
 * }
 * ```
 *
 * @param block the code to execute within a [CatchScope]
 * @return [Res.Ok] with the block's result, or [Res.Err.Thrown] if an exception was thrown
 * @see res for the full DSL with [ResDsl.ok], [ResDsl.err], and chaining support
 */
inline fun <T> catch(block: CatchScope.() -> T): Res<T> {
  val scope = CatchScope()
  return try {
    Res.value(scope.block())
  } catch (exc: Exception) {
    val msg = scope._message?.invoke()
    if (msg != null) Res.error(exc, msg) else Res.error(exc)
  }
}
