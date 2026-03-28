# Result-Kit

A functional error handling library for Kotlin with Railway-Oriented Programming support. Provides `Res<V, E>`, a zero-allocation inline value class for typed errors, and the `rail {}` DSL for composing operations that can fail with short-circuit semantics.

## Overview

Result-Kit brings Railway-Oriented Programming to Kotlin, allowing you to compose operations that can fail in a type-safe, functional way. Instead of throwing exceptions or using nullable types, operations return `Res<V, E>` values that are either Ok (containing a value `V`) or Fail (containing an error `E`). The `rail {}` DSL provides short-circuit semantics, allowing failed operations to automatically propagate through your code without manual error checking at each step.

## Features

- **Type-safe result type** — `@JvmInline value class Res<V, E>` with zero-allocation Ok path, covariant in both type parameters
- **DSL scope** — `rail {}` blocks with `Rail<E>` for composing failable operations
- **Coroutine support** — Same `rail {}` function works in both sync and suspend contexts (compiler disambiguates)
- **Short-circuit operations** — `orFail()` unwrapping, `ensure()`, `ensureNotNull()`
- **Safe exception handling** — `Rail.attempt {}` and `failMapping {} { block }` for converting exceptions to typed errors
- **Clean composition** — Chain operations without manual error checking

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("tech.codingzen:result-kit:1.0.0")
}
```

## Usage

### Basic DSL

The `rail {}` block creates a `Rail<E>` where you can compose operations that return `Res<V, E>`:

```kotlin
import tech.codingzen.resultkit.*

data class User(val id: Int, val name: String)

fun fetchUser(id: Int): Res<User, String> = rail {
    if (id < 0) fail("User ID must be positive")
    User(id, "User $id")
}

fun getUserName(id: Int): Res<String, String> = rail {
    val user = fetchUser(id).orFail()
    user.name
}

// Usage
val result = getUserName(123)
result.fold(
    onOk = { println("User: $it") },
    onFail = { println("Error: $it") }
)
```

### Short-Circuit with orFail

Use `.orFail()` to unwrap successful results or short-circuit on failure:

```kotlin
val result = rail<Int, String> {
    val x = operation1().orFail()  // Returns the value or short-circuits
    val y = operation2().orFail()  // Only runs if operation1 succeeded
    val z = operation3().orFail()  // Only runs if operation2 succeeded
    x + y + z
}
```

Use `.orFail { mapError }` to convert between error types:

```kotlin
val result = rail<User, AppError> {
    val id = parseUserId(input).orFail { parseErr -> AppError.InvalidInput(parseErr) }
    val user = fetchUser(id).orFail { httpErr -> AppError.Network(httpErr) }
    user
}
```

### Validation with ensure / ensureNotNull

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

### Exception Handling with Rail.attempt

Use `Rail.attempt {}` to convert throwing code into `Res<V, Exception>`:

```kotlin
val config: Res<Config, Exception> = Rail.attempt { loadConfigFile(path) }
```

You can chain `.mapError {}` if you need a typed error:

```kotlin
val config: Res<Config, String> = Rail.attempt {
    loadConfigFile(path)
}.mapError { e -> "Failed to load config: ${e.message}" }
```

### Exception Handling with failMapping

Use `failMapping {}` inside `rail {}` to create a reusable exception-catching scope. Invoke it with a block — on exception, the error is mapped and short-circuited:

```kotlin
fun loadAppConfig(path: String): Res<AppConfig, String> = rail {
    val io = failMapping { e -> "IO error: ${e.message}" }

    val raw = io { File(path).readText() }
    val parsed = io { Json.decodeFromString<ConfigData>(raw) }

    val validated = validateConfig(parsed).orFail()
    AppConfig(validated)
}
```

`failMapping` creates a reusable `FailMappingRail` — invoke it multiple times with the same error mapping.

### Exception-Catching Rail with FailMappingRail

Use `Rail.failMapping {}` to create a reusable entry point that catches all exceptions and maps them to your error type:

```kotlin
val appRail = Rail.failMapping { e -> AppError.Unexpected(e) }

