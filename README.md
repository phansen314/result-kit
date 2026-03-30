# Result-Kit

Functional error handling for Kotlin. A `Res<V, E>` type and a `rail {}` DSL that lets you write straight-line code where errors short-circuit automatically — no exception swallowing, no nested `when` branches, no runtime dependencies.

```kotlin
// Without Result-Kit — nullable return, error details lost
fun processOrder(orderId: Int): Order? {
    val order = fetchOrder(orderId) ?: return null
    val validated = validateOrder(order) ?: return null
    val saved = saveOrder(validated) ?: return null
    return saved
}

// With Result-Kit — straight-line code, typed errors, automatic short-circuiting
fun processOrder(orderId: Int): Res<Order, AppError> = rail {
    val order = fetchOrder(orderId).orFail()
    val validated = validateOrder(order).orFail()
    saveOrder(validated).orFail()
}
```

Whether you're currently using nullable returns, `kotlin.Result`, exception-heavy code, or another result library, the `rail {}` DSL gives you typed errors with straight-line control flow and zero runtime dependencies.

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("tech.codingzen:result-kit:1.0.0")

    // Optional: KSP module for @TraceContext automatic traced-wrapper generation
    ksp("tech.codingzen:result-kit-ksp:1.0.0")
}
```

The KSP module requires the [KSP Gradle plugin](https://kotlinlang.org/docs/ksp-quickstart.html). The core `result-kit` module has zero runtime dependencies and can be used standalone.

All examples below assume this import:

```kotlin
import tech.codingzen.resultkit.*
```

## The Res Type

`Res<V, E>` is a result type with two states: `Ok` containing a success value of type `V`, or `Fail` containing an error of type `E`. Both type parameters are covariant, so `Res<String, IOException>` is assignable to `Res<Any, Throwable>`.

`Res` is a `@JvmInline value class`. The Ok path stores the raw value with zero allocation. The Fail path wraps the error in an internal sentinel (one allocation). There are no runtime dependencies beyond the Kotlin standard library.

### Creating Results

```kotlin
val success: Res<Int, Nothing> = Res.ok(42)
val failure: Res<Nothing, String> = Res.failure("something went wrong")
```

### Consuming Results

Because `Res` is a value class (not a sealed class), you cannot use exhaustive `when` expressions. Use `fold` for exhaustive handling:

```kotlin
val message: String = result.fold(
    onOk = { value -> "Got: $value" },
    onFail = { error -> "Error: $error" }
)
```

Other accessors:

```kotlin
result.getOrNull()                             // V? — returns null on Fail
result.errorOrNull()                           // E? — returns null on Ok
result.getOrElse { error -> defaultValue }     // V — computes default from error
result.getOrThrow()                            // V — throws E directly (requires E : Throwable)
result.getOrThrow { e -> RuntimeException(e) } // V — throws transformed error
result.errorOrThrow()                          // E — throws IllegalStateException on Ok
```

### Transforming Results

```kotlin
result.map { value -> value.toString() }       // Res<String, E> — transforms Ok, Fail passes through
result.mapError { error -> AppError(error) }   // Res<V, AppError> — transforms Fail, Ok passes through
result.recover { error -> fallbackValue }      // Res<V, Nothing> — converts Fail to Ok
result.orElse { error -> tryAlternative() }    // Res<V, F> — fallible recovery, may itself fail
result.flatMap { value -> nextOperation(it) }  // Res<U, E> — chains (V) -> Res<U, E>
result.flatten()                               // Res<V, E> — unwraps Res<Res<V, E>, E>
```

### Side Effects

```kotlin
result.onOk { value -> log("Success: $value") }   // runs action on Ok, returns self
result.onFail { error -> log("Error: $error") }    // runs action on Fail, returns self
```

Both return `this` for chaining: `result.onOk { log(it) }.onFail { report(it) }`.

### Checking State

```kotlin
result.isOk    // true if this is a success
result.isFail  // true if this is a failure
```

### Converting Nullable Values

```kotlin
val user: User? = findUser(id)
val result: Res<User, String> = user.toResOr { "User not found" }
```

`toResOr` converts non-null to Ok, null to Fail with a lazily-evaluated error.

### Conditional Failure

```kotlin
val result = Res.ok(age).toFailIf({ it < 0 }) { "Age cannot be negative: $it" }
```

`toFailIf` converts an Ok to Fail when the predicate matches. A Fail passes through unchanged.

### kotlin.Result Interop

```kotlin
val kotlinResult: Result<Int> = runCatching { riskyOperation() }
val res: Res<Int, Throwable> = kotlinResult.toRes()

