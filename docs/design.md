# Design

The design rationale behind Result-Kit — why it exists, the tradeoffs it makes, and how the internals work.

## Why Another Result Type?

Kotlin has several ways to represent operations that can fail:

- **Exceptions** — the JVM default, but they're invisible in type signatures, easy to forget to catch, and expensive to create (stack trace allocation). They conflate "something went wrong" with "the program has a bug."
- **Nullable returns** — `T?` says "this might not work" but gives you no information about *why* it failed. Error details are lost.
- **`kotlin.Result<T>`** — wraps success or failure, but the error type is always `Throwable`. You can't represent domain-specific errors like `UserNotFound` or `InvalidEmail` without subclassing `Throwable`.
- **Arrow's `Either<E, A>`** — fully typed, but is a sealed class (allocates on both paths) and comes with a large dependency graph.

Result-Kit occupies a specific niche: **typed errors with zero allocation on the happy path and zero runtime dependencies.** If you want `Res<User, AppError>` where `AppError` is a sealed class, enum, string, or any type you choose — and you want the Ok path to be as cheap as returning the value directly — that's what Result-Kit provides.

## Inline Value Class

`Res<V, E>` is a `@JvmInline value class` wrapping `Any?`:

```kotlin
@JvmInline
public value class Res<out V, out E> @PublishedApi internal constructor(
    @PublishedApi internal val inlineValue: Any?
)
```

**Ok values** are stored directly — `Res.ok(42)` is just `42` at runtime, with no wrapper allocation. The JVM sees an `Any?`, not a `Res` object.

**Fail values** are wrapped in an internal `Failure` sentinel class. This is the one allocation on the error path. Variant discrimination is via `instanceof Failure`.

**Why not a sealed class?** A sealed class like `sealed class Res<V, E> { data class Ok<V>(val value: V) : Res<V, Nothing>(); data class Fail<E>(val error: E) : Res<Nothing, E>() }` allocates a wrapper object on *every* call, including the happy path. For a result type that wraps every return value in an error-handling pipeline, this adds up. The inline value class avoids this entirely for Ok values.

**The tradeoff:** You lose exhaustive `when` expressions. The compiler can't know that `Res` has exactly two states, so `when(result) { is Ok -> ...; is Fail -> ... }` doesn't compile. Use `fold()` for exhaustive handling instead — it's checked at compile time and reads cleanly.

**Nested Res safety:** `Res<Res<Int, String>, String>` is safe. The inner `Res` gets boxed when stored as `Any?` in generic contexts, so the runtime can distinguish between "an Ok containing a Res" and "a bare Res."

## Control Flow via FailException

The `rail {}` DSL achieves short-circuit semantics by throwing and catching an internal exception:

```kotlin
@PublishedApi
internal class FailException(
    val error: Any?,
    val scope: Rail<*>
) : Throwable(null, null, true, false)  // suppressStackTrace = false, writableStackTrace = false
```

Key details:

- **Extends `Throwable`, not `Exception`.** This is critical. Inside `rail {}`, users create `catching` scopes that catch `Exception`. If `FailException` extended `Exception`, those scopes would intercept the control-flow exception and break the railway. By extending `Throwable` directly, `catch(Exception)` blocks never see it.

- **Stack trace is suppressed.** The `Throwable` constructor is called with `writableStackTrace = false`, so no stack trace is allocated. This exception is purely for control flow — it's caught at the `rail {}` boundary and never exposed to callers. Suppressing the stack trace makes it essentially free.

- **Carries a scope reference.** Each `FailException` stores a reference to the `Rail` instance that created it. The `rail {}` boundary checks `e.scope === this` before catching — if the exception came from a nested inner `rail {}`, it propagates upward instead of being caught by the wrong scope.

### Why not use coroutine-style continuations?

