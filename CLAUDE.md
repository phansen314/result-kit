# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository. It is the single-file knowledge base for the library — comprehensive enough to answer any question about API surface, behavior, edge cases, and interactions between subsystems.

## Build & Test

```bash
./gradlew build                          # Compile + test
./gradlew :result-kit:test               # Core module tests only
./gradlew :result-kit:test --tests "tech.codingzen.resultkit.ResTest"           # Single test class
./gradlew :result-kit:test --tests "tech.codingzen.resultkit.ResTest.testName"  # Single test method
```

No linter configured. Code quality is enforced by `explicitApi()` (all public APIs need explicit visibility and return types) and the Kotlin compiler.

## Project Structure

Gradle project (`rootProject.name = "result-kit"`). The shipped build is effectively single-module:
only `:result-kit` is in `settings.gradle.kts` `include(...)`. The `result-kit-ksp` module lives
on disk but is detached from the build (see "KSP Module" below).

```
result-kit/          — core library (zero runtime dependencies)
  src/main/kotlin/tech/codingzen/resultkit/
    Res.kt                 — Res<V,E> value class, Failure sentinel, extension functions
    Rail.kt                — Rail<E> DSL scope, FailException, ErrorMapperException
    RailBuilder.kt         — rail {} entry point
    RailDsl.kt             — @RailDsl marker annotation
    ExceptionMappingRail.kt     — exception-catching scope
    ErrorMappingRail.kt    — typed-error-mapping scope
    MappingRail.kt         — combined exception + typed-error scope
    Zip.kt                 — zip (fail-fast, arities 2-4)
    Iterable.kt            — collection extensions
    context/
      Frame.kt             — Frame, SourceLocation data classes
      ContextExtensions.kt — .context(), contextFrame(), extended fold()
      ContextRendering.kt  — contextChain(), renderContext(), contextSummary(), contextMap(), findAttachment()

result-kit-ksp/      — KSP annotation processor. DETACHED FROM THE BUILD (not in settings.gradle.kts
                       include(...), not shipped). Kept on disk for a future release. Owns the
                       @TraceContext/@TraceMessage/@TraceInclude annotations (moved out of core so the
                       core jar carries nothing KSP-related). To work on it, temporarily re-add
                       ":result-kit-ksp" to settings.gradle.kts.
  src/main/kotlin/tech/codingzen/resultkit/
    ksp/TraceContextProcessor.kt          — KSP processor and provider
    context/TraceAnnotations.kt           — @TraceContext, @TraceMessage, @TraceInclude
  src/main/resources/META-INF/services/
    com.google.devtools.ksp.processing.SymbolProcessorProvider
```

## Core Type: Res<V, E>

`@JvmInline value class` wrapping `Any?`. Covariant in both `V` and `E`.

**Internal representation:**
- Ok path: `inlineValue` stores the raw value directly (zero allocation)
- Fail path: `inlineValue` stores a `Failure(error: Any?, frames: List<Frame> = emptyList())` sentinel (one allocation)
- Variant discrimination: `inlineValue is Failure`
- Nested `Res<Res<...>, ...>` is safe: inner Res gets boxed when stored as `Any?`

**Factories:**
- `Res.ok(value)` — defensive `check(value !is Failure)` guard; cost: one instanceof per call
- `Res.failure(error)` — wraps in `Failure`; always safe, one allocation
- `Res.unsafeOk(value)` — `@PublishedApi internal`, skips the guard; callers must guarantee value is not a Failure instance

**Equality:** `Failure.equals` compares only `error`, not `frames`. Frames are observability metadata, not part of domain equality. Hash collision between `ok(null)` and `failure(null)` is prevented by XOR with `0x4641494C`.

### Res Extension Functions

Accessors:
- `getOrNull(): V?` — Ok value or null
- `errorOrNull(): E?` — Fail error or null
- `getOrElse(default: (E) -> V): V`
- `getOrThrow(attachFrames: Boolean = false): V` — requires `E : Throwable`. By default unwraps the Ok value or throws the error **untouched**. Pass `attachFrames = true` to add context frames to the thrown error as `Throwable.addSuppressed(FrameTrace(frame))` entries so the breadcrumb chain survives the JVM throw boundary and appears in standard stack dumps. **Attachment is opt-in because it mutates the thrown error in place** — not idempotent, and unsafe for shared/`object` errors reused across results (frames accumulate/intermix on the shared instance).
- `getOrThrow(attachFrames: Boolean = false, transform: (E) -> Throwable): V` — same opt-in `attachFrames` flag, off by default. When `transform` builds a fresh throwable per call (the common case), attaching is safe even on repeated calls.
- `errorOrThrow(): E` — throws ISE on Ok

