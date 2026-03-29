package tech.codingzen.resultkit

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValidationMappingTest {

    // -- top-level invoke --

    @Test
    fun `top-level invoke returns Ok when no errors`() {
        val validate = ValidationMapping<String, String> { errors -> errors.joinToString() }
        val result: Res<Unit, String> = validate {
            ensure(true) { "should not appear" }
        }
        assertTrue(result.isOk)
    }

    @Test
    fun `top-level invoke returns Fail with mapped error when errors`() {
        val validate = ValidationMapping<String, String> { errors -> "Errors: ${errors.joinToString()}" }
        val result: Res<Unit, String> = validate {
            ensure(false) { "a" }
            ensure(false) { "b" }
        }
        assertTrue(result.isFail)
        assertEquals("Errors: a, b", result.errorOrThrow())
    }

    // -- Rail.validation factory --

    @Test
    fun `Rail companion validation creates a ValidationMapping`() {
        val validate = Rail.validation<String, String> { errors -> errors.joinToString() }
        val result: Res<Unit, String> = validate {
            ensure(false) { "err" }
        }
        assertTrue(result.isFail)
        assertEquals("err", result.errorOrThrow())
    }

    // -- inside rail {} blocks --

    @Test
    fun `inside rail, validation factory creates ValidationMapping`() {
        val result = rail<Int, String> {
            val validate = validation<String> { errors -> errors.joinToString("; ") }
            validate {
                ensure(true) { "should not fail" }
            }
            42
        }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `inside rail, ValidationMapping invoke short-circuits on errors`() {
        val result = rail<Int, String> {
            val validate = validation<String> { errors -> errors.joinToString("; ") }
            validate {
                ensure(false) { "first" }
                ensure(false) { "second" }
            }
            42 // should not reach here
        }
        assertTrue(result.isFail)
        assertEquals("first; second", result.errorOrThrow())
    }

    @Test
    fun `inside rail, ValidationMapping invoke does nothing when all ensures pass`() {
        val result = rail<Int, String> {
            val validate = validation<String> { errors -> errors.joinToString() }
            validate {
                ensure(true) { "a" }
                ensure(true) { "b" }
            }
            99
        }
        assertTrue(result.isOk)
        assertEquals(99, result.getOrNull())
    }

    @Test
    fun `same ValidationMapping reused across multiple blocks in one rail`() {
        val result = rail<Int, String> {
            val validate = validation<String> { errors -> errors.joinToString("; ") }

            // First block passes
            validate {
                ensure(true) { "should not appear" }
            }

            // Second block fails
            validate {
                ensure(false) { "from second block" }
            }
            42
        }
        assertTrue(result.isFail)
        assertEquals("from second block", result.errorOrThrow())
    }

    // -- Validator.orFail inside rail --

    @Test
    fun `Validator orFail short-circuits when validator has errors`() {
        val result = rail<Int, String> {
            val v = Validator.validator<String>()
            v.ensure(false) { "err1" }
            v.ensure(false) { "err2" }
            v.orFail { errors -> errors.joinToString("; ") }
            42
        }
        assertTrue(result.isFail)
        assertEquals("err1; err2", result.errorOrThrow())
    }

    @Test
    fun `Validator orFail does nothing when validator is clean`() {
        val result = rail<Int, String> {
            val v = Validator.validator<String>()
            v.ensure(true) { "should not appear" }
            v.orFail { errors -> errors.joinToString() }
            42
        }
        assertTrue(result.isOk)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `imperative pattern with interleaved ensure calls and code`() {
        val name = "  Al  "
        val email = "al@example.com"

        val result = rail<String, String> {
            val v = Validator.validator<String>()
            v.ensure(name.isNotBlank()) { "Name required" }
            val trimmed = name.trim()
            v.ensure(trimmed.length >= 2) { "Name too short" }
            v.ensure(email.contains('@')) { "Invalid email" }
            v.orFail { errors -> errors.joinToString("; ") }
            "$trimmed <$email>"
        }
        assertTrue(result.isOk)
        assertEquals("Al <al@example.com>", result.getOrNull())
    }

    @Test
    fun `imperative pattern fails with all accumulated errors`() {
        val name = "   "
        val email = "bad-email"

        val result = rail<String, String> {
            val v = Validator.validator<String>()
            v.ensure(name.isNotBlank()) { "Name required" }
            val trimmed = name.trim()
            v.ensure(trimmed.length >= 2) { "Name too short" }
            v.ensure(email.contains('@')) { "Invalid email" }
            v.orFail { errors -> errors.joinToString("; ") }
            "$trimmed <$email>"
        }
        assertTrue(result.isFail)
        assertEquals("Name required; Name too short; Invalid email", result.errorOrThrow())
    }

    // -- coroutine support --

    @Test
    fun `validation works inside suspend rail blocks`() = runTest {
        val result = rail<Int, String> {
            val validate = validation<String> { errors -> errors.joinToString() }
            validate {
                ensure(false) { "async error" }
            }
            42
        }
        assertTrue(result.isFail)
        assertEquals("async error", result.errorOrThrow())
    }

    // -- interaction with other rail operations --

    @Test
    fun `validation and orFail compose in the same rail`() {
        val result = rail<Int, String> {
            // Validation block
            val validate = validation<String> { errors -> errors.joinToString() }
            validate {
                ensure(true) { "should pass" }
            }

            // Normal rail operation
            val x = Res.ok(10).orFail()
            x + 5
        }
        assertTrue(result.isOk)
        assertEquals(15, result.getOrNull())
    }

    @Test
    fun `orFail short-circuits before validation block runs`() {
        var validationRan = false
        val result = rail<Int, String> {
            Res.failure("early fail").orFail()

            val validate = validation<String> { errors -> errors.joinToString() }
            validate {
                validationRan = true
                ensure(false) { "should not run" }
            }
            42
        }
        assertTrue(result.isFail)
        assertEquals("early fail", result.errorOrThrow())
        assertEquals(false, validationRan)
    }

    @Test
    fun `validation with check collects Res errors`() {
        val result = rail<Int, String> {
            val validate = validation<String> { errors -> errors.joinToString("; ") }
            validate {
                Res.failure("from res").check()
                ensure(false) { "from ensure" }
            }
            42
        }
        assertTrue(result.isFail)
        assertEquals("from res; from ensure", result.errorOrThrow())
    }

    // -- check/checkOrNull in top-level ValidationMapping invoke --

    @Test
    fun `top-level invoke check collects Fail error`() {
        val validate = ValidationMapping<String, String> { errors -> errors.joinToString("; ") }
        val result = validate {
            Res.failure("from check").check()
            ensure(false) { "from ensure" }
        }
        assertTrue(result.isFail)
        assertEquals("from check; from ensure", result.errorOrThrow())
    }

    @Test
    fun `top-level invoke checkOrNull returns value on Ok and null on Fail`() {
        val validate = ValidationMapping<String, String> { errors -> errors.joinToString("; ") }
        val result = validate {
            val a = Res.ok(42).checkOrNull()
            assertEquals(42, a)
            val b: Int? = Res.failure("bad").checkOrNull()
            assertNull(b)
        }
        assertTrue(result.isFail)
        assertEquals("bad", result.errorOrThrow())
    }

    // -- ErrorMapperException protection --

    @Test
    fun `top-level invoke wraps mapErrors exception in ErrorMapperException`() {
        val validate = ValidationMapping<String, String> { throw IllegalArgumentException("mapper broke") }
        val ex = assertFailsWith<ErrorMapperException> {
            validate {
                ensure(false) { "err" }
            }
        }
        assertIs<IllegalArgumentException>(ex.cause)
        assertEquals("mapper broke", ex.cause!!.message)
        assertIs<IllegalStateException>(ex.originalException)
    }

    @Test
    fun `member extension wraps mapErrors exception in ErrorMapperException`() {
        val ex = assertFailsWith<ErrorMapperException> {
            rail<Int, String> {
                val validate = validation<String> { throw IllegalArgumentException("mapper broke") }
                validate {
                    ensure(false) { "err" }
                }
                42
            }
        }
        assertIs<IllegalArgumentException>(ex.cause)
        assertEquals("mapper broke", ex.cause!!.message)
        assertIs<IllegalStateException>(ex.originalException)
    }

    @Test
    fun `Validator orFail wraps mapErrors exception in ErrorMapperException`() {
        val ex = assertFailsWith<ErrorMapperException> {
            rail<Int, String> {
                val v = Validator.validator<String>()
                v.ensure(false) { "err" }
                v.orFail { throw IllegalArgumentException("mapper broke") }
                42
            }
        }
        assertIs<IllegalArgumentException>(ex.cause)
        assertEquals("mapper broke", ex.cause!!.message)
        assertIs<IllegalStateException>(ex.originalException)
    }
}
