@file:Suppress("UNCHECKED_CAST", "USELESS_CAST")

package tech.codingzen.resultkit

// -- Querying --

public fun <V, E> Iterable<Res<V, E>>.allOk(): Boolean {
    for (res in this) if (res.inlineValue is Failure) return false
    return true
}

public fun <V, E> Iterable<Res<V, E>>.allFail(): Boolean {
    for (res in this) if (res.inlineValue !is Failure) return false
    return true
}

public fun <V, E> Iterable<Res<V, E>>.anyOk(): Boolean {
    for (res in this) if (res.inlineValue !is Failure) return true
    return false
}

public fun <V, E> Iterable<Res<V, E>>.anyFail(): Boolean {
    for (res in this) if (res.inlineValue is Failure) return true
    return false
}

// -- Filtering --

public fun <V, E> Iterable<Res<V, E>>.filterOk(): List<V> {
    val result = mutableListOf<V>()
    for (res in this) if (res.inlineValue !is Failure) result.add(res.inlineValue as V)
    return result
}

public fun <V, E> Iterable<Res<V, E>>.filterFail(): List<E> {
    val result = mutableListOf<E>()
    for (res in this) if (res.inlineValue is Failure) result.add((res.inlineValue as Failure).error as E)
    return result
}

// -- Combining --

public fun <V, E> Iterable<Res<V, E>>.combine(): Res<List<V>, E> {
    val values = mutableListOf<V>()
    for (res in this) {
        if (res.inlineValue is Failure) return Res(res.inlineValue)
        values.add(res.inlineValue as V)
    }
    return Res.unsafeOk(values)
}

public fun <V, E> Iterable<Res<V, E>>.partition(): Pair<List<V>, List<E>> {
    val oks = mutableListOf<V>()
    val fails = mutableListOf<E>()
    for (res in this) {
        if (res.inlineValue is Failure) fails.add((res.inlineValue as Failure).error as E)
        else oks.add(res.inlineValue as V)
    }
    return Pair(oks, fails)
}

// -- Fallible iteration --

public inline fun <V, U, E> Iterable<V>.tryMap(transform: (V) -> Res<U, E>): Res<List<U>, E> {
    val results = mutableListOf<U>()
    for (item in this) {
        val res = transform(item)
        if (res.inlineValue is Failure) return Res(res.inlineValue)
        results.add(res.inlineValue as U)
    }
    return Res.unsafeOk(results)
}

public inline fun <V, E> Iterable<V>.tryForEach(action: (V) -> Res<*, E>): Res<Unit, E> {
    for (item in this) {
        val res = action(item)
        if (res.inlineValue is Failure) return Res(res.inlineValue)
    }
    return Res.unsafeOk(Unit)
}

public inline fun <V, E> Iterable<V>.tryFilter(predicate: (V) -> Res<Boolean, E>): Res<List<V>, E> {
    val results = mutableListOf<V>()
    for (item in this) {
        val res = predicate(item)
        if (res.inlineValue is Failure) return Res(res.inlineValue)
        if (res.inlineValue as Boolean) results.add(item)
    }
    return Res.unsafeOk(results)
}
