# Result-Kit vs kotlin-result

[kotlin-result](https://github.com/michaelbull/kotlin-result) by Michael Bull is the most popular standalone typed-error library for Kotlin. It and Result-Kit are closer cousins than either is to Arrow — both occupy the "lightweight typed errors" niche. This document compares them on the dimensions that matter for adoption decisions.

> **TL;DR:** kotlin-result if you want a mature, multiplatform library with a sealed-class representation and a coroutine-based `binding { }` DSL. Result-Kit if you want value-class allocation characteristics, reusable mapping scopes, first-class context frames, and zero dependencies (including no `kotlinx-coroutines` for the core DSL).

## Core type

| | Result-Kit | kotlin-result |
|---|---|---|
| Type | `Res<V, E>` | `Result<V, E>` |
| Parameter order | value first, error second | value first, error second (same!) |
| Representation | `@JvmInline value class` wrapping `Any?` | sealed class `Result<V, E>` with `Ok(value)` / `Err(error)` data classes |
| Ok-path allocation | none (raw value) | one (`Ok` wrapper) |
| Fail-path allocation | one (`Failure` sentinel) | one (`Err` wrapper) |
| `when` exhaustiveness | no (value class) — use `fold` or `getOr*`/`onSuccess` | yes (sealed class) |

Parameter order matches, so mental-model switching between the two is low-friction. The fundamental representation differs: kotlin-result uses a conventional sealed class (1 allocation per Ok call); Result-Kit uses an inline value class (0 allocations on Ok). For a result wrapper that flows through every error-handling boundary, the value-class approach matters under load.

## Short-circuit DSL

Both have a DSL for linear short-circuit composition.

**kotlin-result** uses `binding { }` with `.bind()`:

```kotlin
import com.github.michaelbull.result.coroutines.binding.binding

suspend fun loadDashboard(id: Int): Result<Dashboard, AppError> = binding {
    val user = fetchUser(id).bind()         // bind: unwrap Ok or short-circuit
    val prefs = fetchPrefs(user.id).bind()
    Dashboard(user, prefs)
}
```

**Result-Kit** uses `rail { }` with `.orFail()`:

```kotlin
fun loadDashboard(id: Int): Res<Dashboard, AppError> = rail {
    val user = fetchUser(id).orFail()
    val prefs = fetchPrefs(user.id).orFail()
    Dashboard(user, prefs)
}
```

Important difference: kotlin-result's `binding { }` is **coroutine-based** — it uses `suspend` machinery for the short-circuit primitive. The core library imports `kotlinx-coroutines` as a transitive dependency. Result-Kit's `rail { }` is just an inline function with `try/catch` around a custom `Throwable` subclass. No coroutine dependency.

This means:
- **kotlin-result** requires a coroutine context to use the binding DSL. The non-suspend `binding` was deprecated in favor of the suspend-based one.
- **Result-Kit's** `rail { }` works in any context — sync, suspend, in tests, in `init` blocks, anywhere.

Both use a similar control-flow trick internally — kotlin-result has its own `BindException`, Result-Kit has `FailException`. Both extend `Throwable` rather than `Exception` to avoid being caught by `catch(Exception)` blocks. The footgun (catching `Throwable` breaks the DSL) applies to both.

## Error translation between domains

This is the largest divergence. kotlin-result expects you to chain `.mapError` at every call site:

```kotlin
binding {
    val user = fetchUser(id).mapError { AppError.Network(it) }.bind()
    val prefs = fetchPrefs(user.id).mapError { AppError.Network(it) }.bind()
    val settings = db.getSettings(user.id).mapError { AppError.Database(it) }.bind()
}
```

For N calls to the same domain, that's N copies of the same `.mapError`.

Result-Kit's central design move is to capture the translation once and reuse it:

```kotlin
rail {
    val http = mapping<HttpError> { AppError.Network(it) }
    val db = mapping<DbError> { AppError.Database(it) }

    val user = fetchUser(id).orFail(http)
    val prefs = fetchPrefs(user.id).orFail(http)        // same http
    val settings = db.getSettings(user.id).orFail(db)
}
```

For functions that both throw exceptions and return `Result`/`Res`, kotlin-result has nothing built-in — you typically wrap in `runCatching { ... }.mapError { ... }.bind()`. Result-Kit has `catchingMapping` that handles both in one scope.

## Error context chains

**Result-Kit** has first-class context frames:

```kotlin
fun loadUserProfile(id: Int): Res<Profile, AppError> =
    userRepo.findById(id)
        .context { "loading profile for user $id" }
        .context { "handling /profile request" }
```

Frames survive `mapError`, `orFail`, and the rail short-circuit boundary.

**kotlin-result** has no equivalent. You'd attach context manually by enriching the error type:

```kotlin
binding {
    val user = userRepo.findById(id)
        .mapError { ContextualError("loading profile for user $id", it) }
        .bind()
}
```

You define `ContextualError` and the chaining logic yourself.

## Composition primitives

| | Result-Kit | kotlin-result |
|---|---|---|
| Pair zip | `zip(b1, b2) { ... }` | `zip(b1, b2) { ... }` |
| Higher arity | `zip` (2–4), fail-fast only | `zip`/`zipOrAccumulate` (up to 5) |
| Iterable | `combine`, `partition`, `tryMap`, `tryForEach`, `allOk`, `anyOk`, etc. | `combine`, `partition`, `andThen` chains, `getAll`, etc. |
| Accumulation | none — use a JVM validation library | `zipOrAccumulate`, `getAll` |
| Interop | `Result<V>.toRes()` / `Res<V, E>.toResult()` | `kotlinx-result` → stdlib `kotlin.Result` interop is manual |

Result-Kit deliberately ships no error accumulator: collecting all of a request's validation errors is delegated to the mature JVM ecosystem (Jakarta Bean Validation, Konform, Valiktor), which maps into a `rail { }` in a line. kotlin-result includes `zipOrAccumulate` if you prefer it in-library.

## Exception handling

Both libraries can catch exceptions and turn them into typed errors, with different ergonomics:

**kotlin-result** uses `runCatching` from the stdlib:

```kotlin
val res = runCatching { File(path).readText() }
    .mapError { e -> AppError.IO(e.message) }
```

**Result-Kit** uses a reusable `catching` scope:

```kotlin
rail {
    val io = catching { e -> AppError.IO(e.message) }
    val text = io { File(path).readText() }
}
```

For a single one-off, kotlin-result is one line. For multiple calls under the same translation, Result-Kit's reusable scope wins.

Result-Kit also has `Rail.attempt { ... }` as a convenience that catches `Exception` and returns `Res<V, Exception>` — equivalent to `runCatching` but returning the library's type.

## Dependency footprint

| | Result-Kit | kotlin-result |
|---|---|---|
| Core runtime deps | **zero** | `kotlinx-coroutines-core` (for `binding`) |
| Multiplatform | JVM only (currently) | full Kotlin Multiplatform (JVM, JS, Native, WASM) |
| Module count | 1 (`result-kit`) | several (`kotlin-result`, `kotlin-result-coroutines`, etc.) |
| ABI tracking | `binary-compatibility-validator` plugin | yes |

kotlin-result's coroutine dependency is small and likely already on your classpath, but it's worth knowing. Result-Kit deliberately keeps the core out of the coroutine machinery — `rail { }` works in suspend contexts because it's an `inline` function, not because it suspends internally.

If you need multiplatform today, kotlin-result is the answer. Result-Kit is JVM-only at present; KMP support is a future possibility but not committed.

## Community and maturity

kotlin-result has been around since 2017 with steady releases, broad adoption, and several mature integrations (Ktor, Arrow interop helpers, etc.). Result-Kit is newer and more opinionated.

## Learning curve

Both are small. kotlin-result's API surface is `Result<V, E>`, `Ok`/`Err`, `binding { }`, and a set of combinators (`map`, `mapError`, `andThen`, `getOr*`). Result-Kit's surface adds the mapping-scope concept (`catching`/`mapping`/`catchingMapping`) plus context frames. Both can be productively learned in an afternoon.

## When to use which

Choose **Result-Kit** if:

- The reusable mapping-scope pattern fits your codebase (many calls to the same domain in a single function).
- You want first-class context frames / breadcrumb chains.
- Zero runtime dependencies is a hard requirement.
- You want value-class allocation characteristics on the Ok path.
- You want a sync-and-suspend DSL with no coroutine dependency.
- JVM-only is acceptable.

Choose **kotlin-result** if:

- Kotlin Multiplatform support matters now.
- A more mature library with a broader user base is reassuring.
- You're already deep in `kotlinx-coroutines` and the coroutine-based `binding { }` is natural.
- The sealed-class representation (with exhaustive `when`) is preferred over `fold`.
- Your error translation is mostly inline `.mapError` calls; the reusable-scope pattern doesn't pay off for you.

## Quick interop

```kotlin
// kotlin-result Result<V, E> → Result-Kit Res<V, E>
fun <V, E> com.github.michaelbull.result.Result<V, E>.toRes(): Res<V, E> =
    fold({ Res.ok(it) }, { Res.failure(it) })

// Result-Kit Res<V, E> → kotlin-result Result<V, E>
fun <V, E> Res<V, E>.toKotlinResult(): com.github.michaelbull.result.Result<V, E> =
    fold(
        onOk = { com.github.michaelbull.result.Ok(it) },
        onFail = { com.github.michaelbull.result.Err(it) },
    )
```

Frames are not preserved through the conversion — kotlin-result has no equivalent concept.
