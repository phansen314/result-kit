# Changelog

All notable changes to this project will be documented in this file. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.0]

Major redesign around the `Res<V, E>` inline value class and the `rail {}` DSL. Not backwards-compatible with 1.0.0.

### Added
- `Res<V, E>` `@JvmInline value class` (zero allocation on the Ok path); `Res.ok` / `Res.failure` factories on the companion.
- `rail {}` DSL with `orFail`, `ensure`, `ensureNotNull`, `fail`.
- Mapping scopes — `failMapping`, `errorMapping`, `mapping` — usable both top-level (return `Res`) and inside `rail {}` (unwrap or short-circuit).
- `Validator` / `validation {}` for accumulating multiple errors; `ValidationMapping` bridges accumulated errors into a rail.
- `zip` / `zipOrAccumulate` (arities 2–4); collection extensions (`combine`, `partition`, `tryMap`, `tryForEach`, `allOk`, `anyOk`, `anyFail`, `filterOk`, `filterFail`).
- `Result` interop (`toRes`, `toResult`).
- Error context chains: `Frame`, `SourceLocation`, `.context { }`, `withContext`, `orFailContext`, `contextChain`, `renderContext`, `contextSummary`, `contextMap`, `findAttachment`. Frames preserved across `mapError`, `orFail`, and rail boundaries.
- `result-kit-ksp` module: `@TraceContext` interface annotation generates `{Interface}Traced` decorators that auto-attach context to every `Res`-returning method. Customisable via `@TraceMessage`, opt-in parameter values via `@TraceInclude`. Secure-by-default — parameter values omitted unless annotated.

### Changed
- `FailException` now extends `Throwable` (not `Exception`) so `catch(Exception)` in user code does not intercept rail control flow.
- `errorMapping` mapper consistency: `mapError` lambdas receive the source error type uniformly across scopes.
- KSP processor: type-parameter bounds and source locations resolved correctly; package-relative paths in generated `SourceLocation` for unambiguous identification in mono-repos.

### Removed
- `MappingFactory` and `Res` `componentN` destructuring functions.

## [1.0.0]

Initial release.

[Unreleased]: https://github.com/phansen314/result-kit/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/phansen314/result-kit/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/phansen314/result-kit/releases/tag/v1.0.0
