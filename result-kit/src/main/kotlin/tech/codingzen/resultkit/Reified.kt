@file:Suppress("UNCHECKED_CAST")

package tech.codingzen.resultkit

// Reified, subtype-selective variants of mapError / recover. Useful with sealed error hierarchies:
// refine or recover a single error case while passing the rest through untouched.

/**
 * Transforms the Fail error **only if** it is an instance of the reified subtype [F], leaving Ok and
 * non-[F] errors unchanged. A no-op on Ok.
 *
 * Frames are preserved across the transform (like [mapError]).
 *
 * The subtype [F] is inferred from the lambda's parameter type:
 * ```
 * res.mapErrorIf { e: NotFound -> AppError.Missing(e.id) }   // other AppErrors pass through
 * ```
 *
 * @param transform applied to the error only when it is an [F].
 */
public inline fun <V, E, reified F : E> Res<V, E>.mapErrorIf(transform: (F) -> E): Res<V, E> {
    val underlying = inlineValue
    if (underlying !is Failure) return this
    val error = underlying.error
    return if (error is F) Res(Failure(transform(error), underlying.frames)) else this
}

/**
 * Converts a Fail to Ok **only if** the error is an instance of the reified subtype [F]. Ok and
 * non-[F] errors pass through unchanged (so the result may still be Fail). A no-op on Ok.
 *
 * Frames on the recovered branch are discarded (the result is Ok, which carries no frames); a
 * non-[F] Fail keeps its frames. For unconditional recovery use [recover].
 *
 * The subtype [F] is inferred from the lambda's parameter type:
 * ```
 * res.recoverIf { _: Transient -> cachedFallback() }   // permanent errors stay Fail
 * ```
 *
 * @param transform converts an [F] error into a success value.
 */
// @UnsafeVariance is safe — the lambda produces a V, it does not consume a covariant V.
public inline fun <V, E, reified F : E> Res<V, E>.recoverIf(
    transform: (F) -> @UnsafeVariance V,
): Res<V, E> {
    val underlying = inlineValue
    if (underlying !is Failure) return this
    val error = underlying.error
    return if (error is F) Res.unsafeOk(transform(error)) else this
}
