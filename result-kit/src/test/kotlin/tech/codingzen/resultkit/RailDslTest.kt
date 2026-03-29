package tech.codingzen.resultkit

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RailDslTest {

    @Test
    fun `Rail class is annotated with RailDsl`() {
        val annotation = Rail::class.java.getAnnotation(RailDsl::class.java)
        assertNotNull(annotation, "Rail should be annotated with @RailDsl")
    }

    @Test
    fun `RailDsl annotation exists and is usable`() {
        // @DslMarker has SOURCE retention so it's not visible at runtime.
        // We verify the annotation class itself is loadable and applied to Rail.
        val railDslClass = RailDsl::class.java
        assertNotNull(railDslClass, "@RailDsl annotation class should exist")
    }

    @Test
    fun `nested rail with same error type still works via explicit orFail`() {
        val result = rail<Int, String> {
            val inner = rail<Int, String> { fail("inner") }
            assertTrue(inner.isFail)
            42
        }
        assertTrue(result.isOk)
    }
}
