@file:Suppress("UNCHECKED_CAST")

package tech.codingzen.resultkit

/**
 * Reusable scope for catching exceptions and mapping them to a typed error.
 *
 * **Top-level usage** — create via [Rail.Companion.catching] or constructor, invoke to get [Res]:
 * ```
 * val appRail = Rail.catching { e -> AppError.Unexpected(e) }
 * val r: Res<User, AppError> = appRail { fetchUser(id) }
 * ```
 *
 * **Inside [rail] blocks** — create via [Rail.catching], invoke to get the unwrapped value
 * (short-circuits the outer scope on exception):
 * ```
 * val result = rail<Int, String> {
 *     val http = catching { e -> "HTTP: ${e.message}" }
 *     val user = http { fetchUser(id) }   // returns User directly
 * }
 * ```
 *
 * **Note:** The same [ExceptionMappingRail] instance behaves differently depending on context.
 * Top-level invoke returns `Res<V, E>`; inside `rail {}` the member extension on [Rail] wins
 * and returns `V` directly (short-circuiting on error). The compiler enforces correct usage —
 * a return-type mismatch is a compile error.
 *
 * For typed-error-only mapping, use [ErrorMappingRail].
 * For combined exception + typed-error handling, use [MappingRail].
 */
public class ExceptionMappingRail<E>(
    @PublishedApi internal val mapError: (Exception) -> E
)

/**
 * Top-level invoke: creates its own [Rail], catches exceptions, returns [Res].
 *
 * Inside a [rail] block, the member extension [Rail.invoke] takes precedence and
 * returns the unwrapped value directly instead.
 */
public inline operator fun <V, E> ExceptionMappingRail<E>.invoke(
    block: Rail<E>.() -> V
): Res<V, E> {
    val scope = Rail<E>()
    return try {
        Res.ok(scope.block())
    // FailException catch needed — this invoke owns its own Rail scope and must
    // convert FailException to Res.Fail. (Compare with the member extension in Rail
    // which lets FailException pass through to the outer rail {}.)
    } catch (e: FailException) {
        if (e.scope !== scope) throw e
        Res(Failure(e.error, e.frames))
    // FQN: stdlib CancellationException, not kotlinx — avoids runtime dependency on kotlinx-coroutines
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        try { Res.failure(mapError(e)) } catch (me: Exception) {
            if (me is kotlin.coroutines.cancellation.CancellationException) throw me
            throw ErrorMapperException(e, me)
        }
    }
}
