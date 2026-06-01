# Result-Kit vs Arrow

[Arrow](https://arrow-kt.io) is the comprehensive functional-programming toolkit for Kotlin. Result-Kit and Arrow's typed-error layer (`Either`, `Raise`) solve overlapping problems with different philosophies. This document compares them on the dimensions that matter for adoption decisions.

> **TL;DR:** Result-Kit if you want a focused typed-error library with zero runtime dependencies and a value-class representation. Arrow if you want a broader FP toolkit (optics, resilience, FX) and you're already comfortable taking a multi-artifact dependency.

## Core type

| | Result-Kit | Arrow |
|---|---|---|
| Type | `Res<V, E>` | `Either<A, B>` |
| Parameter order | value first, error second | **error first**, value second |
| Representation | `@JvmInline value class` wrapping `Any?` | sealed class `Either<A, B>` with `Left(a)` / `Right(b)` data classes |
| Ok-path allocation | none (raw value) | one (`Right` wrapper) |
| Fail-path allocation | one (`Failure` sentinel) | one (`Left` wrapper) |
| `when` exhaustiveness | no (value class) — use `fold` | yes (sealed class) |
| Subtype relationship | `Res.ok(...)` / `Res.failure(...)` factories | `value.right()` / `error.left()` extensions |

The biggest difference: Arrow's `Either<A, B>` puts the **error type first**. This follows category-theory convention (Left is "bad") and is consistent with Haskell, Scala, Rust's older conventions. Result-Kit puts the value type first because Kotlin code reads value-first ("a result of type V that might fail with E").

Switching mental models between the two is the most common adoption friction.

## Short-circuit DSL

Both libraries support a DSL where you write linear code and short-circuit on the first failure.

**Arrow** uses `Raise<E>` / `either { }`:

```kotlin
import arrow.core.raise.either

fun loadDashboard(id: Int): Either<AppError, Dashboard> = either {
    val user = fetchUser(id).bind()        // bind: unwrap Right or short-circuit
    val prefs = fetchPrefs(user.id).bind()
    Dashboard(user, prefs)
}
```

**Result-Kit** uses `Rail<E>` / `rail { }`:

```kotlin
fun loadDashboard(id: Int): Res<Dashboard, AppError> = rail {
    val user = fetchUser(id).orFail()       // orFail: unwrap Ok or short-circuit
    val prefs = fetchPrefs(user.id).orFail()
    Dashboard(user, prefs)
}
```

The mechanisms differ:
- **Arrow's `Raise<E>`** is non-throwing: `bind()` uses an internal short-circuit via a special non-local return mechanism. No exception escapes the boundary even if you misuse it.
- **Result-Kit's `Rail<E>`** uses a `FailException : Throwable` for short-circuit. Catching `Throwable` inside the rail breaks the control flow. Result-Kit goes out of its way to make this hard to do accidentally (custom `FailException` type, scope-identity check on the catch, documentation), but the failure mode exists.

The Arrow approach is safer; the Result-Kit approach is simpler to implement and reason about.

## Error translation between domains

This is where Result-Kit's design diverges most. The library's central idea: **define exception/error translation once, reuse it everywhere**.

**Arrow** combines `withError` (for typed-error translation) and `catch` (for exceptions):

```kotlin
either {
    val user = withError({ AppError.Network(it) }) {
        fetchUser(id).bind()                 // Either<HttpError, User> → User
    }
    val raw = catch({ AppError.IO(it.message) }) {
        File(path).readText()                // String, may throw IOException
    }
}
```

`withError` and `catch` are inline blocks — each call site repeats the mapping lambda.

**Result-Kit** uses reusable mapping scopes captured outside the rail body:

```kotlin
rail {
    val http = mapping<HttpError> { AppError.Network(it) }
    val io = catching { e -> AppError.IO(e.message) }

    val user = fetchUser(id).orFail(http)
    val raw = io { File(path).readText() }
}
```

The mapping scope (`http`, `io`) is a value — capture it once, reuse it for every call in the block. For a service with 20 HTTP calls, that's one mapping instead of 20.

When a method needs both exception catching *and* typed error mapping (HTTP clients, DB drivers), Result-Kit has `catchingMapping`:

```kotlin
rail {
    val api = catchingMapping<ApiError>(
        onError = { AppError.Api(it) },
        onException = { AppError.Network(it.message) },
    )
    val user = api { retrofitService.getUser(id) }
}
```

Arrow approximates this by nesting `withError` inside `catch`, but the result is more verbose.

## Error context chains

**Result-Kit** has first-class support for breadcrumb-style context attached to failures:

```kotlin
fun loadUserProfile(id: Int): Res<Profile, AppError> =
    userRepo.findById(id)
        .context { "loading profile for user $id" }
        .context { "handling /profile request" }
```

On failure, the frames are accessible via `contextChain()`, `renderContext()`, `contextSummary()`, etc. They survive `mapError`, `orElse Fail→Fail`, and the rail short-circuit boundary.

**Arrow** has no equivalent first-class concept. You'd attach context manually via `mapError`:

```kotlin
either {
    val user = fetchUser(id)
        .mapLeft { ContextualError("loading user $id", it) }
        .bind()
}
```

Building a chain requires you to define `ContextualError` and propagate it yourself.

## Error accumulation

This is a deliberate divergence.

**Arrow** ships a full accumulation toolkit — `zipOrAccumulate` (fixed-arity up to 9) and `mapOrAccumulate` for collections:

```kotlin
either<NonEmptyList<String>, User> {
    zipOrAccumulate(
        { validateName(name).bind() },
        { validateEmail(email).bind() },
        { validateAge(age).bind() },
    ) { n, e, a -> User(n, e, a) }
}
```

**Result-Kit** ships no accumulator on purpose. Gathering *all* of a request's validation errors is exactly what the mature JVM validation ecosystem (Jakarta Bean Validation, Konform, Valiktor) already does, and those drop into `rail { }` in a line by mapping their result into your `E`:

```kotlin
rail {
    val errors = konformSchema.validate(req).errors          // your validation library
    ensure(errors.isEmpty()) { AppError.Invalid(errors.map { it.message }) }
    save(req).orFail()
}
```

Result-Kit keeps only **fail-fast** composition in the box — `zip` (arities 2–4) for "stop at the first failure." If you want accumulation, bring the validator you almost certainly already have on the classpath rather than learning a second one.

## Dependency footprint

| | Result-Kit | Arrow |
|---|---|---|
| Runtime deps | **zero** | `arrow-core` plus what you import |
| Module count | 1 (`result-kit`) | many (`arrow-core`, `arrow-fx-coroutines`, `arrow-optics`, `arrow-resilience`, ...) |
| Multiplatform | JVM only (currently) | full Kotlin Multiplatform |
| ABI tracking | `binary-compatibility-validator` plugin | yes (Arrow ecosystem-wide) |

Result-Kit's zero-dep stance is deliberate — see the [Design doc](design.md#zero-dependencies). For a result type that wraps every return value in your application, the cost of pulling in unrelated machinery is high.

## Ecosystem scope

Arrow provides:
- `Either` / `Raise` (typed errors)
- `Option` (nullable alternative)
- `Optics` (lenses, prisms, traversals for immutable data)
- `Resilience` (retries, circuit breakers, schedules)
- `FX coroutines` (suspending FP combinators)
- `Atomic` / `Concurrent` primitives
- A whole ecosystem of conventions and metaphors

Result-Kit provides:
- `Res<V, E>` (typed errors)
- `rail { }` (short-circuit DSL)
- Mapping scopes (`catching`, `mapping`, `catchingMapping`, `validation`)
- Context frames (`Frame`, `SourceLocation`)
- Iterable extensions, `zip`, interop with `kotlin.Result`

If you only need typed errors, Result-Kit is the focused choice. If you're already adopting FP patterns broadly, Arrow gives you a consistent ecosystem.

## Learning curve

**Arrow** is large. The team and the docs do a lot to make it accessible, but you're learning a vocabulary (`bind`, `withError`, `Raise`, `Schedule`, `Resource`, `arrow-fx-coroutines`, `optics`) and a set of conventions that take a few weeks to internalize.

**Result-Kit** is small. The core surface is `Res`, `rail { } / orFail()`, and three mapping scope types. The full API reference fits on one page. A new Kotlin engineer can be productive in an afternoon.

## When to use which

Choose **Result-Kit** if:

- You want typed errors *only*, not a broader FP framework.
- Zero-dependency, lightweight library is a hard requirement.
- Your team is Kotlin-fluent but not necessarily FP-fluent.
- You want value-class allocation characteristics for hot paths.
- The error-context / breadcrumb chain feature is appealing.
- JVM-only is acceptable today (Kotlin Multiplatform support is a future possibility).

Choose **Arrow** if:

- You're adopting FP patterns more broadly (optics, resilience, effects).
- Kotlin Multiplatform support matters now.
- You want the safer `Raise<E>` short-circuit (no `Throwable` involved).
- `Either`-with-error-first matches your team's existing mental model (e.g. from Scala / Haskell).
- You're comfortable taking a multi-artifact dependency.
- The Arrow ecosystem's optics/resilience features are independently valuable to you.

The two libraries can coexist in the same codebase — `Res<V, E>` and `Either<E, A>` are convertible in a few lines. But mixing them at a single boundary is usually a sign you should pick one and stick with it.

## Quick interop

If you need to bridge between the two:

```kotlin
// Arrow → Result-Kit
fun <V, E> Either<E, V>.toRes(): Res<V, E> = fold({ Res.failure(it) }, { Res.ok(it) })

// Result-Kit → Arrow
fun <V, E> Res<V, E>.toEither(): Either<E, V> = fold({ it.right() }, { it.left() })
```

Frames are not preserved through the conversion — Arrow's `Either` has no equivalent concept.
