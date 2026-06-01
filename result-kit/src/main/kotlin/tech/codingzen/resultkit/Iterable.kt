@file:Suppress("UNCHECKED_CAST", "USELESS_CAST")

package tech.codingzen.resultkit

// -- Querying --

/** Returns `true` if every element is [Ok][Res.isOk]. Returns `true` for empty iterables. */
public fun <V, E> Iterable<Res<V, E>>.allOk(): Boolean {
    for (res in this) if (res.inlineValue is Failure) return false
    return true
}

/** Returns `true` if at least one element is [Ok][Res.isOk]. Returns `false` for empty iterables. */
public fun <V, E> Iterable<Res<V, E>>.anyOk(): Boolean {
    for (res in this) if (res.inlineValue !is Failure) return true
    return false
}

/** Returns `true` if at least one element is a [failure][Res.isFail]. Returns `false` for empty iterables. */
public fun <V, E> Iterable<Res<V, E>>.anyFail(): Boolean {
    for (res in this) if (res.inlineValue is Failure) return true
    return false
}

// -- Filtering --

/**
 * Returns a list of all Ok values, discarding Fail elements. See also [filterFail], [partition].
 *
 * The returned list is the internal accumulator; treat it as read-only (do not downcast and mutate).
 */
public fun <V, E> Iterable<Res<V, E>>.filterOk(): List<V> {
    val result = mutableListOf<V>()
    for (res in this) if (res.inlineValue !is Failure) result.add(res.inlineValue as V)
    return result
}

/**
 * Returns a list of all Fail errors, discarding Ok elements. See also [filterOk], [partition].
 *
 * The returned list is the internal accumulator; treat it as read-only (do not downcast and mutate).
 *
 * Errors are returned as bare `E`, so any context frames are dropped. Use [filterFailFramed] to
 * retain each error's frames.
 */
public fun <V, E> Iterable<Res<V, E>>.filterFail(): List<E> {
    val result = mutableListOf<E>()
    for (res in this) if (res.inlineValue is Failure) result.add((res.inlineValue as Failure).error as E)
    return result
}

/**
 * Frame-retaining variant of [filterFail]: each Fail element contributes a [FramedError] pairing its
 * error with the context frames it carried. See also [partitionFramed].
 *
 * The returned list is the internal accumulator; treat it as read-only (do not downcast and mutate).
 */
public fun <V, E> Iterable<Res<V, E>>.filterFailFramed(): List<FramedError<E>> {
    val result = mutableListOf<FramedError<E>>()
    for (res in this) if (res.inlineValue is Failure) {
        result.add(FramedError((res.inlineValue as Failure).error as E, (res.inlineValue as Failure).frames))
    }
    return result
}

// -- Combining --

/**
 * Collects all Ok values into a single `Res<List<V>, E>`, short-circuiting on the first Fail.
 *
 * For error accumulation instead of fail-fast, use [zipOrAccumulate] or [partition].
 *
 * The Ok list is the internal accumulator; treat it as read-only (do not downcast and mutate).
 */
public fun <V, E> Iterable<Res<V, E>>.combine(): Res<List<V>, E> {
    val values = mutableListOf<V>()
    for (res in this) {
        if (res.inlineValue is Failure) return Res(res.inlineValue)
        values.add(res.inlineValue as V)
    }
    return Res.unsafeOk(values)
}

/**
 * Splits into a [Pair] of Ok values and Fail errors. Every element is categorized. See also [filterOk], [filterFail].
 *
 * Both lists are internal accumulators; treat them as read-only (do not downcast and mutate).
 *
 * Errors are returned as bare `E`, so any context frames are dropped. Use [partitionFramed] to
 * retain each error's frames.
 */
public fun <V, E> Iterable<Res<V, E>>.partition(): Pair<List<V>, List<E>> {
    val oks = mutableListOf<V>()
    val fails = mutableListOf<E>()
    for (res in this) {
        if (res.inlineValue is Failure) fails.add((res.inlineValue as Failure).error as E)
        else oks.add(res.inlineValue as V)
    }
    return Pair(oks, fails)
}

/**
 * Frame-retaining variant of [partition]: Fail errors are returned as [FramedError], each pairing its
 * error with the context frames it carried. Ok values are unchanged.
 *
 * Both lists are internal accumulators; treat them as read-only (do not downcast and mutate).
 */
public fun <V, E> Iterable<Res<V, E>>.partitionFramed(): Pair<List<V>, List<FramedError<E>>> {
    val oks = mutableListOf<V>()
    val fails = mutableListOf<FramedError<E>>()
    for (res in this) {
        if (res.inlineValue is Failure) fails.add(FramedError((res.inlineValue as Failure).error as E, (res.inlineValue as Failure).frames))
        else oks.add(res.inlineValue as V)
    }
    return Pair(oks, fails)
}

// -- Fallible iteration --

/**
 * Maps each element through a failable [transform], short-circuiting on the first Fail.
 *
 * @param transform applied to each element; returns a [Res].
 * @return Ok with the list of transformed values, or the first Fail encountered.
 *
 * The Ok list is the internal accumulator; treat it as read-only (do not downcast and mutate).
 */
public inline fun <V, U, E> Iterable<V>.tryMap(transform: (V) -> Res<U, E>): Res<List<U>, E> {
    val results = mutableListOf<U>()
    for (item in this) {
        val res = transform(item)
        if (res.inlineValue is Failure) return Res(res.inlineValue)
        results.add(res.inlineValue as U)
    }
    return Res.unsafeOk(results)
}

/**
 * Executes a failable [action] on each element, short-circuiting on the first Fail.
 *
 * @return `Res<Unit, E>` — Ok if all actions succeeded, or the first Fail encountered.
 */
public inline fun <V, E> Iterable<V>.tryForEach(action: (V) -> Res<*, E>): Res<Unit, E> {
    for (item in this) {
        val res = action(item)
        if (res.inlineValue is Failure) return Res(res.inlineValue)
    }
    return Res.unsafeOk(Unit)
}

