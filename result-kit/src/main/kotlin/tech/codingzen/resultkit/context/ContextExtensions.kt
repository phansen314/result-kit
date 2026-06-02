@file:Suppress("UNCHECKED_CAST")

package tech.codingzen.resultkit.context

import tech.codingzen.resultkit.Failure
import tech.codingzen.resultkit.Res
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Attaches a context message to a Fail result. No-op on Ok.
 *
 * The [message] lambda is only invoked when this result is Fail — zero cost on the Ok path.
 *
 * Frames are stored innermost-first: each call appends a new frame, so index 0 is the
 * first-attached (innermost/closest-to-error) context and the last index is the outermost.
 *
 * Each call on a Fail allocates a new [Frame], a new `Failure`, and a copy of the frames list.
 * The Ok path is allocation-free. Chaining many `.context()` calls per failure in tight loops
 * is therefore not free; group them or use [contextFrame] with a pre-built frame if needed.
 *
 * ```
 * fun loadUser(id: Int): Res<User, AppError> =
 *     repository.findById(id)
 *         .context { "loading user id=$id" }
 * ```
 */
@OptIn(ExperimentalContracts::class)
public inline fun <V, E> Res<V, E>.context(
    message: () -> String,
): Res<V, E> {
    contract { callsInPlace(message, InvocationKind.AT_MOST_ONCE) }
    val underlying = inlineValue
    if (underlying !is Failure) return this
    return Res(
        Failure(
            error = underlying.error,
            frames = underlying.frames + Frame(message = message()),
        )
    )
}

/**
 * Attaches a context message and source location to a Fail result. No-op on Ok.
 *
 * Both lambdas are only invoked when this result is Fail — zero cost on the Ok path.
 *
 * ```
 * fun loadUser(id: Int): Res<User, AppError> =
 *     repository.findById(id)
 *         .context(
 *             { "loading user id=$id" },
 *             { SourceLocation("UserService.kt", 42, "loadUser") },
 *         )
 * ```
 */
@OptIn(ExperimentalContracts::class)
public inline fun <V, E> Res<V, E>.context(
    message: () -> String,
    location: () -> SourceLocation,
): Res<V, E> {
    contract {
        callsInPlace(message, InvocationKind.AT_MOST_ONCE)
        callsInPlace(location, InvocationKind.AT_MOST_ONCE)
    }
    val underlying = inlineValue
    if (underlying !is Failure) return this
    return Res(
        Failure(
            error = underlying.error,
            frames = underlying.frames + Frame(message = message(), location = location()),
        )
    )
}

/**
 * Attaches a context frame (message + optional attachment + optional location) to a Fail result.
 * No-op on Ok. The [frame] lambda is only invoked when this result is Fail.
 */
@OptIn(ExperimentalContracts::class)
public inline fun <V, E> Res<V, E>.contextFrame(
    frame: () -> Frame,
): Res<V, E> {
    contract { callsInPlace(frame, InvocationKind.AT_MOST_ONCE) }
    val underlying = inlineValue
    if (underlying !is Failure) return this
    return Res(
        Failure(
            error = underlying.error,
            frames = underlying.frames + frame(),
        )
    )
}

/**
 * Exhaustively handles this result, passing both the error and its context frame chain to [onFail].
 *
 * Disambiguated from the standard `fold(onOk, onFail: (E) -> T)` by the two-parameter [onFail] lambda.
 *
 * ```
 * result.fold(
 *     onOk = { value -> respond(value) },
 *     onFail = { error, frames ->
 *         logger.error(renderContext())
 *         respond(error)
 *     }
 * )
 * ```
 */
@OptIn(ExperimentalContracts::class)
public inline fun <V, E, T> Res<V, E>.fold(
    onOk: (V) -> T,
    onFail: (error: E, context: List<Frame>) -> T,
): T {
    contract {
        callsInPlace(onOk, InvocationKind.AT_MOST_ONCE)
        callsInPlace(onFail, InvocationKind.AT_MOST_ONCE)
    }
    return if (inlineValue is Failure)
        onFail(inlineValue.error as E, inlineValue.frames)
    else
        onOk(inlineValue as V)
}

/**
 * Frame-aware variant of [tech.codingzen.resultkit.recover]. Converts a Fail to Ok by applying
 * [transform] to both the error and its context frames. An Ok passes through unchanged.
 *
 * The frames are passed to the transform before being discarded — use this when recovery
 * needs to observe the trail (e.g. logging the recovered failure with full context) without
 * splitting into a separate `.tap` + `.recover` chain.
 *
 * Disambiguated from the standard `recover(transform: (E) -> V)` by the two-parameter lambda.
 *
 * ```
 * val safe: Res<Config, Nothing> = loadFromDisk().recover { err, frames ->
 *     logger.warn("falling back to defaults — was: ${frames.joinToString(" / ")}, err=$err")
 *     Config.defaults()
 * }
 * ```
 */
@OptIn(ExperimentalContracts::class)
public inline fun <V, E> Res<V, E>.recover(
    transform: (error: E, context: List<Frame>) -> @UnsafeVariance V,
): Res<V, Nothing> {
    contract { callsInPlace(transform, InvocationKind.AT_MOST_ONCE) }
    return if (inlineValue is Failure)
        Res.unsafeOk(transform(inlineValue.error as E, inlineValue.frames))
    else
        Res(inlineValue)
}
