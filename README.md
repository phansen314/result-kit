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
    val http = failMapping { e -> AppError.Network(e.message) }
    val db   = failMapping { e -> AppError.Database(e.message) }

    val user     = http { httpClient.getUser(id) }
    val prefs    = http { httpClient.getPrefs(user.id) }
    val settings = db { db.getSettings(user.id) }
    Dashboard(user, prefs, settings)
}
```

The `failMapping` scopes are values — capture them once, reuse them across every function in the layer:

```kotlin
class UserService(private val httpClient: HttpClient, private val db: Database) {
    fun loadDashboard(id: Int): Res<Dashboard, AppError> = rail {
        val http = failMapping { e -> AppError.Network(e.message) }
        val sql  = failMapping { e -> AppError.Database(e.message) }

        val user     = http { httpClient.getUser(id) }
        val prefs    = http { httpClient.getPrefs(user.id) }
        val settings = sql  { db.getSettings(user.id) }
        Dashboard(user, prefs, settings)
    }

    fun loadProfile(id: Int): Res<Profile, AppError> = rail {
        val http = failMapping { e -> AppError.Network(e.message) }
        val sql  = failMapping { e -> AppError.Database(e.message) }

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
    implementation("tech.codingzen:result-kit:1.1.0")

    // Optional: KSP module for @TraceContext automatic traced-wrapper generation
    ksp("tech.codingzen:result-kit-ksp:1.1.0")
}
```

The KSP module requires the [KSP Gradle plugin](https://kotlinlang.org/docs/ksp-quickstart.html).

## Quick Start

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

**`failMapping`** — catches exceptions from IO, HTTP clients, JSON parsing:
```kotlin
val io = failMapping { e -> AppError.IO(e.message) }
val raw = io { File(path).readText() }
```

**`errorMapping`** — translates between typed error domains:
```kotlin
val fromUser = errorMapping<UserError> { AppError.User(it) }
val user = fetchUser(id).orFail(fromUser)
```

**`mapping`** — handles code that both throws and returns typed errors:
```kotlin
val api = mapping<ApiError>(
    onError     = { AppError.Api(it) },
    onException = { AppError.Network(it.message) },
)
val user = api { retrofitService.getUser(id) }
```

### Error Context Chains

Attach breadcrumb frames to failures as they propagate — without changing the error type:

```kotlin
rail {
    withContext("processing order $id") {
        val user = fetchUser(userId).orFailContext { "fetching user" }
        val order = createOrder(user).orFailContext { "creating order" }
        order
    }
}
// On failure: "processing order 42 → fetching user → DbError(connection refused)"
```

### KSP Traced Wrappers

Auto-generate context-attaching decorators for your repository interfaces:

```kotlin
@TraceContext
interface UserRepository {
    fun findById(@TraceInclude id: Int): Res<User, DbError>
}
// Generates UserRepositoryTraced — every Res-returning method
// wrapped with .context(message, sourceLocation) automatically
```

## Documentation

| Document | Audience | Contents |
|---|---|---|
| [Guide](docs/guide.md) | Developers | Full tutorial with examples for every feature |
| [Design](docs/design.md) | Contributors | Architecture, invariants, design rationale |
| [vs kotlin-result](docs/vs-kotlin-result.md) | Evaluators | Feature comparison with kotlin-result (coming soon) |
| [vs Arrow](docs/vs-arrow.md) | Evaluators | Feature comparison with Arrow (coming soon) |

## Important: Exception Handling Inside `rail {}`

The `rail {}` DSL uses an internal exception for control flow. **Do not use raw `try/catch(Throwable)` inside `rail {}` blocks** — use `failMapping` instead. See the [Common Pitfalls](docs/guide.md#common-pitfalls) section in the guide for details.

## Building

```bash
./gradlew build    # compile + test (all modules)
./gradlew test     # tests only
```

**Requirements:** Kotlin 1.9.25+, JDK 21+ to build (targets Java 8 bytecode at runtime).

## License

MIT
