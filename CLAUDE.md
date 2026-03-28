# Result-Kit

Functional error handling library for Kotlin using Railway-Oriented Programming. `@JvmInline value class Res<V, E>` with zero-allocation Ok path (tagged union via internal `Failure` sentinel), plus a `rail {}` DSL for composing failable operations with short-circuit semantics.

## Critical: Do not use raw try/catch inside rail {} blocks

The DSL uses an internal `FailException` (a direct `Throwable` subclass, **not** an `Exception`) for control flow.

- `catch(e: Throwable)` inside `rail {}` will silently swallow `FailException`, breaking the railway
- `catch(e: Exception)` inside `failMapping { }` invoke blocks will intercept exceptions before the mapping can catch and translate them

```kotlin
// NEVER do this inside rail {}
try {
    someResult.orFail()
} catch (e: Throwable) { // swallows FailException!
    fallback
}

// Use Rail.attempt {} instead
Rail.attempt { riskyOperation() }

// Or failMapping { mapper } then invoke with a block
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
- `Rail.failMapping { e -> myError }` — companion factory, returns `FailMappingRail<E>`. Invoke with a block: `appRail { block }` — returns `Res<V, E>`. Combines full `Rail` DSL with automatic exception catching
- `failMapping { e -> myError }` — inside `rail {}`, creates `FailMappingRail<E>`. Invoke with a block: `io { riskyOp() }` — returns `V` directly, short-circuits on exception. Reusable across multiple calls
- `Rail.errorMapping { d -> myError }` — companion factory, returns `ErrorMappingRail<D, E>`. Invoke with a block: `http { block }` — returns `Res<V, E>`. Block runs in a `Rail<D>` scope; short-circuited `D` errors are mapped to `E`. Does **not** catch exceptions
- `errorMapping { d -> myError }` — inside `rail {}`, creates `ErrorMappingRail<D, E>`. Invoke with a `Res<V, D>`: `http(fetchUser(id))` — returns `V` directly, short-circuits on error with mapped type. Reusable across multiple calls
- `Rail.mapping(onError = { d -> myError }, onException = { e -> myError })` — companion factory, returns `MappingRail<D, E>`. Catches exceptions AND unwraps Res, maps both to `E`. Top-level invoke returns `Res<V, E>`
- `mapping(onError = { d -> myError }, onException = { e -> myError })` — inside `rail {}`, creates `MappingRail<D, E>`. Invoke with a block: `http { fetchUser(id) }` — returns `V` directly, short-circuits on exception or error. Reusable across multiple calls

## Res accessors

- `isOk` / `isFail` — check variant without unwrapping
- `getOrNull()` — returns Ok value or `null`
- `errorOrNull()` — returns Fail error or `null`
- `getOrThrow()` — returns Ok value or throws error (requires `E : Throwable`)
- `getOrThrow { transform }` — returns Ok value or throws `transform(error)`
- `errorOrThrow()` — returns Fail error or throws `IllegalStateException`
- `recover { transform }` — convert Fail to Ok; passes through Ok unchanged
- `flatMap { transform }` — chain `(V) -> Res<U, E>` outside `rail {}` (escape hatch; prefer `rail {}` + `orFail()`)
- `toRes()` / `toResult()` — kotlin.Result interop
- Use `fold()` for exhaustive handling (no `when` pattern matching — `Res` is a value class, not sealed)

## Additional Res extensions

- `V?.toResOr { error }` — convert nullable to `Res<V, E>`, lazy error on null
- `Res<V, E>.toFailIf(predicate) { transform }` — convert Ok to Fail if predicate matches
- `Res<V, E>.toFailUnless(predicate) { transform }` — convert Ok to Fail if predicate does not match
- `Res<V?, E>.transpose()` — `Res<V?, E>` to `Res<V, E>?` (null Ok becomes null, Fail stays Fail)
- `Res<Res<V, E>, E>.flatten()` — unwrap nested Res

## Collection extensions (Iterable.kt)

- `Iterable<Res<V, E>>.allOk()` / `anyOk()` / `allFail()` / `anyFail()` — boolean aggregates
- `Iterable<Res<V, E>>.filterOk()` / `filterFail()` — collect Ok values or Fail errors
- `Iterable<Res<V, E>>.combine()` — `Res<List<V>, E>` (short-circuits on first Fail)
- `Iterable<Res<V, E>>.partition()` — `Pair<List<V>, List<E>>`
- `Iterable<V>.tryMap { (V) -> Res<U, E> }` — `Res<List<U>, E>` (short-circuits on first Fail)
- `Iterable<V>.tryForEach { (V) -> Res<*, E> }` — `Res<Unit, E>`
- `Iterable<V>.tryFilter { (V) -> Res<Boolean, E> }` — `Res<List<V>, E>`

## Zip (Zip.kt)

- `zip(block1, block2, ..., transform)` — combine 2–4 `Res` values, short-circuit on first Fail
- `zipOrAccumulate(block1, block2, ..., transform)` — combine 2–4 `Res` values, accumulate all Fails into `List<E>`

## Design decisions

- **Inline value class** — `Res` is `@JvmInline value class` wrapping `Any?`. Ok stores raw value (zero allocation), Fail wraps in internal `Failure` sentinel. Discrimination via `instanceof Failure`
- **`flatMap` exists but is not idiomatic** — `rail {}` with `orFail()` is the preferred chaining pattern; `flatMap` is an escape hatch for composing `(V) -> Res<U, E>` outside rail blocks
- **`failMapping` pattern** — creates a reusable `FailMappingRail`; invoke it multiple times for shared error mapping: `val io = failMapping { e -> ... }; io { block }`
- **`attempt {}` returns `Res<V, Exception>`** — intentional; use `failMapping` + invoke when you need typed errors
- **`Rail` allocation per `rail {}` call** — acceptable, not a performance concern
- **Three mapping rail types** — `failMapping` catches exceptions, `errorMapping` maps typed Res errors, `mapping` does both. Each covers a distinct call-site shape: raw-throwing code, Res-returning code, and code that does both. The types parallel each other with consistent naming and usage patterns
- **`MappingRail` has dual invoke like `FailMappingRail`** — top-level invoke creates its own Rail scope and returns `Res<V, E>`; member extension inside `rail {}` short-circuits the outer scope and returns `V`. Kotlin member extension resolution selects the correct one automatically
- **Zero runtime dependencies** — only Kotlin stdlib; recommend tools (like detekt) to consumers via docs, do not add as build dependencies
