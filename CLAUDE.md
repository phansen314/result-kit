# Result-Kit

Functional error handling library for Kotlin using Railway-Oriented Programming. Sealed `Res<V, E>` type with `Ok<V>` and `Fail<E>` branches, plus a `rail {}` DSL for composing failable operations with short-circuit semantics.

## Critical: Do not use raw try/catch inside rail {} blocks

The DSL uses an internal `FailException` (extends `Throwable`) for control flow. A raw `catch(e: Exception)` or `catch(e: Throwable)` inside `rail {}` will silently swallow it, breaking the railway with no visible error.

```kotlin
// NEVER do this inside rail {}
try {
    someResult.orFail()
} catch (e: Exception) { // swallows FailException!
    fallback
}

// Use catching {} instead
catching { riskyOperation() }

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
- `catching { block }` — top-level convenience, returns `Res<V, Exception>`
- `failMapping { e -> myError }` — inside `rail {}`, creates `FailMappingRail<E>`. Invoke with a block: `io { riskyOp() }` — returns `V` directly, short-circuits on exception. Reusable across multiple calls
- `FailMappingRail { e -> myError }` — top-level factory returning `FailMappingRail<E>`. Invoke with a block: `appRail { block }` — returns `Res<V, E>`. Combines full `Rail` DSL with automatic exception catching

## Design decisions

- **No `flatMap` on `Res`** — contradicts DSL-first design; use `rail {}` with `orFail()` for chaining
- **`failMapping {} { block }` pattern** — `mapping` creates a reusable `FailMappingRail`; invoke it multiple times for shared error mapping
- **`catching {}` returns `Res<V, Exception>`** — intentional; use `failMapping {} { block }` when you need typed errors
- **`Rail` allocation per `rail {}` call** — acceptable, not a performance concern
- **Zero runtime dependencies** — only Kotlin stdlib; recommend tools (like detekt) to consumers via docs, do not add as build dependencies
