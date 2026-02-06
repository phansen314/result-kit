package tech.codingzen.resultkit

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResDslTest {

  // -- res block basics --

  @Test
  fun `res returns Ok on success`() {
    val result = res { 42 }
    assertIs<Res.Ok<Int>>(result)
    assertEquals(42, result.value)
  }

  @Test
  fun `res returns UncaughtThrown for uncaught exception`() {
    val exc = RuntimeException("boom")
    val result = res { throw exc }
    assertIs<Res.Err.UncaughtThrown>(result)
    assertEquals(exc, result.uncaught)
  }

  // -- ok --

  @Test
  fun `ok unwraps Ok value`() {
    val result = res {
      val x = Res.value(10).ok()
      x + 5
    }
    assertIs<Res.Ok<Int>>(result)
    assertEquals(15, result.value)
  }

  @Test
  fun `ok short-circuits on Err Message`() {
    val result = res {
      val x: Int = Res.error("fail").ok()
      x + 1
    }
    assertIs<Res.Err.Message>(result)
    assertEquals("fail", result.message)
  }

  @Test
  fun `ok short-circuits on Err Thrown`() {
    val exc = RuntimeException("boom")
    val result = res {
      val x: Int = Res.error(exc, "context").ok()
      x + 1
    }
    assertIs<Res.Err.Thrown>(result)
    assertEquals(exc, result.exception)
    assertEquals("context", result.message)
  }

  // -- or --

  @Test
  fun `or returns value on Ok`() {
    val result = res {
      Res.value(10).or(99)
    }
    assertIs<Res.Ok<Int>>(result)
    assertEquals(10, result.value)
  }

  @Test
  fun `or returns default on Err`() {
    val result = res {
      Res.error("fail").or(99)
    }
    assertIs<Res.Ok<Int>>(result)
    assertEquals(99, result.value)
  }

  // -- err --

  @Test
  fun `err with message short-circuits with Message`() {
    val result = res {
      err("something broke")
      @Suppress("UNREACHABLE_CODE")
      42
    }
    assertIs<Res.Err.Message>(result)
    assertEquals("something broke", result.message)
  }

  @Test
  fun `err with exception and message short-circuits with Thrown`() {
    val exc = IllegalStateException("bad state")
    val result = res {
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
  fun `catch returns value when no exception`() {
    val result = res {
      val x = catch { 42 }
      x + 1
    }
    assertIs<Res.Ok<Int>>(result)
    assertEquals(43, result.value)
  }

  @Test
  fun `catch wraps exception as Thrown without message`() {
    val result = res {
      catch { throw RuntimeException("boom") }
    }
    assertIs<Res.Err.Thrown>(result)
    assertIs<RuntimeException>(result.exception)
    assertEquals("boom", result.exception.message)
    assertNull(result.message)
  }

  @Test
  fun `catch wraps exception as Thrown with message`() {
    val result = res {
      catch {
        message { "operation failed" }
        throw RuntimeException("boom")
      }
    }
    assertIs<Res.Err.Thrown>(result)
    assertEquals("operation failed", result.message)
  }

  @Test
  fun `catch message is lazy and only evaluated on failure`() {
    var evaluated = false
    val result = res {
      catch {
        message { evaluated = true; "should not evaluate" }
        42
      }
    }
    assertIs<Res.Ok<Int>>(result)
    assertEquals(false, evaluated)
  }

  @Test
  fun `catch uses most recent message`() {
    val result = res {
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
  fun `catch re-throws ErrException from inner ok`() {
    val result = res {
      catch {
        message { "catch context" }
        Res.error("inner error").ok()
      }
    }
    // The ErrException from ok() should pass through catch unchanged
    assertIs<Res.Err.Message>(result)
    assertEquals("inner error", result.message)
  }

  @Test
  fun `catch re-throws ErrException from inner err`() {
    val result = res {
      catch {
        message { "catch context" }
        err("explicit error")
      }
    }
    assertIs<Res.Err.Message>(result)
    assertEquals("explicit error", result.message)
  }

  // -- chaining --

  @Test
  fun `chained ok calls short-circuit on first error`() {
    val result = res {
      val a = Res.value(1).ok()
      val b: Int = Res.error("fail at b").ok()
      @Suppress("UNREACHABLE_CODE")
      a + b
    }
    assertIs<Res.Err.Message>(result)
    assertEquals("fail at b", result.message)
  }

  @Test
  fun `nested res blocks are independent`() {
    val result = res {
      val inner: Res<Int> = res {
        err("inner fail")
        @Suppress("UNREACHABLE_CODE")
        42
      }
      // Inner failure doesn't affect outer
      inner.or(0)
    }
    assertIs<Res.Ok<Int>>(result)
    assertEquals(0, result.value)
  }

  @Test
  fun `multiple catch blocks work independently`() {
    val result = res {
      val a = catch {
        message { "a failed" }
        10
      }
      val b = catch {
        message { "b failed" }
        20
      }
      a + b
    }
    assertIs<Res.Ok<Int>>(result)
    assertEquals(30, result.value)
  }

  @Test
  fun `second catch short-circuits after first succeeds`() {
    val result = res {
      val a = catch { 10 }
      catch {
        message { "b failed" }
        throw IllegalStateException("boom")
      }
      @Suppress("UNREACHABLE_CODE")
      a
    }
    assertIs<Res.Err.Thrown>(result)
    assertEquals("b failed", result.message)
  }

  // -- top-level catch --

  @Test
  fun `top-level catch returns Ok on success`() {
    val result = catch { 42 }
    assertIs<Res.Ok<Int>>(result)
    assertEquals(42, result.value)
  }

  @Test
  fun `top-level catch returns Thrown on exception`() {
    val exc = RuntimeException("boom")
    val result = catch { throw exc }
    assertIs<Res.Err.Thrown>(result)
    assertSame(exc, result.exception)
    assertNull(result.message)
  }

  @Test
  fun `top-level catch returns Thrown with message`() {
    val result = catch {
      message { "operation failed" }
      throw RuntimeException("boom")
    }
    assertIs<Res.Err.Thrown>(result)
    assertEquals("operation failed", result.message)
  }

  @Test
  fun `top-level catch message is lazy`() {
    var evaluated = false
    val result = catch {
      message { evaluated = true; "should not evaluate" }
      42
    }
    assertIs<Res.Ok<Int>>(result)
    assertEquals(false, evaluated)
  }

  @Test
  fun `top-level catch uses most recent message`() {
    val result = catch {
      message { "first" }
      val step1 = "done"
      message { "second after $step1" }
      throw RuntimeException("boom")
    }
    assertIs<Res.Err.Thrown>(result)
    assertEquals("second after done", result.message)
  }
}
