# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

```bash
./gradlew build                          # Compile + test (all modules)
./gradlew :result-kit:test               # Core module tests only
./gradlew :result-kit-ksp:test           # KSP module tests only
./gradlew :result-kit:test --tests "tech.codingzen.resultkit.ResTest"           # Single test class
./gradlew :result-kit:test --tests "tech.codingzen.resultkit.ResTest.testName"  # Single test method
```

No linter configured. Code quality is enforced by `explicitApi()` (all public APIs need explicit visibility and return types) and the Kotlin compiler.

## Project Structure

Multi-module project (`rootProject.name = "result-kit-core"`):
- `result-kit/` — core library (zero runtime dependencies)
- `result-kit-ksp/` — KSP annotation processor (depends on `result-kit`; compile-time only for consumers)

## Architecture

Result-kit is a zero-dependency Kotlin library for Railway-Oriented Programming — typed error handling via a `Res<V, E>` result type and a `rail {}` DSL for short-circuit control flow.

### Core type: `Res<V, E>` (Res.kt)

An inline value class (`@JvmInline value class`) representing Ok or Failure. Ok values are stored unboxed; failures wrap the error in an internal `Failure` sentinel. Covariant in both `V` and `E`. Factories live on the companion: `Res.ok()`, `Res.failure()`. `Rail.attempt()` is a convenience for exception-catching.

### DSL entry point: `rail {}` (RailBuilder.kt)

A single inline function that works for both sync and suspend contexts (the compiler resolves which). Creates a `Rail<E>` scope. Short-circuiting is implemented via an internal `FailException` (extends `Throwable`, not `Exception`) caught at the `rail {}` boundary.

### Rail scope (Rail.kt)

Inside `rail {}`, the receiver is `Rail<E>` which provides:
- `Res<V, E>.orFail(): V` — unwrap or short-circuit
- `fail(e)`, `ensure(...)`, `ensureNotNull(...)` — explicit short-circuit
- `orFail(mapping)` — inside `rail {}`, unwrap `Res<V, D>` using a reusable `ErrorMappingRail`. Preferred over `invoke(res)` for consistency with other `orFail` variants.
- `orFailContext(context: () -> String): V` / `orFailContext(context, location)` — unwrap with context frame attached to failure. Lazy lambda: zero allocation on Ok path. Named `orFailContext` (not `orFail`) to avoid overload ambiguity with `orFail(mapError)` when `E = String`.
- `withContext(message, block)` / `withContext(message, location, block)` — wraps a sub-block; catches `FailException`, appends a frame, rethrows.
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

### Error context chains (context/)

Opt-in error context — frames are stored inside the internal `Failure` sentinel; the public `E` type is unchanged.

- `Frame(message, attachment?, location?)` — one context entry
- `SourceLocation(file, line, function?)` — toString: `"file:line in function"`
- `Failure` carries `List<Frame>`; frames thread through all `FailException`/`Failure` boundaries
- Frame ordering: **append** — index 0 is innermost (first `.context()` call, closest to the error)
- `Res<V, E>.context(message: () -> String): Res<V, E>` — no-op on Ok, appends frame on Fail; lambdas only evaluated on Fail path
- `Res<V, E>.context(message: () -> String, location: () -> SourceLocation): Res<V, E>`
- Extended `fold(onOk, onFail: (E, List<Frame>) -> T)` — disambiguated by 2-param `onFail`
- Rendering: `contextChain()`, `renderContext()`, `contextSummary()`, `contextMap()`, `List<Frame>.findAttachment<T>()`

### KSP module (result-kit-ksp)

`@TraceContext` on an interface → KSP generates `{Name}Traced` decorator:
- Constructor-injects a delegate implementing the interface
- Every `Res`-returning method: `delegate.method(args).context({ "Name.method(p=$p)" }, { SourceLocation(...) })`
- Non-`Res` methods: plain delegation, no wrapping
- `@TraceContext(suffix = "Wrapped")` — custom class name suffix (default `"Traced"`)
- `@TraceMessage("loading user {id}")` on a method — replaces auto-generated message; `{param}` → `$param`
- `@TraceInclude` on a parameter — opts its value into the auto-generated message (values excluded by default)
- `suspend` and type parameters are preserved in the generated class

### Composition (Zip.kt, Iterable.kt)

- `zip(...)` — fail-fast sequential combination of up to 4 results
- `zipOrAccumulate(...)` — evaluates all blocks, accumulates errors into `List<E>`
- Iterable extensions: `combine()`, `tryMap()`, `tryForEach()`, `partition()`, `filterOk()`, `filterFail()`

## Design Decisions

- **Zero runtime dependencies.** Don't add any. Recommend tools to consumers in docs only.
- **Companion factories only.** `ok()`/`failure()` live on `Res.Companion`; `attempt()`/`failMapping()`/`mapping()` live on `Rail.Companion`. None are top-level functions.
- **No `flatMap` on `Res`.** The DSL (`rail {}` + `orFail()`) replaces `flatMap` chaining — this is intentional.
- **`FailException` extends `Throwable`, not `Exception`.** This is critical — `catch (e: Exception)` blocks inside `rail {}` must not intercept control flow. Never catch `Throwable` inside `rail {}`.
- **Scope allocation per `rail {}` call is acceptable.** Don't flag it in reviews.
- **`failMapping { mapError }` + `scope { block }` pattern is intentional.** Don't collapse scope creation and invocation into a single call.
- **Scope interfaces are standalone.** Don't create inheritance hierarchies between scope types; duplicate methods instead.
- **Frame ordering is append (not prepend).** Index 0 = innermost/most-specific context. `orFailContext { }` and `.context()` both append.
- **`orFailContext` takes a `() -> String` lambda, not a plain `String`.** Zero allocation on Ok path. Named `orFailContext` (not `orFail`) to avoid overload ambiguity with `orFail(mapError: (F) -> E)` when `E = String`.

## Kotlin Compiler Settings

- `explicitApi()` — all public members must declare visibility and return types
- JVM toolchain 21 (builds on JDK 21), targets JVM 1.8 bytecode
- Kotlin 1.9.25
