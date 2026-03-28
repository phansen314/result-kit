@file:Suppress("UNCHECKED_CAST")

package tech.codingzen.resultkit

/**
 * DSL scope for [rail] blocks, providing short-circuit operations on [Res] values.
 *
 * **Warning:** Do not use raw `try { } catch(e: Throwable)` inside a `rail {}` block.
 * The DSL uses an internal exception (`FailException`, a direct [Throwable] subclass) for
 * control flow, and `catch(Throwable)` will silently swallow it, breaking the railway.
 *
 * Also avoid `catch(e: Exception)` inside [failMapping]`{ }` blocks — it will intercept
 * exceptions before the mapping can catch and translate them.
 *
 * Use [failMapping]`{ }` for safe exception handling instead.
 */
public class Rail<E> @PublishedApi internal constructor() {
    public fun fail(e: E): Nothing {
        throw FailException(e as Any?, this)
    }

    @Suppress("NOTHING_TO_INLINE")
    public inline fun <V> Res<V, E>.orFail(): V =
        if (inlineValue is Failure) fail(inlineValue.error as E)
        else inlineValue as V

    public inline fun <V, F> Res<V, F>.orFail(mapError: (F) -> E): V =
        if (inlineValue is Failure) fail(mapError(inlineValue.error as F))
        else inlineValue as V

    public inline fun ensure(condition: Boolean, error: () -> E) {
        if (!condition) fail(error())
    }

    public inline fun <V> ensureNotNull(value: V?, error: () -> E): V {
        return value ?: fail(error())
    }

    public fun failMapping(mapError: (Exception) -> E): FailMappingRail<E> =
        FailMappingRail(mapError)

    /**
     * Member extension: catches exceptions thrown inside [block], maps them via [FailMappingRail.mapError],
     * and short-circuits the outer [rail] with the mapped error.
     *
     * **Important:** [fail] calls inside [block] are **not** caught or mapped — they bypass the
     * mapping and short-circuit the outer rail directly with the raw error. Only JVM exceptions
     * (subtypes of [Exception]) are caught and mapped. This is intentional: [fail] is explicit
     * control flow, while exceptions are unexpected failures that need translation.
     */
    public inline operator fun <V> FailMappingRail<E>.invoke(block: Rail<E>.() -> V): V =
        try {
            this@Rail.block()
        // No FailException catch needed — FailException extends Throwable (not Exception),
        // so it passes through catch(Exception) below. The outer rail {} catches it.
        // (Compare with top-level FailMappingRail.invoke which owns its own Rail scope
        // and must catch FailException to convert it to Res.Fail.)
        // FQN: stdlib CancellationException, not kotlinx — avoids runtime dependency on kotlinx-coroutines
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            try { fail(mapError(e)) } catch (me: Exception) {
                if (me is kotlin.coroutines.cancellation.CancellationException) throw me
                throw ErrorMapperException(e, me)
            }
        }

    public companion object {
        public fun <E> failMapping(mapError: (Exception) -> E): FailMappingRail<E> =
            FailMappingRail(mapError)

        public inline fun <V> attempt(block: Rail<Exception>.() -> V): Res<V, Exception> =
            FailMappingRail<Exception> { it }(block)
    }
}

// INVARIANT: Must extend Throwable directly (not Exception) — ErrorMapper catch blocks
// in Rail.invoke and FailMappingRail.invoke use catch(Exception) to distinguish
// mapper failures from rail control flow. Changing this hierarchy breaks that silently.
@PublishedApi
internal class FailException(val error: Any?, val scope: Any) : Throwable(
    "result-kit: FailException escaped a rail{} block — avoid catching Throwable inside rail{} blocks"
) {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * Thrown when the `mapError` lambda passed to [FailMappingRail] itself throws an exception.
 *
 * The [cause] is the exception thrown by the mapper; [originalException] is the exception
 * that the mapper was trying to map.
 */
public class ErrorMapperException(
    public val originalException: Exception,
    cause: Exception
) : RuntimeException(
    "FailMappingRail mapError threw while mapping ${originalException::class.simpleName}: ${cause.message}",
    cause
) {
    init {
        addSuppressed(originalException)
    }
}
