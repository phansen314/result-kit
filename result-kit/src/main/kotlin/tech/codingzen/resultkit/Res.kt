@file:Suppress("UNCHECKED_CAST")
@file:OptIn(ExperimentalContracts::class)

package tech.codingzen.resultkit

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import tech.codingzen.resultkit.context.Frame

@PublishedApi
internal class Failure(
    @JvmField val error: Any?,
    @JvmField val frames: List<Frame> = emptyList(),
) {
    override fun equals(other: Any?) = other is Failure && other.error == error && other.frames == frames
    override fun hashCode() = ((error?.hashCode() ?: 0) xor 0x4641494C) * 31 + frames.hashCode()
    override fun toString() = if (frames.isEmpty()) "Fail($error)" else "Fail($error, frames=$frames)"
}

/**
 * A result type that represents either a success ([Ok][isOk]) value of type [V]
 * or a failure ([Fail][isFail]) error of type [E].
 *
 * `Res` is a `@JvmInline value class` wrapping `Any?`. The Ok path stores the raw value
 * with zero allocation; the Fail path wraps the error in an internal sentinel (one allocation).
 *
 * Because `Res` is a value class (not sealed), it cannot be used in exhaustive `when` expressions.
 * Use [fold] for exhaustive handling instead.
 *
 * Both type parameters are covariant (`out`), so `Res<String, IOException>` is assignable to
 * `Res<Any, Throwable>`.
 *
 * For composing multiple failable operations, use the [rail] DSL with [Rail.orFail] rather than
 * chaining [flatMap] calls.
 *
 * ```
 * val res: Res<Int, String> = Res.ok(42)
 * val value: String = res.fold(
 *     onOk = { "got $it" },
 *     onFail = { "error: $it" }
 * )
 * ```
 */
// Nested Res<Res<...>, ...> is safe: inner Res gets boxed when stored as Any?,
// so the outer inlineValue sees a boxed Res object, never a raw Failure.
@JvmInline
public value class Res<out V, out E> @PublishedApi internal constructor(
    @PublishedApi internal val inlineValue: Any?
) {
    /** `true` if this result contains a success value. */
    public val isOk: Boolean get() = inlineValue !is Failure
    /** `true` if this result contains a failure error. */
    public val isFail: Boolean get() = inlineValue is Failure

    override fun toString(): String =
        if (inlineValue is Failure) inlineValue.toString() else "Ok($inlineValue)"

    public companion object {
        /**
         * Creates an Ok result containing [value].
         *
         * @param value the success value to wrap.
         * @return a [Res] in the Ok state.
         */
        // Defensive guard: Failure is internal, so user code can't pass one here.
        // Cost: one instanceof check per ok() call. Internal hot paths (map, recover)
        // use unsafeOk() to skip this check.
        @Suppress("NOTHING_TO_INLINE")
        public inline fun <V> ok(value: V): Res<V, Nothing> {
            check(value !is Failure) { "Res.ok() received an internal sentinel value — this is a result-kit bug, please report it" }
            return Res(value)
        }
        /**
         * Creates a Fail result containing [error].
         *
         * @param error the failure error to wrap.
         * @return a [Res] in the Fail state.
         */
        // No guard needed — wrapping in Failure is always safe. Cost: one Failure allocation.
        @Suppress("NOTHING_TO_INLINE")
        public inline fun <E> failure(error: E): Res<Nothing, E> = Res(Failure(error))

        // SAFETY: Callers must guarantee value is not a Failure instance.
        // This is enforced by Failure being internal — user transform lambdas in
        // map/recover cannot return Failure. If Failure ever becomes accessible
        // outside this module, all unsafeOk call sites must switch to ok().
        @PublishedApi
        internal fun <V> unsafeOk(value: V): Res<V, Nothing> = Res(value)
    }
}

/** Returns the Ok value, or `null` if this is a Fail. See also [errorOrNull]. */
@Suppress("NOTHING_TO_INLINE")
public inline fun <V, E> Res<V, E>.getOrNull(): V? =
    if (inlineValue is Failure) null else inlineValue as V

/** Returns the Fail error, or `null` if this is Ok. See also [getOrNull]. */
@Suppress("NOTHING_TO_INLINE", "USELESS_CAST")
public inline fun <V, E> Res<V, E>.errorOrNull(): E? =
    if (inlineValue is Failure) (inlineValue as Failure).error as E else null

/**
 * Handles both cases exhaustively, applying [onOk] for a success value or [onFail] for a failure error.
 *
 * This is the recommended way to consume a [Res], since `when` exhaustiveness checks are not
 * available on inline value classes.
 *
 * @param onOk called with the success value if this is Ok.
 * @param onFail called with the failure error if this is Fail.
 * @return the result of whichever branch was taken.
 */
