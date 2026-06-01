# Changelog

All notable changes to this project will be documented in this file. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **`binary-compatibility-validator` plugin** wired into the build. `./gradlew apiDump` captures the public ABI to `result-kit/api/result-kit.api`; `apiCheck` (part of `build`) fails CI if the captured surface drifts from what's committed. Important because nearly all public APIs are `inline` and reference `@PublishedApi internal` symbols (`Failure`, `FailException`, `Res.unsafeOk`) — any silent change to those breaks downstream jars compiled against an older shape.
- `tap(onOk, onFail)` — combined Ok/Fail side effects in a single chain step. Both lambdas default to no-ops.
- `Res<V, E>.toResult(transform: (E) -> Throwable)` overload for converting to `kotlin.Result` when `E` is not a `Throwable`.
- `Validator.checkOr(default, res)` (and extension form `Res.checkOr(default)`) — non-null variant of `valueOrNull` that returns a sane fallback on Fail so dependent validations don't have to null-check.
- `Res<V, E>.recover(transform: (E, List<Frame>) -> V)` overload (in `tech.codingzen.resultkit.context`) — observe frames before they're discarded by recovery, useful for logging "we recovered from X with context Y" without splitting into `.tap` + `.recover`.
- `FrameTrace(frame: Frame)` — public `Throwable` view of a single [Frame] used to attach context to thrown errors via [Throwable.addSuppressed]. Stack-trace disabled; only the frame's message and location carry information.
- **`FramedError<E>(error, frames)` and frame-retaining accumulators.** Accumulation paths collapse many failures into one `List<E>`, which has no per-error slot for context frames, so they drop them. The new `FramedError` carrier pairs each error with its frames, and opt-in `…Framed` siblings return `List<FramedError<E>>`: `zipOrAccumulateFramed` (arities 2–4), `Validator.errorsFramed()` / `toResFramed()`, `validationFramed { }`, the rail member `Validator<F>.orFailFramed { }`, and `Iterable.filterFailFramed()` / `partitionFramed()`. The existing `List<E>` paths (`zipOrAccumulate`, `validation`, `toRes`, `filterFail`, `partition`) are byte-for-byte unchanged — `Validator` keeps a lazily-allocated sparse frame side-table, so `ensure`-only validation pays no extra allocation. Unlike the frames-ignoring equality of the internal failure sentinel, `FramedError` is a data class whose frames participate in `equals`/`hashCode`.

### Changed
- **`FailException.fillInStackTrace` now honours `-Dresultkit.debug=true`.** Default behavior remains a zero-cost no-op for the control-flow hot path; when the system property is set on the JVM command line, a real stack trace is captured so a stray `FailException` (e.g. one leaking past a `catch(Throwable)` interceptor) can be traced to its origin.
- **`Res.ok` and `Res.failure` carry `@CheckReturnValue` (JSR-305).** IntelliJ now warns when a `Res.failure(e)` expression is discarded inside `rail {}` — the most common footgun where a developer means `fail(e)` but writes the factory and drops the value. JSR-305 is a `compileOnly` dependency; the published artifact stays zero-runtime-dep.
- **`getOrThrow` frame attachment is now opt-in via `attachFrames` (default `false`).** Both overloads gain a leading `attachFrames: Boolean = false` parameter — `getOrThrow(attachFrames = false)` and `getOrThrow(attachFrames = false) { transform }`. With `attachFrames = true`, context frames are added as suppressed [FrameTrace] entries so the breadcrumb chain survives into stack-trace dumps. It defaults to **off** because attachment mutates the thrown error in place — not idempotent, and unsafe for shared/`object` errors reused across results (suppressed entries would accumulate and intermix on the shared instance). Pre-1.0 callers relying on the old always-attach behavior must now pass `attachFrames = true`.
- **Mapping-scope rename.** `FailMappingRail` → `ExceptionMappingRail`; factories `failMapping` → `catching`, `errorMapping` → `mapping`, and the two-arg `mapping(onError, onException)` → `catchingMapping(onError, onException)`. The "Fail" vs "Error" distinction was invisible at call sites; the new names make the verb (`catching` for exceptions) and noun (typed-error `mapping`) explicit. Class names `ErrorMappingRail` and `MappingRail` are unchanged.
- **`withContext` renamed to `withFrame`** to avoid collision with `kotlinx.coroutines.withContext`. The rail-DSL operation appends a frame to any failure that short-circuits out of its block — `withFrame` reads correctly and doesn't import-shadow.
- **`Validator.checkOrNull` renamed to `valueOrNull`** to signal the footgun: the `OrNull` suffix now matches the rest of the Kotlin ecosystem (e.g. `getOrNull`, `firstOrNull`), making the nullable-return surface obvious at call sites. Behavior unchanged.
- `orElse`: when fallible recovery itself fails, the original frames are now merged with the recovery's frames (`original.frames + rec.frames`) so the trail back to the original failure is preserved. Previously the original frames were discarded.

### Removed
- **`result-kit-ksp` (`@TraceContext`) is no longer part of the build or shipped.** The KSP traced-wrapper processor — a convenience layer that auto-generated the `.context(...)` decorators you can write by hand with `.context { }` — was ~28% of the codebase and the dominant maintenance/bug surface, for a feature with no demand yet. It is kept on disk under `result-kit-ksp/` (detached from `settings.gradle.kts`) and may return as a standalone module in a future release. The `@TraceContext`/`@TraceMessage`/`@TraceInclude` annotations moved out of core into that module, so the core jar carries nothing KSP-related. Manual `.context { }` / `withFrame` / `orFailContext` are unaffected.

### Fixed
- `withFrame` now performs the same scope-identity check as the `rail {}` boundary, so a `FailException` from a foreign rail scope passes through without having a context frame appended.

## [1.1.0]

Major redesign around the `Res<V, E>` inline value class and the `rail {}` DSL. **This release is a one-time semver break from 1.0.0** — the pre-1.0 era is closed, and 1.x onwards follows semver strictly. See [Stability](README.md#stability) for the policy going forward.

### Added
- `Res<V, E>` `@JvmInline value class` (zero allocation on the Ok path); `Res.ok` / `Res.failure` factories on the companion.
- `rail {}` DSL with `orFail`, `ensure`, `ensureNotNull`, `fail`.
- Mapping scopes — `catching`, `mapping`, `catchingMapping` — usable both top-level (return `Res`) and inside `rail {}` (unwrap or short-circuit).
- `Validator` / `validation {}` for accumulating multiple errors; `ValidationMapping` bridges accumulated errors into a rail.
- `zip` / `zipOrAccumulate` (arities 2–4); collection extensions (`combine`, `partition`, `tryMap`, `tryForEach`, `allOk`, `anyOk`, `anyFail`, `filterOk`, `filterFail`).
- `Result` interop (`toRes`, `toResult`).
- Error context chains: `Frame`, `SourceLocation`, `.context { }`, `withFrame`, `orFailContext`, `contextChain`, `renderContext`, `contextSummary`, `contextMap`, `findAttachment`. Frames preserved across `mapError`, `orFail`, and rail boundaries.

### Changed
- `FailException` now extends `Throwable` (not `Exception`) so `catch(Exception)` in user code does not intercept rail control flow.
- `mapping` mapper consistency: `mapError` lambdas receive the source error type uniformly across scopes.

### Removed
- `MappingFactory` and `Res` `componentN` destructuring functions.

## [1.0.0]

Initial release.

[Unreleased]: https://github.com/phansen314/result-kit/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/phansen314/result-kit/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/phansen314/result-kit/releases/tag/v1.0.0
