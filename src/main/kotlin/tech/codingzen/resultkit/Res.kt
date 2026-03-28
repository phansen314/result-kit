@file:Suppress("UNCHECKED_CAST")

package tech.codingzen.resultkit

@PublishedApi
internal class Failure(@JvmField val error: Any?) {
    override fun equals(other: Any?) = other is Failure && other.error == error
    override fun hashCode() = (error?.hashCode() ?: 0) xor 0x4641494C // "FAIL" in ASCII — prevents ok(null) / failure(null) hash collision
    override fun toString() = "Fail($error)"
}

// Nested Res<Res<...>, ...> is safe: inner Res gets boxed when stored as Any?,
// so the outer inlineValue sees a boxed Res object, never a raw Failure.
@JvmInline
public value class Res<out V, out E> @PublishedApi internal constructor(
    @PublishedApi internal val inlineValue: Any?
) {
    public val isOk: Boolean get() = inlineValue !is Failure
    public val isFail: Boolean get() = inlineValue is Failure

    override fun toString(): String =
        if (inlineValue is Failure) inlineValue.toString() else "Ok($inlineValue)"

    public companion object {
        // Defensive guard: Failure is internal, so user code can't pass one here.
        // Cost: one instanceof check per ok() call. Internal hot paths (map, recover)
        // use unsafeOk() to skip this check.
        @Suppress("NOTHING_TO_INLINE")
        public inline fun <V> ok(value: V): Res<V, Nothing> {
            check(value !is Failure) { "Res.ok() received an internal sentinel value — this is a result-kit bug, please report it" }
            return Res(value)
        }
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

@Suppress("NOTHING_TO_INLINE")
public inline fun <V, E> Res<V, E>.getOrNull(): V? =
    if (inlineValue is Failure) null else inlineValue as V

@Suppress("NOTHING_TO_INLINE", "USELESS_CAST")
public inline fun <V, E> Res<V, E>.errorOrNull(): E? =
    if (inlineValue is Failure) (inlineValue as Failure).error as E else null

public inline fun <V, E, T> Res<V, E>.fold(onOk: (V) -> T, onFail: (E) -> T): T =
    if (inlineValue is Failure) onFail(inlineValue.error as E)
    else onOk(inlineValue as V)

public inline fun <V, E, U> Res<V, E>.map(transform: (V) -> U): Res<U, E> =
    if (inlineValue is Failure) Res(inlineValue)
    else Res.unsafeOk(transform(inlineValue as V))

public inline fun <V, E, F> Res<V, E>.mapError(transform: (E) -> F): Res<V, F> =
    if (inlineValue is Failure) Res(Failure(transform(inlineValue.error as E)))
    else Res(inlineValue)

// Infallible fallback: transform always produces a value, so error type becomes Nothing.
// @UnsafeVariance is safe — the lambda produces a V, it doesn't consume a covariant V.
public inline fun <V, E> Res<V, E>.recover(transform: (E) -> @UnsafeVariance V): Res<V, Nothing> =
    if (inlineValue is Failure) Res.unsafeOk(transform(inlineValue.error as E))
    else Res(inlineValue)

// Fallible fallback: transform returns a new Res that may itself fail with error type F.
// @UnsafeVariance is safe — the lambda produces a Res<V, F>, it doesn't consume a covariant V.
public inline fun <V, E, F> Res<V, E>.orElse(transform: (E) -> Res<@UnsafeVariance V, F>): Res<V, F> =
    if (inlineValue is Failure) transform(inlineValue.error as E)
    else Res(inlineValue)

// Escape hatch for chaining (V) -> Res<U, E> outside rail {} blocks.
// Prefer rail { first().orFail().let { second(it).orFail() } } when inside the DSL.
// @UnsafeVariance is safe — the lambda produces a Res<U, E>, it doesn't consume a covariant E.
public inline fun <V, E, U> Res<V, E>.flatMap(transform: (V) -> Res<U, @UnsafeVariance E>): Res<U, E> =
    if (inlineValue is Failure) Res(inlineValue)
    else transform(inlineValue as V)

// @UnsafeVariance is safe here: the lambda produces a V, it doesn't consume a covariant V
public inline fun <V, E> Res<V, E>.getOrElse(default: (E) -> @UnsafeVariance V): V =
    if (inlineValue is Failure) default(inlineValue.error as E)
    else inlineValue as V

public inline fun <V, E> Res<V, E>.onOk(action: (V) -> Unit): Res<V, E> {
    if (inlineValue !is Failure) action(inlineValue as V)
    return this
}

public inline fun <V, E> Res<V, E>.onFail(action: (E) -> Unit): Res<V, E> {
    if (inlineValue is Failure) action(inlineValue.error as E)
    return this
}

@Suppress("NOTHING_TO_INLINE")
public inline fun <V, E : Throwable> Res<V, E>.getOrThrow(): V =
    if (inlineValue is Failure) throw inlineValue.error as E
    else inlineValue as V

public inline fun <V, E> Res<V, E>.getOrThrow(transform: (E) -> Throwable): V =
    if (inlineValue is Failure) throw transform(inlineValue.error as E)
    else inlineValue as V

@Suppress("NOTHING_TO_INLINE", "USELESS_CAST")
public inline fun <V, E> Res<V, E>.errorOrThrow(): E {
    check(inlineValue is Failure) { "Called errorOrThrow() on an Ok: $this" }
    return (inlineValue as Failure).error as E
}

// -- kotlin.Result interop --

public fun <V> Result<V>.toRes(): Res<V, Throwable> =
    fold(onSuccess = { Res.ok(it) }, onFailure = { Res.failure(it) })

@Suppress("NOTHING_TO_INLINE")
public inline fun <V, E : Throwable> Res<V, E>.toResult(): Result<V> =
    if (inlineValue is Failure) Result.failure(inlineValue.error as E)
    else Result.success(inlineValue as V)