fun fetchUser(id: Int): Res<User, AppError> = appRail {
    val user = apiClient.getUser(id)  // exceptions caught automatically
    ensure(user.isActive) { AppError.Inactive(id) }
    user
}

fun saveUser(user: User): Res<Unit, AppError> = appRail {
    repository.save(user)  // exceptions caught automatically
}
```

This combines the full `Rail` DSL (orFail, fail, ensure, etc.) with automatic exception catching. Railway operations short-circuit as normal; any uncaught exception is mapped to a Fail result via the provided mapper.

### Coroutine Support

The same `rail {}` function works in suspend contexts — no separate API needed. Because `rail` is `inline`, the compiler allows suspend calls within the block based on call-site context:

```kotlin
suspend fun fetchUserData(userId: Int): Res<UserData, String> = rail {
    val http = failMapping { e -> "HTTP error: ${e.message}" }

    val user = http { userService.getUser(userId) }
    val profile = http { profileService.getProfile(user.id) }
    val preferences = http { preferencesService.get(user.id) }

    UserData(user, profile, preferences)
}
```

## API Reference

### Res<V, E>

Inline value class representing either a successful value or an error. Covariant in both `V` and `E`. Uses a tagged union internally — Ok values are stored directly (zero allocation), Fail values are wrapped in an internal sentinel.

**Checking the variant:**
- `isOk: Boolean` / `isFail: Boolean` — check without unwrapping
- `fold(onOk: (V) -> T, onFail: (E) -> T): T` — exhaustive handling (use instead of `when`)

**Unwrapping:**
- `getOrNull(): V?` — Ok value or `null`
- `errorOrNull(): E?` — Fail error or `null`
- `getOrThrow(): V` — Ok value or throws error (requires `E : Throwable`)
- `getOrThrow(transform: (E) -> Throwable): V` — Ok value or throws transformed error
- `errorOrThrow(): E` — Fail error or throws `IllegalStateException`
- `getOrElse(default: (E) -> V): V` — Ok value or computed default

**Transforming:**
- `map(transform: (V) -> U): Res<U, E>` — transform the success value
- `mapError(transform: (E) -> F): Res<V, F>` — transform the error value
- `flatMap(transform: (V) -> Res<U, E>): Res<U, E>` — chain a failable operation on Ok (escape hatch for outside `rail {}`; prefer `rail {}` + `orFail()`)
- `recover(transform: (E) -> V): Res<V, Nothing>` — convert Fail to Ok
- `orElse(transform: (E) -> Res<V, F>): Res<V, F>` — fallback to another Res on Fail

**Side effects:**
- `onOk(action: (V) -> Unit): Res<V, E>` — side-effect on success, returns self
- `onFail(action: (E) -> Unit): Res<V, E>` — side-effect on failure, returns self

**Interop:**
- `Result<V>.toRes(): Res<V, Throwable>` — convert kotlin.Result to Res
- `Res<V, E : Throwable>.toResult(): Result<V>` — convert Res to kotlin.Result

**Factory functions (on `Res.Companion`):**
- `Res.ok(value): Res<V, Nothing>` — create a successful result
- `Res.failure(error): Res<Nothing, E>` — create a failed result

### rail {}

Entry point for railway-oriented error handling.

```kotlin
inline fun <V, E> rail(block: Rail<E>.() -> V): Res<V, E>
```

Executes the block with a `Rail<E>` receiver, giving access to `fail()`, `orFail()`, `ensure()`, and other short-circuit operations inside the block. Returns Ok on success or Fail on short-circuit. Works in both sync and suspend contexts since the function is `inline`.

### Rail<E>

DSL scope — the receiver type inside `rail {}` blocks. Provides short-circuit operations:

- `fail(e: E): Nothing` — short-circuit with an error
- `Res<V, E>.orFail(): V` — unwrap Ok or short-circuit with the Fail error
- `Res<V, F>.orFail(mapError: (F) -> E): V` — unwrap Ok or short-circuit with mapped error
- `ensure(condition: Boolean, error: () -> E)` — short-circuit if condition is false
- `ensureNotNull(value: V?, error: () -> E): V` — short-circuit if value is null
- `failMapping(mapError: (Exception) -> E): FailMappingRail<E>` — create a reusable exception-catching scope with error mapping. Invoke it with a block: `io { riskyOp() }` — returns `V` directly, short-circuits on exception.

**Companion functions:**
- `Rail.attempt { block }: Res<V, Exception>` — catch exceptions with identity mapping
- `Rail.failMapping { e -> myError }: FailMappingRail<E>` — create a reusable exception-catching scope for use outside `rail {}` blocks

### FailMappingRail<E>

Reusable scope for catching exceptions and mapping them to a typed error. Two modes of operation:

**Top-level** — create via `Rail.failMapping`, invoke to get `Res<V, E>`:
```kotlin
val appRail = Rail.failMapping { e -> AppError.Unexpected(e) }
val r: Res<User, AppError> = appRail { fetchUser(id) }
```

**Inside `rail {}` blocks** — create via `failMapping`, invoke to get unwrapped `V` (short-circuits on exception):
```kotlin
val result = rail<Int, String> {
    val io = failMapping { e -> "IO: ${e.message}" }
    val data = io { loadData() }  // returns V, short-circuits on exception
    process(data)
}
```

Kotlin member extension resolution ensures the correct behavior is selected automatically.

## Common Pitfalls

### Do not use raw `try/catch` inside `rail {}` blocks

The `rail {}` DSL uses an internal exception (`FailException`, a direct `Throwable` subclass — **not** an `Exception`) for control flow.

- `catch(e: Throwable)` inside `rail {}` will silently swallow `FailException`, breaking the railway
- `catch(e: Exception)` inside `failMapping { } { }` blocks will intercept exceptions before the mapping can catch and translate them

```kotlin
// BAD — catch(Throwable) swallows FailException, breaking short-circuit behavior
val result = rail<Int, String> {
    val value = try {
        someResult.orFail()  // throws FailException on Fail
    } catch (e: Throwable) {  // catches FailException!
        0  // railway is broken — orFail error is silently lost
    }
    value
}
```

Instead, use `Rail.attempt {}` or `failMapping {} { block }`:

```kotlin
// GOOD — Rail.attempt {} safely handles exceptions
val config: Res<Config, Exception> = Rail.attempt { loadConfigFile(path) }

