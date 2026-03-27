package tech.codingzen.resultkit

/**
 * Entry point for railway-oriented error handling.
 *
 * Executes [block] within a [Rail] and returns an Ok result on success or a Fail result on
 * short-circuit via [Rail.fail], [Rail.orFail], or [Rail.ensure].
 *
 * **Warning:** Do not use raw `try { } catch(e: Exception)` or `catch(e: Throwable)` inside
 * the block. The DSL uses an internal exception for control flow, and a broad catch will
 * silently swallow it, breaking the railway. Use [Rail.failMapping]`{ }` instead.
 */
inline fun <V, E> rail(block: Rail<E>.() -> V): Res<V, E> {
    val scope = Rail<E>()
    return try {
        ok(scope.block())
    } catch (e: FailException) {
        if (e.scope !== scope) throw e
        @Suppress("UNCHECKED_CAST")
        failure(e.error as E)
    }
}
