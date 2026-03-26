package tech.codingzen.resultkit

sealed class Res<out V, out E> {
    class Ok<V> @PublishedApi internal constructor(val value: V) : Res<V, Nothing>() {
        override fun equals(other: Any?) = other is Ok<*> && other.value == value
        override fun hashCode() = value.hashCode()
        override fun toString() = "Ok($value)"
    }

    class Fail<E> @PublishedApi internal constructor(val error: E) : Res<Nothing, E>() {
        override fun equals(other: Any?) = other is Fail<*> && other.error == error
        override fun hashCode() = error.hashCode()
        override fun toString() = "Fail($error)"
    }

    inline fun <T> fold(onOk: (V) -> T, onFail: (E) -> T): T = when (this) {
        is Ok -> onOk(value)
        is Fail -> onFail(error)
    }
}

inline fun <V, E, U> Res<V, E>.map(transform: (V) -> U): Res<U, E> = when (this) {
    is Res.Ok -> Res.Ok(transform(value))
    is Res.Fail -> this
}

inline fun <V, E, F> Res<V, E>.mapError(transform: (E) -> F): Res<V, F> = when (this) {
    is Res.Ok -> this
    is Res.Fail -> Res.Fail(transform(error))
}

// @UnsafeVariance is safe here: the lambda produces a V, it doesn't consume a covariant V
inline fun <V, E> Res<V, E>.getOrElse(default: (E) -> @UnsafeVariance V): V = when (this) {
    is Res.Ok -> value
    is Res.Fail -> default(error)
}

inline fun <V, E> Res<V, E>.onOk(action: (V) -> Unit): Res<V, E> {
    if (this is Res.Ok) action(value)
    return this
}

inline fun <V, E> Res<V, E>.onFail(action: (E) -> Unit): Res<V, E> {
    if (this is Res.Fail) action(error)
    return this
}

fun <V, E : Throwable> Res<V, E>.getOrThrow(): V = when (this) {
    is Res.Ok -> value
    is Res.Fail -> throw error
}

inline fun <V, E> Res<V, E>.getOrThrow(transform: (E) -> Throwable): V = when (this) {
    is Res.Ok -> value
    is Res.Fail -> throw transform(error)
}

fun <V> ok(value: V): Res<V, Nothing> = Res.Ok(value)

fun <E> failure(error: E): Res<Nothing, E> = Res.Fail(error)