// GOOD — failMapping {} { block } maps exceptions to your error type
val result = rail<Int, String> {
    val http = failMapping { e -> "HTTP error: ${e.message}" }
    val user = http { fetchUser(id) }
    val prefs = http { fetchPrefs(id) }
    process(user, prefs)
}
```

### Static analysis with detekt

If you use [detekt](https://detekt.dev) in your project, the built-in `TooGenericExceptionCaught` rule (active by default) will flag `catch(Throwable)` and `catch(Exception)` patterns. This catches the footgun above at compile time.

```kotlin
// build.gradle.kts
plugins {
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}
```

## Use Cases

### API Request Handling

Compose multiple API calls with automatic error propagation:

```kotlin
suspend fun fetchDashboardData(userId: Int): Res<Dashboard, String> = rail {
    val api = failMapping { e -> "API error: ${e.message}" }

    val user = api { apiClient.getUser(userId) }
    val metrics = api { apiClient.getMetrics(user.id) }

    Dashboard(user, metrics)
}
```

### Configuration Loading

Load and validate configuration with descriptive error messages:

```kotlin
fun loadAppConfig(): Res<AppConfig, String> = rail {
    val io = failMapping { e -> "IO error: ${e.message}" }

    val raw = io { File(CONFIG_PATH).readText() }
    val parsed = io { Json.decodeFromString<ConfigData>(raw) }
    val validated = validateConfig(parsed).orFail()

    AppConfig(validated)
}

