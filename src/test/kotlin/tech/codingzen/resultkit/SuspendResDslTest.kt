package tech.codingzen.resultkit

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SuspendResDslTest {

  // -- suspendRes block basics --

  @Test
  fun `suspendRes returns Ok on success`() = runTest {
    val result = suspendRes { 42 }
    assertIs<Res.Ok<Int>>(result)
    assertEquals(42, result.value)
  }

  @Test
  fun `suspendRes returns UncaughtThrown for uncaught exception`() = runTest {
    val exc = RuntimeException("boom")
    val result = suspendRes { throw exc }
    assertIs<Res.Err.UncaughtThrown>(result)
    assertEquals(exc, result.uncaught)
  }

  // -- ok --

  @Test
  fun `ok unwraps Ok value`() = runTest {
    val result = suspendRes {
      val x = Res.value(10).ok()
      x + 5
    }
    assertIs<Res.Ok<Int>>(result)
    assertEquals(15, result.value)
  }

  @Test
  fun `ok short-circuits on Err Message`() = runTest {
    val result = suspendRes {
      val x: Int = Res.error("fail").ok()
      x + 1
    }
    assertIs<Res.Err.Message>(result)
    assertEquals("fail", result.message)
  }

  @Test
  fun `ok short-circuits on Err Thrown`() = runTest {
    val exc = RuntimeException("boom")
    val result = suspendRes {
      val x: Int = Res.error(exc, "context").ok()
      x + 1
    }
    assertIs<Res.Err.Thrown>(result)
    assertEquals(exc, result.exception)
    assertEquals("context", result.message)
  }

  // -- or --

  @Test
  fun `or returns value on Ok`() = runTest {
    val result = suspendRes {
      Res.value(10).or(99)
    }
    assertIs<Res.Ok<Int>>(result)
    assertEquals(10, result.value)
  }

  @Test
  fun `or returns default on Err`() = runTest {
    val result = suspendRes {
      Res.error("fail").or(99)
    }
    assertIs<Res.Ok<Int>>(result)
    assertEquals(99, result.value)
  }

  // -- err --

  @Test
  fun `err with message short-circuits with Message`() = runTest {
    val result = suspendRes {
      err("something broke")
      @Suppress("UNREACHABLE_CODE")
      42
    }
    assertIs<Res.Err.Message>(result)
    assertEquals("something broke", result.message)
  }

  @Test
  fun `err with exception and message short-circuits with Thrown`() = runTest {
    val exc = IllegalStateException("bad state")
    val result = suspendRes {
      err(exc, "context info")
      @Suppress("UNREACHABLE_CODE")
      42
    }
    assertIs<Res.Err.Thrown>(result)
    assertEquals(exc, result.exception)
    assertEquals("context info", result.message)
  }

  // -- catch --

  @Test
  fun `catch returns value when no exception`() = runTest {
    val result = suspendRes {
      val x = catch { 42 }
      x + 1
    }
    assertIs<Res.Ok<Int>>(result)
    assertEquals(43, result.value)
  }

  @Test
  fun `catch wraps exception as Thrown without message`() = runTest {
    val result = suspendRes {
      catch { throw RuntimeException("boom") }
    }
    assertIs<Res.Err.Thrown>(result)
    assertIs<RuntimeException>(result.exception)
    assertNull(result.message)
  }

  @Test
  fun `catch wraps exception as Thrown with message`() = runTest {
    val result = suspendRes {
      catch {
        message { "operation failed" }
        throw RuntimeException("boom")
      }
    }
    assertIs<Res.Err.Thrown>(result)
    assertEquals("operation failed", result.message)
  }

  @Test
  fun `catch message is lazy and only evaluated on failure`() = runTest {
    var evaluated = false
    val result = suspendRes {
      catch {
        message { evaluated = true; "should not evaluate" }
        42
      }
    }
    assertIs<Res.Ok<Int>>(result)
    assertEquals(false, evaluated)
  }

  @Test
  fun `catch uses most recent message`() = runTest {
    val result = suspendRes {
      catch {
        message { "first" }
        val step1 = "done"
        message { "second after $step1" }
        throw RuntimeException("boom")
      }
    }
    assertIs<Res.Err.Thrown>(result)
    assertEquals("second after done", result.message)
  }

  @Test
  fun `catch re-throws ErrException from inner ok`() = runTest {
    val result = suspendRes {
      catch {
        message { "catch context" }
        Res.error("inner error").ok()
      }
    }
    assertIs<Res.Err.Message>(result)
    assertEquals("inner error", result.message)
  }

  // -- suspend-specific --

  @Test
  fun `suspendRes works with suspend calls`() = runTest {
    suspend fun fetchValue(): Res<Int> = Res.value(42)

    val result = suspendRes {
      val x = fetchValue().ok()
      x * 2
    }
    assertIs<Res.Ok<Int>>(result)
    assertEquals(84, result.value)
  }

  @Test
  fun `suspendRes works with suspend calls that return errors`() = runTest {
    suspend fun fetchValue(): Res<Int> = Res.error("remote failure")

    val result = suspendRes {
      val x = fetchValue().ok()
      x * 2
    }
    assertIs<Res.Err.Message>(result)
    assertEquals("remote failure", result.message)
  }

  @Test
  fun `chained ok calls short-circuit on first error`() = runTest {
    val result = suspendRes {
      val a = Res.value(1).ok()
      val b: Int = Res.error("fail at b").ok()
      @Suppress("UNREACHABLE_CODE")
      a + b
    }
    assertIs<Res.Err.Message>(result)
    assertEquals("fail at b", result.message)
  }
}