public inline fun <V, E, T> Res<V, E>.fold(onOk: (V) -> T, onFail: (E) -> T): T {
    contract {
        callsInPlace(onOk, InvocationKind.AT_MOST_ONCE)
        callsInPlace(onFail, InvocationKind.AT_MOST_ONCE)
    }
    return if (inlineValue is Failure) onFail(inlineValue.error as E)
    else onOk(inlineValue as V)
}

/**
 * Transforms the Ok value using [transform], leaving a Fail unchanged.
 *
 * @param transform applied to the success value if this is Ok.
 * @return Ok with the transformed value, or the original Fail.
 */
public inline fun <V, E, U> Res<V, E>.map(transform: (V) -> U): Res<U, E> {
    contract { callsInPlace(transform, InvocationKind.AT_MOST_ONCE) }
    return if (inlineValue is Failure) Res(inlineValue)
    else Res.unsafeOk(transform(inlineValue as V))
}

/**
 * Transforms the Fail error using [transform], leaving an Ok unchanged.
 *
 * @param transform applied to the failure error if this is Fail.
 * @return Fail with the transformed error, or the original Ok.
 */
public inline fun <V, E, F> Res<V, E>.mapError(transform: (E) -> F): Res<V, F> {
    contract { callsInPlace(transform, InvocationKind.AT_MOST_ONCE) }
    return if (inlineValue is Failure) Res(Failure(transform(inlineValue.error as E), inlineValue.frames))
    else Res(inlineValue)
}

/**
 * Converts a Fail to Ok by applying [transform] to the error. An Ok passes through unchanged.
 *
 * The error type becomes `Nothing` because recovery is infallible.
 *
 * @param transform converts the failure error into a success value.
 * @return Ok with either the original value or the recovered value.
 */
// Infallible fallback: transform always produces a value, so error type becomes Nothing.
// @UnsafeVariance is safe — the lambda produces a V, it doesn't consume a covariant V.
public inline fun <V, E> Res<V, E>.recover(transform: (E) -> @UnsafeVariance V): Res<V, Nothing> {
    contract { callsInPlace(transform, InvocationKind.AT_MOST_ONCE) }
    return if (inlineValue is Failure) Res.unsafeOk(transform(inlineValue.error as E))
    else Res(inlineValue)
}

/**
 * Attempts fallible recovery: applies [transform] to the error if this is Fail, returning a new
 * [Res] that may itself fail with a different error type [F]. An Ok passes through unchanged.
 *
 * For infallible recovery, use [recover] instead.
 *
 * @param transform converts the failure error into a new [Res].
 * @return the original Ok, or the result of [transform].
 */
// Fallible fallback: transform returns a new Res that may itself fail with error type F.
// @UnsafeVariance is safe — the lambda produces a Res<V, F>, it doesn't consume a covariant V.
public inline fun <V, E, F> Res<V, E>.orElse(transform: (E) -> Res<@UnsafeVariance V, F>): Res<V, F> {
    contract { callsInPlace(transform, InvocationKind.AT_MOST_ONCE) }
    return if (inlineValue is Failure) transform(inlineValue.error as E)
    else Res(inlineValue)
}

/**
 * Chains a failable computation on the Ok value. If this is Fail, returns the Fail unchanged.
 *
 * This is an escape hatch for composing `(V) -> Res<U, E>` outside [rail] blocks.
 * Prefer `rail { first().orFail().let { second(it).orFail() } }` for multi-step chains.
 *
 * @param transform applied to the success value; returns a new [Res].
 * @return the result of [transform] if Ok, or the original Fail.
 */
// Escape hatch for chaining (V) -> Res<U, E> outside rail {} blocks.
// Prefer rail { first().orFail().let { second(it).orFail() } } when inside the DSL.
// @UnsafeVariance is safe — the lambda produces a Res<U, E>, it doesn't consume a covariant E.
public inline fun <V, E, U> Res<V, E>.flatMap(transform: (V) -> Res<U, @UnsafeVariance E>): Res<U, E> {
    contract { callsInPlace(transform, InvocationKind.AT_MOST_ONCE) }
    return if (inlineValue is Failure) Res(inlineValue)
    else transform(inlineValue as V)
}

/**
 * Returns the Ok value, or computes a [default] from the error.
 *
 * @param default called with the failure error if this is Fail.
 * @return the success value or the computed default.
 */
// @UnsafeVariance is safe here: the lambda produces a V, it doesn't consume a covariant V
public inline fun <V, E> Res<V, E>.getOrElse(default: (E) -> @UnsafeVariance V): V {
    contract { callsInPlace(default, InvocationKind.AT_MOST_ONCE) }
    return if (inlineValue is Failure) default(inlineValue.error as E)
    else inlineValue as V
}

