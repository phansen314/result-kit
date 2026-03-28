@file:Suppress("UNCHECKED_CAST")

package tech.codingzen.resultkit

/**
 * Combined exception-catching and typed-error-mapping rail.
 *
 * Handles the common case where a called function can both throw exceptions AND
 * return [Res] with a typed error — e.g. HTTP clients, database drivers, gRPC stubs.
 *
 * **Two modes of operation:**
 *
 * **Top-level** — create via [Rail.Companion.mapping] or [MappingFactory.error], invoke to get [Res]:
 * ```
 * val httpRail = Rail.mapping<HttpError, AppError>(
 *     onError = { AppError.Network(it) },
 *     onException = { AppError.Unexpected(it) },
 * )
 * val result: Res<User, AppError> = httpRail { fetchUser(id) }
 * ```
 *
 * **Inside [rail] blocks** — create via [Rail.mapping], invoke to get unwrapped `V`
 * (short-circuits the outer scope on exception or error):
 * ```
 * val result = rail<Dashboard, AppError> {
 *     val http = mapping<HttpError>(
 *         onError = { AppError.Network(it) },
 *         onException = { AppError.Unexpected(it) },
 *     )
 *     val user = http { fetchUser(id) }   // returns User directly
 * }
 * ```
 *
 * **Note:** The same [MappingRail] instance behaves differently depending on context.
 * Top-level invoke returns `Res<V, E>`; inside `rail {}` the member extension on [Rail]
 * wins and returns `V` directly (short-circuiting on error). The compiler enforces correct
 * usage — a return-type mismatch is a compile error.
 *
 * For exception-only catching, use [FailMappingRail].
 * For typed-error-only mapping, use [ErrorMappingRail].
 */
public class MappingRail<in D, E>(
    @PublishedApi internal val onError: (D) -> E,
    @PublishedApi internal val onException: (Exception) -> E,
)

/**
 * Top-level invoke: creates its own [Rail], catches exceptions, maps typed errors, returns [Res].
 *
 * Inside a [rail] block, the member extension [Rail.invoke] takes precedence and
 * returns the unwrapped value directly instead.
 */
public inline operator fun <V, D, E> MappingRail<D, E>.invoke(
    block: Rail<E>.() -> Res<V, D>
): Res<V, E> {
    val scope = Rail<E>()
    return try {
        scope.block().mapError { onError(it) }
    } catch (e: FailException) {
        if (e.scope !== scope) throw e
        @Suppress("UNCHECKED_CAST")
        Res.failure(e.error as E)
    // FQN: stdlib CancellationException, not kotlinx — avoids runtime dependency on kotlinx-coroutines
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        try { Res.failure(onException(e)) } catch (me: Exception) {
            if (me is kotlin.coroutines.cancellation.CancellationException) throw me
            throw ErrorMapperException(e, me)
        }
    }
}

/**
 * Factory for creating [MappingRail] instances with a shared exception mapper.
 *
 * Eliminates repetition when multiple error domains share the same exception handling:
 *
 * ```
 * // Inside rail {}
 * val result = rail<Dashboard, AppError> {
 *     val apis = mappingWith { AppError.Unexpected(it) }
 *     val http = apis.error<HttpError> { AppError.Network(it) }
 *     val db = apis.error<DbError> { AppError.Database(it) }
 *
 *     val user = http { fetchUser(id) }
 *     val settings = db { loadSettings(user.id) }
 *     Dashboard(user, settings)
 * }
 *
 * // Outside rail {} — reusable across functions
 * val apis = Rail.mappingWith<AppError> { AppError.Unexpected(it) }
 * val httpRail = apis.error<HttpError> { AppError.Network(it) }
 * val dbRail = apis.error<DbError> { AppError.Database(it) }
 *
 * fun getUser(id: Int): Res<User, AppError> = httpRail { fetchUser(id) }
 * fun getSettings(id: Int): Res<Settings, AppError> = dbRail { loadSettings(id) }
 * ```
 */
public class MappingFactory<E>(
    @PublishedApi internal val onException: (Exception) -> E,
) {
    public fun <D> error(onError: (D) -> E): MappingRail<D, E> =
        MappingRail(onError, onException)
}
