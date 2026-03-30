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
@RailDsl
public class Rail<E> @PublishedApi internal constructor() {
    /** Short-circuits this rail, producing a [Res.failure] with error [e] from the enclosing [rail] block. */
    public fun fail(e: E): Nothing {
        throw FailException(e as Any?, this)
    }

    /** Unwraps the Ok value, or short-circuits this rail with the Fail error. */
    @Suppress("NOTHING_TO_INLINE")
    public inline fun <V> Res<V, E>.orFail(): V =
        if (inlineValue is Failure) fail(inlineValue.error as E)
        else inlineValue as V

    /** Unwraps the Ok value, or maps the error via [mapError] and short-circuits this rail. */
    public inline fun <V, F> Res<V, F>.orFail(mapError: (F) -> E): V =
        if (inlineValue is Failure) fail(mapError(inlineValue.error as F))
        else inlineValue as V

    /**
     * Unwraps the Ok value, or maps the error via [mapping] and short-circuits this rail.
     *
     * Equivalent to `orFail { mapping.mapError(it) }` but allows reusing an [ErrorMappingRail]
     * across multiple call sites.
     */
    public inline fun <V, F> Res<V, F>.orFail(mapping: ErrorMappingRail<F, @UnsafeVariance E>): V =
        orFail { mapping.mapError(it) }

    /** Short-circuits this rail with [error] if [condition] is `false`. Analogous to [require]. */
    public inline fun ensure(condition: Boolean, error: () -> E) {
        if (!condition) fail(error())
    }

    /** Returns [value] if non-null, or short-circuits this rail with [error]. Analogous to [requireNotNull]. */
    public inline fun <V> ensureNotNull(value: V?, error: () -> E): V {
        return value ?: fail(error())
    }

    /** Creates a [FailMappingRail] for catching exceptions within sub-blocks of this rail. */
    public fun failMapping(mapError: (Exception) -> E): FailMappingRail<E> =
        FailMappingRail(mapError)

    /** Creates an [ErrorMappingRail] for mapping typed errors from a different domain into this rail's error type. */
    public fun <D> errorMapping(mapError: (D) -> E): ErrorMappingRail<D, E> =
        ErrorMappingRail(mapError)

    /**
     * Creates a [MappingRail] that both catches exceptions and maps typed errors.
     *
     * Use when calling functions that can throw AND return [Res] with a typed error.
     */
    public fun <D> mapping(
        onError: (D) -> E,
        onException: (Exception) -> E,
    ): MappingRail<D, E> = MappingRail(onError, onException)

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

    /**
     * Member extension: unwraps [res] if Ok, or maps the error via [ErrorMappingRail.mapError]
     * and short-circuits the outer [rail] with the mapped error.
     *
     * Unlike [FailMappingRail.invoke], this does **not** catch exceptions — it only
     * translates typed errors between domains.
     */
    @Suppress("NOTHING_TO_INLINE")
    public inline operator fun <V, D> ErrorMappingRail<D, @UnsafeVariance E>.invoke(res: Res<V, D>): V =
        res.orFail { mapError(it) }

    /**
     * Runs [block], catches exceptions via [MappingRail.onException], then unwraps the
     * returned [Res] via [MappingRail.onError]. Short-circuits the outer [rail] on either.
     *
     * [Rail.fail] calls inside [block] bypass both mappers — they short-circuit the
     * outer rail directly. Only JVM exceptions and typed [Res] errors are mapped.
     * [CancellationException] is always rethrown to preserve structured concurrency.
     */
    public inline operator fun <V, D> MappingRail<D, @UnsafeVariance E>.invoke(
        block: Rail<E>.() -> Res<V, D>
    ): V =
        try {
            this@Rail.block().orFail { onError(it) }
        // FQN: stdlib CancellationException, not kotlinx — avoids runtime dependency on kotlinx-coroutines
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            try { fail(onException(e)) } catch (me: Exception) {
                if (me is kotlin.coroutines.cancellation.CancellationException) throw me
                throw ErrorMapperException(e, me)
            }
        }

