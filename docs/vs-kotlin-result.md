# Result-Kit vs kotlin-result

> **TODO:** This comparison is planned but not yet written.
>
> Comparison with [kotlin-result](https://github.com/michaelbull/kotlin-result) by Michael Bull.
>
> Topics to cover:
> - Typed errors: `Res<V, E>` vs `Result<V, E>` (same concept, different API shapes)
> - DSL: `rail {}` + `orFail()` vs `binding {}` (coroutine-based short-circuit)
> - Inline value class (`Res`) vs sealed class (`Result`)
> - Error mapping scopes (`catching`, `mapping`, `catchingMapping`) vs manual `mapError`/`get`/`getError`
> - Composition: `zip`/`zipOrAccumulate` vs `zip`/`zipOrAccumulateWith`
> - Iterable extensions comparison
> - Exception handling: `Rail.attempt` vs `runCatching`
> - Dependency footprint: zero deps vs coroutines dependency for `binding`
> - Multiplatform support
> - Community adoption and maturity
