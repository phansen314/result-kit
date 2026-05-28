# Changelog

All notable changes to this project will be documented in this file. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **`binary-compatibility-validator` plugin** wired into the build. `./gradlew apiDump` captures the public ABI to `result-kit/api/*.api` (and the KSP module's); `apiCheck` (part of `build`) fails CI if the captured surface drifts from what's committed. Important because nearly all public APIs are `inline` and reference `@PublishedApi internal` symbols (`Failure`, `FailException`, `Res.unsafeOk`) — any silent change to those breaks downstream jars compiled against an older shape.
- `tap(onOk, onFail)` — combined Ok/Fail side effects in a single chain step. Both lambdas default to no-ops.
- `Res<V, E>.toResult(transform: (E) -> Throwable)` overload for converting to `kotlin.Result` when `E` is not a `Throwable`.
- `Validator.checkOr(default, res)` (and extension form `Res.checkOr(default)`) — non-null variant of `valueOrNull` that returns a sane fallback on Fail so dependent validations don't have to null-check.
- `Res<V, E>.recover(transform: (E, List<Frame>) -> V)` overload (in `tech.codingzen.resultkit.context`) — observe frames before they're discarded by recovery, useful for logging "we recovered from X with context Y" without splitting into `.tap` + `.recover`.
- `FrameTrace(frame: Frame)` — public `Throwable` view of a single [Frame] used to attach context to thrown errors via [Throwable.addSuppressed]. Stack-trace disabled; only the frame's message and location carry information.

### Changed
- **`FailException.fillInStackTrace` now honours `-Dresultkit.debug=true`.** Default behavior remains a zero-cost no-op for the control-flow hot path; when the system property is set on the JVM command line, a real stack trace is captured so a stray `FailException` (e.g. one leaking past a `catch(Throwable)` interceptor) can be traced to its origin.
- **`Res.ok` and `Res.failure` carry `@CheckReturnValue` (JSR-305).** IntelliJ now warns when a `Res.failure(e)` expression is discarded inside `rail {}` — the most common footgun where a developer means `fail(e)` but writes the factory and drops the value. JSR-305 is a `compileOnly` dependency; the published artifact stays zero-runtime-dep.
- **`getOrThrow()` and `getOrThrow { transform }` now attach context frames as suppressed exceptions** ([FrameTrace] entries) on the thrown error. Frames previously were discarded at the JVM throw boundary; they now survive into standard stack-trace dumps so the breadcrumb chain remains visible.
- **Mapping-scope rename.** `FailMappingRail` → `ExceptionMappingRail`; factories `failMapping` → `catching`, `errorMapping` → `mapping`, and the two-arg `mapping(onError, onException)` → `catchingMapping(onError, onException)`. The "Fail" vs "Error" distinction was invisible at call sites; the new names make the verb (`catching` for exceptions) and noun (typed-error `mapping`) explicit. Class names `ErrorMappingRail` and `MappingRail` are unchanged.
- **`withContext` renamed to `withFrame`** to avoid collision with `kotlinx.coroutines.withContext`. The rail-DSL operation appends a frame to any failure that short-circuits out of its block — `withFrame` reads correctly and doesn't import-shadow.
- **`Validator.checkOrNull` renamed to `valueOrNull`** to signal the footgun: the `OrNull` suffix now matches the rest of the Kotlin ecosystem (e.g. `getOrNull`, `firstOrNull`), making the nullable-return surface obvious at call sites. Behavior unchanged.
- `orElse`: when fallible recovery itself fails, the original frames are now merged with the recovery's frames (`original.frames + rec.frames`) so the trail back to the original failure is preserved. Previously the original frames were discarded.

### Fixed
- KSP `@TraceContext` processor: type-parameter handling — generated wrappers preserve generic signatures and bounds correctly, including for `suspend` methods.
- **KSP `@TraceContext` correctness sweep** (closes silent-codegen failures consumers would hit at compile time):
  - **Extension-receiver methods** (e.g. `fun String.parse(): Res<...>`) are now correctly wrapped. Previously `fn.extensionReceiver` was ignored, emitting a non-extension override that failed to implement the interface. The wrapper now emits the receiver and delegates via `with(delegate) { this@<fn>.<fn>(args) }`.
  - **Reserved-keyword parameter names** (`fun`, `class`, `in`, etc.) are now backtick-wrapped in both the signature and the delegate-call args. Previously emitted as bare identifiers → consumer's compile failed with a syntax error.
  - **Windows path handling**: file paths are normalized from `\` to `/` before the package-relative path heuristic runs, and emitted `SourceLocation` literals escape any remaining `\` or `"` characters. Previously the heuristic always assumed forward slashes — broken on Windows builds.
  - **Incremental builds**: `Dependencies(aggregating = false, ...)` (isolating mode) instead of `true`. KSP no longer reprocesses every wrapper when one unrelated annotated interface changes.
  - **Unresolvable return type**: the processor now KSP-errors instead of silently falling back to `Unit`. Avoids a generated method whose return type contradicts the interface.
  - **`@TraceMessage` / `@TraceInclude` interpolation** uses `${name}` (braces) form so keyword parameter references resolve correctly.
- `withFrame` now performs the same scope-identity check as the `rail {}` boundary, so a `FailException` from a foreign rail scope passes through without having a context frame appended.

## [1.1.0]

Major redesign around the `Res<V, E>` inline value class and the `rail {}` DSL. Not backwards-compatible with 1.0.0.

### Added
- `Res<V, E>` `@JvmInline value class` (zero allocation on the Ok path); `Res.ok` / `Res.failure` factories on the companion.
- `rail {}` DSL with `orFail`, `ensure`, `ensureNotNull`, `fail`.
- Mapping scopes — `catching`, `mapping`, `catchingMapping` — usable both top-level (return `Res`) and inside `rail {}` (unwrap or short-circuit).
- `Validator` / `validation {}` for accumulating multiple errors; `ValidationMapping` bridges accumulated errors into a rail.
- `zip` / `zipOrAccumulate` (arities 2–4); collection extensions (`combine`, `partition`, `tryMap`, `tryForEach`, `allOk`, `anyOk`, `anyFail`, `filterOk`, `filterFail`).
- `Result` interop (`toRes`, `toResult`).
- Error context chains: `Frame`, `SourceLocation`, `.context { }`, `withFrame`, `orFailContext`, `contextChain`, `renderContext`, `contextSummary`, `contextMap`, `findAttachment`. Frames preserved across `mapError`, `orFail`, and rail boundaries.
- `result-kit-ksp` module: `@TraceContext` interface annotation generates `{Interface}Traced` decorators that auto-attach context to every `Res`-returning method. Customisable via `@TraceMessage`, opt-in parameter values via `@TraceInclude`. Secure-by-default — parameter values omitted unless annotated.

### Changed
- `FailException` now extends `Throwable` (not `Exception`) so `catch(Exception)` in user code does not intercept rail control flow.
- `mapping` mapper consistency: `mapError` lambdas receive the source error type uniformly across scopes.
- KSP processor: type-parameter bounds and source locations resolved correctly; package-relative paths in generated `SourceLocation` for unambiguous identification in mono-repos.

### Removed
- `MappingFactory` and `Res` `componentN` destructuring functions.

## [1.0.0]

Initial release.

[Unreleased]: https://github.com/phansen314/result-kit/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/phansen314/result-kit/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/phansen314/result-kit/releases/tag/v1.0.0