    /** Creates a [ValidationMapping] for accumulating validation errors and flushing them into this rail. */
    public fun <F> validation(mapErrors: (List<F>) -> E): ValidationMapping<F, E> =
        ValidationMapping(mapErrors)

    /**
     * Member extension: runs [block] on a [Validator], accumulating errors.
     * If any errors accumulated, maps them via [ValidationMapping.mapErrors]
     * and short-circuits this rail.
     *
     * Follows the same invoke pattern as [FailMappingRail] — the member extension
     * wins over the top-level invoke inside a [rail] block.
     */
    public inline operator fun <F> ValidationMapping<F, @UnsafeVariance E>.invoke(
        block: Validator<F>.() -> Unit
    ) {
        val v = Validator<F>()
        v.block()
        if (v.hasErrors) {
            val errors = v.errors()
            try { fail(mapErrors(errors)) } catch (me: Exception) {
                if (me is kotlin.coroutines.cancellation.CancellationException) throw me
                throw ErrorMapperException(
                    IllegalStateException("Validation failed with ${errors.size} error(s)"),
                    me
                )
            }
        }
    }

    /**
     * Member extension: flushes a [Validator]'s accumulated errors into this rail.
     * If the validator has errors, maps them via [mapErrors] and short-circuits.
     *
     * For the imperative [Validator] pattern where ensure calls are interleaved
     * with other code:
     * ```
     * rail<User, AppError> {
     *     val v = validator<String>()
     *     v.ensure(name.isNotBlank()) { "Name required" }
     *     val domain = email.substringAfter('@')
     *     v.ensure(domain.contains('.')) { "Invalid domain" }
     *     v.orFail { errors -> AppError.Validation(errors) }
     *     User(name, age, email)
     * }
     * ```
     */
    public inline fun <F> Validator<F>.orFail(mapErrors: (List<F>) -> E) {
        if (hasErrors) {
            val errors = errors()
            try { this@Rail.fail(mapErrors(errors)) } catch (me: Exception) {
                if (me is kotlin.coroutines.cancellation.CancellationException) throw me
                throw ErrorMapperException(
                    IllegalStateException("Validation failed with ${errors.size} error(s)"),
                    me
                )
            }
        }
    }

    // -- Context DSL --

    /**
     * Unwraps the Ok value, or short-circuits this rail with a context message appended to the
     * Fail's frame chain.
     *
     * The [context] lambda is only invoked when this result is Fail — zero allocation on the
     * Ok path. Named `orFailContext` (not `orFail`) to avoid overload ambiguity with
     * `orFail(mapError: (F) -> E)` when `E = String`.
     *
     * ```
     * val user = fetchUser(id).orFailContext { "fetching user id=$id" }
     * ```
     */
    public inline fun <V> Res<V, E>.orFailContext(context: () -> String): V {
        if (inlineValue !is Failure) return inlineValue as V
        val frame = tech.codingzen.resultkit.context.Frame(message = context())
        throw FailException(inlineValue.error, this@Rail, inlineValue.frames + frame)
    }

    /**
     * Unwraps the Ok value, or short-circuits this rail with a context message and source location
     * appended to the Fail's frame chain.
     *
     * Both lambdas are only invoked when this result is Fail.
     */
    public inline fun <V> Res<V, E>.orFailContext(
        context: () -> String,
        location: () -> tech.codingzen.resultkit.context.SourceLocation,
    ): V {
        if (inlineValue !is Failure) return inlineValue as V
        val frame = tech.codingzen.resultkit.context.Frame(
            message = context(),
            location = location(),
        )
        throw FailException(inlineValue.error, this@Rail, inlineValue.frames + frame)
    }

