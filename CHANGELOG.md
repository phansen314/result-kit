# Changelog

All notable changes to this project will be documented in this file. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0] - 2026-06-01

A complete redesign around the `Res<V, E>` inline value class and the `rail {}` DSL. This **fully supersedes the 1.0.0 API** — there is no incremental migration; adopt the new surface directly. The 1.x line was never published, so this is the first release since 1.0.0 and folds all of that work into one version.

### Added
- `Res<V, E>` — `@JvmInline value class` with zero allocation on the Ok path; `Res.ok` / `Res.failure` factories on the companion.
- `rail {}` DSL with `orFail`, `ensure`, `ensureNotNull`, and `fail`.
- Mapping scopes — `catching` (`ExceptionMappingRail`, catches JVM exceptions), `mapping` (`ErrorMappingRail`, maps typed errors), and `catchingMapping` (`MappingRail`, both) — usable top-level (return `Res`) or inside `rail {}` (unwrap or short-circuit). `Rail.attempt { }` convenience entry point.
- `zip` (fail-fast, arities 2–4) and collection extensions: `combine`, `partition`, `tryMap`, `tryForEach`, `allOk`, `anyOk`, `anyFail`, `filterOk`, `filterFail`.
- `kotlin.Result` interop: `toRes`, `toResult`, and `toResult(transform: (E) -> Throwable)` for non-throwable error types.
- Error context chains: `Frame`, `SourceLocation`, `.context { }`, `contextFrame`, `withFrame`, `orFailContext`, `contextChain`, `renderContext`, `contextSummary`, `contextMap`, `findAttachment`. Frames are preserved across `map`, `mapError`, `orFail`, `orElse`, and rail boundaries.
- `recover(transform: (E, List<Frame>) -> V)` overload (in `tech.codingzen.resultkit.context`) — observe frames before recovery discards them.
- `tap(onOk, onFail)` — combined Ok/Fail side effects in a single chain step; both lambdas default to no-ops.
- `getOrThrow(attachFrames: Boolean = false)` (both overloads) — opt-in attachment of context frames to the thrown error as suppressed `FrameTrace` entries so the breadcrumb chain survives the throw boundary. Off by default because attachment mutates the thrown error in place (not idempotent; unsafe for shared/`object` errors reused across results).
- `FrameTrace(frame: Frame)` — public `Throwable` view of a single `Frame` for the attachment above. Stack trace disabled; only the frame's message and location carry information.
- `binary-compatibility-validator` wired into the build: `./gradlew apiDump` captures the public ABI to `result-kit/api/result-kit.api`; `apiCheck` (part of `build`) fails on drift. Important because nearly all public APIs are `inline` and reference `@PublishedApi internal` symbols (`Failure`, `FailException`, `Res.unsafeOk`).
- `@CheckReturnValue` (JSR-305, `compileOnly`) on `Res.ok` / `Res.failure`: IDEs now warn when a `Res.failure(e)` result is discarded inside `rail {}` (the common "meant `fail(e)`" footgun). The published artifact stays zero-runtime-dependency.

### Behavior
- `FailException` extends `Throwable` directly (not `Exception`), so `catch(Exception)` in user code never intercepts rail control flow. `fillInStackTrace` is a zero-cost no-op by default and honours `-Dresultkit.debug=true` to capture a real trace when a `FailException` leaks past a `catch(Throwable)`.
- `orElse`: when fallible recovery itself fails, the original frames are merged ahead of the recovery's (`original.frames + rec.frames`) so the trail back to the original failure is preserved.

### Not included
- **No bundled error accumulator.** Collecting all of a request's validation errors is delegated to the mature JVM validation ecosystem (Jakarta Bean Validation, Konform, Valiktor): run the validator, then map its result into your error type inside a `rail {}` — see the [Validation section of the guide](docs/guide.md#validation). Fail-fast `zip` and the plain `Iterable<Res>` helpers cover the rest.
- **`result-kit-ksp` (`@TraceContext`) is not shipped.** The KSP traced-wrapper processor is kept on disk (`result-kit-ksp/`, detached from `settings.gradle.kts`) and may return as a standalone module later. Its `@TraceContext`/`@TraceMessage`/`@TraceInclude` annotations live in that module, so the core jar carries nothing KSP-related. Write `.context { }` / `withFrame` / `orFailContext` by hand instead.

## [1.0.0] - 2026-02-06

Initial release.

[2.0.0]: https://github.com/phansen314/result-kit/compare/v1.0.0...v2.0.0
[1.0.0]: https://github.com/phansen314/result-kit/releases/tag/v1.0.0