Transforms:
- `fold(onOk, onFail): T` — exhaustive match (recommended over `when`)
- `map(transform): Res<U, E>` — transforms Ok, Fail passes through unchanged (including frames)
- `mapError(transform): Res<V, F>` — transforms Fail error, **preserves frames**, Ok passes through
- `recover(transform: (E) -> V): Res<V, Nothing>` — infallible Fail→Ok conversion. Frames are discarded (Ok carries no frames).
- `recover(transform: (E, List<Frame>) -> V): Res<V, Nothing>` — frame-aware overload in `context` package; passes frames to the transform before they are discarded. Disambiguated by two-arg lambda shape.
- `orElse(transform: (E) -> Res<V, F>): Res<V, F>` — fallible recovery. If the recovery also fails, original frames are prepended to the recovery's frames.
- `flatMap(transform: (V) -> Res<U, E>): Res<U, E>` — escape hatch for chaining outside rail; prefer rail + orFail
- `flatten(): Res<V, E>` — unwraps `Res<Res<V, E>, E>`

Side effects:
- `onOk(action): Res<V, E>` — runs action, returns self
- `onFail(action): Res<V, E>` — runs action, returns self
- `tap(onOk, onFail): Res<V, E>` — both branches at once; default no-op lambdas

Factories & conditionals:
- `V?.toResOr(error: () -> E): Res<V, E>` — non-null→Ok, null→Fail
- `toFailIf(predicate, transform): Res<V, E>` — Ok→Fail if predicate matches

Interop:
- `Result<V>.toRes(): Res<V, Throwable>`
- `Res<V, E : Throwable>.toResult(): Result<V>`
- `Res<V, E>.toResult(transform: (E) -> Throwable): Result<V>` — for non-throwable error types

All inline extension functions use `@OptIn(ExperimentalContracts::class)` with appropriate `callsInPlace` contracts. `@UnsafeVariance` is used where lambdas produce (not consume) covariant type parameters — each usage is safe.

## Rail DSL

### Entry Point: `rail {}`

```kotlin
inline fun <V, E> rail(block: Rail<E>.() -> V): Res<V, E>
```

Creates a `Rail<E>` scope. Returns `Res.Ok` on normal completion, `Res.Fail` on short-circuit. Inline — works in both sync and suspend contexts (compiler resolves based on call site). Has `callsInPlace(block, EXACTLY_ONCE)` contract.

The catch at the boundary: catches `FailException`, checks `e.scope === scope` (rethrows if foreign scope), constructs `Failure(e.error, e.frames)`.

### Rail<E> Scope Operations

Inside `rail {}`, the receiver `Rail<E>` provides:

**Short-circuit:**
- `fail(e: E): Nothing` — throws `FailException(error=e, scope=this, frames=emptyList())`
- `Res<V, E>.orFail(): V` — unwrap Ok or short-circuit with Fail error. Preserves frames from the Failure.
- `Res<V, F>.orFail(mapError: (F) -> E): V` — unwrap or map error + short-circuit. Preserves frames.
- `Res<V, F>.orFail(mapping: ErrorMappingRail<F, E>): V` — delegates to `mapping(this)` (the in-rail invoke); a throwing `mapError` is wrapped in `ErrorMapperException`
- `ensure(condition, error: () -> E)` — short-circuits if false
- `ensureNotNull(value: V?, error: () -> E): V` — short-circuits if null

**Context-aware short-circuit:**
- `Res<V, E>.orFailContext(context: () -> String): V` — unwrap or short-circuit; appends `Frame(message=context())` to existing frames from the Failure. Lambda only evaluated on Fail.
- `Res<V, E>.orFailContext(context, location): V` — same with `SourceLocation`
- `withFrame(message: String, block: Rail<E>.() -> V): V` — runs block; catches FailException from this scope, appends frame, rethrows. Foreign-scope FailExceptions pass through without modification.
- `withFrame(message: String, location: () -> SourceLocation, block): V` — same with location (lazy, only evaluated on fail)