val backToResult: Result<Int> = res.toResult()  // requires E : Throwable
```

## The Rail DSL

The `rail {}` block is the core of Result-Kit. It creates a scope where you can compose operations that return `Res`, with automatic short-circuiting on failure. The block returns `Res.Ok` on success or `Res.Fail` when any operation short-circuits.

```kotlin
fun fetchDashboard(userId: Int): Res<Dashboard, String> = rail {
    val user = fetchUser(userId).orFail()           // unwraps Ok or short-circuits
    val prefs = fetchPreferences(user.id).orFail()  // only runs if fetchUser succeeded
    Dashboard(user, prefs)                          // returned as Res.Ok
}
```

Because `rail` is an `inline` function, it works in both synchronous and suspend contexts. The compiler disambiguates based on the call site — no separate API needed:

```kotlin
suspend fun fetchDashboard(userId: Int): Res<Dashboard, String> = rail {
    val user = userService.getUser(userId).orFail()       // suspend call
    val prefs = prefsService.getPreferences(user.id).orFail()
    Dashboard(user, prefs)
}
```

### Short-Circuiting with orFail

`orFail()` unwraps an Ok value or short-circuits the enclosing `rail` with the Fail error:

```kotlin
val result = rail<Int, String> {
    val x = operation1().orFail()  // returns V or short-circuits
    val y = operation2().orFail()  // only runs if operation1 succeeded
    x + y
}
```

`orFail { mapError }` converts the error type before short-circuiting:

```kotlin
val result = rail<User, AppError> {
    val id = parseUserId(input).orFail { parseErr -> AppError.InvalidInput(parseErr) }
    val user = fetchUser(id).orFail { httpErr -> AppError.Network(httpErr) }
    user
}
```

### Validation with ensure and ensureNotNull

`ensure` short-circuits if a condition is false. `ensureNotNull` short-circuits if a value is null:

```kotlin
fun validateAge(age: Int): Res<Int, String> = rail {
    ensure(age >= 0) { "Age cannot be negative" }
    ensure(age <= 150) { "Age seems unrealistic" }
    age
}

fun parseUser(map: Map<String, Any?>): Res<User, String> = rail {
    val name = ensureNotNull(map["name"] as? String) { "Missing name" }
    val age = ensureNotNull(map["age"] as? Int) { "Missing age" }
    User(name, age)
}
```

### Explicit Failure

`fail(e)` short-circuits immediately with the given error:

```kotlin
fun divide(a: Int, b: Int): Res<Int, String> = rail {
    if (b == 0) fail("Division by zero")
    a / b
}
```

## Exception Handling — Which Rail Do I Use?

Inside `rail {}` blocks, you need to handle code that throws exceptions, code that returns `Res` with a different error type, or both. Result-Kit provides three rail types, each matching a specific call-site shape.

### failMapping — Your Code Throws Exceptions

Use `failMapping` when calling Java libraries, IO operations, serialization, or anything that communicates failure via exceptions rather than `Res`.

```kotlin
fun loadConfig(path: String): Res<Config, String> = rail {
    val io = failMapping { e -> "IO error: ${e.message}" }

    val raw = io { File(path).readText() }                 // catches IOException
    val parsed = io { Json.decodeFromString<Config>(raw) }  // catches SerializationException
    parsed
}
```

`failMapping` creates a reusable `FailMappingRail<E>`. Invoke it with a block — exceptions are caught, mapped to your error type, and short-circuited. `CancellationException` is always rethrown to preserve structured concurrency. The `FailMappingRail` is reusable across multiple calls within the same rail block.

### errorMapping — Your Code Returns Res with a Different Error Type

Use `errorMapping` when composing functions from different domains that each return `Res` with their own error type, and you need to unify them into a single error type.

```kotlin
fun getDashboard(userId: Int): Res<Dashboard, AppError> = rail {
    val http = errorMapping<HttpError> { AppError.Network(it) }
    val db = errorMapping<DbError> { AppError.Database(it) }

    val user = fetchUser(userId).orFail(http)        // Res<User, HttpError> → User
    val settings = loadSettings(user.id).orFail(db)  // Res<Settings, DbError> → Settings
    Dashboard(user, settings)
}
```

`errorMapping` creates a reusable `ErrorMappingRail<D, E>`. Pass it to `.orFail(mapping)` to unwrap the Ok value or map the error and short-circuit. It does not catch exceptions.

### mapping — Your Code Throws Exceptions AND Returns Res

Use `mapping` when calling functions that can both throw (timeouts, serialization errors) and return `Res` with a domain-specific error type (HTTP 404, validation failure). This is common with HTTP clients, database drivers, and gRPC stubs.

```kotlin
fun getDashboard(userId: Int): Res<Dashboard, AppError> = rail {
    val http = mapping<HttpError>(
        onError = { AppError.Network(it) },
        onException = { AppError.Unexpected(it) },
    )
    val user = http { userClient.getUser(userId) }          // catches exceptions AND unwraps Res
    val profile = http { profileClient.getProfile(user.id) }
    Dashboard(user, profile)
}
```

`mapping` creates a reusable `MappingRail<D, E>`. Invoke it with a block that returns `Res<V, D>` — exceptions are caught and mapped via `onException`, and the returned `Res` is unwrapped or mapped via `onError`. Both paths short-circuit the outer rail.

### Decision Summary

| Call site shape | Rail type | Block returns | What it catches |
|---|---|---|---|
| Throws exceptions, no `Res` | `failMapping` | `V` | Exceptions |
| Returns `Res<V, D>`, no exceptions | `errorMapping` | `Res` via `.orFail(mapping)` | Typed errors |
| Throws exceptions AND returns `Res<V, D>` | `mapping` | `Res<V, D>` | Both |

### Top-Level Usage (Outside rail {} Blocks)

All three rail types can also be used as top-level entry points, creating their own `Rail` scope and returning `Res<V, E>` instead of short-circuiting an outer rail:

```kotlin
// FailMappingRail as top-level entry point
val appRail = Rail.failMapping<AppError> { e -> AppError.Unexpected(e) }

