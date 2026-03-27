# Result-Kit

Functional error handling library for Kotlin using Railway-Oriented Programming. `@JvmInline value class Res<V, E>` with zero-allocation Ok path (tagged union via internal `Failure` sentinel), plus a `rail {}` DSL for composing failable operations with short-circuit semantics.

## Critical: Do not use raw try/catch inside rail {} blocks

The DSL uses an internal `FailException` (a direct `Throwable` subclass, **not** an `Exception`) for control flow.

- `catch(e: Throwable)` inside `rail {}` will silently swallow `FailException`, breaking the railway
- `catch(e: Exception)` inside `failMapping { } { }` blocks will intercept exceptions before the mapping can catch and translate them

```kotlin
// NEVER do this inside rail {}
try {
    someResult.orFail()
} catch (e: Throwable) { // swallows FailException!
    fallback
}

// Use Rail.attempt {} instead
Rail.attempt { riskyOperation() }

// Or failMapping {} { block } for error mapping
val http = failMapping { e -> "HTTP error: ${e.message}" }
http { fetchUser(id) }
```

## API patterns

- `rail {}` — single inline function, works in both sync and suspend contexts (compiler disambiguates)
- `fail(e)` — short-circuit with error
- `orFail()` — unwrap Ok or short-circuit
- `orFail { mapError }` — unwrap with error type conversion
- `ensure(condition) { error }` — validate or short-circuit
- `ensureNotNull(value) { error }` — null-check or short-circuit
- `Rail.attempt { block }` — companion factory, returns `Res<V, Exception>`
- `failMapping { e -> myError }` — inside `rail {}`, creates `FailMappingRail<E>`. Invoke with a block: `io { riskyOp() }` — returns `V` directly, short-circuits on exception. Reusable across multiple calls
- `FailMappingRail { e -> myError }` — top-level factory returning `FailMappingRail<E>`. Invoke with a block: `appRail { block }` — returns `Res<V, E>`. Combines full `Rail` DSL with automatic exception catching

## Res properties

- `isOk` / `isFail` — check variant without unwrapping
- `getOrNull` — property, returns Ok value or `null`
- `errorOrNull` — property, returns Fail error or `null`
- `getOrThrow()` — function, returns Ok value or throws error (requires `E : Throwable`)
- `getOrThrow { transform }` — function, returns Ok value or throws `transform(error)`
- `errorOrThrow()` — function, returns Fail error or throws `IllegalStateException`
- `recover { transform }` — convert Fail to Ok; passes through Ok unchanged
- Use `fold()` for exhaustive handling (no `when` pattern matching — `Res` is a value class, not sealed)

## Design decisions

- **Inline value class** — `Res` is `@JvmInline value class` wrapping `Any?`. Ok stores raw value (zero allocation), Fail wraps in internal `Failure` sentinel. Discrimination via `instanceof Failure`
- **No `flatMap` on `Res`** — contradicts DSL-first design; use `rail {}` with `orFail()` for chaining
- **`failMapping {} { block }` pattern** — `mapping` creates a reusable `FailMappingRail`; invoke it multiple times for shared error mapping
- **`attempt {}` returns `Res<V, Exception>`** — intentional; use `failMapping {} { block }` when you need typed errors
- **`Rail` allocation per `rail {}` call** — acceptable, not a performance concern
- **Zero runtime dependencies** — only Kotlin stdlib; recommend tools (like detekt) to consumers via docs, do not add as build dependencies