fun validateConfig(data: ConfigData): Res<ConfigData, String> = rail {
    ensure(data.port in 1..65535) { "Port must be between 1 and 65535" }
    ensure(data.host.isNotBlank()) { "Host cannot be blank" }
    ensure(data.timeout > 0) { "Timeout must be positive" }
    data
}
```

### Database Queries

Compose multiple read operations with shared error mapping:

```kotlin
suspend fun getUserProfile(userId: Int): Res<UserProfile, String> = rail {
    val db = failMapping { e -> "DB error: ${e.message}" }

    val user = db { userRepo.findById(userId) }
    val prefs = db { prefsRepo.findByUserId(userId) }
    val avatar = db { avatarRepo.findByUserId(userId) }

    UserProfile(user, prefs, avatar)
}
```

### Input Validation Pipeline

Chain multiple validation steps:

```kotlin
fun registerUser(
    email: String,
    password: String,
    age: Int
): Res<User, String> = rail {
    val validEmail = validateEmail(email).orFail()
    val validPassword = validatePassword(password).orFail()
    ensure(age in 0..150) { "Age out of range" }

    val repo = failMapping { e -> "DB error: ${e.message}" }
    repo { userRepository.create(validEmail, validPassword, age) }
}

fun validateEmail(email: String): Res<String, String> = rail {
    ensure(email.contains('@')) { "Email must contain @" }
    ensure(email.length >= 3) { "Email too short" }
    email
}

fun validatePassword(password: String): Res<String, String> = rail {
    ensure(password.length >= 8) { "Password must be at least 8 characters" }
    ensure(password.any { it.isDigit() }) { "Password must contain a digit" }
    password
}
```

> **Note:** `rail {}` short-circuits on the first error. If you need to accumulate
> multiple validation errors (e.g., all invalid fields at once), collect them into
> a list before calling `fail()`:
>
> ```kotlin
> fun validate(input: Input): Res<Input, List<String>> = rail {
>     val errors = mutableListOf<String>()
>     if (!input.emailValid) errors += "Invalid email"
>     if (!input.passwordValid) errors += "Password too short"
>     ensure(errors.isEmpty()) { errors }
>     input
> }
> ```

## Building

Build the project:

```bash
./gradlew build
```

Run tests:

```bash
./gradlew test
```

Publish to local Maven repository:

```bash
./gradlew publishToMavenLocal
```

## Requirements

- Kotlin 1.9.25 or higher
- Gradle 8.5 or higher
- JDK 21 or higher (for building — `jvmToolchain(21)`; output targets JVM 8 bytecode)

## Dependencies

- [kotlinx-coroutines-test](https://github.com/Kotlin/kotlinx.coroutines) - For testing suspend functions (test dependency only)

Result-Kit has no runtime dependencies beyond the Kotlin standard library.

## Design

Result-Kit implements **Railway-Oriented Programming**, a functional pattern where operations return a result type that represents either success or failure. This creates two "tracks" through your code:

- **Happy path** — The success track where operations proceed normally
- **Error path** — The failure track where errors short-circuit remaining operations

Key design decisions:

### Inline Value Class

`Res<V, E>` is a `@JvmInline value class` wrapping `Any?`. Ok values are stored directly (zero allocation on the Ok path), while Fail values are wrapped in an internal `Failure` sentinel. Variant discrimination is via `instanceof Failure`. Since `Res` is not a sealed class, use `fold()` or `isOk`/`isFail` for exhaustive handling instead of `when` expressions.

### Control Flow via Exceptions

The DSL uses an internal exception (`FailException`, extending `Throwable` directly — not `Exception`) for control flow to achieve short-circuit semantics. This exception is never exposed to callers — it is caught at the `rail {}` boundary and converted to a Fail result. Stack trace filling is skipped for performance since the exception is purely for control flow.

### Typed Errors

`Res<V, E>` is parameterized over both the value type `V` and the error type `E`, giving callers full control over error representation. Errors can be strings, enums, sealed classes, or any other type — the library imposes no constraints.

### Single Entry Point

Both sync and suspend code use the same `rail {}` function. Because `rail` is `inline`, the compiler can resolve suspend calls within the lambda based on the call-site context, eliminating the need for a separate suspend variant.

### No Runtime Dependencies

Result-Kit deliberately has zero runtime dependencies beyond the Kotlin standard library, making it lightweight and suitable for any Kotlin project.

## License

MIT License

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests on [GitHub](https://github.com/phansen314/result-kit).