fun fetchUser(id: Int): Res<User, AppError> = appRail {
    val user = apiClient.getUser(id)     // exceptions caught automatically
    ensure(user.isActive) { AppError.Inactive(id) }
    user
}

// ErrorMappingRail as top-level entry point
val http = Rail.errorMapping<HttpError, AppError> { AppError.Network(it) }

fun getUser(id: Int): Res<User, AppError> = http {
    fetchUser(id).orFail()
}

// MappingRail as top-level entry point
val httpRail = Rail.mapping<HttpError, AppError>(
    onError = { AppError.Network(it) },
    onException = { AppError.Unexpected(it) },
)

fun getUser(id: Int): Res<User, AppError> = httpRail {
    userClient.getUser(id)   // returns Res<User, HttpError>, may throw
}
```

The same instance behaves differently depending on context: top-level invoke returns `Res<V, E>`, while inside `rail {}` the member extension short-circuits and returns `V` directly. The Kotlin compiler enforces correct usage — a return-type mismatch is a compile error.

### Rail.attempt

`Rail.attempt` is a convenience for catching any exception without mapping it to a typed error:

```kotlin
val config: Res<Config, Exception> = Rail.attempt { loadConfigFile(path) }
```

It's equivalent to `Rail.failMapping { it }` followed by an invoke. Use it when you want `Res<V, Exception>` and don't need a typed error.

## Combining Results with zip and zipOrAccumulate

### zip (Fail-Fast)

`zip` combines 2–4 independent results, short-circuiting on the first failure:

```kotlin
val result: Res<Dashboard, String> = zip(
    { fetchUser(id) },
    { fetchSettings(id) },
    { fetchMetrics(id) },
) { user, settings, metrics ->
    Dashboard(user, settings, metrics)
}
```

### zipOrAccumulate (Error Accumulation)

`zipOrAccumulate` runs all operations and collects all errors into a `List<E>`:

```kotlin
val result: Res<User, List<String>> = zipOrAccumulate(
    { validateName(name) },
    { validateEmail(email) },
    { validateAge(age) },
) { validName, validEmail, validAge ->
    User(validName, validEmail, validAge)
}
// If name and age are invalid: Res.Fail(["Name too short", "Age must be positive"])
```

This is useful for form validation and similar cases where you want to report all errors at once rather than stopping at the first one. Note that the error type changes from `E` to `List<E>`.

Both `zip` and `zipOrAccumulate` support arities 2 through 4.

## Collection Extensions

Result-Kit provides extensions for working with collections of `Res` values.

### Querying

```kotlin
val results: List<Res<Int, String>> = listOf(Res.ok(1), Res.failure("err"), Res.ok(3))

results.allOk()   // false — true only if every element is Ok (true for empty lists)
results.anyOk()   // true  — true if at least one element is Ok
results.anyFail() // true  — true if at least one element is Fail
```

### Filtering

```kotlin
results.filterOk()   // List<Int> — [1, 3], discards Fail elements
results.filterFail() // List<String> — ["err"], discards Ok elements
```

### Combining

```kotlin
// Fail-fast: short-circuits on the first Fail
listOf(Res.ok(1), Res.ok(2), Res.ok(3)).combine()
// → Res.Ok([1, 2, 3])

listOf(Res.ok(1), Res.failure("err"), Res.ok(3)).combine()
// → Res.Fail("err")

// Partition: categorizes every element
listOf(Res.ok(1), Res.failure("a"), Res.ok(3), Res.failure("b")).partition()
// → Pair([1, 3], ["a", "b"])
```

### Fallible Iteration

```kotlin
// tryMap — maps each element through a failable transform, short-circuits on first Fail
val result: Res<List<User>, String> = userIds.tryMap { id -> fetchUser(id) }

