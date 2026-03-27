package tech.codingzen.resultkit

@PublishedApi
internal class Failure(@JvmField val error: Any?) {
    override fun equals(other: Any?) = other is Failure && other.error == error
    override fun hashCode() = error.hashCode()
    override fun toString() = "Fail($error)"
}

@JvmInline
value class Res<out V, out E> @PublishedApi internal constructor(
    @PublishedApi internal val inlineValue: Any?
) {
    val isOk: Boolean get() = inlineValue !is Failure
    val isFail: Boolean get() = inlineValue is Failure

    inline fun <T> fold(onOk: (V) -> T, onFail: (E) -> T): T =
        if (inlineValue is Failure) {
            @Suppress("UNCHECKED_CAST")
            onFail(inlineValue.error as E)
        } else {
            @Suppress("UNCHECKED_CAST")
            onOk(inlineValue as V)
        }

    override fun toString(): String =
        if (inlineValue is Failure) "Fail(${inlineValue.error})" else "Ok($inlineValue)"
}

val <V, E> Res<V, E>.value: V
    get() {
        check(isOk) { "Called value on a Fail: $this" }
        @Suppress("UNCHECKED_CAST")
        return inlineValue as V
    }

val <V, E> Res<V, E>.error: E
    get() {
        check(isFail) { "Called error on an Ok: $this" }
        @Suppress("UNCHECKED_CAST")
        return (inlineValue as Failure).error as E
    }

val <V, E> Res<V, E>.valueOrNull: V?
    get() = if (inlineValue is Failure) null else {
        @Suppress("UNCHECKED_CAST")
        inlineValue as V
    }

val <V, E> Res<V, E>.errorOrNull: E?
    get() = if (inlineValue is Failure) {
        @Suppress("UNCHECKED_CAST")
        (inlineValue as Failure).error as E
    } else null

inline fun <V, E, U> Res<V, E>.map(transform: (V) -> U): Res<U, E> =
    if (inlineValue is Failure) {
        @Suppress("UNCHECKED_CAST")
        Res(inlineValue)
    } else {
        @Suppress("UNCHECKED_CAST")
        Res(transform(inlineValue as V))
    }

inline fun <V, E, F> Res<V, E>.mapError(transform: (E) -> F): Res<V, F> =
    if (inlineValue is Failure) {
        @Suppress("UNCHECKED_CAST")
        Res(Failure(transform(inlineValue.error as E)))
    } else {
        @Suppress("UNCHECKED_CAST")
        Res(inlineValue)
    }

// @UnsafeVariance is safe here: the lambda produces a V, it doesn't consume a covariant V in recover
inline fun <V, E> Res<V, E>.recover(transform: (E) -> @UnsafeVariance V): Res<V, Nothing> =
    if (inlineValue is Failure) {
        @Suppress("UNCHECKED_CAST")
        Res(transform(inlineValue.error as E))
    } else {
        @Suppress("UNCHECKED_CAST")
        Res(inlineValue)
    }

// @UnsafeVariance is safe here: the lambda produces a V, it doesn't consume a covariant V
inline fun <V, E> Res<V, E>.getOrElse(default: (E) -> @UnsafeVariance V): V =
    if (inlineValue is Failure) {
        @Suppress("UNCHECKED_CAST")
        default(inlineValue.error as E)
    } else {
        @Suppress("UNCHECKED_CAST")
        inlineValue as V
    }

inline fun <V, E> Res<V, E>.onOk(action: (V) -> Unit): Res<V, E> {
    if (inlineValue !is Failure) {
        @Suppress("UNCHECKED_CAST")
        action(inlineValue as V)
    }
    return this
}

inline fun <V, E> Res<V, E>.onFail(action: (E) -> Unit): Res<V, E> {
    if (inlineValue is Failure) {
        @Suppress("UNCHECKED_CAST")
        action(inlineValue.error as E)
    }
    return this
}

fun <V, E : Throwable> Res<V, E>.getOrThrow(): V =
    if (inlineValue is Failure) {
        @Suppress("UNCHECKED_CAST")
        throw inlineValue.error as E
    } else {
        @Suppress("UNCHECKED_CAST")
        inlineValue as V
    }

inline fun <V, E> Res<V, E>.getOrThrow(transform: (E) -> Throwable): V =
    if (inlineValue is Failure) {
        @Suppress("UNCHECKED_CAST")
        throw transform(inlineValue.error as E)
    } else {
        @Suppress("UNCHECKED_CAST")
        inlineValue as V
    }

fun <V> ok(value: V): Res<V, Nothing> = Res(value)

fun <E> failure(error: E): Res<Nothing, E> = Res(Failure(error))