**Scope factories:**
- `catching(mapError: (Exception) -> E): ExceptionMappingRail<E>`
- `mapping(mapError: (D) -> E): ErrorMappingRail<D, E>`
- `catchingMapping(onError: (D) -> E, onException: (Exception) -> E): MappingRail<D, E>`

**Companion factories** (top-level, return Res instead of short-circuiting):
- `Rail.catching(...)`, `Rail.mapping(...)`, `Rail.catchingMapping(...)`
- `Rail.attempt(block: Rail<Exception>.() -> V): Res<V, Exception>` — convenience for `ExceptionMappingRail<Exception> { it }(block)`

### FailException

```kotlin
internal class FailException(
    val error: Any?,
    val scope: Rail<*>,
    val frames: List<Frame> = emptyList(),
) : Throwable(...)
```

**INVARIANT: Must extend Throwable directly, not Exception.** All exception-catching paths use `catch(Exception)`, which must not intercept `FailException`. Changing this hierarchy silently breaks rail control flow.

`fillInStackTrace()` returns `this` (no-op) — performance optimization since the exception is purely for control flow.

### ErrorMapperException

Thrown when a `mapError` lambda itself throws. Contains `originalException` (the error being mapped) and the mapper's exception as `cause`. Both are attached via `addSuppressed`.

## Mapping Scopes

All three scope types follow a dual-invoke pattern:
- **Top-level invoke** (extension function): creates its own `Rail`, returns `Res<V, E>`
- **Inside `rail {}` invoke** (member extension on `Rail`): uses the outer `Rail`, returns unwrapped `V`, short-circuits on failure

Kotlin member extension dispatch priority ensures the member extension wins inside `rail {}`. The
consequence is a **context-dependent return type**: the same scope-invoke call yields `V` inside a
`rail {}` block but `Res<V, E>` outside one — resolved by lexical context, not call syntax. The
compiler enforces the correct overload (a mismatch is a compile error), so it is type-safe but not
locally obvious; readers must note the enclosing scope.

### ExceptionMappingRail<E>

Catches JVM exceptions (subtypes of `Exception`), maps them to `E`.

Constructor: `ExceptionMappingRail(mapError: (Exception) -> E)`

**Member extension (inside rail):** `operator fun <V> ExceptionMappingRail<E>.invoke(block: Rail<E>.() -> V): V`
- Runs block on the outer Rail receiver
- `FailException` passes through (extends Throwable, not Exception)
- `CancellationException` rethrown
- Other exceptions caught → `fail(mapError(e))`
- If mapError itself throws → `ErrorMapperException`

**Top-level invoke:** `operator fun <V, E> ExceptionMappingRail<E>.invoke(block: Rail<E>.() -> V): Res<V, E>`
- Creates its own Rail scope
- FailException from own scope → `Res(Failure(e.error, e.frames))`
- FailException from foreign scope → rethrown
- CancellationException → rethrown
- Other exceptions → `Res.failure(mapError(e))`

### ErrorMappingRail<D, E>

Maps typed errors from domain `D` to `E`. Does NOT catch exceptions.

Constructor: `ErrorMappingRail(mapError: (D) -> E)`

**Member extension (inside rail):** `operator fun <V, D> ErrorMappingRail<D, E>.invoke(res: Res<V, D>): V`
- Unwraps Ok or maps the error via `mapError` and short-circuits.
- A throwing `mapError` is wrapped: `ErrorMapperException(IllegalStateException("mapError threw while mapping domain error: $d"), me)`, CancellationException rethrown.

**Alternative inside rail:** `Res<V, F>.orFail(mapping: ErrorMappingRail<F, E>): V`
- Preferred pattern — reads consistently with `orFail()` and `orFail { }`.
- Delegates to `mapping(this)` (the member-extension invoke), so it shares the same throwing-`mapError` wrapping.

**Top-level invoke:** `operator fun <V, D, E> ErrorMappingRail<D, E>.invoke(block: Rail<D>.() -> V): Res<V, E>`
- Creates own Rail<D> scope, catches FailException, maps error via mapError → `Res(Failure(mapError(e.error as D), e.frames))`
- A throwing `mapError` is wrapped the same way → `ErrorMapperException`.

