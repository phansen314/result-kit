@file:Suppress("UNCHECKED_CAST", "USELESS_CAST")

package tech.codingzen.resultkit

// Lazy counterparts of the Iterable extensions. Intermediate operations (filterOk, filterFail)
// stay lazy and return Sequence; terminal operations short-circuit, so on a lazy source the
// elements after the first Fail are never evaluated.

// -- Querying (terminal, short-circuiting) --

/** Returns `true` if every element is [Ok][Res.isOk]. Returns `true` for empty sequences. */
public fun <V, E> Sequence<Res<V, E>>.allOk(): Boolean {
    for (res in this) if (res.inlineValue is Failure) return false
    return true
}

/** Returns `true` if at least one element is [Ok][Res.isOk]. Returns `false` for empty sequences. */
public fun <V, E> Sequence<Res<V, E>>.anyOk(): Boolean {
    for (res in this) if (res.inlineValue !is Failure) return true
    return false
}

/** Returns `true` if at least one element is a [failure][Res.isFail]. Returns `false` for empty sequences. */
public fun <V, E> Sequence<Res<V, E>>.anyFail(): Boolean {
    for (res in this) if (res.inlineValue is Failure) return true
    return false
}

// -- Filtering (lazy, intermediate) --

/**
 * Returns a lazy [Sequence] of the Ok values, skipping Fail elements. See also [filterFail], [partition].
 *
 * Nothing is evaluated until the result is consumed by a terminal operation.
 */
public fun <V, E> Sequence<Res<V, E>>.filterOk(): Sequence<V> =
    filter { it.inlineValue !is Failure }.map { it.inlineValue as V }

/**
 * Returns a lazy [Sequence] of the Fail errors, skipping Ok elements. See also [filterOk], [partition].
 *
 * Nothing is evaluated until the result is consumed by a terminal operation.
 */
public fun <V, E> Sequence<Res<V, E>>.filterFail(): Sequence<E> =
    filter { it.inlineValue is Failure }.map { (it.inlineValue as Failure).error as E }

// -- Combining (terminal) --

/**
 * Collects all Ok values into a single `Res<List<V>, E>`, short-circuiting on the first Fail.
 *
 * On a lazy source, elements after the first Fail are never evaluated. For categorizing every
 * element instead of fail-fast, use [partition].
 *
 * The Ok list is the internal accumulator; treat it as read-only (do not downcast and mutate).
 */
public fun <V, E> Sequence<Res<V, E>>.combine(): Res<List<V>, E> {
    val values = mutableListOf<V>()
    for (res in this) {
        if (res.inlineValue is Failure) return Res(res.inlineValue)
        values.add(res.inlineValue as V)
    }
    return Res.unsafeOk(values)
}

/**
 * Splits into a [Pair] of Ok values and Fail errors. Every element is evaluated and categorized.
 * See also [filterOk], [filterFail].
 *
 * Both lists are internal accumulators; treat them as read-only (do not downcast and mutate).
 */
public fun <V, E> Sequence<Res<V, E>>.partition(): Pair<List<V>, List<E>> {
    val oks = mutableListOf<V>()
    val fails = mutableListOf<E>()
    for (res in this) {
        if (res.inlineValue is Failure) fails.add((res.inlineValue as Failure).error as E)
        else oks.add(res.inlineValue as V)
    }
    return Pair(oks, fails)
}

// -- Fallible iteration (terminal) --

/**
 * Maps each element through a failable [transform], short-circuiting on the first Fail.
 *
 * On a lazy source, elements after the first Fail are never pulled or transformed.
 *
 * @param transform applied to each element; returns a [Res].
 * @return Ok with the list of transformed values, or the first Fail encountered.
 *
 * The Ok list is the internal accumulator; treat it as read-only (do not downcast and mutate).
 */
public inline fun <V, U, E> Sequence<V>.tryMap(transform: (V) -> Res<U, E>): Res<List<U>, E> {
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
 * On a lazy source, elements after the first Fail are never pulled.
 *
 * @return `Res<Unit, E>` — Ok if all actions succeeded, or the first Fail encountered.
 */
public inline fun <V, E> Sequence<V>.tryForEach(action: (V) -> Res<*, E>): Res<Unit, E> {
    for (item in this) {
        val res = action(item)
        if (res.inlineValue is Failure) return Res(res.inlineValue)
    }
    return Res.unsafeOk(Unit)
}
