package tech.codingzen.resultkit.context

/**
 * A single context frame attached to a [tech.codingzen.resultkit.Res] failure.
 *
 * Frames are stored innermost-first: index 0 is the most specific context
 * (closest to the error site), higher indices are more general.
 *
 * @property message Human-readable description of what was being attempted.
 * @property attachment Optional structured data (user ID, request body, etc.).
 * @property location Optional source location where context was added.
 */
public data class Frame(
    val message: String,
    val attachment: Any? = null,
    val location: SourceLocation? = null,
)

/**
 * Identifies the source location where a context frame was created.
 *
 * All fields are typically compile-time constants (string literals, line numbers
 * from KSP or manual input) — never runtime stack-walking.
 *
 * @property file Source file name (e.g. `"UserService.kt"`).
 * @property line Line number within the file.
 * @property function Optional function or method name.
 */
public data class SourceLocation(
    val file: String,
    val line: Int,
    val function: String? = null,
) {
    override fun toString(): String = buildString {
        append(file)
        append(':')
        append(line)
        if (function != null) {
            append(" in ")
            append(function)
        }
    }
}
