package tech.codingzen.resultkit

import tech.codingzen.resultkit.context.Frame

/**
 * An error paired with the context [frames] that were attached to it.
 *
 * For a single [Res], an error and its frames already travel together inside the failure and surface
 * via [contextChain][tech.codingzen.resultkit.context.contextChain]. The pairing only needs an
 * explicit carrier when **many** failures are collapsed into one result — accumulation paths produce
 * a `List<E>`, which has no per-error slot for frames. The frame-aware accumulators
 * ([zipOrAccumulateFramed], [Validator.toResFramed], [validationFramed],
 * [filterFailFramed][filterFailFramed], [partitionFramed]) return `List<FramedError<E>>` so each
 * error keeps its own trail.
 *
 * ```
 * val result: Res<Order, List<FramedError<ValidationError>>> = ...
 * result.fold(
 *     onOk = { it },
 *     onFail = { errs, _ -> errs.forEach { log.warn("${it.error}  trail=${it.frames}") } },
 * )
 * ```
 *
 * Unlike the internal failure sentinel — whose equality ignores frames because they are observability
 * metadata, not domain state — `FramedError` is the explicit *carry-the-frames* type, so [frames]
 * **do** participate in [equals]/[hashCode] (standard data-class semantics).
 */
public data class FramedError<out E>(
    val error: E,
    val frames: List<Frame> = emptyList(),
)
