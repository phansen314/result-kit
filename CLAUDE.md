# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

```bash
./gradlew build          # Compile + test
./gradlew test           # Run tests only
./gradlew compileKotlin  # Compile only (no tests)
./gradlew test --tests "tech.codingzen.resultkit.ResTest"           # Single test class
./gradlew test --tests "tech.codingzen.resultkit.ResTest.testName"  # Single test method
```

No linter configured. Code quality is enforced by `explicitApi()` (all public APIs need explicit visibility and return types) and the Kotlin compiler.

## Architecture

Result-kit is a zero-dependency Kotlin library for Railway-Oriented Programming — typed error handling via a `Res<V, E>` result type and a `rail {}` DSL for short-circuit control flow.

### Core type: `Res<V, E>` (Res.kt)

An inline value class (`@JvmInline value class`) representing Ok or Failure. Ok values are stored unboxed; failures wrap the error in an internal `Failure` sentinel. Covariant in both `V` and `E`. Factories live on the companion: `Res.ok()`, `Res.failure()`, `Res.attempt()`.

### DSL entry point: `rail {}` (RailBuilder.kt)

A single inline function that works for both sync and suspend contexts (the compiler resolves which). Creates a `Rail<E>` scope. Short-circuiting is implemented via an internal `FailException` (extends `Throwable`, not `Exception`) caught at the `rail {}` boundary.

### Rail scope (Rail.kt)

Inside `rail {}`, the receiver is `Rail<E>` which provides:
- `Res<V, E>.orFail(): V` — unwrap or short-circuit
- `fail(e)`, `ensure(...)`, `ensureNotNull(...)` — explicit short-circuit
- `orFail(mapping)` — inside `rail {}`, unwrap `Res<V, D>` using a reusable `ErrorMappingRail`. Preferred over `invoke(res)` for consistency with other `orFail` variants.
- Factory methods for mapping scopes: `failMapping()`, `errorMapping()`, `mapping()`

### Mapping scopes (FailMappingRail.kt, ErrorMappingRail.kt, MappingRail.kt)

Reusable scopes created inside `rail {}` that handle exception catching and/or typed-error mapping. They behave differently at top-level (return `Res`) vs. inside a `rail {}` block (return unwrapped value, short-circuit on failure). The `@RailDsl` marker annotation prevents implicit access to outer `Rail` receivers in nested scopes.

### Validation (Validator.kt, ValidationMapping.kt)

- `Validator<E>` — mutable error accumulator, does NOT extend Rail
  - `fail(error)` — add error directly
  - `ensure(condition) { error }` — add error if condition false (does not short-circuit)
  - `addAll(Iterable<E>)` — bulk add (for bridging Spring/JSR-303 errors)
  - `Res<V, E>.check()` — collect Fail error, discard value
  - `Res<V, F>.check(mapError)` — collect mapped Fail error, discard value
  - `Res<V, E>.checkOrNull(): V?` — collect Fail error and return null, or return Ok value
  - `Res<V, F>.checkOrNull(mapError): V?` — collect mapped Fail error and return null, or return Ok value
  - `hasErrors: Boolean` — check if any errors accumulated
  - `errors(): List<E>` — snapshot of accumulated errors
  - `toRes(): Res<Unit, List<E>>` — Ok(Unit) if clean, Fail(errors) if not
- `validation { block }` — scoped block on Validator, returns `Res<Unit, List<E>>`
- `validator<E>()` — factory for imperative use
- `ValidationMapping<F, E>` — reusable mapper (like FailMappingRail)
  - Top-level invoke: runs block, returns `Res<Unit, E>` with mapped errors
  - Inside `rail {}`: member extension wins, short-circuits rail on errors
- Rail member extensions:
  - `validation<F>(mapErrors)` — factory for ValidationMapping
  - `ValidationMapping.invoke(block)` — run validation block, flush into rail
  - `Validator<F>.orFail(mapErrors)` — flush imperative validator into rail
- `Rail.Companion.validation<F, E>(mapErrors)` — top-level ValidationMapping factory

### Composition (Zip.kt, Iterable.kt)

- `zip(...)` — fail-fast sequential combination of up to 4 results
- `zipOrAccumulate(...)` — evaluates all blocks, accumulates errors into `List<E>`
- Iterable extensions: `combine()`, `tryMap()`, `tryForEach()`, `partition()`, `filterOk()`, `filterFail()`

## Design Decisions

- **Zero runtime dependencies.** Don't add any. Recommend tools to consumers in docs only.
- **Companion factories only.** `ok()`/`failure()`/`attempt()` live on `Res.Companion`, not as top-level functions.
- **No `flatMap` on `Res`.** The DSL (`rail {}` + `orFail()`) replaces `flatMap` chaining — this is intentional.
- **`FailException` extends `Throwable`, not `Exception`.** This is critical — `catching {}` blocks inside `rail {}` must not intercept control flow. Never catch `Throwable` inside `rail {}`.
- **Scope allocation per `rail {}` call is acceptable.** Don't flag it in reviews.
- **`mapping{}.catching{}` pattern is intentional.** Don't collapse to `catching(mapError) { block }`.
- **Scope interfaces are standalone.** Don't create inheritance hierarchies between scope types; duplicate methods instead.

## Kotlin Compiler Settings

- `explicitApi()` — all public members must declare visibility and return types
- JVM toolchain 21 (builds on JDK 21), targets JVM 1.8 bytecode
- Kotlin 1.9.25
