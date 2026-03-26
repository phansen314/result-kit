package tech.codingzen.resultkit

/**
 * DSL scope for [rail] blocks, providing short-circuit operations on [Res] values.
 *
 * **Warning:** Do not use raw `try { } catch(e: Exception)` or `catch(e: Throwable)` inside
 * a `rail {}` block. The DSL uses an internal exception (`FailException`) for control flow,
 * and a broad catch will silently swallow it, breaking the railway with no visible error.
 * Use [failMapping]`{ }` for safe exception handling instead.
 */
class Rail<E> @PublishedApi internal constructor() {
    fun fail(e: E): Nothing {
        throw FailException(e as Any?, this)
    }

    fun <V> Res<V, E>.orFail(): V = when (this) {
        is Res.Ok -> value
        is Res.Fail -> fail(error)
    }

    inline fun <V, F> Res<V, F>.orFail(mapError: (F) -> E): V = when (this) {
        is Res.Ok -> value
        is Res.Fail -> fail(mapError(error))
    }

    inline fun ensure(condition: Boolean, error: () -> E) {
        if (!condition) fail(error())
    }

    inline fun <V> ensureNotNull(value: V?, error: () -> E): V {
        return value ?: fail(error())
    }

    fun failMapping(mapError: (Exception) -> E): FailMappingRail<E> =
        FailMappingRail(mapError)

    inline operator fun <V> FailMappingRail<E>.invoke(block: Rail<E>.() -> V): V =
        try {
            this@Rail.block()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(mapError(e))
        }

}

@PublishedApi
internal class FailException(val error: Any?, val scope: Any) : Throwable(null as String?) {
    override fun fillInStackTrace(): Throwable = this
}
