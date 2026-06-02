# Result-Kit

Reusable error translation scopes for Kotlin. Define how exceptions and typed errors map into your domain — once — then apply that mapping everywhere.

```kotlin
// Other result libraries: repeat mapError + bind on every call
binding {
    val user = runCatching { httpClient.getUser(id) }
        .mapError { AppError.Network(it.message) }.bind()
    val prefs = runCatching { httpClient.getPrefs(user.id) }
        .mapError { AppError.Network(it.message) }.bind()
    val settings = runCatching { db.getSettings(user.id) }
        .mapError { AppError.Database(it.message) }.bind()
    Dashboard(user, prefs, settings)
}

// Result-Kit: define the translation once, reuse it
rail {
    val http = catching { e -> AppError.Network(e.message) }
    val sql  = catching { e -> AppError.Database(e.message) }

    val user     = http { httpClient.getUser(id) }
    val prefs    = http { httpClient.getPrefs(user.id) }
    val settings = sql  { db.getSettings(user.id) }
    Dashboard(user, prefs, settings)
}
```

The `catching` scopes are values — capture them once, reuse them across every function in the layer:

```kotlin
class UserService(private val httpClient: HttpClient, private val db: Database) {
    fun loadDashboard(id: Int): Res<Dashboard, AppError> = rail {
        val http = catching { e -> AppError.Network(e.message) }
        val sql  = catching { e -> AppError.Database(e.message) }

        val user     = http { httpClient.getUser(id) }
        val prefs    = http { httpClient.getPrefs(user.id) }
        val settings = sql  { db.getSettings(user.id) }
        Dashboard(user, prefs, settings)
    }

    fun loadProfile(id: Int): Res<Profile, AppError> = rail {
        val http = catching { e -> AppError.Network(e.message) }
        val sql  = catching { e -> AppError.Database(e.message) }

        val user    = http { httpClient.getUser(id) }
        val history = sql  { db.getHistory(user.id) }
        Profile(user, history)
    }
}
```

Built on a `Res<V, E>` inline value class (zero allocation on the Ok path) and a `rail {}` DSL for short-circuit composition. Zero runtime dependencies.

## Installation

```kotlin
dependencies {
    implementation("tech.codingzen:result-kit:2.0.0")
}
```

## Stability

