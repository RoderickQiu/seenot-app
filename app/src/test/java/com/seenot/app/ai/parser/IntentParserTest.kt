package com.seenot.app.ai.parser

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntentParserTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val parser = IntentParser { context }

    @Test
    fun malformedEffectiveIntentDoesNotInvalidateConstraint() {
        val payload = parser.parsePayload(
            """
            {
              "constraints": [
                {
                  "type": "DENY",
                  "description": "朋友圈",
                  "timeLimitMinutes": null,
                  "timeScope": "SESSION",
                  "intervention": "MODERATE",
                  "effectiveIntent": "not an object"
                }
              ],
              "unsupportedMode": null
            }
            """.trimIndent()
        )
        val constraints = payload.constraints()

        assertEquals(1, constraints.size)
        assertEquals("朋友圈", constraints.first().description())
        assertNull(constraints.first().effectiveIntent())
    }

    private fun IntentParser.parsePayload(response: String): Any {
        val method = IntentParser::class.java.getDeclaredMethod("parseConstraintsFromJson", String::class.java)
        method.isAccessible = true
        return requireNotNull(method.invoke(this, response))
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any.constraints(): List<Any> {
        val field = javaClass.getDeclaredField("constraints")
        field.isAccessible = true
        return requireNotNull(field.get(this) as? List<Any>)
    }

    private fun Any.description(): String {
        val field = javaClass.getDeclaredField("description")
        field.isAccessible = true
        return field.get(this) as String
    }

    private fun Any.effectiveIntent(): Any? {
        val field = javaClass.getDeclaredField("effectiveIntent")
        field.isAccessible = true
        return field.get(this)
    }
}
