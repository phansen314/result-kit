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

- **Extends `Throwable`, not `Exception`.** This is critical. Inside `rail {}`, users create `failMapping` scopes that catch `Exception`. If `FailException` extended `Exception`, those scopes would intercept the control-flow exception and break the railway. By extending `Throwable` directly, `catch(Exception)` blocks never see it.

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
| `FailMappingRail<E>` | Exception translation | Yes | No |
| `ErrorMappingRail<D, E>` | Typed error translation | No | Yes |
| `MappingRail<D, E>` | Both | Yes | Yes |

**Why no inheritance?** The scopes have different type parameters and different invoke signatures. An inheritance hierarchy would require complex generics, and the overlapping methods would need to be carefully overridden. Duplicating the few shared methods across standalone classes is simpler, produces clearer error messages, and avoids constraining future changes.

### The @RailDsl Marker

`@RailDsl` is a `@DslMarker` annotation applied to `Rail`:

```kotlin
@DslMarker
public annotation class RailDsl
```

This prevents implicit access to an outer `Rail` receiver from within a nested `rail {}` block. Without it, a nested `rail {}` could accidentally call `fail()` on the outer scope, producing confusing behavior. The compiler forces you to use explicit qualification if you intentionally want to access an outer scope.

### Dual Behavior of Mapping Scopes

Each mapping scope (`FailMappingRail`, `ErrorMappingRail`, `MappingRail`) has two invoke implementations:

1. **Top-level extension function** — creates its own `Rail` scope, returns `Res<V, E>`
2. **Member extension on `Rail<E>`** — short-circuits the outer rail, returns unwrapped `V`

Kotlin's member extension resolution ensures the correct one is selected: inside a `rail {}` block (where `this` is `Rail<E>`), the member extension wins. Outside, only the top-level extension is in scope.

This means the same `FailMappingRail` instance can be used as both a top-level entry point and a scoped exception catcher inside `rail {}`, with the compiler enforcing correct usage via return types.

**`ErrorMappingRail` and `.orFail(mapping)`:** In addition to the invoke pattern, `ErrorMappingRail` can be passed directly to `.orFail(mapping)` inside `rail {}` blocks. This is the preferred pattern because it reads consistently with `orFail()` and `orFail { }`:

```kotlin
val http = errorMapping<HttpError> { AppError.Network(it) }
val user = fetchUser(id).orFail(http)  // preferred over http(fetchUser(id))
```

The member extension `invoke(res)` still works but `.orFail(mapping)` is the primary documented pattern for `ErrorMappingRail` inside `rail {}` blocks. `FailMappingRail` and `MappingRail` continue to use the invoke pattern because they accept blocks, not `Res` values.

## Zero Dependencies

Result-Kit has zero runtime dependencies beyond the Kotlin standard library. This is a deliberate design constraint:

- **No kotlinx-coroutines dependency.** Coroutine support works via `inline` functions and the stdlib `CancellationException` type (`kotlin.coroutines.cancellation.CancellationException`), not the kotlinx one.
- **No Arrow, no kotlinx-serialization, no test frameworks at runtime.** The only test dependency is `kotlinx-coroutines-test` for testing suspend functions.

A result type is foundational infrastructure — it wraps return values throughout a codebase. Adding transitive dependencies to it would force those dependencies on every consumer. By staying at zero, Result-Kit can be adopted without version conflicts or dependency bloat.

## Mapping Scope Dual Behavior

The dual behavior (top-level vs inside rail) deserves a deeper explanation because it's the most surprising part of the API.

Consider `FailMappingRail<E>`. It has two `invoke` operator functions:

```kotlin
// Top-level extension (in FailMappingRail.kt)
public inline operator fun <V, E> FailMappingRail<E>.invoke(
    block: Rail<E>.() -> V
): Res<V, E>

// Member extension (in Rail.kt, inside Rail<E>)
public inline operator fun <V> FailMappingRail<E>.invoke(
    block: Rail<E>.() -> V
): V
```

When you write `io { someCode() }` inside a `rail {}` block, the compiler sees two candidates. The member extension on `Rail<E>` wins because Kotlin's resolution rules prefer member extensions over standalone extensions. The return type is `V` (unwrapped), and on failure, the outer rail is short-circuited.

When you write `io { someCode() }` at the top level (not inside `rail {}`), there is no `Rail<E>` receiver, so only the standalone extension is in scope. The return type is `Res<V, E>`.

This means you can define a `FailMappingRail` once and use it in both contexts:

```kotlin
val appRail = Rail.failMapping<AppError> { e -> AppError.Unexpected(e) }

// Top-level: returns Res
fun loadConfig(): Res<Config, AppError> = appRail { parseConfig() }

// Inside rail: returns V, short-circuits on error
fun loadApp(): Res<App, AppError> = rail {
    val config = appRail { parseConfig() }  // V, not Res<V, E>
    App(config)
}
```
