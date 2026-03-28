@file:Suppress("UNCHECKED_CAST")
@file:OptIn(ExperimentalContracts::class)

package tech.codingzen.resultkit

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Entry point for railway-oriented error handling.
 *
 * Executes [block] within a [Rail] and returns an Ok result on success or a Fail result on
 * short-circuit via [Rail.fail], [Rail.orFail], or [Rail.ensure].
 *
 * **Warning:** Do not use raw `try { } catch(e: Throwable)` inside the block. The DSL uses
 * an internal exception (`FailException`, a direct [Throwable] subclass) for control flow,
 * and `catch(Throwable)` will silently swallow it, breaking the railway.
 *
 * Also avoid `catch(e: Exception)` inside [Rail.failMapping]`{ }` blocks — it will intercept
 * exceptions before the mapping can catch and translate them.
 */
public inline fun <V, E> rail(block: Rail<E>.() -> V): Res<V, E> {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    val scope = Rail<E>()
    return try {
        Res.ok(scope.block())
    // No CancellationException guard needed — we only catch FailException (a Throwable, not Exception)
    } catch (e: FailException) {
        if (e.scope !== scope) throw e
        Res.failure(e.error as E)
    }
}
