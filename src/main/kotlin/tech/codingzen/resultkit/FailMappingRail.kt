package tech.codingzen.resultkit

/**
 * Reusable scope for catching exceptions and mapping them to a typed error.
 *
 * **Top-level usage** — create via constructor, invoke to get [Res]:
 * ```
 * val appRail = FailMappingRail<AppError> { e -> AppError.Unexpected(e) }
 * val r: Res<User, AppError> = appRail { fetchUser(id) }
 * ```
 *
 * **Inside [rail] blocks** — create via [Rail.failMapping], invoke to get the unwrapped value
 * (short-circuits the outer scope on exception):
 * ```
 * val result = rail<Int, String> {
 *     val http = failMapping { e -> "HTTP: ${e.message}" }
 *     val user = http { fetchUser(id) }   // returns User directly
 * }
 * ```
 */
class FailMappingRail<E>(
    @PublishedApi internal val mapError: (Exception) -> E
)

/**
 * Pre-built [FailMappingRail] with identity mapping — converts throwing code into
 * `Res<V, Exception>`. Usage: `catching { riskyOperation() }`.
 */
val catching = FailMappingRail<Exception> { e -> e }

/**
 * Top-level invoke: creates its own [Rail], catches exceptions, returns [Res].
 */
inline operator fun <V, E> FailMappingRail<E>.invoke(
    block: Rail<E>.() -> V
): Res<V, E> {
    val scope = Rail<E>()
    return try {
        Res.Ok(scope.block())
    } catch (e: FailException) {
        if (e.scope !== scope) throw e
        @Suppress("UNCHECKED_CAST")
        Res.Fail(e.error as E)
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        Res.Fail(mapError(e))
    }
}
