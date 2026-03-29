package tech.codingzen.resultkit.context

/**
 * Marks an interface for KSP traced-wrapper generation.
 *
 * The KSP processor (`result-kit-ksp`) generates a decorator class named
 * `{InterfaceName}{suffix}` that wraps every [tech.codingzen.resultkit.Res]-returning
 * method with `.context(messageλ, locationλ)`, attaching the method name, parameters,
 * and source location as a [Frame].
 *
 * Non-Res methods are delegated as-is with no wrapping.
 *
 * ```kotlin
 * @TraceContext
 * interface UserRepository {
 *     fun findById(id: Int): Res<User, DbError>
 *     suspend fun save(user: User): Res<Unit, DbError>
 *     fun count(): Int   // not wrapped — not Res-returning
 * }
 * // KSP generates UserRepositoryTraced
 * ```
 *
 * @property suffix The class name suffix for the generated wrapper. Defaults to `"Traced"`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
public annotation class TraceContext(
    val suffix: String = "Traced",
)

/**
 * Overrides the auto-generated context message for a specific method.
 *
 * The generated default message is `"ClassName.method(param1=$param1, param2=$param2)"`.
 * Use this annotation to provide a custom template instead.
 *
 * Supports `{paramName}` interpolation — replaced with the actual parameter value at runtime:
 * ```kotlin
 * @TraceMessage("loading user {id}")
 * fun findById(id: Int): Res<User, DbError>
 * // generates: .context { "loading user $id" }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
public annotation class TraceMessage(val value: String)

/**
 * Excludes a parameter from the auto-generated context message.
 *
 * Use on sensitive parameters (passwords, tokens, PII) that should not appear in logs:
 * ```kotlin
 * fun authenticate(username: String, @TraceExclude password: String): Res<Token, AuthError>
 * // generates: .context { "UserService.authenticate(username=$username)" }
 * // (password is omitted)
 * ```
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
public annotation class TraceExclude