/**
 * Performs [action] on the Ok value if present, then returns `this` unchanged for chaining.
 * See also [onFail].
 */
public inline fun <V, E> Res<V, E>.onOk(action: (V) -> Unit): Res<V, E> {
    contract { callsInPlace(action, InvocationKind.AT_MOST_ONCE) }
    if (inlineValue !is Failure) action(inlineValue as V)
    return this
}

/**
 * Performs [action] on the Fail error if present, then returns `this` unchanged for chaining.
 * See also [onOk].
 */
public inline fun <V, E> Res<V, E>.onFail(action: (E) -> Unit): Res<V, E> {
    contract { callsInPlace(action, InvocationKind.AT_MOST_ONCE) }
    if (inlineValue is Failure) action(inlineValue.error as E)
    return this
}

/**
 * Returns the Ok value, or throws the Fail error directly.
 *
 * Requires `E : Throwable`. For non-throwable error types, use [getOrThrow] with a transform.
 */
@Suppress("NOTHING_TO_INLINE")
public inline fun <V, E : Throwable> Res<V, E>.getOrThrow(): V =
    if (inlineValue is Failure) throw inlineValue.error as E
    else inlineValue as V

/**
 * Returns the Ok value, or throws the result of applying [transform] to the Fail error.
 *
 * @param transform converts the failure error into a [Throwable] to throw.
 */
public inline fun <V, E> Res<V, E>.getOrThrow(transform: (E) -> Throwable): V {
    contract { callsInPlace(transform, InvocationKind.AT_MOST_ONCE) }
    return if (inlineValue is Failure) throw transform(inlineValue.error as E)
    else inlineValue as V
}

/**
 * Returns the Fail error, or throws [IllegalStateException] if this is Ok.
 *
 * Primarily useful in tests to extract the error for assertions.
 */
@Suppress("NOTHING_TO_INLINE", "USELESS_CAST")
public inline fun <V, E> Res<V, E>.errorOrThrow(): E {
    check(inlineValue is Failure) { "Called errorOrThrow() on an Ok: $this" }
    return (inlineValue as Failure).error as E
}

// -- Factories --

/**
 * Converts a nullable value to [Res]. Non-null becomes Ok; `null` becomes Fail with the
 * lazily-evaluated [error].
 *
 * @param error called to produce the failure error when `this` is `null`.
 */
public inline fun <V, E> V?.toResOr(error: () -> E): Res<V, E> {
    contract { callsInPlace(error, InvocationKind.AT_MOST_ONCE) }
    return if (this != null) Res.unsafeOk(this) else Res.failure(error())
}

// -- Conditional fail --

/**
 * Converts an Ok to Fail if [predicate] returns `true` for the success value.
 * A Fail passes through unchanged. See also [toFailUnless].
 *
 * @param predicate tested against the Ok value.
 * @param transform produces the error when [predicate] matches.
 */
public inline fun <V, E> Res<V, E>.toFailIf(
    predicate: (V) -> Boolean,
    transform: (V) -> @UnsafeVariance E
): Res<V, E> {
    contract {
        callsInPlace(predicate, InvocationKind.AT_MOST_ONCE)
        callsInPlace(transform, InvocationKind.AT_MOST_ONCE)
    }
    return if (inlineValue is Failure) this
    else if (predicate(inlineValue as V)) Res.failure(transform(inlineValue as V))
    else this
}

// -- Transformations --

/** Unwraps a nested `Res<Res<V, E>, E>` into a single `Res<V, E>`. */
@Suppress("NOTHING_TO_INLINE")
public inline fun <V, E> Res<Res<V, E>, E>.flatten(): Res<V, E> =
    if (inlineValue is Failure) Res(inlineValue)
    else inlineValue as Res<V, E>

// -- kotlin.Result interop --

/** Converts a stdlib [Result] to [Res]. Success becomes Ok, failure becomes Fail. See also [toResult]. */
@Suppress("NOTHING_TO_INLINE")
public inline fun <V> Result<V>.toRes(): Res<V, Throwable> =
    fold(onSuccess = { Res.ok(it) }, onFailure = { Res.failure(it) })

/** Converts this [Res] to a stdlib [Result]. Requires `E : Throwable`. See also [toRes]. */
@Suppress("NOTHING_TO_INLINE")
public inline fun <V, E : Throwable> Res<V, E>.toResult(): Result<V> =
    if (inlineValue is Failure) Result.failure(inlineValue.error as E)
    else Result.success(inlineValue as V)
