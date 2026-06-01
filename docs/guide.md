# Result-Kit Guide

A hands-on walkthrough of Result-Kit, from your first result to composing complex error-handling pipelines.

## Introduction

Railway-Oriented Programming (ROP) models computations as flowing along two tracks: a **success track** where operations proceed normally, and an **error track** where failures short-circuit remaining operations. Instead of throwing exceptions or returning null, each operation returns a result that is either Ok (success) or Fail (error). The `rail {}` DSL lets you write code that looks like straight-line imperative code, but automatically switches to the error track when something goes wrong.

Result-Kit implements this pattern for Kotlin with:
- `Res<V, E>` — a result type parameterized over both value and error
- `rail {}` — a DSL for composing operations with short-circuit semantics
- Mapping scopes — reusable exception catchers and error translators

## Your First Result

Every result is either Ok or Fail. Create them with `Res.ok()` and `Res.failure()`:

```kotlin
import tech.codingzen.resultkit.*

val success: Res<Int, String> = Res.ok(42)
val failure: Res<Int, String> = Res.failure("something went wrong")
```

To consume a result, use `fold` for exhaustive handling:

```kotlin
val message = success.fold(
    onOk = { "The answer is $it" },
    onFail = { "Error: $it" }
)
// message = "The answer is 42"
```

> **Why `fold` and not `when`?** `Res` is a `@JvmInline value class`, not a sealed class, so the compiler can't enforce exhaustive `when` expressions. `fold` guarantees you handle both cases.

For quick checks, use the property accessors:

```kotlin
success.isOk       // true
success.isFail     // false
success.getOrNull() // 42
failure.getOrNull() // null
failure.errorOrNull() // "something went wrong"
```

## Your First Rail

The `rail {}` block creates a scope where you can short-circuit on failure. The last expression becomes the Ok value:

```kotlin
fun divide(a: Int, b: Int): Res<Int, String> = rail {
    if (b == 0) fail("Cannot divide by zero")
    a / b
}

divide(10, 2)  // Res.Ok(5)
divide(10, 0)  // Res.Fail("Cannot divide by zero")
```

`fail(e)` immediately exits the `rail {}` block and produces a `Res.Fail`. No code after `fail()` runs.

## Chaining Operations

The real power of `rail {}` is composing multiple operations that each return `Res`. Use `orFail()` to unwrap an Ok value or short-circuit the entire block:

```kotlin
fun fetchUser(id: Int): Res<User, String> = rail {
    ensure(id > 0) { "Invalid user ID" }
    // imagine a database lookup here
    User(id, "Alice")
}

fun fetchPreferences(userId: Int): Res<Preferences, String> = rail {
    // imagine a database lookup here
    Preferences(userId, theme = "dark")
}

fun loadDashboard(userId: Int): Res<Dashboard, String> = rail {
    val user = fetchUser(userId).orFail()           // unwraps User or short-circuits
    val prefs = fetchPreferences(user.id).orFail()  // only runs if fetchUser succeeded
    Dashboard(user, prefs)                          // wrapped in Res.Ok automatically
}
```

If `fetchUser` returns a Fail, the entire `loadDashboard` block short-circuits — `fetchPreferences` never runs, and the Fail propagates as the result.

When the error types don't match, use `orFail { mapError }` to convert:

```kotlin
fun loadDashboard(userId: Int): Res<Dashboard, AppError> = rail {
    val user = fetchUser(userId).orFail { AppError.UserNotFound(it) }
    val prefs = fetchPreferences(user.id).orFail { AppError.PrefsError(it) }
    Dashboard(user, prefs)
}
```

## Validation

`ensure` and `ensureNotNull` are the validation primitives inside `rail {}`:

```kotlin
fun validateRegistration(
    email: String,
    password: String,
    age: Int
): Res<Registration, String> = rail {
    ensure(email.contains('@')) { "Invalid email address" }
    ensure(password.length >= 8) { "Password must be at least 8 characters" }
    ensure(age in 13..150) { "Age must be between 13 and 150" }
    val username = ensureNotNull(email.split('@').firstOrNull()) { "Could not parse username" }
    Registration(username, email, password, age)
}
```