// tryForEach — executes a failable action on each element, short-circuits on first Fail
val result: Res<Unit, String> = users.tryForEach { user -> saveUser(user) }
```

## Error Context Chains

When a `Res.Fail` propagates through several layers of code, it can be hard to know where the failure originated or what operations were in flight. Error context chains let you attach breadcrumb frames — a message and optional source location — to a failure as it unwinds, without changing the error type `E` or wrapping `Res` in another type.

Context is stored inside the internal `Failure` sentinel. The public `E` type is untouched. On the Ok path there is zero overhead — lambdas are only evaluated when the result is actually a failure.

### Attaching Context with .context()

```kotlin
fun loadUserProfile(id: Int): Res<Profile, AppError> =
    userRepo.findById(id)
        .context { "loading profile for user $id" }

fun handleRequest(userId: Int): Res<Response, AppError> =
    loadUserProfile(userId)
        .context { "handling request" }
        .context({ "request for user $userId" }, { SourceLocation("RequestHandler.kt", 42, "handleRequest") })
```

Both lambdas are only evaluated if the result is a Fail. Index 0 in the frame list is the innermost frame (first `.context()` call, closest to the error site).

### Attaching Context Inside rail {}

Use `withContext` to attach a frame to any failure that short-circuits through a block:

```kotlin
fun processOrder(id: Int): Res<Order, AppError> = rail {
    withContext("processing order $id") {
        val order = fetchOrder(id).orFail()
        val validated = validateOrder(order).orFail()
        saveOrder(validated).orFail()
    }
}
```

Use `orFailContext` to attach a frame at the point of short-circuit:

```kotlin
val user = fetchUser(id).orFailContext { "fetching user $id" }
val profile = loadProfile(user.id).orFailContext({ "loading profile" }) { SourceLocation("Service.kt", 55, "processUser") }
```

`orFailContext` takes a `() -> String` lambda — the message is only evaluated on the Fail path, so there is zero allocation on Ok. Named `orFailContext` (not `orFail`) to avoid overload ambiguity with `orFail(mapError)` when `E = String`.

### Extended fold

When you need the context frames at the consumption point, use the two-parameter `onFail`:

```kotlin
result.fold(
    onOk = { value -> render(value) },
    onFail = { error, frames -> renderError(error, frames) },
)
```

### Reading Context

```kotlin
val res: Res<V, E> = ...

res.contextChain()    // List<Frame> — empty on Ok, frames ordered innermost-first
res.renderContext()   // Multi-line string: "Error: ...\n  0: frame0\n     at file:line\n  1: ..."
res.contextSummary()  // Compact: "frame0 → frame1 → error.toString()"
res.contextMap()      // Map<String, Any?> for structured/JSON logging
```

Searching for typed attachments:

```kotlin
// Attach structured data to a frame
result.context { "processing $id" }
// or with attachment:
// result.contextFrame { Frame("processing $id", attachment = RequestMetadata(id, timestamp)) }

val meta: RequestMetadata? = result.contextChain().findAttachment<RequestMetadata>()
```

### @TraceContext — KSP Code Generation

The KSP module generates traced decorator classes automatically. Annotate an interface with `@TraceContext` and the processor generates `{Name}Traced` that wraps every `Res`-returning method with `.context(message, location)`:

```kotlin
@TraceContext
interface UserRepository {
    fun findById(id: Int): Res<User, DbError>
    suspend fun save(user: User): Res<Unit, DbError>
    fun count(): Int   // not wrapped — not Res-returning
}
// KSP generates UserRepositoryTraced
```

The generated class:

```kotlin
class UserRepositoryTraced(
    private val delegate: UserRepository,
) : UserRepository {
    override fun findById(id: Int): Res<User, DbError> =
        delegate.findById(id)
            .context(
                { "UserRepository.findById(id)" },
                { SourceLocation("UserRepository.kt", 3, "findById") },
            )

    override suspend fun save(user: User): Res<Unit, DbError> =
        delegate.save(user)
            .context(
                { "UserRepository.save(user)" },
                { SourceLocation("UserRepository.kt", 4, "save") },
            )

    override fun count(): Int = delegate.count()
}
```

Customising generation:

```kotlin
@TraceContext(suffix = "Instrumented")       // generates UserRepositoryInstrumented
interface UserRepository { ... }

@TraceContext
interface AuthService {
    // Default: names only — "AuthService.login(username, password)"
    fun login(username: String, password: String): Res<Token, AuthError>

    // @TraceInclude opts a single param's value in
    fun findById(@TraceInclude id: Int): Res<User, AuthError>
    // generated: "AuthService.findById(id=$id)"

