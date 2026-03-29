@file:Suppress("UNCHECKED_CAST")

package tech.codingzen.resultkit

/**
 * Reusable mapper for flushing accumulated validation errors into a [rail] scope.
 *
 * Follows the same dual-invoke pattern as [FailMappingRail], [ErrorMappingRail],
 * and [MappingRail]:
 *
 * **Top-level usage** — invoke returns [Res]:
 * ```
 * val validate = Rail.validation<String, AppError> { AppError.Validation(it) }
 * val result: Res<Unit, AppError> = validate {
 *     ensure(name.isNotBlank()) { "Name required" }
 * }
 * ```
 *
 * **Inside [rail] blocks** — the member extension on [Rail] wins, short-circuiting
 * the outer rail if any errors accumulated:
 * ```
 * rail<User, AppError> {
 *     val validate = validation<String> { AppError.Validation(it) }
 *     validate {
 *         ensure(name.isNotBlank()) { "Name required" }
 *         ensure(age >= 0) { "Negative age" }
 *     }
 *     // only reaches here if all ensures passed
 *     User(name, age)
 * }
 * ```
 *
 * The same [ValidationMapping] instance can be reused across multiple validation
 * blocks in the same rail scope.
 */
public class ValidationMapping<F, E>(
    @PublishedApi internal val mapErrors: (List<F>) -> E
)

/**
 * Top-level invoke: runs [block] on a [Validator], returns [Res] with mapped errors.
 *
 * Inside a [rail] block, the member extension [Rail.invoke] takes precedence and
 * short-circuits instead.
 */
public inline operator fun <F, E> ValidationMapping<F, E>.invoke(
    block: Validator<F>.() -> Unit
): Res<Unit, E> {
    val v = Validator<F>()
    v.block()
    return if (v.hasErrors) {
        val errors = v.errors()
        try { Res.failure(mapErrors(errors)) } catch (me: Exception) {
            if (me is kotlin.coroutines.cancellation.CancellationException) throw me
            throw ErrorMapperException(
                IllegalStateException("Validation failed with ${errors.size} error(s)"),
                me
            )
        }
    } else Res.ok(Unit)
}