`ensure` short-circuits if the condition is false. `ensureNotNull` short-circuits if the value is null and returns the non-null value otherwise.

> **Note:** `rail {}` short-circuits on the **first** error. If you need *all* validation errors at once, reach for a validation library (Bean Validation, Konform, Valiktor) and map its result into your error type — see [Combining Results](#combining-results).

## Handling Exceptions

Many libraries — Java IO, JSON parsers, HTTP clients — communicate failure by throwing exceptions. Use `catching` to catch and translate them:

```kotlin
fun loadConfig(path: String): Res<Config, String> = rail {
    val io = catching { e -> "IO error: ${e.message}" }

    val raw = io { File(path).readText() }
    val parsed = io { Json.decodeFromString<Config>(raw) }
    parsed
}
```

Here's what happens:
1. `catching { ... }` creates a reusable exception catcher that maps any `Exception` to your error type
2. `io { ... }` runs the block — if it throws, the exception is caught, mapped, and short-circuited
3. If it succeeds, the value is returned directly (not wrapped in `Res`)

The `io` scope is reusable — use it for every throwing call in the block. `CancellationException` is always rethrown to preserve coroutine structured concurrency.

> **Heads up:** The same `ExceptionMappingRail` instance behaves differently depending on context. Inside `rail {}`, invoking it returns the unwrapped value and short-circuits on error. At the top level, the same call returns `Res<V, E>` instead. The compiler enforces this — a return-type mismatch is a compile error. See [Top-Level Usage](../README.md#top-level-usage-outside-rail--blocks) for details.

For one-off exception catching without mapping, use `Rail.attempt`:

```kotlin
val config: Res<Config, Exception> = Rail.attempt { loadConfigFile(path) }
```

## Working Across Error Domains

Real applications have functions from different domains with different error types. Use `mapping` to translate between them:

```kotlin
sealed class AppError {
    data class Network(val err: HttpError) : AppError()
    data class Database(val err: DbError) : AppError()
}

fun getDashboard(userId: Int): Res<Dashboard, AppError> = rail {
    val http = mapping<HttpError> { AppError.Network(it) }
    val db = mapping<DbError> { AppError.Database(it) }

    val user = fetchUser(userId).orFail(http)        // Res<User, HttpError> → User
    val settings = loadSettings(user.id).orFail(db)  // Res<Settings, DbError> → Settings
    Dashboard(user, settings)
}
```

`mapping` creates a reusable mapper. Pass it to `.orFail(mapping)` to unwrap Ok values or map and short-circuit Fail errors — it does **not** catch exceptions. If your callees can both throw and return `Res`, use `catchingMapping` which handles both:

```kotlin
fun getDashboard(userId: Int): Res<Dashboard, AppError> = rail {
    val http = catchingMapping<HttpError>(
        onError = { AppError.Network(it) },
        onException = { AppError.Unexpected(it) },
    )
    val user = http { userClient.getUser(userId) }
    val profile = http { profileClient.getProfile(user.id) }
    Dashboard(user, profile)
}
```

### Which scope do I need?

| Your callee... | Use |
|---|---|
| Throws exceptions (Java libs, IO, parsing) | `catching` |
| Returns `Res<V, D>` with a different error type | `mapping` |
| Does both (HTTP clients, DB drivers, gRPC) | `catchingMapping` |

## Recovery

Sometimes a Fail isn't terminal — you have a fallback value, a cache, a default. There are two recovery operations depending on whether the fallback can itself fail.

**`recover` — infallible.** The transform always produces a value, so the result is always Ok. The error type collapses to `Nothing`:

```kotlin
val cached: Res<Config, ConfigError> = loadFromDisk()
val safe: Res<Config, Nothing> = cached.recover { Config.defaults() }
```

A two-arg overload (from `tech.codingzen.resultkit.context`) exposes the frame chain to the transform before it's discarded, so you can log "we recovered from X with context Y" without splitting into a separate `.tap` + `.recover` chain:

```kotlin
val safe: Res<Config, Nothing> = loadFromDisk().recover { err, frames ->
    logger.warn("falling back to defaults — was: ${frames.joinToString(" / ")}, err=$err")
    Config.defaults()
}
```

**`orElse` — fallible.** The transform returns another `Res`. If the recovery succeeds you get its Ok. If the recovery itself fails, the original frames are merged with the recovery's frames so you keep the trail back to the original failure:

```kotlin
val live = fetchLiveConfig()                     // Res<Config, NetworkError>
val withFallback: Res<Config, ConfigError> =
    live.orElse { loadFromDisk() }               // Res<Config, ConfigError>

// On Fail → Fail, frames look like:
// [originalNetworkFrames..., recoveryDiskFrames...]
```

Inside `rail {}`, you typically don't reach for `orElse` — you can branch with plain `if`/`when` on the `Res` and call `.orFail()` on whichever branch you choose. `orElse` is most useful as a building block on a chain of `Res` values outside `rail {}`.

### Side effects on both branches

`onOk` / `onFail` add a side effect to one branch. `tap` covers both at once and is convenient for logging or metrics where Ok and Fail need different reactions:

```kotlin
val result = processOrder(id).tap(
    onOk = { logger.info("processed order ${it.id}") },
    onFail = { logger.warn("order $id failed: $it") },
)
```

Both lambdas default to no-op, so `tap(onOk = { ... })` and `tap(onFail = { ... })` are valid for one-sided cases too.

## Combining Results

When you have independent operations that don't depend on each other's output, use `zip`:

```kotlin
val result: Res<Dashboard, String> = zip(
    { fetchUser(id) },
    { fetchSettings(id) },
    { fetchMetrics(id) },
) { user, settings, metrics ->
    Dashboard(user, settings, metrics)
}
```

`zip` short-circuits on the first failure and supports arities 2 through 4.

### Accumulating all errors — use a validation library

`zip` and `rail {}` are **fail-fast**: they stop at the first error. When you want to report *every*
error at once (the classic "form with five bad fields" case), reach for the JVM validation library you
most likely already have — [Jakarta Bean Validation](https://beanvalidation.org/),
[Konform](https://github.com/konform-kt/konform), or [Valiktor](https://github.com/valiktor/valiktor).
They accumulate violations internally; you map the result into your error type and drop it into `rail {}`:

```kotlin
// Libraries that RETURN all violations (Bean Validation, Konform) — no exception needed:
fun register(req: Request, validator: jakarta.validation.Validator): Res<User, AppError> = rail {
    val violations = validator.validate(req)               // Set<ConstraintViolation<Request>>
    ensure(violations.isEmpty()) {
        AppError.Validation(violations.map { "${it.propertyPath}: ${it.message}" })
    }
    saveUser(req).orFail()
}

// Libraries that THROW with all violations (Valiktor) — wrap in catching:
fun register(req: Request): Res<User, AppError> = rail {
    val validate = catching { e ->
        AppError.Validation((e as ConstraintViolationException).constraintViolations.map { it.property })
    }
    validate { validate(req) { validate(Request::email).isEmail() } }
    saveUser(req).orFail()
}
```

This keeps result-kit focused on control flow and error *propagation*, and leaves field/bean validation
to libraries built for it. If you only need to gather errors from a handful of your own `Res`-returning
checks, `listOf(checkA(), checkB(), checkC()).filterFail()` returns every error (see
[Working with Collections](#working-with-collections)).

## Working with Collections

Result-Kit provides extensions for iterables of results:

```kotlin
val results: List<Res<Int, String>> = listOf(Res.ok(1), Res.failure("err"), Res.ok(3))

// Querying
results.allOk()    // false
results.anyOk()    // true
results.anyFail()  // true

// Filtering
results.filterOk()   // [1, 3]
results.filterFail() // ["err"]

// Combining (fail-fast)
results.combine()    // Res.Fail("err") — short-circuits on first Fail

// Partitioning (processes all)
results.partition()  // Pair([1, 3], ["err"])
```

For mapping each element through a failable function:

```kotlin
// Fail-fast: stops on first error
val users: Res<List<User>, String> = userIds.tryMap { id -> fetchUser(id) }

// Side-effecting: stops on first error
val saved: Res<Unit, String> = users.tryForEach { user -> saveUser(user) }
```

## Coroutine Support

`rail {}` works in suspend contexts with no changes — the function is `inline`, so the compiler allows suspend calls inside the block based on the call site:

```kotlin
suspend fun fetchDashboard(userId: Int): Res<Dashboard, String> = rail {
    val http = catching { e -> "HTTP error: ${e.message}" }

    val user = http { userService.getUser(userId) }        // suspend
    val profile = http { profileService.getProfile(user.id) } // suspend
    Dashboard(user, profile)
}
```

All exception-catching scopes (`catching`, `catchingMapping`, `Rail.attempt`) rethrow `CancellationException`, so structured concurrency is preserved. (`mapping` does not catch exceptions at all, so `CancellationException` simply propagates through it unchanged.)

## Database Transactions

Many database frameworks use exceptions to trigger rollback — Spring `@Transactional`, Exposed `transaction {}`, JOOQ, raw JDBC. The `rail {}` DSL uses an internal `FailException` for control flow. These mechanisms can interfere if the boundaries aren't set up correctly.

**The rule: keep `rail {}` outside the transaction boundary.** Use `catching` to wrap the transaction call. Exceptions roll back the transaction first, then `catching` catches and translates the re-thrown exception.

```kotlin
fun transferFunds(from: Int, to: Int, amount: BigDecimal): Res<Transfer, AppError> = rail {
    val db = catching { e -> AppError.Database("Transfer failed: ${e.message}") }

    db {
        transaction {
            val sender = accountRepo.findById(from) ?: throw NotFoundException("Account $from")
            require(sender.balance >= amount) { "Insufficient funds" }
            accountRepo.debit(from, amount)
            accountRepo.credit(to, amount)
            Transfer(from, to, amount)
        }
    }
}
```

The flow on failure:
1. `accountRepo.debit()` throws →
2. `transaction {}` catches, rolls back, re-throws →
3. `catching` catches the re-thrown exception, maps to `AppError` →
4. `rail {}` returns `Res.Fail`

**Don't put `rail {}` inside the transaction.** `FailException` extends `Throwable` (not `Exception`), and some transaction frameworks catch `Throwable` for rollback. This can trigger unintended rollbacks or swallow your typed errors.

### Validate first, transact second

Separate validation from database writes. Validate outside the transaction, then enter the transaction only for writes:

```kotlin
fun createOrder(request: OrderRequest): Res<Order, AppError> = rail {
    // Validation — no transaction needed
    val items = request.items.tryMap { validateItem(it) }
        .orFail { AppError.Validation(it) }
    ensure(items.isNotEmpty()) { AppError.Validation("Order must have at least one item") }

    // Database write — catching wraps the transaction
    val db = catching { e -> AppError.Database(e.message ?: "DB error") }
    db {
        transaction {
            val order = orderRepo.create(request.customerId)
            items.forEach { item -> orderItemRepo.create(order.id, item) }
            inventoryService.reserve(order.id, items)
            order
        }
    }
}
```

### Transaction returns Res

If your repository functions return `Res`, evaluate the result *after* the transaction commits:

```kotlin
fun deactivateUser(userId: Int): Res<Unit, AppError> = rail {
    val db = catching { e -> AppError.Database(e.message ?: "DB error") }

    val result: Res<Unit, AppError> = db {
        transaction {
            val user = userRepo.findById(userId)
                ?: return@transaction Res.failure(AppError.NotFound("User $userId"))
            userRepo.deactivate(user.id)
            emailService.sendDeactivationNotice(user.email)
            Res.ok(Unit)
        }
    }

    // Evaluate after the transaction has committed/rolled back
    result.orFail()
}
```

## Complete Example

Here's an end-to-end example tying together multiple concepts — a user registration flow with validation, exception handling, and error domain translation:

```kotlin
// Domain errors
sealed class RegistrationError {
    data class Validation(val messages: List<String>) : RegistrationError()
    data class Database(val message: String) : RegistrationError()
    data class Email(val message: String) : RegistrationError()
}

// The request carries Bean Validation constraints; @field:NotBlank, @field:Email, @field:Size, etc.
// The validation library accumulates ALL violations for us — result-kit just maps them in.
data class Request(val name: String, val email: String, val password: String)

// Registration flow
suspend fun registerUser(
    req: Request,
    validator: jakarta.validation.Validator,
): Res<User, RegistrationError> = rail {
    // Validate all fields at once — Bean Validation returns every violation, not just the first.
    val violations = validator.validate(req)
    ensure(violations.isEmpty()) {
        RegistrationError.Validation(violations.map { "${it.propertyPath}: ${it.message}" })
    }

    // Save to database (may throw)
    val db = catching { e -> RegistrationError.Database("DB error: ${e.message}") }
    val user = db { userRepository.create(req.name, req.email, req.password) }

    // Send welcome email (may throw)
    val mail = catching { e -> RegistrationError.Email("Email error: ${e.message}") }
    mail { emailService.sendWelcome(user.email) }

    user
}
```

This example demonstrates:
- An external validation library collecting all field errors at once, mapped into the domain error type with a single `ensure`
- Separate `catching` scopes for database and email, each with their own error mapping
- The entire flow reads top-to-bottom as straight-line code despite having multiple failure modes

## Error Context Chains

When a failure propagates through several layers, it can be hard to tell what operations were in flight. Context chains let you attach breadcrumb frames to a failure as it unwinds — without changing the error type `E`.

### Basic usage

```kotlin
fun loadUserProfile(id: Int): Res<Profile, AppError> =
    userRepo.findById(id)
        .context { "loading profile for user $id" }

fun handleRequest(userId: Int): Res<Response, AppError> =
    loadUserProfile(userId)
        .context { "handling request for user $userId" }
```

On the Ok path `.context {}` is a no-op — the lambda is never evaluated.

The frame list is ordered **innermost-first**: index 0 is the frame closest to the original error. In the example above, if `userRepo.findById` fails, the frame list would be:

```
0: "loading profile for user 42"
1: "handling request for user 42"
```

### Inside rail {}

`withFrame` wraps a block and attaches a frame to any failure that short-circuits out of it:

```kotlin
fun processOrder(id: Int): Res<Order, AppError> = rail {
    withFrame("processing order $id") {
        val order = fetchOrder(id).orFail()
        val validated = validateOrder(order).orFail()
        saveOrder(validated).orFail()
    }
}
```

`orFailContext` attaches a frame at the point of short-circuit:

```kotlin
val user = fetchUser(id).orFailContext { "fetching user $id" }
```

### Reading context at the boundary

```kotlin
val result: Res<Order, AppError> = processOrder(id)

// Frame-aware fold (note the two-arg onFail lambda — disambiguates from the standard fold)
result.fold(
    onOk = { order -> respond(order) },
    onFail = { error, frames ->
        logger.error(result.renderContext())
        respond(error)
    },
)

// Or extract individually
val frames: List<Frame> = result.contextChain()
val summary: String = result.contextSummary()        // "frame0 → frame1 → error.toString()"
val logMap: Map<String, Any?> = result.contextMap()  // for structured/JSON logging
```

Sample output for a failure with two frames:

```text
DbError(connection refused)

  0: MetricsRepository.findByTeam(teamId=7)
     at MetricsRepository.kt:42 in findByTeam
  1: building dashboard for user 42
```

`contextSummary()` for the same failure:

```text
building dashboard for user 42 → MetricsRepository.findByTeam(teamId=7) → DbError(connection refused)
```

`contextMap()` for the same failure:

```kotlin
mapOf(
    "error"  to DbError("connection refused"),
    "frames" to listOf(
        mapOf(
            "message"  to "MetricsRepository.findByTeam(teamId=7)",
            "location" to "MetricsRepository.kt:42 in findByTeam",
        ),
        mapOf("message" to "building dashboard for user 42"),
    ),
)
```

## Common Pitfalls

### Don't `catch(Throwable)` or `catch(Exception)` inside `rail {}`

`rail {}` uses an internal `FailException` (extends `Throwable`, not `Exception`) for short-circuit control flow. A bare `catch(Throwable)` inside the rail will swallow it and break the DSL. A `catch(Exception)` is safer — `FailException` slips past it — but it will still silently absorb anything `catching` should be translating.

```kotlin
// WRONG — catches FailException, breaks short-circuit
rail {
    try {
        riskyCall().orFail()
    } catch (t: Throwable) {
        // swallows the rail's own control-flow exception
    }
}

// WRONG — silently eats exceptions that catching should translate
rail {
    try {
        File(path).readText()
    } catch (e: Exception) {
        // exception never reaches a typed error
    }
}

// RIGHT — catching translates exceptions to your error type
rail {
    val io = catching { e -> AppError.IO(e.message) }
    val text = io { File(path).readText() }
}
```

### Don't put `rail {}` inside a transaction

Some transaction frameworks catch `Throwable` to trigger rollback. Because `FailException` extends `Throwable`, that can cause unintended rollbacks or swallow your typed errors. Keep `rail {}` outside; wrap the transaction with `catching` instead. See [Database Transactions](#database-transactions).

### Global `Throwable` interceptors — Spring, gRPC, Sentry, MDC

`FailException` is `Throwable`-typed by design (so user `catch(Exception)` doesn't intercept it). But anything *above* your `rail {}` that catches `Throwable` will see it: Spring `@ExceptionHandler(Throwable::class)`, gRPC `ServerInterceptor`, Sentry's exception capture, MDC clearing filters, request-scoped instrumentation.

The symptom is silent `Ok` from a failed rail, or noisy "unhandled exception" logs for normal control flow.

Mitigations:
- Keep `rail {}` **inside** the request handler — let global interceptors run on framework errors only.
- If an interceptor must run across the `rail {}` boundary, special-case `FailException` and rethrow it.
- Enable a real stack trace with `-Dresultkit.debug=true` when investigating a stray `FailException`. By default `fillInStackTrace` is a no-op for zero-cost control flow; the system property opts in to a JVM stack so you can find the call site. Read once at class init — set the property on the JVM command line, not at runtime.

### Don't write a bare `Res.failure(e)` inside `rail {}` — use `fail(e)` instead

`Res.failure(e)` builds a Fail value; if you don't return or assign it, it's silently dropped. `Rail.fail(e)` short-circuits the rail directly.

```kotlin
// WRONG — Res.failure built, discarded, rail continues
rail {
    if (id < 0) Res.failure("negative id")   // does nothing
    process(id)
}

// RIGHT — short-circuits the rail
rail {
    if (id < 0) fail("negative id")
    process(id)
}
```

`Res.ok` / `Res.failure` are annotated with `@CheckReturnValue` so IntelliJ flags discarded results.

### `mapError` preserves frames

If you transform a Fail error with `mapError`, any context frames already attached are carried over to the new failure. You don't need to re-attach context after changing the error type.

### Frames are storage-order, summaries are reversed

`contextChain()` returns frames innermost-first (index 0 = closest to error). `renderContext()` matches that order. `contextSummary()` reverses for display so the trail reads outermost → innermost → error.

## Next Steps

- [API Reference](../README.md#api-reference) — complete signature tables for every public symbol
- [Design](design.md) — why Result-Kit is built the way it is
