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
class Rail<E> @PublishedApi internal constructor() {
    fun fail(e: E): Nothing {
        throw FailException(e as Any?, this)
    }

    fun <V> Res<V, E>.orFail(): V =
        if (inlineValue is Failure) {
            @Suppress("UNCHECKED_CAST")
            fail(inlineValue.error as E)
        } else {
            @Suppress("UNCHECKED_CAST")
            inlineValue as V
        }

    inline fun <V, F> Res<V, F>.orFail(mapError: (F) -> E): V =
        if (inlineValue is Failure) {
            @Suppress("UNCHECKED_CAST")
            fail(mapError(inlineValue.error as F))
        } else {
            @Suppress("UNCHECKED_CAST")
            inlineValue as V
        }

    inline fun ensure(condition: Boolean, error: () -> E) {
        if (!condition) fail(error())
    }

    inline fun <V> ensureNotNull(value: V?, error: () -> E): V {
        return value ?: fail(error())
    }

    fun failMapping(mapError: (Exception) -> E): FailMappingRail<E> =
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
    inline operator fun <V> FailMappingRail<E>.invoke(block: Rail<E>.() -> V): V =
        try {
            this@Rail.block()
        // CancellationException is a subtype of Exception on JVM — must be caught first to preserve structured concurrency
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            try { fail(mapError(e)) } catch (me: Exception) {
                if (me is kotlin.coroutines.cancellation.CancellationException) throw me
                throw ErrorMapperException(e, me)
            }
        }

    companion object {
        inline fun <V> attempt(block: Rail<Exception>.() -> V): Res<V, Exception> =
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
class ErrorMapperException(
    val originalException: Exception,
    cause: Exception
) : RuntimeException(
    "FailMappingRail mapError threw while mapping ${originalException::class.simpleName}: ${cause.message}",
    cause
) {
    init {
        addSuppressed(originalException)
    }
}
