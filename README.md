# Result-Kit

A functional error handling library for Kotlin with Railway-Oriented Programming support. Provides a sealed `Res<T>` type and DSL for composing operations that can fail, with both synchronous and coroutine-based entry points.

## Overview

Result-Kit brings Railway-Oriented Programming to Kotlin, allowing you to compose operations that can fail in a type-safe, functional way. Instead of throwing exceptions or using nullable types, operations return `Res<T>` values that are either successful (`Res.Ok<T>`) or failed (`Res.Err`). The DSL provides short-circuit semantics, allowing failed operations to automatically propagate through your code without manual error checking at each step.

## Features

- **Type-safe result type** - Sealed `Res<T>` with `Ok<T>` and `Err` branches
- **Synchronous DSL** - `res {}` blocks with `ResDsl` scope for regular functions
- **Coroutine support** - `suspendRes {}` blocks with `SuspendResDsl` scope for suspend functions
- **Flexible error handling** - `CatchScope` with lazy, evolving error messages
- **Short-circuit operations** - `.ok()` unwrapping and `.or(default)` fallback
- **Explicit error raising** - `err(message)` and `err(exception, message)` for controlled failure
- **Clean composition** - Chain operations without manual error checking
- **Exception catching** - Convert throwing code to `Res` values with contextual messages

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("tech.codingzen:result-kit:1.0.0")
}
```

## Usage

### Basic Synchronous DSL

The `res {}` block creates a scope where you can compose operations that return `Res<T>`:

```kotlin
import tech.codingzen.resultkit.*

data class User(val id: Int, val name: String)

fun parseUserId(input: String): Res<Int> = catch {
    message { "Invalid user ID: $input" }
    input.toInt()
}

fun fetchUser(id: Int): Res<User> = res {
    if (id < 0) err("User ID must be positive")
    // Simulate fetch
    Res.value(User(id, "User $id"))
}

fun getUserName(input: String): Res<String> = res {
    val id = parseUserId(input).ok()  // Short-circuit if parsing fails
    val user = fetchUser(id).ok()      // Short-circuit if fetch fails
    user.name
}

// Usage
when (val result = getUserName("123")) {
    is Res.Ok -> println("User: ${result.value}")
    is Res.Err.Message -> println("Error: ${result.message}")
    is Res.Err.Thrown -> println("Exception: ${result.exception.message}")
    is Res.Err.UncaughtThrown -> println("Uncaught: ${result.uncaught.message}")
}
```

### Short-Circuit Operations

Use `.ok()` to unwrap successful results or short-circuit on error:

```kotlin
val result = res {
    val x = operation1().ok()  // Returns the value or short-circuits
    val y = operation2().ok()  // Only runs if operation1 succeeded
    val z = operation3().ok()  // Only runs if operation2 succeeded
    x + y + z
}
```

Use `.or()` to provide a default value for failed results:

```kotlin
val result = res {
    val config = loadConfig().or(defaultConfig)  // Use default on failure
    val port = parsePort(input).or(8080)         // Use 8080 on parse error
    connectToServer(config, port)
}
```

### Error Raising

Explicitly raise errors with `err()`:

```kotlin
fun validateAge(age: Int): Res<Int> = res {
    if (age < 0) err("Age cannot be negative")
    if (age > 150) err("Age seems unrealistic")
    age
}

fun processWithException(data: String): Res<Data> = res {
    try {
        parse(data)
    } catch (e: ParseException) {
        err(e, "Failed to parse data")
    }
}
```

### Exception Handling with Lazy Messages

Use `catch {}` blocks to convert throwing code into `Res` values with contextual error messages:

```kotlin
val result = res {
    val config = catch {
        message { "Failed to load config from $configPath" }
        loadConfigFile(configPath)  // May throw IOException
    }

    val connection = catch {
        message { "Failed to connect to ${config.host}:${config.port}" }
        openConnection(config)  // May throw ConnectionException
    }

    val data = catch {
        message { "Failed to fetch data from connection ${connection.id}" }
        connection.fetchData()  // May throw NetworkException
    }

    processData(data)
}
```

The message is lazily evaluated only if an exception occurs, and can be updated as the block progresses:

```kotlin
res {
    val results = catch {
        val items = loadItems()
        for ((index, item) in items.withIndex()) {
            message { "Processing item $index of ${items.size}" }
            processItem(item)  // If this throws, error includes current index
        }
        items
    }
}
```

### Top-Level Catch Functions

For simple exception-to-`Res` conversion without the full DSL:

```kotlin
// Synchronous
val config: Res<Config> = catch {
    message { "Failed to load config from $path" }
    loadConfigFile(path)
}

