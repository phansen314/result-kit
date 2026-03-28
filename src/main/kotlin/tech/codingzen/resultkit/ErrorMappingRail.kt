@file:Suppress("UNCHECKED_CAST")

package tech.codingzen.resultkit

/**
 * Reusable mapper for converting between typed error domains inside [rail] blocks.
 *
 * Create via [Rail.errorMapping], then invoke with a [Res] to unwrap the Ok value
 * or short-circuit with the mapped error:
 *
 * ```
 * val result = rail<Dashboard, AppError> {
 *     val http = errorMapping<HttpError> { AppError.Network(it) }
 *     val db = errorMapping<DbError> { AppError.Database(it) }
 *
 *     val user = http(fetchUser(id))       // Res<User, HttpError> -> User
 *     val settings = db(loadSettings(id))  // Res<Settings, DbError> -> Settings
 *     Dashboard(user, settings)
 * }
 * ```
 *
 * **Top-level usage** — invoke with a block to get [Res] with mapped errors:
 * ```
 * val http = Rail.errorMapping<HttpError, AppError> { AppError.Network(it) }
 * val r: Res<User, AppError> = http { fetchUser(id).orFail() }
 * ```
 *
 * This parallels [FailMappingRail] which catches exceptions. [ErrorMappingRail]
 * maps typed [Res] errors; [FailMappingRail] catches and maps JVM exceptions.
 */
public class ErrorMappingRail<in D, E>(
    @PublishedApi internal val mapError: (D) -> E
)

/**
 * Top-level invoke: creates its own [Rail]<[D]>, runs [block], and maps any
 * short-circuited [D] error to [E] via [ErrorMappingRail.mapError].
 *
 * Does **not** catch exceptions — use [FailMappingRail] for that.
 *
 * Inside a [rail] block, the member extension [Rail.invoke] takes precedence and
 * unwraps a [Res] directly instead.
 */
public inline operator fun <V, D, E> ErrorMappingRail<D, E>.invoke(
    block: Rail<D>.() -> V
): Res<V, E> {
    val scope = Rail<D>()
    return try {
        Res.ok(scope.block())
    } catch (e: FailException) {
        if (e.scope !== scope) throw e
        Res.failure(mapError(e.error as D))
    }
}
