@file:Suppress("UNCHECKED_CAST")

package tech.codingzen.resultkit.context

import tech.codingzen.resultkit.Failure
import tech.codingzen.resultkit.Res

/**
 * Returns the context frame chain attached to this result.
 *
 * Frames are ordered innermost-first: index 0 is the first-attached (innermost/closest-to-error)
 * context; the last index is the outermost. Returns an empty list for Ok results.
 */
public fun <V, E> Res<V, E>.contextChain(): List<Frame> {
    val underlying = inlineValue
    return if (underlying is Failure) underlying.frames else emptyList()
}

/**
 * Renders the error and its context chain as a human-readable multi-line string.
 *
 * Frames are listed in storage order (innermost-first): index 0 is the closest to the error.
 * Compare with [contextSummary] which reverses frames for an outermost-first breadcrumb trail.
 *
 * Format:
 * ```
 * AppError.DbError("connection refused")
 *
 *   0: MetricsRepository.findByTeam(teamId=7)
 *      at MetricsRepository.kt:3 in findByTeam
 *   1: building dashboard for user 42
 * ```
 *
 * Returns an empty string for Ok results.
 */
public fun <V, E> Res<V, E>.renderContext(): String {
    val underlying = inlineValue
    if (underlying !is Failure) return ""
    return buildString {
        append(underlying.error.toString())
        if (underlying.frames.isNotEmpty()) {
            appendLine()
            underlying.frames.forEachIndexed { index, frame ->
                appendLine()
                append("  ")
                append(index)
                append(": ")
                append(frame.message)
                if (frame.location != null) {
                    appendLine()
                    append("     at ")
                    append(frame.location)
                }
                if (frame.attachment != null) {
                    appendLine()
                    append("     attachment=")
                    append(frame.attachment)
                }
            }
        }
    }
}

/**
 * Returns a compact one-line summary: `"outerCtx → innerCtx → ErrorType(msg)"`.
 *
 * Frames are listed outermost-first (reversed from storage order) so the summary
 * reads as a breadcrumb trail leading to the error. Returns an empty string for
 * Ok results or Fail results with no context frames.
 */
public fun <V, E> Res<V, E>.contextSummary(): String {
    val underlying = inlineValue
    if (underlying !is Failure) return ""
    if (underlying.frames.isEmpty()) return underlying.error.toString()
    return buildString {
        underlying.frames.reversed().forEach { frame ->
            append(frame.message)
            append(" → ")
        }
        append(underlying.error.toString())
    }
}

/**
 * Returns the error and context chain as a structured map suitable for JSON logging.
 *
 * Keys: `"error"`, `"frames"` (list of maps with `"message"`, optional `"location"`, optional `"attachment"`).
 * Returns an empty map for Ok results.
 */
public fun <V, E> Res<V, E>.contextMap(): Map<String, Any?> {
    val underlying = inlineValue
    if (underlying !is Failure) return emptyMap()
    return mapOf(
        "error" to underlying.error,
        "frames" to underlying.frames.map { frame ->
            buildMap {
                put("message", frame.message)
                if (frame.location != null) put("location", frame.location.toString())
                if (frame.attachment != null) put("attachment", frame.attachment)
            }
        }
    )
}

/**
 * Finds the first attachment of type [T] in this frame list.
 *
 * ```
 * val userId: Int? = res.contextChain().findAttachment<Int>()
 * ```
 */
public inline fun <reified T> List<Frame>.findAttachment(): T? =
    firstOrNull { it.attachment is T }?.attachment as T?