// Suspend
val user: Res<User> = suspendCatch {
    message { "Failed to fetch user $id" }
    userService.fetch(id)  // suspend function
}
```

### Suspend DSL

Use `suspendRes {}` for coroutine-based operations:

```kotlin
suspend fun fetchUserData(userId: Int): Res<UserData> = suspendRes {
    val user = catch {
        message { "Failed to fetch user $userId" }
        userService.getUser(userId)  // suspend call
    }

    val profile = catch {
        message { "Failed to fetch profile for ${user.name}" }
        profileService.getProfile(user.id)  // suspend call
    }

    val preferences = catch {
        message { "Failed to fetch preferences" }
        preferencesService.get(user.id)  // suspend call
    }

    UserData(user, profile, preferences)
}
```

The suspend DSL provides the same operations as the synchronous DSL:

```kotlin
suspend fun processOrder(orderId: String): Res<Receipt> = suspendRes {
    val order = parseOrder(orderId).ok()
    val validated = validateOrder(order).ok()

    val payment = catch {
        message { "Payment failed for order $orderId" }
        paymentService.processPayment(validated.amount)
    }

    val receipt = catch {
        message { "Failed to generate receipt" }
        receiptService.generate(validated, payment)
    }

    receipt
}
```

## API Reference

### Res<T>

Sealed result type representing either a successful value or an error.

**Subtypes**:
- `Res.Ok<T>` - Contains a successful `value: T`
- `Res.Err` - Base class for errors
  - `Res.Err.Message` - Error with a `message: String`
  - `Res.Err.Thrown` - Error wrapping an `exception: Exception` with optional `message: String?`
  - `Res.Err.UncaughtThrown` - Exception that escaped the DSL with `uncaught: Exception`

**Companion Object**:
- `Res.value(value: T): Res<T>` - Create a successful result
- `Res.error(message: String): Res.Err` - Create a message error
- `Res.error(exception: Exception, message: String? = null): Res.Err` - Create an exception error

### res {}

Entry point for synchronous railway-oriented error handling.

```kotlin
inline fun <T> res(block: ResDsl.() -> T): Res<T>
```

Executes the block within a `ResDsl` scope and returns `Res.Ok` on success or `Res.Err` on failure.

### ResDsl

Synchronous DSL scope providing:

- `Res<T>.ok(): T` - Unwrap value or short-circuit
- `Res<T>.or(default: T): T` - Unwrap value or use default
- `err(message: String): Nothing` - Raise a message error
- `err(thrown: Exception, message: String): Nothing` - Raise an exception error
- `catch(block: CatchScope.() -> T): T` - Catch exceptions with lazy messages

### suspendRes {}

Entry point for suspend railway-oriented error handling.

```kotlin
suspend inline fun <T> suspendRes(block: suspend SuspendResDsl.() -> T): Res<T>
```

The suspend counterpart to `res {}` for use within coroutines.

### SuspendResDsl

Suspend DSL scope providing the same operations as `ResDsl`:

- `suspend Res<T>.ok(): T` - Unwrap value or short-circuit
- `Res<T>.or(default: T): T` - Unwrap value or use default
- `err(message: String): Nothing` - Raise a message error
- `err(thrown: Exception, message: String): Nothing` - Raise an exception error
- `suspend catch(block: CatchScope.() -> T): T` - Catch exceptions with lazy messages

### CatchScope

Receiver scope for `catch {}` blocks providing:

- `message(provider: () -> String)` - Set lazy error message

The message can be set or updated at any point during the block. If an exception occurs, the most recently set message is included in the resulting error.

### Top-Level Functions

Convenience functions for simple exception catching:

```kotlin
inline fun <T> catch(block: CatchScope.() -> T): Res<T>
suspend inline fun <T> suspendCatch(block: suspend CatchScope.() -> T): Res<T>
```

Convert throwing operations directly to `Res` without the full DSL scope. All exceptions become `Res.Err.Thrown` (no `UncaughtThrown` case).

## Use Cases

### API Request Handling

Compose multiple API calls with automatic error propagation:

```kotlin
suspend fun fetchDashboardData(userId: Int): Res<Dashboard> = suspendRes {
    val user = catch {
        message { "Failed to fetch user $userId" }
        api.getUser(userId)
    }

    val metrics = catch {
        message { "Failed to fetch metrics for ${user.name}" }
        api.getMetrics(user.id)
    }

    val notifications = catch {
        message { "Failed to fetch notifications" }
        api.getNotifications(user.id)
    }.or(emptyList())  // Use empty list if notifications fail

    Dashboard(user, metrics, notifications)
}
```

### Configuration Loading

Load and validate configuration with descriptive error messages:

```kotlin
fun loadAppConfig(): Res<AppConfig> = res {
    val raw = catch {
        message { "Failed to read config file at $CONFIG_PATH" }
        File(CONFIG_PATH).readText()
    }

    val parsed = catch {
        message { "Failed to parse config JSON" }
        Json.decodeFromString<ConfigData>(raw)
    }

    val validated = validateConfig(parsed).ok()

    AppConfig(validated)
}

