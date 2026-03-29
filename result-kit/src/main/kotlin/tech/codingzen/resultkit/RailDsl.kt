package tech.codingzen.resultkit

/**
 * DSL marker for [Rail] scopes.
 *
 * Prevents implicit access to an outer [Rail] receiver from within a nested rail scope,
 * forcing callers to be explicit about which scope they intend to short-circuit.
 */
@DslMarker
public annotation class RailDsl
