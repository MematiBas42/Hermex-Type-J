package io.github.jcartu.hermex.ui.feature.chat

import io.github.jcartu.hermex.model.ApprovalChoice
import io.github.jcartu.hermex.model.JSONValue
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalChoiceValidationTest {
    @Test
    fun `malformed present choices offer deny only`() {
        val malformed = listOf(
            JSONValue.string("once"),
            JSONValue.objectOf(mapOf("once" to JSONValue.bool(true))),
            JSONValue.NULL,
            JSONValue.number(7.0),
        )
        malformed.forEach { value ->
            assertEquals(
                listOf(ApprovalChoice.DENY),
                ApprovalChoiceValidation.choices(fieldPresent = true, rawValue = value),
            )
        }
    }

    @Test
    fun `unknown or empty array choices offer deny only`() {
        assertEquals(
            listOf(ApprovalChoice.DENY),
            ApprovalChoiceValidation.choices(true, JSONValue.array(emptyList())),
        )
        assertEquals(
            listOf(ApprovalChoice.DENY),
            ApprovalChoiceValidation.choices(
                true,
                JSONValue.array(listOf(JSONValue.string("invented"))),
            ),
        )
    }

    @Test
    fun `valid host order is preserved and deny is appended when absent`() {
        assertEquals(
            listOf(ApprovalChoice.ONCE, ApprovalChoice.SESSION, ApprovalChoice.DENY),
            ApprovalChoiceValidation.choices(
                true,
                JSONValue.array(
                    listOf(JSONValue.string("once"), JSONValue.string("session")),
                ),
            ),
        )
    }

    @Test
    fun `absent field uses legacy choices`() {
        assertEquals(
            listOf(ApprovalChoice.ONCE, ApprovalChoice.DENY),
            ApprovalChoiceValidation.choices(
                fieldPresent = false,
                rawValue = null,
                legacyChoices = listOf(ApprovalChoice.ONCE, ApprovalChoice.DENY),
            ),
        )
    }
}