fun validateConfig(data: ConfigData): Res<ConfigData> = res {
    if (data.port !in 1..65535) err("Port must be between 1 and 65535")
    if (data.host.isBlank()) err("Host cannot be blank")
    if (data.timeout <= 0) err("Timeout must be positive")
    data
}
```

### Database Operations

Handle database operations with transaction rollback on error:

```kotlin
suspend fun transferFunds(
    from: AccountId,
    to: AccountId,
    amount: Money
): Res<Transaction> = suspendRes {
    val transaction = db.beginTransaction()

    val result = catch {
        message { "Failed to debit account $from" }
        transaction.debitAccount(from, amount)

        message { "Failed to credit account $to" }
        transaction.creditAccount(to, amount)

        message { "Failed to commit transaction" }
        transaction.commit()
    }

    result
}
```

### Input Validation Pipeline

Chain multiple validation steps:

```kotlin
fun registerUser(
    email: String,
    password: String,
    age: String
): Res<User> = res {
    val validEmail = validateEmail(email).ok()
    val validPassword = validatePassword(password).ok()
    val validAge = parseAge(age).ok()

    val user = catch {
        message { "Failed to create user record" }
        userRepository.create(validEmail, validPassword, validAge)
    }

    user
}

fun validateEmail(email: String): Res<String> = res {
    if (!email.contains('@')) err("Email must contain @")
    if (email.length < 3) err("Email too short")
    email
}

fun validatePassword(password: String): Res<String> = res {
    if (password.length < 8) err("Password must be at least 8 characters")
    if (!password.any { it.isDigit() }) err("Password must contain a digit")
    password
}

fun parseAge(input: String): Res<Int> = catch {
    message { "Invalid age: $input" }
    val age = input.toInt()
    if (age < 0 || age > 150) throw IllegalArgumentException("Age out of range")
    age
}
```

### Fallback Chains

Provide defaults for non-critical failures:

```kotlin
suspend fun getUserPreferences(userId: Int): Res<Preferences> = suspendRes {
    val user = fetchUser(userId).ok()  // Critical - must succeed

    // Non-critical - use defaults on failure
    val theme = loadTheme(userId).or(Theme.DEFAULT)
    val language = loadLanguage(userId).or(Language.ENGLISH)
    val notifications = loadNotificationSettings(userId).or(NotificationSettings.DEFAULT)

    Preferences(user, theme, language, notifications)
}
```

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

- Kotlin 2.0.21 or higher
- Gradle 8.5 or higher
- JVM 1.8 or higher (compiled for Java 8 compatibility)

## Dependencies

- [kotlinx-coroutines-test](https://github.com/Kotlin/kotlinx.coroutines) - For testing suspend functions (test dependency only)

Result-Kit has no runtime dependencies beyond the Kotlin standard library.

## Design

Result-Kit implements **Railway-Oriented Programming**, a functional pattern where operations return a result type that represents either success or failure. This creates two "tracks" through your code:

- **Happy path** - The success track where operations proceed normally
- **Error path** - The failure track where errors short-circuit remaining operations

Key design decisions:

### Control Flow via Exceptions

The DSL uses exceptions (`ErrException`) internally for control flow to achieve short-circuit semantics. These exceptions are never exposed to callers - they are caught at the DSL boundary and converted to `Res.Err` values. Stack trace filling is skipped for performance since these exceptions are purely for control flow.

### Lazy Error Messages

Error messages in `catch {}` blocks are lazy by design. The message provider is only evaluated if an exception actually occurs, avoiding the performance cost of string building for the happy path. Messages can be updated as the block progresses to reflect the current operation context.

### Distinction Between Caught and Uncaught Exceptions

- **Caught exceptions** (via `catch {}`) become `Res.Err.Thrown` with optional contextual messages
- **Uncaught exceptions** (that escape all `catch {}` blocks) become `Res.Err.UncaughtThrown`

This distinction helps identify programming errors (exceptions that should have been caught) versus expected exceptions.

### Suspend DSL Implementation

The suspend DSL uses `suspendCoroutineUninterceptedOrReturn` to properly short-circuit suspend contexts when `.ok()` encounters an error. This ensures that error propagation works correctly even when crossing suspend function boundaries.

### No Runtime Dependencies

Result-Kit deliberately has zero runtime dependencies beyond the Kotlin standard library, making it lightweight and suitable for any Kotlin project.

## License

MIT License

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests on [GitHub](https://github.com/phansen314/result-kit).