**Throwing-`mapError` consistency:** all three entry points (top-level invoke, in-rail invoke, `orFail(mapping)`) wrap a throwing `mapError` in `ErrorMapperException` whose `originalException` is an `IllegalStateException` naming the domain error — never the internal `FailException`. This matches `MappingRail`'s `onError` handling. The low-level hand-rolled `orFail { mapError(it) }` form does **not** wrap — it propagates the mapper exception raw. Rule: named scope = wrapped, hand-rolled `orFail { }` = raw.

### MappingRail<D, E>

Combined exception catching + typed error mapping.

Constructor: `MappingRail(onError: (D) -> E, onException: (Exception) -> E)`

**Member extension (inside rail):** `operator fun <V, D> MappingRail<D, E>.invoke(block: Rail<E>.() -> Res<V, D>): V`
- Runs block → gets `Res<V, D>` → unwraps via `orFail { onError(it) }`
- Exceptions thrown **inside block** caught → `fail(onException(e))`
- CancellationException rethrown
- `onError`/`onException` have disjoint roles: `onException` handles only block exceptions; `onError`
  handles only the typed error. A throwing `onError` is a mapper bug → surfaces as `ErrorMapperException`
  (domain error in the message), **not** rerouted through `onException`. The `onError` mapping runs
  outside the block-exception `catch` so the two can't be confused.

**Top-level invoke:** returns `Res<V, E>`, creates own scope. Same disjoint mapper semantics.

## Validation

Result-kit ships **no error accumulator** (removed in 2.0.0). Accumulating all of a request's
validation errors is delegated to the JVM validation ecosystem (Jakarta Bean Validation, Konform,
Valiktor): run the validator, then map its result/violations into the domain error `E` inside a rail
(`ensure(violations.isEmpty()) { ... }`, or wrap a throwing validator in `catching { }`). In-rail
`ensure`/`ensureNotNull` cover ad-hoc fail-fast checks. `Validator`, `validation {}`,
`ValidationMapping`, `FramedError`, `zipOrAccumulate*`, and the `…Framed` iterable helpers no longer
exist.

## Error Context Chains

### Data Types

```kotlin
data class Frame(
    val message: String,
    val attachment: Any? = null,
    val location: SourceLocation? = null,
)

data class SourceLocation(
    val file: String,
    val line: Int,
    val function: String? = null,
)
// toString: "file:line" or "file:line in function"
```

### Storage

Frames are stored in `Failure.frames: List<Frame>` (default `emptyList()`). Also carried on `FailException.frames`.

**Frame ordering: append.** Index 0 = innermost/most-specific context (first `.context()` call, closest to the error). Higher indices = more general/outer context.

### How Frames Flow Through the System

| Operation | Frame behavior |
|---|---|
| `Res.failure(error)` | `frames = emptyList()` |
| `Res.ok(value)` | No Failure, no frames |
| `.context { msg }` on Fail | Creates new `Failure(error, oldFrames + newFrame)` |
| `.context { msg }` on Ok | No-op, returns `this` |
| `.mapError { transform }` | Creates new `Failure(newError, existingFrames)` — **preserves frames** |
| `.map { transform }` on Fail | Passes `Failure` through unchanged — **preserves frames** |
| `.flatMap { transform }` on Fail | Passes `Failure` through unchanged — **preserves frames** |
| `.flatten()` on outer Fail | Passes outer `Failure` through — **preserves frames** |
| `.contextFrame { frame }` on Fail | Appends a pre-built frame — **appends** |
| `.orElse { transform }` on Fail→Fail | Merges: `Failure(rec.error, original.frames + rec.frames)` — **preserves frames** |
| `.orElse { transform }` on Fail→Ok | Returns recovery Ok unchanged — frames discarded (Ok has none) |
| `.recover { transform }` on Fail | Returns Ok — frames discarded (Ok has none) |
| `.orFail()` inside rail | Throws `FailException(error, scope, inlineValue.frames)` — **preserves frames** |
| `.orFail(mapError)` inside rail | Throws `FailException(mapError(error), scope, inlineValue.frames)` — **preserves frames** |
| `.orFailContext { msg }` | Reads `inlineValue.frames`, appends new frame, throws with them — **correct** |
| `withFrame(msg) { block }` | Catches FailException from own scope, appends frame to `e.frames`, rethrows — **correct** |
| `rail {}` boundary catch | `Failure(e.error, e.frames)` — transfers frames from FailException |
| `ExceptionMappingRail` top-level catch | `Res(Failure(e.error, e.frames))` — transfers frames |
| `ErrorMappingRail` top-level catch | `Res(Failure(mapError(e.error), e.frames))` — transfers frames |
| `MappingRail` top-level catch | `Res(Failure(e.error, e.frames))` — transfers frames |
| Any mapping-rail **exception-caught** path | Caught `Exception` is not a `Failure` → new `Failure` with `emptyList()` — **no frames** (none existed) |
| `getOrThrow(attachFrames = true)` | Adds each frame to the thrown error as `addSuppressed(FrameTrace(..))` — **attaches** |
| `getOrThrow(attachFrames = false)` (default) | Throws error untouched — frames **dropped** |
| `.toResult()` / `.toResult(transform)` | stdlib `Result` has no frame slot — frames **dropped** |
| `zip(...)` fail-fast | Returns first failing branch's `Failure` unchanged — **preserves frames** |
| `combine()` / `tryMap` / `tryForEach` | Return first failing element's `Failure` unchanged — **preserves frames** |
| `filterFail()` / `partition()` | Extract only `E` — frames **dropped** |