    /**
     * Executes [block] within this rail's scope. On short-circuit, prepends [message] as a context
     * frame before re-throwing, so the failure carries the additional context.
     *
     * ```
     * rail<Dashboard, AppError> {
     *     val user = withContext("loading dashboard for user $userId") {
     *         fetchUser(userId).orFail()
     *     }
     * }
     * ```
     */
    public inline fun <V> withContext(
        message: String,
        block: Rail<E>.() -> V,
    ): V {
        try {
            return block()
        } catch (e: FailException) {
            if (e.scope !== this) throw e
            val frame = tech.codingzen.resultkit.context.Frame(message = message)
            throw FailException(e.error, e.scope, e.frames + frame)
        }
    }

    /**
     * Executes [block] within this rail's scope. On short-circuit, prepends a context frame with
     * [message] and a source [location] before re-throwing.
     *
     * The [location] lambda is only invoked on the Fail path.
     */
    public inline fun <V> withContext(
        message: String,
        location: () -> tech.codingzen.resultkit.context.SourceLocation,
        block: Rail<E>.() -> V,
    ): V {
        try {
            return block()
        } catch (e: FailException) {
            if (e.scope !== this) throw e
            val frame = tech.codingzen.resultkit.context.Frame(
                message = message,
                location = location(),
            )
            throw FailException(e.error, e.scope, e.frames + frame)
        }
    }

    public companion object {
        /** Creates a top-level [FailMappingRail] for catching exceptions and mapping them to typed errors. */
        public fun <E> failMapping(mapError: (Exception) -> E): FailMappingRail<E> =
            FailMappingRail(mapError)

        /** Creates a top-level [ErrorMappingRail] for mapping typed errors between domains. */
        public fun <D, E> errorMapping(mapError: (D) -> E): ErrorMappingRail<D, E> =
            ErrorMappingRail(mapError)

        /**
         * Convenience entry point that catches any [Exception] and returns it as the error type.
         *
         * Equivalent to `Rail.failMapping { it }` followed by an invoke.
         */
        public inline fun <V> attempt(block: Rail<Exception>.() -> V): Res<V, Exception> =
            FailMappingRail<Exception> { it }(block)

        /**
         * Creates a [MappingRail] for use as a top-level entry point.
         *
         * ```
         * val httpRail = Rail.mapping<HttpError, AppError>(
         *     onError = { AppError.Network(it) },
         *     onException = { AppError.Unexpected(it) },
         * )
         * fun getUser(id: Int): Res<User, AppError> = httpRail { fetchUser(id) }
         * ```
         */
        public fun <D, E> mapping(
            onError: (D) -> E,
            onException: (Exception) -> E,
        ): MappingRail<D, E> = MappingRail(onError, onException)

        /** Creates a top-level [ValidationMapping] for accumulating validation errors. */
        public fun <F, E> validation(mapErrors: (List<F>) -> E): ValidationMapping<F, E> =
            ValidationMapping(mapErrors)
    }
}

// INVARIANT: Must extend Throwable directly (not Exception) — ErrorMapper catch blocks
// in Rail.invoke and FailMappingRail.invoke use catch(Exception) to distinguish
// mapper failures from rail control flow. Changing this hierarchy breaks that silently.
@PublishedApi
internal class FailException(
    val error: Any?,
    val scope: Rail<*>,
    val frames: List<tech.codingzen.resultkit.context.Frame> = emptyList(),
) : Throwable(
    "result-kit: FailException escaped a rail{} block — avoid catching Throwable inside rail{} blocks"
) {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * Thrown when a `mapError` lambda itself throws an exception.
 *
 * The [cause] is the exception thrown by the mapper; [originalException] is the error
 * that the mapper was trying to map.
 */
public class ErrorMapperException(
    public val originalException: Throwable,
    cause: Exception
) : RuntimeException(
    "mapError threw while mapping ${originalException::class.simpleName}: ${cause.message}",
    cause
) {
    init {
        addSuppressed(originalException)
    }
}
