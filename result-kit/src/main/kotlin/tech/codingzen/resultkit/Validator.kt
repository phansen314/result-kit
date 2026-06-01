@file:Suppress("UNCHECKED_CAST")

package tech.codingzen.resultkit

import tech.codingzen.resultkit.context.Frame

/**
 * Accumulates validation errors without short-circuiting.
 *
 * Two ways to use:
 *
 * **Scoped** — via [validation]:
 * ```
 * val result: Res<Unit, List<String>> = validation {
 *     ensure(name.isNotBlank()) { "Name required" }
 *     ensure(age >= 0) { "Negative age" }
 * }
 * ```
 *
 * **Imperative** — via [validator]:
 * ```
 * val v = validator<String>()
 * v.ensure(name.isNotBlank()) { "Name required" }
 * val domain = email.substringAfter('@')
 * v.ensure(domain.contains('.')) { "Invalid domain" }
 * v.toRes() // Res<Unit, List<String>>
 * ```
 *
 * Inside [rail] blocks, use [ValidationMapping] to flush accumulated errors
 * into the rail's error type. See [Rail.validation] and [Rail.Companion.validation].
 *
 * **Not thread-safe.** Errors are collected into a plain mutable list.
 * Do not call [fail], [ensure], [check], [checkOr], or [valueOrNull] concurrently from
 * multiple coroutines. To accumulate errors from independent checks without a shared mutable
 * accumulator, use [zipOrAccumulate] (it evaluates each block sequentially and collects their
 * errors).
 */
@RailDsl
public class Validator<E> @PublishedApi internal constructor() {
    @PublishedApi internal val errors: MutableList<E> = mutableListOf()

    /**
     * Sparse side-table mapping an error's index in [errors] to the context frames it carried.
     * `null` until a frame-bearing error actually lands, so the common (frameless) path — `ensure`,
     * `fail`, `check` on a Res with no frames — pays nothing. Read back via [errorsFramed].
     */
    private var frameMap: HashMap<Int, List<Frame>>? = null

    /** Appends a frameless error. The hot path for [ensure]/[fail]/[ensureNotNull]/[addAll]. */
    @PublishedApi internal fun addError(error: E) {
        errors.add(error)
    }

    /** Appends an error, recording [frames] in the sparse side-table when non-empty. */
    @PublishedApi internal fun addFramed(error: E, frames: List<Frame>) {
        if (frames.isNotEmpty()) {
            (frameMap ?: HashMap<Int, List<Frame>>().also { frameMap = it })[errors.size] = frames
        }
        errors.add(error)
    }

    /** Adds [error] to the accumulated error list. Does not short-circuit. */
    public fun fail(error: E) {
        addError(error)
    }

    /** If [condition] is `false`, adds the lazily-evaluated [error]. Does not short-circuit. */
    public inline fun ensure(condition: Boolean, error: () -> E) {
        if (!condition) addError(error())
    }

    /** If [value] is `null`, adds the lazily-evaluated [error]. Does not short-circuit. */
    public inline fun <V> ensureNotNull(value: V?, error: () -> E) {
        if (value == null) addError(error())
    }

    /** Adds all [errors] to the accumulated error list. Useful for bridging external validation (Spring, JSR-303). */
    public fun addAll(errors: Iterable<E>) {
        for (e in errors) addError(e)
    }

    /**
     * If this is Fail, adds its error to the accumulated list, retaining any context frames for
     * [errorsFramed]/[toResFramed]. Discards the Ok value.
     */
    @Suppress("NOTHING_TO_INLINE")
    public inline fun <V> Res<V, E>.check() {
        if (inlineValue is Failure) addFramed(inlineValue.error as E, inlineValue.frames)
    }

    /**
     * If this is Fail, maps the error via [mapError] and adds it, retaining the source frames across
     * the map for [errorsFramed]/[toResFramed]. Discards the Ok value.
     */
    public inline fun <V, F> Res<V, F>.check(mapError: (F) -> E) {
        if (inlineValue is Failure) addFramed(mapError(inlineValue.error as F), inlineValue.frames)
    }

    /**
     * If this is Fail, adds its error and returns `null`. If Ok, returns the value.
     *
     * **Caution:** Validation does not short-circuit, so subsequent code that depends
     * on the returned value will see `null` on the Fail path. The `OrNull` suffix is a
     * deliberate signal — prefer [checkOr] when you have a sane default, or guard the
     * result with a null check before dependent validations to avoid [NullPointerException].
     */
    @Suppress("NOTHING_TO_INLINE")
    public inline fun <V> Res<V, E>.valueOrNull(): V? {
        return if (inlineValue is Failure) {
            addFramed(inlineValue.error as E, inlineValue.frames)
            null
        } else {
            inlineValue as V
        }
    }

