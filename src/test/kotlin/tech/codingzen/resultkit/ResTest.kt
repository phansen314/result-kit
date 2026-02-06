package tech.codingzen.resultkit

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResTest {

  @Test
  fun `value creates Ok`() {
    val result = Res.value(42)
    assertIs<Res.Ok<Int>>(result)
    assertEquals(42, result.value)
  }

  @Test
  fun `error with message creates Message`() {
    val result = Res.error("something failed")
    assertIs<Res.Err.Message>(result)
    assertEquals("something failed", result.message)
  }

  @Test
  fun `error with exception creates Thrown`() {
    val exc = RuntimeException("boom")
    val result = Res.error(exc)
    assertIs<Res.Err.Thrown>(result)
    assertEquals(exc, result.exception)
    assertNull(result.message)
  }

  @Test
  fun `error with exception and message creates Thrown with message`() {
    val exc = RuntimeException("boom")
    val result = Res.error(exc, "context")
    assertIs<Res.Err.Thrown>(result)
    assertEquals(exc, result.exception)
    assertEquals("context", result.message)
  }
}