### Rendering

- `contextChain(): List<Frame>` — returns `Failure.frames` or `emptyList()` on Ok
- `renderContext(): String` — multi-line: error toString, then numbered frames with locations and attachments
- `contextSummary(): String` — frames reversed (outermost-first) joined by ` → `, ending with error toString
- `contextMap(): Map<String, Any?>` — keys: `"error"`, `"frames"` (list of maps with `"message"`, optional `"location"`, optional `"attachment"`)
- `List<Frame>.findAttachment<T>(): T?` — reified, finds first attachment that `is T`

### Extended Fold

```kotlin
fun <V, E, T> Res<V, E>.fold(
    onOk: (V) -> T,
    onFail: (error: E, context: List<Frame>) -> T,
): T
```

Disambiguated from the standard `fold` by the two-parameter `onFail` lambda.

## KSP Module: @TraceContext

> **Status: detached from the build, not shipped.** `result-kit-ksp` is excluded from
> `settings.gradle.kts` `include(...)` and is not published. The code is kept on disk for a future
> release. The `@TraceContext`/`@TraceMessage`/`@TraceInclude` annotations have moved out of core into
> `result-kit-ksp/src/main/.../context/TraceAnnotations.kt`, so the core jar carries nothing
> KSP-related. The reference below describes the shelved processor; re-add `":result-kit-ksp"` to
> `settings.gradle.kts` to build/work on it.

### What It Generates

For an interface annotated `@TraceContext`:
1. A class `{InterfaceName}{suffix}` (default suffix: `"Traced"`)
2. Constructor takes `private val delegate: InterfaceName`
3. Implements the interface
4. Every `Res`-returning method: `delegate.method(args).context({ message }, { SourceLocation(...) })`
5. Non-Res methods: plain `delegate.method(args)` (no wrapping)
6. `suspend`, type parameters (including bounds), and all parameter types preserved

### Annotations

- `@TraceContext(suffix: String = "Traced")` — on interface only. Processor logs error on non-interface.
- `@TraceMessage(value: String)` — on method. Replaces auto-generated message. `{paramName}` → `$paramName` interpolation.
- `@TraceInclude` — on parameter. Opts value into auto-generated message.

### Message Generation

Default message: `"ClassName.method(param1, param2)"` — parameter names only, no values. This is secure by default (no PII leakage).

With `@TraceInclude` on a param: `"ClassName.method(param1=$param1, param2)"` — opted-in param shows value.

With `@TraceMessage("custom {param}")`: `"custom $param"` — full control.

### Source Location

Derived from KSP's `FileLocation`. Uses package-relative path for unambiguous identification in mono-repos. If KSP can't resolve a FileLocation (synthetic/binary sources), the location lambda is omitted and only `.context { message }` is generated.

### Processor Details