Coroutine-based approaches (like `kotlin-result`'s `binding {}`) are elegant but require the `kotlinx-coroutines` dependency. Result-Kit's goal is zero runtime dependencies. The exception-based approach achieves the same short-circuit semantics with no dependencies beyond the Kotlin standard library, at the cost of requiring users to avoid `catch(Throwable)` inside `rail {}` blocks.

## Single Entry Point

Both synchronous and suspend code use the same `rail {}` function:

```kotlin
public inline fun <V, E> rail(block: Rail<E>.() -> V): Res<V, E>
```

Because `rail` is `inline`, the lambda body is inlined at the call site. If the call site is a `suspend` function, the compiler allows suspend calls inside the lambda. No separate `suspendRail {}` or `rail.suspend {}` API is needed.

This works because Kotlin's inline functions don't enforce a specific calling convention on their lambda parameters — the lambda inherits the calling convention of the call site.

## Companion Factories

`Res.ok()` and `Res.failure()` live on `Res.Companion`, not as top-level functions:

```kotlin
val success = Res.ok(42)        // not ok(42)
val failure = Res.failure("err") // not failure("err")
```

**Why?** Top-level `ok()` and `failure()` would pollute the global namespace and conflict with common variable names. `Res.ok()` is unambiguous, discoverable via IDE auto-complete on `Res.`, and mirrors Kotlin's `Result.success()` / `Result.failure()` convention.

## Scope Architecture

Result-Kit has four scope types, each standalone with no inheritance between them:

| Scope | Purpose | Exception catching | Error mapping |
|---|---|---|---|
| `Rail<E>` | Base DSL scope | No | No |
| `ExceptionMappingRail<E>` | Exception translation | Yes | No |
| `ErrorMappingRail<D, E>` | Typed error translation | No | Yes |
| `MappingRail<D, E>` | Both | Yes | Yes |
| `ValidationMapping<F, E>` | Error accumulation + mapping | No | Yes |

**Why no `Rail` suffix on `ValidationMapping`?** The `*Rail` types all create a `Rail<E>()` scope internally in their top-level invoke. `ValidationMapping` creates a `Validator<F>()` instead — it accumulates errors rather than short-circuiting. The name reflects this: it maps validation errors, not rail errors.

**Why no inheritance?** The scopes have different type parameters and different invoke signatures. An inheritance hierarchy would require complex generics, and the overlapping methods would need to be carefully overridden. Duplicating the few shared methods across standalone classes is simpler, produces clearer error messages, and avoids constraining future changes.

### The @RailDsl Marker

`@RailDsl` is a `@DslMarker` annotation applied to `Rail`:

```kotlin
@DslMarker
public annotation class RailDsl
```

This prevents implicit access to an outer `Rail` receiver from within a nested `rail {}` block. Without it, a nested `rail {}` could accidentally call `fail()` on the outer scope, producing confusing behavior. The compiler forces you to use explicit qualification if you intentionally want to access an outer scope.

### Dual Behavior of Mapping Scopes

Each mapping scope (`ExceptionMappingRail`, `ErrorMappingRail`, `MappingRail`) has two invoke implementations:

1. **Top-level extension function** — creates its own `Rail` scope, returns `Res<V, E>`
2. **Member extension on `Rail<E>`** — short-circuits the outer rail, returns unwrapped `V`

Kotlin's member extension resolution ensures the correct one is selected: inside a `rail {}` block (where `this` is `Rail<E>`), the member extension wins. Outside, only the top-level extension is in scope.

This means the same `ExceptionMappingRail` instance can be used as both a top-level entry point and a scoped exception catcher inside `rail {}`, with the compiler enforcing correct usage via return types.

**`ErrorMappingRail` and `.orFail(mapping)`:** In addition to the invoke pattern, `ErrorMappingRail` can be passed directly to `.orFail(mapping)` inside `rail {}` blocks. This is the preferred pattern because it reads consistently with `orFail()` and `orFail { }`:

```kotlin
val http = mapping<HttpError> { AppError.Network(it) }
val user = fetchUser(id).orFail(http)  // preferred over http(fetchUser(id))
```

The member extension `invoke(res)` still works but `.orFail(mapping)` is the primary documented pattern for `ErrorMappingRail` inside `rail {}` blocks. `ExceptionMappingRail` and `MappingRail` continue to use the invoke pattern because they accept blocks, not `Res` values.

## Zero Dependencies

Result-Kit has zero runtime dependencies beyond the Kotlin standard library. This is a deliberate design constraint:

- **No kotlinx-coroutines dependency.** Coroutine support works via `inline` functions and the stdlib `CancellationException` type (`kotlin.coroutines.cancellation.CancellationException`), not the kotlinx one.
- **No Arrow, no kotlinx-serialization, no test frameworks at runtime.** The only test dependency is `kotlinx-coroutines-test` for testing suspend functions.

A result type is foundational infrastructure — it wraps return values throughout a codebase. Adding transitive dependencies to it would force those dependencies on every consumer. By staying at zero, Result-Kit can be adopted without version conflicts or dependency bloat.

## Mapping Scope Dual Behavior

The dual behavior (top-level vs inside rail) deserves a deeper explanation because it's the most surprising part of the API.

Consider `ExceptionMappingRail<E>`. It has two `invoke` operator functions:

```kotlin
// Top-level extension (in ExceptionMappingRail.kt)
public inline operator fun <V, E> ExceptionMappingRail<E>.invoke(
    block: Rail<E>.() -> V
): Res<V, E>

// Member extension (in Rail.kt, inside Rail<E>)
public inline operator fun <V> ExceptionMappingRail<E>.invoke(
    block: Rail<E>.() -> V
): V
```

When you write `io { someCode() }` inside a `rail {}` block, the compiler sees two candidates. The member extension on `Rail<E>` wins because Kotlin's resolution rules prefer member extensions over standalone extensions. The return type is `V` (unwrapped), and on failure, the outer rail is short-circuited.

When you write `io { someCode() }` at the top level (not inside `rail {}`), there is no `Rail<E>` receiver, so only the standalone extension is in scope. The return type is `Res<V, E>`.

This means you can define a `ExceptionMappingRail` once and use it in both contexts:

```kotlin
val appRail = Rail.catching<AppError> { e -> AppError.Unexpected(e) }

// Top-level: returns Res
fun loadConfig(): Res<Config, AppError> = appRail { parseConfig() }

// Inside rail: returns V, short-circuits on error
fun loadApp(): Res<App, AppError> = rail {
    val config = appRail { parseConfig() }  // V, not Res<V, E>
    App(config)
}
```

## Error Context Chains

### Why frames are in Failure, not in a wrapper type

The alternative — a wrapper like `Traced<Res<V, E>>` or an error wrapper like `WithContext<E>` — would change every function signature that participates in context tracking. That creates friction: you can't call a function returning `Res<V, E>` directly from a function returning `Traced<Res<V, E>>`, and consumers of `E` would have to unwrap it everywhere.

Storing frames inside the internal `Failure` sentinel keeps the public API entirely unchanged. `Res<V, E>` stays `Res<V, E>`. Consumers who don't care about context are completely unaffected. Consumers who do care call `.contextChain()` or `.renderContext()` at the reporting boundary.

The one place a wrapper type *is* used — `FramedError<E>(error, frames)` — is deliberately confined to **accumulation**, where the result is already a collection (`List<E>`) rather than a bare `Res<V, E>`. Collapsing N failures into one loses the per-error frame association that a single `Failure` holds for free, and a `List<E>` has nowhere to put it. There the wrapper costs no signature friction on the normal flow (it appears only inside the `…Framed` opt-in functions) while restoring the pairing. See "Where frames are dropped, and how to keep them" below.

### Zero cost on the Ok path

`.context(message: () -> String)` performs one `instanceof Failure` check. On Ok, it returns `this` immediately. The lambda is never allocated and never evaluated. On Fail, one new `Failure` is allocated with the frame list extended by one entry.

This means you can add `.context {}` calls liberally in hot code paths without worrying about Ok-path overhead.

### Frame ordering: append, not prepend

Frames are appended (index 0 = innermost/most-specific). This is the natural reading order: start at 0, work outward. It matches how call stacks are typically presented — the deepest frame first.

Prepend would be more efficient (linked list, `O(1)` prepend vs `O(n)` list copy) but would require reversing the list for display. The current approach keeps the implementation simple and the frame list immediately usable without reversal.

### orFailContext — lazy lambda, not plain String

`orFailContext` takes a `() -> String` lambda rather than a plain `String`. The lambda is only invoked on the Fail path, so there is zero allocation on Ok. The function is named `orFailContext` (not `orFail`) to avoid overload ambiguity with `orFail(mapError: (F) -> E)` when `E = String` — `{ "message" }` would be ambiguous between `() -> String` and `(String) -> String`.

### FailException carries frames

`FailException` extends `Throwable` for control flow. When `.orFailContext { }` or `withFrame` short-circuits, the frame is attached to the `FailException` before it's thrown. All `Failure`-constructing catch sites (in `RailBuilder`, `ExceptionMappingRail`, `ErrorMappingRail`, `MappingRail`) transfer `e.frames` to the new `Failure`. This ensures frames survive the throw/catch journey intact, even across mapping and exception-catching scopes.

### Frames across recovery

`recover` and `orElse` follow consistent rules:

- **`recover { ... }`** — infallible. The result is always Ok, and Ok carries no frames, so the original frames are discarded by definition. The recovery has succeeded and the trail is no longer relevant.
- **`orElse { ... }` returning Ok** — same as `recover`: frames discarded, the result is Ok.
- **`orElse { ... }` returning Fail** — frames are merged as `original.frames + rec.frames`. The original frames sit before the recovery's frames so the chain reads from the most-specific original context outward through whatever context the recovery added. This preserves the trail back to the original failure when fallible recovery itself fails.

This is the only operation that combines two frame lists. Every other *propagation* path either preserves frames unchanged (`map`, `mapError`, `flatMap`, `flatten`, `orFail`, `zip`, `combine`/`tryMap`/`tryForEach`, mapping-rail boundaries) or appends a single new frame (`.context`, `contextFrame`, `withFrame`, `orFailContext`).

### Where frames are dropped, and how to keep them

A handful of paths drop frames — always for a structural reason, never silently:

- **Recovery to Ok** — `recover` and a successful `orElse` produce an Ok, which carries no frames (above).
- **Leaving the type system** — `toResult()` maps to stdlib `Result`, which has no frame slot; `getOrThrow()` throws the bare error unless you opt in with `attachFrames = true` (which re-attaches frames as suppressed `FrameTrace` entries).
- **Exception-caught mapping** — when a mapping rail catches a real `Exception`, there is no `Failure` and thus no frames to carry; the resulting failure starts empty.
- **Accumulation** — `zipOrAccumulate`, `Validator`/`validation`, and `filterFail`/`partition` collapse many failures into one `List<E>`. `List<E>` has no per-error slot for frames, so they are dropped.

The accumulation case is the one where you might genuinely want the frames back. The library keeps **errors** (`List<E>`, domain values) and **frames** (observability) separate rather than forcing every error type to be frame-bearing — `E` can be a bare `String`. So retention is opt-in through a small paired carrier, `FramedError<E>(error, frames)`, and `…Framed` siblings: `zipOrAccumulateFramed`, `validationFramed` / `Validator.toResFramed` / `errorsFramed` / `orFailFramed`, `filterFailFramed`, `partitionFramed`. The default `List<E>` paths are unchanged — `Validator` keeps a lazily-allocated sparse frame side-table so an `ensure`-only validation pays nothing.

## Binary Compatibility

The project uses the [`binary-compatibility-validator`](https://github.com/Kotlin/binary-compatibility-validator) Gradle plugin to track the public ABI. The captured API dump lives in `result-kit/api/result-kit.api` and is checked on every build.

Why this matters specifically for Result-Kit: almost every public API is `inline`. Inline functions are copied into the consumer's bytecode at compile time, so any change to a referenced `@PublishedApi internal` symbol (notably `Failure`, `FailException`, `Res.unsafeOk`) is an ABI break — old consumer jars compiled against an earlier shape will fail to link.

**Policy:** changes to `*.api` files are reviewed alongside the code change. A diff that adds new entries is additive (safe). A diff that removes or changes signatures is a breaking change and must be paired with a major version bump or a documented migration path.

Run `./gradlew apiDump` to update the dumps after intentional API changes; `./gradlew apiCheck` (part of `build`) verifies the dumps are in sync.