    /**
     * If this is Fail, maps the error via [mapError], adds it, and returns `null`. If Ok, returns the value.
     *
     * **Caution:** Validation does not short-circuit, so subsequent code that depends
     * on the returned value will see `null` on the Fail path. The `OrNull` suffix is a
     * deliberate signal — prefer [checkOr] when you have a sane default, or guard the
     * result with a null check before dependent validations to avoid [NullPointerException].
     */
    public inline fun <V, F> Res<V, F>.valueOrNull(mapError: (F) -> E): V? {
        return if (inlineValue is Failure) {
            addFramed(mapError(inlineValue.error as F), inlineValue.frames)
            null
        } else {
            inlineValue as V
        }
    }

    /**
     * If this is Fail, adds its error and returns [default]. If Ok, returns the value.
     *
     * Non-null variant of [valueOrNull] — use when you have a sane fallback so dependent
     * validations don't have to guard against null:
     *
     * ```
     * validation<String> {
     *     val name = parseName(input).checkOr("anonymous")     // V, never null
     *     ensure(name.length <= 50) { "name too long" }        // safe to dereference
     * }
     * ```
     */
    @Suppress("NOTHING_TO_INLINE")
    public inline fun <V> Res<V, E>.checkOr(default: V): V {
        return if (inlineValue is Failure) {
            addFramed(inlineValue.error as E, inlineValue.frames)
            default
        } else {
            inlineValue as V
        }
    }

    /**
     * If this is Fail, maps the error via [mapError], adds it, and returns [default]. If Ok, returns the value.
     *
     * Non-null variant of [valueOrNull] — see [checkOr] for usage.
     */
    public inline fun <V, F> Res<V, F>.checkOr(default: V, mapError: (F) -> E): V {
        return if (inlineValue is Failure) {
            addFramed(mapError(inlineValue.error as F), inlineValue.frames)
            default
        } else {
            inlineValue as V
        }
    }

    /** If [res] is Fail, adds its error to the accumulated list. Discards the Ok value. */
    @Suppress("NOTHING_TO_INLINE")
    @JvmName("checkRes")
    public inline fun <V> check(res: Res<V, E>) {
        if (res.inlineValue is Failure) addFramed(res.inlineValue.error as E, res.inlineValue.frames)
    }

    /** If [res] is Fail, maps the error via [mapError] and adds it. Discards the Ok value. */
    @JvmName("checkResMapped")
    public inline fun <V, F> check(res: Res<V, F>, mapError: (F) -> E) {
        if (res.inlineValue is Failure) addFramed(mapError(res.inlineValue.error as F), res.inlineValue.frames)
    }

    /**
     * If [res] is Fail, adds its error and returns `null`. If Ok, returns the value.
     *
     * **Caution:** Validation does not short-circuit, so subsequent code that depends
     * on the returned value will see `null` on the Fail path. Prefer [checkOr] when you
     * have a sane default; otherwise guard the result with a null check to avoid [NullPointerException].
     */
    @Suppress("NOTHING_TO_INLINE")
    @JvmName("valueOrNullRes")
    public inline fun <V> valueOrNull(res: Res<V, E>): V? {
        return if (res.inlineValue is Failure) {
            addFramed(res.inlineValue.error as E, res.inlineValue.frames)
            null
        } else {
            res.inlineValue as V
        }
    }

    /**
     * If [res] is Fail, maps the error via [mapError], adds it, and returns `null`. If Ok, returns the value.
     *
     * **Caution:** See [valueOrNull] for the non-null guidance.
     */
    @JvmName("valueOrNullResMapped")
    public inline fun <V, F> valueOrNull(res: Res<V, F>, mapError: (F) -> E): V? {
        return if (res.inlineValue is Failure) {
            addFramed(mapError(res.inlineValue.error as F), res.inlineValue.frames)
            null
        } else {
            res.inlineValue as V
        }
    }

    /**
     * If [res] is Fail, adds its error and returns [default]. If Ok, returns the value.
     *
     * Non-null variant of [valueOrNull].
     */
    @Suppress("NOTHING_TO_INLINE")
    @JvmName("checkOrRes")
    public inline fun <V> checkOr(default: V, res: Res<V, E>): V {
        return if (res.inlineValue is Failure) {
            addFramed(res.inlineValue.error as E, res.inlineValue.frames)
            default
        } else {
            res.inlineValue as V
        }
    }