    @TraceMessage("authenticating user {username}")  // custom template, full control
    fun authenticate(username: String, password: String): Res<Token, AuthError>
}
```

Parameter values are **excluded by default** — the message shows param names only, which is sufficient to identify the overload without leaking data. `@TraceInclude` opts a specific param's value in. `@TraceMessage` replaces the auto-generated message entirely; `{paramName}` placeholders are replaced with the runtime value.

## Common Pitfalls

### Do not use raw try/catch inside rail {} blocks

The `rail {}` DSL uses an internal exception (`FailException`, a direct `Throwable` subclass — not `Exception`) for control flow. If you catch `Throwable` inside a `rail {}` block, you will silently swallow this control-flow exception and break the railway:

```kotlin
// BAD — catches FailException, breaking short-circuit behavior
val result = rail<Int, String> {
    val value = try {
        someResult.orFail()  // throws FailException on Fail
    } catch (e: Throwable) {  // catches FailException!
        0  // railway is broken — error is silently lost
    }
    value
}
```

Use `failMapping` or `Rail.attempt` instead:

```kotlin
// GOOD — failMapping catches and maps exceptions safely
val result = rail<Int, String> {
    val io = failMapping { e -> "IO error: ${e.message}" }
    io { riskyOperation() }
}

// GOOD — Rail.attempt for one-off exception catching
val result: Res<Config, Exception> = Rail.attempt { loadConfigFile(path) }
```

### Database transactions and exception-based rollback

Many database frameworks (Spring `@Transactional`, Exposed `transaction {}`, JOOQ, raw JDBC) use exceptions to trigger rollback. The `rail {}` DSL uses `FailException` (a `Throwable` subclass) for control flow. These two mechanisms can interfere with each other if the boundaries aren't set up correctly.

**The rule: keep `rail {}` outside the transaction boundary.** Use `failMapping` to catch exceptions that escape the transaction after rollback has already occurred.

```kotlin
// GOOD — rail wraps the transaction, failMapping catches after rollback
fun transferFunds(from: Int, to: Int, amount: BigDecimal): Res<Transfer, AppError> = rail {
    val db = failMapping { e -> AppError.Database("Transfer failed: ${e.message}") }

    db {
        transaction {
            // All DB ops inside the transaction — exceptions trigger rollback normally
            val sender = accountRepo.findById(from) ?: throw NotFoundException("Account $from")
            require(sender.balance >= amount) { "Insufficient funds" }
            accountRepo.debit(from, amount)
            accountRepo.credit(to, amount)
            Transfer(from, to, amount)
        }
        // Transaction committed here. If any exception was thrown above,
        // the transaction rolled back and the exception propagates to failMapping.
    }
}
```

**Why this ordering matters:**

1. `accountRepo.debit()` throws `SQLException` →
2. `transaction {}` catches it, rolls back, re-throws →
3. `failMapping` catches the re-thrown exception, maps it to `AppError`, short-circuits the rail →
4. `rail {}` catches `FailException`, returns `Res.Fail(AppError.Database(...))`

The transaction framework sees the exception first and rolls back cleanly. Then `failMapping` translates it to a typed error.

**BAD — `rail {}` inside the transaction:**

```kotlin
// BAD — FailException may confuse the transaction manager
fun transferFunds(from: Int, to: Int, amount: BigDecimal): Res<Transfer, AppError> {
    return transaction {
        rail {
            val sender = fetchAccount(from).orFail()  // throws FailException on Fail!
            // FailException extends Throwable — some transaction frameworks will
            // catch it, trigger rollback, and either swallow it or wrap it
        }
    }
}
```

If the transaction framework catches `Throwable` for rollback (Spring does this by default for `Error` subclasses), `FailException` may trigger an unintended rollback or get wrapped in a framework-specific exception, losing the typed error.

**Pattern: validate first, transact second**

For operations that need both validation and database writes, validate outside the transaction and only enter the transaction for writes:

```kotlin
fun createOrder(request: OrderRequest): Res<Order, AppError> = rail {
    // Validation — no DB, no transaction needed
    val items = request.items.tryMap { validateItem(it) }
        .orFail { AppError.Validation(it) }
    ensure(items.isNotEmpty()) { AppError.Validation("Order must have at least one item") }

    // Database — inside failMapping, which wraps the transaction
    val db = failMapping { e -> AppError.Database(e.message ?: "DB error") }
    db {
        transaction {
            val order = orderRepo.create(request.customerId)
            items.forEach { item -> orderItemRepo.create(order.id, item) }
            inventoryService.reserve(order.id, items)  // throws on insufficient stock
            order
        }
    }
}
```

**Pattern: transaction returns `Res`, evaluate outside**

If your transaction logic itself produces a `Res` (e.g., the repository returns `Res`), evaluate it *after* the transaction commits:

```kotlin
fun deactivateUser(userId: Int): Res<Unit, AppError> = rail {
    val db = failMapping { e -> AppError.Database(e.message ?: "DB error") }

    // Transaction returns Res — don't orFail() inside the transaction
    val result: Res<Unit, AppError> = db {
        transaction {
            val user = userRepo.findById(userId)
                ?: return@transaction Res.failure(AppError.NotFound("User $userId"))
            userRepo.deactivate(user.id)
            emailService.sendDeactivationNotice(user.email)
            Res.ok(Unit)
        }
    }

    // Evaluate the Res after the transaction has committed/rolled back
    result.orFail()
}
```

### Static analysis with detekt

If you use [detekt](https://detekt.dev), the built-in `TooGenericExceptionCaught` rule (active by default) will flag `catch(Throwable)` and `catch(Exception)` patterns, catching this footgun at compile time:

```kotlin
plugins {
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}
```

## API Reference

### Res<V, E> — Result Type

| Signature | Description |
|---|---|
| `Res.ok(value: V): Res<V, Nothing>` | Creates an Ok result |
| `Res.failure(error: E): Res<Nothing, E>` | Creates a Fail result |
| `isOk: Boolean` | True if Ok |
| `isFail: Boolean` | True if Fail |

### Res Extensions — Accessors

| Signature | Description |
|---|---|
| `getOrNull(): V?` | Ok value or null |
| `errorOrNull(): E?` | Fail error or null |
| `getOrElse(default: (E) -> V): V` | Ok value or computed default |
| `getOrThrow(): V` | Ok value or throws E (requires E : Throwable) |
| `getOrThrow(transform: (E) -> Throwable): V` | Ok value or throws transformed error |
| `errorOrThrow(): E` | Fail error or throws IllegalStateException |

### Res Extensions — Transforms

| Signature | Description |
|---|---|
| `fold(onOk: (V) -> T, onFail: (E) -> T): T` | Exhaustive match on both branches |
| `fold(onOk: (V) -> T, onFail: (E, List<Frame>) -> T): T` | Exhaustive match with context frames (disambiguated by 2-param onFail) |
| `map(transform: (V) -> U): Res<U, E>` | Transforms Ok value, Fail passes through |
| `mapError(transform: (E) -> F): Res<V, F>` | Transforms Fail error, Ok passes through |
| `recover(transform: (E) -> V): Res<V, Nothing>` | Converts Fail to Ok (infallible) |
| `orElse(transform: (E) -> Res<V, F>): Res<V, F>` | Fallible recovery, may fail with new type |
| `flatMap(transform: (V) -> Res<U, E>): Res<U, E>` | Chains failable computation (prefer rail + orFail) |
| `flatten(): Res<V, E>` | Unwraps `Res<Res<V, E>, E>` |

### Res Extensions — Side Effects

| Signature | Description |
|---|---|
| `onOk(action: (V) -> Unit): Res<V, E>` | Runs action on Ok, returns self |
| `onFail(action: (E) -> Unit): Res<V, E>` | Runs action on Fail, returns self |

### Res Extensions — Factories and Conditionals

| Signature | Description |
|---|---|
| `V?.toResOr(error: () -> E): Res<V, E>` | Non-null to Ok, null to Fail |
| `toFailIf(predicate: (V) -> Boolean, transform: (V) -> E): Res<V, E>` | Ok to Fail if predicate matches |

### Res Extensions — Interop

| Signature | Description |
|---|---|
| `Result<V>.toRes(): Res<V, Throwable>` | kotlin.Result to Res |
| `Res<V, E : Throwable>.toResult(): Result<V>` | Res to kotlin.Result |

### Rail DSL — Entry Points

| Signature | Description |
|---|---|
| `rail(block: Rail<E>.() -> V): Res<V, E>` | Entry point, returns Ok on success or Fail on short-circuit |
| `Rail.attempt(block: Rail<Exception>.() -> V): Res<V, Exception>` | Catches any exception as the error |

### Rail Companion — Factory Functions

| Signature | Description |
|---|---|
| `Rail.failMapping(mapError: (Exception) -> E): FailMappingRail<E>` | Creates a top-level exception-catching rail |
| `Rail.errorMapping(mapError: (D) -> E): ErrorMappingRail<D, E>` | Creates a top-level typed-error-mapping rail |
| `Rail.mapping(onError: (D) -> E, onException: (Exception) -> E): MappingRail<D, E>` | Creates a top-level combined rail |

### Rail<E> Scope — Available Inside rail {} Blocks

| Signature | Description |
|---|---|
| `fail(e: E): Nothing` | Short-circuit with error |
| `Res<V, E>.orFail(): V` | Unwrap Ok or short-circuit with Fail error |
| `Res<V, F>.orFail(mapError: (F) -> E): V` | Unwrap Ok or short-circuit with mapped error |
| `Res<V, F>.orFail(mapping: ErrorMappingRail<F, E>): V` | Unwrap Ok or short-circuit with mapped error via reusable mapping |
| `ensure(condition: Boolean, error: () -> E)` | Short-circuit if condition is false |
| `ensureNotNull(value: V?, error: () -> E): V` | Short-circuit if value is null |
| `Res<V, E>.orFailContext(context: () -> String): V` | Unwrap Ok or short-circuit; attaches context frame to failure (lazy) |
| `Res<V, E>.orFailContext(context: () -> String, location: () -> SourceLocation): V` | Unwrap Ok or short-circuit; attaches frame with source location (lazy) |
| `withContext(message: String, block: Rail<E>.() -> V): V` | Run block; appends context frame to any failure that short-circuits |
| `withContext(message: String, location: () -> SourceLocation, block: Rail<E>.() -> V): V` | Same with source location |
| `failMapping(mapError: (Exception) -> E): FailMappingRail<E>` | Create exception-catching scope |
| `errorMapping(mapError: (D) -> E): ErrorMappingRail<D, E>` | Create typed-error-mapping scope |
| `mapping(onError: (D) -> E, onException: (Exception) -> E): MappingRail<D, E>` | Create combined scope |

### FailMappingRail<E>

Catches exceptions thrown inside a block and maps them to a typed error. Created via `failMapping` inside `rail {}`, via `Rail.failMapping` at the top level, or directly via `FailMappingRail<E> { e -> ... }`.

| Context | Invoke signature | Returns |
|---|---|---|
| Inside `rail {}` (member extension) | `invoke(block: Rail<E>.() -> V): V` | Unwrapped value, short-circuits outer rail on exception |
| Top-level | `invoke(block: Rail<E>.() -> V): Res<V, E>` | `Res<V, E>` |

### ErrorMappingRail<D, E>

Maps typed errors from domain `D` to domain `E`. Created via `errorMapping` inside `rail {}` or via `Rail.errorMapping` at the top level. Does not catch exceptions.

| Context | Invoke signature | Returns |
|---|---|---|
| Inside `rail {}` (member extension) | `invoke(res: Res<V, D>): V` | Unwrapped value, short-circuits outer rail on error |
| Top-level | `invoke(block: Rail<D>.() -> V): Res<V, E>` | `Res<V, E>` |

> **Preferred pattern:** Inside `rail {}` blocks, use `.orFail(mapping)` instead of the member extension `invoke(res)` — it reads consistently with `orFail()` and `orFail { }`. The `invoke` member extension still works but `.orFail(mapping)` is the primary pattern.

### MappingRail<D, E>

Catches exceptions AND maps typed errors. Created via `mapping` inside `rail {}` or via `Rail.mapping` at the top level.

| Context | Invoke signature | Returns |
|---|---|---|
| Inside `rail {}` (member extension) | `invoke(block: Rail<E>.() -> Res<V, D>): V` | Unwrapped value, short-circuits outer rail |
| Top-level | `invoke(block: Rail<E>.() -> Res<V, D>): Res<V, E>` | `Res<V, E>` |

### Zip Functions

| Signature | Description |
|---|---|
| `zip(block1: () -> Res<V1, E>, block2: () -> Res<V2, E>, transform: (V1, V2) -> R): Res<R, E>` | Fail-fast combine 2 results |
| `zip(block1, block2, block3, transform): Res<R, E>` | Fail-fast combine 3 results |
| `zip(block1, block2, block3, block4, transform): Res<R, E>` | Fail-fast combine 4 results |
| `zipOrAccumulate(block1: () -> Res<V1, E>, block2: () -> Res<V2, E>, transform: (V1, V2) -> R): Res<R, List<E>>` | Accumulate errors from 2 results |
| `zipOrAccumulate(block1, block2, block3, transform): Res<R, List<E>>` | Accumulate errors from 3 results |
| `zipOrAccumulate(block1, block2, block3, block4, transform): Res<R, List<E>>` | Accumulate errors from 4 results |

### Collection Extensions — Iterable<Res<V, E>>

| Signature | Description |
|---|---|
| `allOk(): Boolean` | True if every element is Ok (true for empty) |
| `anyOk(): Boolean` | True if at least one Ok (false for empty) |
| `anyFail(): Boolean` | True if at least one Fail (false for empty) |
| `filterOk(): List<V>` | Collects Ok values |
| `filterFail(): List<E>` | Collects Fail errors |
| `combine(): Res<List<V>, E>` | Fail-fast collect into list |
| `partition(): Pair<List<V>, List<E>>` | Splits into Ok values and Fail errors |

### Collection Extensions — Iterable<V>

| Signature | Description |
|---|---|
| `tryMap(transform: (V) -> Res<U, E>): Res<List<U>, E>` | Fail-fast map through failable transform |
| `tryForEach(action: (V) -> Res<*, E>): Res<Unit, E>` | Fail-fast side-effecting iteration |

### Error Context Chain — Types

| Type | Description |
|---|---|
| `Frame(message: String, attachment: Any? = null, location: SourceLocation? = null)` | One breadcrumb frame attached to a failure |
| `SourceLocation(file: String, line: Int, function: String? = null)` | Source position; `toString()` renders as `"file:line in function"` |

### Error Context Chain — Res Extensions

| Signature | Description |
|---|---|
| `Res<V, E>.context(message: () -> String): Res<V, E>` | Appends frame on Fail, no-op on Ok; lambda only evaluated on Fail |
| `Res<V, E>.context(message: () -> String, location: () -> SourceLocation): Res<V, E>` | Same with source location |
| `Res<V, E>.contextChain(): List<Frame>` | Returns frames (index 0 = innermost); empty list on Ok |
| `Res<V, E>.renderContext(): String` | Multi-line: error then numbered frames with locations |
| `Res<V, E>.contextSummary(): String` | Compact: `"frame0 → frame1 → error.toString()"` |
| `Res<V, E>.contextMap(): Map<String, Any?>` | Structured map for JSON logging |
| `List<Frame>.findAttachment<T>(): T?` | First frame attachment that is an instance of `T` |

### @TraceContext Annotations (result-kit-ksp)

| Annotation | Target | Description |
|---|---|---|
| `@TraceContext(suffix: String = "Traced")` | Interface | Generates `{Name}{suffix}` decorator wrapping all Res-returning methods |
| `@TraceMessage(value: String)` | Method | Replaces auto-generated message; `{paramName}` → `$paramName` at runtime |
| `@TraceInclude` | Parameter | Opts a parameter's value into the auto-generated message (values are excluded by default) |

### Annotations

| Annotation | Description |
|---|---|
| `@RailDsl` | `@DslMarker` annotation applied to `Rail`. Prevents implicit access to an outer `Rail` receiver from within a nested `rail {}` block. If you see a compiler error about implicit receiver access being restricted, this is why — use explicit qualification or restructure the nesting. |

### Exceptions

| Type | Description |
|---|---|
| `ErrorMapperException` | Thrown when a `mapError` lambda itself throws. Contains `originalException` (the error being mapped) and the mapper's exception as `cause`. Both are attached via `addSuppressed`. |

## Design

### Inline Value Class

`Res` is a `@JvmInline value class` wrapping `Any?`. Ok stores the raw value directly (zero allocation). Fail wraps the error in an internal `Failure` sentinel (one allocation). Variant discrimination is via `instanceof Failure`. Nested `Res<Res<...>, ...>` is safe because the inner `Res` gets boxed when stored as `Any?` in generic contexts.

### Control Flow via Exceptions

The DSL uses an internal `FailException` for short-circuit control flow. `FailException` extends `Throwable` directly — not `Exception` — so that `catch(Exception)` blocks inside `failMapping` don't accidentally intercept it. The exception is caught at the `rail {}` boundary and converted to `Res.Fail`. Stack trace filling is skipped for performance since the exception is purely for control flow.

### Scope Isolation

Each `rail {}` call creates a new `Rail` instance. `FailException` carries a reference to its originating scope. The `rail {}` boundary only catches exceptions from its own scope — nested rail failures from inner scopes propagate correctly. This prevents inner `fail()` calls from being silently caught by outer rails.

### Typed Errors

`Res<V, E>` is parameterized over both the value type and the error type. Errors can be strings, enums, sealed classes, data classes, or any other type. The library imposes no constraints on error representation.

### Single Entry Point

Both synchronous and suspend code use the same `rail {}` function. Because `rail` is `inline`, the compiler resolves suspend calls within the lambda based on call-site context, eliminating the need for a separate suspend variant.

### CancellationException Handling

All exception-catching paths (`failMapping`, `mapping`, `Rail.attempt`) rethrow `CancellationException` to preserve structured concurrency. This uses `kotlin.coroutines.cancellation.CancellationException` (the stdlib type) to avoid a runtime dependency on kotlinx-coroutines.

### Error Context Chains

Context frames are stored in the internal `Failure` sentinel as `List<Frame>`. The public error type `E` is unchanged — frames are an orthogonal concern. On the Ok path, `.context()` performs a single `instanceof` check and returns `this` immediately; lambdas are never evaluated. On the Fail path, a new `Failure` is allocated with the frame appended.

Frame ordering is **append**: index 0 is the innermost frame (the first `.context()` call, closest to the error site). Outer wrappers append at the end. This matches the natural reading order when you traverse the frame list from index 0 outward.

`FailException` also carries frames so they survive the throw/catch journey through `rail {}` boundaries without loss. All `Failure`-constructing sites (rail boundaries, `mapError`, mapping scopes) transfer frames from the incoming exception or existing `Failure`.

### No Runtime Dependencies

Result-Kit has zero runtime dependencies beyond the Kotlin standard library.

## Building

```bash
./gradlew build         # Build the project
./gradlew test          # Run tests
./gradlew publishToMavenLocal  # Publish to local Maven repository
```

## Requirements

- Kotlin 1.9.25 or higher
- Gradle 8.5 or higher
- JVM 1.8 or higher (compiled for Java 8 compatibility)

## License

MIT License

## Contributing

Contributions are welcome. Please feel free to submit issues and pull requests on [GitHub](https://github.com/phansen314/result-kit).