Result-Kit follows [Semantic Versioning](https://semver.org/). From `1.1.0` onward:

- **Major versions** (`2.0.0`, `3.0.0`) may include breaking API changes — renames, removed functions, semantic shifts. Migration guidance lives in the [CHANGELOG](CHANGELOG.md).
- **Minor versions** (`1.2.0`, `1.3.0`) add new APIs without breaking existing ones. Safe to bump without code changes.
- **Patch versions** (`1.1.1`, `1.1.2`) are bug fixes only — no behavioral surprises.

**Binary compatibility** is tracked with the [`binary-compatibility-validator`](https://github.com/Kotlin/binary-compatibility-validator) Gradle plugin. The captured public ABI lives in `result-kit/api/result-kit.api`; CI fails on accidental drift. This matters because almost every public API is `inline` and references `@PublishedApi internal` symbols (notably `Failure`, `FailException`, `Res.unsafeOk`) — any silent shape change to those would break downstream jars compiled against an older version.

**What counts as a breaking change:**

- Any API surface listed in the `.api` dump is part of the stability commitment.
- The names of public scope types (`ExceptionMappingRail`, `ErrorMappingRail`, `MappingRail`) and factory functions (`catching`, `mapping`, `catchingMapping`, `rail`).
- The semantics of frame propagation through `map`, `mapError`, `orElse`, `recover`, and rail boundaries.
- Behavior of `FailException` (extends `Throwable`, no stack trace by default, honours `-Dresultkit.debug`).

**What does not count as breaking:**

- Adding new factory methods, extension functions, or overloads.
- Improving error messages or KDoc.
- Optimizing internal implementation while preserving observable behavior.

The library is JVM-only at present. Kotlin Multiplatform support is a future possibility but not committed.

## Quick Start

> **New here?** This README is the reference. For a start-to-finish tutorial, read the **[Guide](docs/guide.md)** — it builds up from your first `Res` to full pipelines. You only need `rail {}`, `orFail`, `fail`, and `ensure` to be productive; everything else is incremental.

Operations that can fail return `Res<V, E>` — either `Ok` with a value or `Fail` with a typed error. The `rail {}` DSL lets you compose them with automatic short-circuiting:

```kotlin
fun processOrder(orderId: Int): Res<Order, AppError> = rail {
    val order = fetchOrder(orderId).orFail()        // unwrap or short-circuit
    val validated = validateOrder(order).orFail()
    saveOrder(validated).orFail()
}
```

## What Makes This Different

### Three Kinds of Error Translation

Real codebases have code that throws exceptions, code that returns typed errors, and code that does both. Result-Kit handles all three with reusable scopes:

**`catching`** — catches exceptions from IO, HTTP clients, JSON parsing:
```kotlin
val io = catching { e -> AppError.IO(e.message) }
val raw = io { File(path).readText() }
```

**`mapping`** — translates between typed error domains:
```kotlin
val fromUser = mapping<UserError> { AppError.User(it) }
val user = fetchUser(id).orFail(fromUser)
```

**`catchingMapping`** — handles code that both throws and returns typed errors:
```kotlin
val api = catchingMapping<ApiError>(
    onError     = { AppError.Api(it) },
    onException = { AppError.Network(it.message) },
)
val user = api { retrofitService.getUser(id) }
```

### Error Context Chains

Attach breadcrumb frames to failures as they propagate — without changing the error type:

```kotlin
rail {
    withFrame("processing order $id") {
        val user = fetchUser(userId).orFailContext { "fetching user" }
        val order = createOrder(user).orFailContext { "creating order" }
        order
    }
}
// On failure: "processing order 42 → fetching user → DbError(connection refused)"
```

## Documentation

| Document | Audience | Contents |
|---|---|---|
| [Guide](docs/guide.md) | Developers | Full tutorial with examples for every feature |
| [Design](docs/design.md) | Contributors | Architecture, invariants, design rationale |
| [vs kotlin-result](docs/vs-kotlin-result.md) | Evaluators | Feature comparison with kotlin-result |
| [vs Arrow](docs/vs-arrow.md) | Evaluators | Feature comparison with Arrow |

## Important: Exception Handling Inside `rail {}`

The `rail {}` DSL uses an internal exception (`FailException`, extending `Throwable` directly) for control flow. **Do not use raw `try/catch(Throwable)` inside `rail {}` blocks** — use `catching` instead.

### Global `Throwable` interceptors — Spring, gRPC, Sentry, MDC

If your runtime intercepts `Throwable` globally — Spring `@ExceptionHandler(Throwable::class)`, gRPC server interceptors, Sentry capture handlers, MDC clearing filters, or any custom framework that catches `Throwable` — those interceptors will see `FailException` and may swallow or report it. The result is silent `Ok` from a failed rail, or noisy logs for normal control flow.

Mitigations:
- **Keep `rail {}` inside the request handler**, after global interceptors have unwrapped framework-level errors. Don't let a `rail {}` boundary fall *outside* a `catch(Throwable)` block you don't own.
- **Configure interceptors to rethrow `tech.codingzen.resultkit.FailException`.** It is `@PublishedApi internal`, so import it from `tech.codingzen.resultkit` via reflection (`Class.forName("tech.codingzen.resultkit.FailException")`) or by writing the check inside a Kotlin file with `@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")`.
- **Enable debug stack traces with `-Dresultkit.debug=true`** when investigating a stray `FailException`. By default `FailException.fillInStackTrace` is a no-op (zero-cost control flow); the system property opts in to a real stack so you can find the call site. Read once at class init — set on the JVM command line, not at runtime.

See the [Common Pitfalls](docs/guide.md#common-pitfalls) section in the guide for details.

## API Reference

A compact list of every public symbol. The [Guide](docs/guide.md) walks through usage with examples; the [Design doc](docs/design.md) covers rationale.

### `Res<V, E>` — core type

| Symbol | Description |
|---|---|
| `Res.ok(value)` / `Res.failure(error)` | Companion factories. |
| `V?.toResOr { error }` | Non-null → Ok; null → Fail with the lazy error. |
| `Res<V, E>.toFailIf(predicate, transform)` | Convert an Ok to Fail when the predicate matches. |
| `getOrNull()` / `errorOrNull()` | Accessors; null on the wrong branch. |
| `getOrElse { default }` | Ok value or compute a default from the error. |
| `getOrThrow(attachFrames = false)` / `getOrThrow(attachFrames = false) { transform }` | Throw the error directly (`E : Throwable`) or via a transform. Pass `attachFrames = true` to add context frames as suppressed `FrameTrace` entries so the breadcrumb chain appears in stack-trace dumps — opt-in because it mutates the thrown error in place (unsafe for shared/`object` errors). |
| `errorOrThrow()` | Returns the error or throws ISE on Ok (test helper). |
| `fold(onOk, onFail)` | Exhaustive match. |
| `fold(onOk, onFail = (E, List<Frame>) -> T)` | Frame-aware fold. |
| `map { transform }` | Transform Ok; pass Fail through (frames preserved). |
| `mapError { transform }` | Transform Fail error; pass Ok through (frames preserved). |
| `recover { transform }` | Infallible Fail → Ok. Frames discarded. |
| `recover { error, frames -> transform }` | Frame-aware overload — observe frames before they are discarded (e.g. logging). |
| `orElse { transform }` | Fallible recovery — the recovery may itself fail with a different error type. On Fail → Fail, frames are merged. |
| `flatMap { transform }` | Chain `(V) -> Res<U, E>` **outside** `rail {}`. Inside a rail, prefer `orFail()` — that's the primary composition style; reach for `flatMap` only when you're not in a `rail {}` block. |
| `flatten()` | Unwrap `Res<Res<V, E>, E>`. |
| `onOk { action }` / `onFail { action }` | Side effect on one branch. |
| `tap(onOk, onFail)` | Side effects on both branches in one call (both default to no-op). |
| `Result<V>.toRes()` | Interop from `kotlin.Result`. |
| `Res<V, E : Throwable>.toResult()` | Interop to `kotlin.Result`. |
| `Res<V, E>.toResult { transform }` | Interop when `E` is not a `Throwable`. |

### `rail {}` DSL

| Symbol | Description |
|---|---|
| `rail { block }` | Top-level entry. Returns `Res<V, E>`. Inline — works in suspend contexts. |
| `Rail.attempt { block }` | Convenience: catches `Exception`, returns `Res<V, Exception>`. |
| `fail(error)` | Short-circuit with the given error. |
| `Res<V, E>.orFail()` | Unwrap Ok or short-circuit (frames preserved). |
| `Res<V, F>.orFail { mapError }` / `Res<V, F>.orFail(mapping)` | Same, with error type translation. |
| `ensure(condition) { error }` / `ensureNotNull(value) { error }` | Short-circuit on a predicate or null. |
| `Res<V, E>.orFailContext { msg }` / `orFailContext(msg, location)` | Unwrap or short-circuit, appending a context frame. |
| `withFrame(message) { block }` / `withFrame(message, location) { block }` | Run a block; append a frame to any failure that short-circuits out. |

### Mapping scopes

All four scopes are reusable values. Each invokes one way at the top level (returns `Res`) and another inside `rail {}` (unwrapped, short-circuiting).

> ⚠️ **Context-dependent return type.** The *same* scope value returns `V` (short-circuiting) inside a `rail {}` block but `Res<V, E>` outside one — the return type depends on the call's lexical context, not its syntax. The compiler always resolves the correct overload (a mismatch is a compile error, never a silent runtime bug), but when reading code, check whether a call sits inside a `rail {}` to know what it yields.

| Scope | Constructor | Inside `rail {}` |
|---|---|---|
| `ExceptionMappingRail<E>` | `catching { (Exception) -> E }` | `io { block }` returns `V` |
| `ErrorMappingRail<D, E>` | `mapping<D> { (D) -> E }` | `res.orFail(mapping)` or `mapping(res)` |
| `MappingRail<D, E>` | `catchingMapping<D>(onError, onException)` | `m { block returning Res<V, D> }` returns `V` |

Companion factories — `Rail.catching`, `Rail.mapping`, `Rail.catchingMapping` — exist for top-level use.

### Validation — use your validation library

Result-Kit ships **no error accumulator**. Collecting all of a request's validation errors is what the
mature JVM ecosystem already does well — [Jakarta Bean Validation](https://beanvalidation.org/),
[Konform](https://github.com/konform-kt/konform), [Valiktor](https://github.com/valiktor/valiktor) —
and they drop into `rail {}` in a line or two by mapping their result into your error type `E`:

```kotlin
// A library that returns all violations (Bean Validation, Konform):
rail {
    val violations = beanValidator.validate(req)               // Set<ConstraintViolation> / errors list
    ensure(violations.isEmpty()) { AppError.Invalid(violations.map { it.message }) }
    save(req).orFail()
}

// A library that throws with all violations (Valiktor):
rail {
    val toError = catching { e -> AppError.Invalid((e as ConstraintViolationException).constraintViolations.map { it.property }) }
    toError { validate(req) { validate(Request::email).isEmail() } }   // Valiktor's validate
    save(req).orFail()
}
```

For ad-hoc, in-`rail` checks use `ensure` / `ensureNotNull` (they short-circuit on the first failure).
For "collect errors across several `Res`-returning checks" without a library, fold them yourself —
e.g. `listOf(a, b, c).filterFail()` gives every error (see [Composition](#composition)).

### Composition

| Symbol | Description |
|---|---|
| `zip(b1, b2, …, transform)` | Fail-fast sequential composition (arities 2–4). Preserves the failing branch's frames. |
| `Iterable<Res<V, E>>.allOk()` / `anyOk()` / `anyFail()` | Boolean queries. |
| `Iterable<Res<V, E>>.filterOk()` / `filterFail()` | Extract Ok values, or all Fail errors (`filterFail` drops frames). |
| `Iterable<Res<V, E>>.combine()` | Fail-fast `Res<List<V>, E>` (preserves the failing element's frames). |
| `Iterable<Res<V, E>>.partition()` | `Pair<List<V>, List<E>>` — every element categorized (frames dropped). |
| `Iterable<V>.tryMap { (V) -> Res<U, E> }` | Fail-fast map. |
| `Iterable<V>.tryForEach { (V) -> Res<*, E> }` | Fail-fast iteration. |

### Error context

| Symbol | Description |
|---|---|
| `Frame(message, attachment, location)` / `SourceLocation(file, line, function)` | Frame data. |
| `Res<V, E>.context { message }` / `context(message, location)` | Append a frame to a Fail; no-op on Ok. |
| `Res<V, E>.contextChain()` / `renderContext()` / `contextSummary()` / `contextMap()` | Read frames at the reporting boundary. |
| `List<Frame>.findAttachment<T>()` | Find first attachment of a given type. |

For a single `Res`, an error and its frames travel together — read them with `contextChain()` / `renderContext()` at the reporting boundary, before leaving the type system. Frames are dropped when you `toResult()`, `getOrThrow()` (unless `attachFrames = true`), `recover` to Ok, or extract bare errors with `filterFail` / `partition`.

### Top-level usage (outside `rail {}` blocks)

The mapping scopes can be used outside `rail {}` too. The same `catching` value invoked at the top level returns `Res<V, E>` rather than unwrapping:

```kotlin
val io = Rail.catching<AppError> { e -> AppError.IO(e.message) }

// Inside rail {}: returns V, short-circuits on failure
fun loadApp(): Res<App, AppError> = rail {
    val text = io { File(path).readText() }   // V
    parse(text).orFail()
}

// Top-level: returns Res<V, AppError>
fun loadConfigOnly(): Res<String, AppError> =
    io { File(path).readText() }              // Res<String, AppError>
```

The compiler picks the right invoke based on the receiver. A return-type mismatch is a compile error, not a silent runtime bug.

## Building

```bash
./gradlew build    # compile + test
./gradlew test     # tests only
```

**Requirements:** Kotlin 1.9.25+, JDK 21+ to build (targets Java 8 bytecode at runtime).

## License

MIT
