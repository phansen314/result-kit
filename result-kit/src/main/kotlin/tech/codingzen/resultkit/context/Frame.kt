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

/**
 * A [Throwable] view of a single context [Frame], used to attach context to thrown errors via
 * [Throwable.addSuppressed].
 *
 * When `Res<V, E : Throwable>.getOrThrow()` throws the underlying error, each attached frame is
 * added as a suppressed `FrameTrace` so the breadcrumb chain survives the JVM throw boundary and
 * appears in standard stack-trace dumps. Stack trace is disabled — only the frame's message and
 * location carry information.
 */
public class FrameTrace(public val frame: Frame) : Throwable(
    buildString {
        append("context: ")
        append(frame.message)
        frame.location?.let { append(" at ").append(it) }
    },
    /* cause = */ null,
    /* enableSuppression = */ false,
    /* writableStackTrace = */ false,
)
