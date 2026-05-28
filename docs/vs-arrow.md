# Result-Kit vs Arrow

> **TODO:** This comparison is planned but not yet written.
>
> Comparison with [Arrow](https://arrow-kt.io), specifically Arrow's typed error handling.
>
> Topics to cover:
> - `Res<V, E>` vs `Either<E, A>` (parameter order, naming conventions, `Left`/`Right` semantics)
> - Inline value class vs sealed class (allocation, `when` exhaustiveness)
> - `rail {}` vs `either {}`/Raise DSL (short-circuit mechanisms)
> - Error mapping: `catching`/`mapping`/`catchingMapping` vs `withError`/`catch`
> - Exception handling: `catching` vs `Effect`/`catch`
> - Dependency footprint: zero deps vs Arrow ecosystem (`arrow-core`, `arrow-fx-coroutines`, etc.)
> - Error accumulation: `zipOrAccumulate` vs Arrow's `zipOrAccumulate`/`mapOrAccumulate`
> - Learning curve and API surface size
> - Ecosystem: Arrow provides optics, resilience, serialization — Result-Kit is focused solely on error handling
> - When to use which: Result-Kit for lightweight typed errors; Arrow for a comprehensive FP toolkit
