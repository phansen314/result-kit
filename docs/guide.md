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

> **Note:** `rail {}` short-circuits on the **first** error. If you need all validation errors at once, use `zipOrAccumulate` (see [Combining Results](#combining-results)).

## Handling Exceptions

Many libraries — Java IO, JSON parsers, HTTP clients — communicate failure by throwing exceptions. Use `failMapping` to catch and translate them:

```kotlin
fun loadConfig(path: String): Res<Config, String> = rail {
    val io = failMapping { e -> "IO error: ${e.message}" }

    val raw = io { File(path).readText() }
    val parsed = io { Json.decodeFromString<Config>(raw) }
    parsed
}
```

Here's what happens:
1. `failMapping { ... }` creates a reusable exception catcher that maps any `Exception` to your error type
2. `io { ... }` runs the block — if it throws, the exception is caught, mapped, and short-circuited
3. If it succeeds, the value is returned directly (not wrapped in `Res`)

The `io` scope is reusable — use it for every throwing call in the block. `CancellationException` is always rethrown to preserve coroutine structured concurrency.

> **Heads up:** The same `FailMappingRail` instance behaves differently depending on context. Inside `rail {}`, invoking it returns the unwrapped value and short-circuits on error. At the top level, the same call returns `Res<V, E>` instead. The compiler enforces this — a return-type mismatch is a compile error. See [Top-Level Usage](../README.md#top-level-usage-outside-rail--blocks) for details.

For one-off exception catching without mapping, use `Rail.attempt`:

```kotlin
val config: Res<Config, Exception> = Rail.attempt { loadConfigFile(path) }
```

## Working Across Error Domains

Real applications have functions from different domains with different error types. Use `errorMapping` to translate between them:

```kotlin
sealed class AppError {
    data class Network(val err: HttpError) : AppError()
    data class Database(val err: DbError) : AppError()
}

fun getDashboard(userId: Int): Res<Dashboard, AppError> = rail {
    val http = errorMapping<HttpError> { AppError.Network(it) }
    val db = errorMapping<DbError> { AppError.Database(it) }

    val user = fetchUser(userId).orFail(http)        // Res<User, HttpError> → User
    val settings = loadSettings(user.id).orFail(db)  // Res<Settings, DbError> → Settings
    Dashboard(user, settings)
}
```

`errorMapping` creates a reusable mapper. Pass it to `.orFail(mapping)` to unwrap Ok values or map and short-circuit Fail errors — it does **not** catch exceptions. If your callees can both throw and return `Res`, use `mapping` which handles both:

```kotlin
fun getDashboard(userId: Int): Res<Dashboard, AppError> = rail {
    val http = mapping<HttpError>(
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
| Throws exceptions (Java libs, IO, parsing) | `failMapping` |
| Returns `Res<V, D>` with a different error type | `errorMapping` |
| Does both (HTTP clients, DB drivers, gRPC) | `mapping` |

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

`zip` short-circuits on the first failure. For validation scenarios where you want **all** errors at once, use `zipOrAccumulate`:

```kotlin
val result: Res<User, List<String>> = zipOrAccumulate(
    { validateName(name) },
    { validateEmail(email) },
    { validateAge(age) },
) { validName, validEmail, validAge ->
    User(validName, validEmail, validAge)
}
// All three run. If name and age fail:
// Res.Fail(["Name too short", "Age must be positive"])
```

Both support arities 2 through 4.

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
    val http = failMapping { e -> "HTTP error: ${e.message}" }

    val user = http { userService.getUser(userId) }        // suspend
    val profile = http { profileService.getProfile(user.id) } // suspend
    Dashboard(user, profile)
}
```

All exception-catching scopes (`failMapping`, `mapping`, `Rail.attempt`) rethrow `CancellationException`, so structured concurrency is preserved.

## Database Transactions

Many database frameworks use exceptions to trigger rollback — Spring `@Transactional`, Exposed `transaction {}`, JOOQ, raw JDBC. The `rail {}` DSL uses an internal `FailException` for control flow. These mechanisms can interfere if the boundaries aren't set up correctly.

**The rule: keep `rail {}` outside the transaction boundary.** Use `failMapping` to wrap the transaction call. Exceptions roll back the transaction first, then `failMapping` catches and translates the re-thrown exception.

```kotlin
fun transferFunds(from: Int, to: Int, amount: BigDecimal): Res<Transfer, AppError> = rail {
    val db = failMapping { e -> AppError.Database("Transfer failed: ${e.message}") }

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
3. `failMapping` catches the re-thrown exception, maps to `AppError` →
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

    // Database write — failMapping wraps the transaction
    val db = failMapping { e -> AppError.Database(e.message ?: "DB error") }
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
    val db = failMapping { e -> AppError.Database(e.message ?: "DB error") }

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

// Validation functions return Res<V, String>
fun validateName(name: String): Res<String, String> = rail {
    ensure(name.isNotBlank()) { "Name cannot be blank" }
    ensure(name.length <= 100) { "Name too long" }
    name.trim()
}

fun validateEmail(email: String): Res<String, String> = rail {
    ensure(email.contains('@')) { "Invalid email" }
    email.lowercase().trim()
}

fun validatePassword(password: String): Res<String, String> = rail {
    ensure(password.length >= 8) { "Password too short" }
    ensure(password.any { it.isDigit() }) { "Must contain a digit" }
    password
}

// Registration flow
suspend fun registerUser(
    name: String,
    email: String,
    password: String,
): Res<User, RegistrationError> = rail {
    // Validate all fields, accumulate errors
    val (validName, validEmail, validPassword) = zipOrAccumulate(
        { validateName(name) },
        { validateEmail(email) },
        { validatePassword(password) },
    ) { n, e, p -> Triple(n, e, p) }
        .orFail { errors -> RegistrationError.Validation(errors) }

    // Save to database (may throw)
    val db = failMapping { e -> RegistrationError.Database("DB error: ${e.message}") }
    val user = db { userRepository.create(validName, validEmail, validPassword) }

    // Send welcome email (may throw)
    val mail = failMapping { e -> RegistrationError.Email("Email error: ${e.message}") }
    mail { emailService.sendWelcome(user.email) }

    user
}
```

This example demonstrates:
- `zipOrAccumulate` to collect all validation errors at once
- `orFail { }` to translate accumulated `List<String>` errors into the domain error type
- Separate `failMapping` scopes for database and email, each with their own error mapping
- The entire flow reads top-to-bottom as straight-line code despite having multiple failure modes

## Next Steps

- [API Reference](../README.md#api-reference) — complete signature tables for every public symbol
- [Design](design.md) — why Result-Kit is built the way it is