- `TraceContextProcessor` implements `SymbolProcessor`
- `TraceContextProcessorProvider` registered via `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`
- `toTypeName()` emits fully-qualified types (`kotlin.Int`, `kotlin.String`, etc.)
- Implicit `kotlin.Any?` upper bounds on type parameters are filtered out
- `equals`, `hashCode`, `toString` inherited from `Any` are skipped

### Known Limitations

- Default parameter values on interface methods — not reachable in KSP (interface methods can't have defaults), but not explicitly guarded
- Generated code uses fully-qualified type names (no imports beyond Res, SourceLocation, context)

## Composition: Zip

`zip(block1, block2, ..., transform)` — fail-fast sequential, arities 2-4. Blocks evaluated in order; short-circuits on first Fail. All blocks are `() -> Res<V, E>`. Preserves the failing branch's frames. (For accumulating all errors, use a JVM validation library — see "Validation".)

All have `callsInPlace` contracts (EXACTLY_ONCE for evaluated blocks, AT_MOST_ONCE for skippable ones and transform).

## Composition: Iterable Extensions

On `Iterable<Res<V, E>>`:
- `allOk(): Boolean` — true if every element is Ok (true for empty)
- `anyOk(): Boolean` — true if at least one Ok (false for empty)
- `anyFail(): Boolean` — true if at least one Fail (false for empty)
- `filterOk(): List<V>` — collects Ok values
- `filterFail(): List<E>` — collects Fail errors (frames dropped)
- `combine(): Res<List<V>, E>` — fail-fast collect (preserves the failing element's frames)
- `partition(): Pair<List<V>, List<E>>` — categorizes all elements (frames dropped)

On `Iterable<V>`:
- `tryMap(transform: (V) -> Res<U, E>): Res<List<U>, E>` — fail-fast map
- `tryForEach(action: (V) -> Res<*, E>): Res<Unit, E>` — fail-fast iteration

All use direct `inlineValue is Failure` checks for performance (no virtual dispatch).

## Design Invariants

These are critical rules. Violating them will silently break the library.

1. **`FailException` extends `Throwable`, not `Exception`.** All exception-catching code uses `catch(Exception)`. If FailException extended Exception, catching/mapping would intercept rail control flow.

2. **Scope identity check on catch.** Every `catch(FailException)` must check `e.scope !== scope` and rethrow if the exception belongs to a foreign scope. This prevents inner rail failures from being silently caught by outer rails.

3. **CancellationException always rethrown.** Every exception-catching path (catching, mapping, attempt, ErrorMappingRail when mapError throws) must rethrow `kotlin.coroutines.cancellation.CancellationException`. Uses stdlib FQN, not kotlinx, to avoid runtime dependency.

4. **Failure is internal.** User code cannot construct `Failure` instances. This makes `unsafeOk()` safe — user transform lambdas in `map`/`recover` cannot accidentally return a `Failure`. If `Failure` ever becomes accessible outside the module, all `unsafeOk` call sites must switch to `ok()`.

5. **Frame ordering is append.** Index 0 = innermost. `.context()` and `withFrame` both append to the end. `contextSummary()` reverses for display. Do not change the ordering convention.

6. **`mapError` preserves frames.** When transforming a Fail error, the new `Failure` must carry the existing frames list. This is how context survives error type changes.

7. **Zero runtime dependencies.** The core module must not depend on anything beyond the Kotlin stdlib. The KSP module depends on `symbol-processing-api` (compile-time only for consumers). Do not add runtime dependencies.

8. **Companion factories only.** `ok()`/`failure()` live on `Res.Companion`. `attempt()`/`catching()`/`mapping()`/`catchingMapping()` live on `Rail.Companion`. None are top-level functions.

9. **Scope types are standalone.** `ExceptionMappingRail`, `ErrorMappingRail`, `MappingRail` do not share an inheritance hierarchy. Methods are duplicated intentionally.

10. **`flatMap` is an escape hatch.** The DSL (`rail {}` + `orFail()`) is the preferred composition style. `flatMap` exists for use outside `rail {}` blocks but is not the primary API.

## Kotlin Compiler Settings

- `explicitApi()` — all public members must declare visibility and return types
- JVM toolchain 21 (builds on JDK 21), targets JVM 1.8 bytecode
- Kotlin 1.9.25
- `@RailDsl` marker annotation prevents implicit outer-scope receiver access in nested rail blocks
