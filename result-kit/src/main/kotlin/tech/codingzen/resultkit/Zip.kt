@file:Suppress("UNCHECKED_CAST", "USELESS_CAST")
@file:OptIn(ExperimentalContracts::class)

package tech.codingzen.resultkit

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

// -- zip (fail-fast) --

/**
 * Evaluates [block1] and [block2] sequentially, short-circuiting on the first Fail.
 * If both succeed, applies [transform] to the Ok values.
 *
 * Blocks are evaluated in order — [block2] is **not** called if [block1] fails.
 *
 * @return Ok with the transformed value, or the first Fail encountered.
 */
public inline fun <V1, V2, E, R> zip(
    block1: () -> Res<V1, E>,
    block2: () -> Res<V2, E>,
    transform: (V1, V2) -> R
): Res<R, E> {
    contract {
        callsInPlace(block1, InvocationKind.EXACTLY_ONCE)
        callsInPlace(block2, InvocationKind.AT_MOST_ONCE)
        callsInPlace(transform, InvocationKind.AT_MOST_ONCE)
    }
    val r1 = block1()
    if (r1.inlineValue is Failure) return Res(r1.inlineValue)
    val r2 = block2()
    if (r2.inlineValue is Failure) return Res(r2.inlineValue)
    return Res.unsafeOk(transform(r1.inlineValue as V1, r2.inlineValue as V2))
}

/** Overload of [zip] for three results. See the two-parameter overload for full documentation. */
public inline fun <V1, V2, V3, E, R> zip(
    block1: () -> Res<V1, E>,
    block2: () -> Res<V2, E>,
    block3: () -> Res<V3, E>,
    transform: (V1, V2, V3) -> R
): Res<R, E> {
    contract {
        callsInPlace(block1, InvocationKind.EXACTLY_ONCE)
        callsInPlace(block2, InvocationKind.AT_MOST_ONCE)
        callsInPlace(block3, InvocationKind.AT_MOST_ONCE)
        callsInPlace(transform, InvocationKind.AT_MOST_ONCE)
    }
    val r1 = block1()
    if (r1.inlineValue is Failure) return Res(r1.inlineValue)
    val r2 = block2()
    if (r2.inlineValue is Failure) return Res(r2.inlineValue)
    val r3 = block3()
    if (r3.inlineValue is Failure) return Res(r3.inlineValue)
    return Res.unsafeOk(transform(r1.inlineValue as V1, r2.inlineValue as V2, r3.inlineValue as V3))
}

/** Overload of [zip] for four results. See the two-parameter overload for full documentation. */
public inline fun <V1, V2, V3, V4, E, R> zip(
    block1: () -> Res<V1, E>,
    block2: () -> Res<V2, E>,
    block3: () -> Res<V3, E>,
    block4: () -> Res<V4, E>,
    transform: (V1, V2, V3, V4) -> R
): Res<R, E> {
    contract {
        callsInPlace(block1, InvocationKind.EXACTLY_ONCE)
        callsInPlace(block2, InvocationKind.AT_MOST_ONCE)
        callsInPlace(block3, InvocationKind.AT_MOST_ONCE)
        callsInPlace(block4, InvocationKind.AT_MOST_ONCE)
        callsInPlace(transform, InvocationKind.AT_MOST_ONCE)
    }
    val r1 = block1()
    if (r1.inlineValue is Failure) return Res(r1.inlineValue)
    val r2 = block2()
    if (r2.inlineValue is Failure) return Res(r2.inlineValue)
    val r3 = block3()
    if (r3.inlineValue is Failure) return Res(r3.inlineValue)
    val r4 = block4()
    if (r4.inlineValue is Failure) return Res(r4.inlineValue)
    return Res.unsafeOk(transform(r1.inlineValue as V1, r2.inlineValue as V2, r3.inlineValue as V3, r4.inlineValue as V4))
}