    /**
     * If [res] is Fail, maps the error via [mapError], adds it, and returns [default]. If Ok, returns the value.
     *
     * Non-null variant of [valueOrNull].
     */
    @JvmName("checkOrResMapped")
    public inline fun <V, F> checkOr(default: V, res: Res<V, F>, mapError: (F) -> E): V {
        return if (res.inlineValue is Failure) {
            addFramed(mapError(res.inlineValue.error as F), res.inlineValue.frames)
            default
        } else {
            res.inlineValue as V
        }
    }

    /** `true` if any errors have been accumulated. */
    public val hasErrors: Boolean get() = errors.isNotEmpty()

    /** Returns a defensive copy of the accumulated errors. Each call allocates a new list. */
    public fun errors(): List<E> = errors.toList()

    /**
     * Returns each accumulated error paired with the context frames it carried, as a defensive copy.
     *
     * Errors added via [ensure]/[fail]/[ensureNotNull]/[addAll] carry no frames
     * (`FramedError(error, emptyList())`); errors added via [check]/[valueOrNull]/[checkOr] carry the
     * frames from the [Res] they were drawn from. Order matches [errors]. See [FramedError].
     */
    public fun errorsFramed(): List<FramedError<E>> =
        errors.mapIndexed { i, e -> FramedError(e, frameMap?.get(i) ?: emptyList()) }

    /**
     * Returns [Res.Ok] if no errors accumulated, or [Res.Fail] with a defensive copy of the error list.
     *
     * The Ok value is [Unit] — [Validator] accumulates errors, it does not produce values.
     * Construct your result value after checking the validator.
     *
     * **Note:** Both this method and [errors] allocate a new list. If you need both,
     * call [errors] once and reuse the snapshot.
     *
     * The Fail error is a bare `List<E>`, so any frames on checked-in failures are dropped. Use
     * [toResFramed] to retain each error's frames.
     */
    public fun toRes(): Res<Unit, List<E>> =
        if (errors.isEmpty()) Res.ok(Unit) else Res.failure(errors.toList())

    /**
     * Frame-retaining variant of [toRes]: [Res.Ok] if clean, or [Res.Fail] with each error paired
     * with its context frames (see [errorsFramed], [FramedError]).
     *
     * The frames ride inside each [FramedError] element, so the outer failure itself carries none —
     * read them per-error, not via [contextChain][tech.codingzen.resultkit.context.contextChain].
     */
    public fun toResFramed(): Res<Unit, List<FramedError<E>>> =
        if (errors.isEmpty()) Res.ok(Unit) else Res.failure(errorsFramed())

    public companion object {
        /**
         * Creates an empty [Validator] for imperative use.
         *
         * The returned instance is **not thread-safe** — do not share it across
         * coroutines or call mutation methods concurrently.
         */
        public fun <E> validator(): Validator<E> = Validator()
    }
}

/**
 * Creates a [Validator] scope, runs [block] to accumulate errors, and returns
 * [Res.Ok] if none accumulated or [Res.Fail] with the error list.
 *
 * ```
 * val result = validation<String> {
 *     ensure(name.isNotBlank()) { "Name required" }
 *     ensure(age >= 0) { "Negative age" }
 * }
 * // result: Res<Unit, List<String>>
 * ```
 *
 * The Fail error is a bare `List<E>`, so any frames on checked-in failures are dropped. Use
 * [validationFramed] to retain each error's frames.
 */
public inline fun <E> validation(block: Validator<E>.() -> Unit): Res<Unit, List<E>> {
    val v = Validator<E>()
    v.block()
    return v.toRes()
}

/**
 * Frame-retaining variant of [validation]: runs [block] and returns [Res.Ok] if no errors
 * accumulated, or [Res.Fail] with each error paired with its context frames.
 *
 * ```
 * val result: Res<Unit, List<FramedError<String>>> = validationFramed {
 *     check(parseName(input))     // keeps parseName's context frames
 *     ensure(age >= 0) { "Negative age" }   // no frames
 * }
 * ```
 *
 * See [FramedError] and [Validator.toResFramed].
 */
public inline fun <E> validationFramed(block: Validator<E>.() -> Unit): Res<Unit, List<FramedError<E>>> {
    val v = Validator<E>()
    v.block()
    return v.toResFramed()
}