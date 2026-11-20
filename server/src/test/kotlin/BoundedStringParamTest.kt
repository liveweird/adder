package ch.nokillswit

import ch.nokillswit.infra.paging.optionalBoundedString
import io.ktor.http.parametersOf
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Pure unit tests of `optionalBoundedString`, the one bounded free-text filter parser (infra/paging/QueryParams.kt). */
class BoundedStringParamTest {

    @Test
    fun `absent and blank are null, a value comes back trimmed`() {
        val params = parametersOf("blank" to listOf("   "), "real" to listOf("  x y  "))
        assertNull(params.optionalBoundedString("missing", 10))
        assertNull(params.optionalBoundedString("blank", 10))
        assertEquals("x y", params.optionalBoundedString("real", 10))
    }

    @Test
    fun `the bound counts code points after trimming, not UTF-16 units or padding`() {
        val atBound = "x".repeat(10)
        assertEquals(atBound, parametersOf("n" to listOf("  $atBound  ")).optionalBoundedString("n", 10))
        val failure = assertFailsWith<BadRequestException> {
            parametersOf("n" to listOf("x".repeat(11))).optionalBoundedString("n", 10)
        }
        assertEquals("n must be at most 10 characters", failure.message)
        // Ten emoji are ten code points though twenty UTF-16 units: inside the bound; eleven are not.
        val emoji = "😀"
        assertEquals(emoji.repeat(10), parametersOf("n" to listOf(emoji.repeat(10))).optionalBoundedString("n", 10))
        assertFailsWith<BadRequestException> { parametersOf("n" to listOf(emoji.repeat(11))).optionalBoundedString("n", 10) }
    }

    @Test
    fun `a repeated key is still a 400`() {
        assertFailsWith<BadRequestException> { parametersOf("n" to listOf("a", "b")).optionalBoundedString("n", 10) }
    }
}
