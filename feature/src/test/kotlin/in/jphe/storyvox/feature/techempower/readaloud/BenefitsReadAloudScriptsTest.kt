package `in`.jphe.storyvox.feature.techempower.readaloud

import `in`.jphe.storyvox.data.docs.NormPoint
import `in`.jphe.storyvox.feature.docs.FormField
import `in`.jphe.storyvox.feature.docs.profile.HouseholdProfile
import `in`.jphe.storyvox.feature.docs.profile.ProfileField
import `in`.jphe.storyvox.feature.techempower.deadline.DateCandidate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1580 — what each benefits document surface speaks. Strings here stand
 * in for the localized resources the screens pass; the builders add no words
 * of their own, so the same assertions hold in Spanish.
 */
class BenefitsReadAloudScriptsTest {

    // ── Deadline keeper (#1515) ───────────────────────────────────────

    private fun candidate(day: Int, snippet: String, past: Boolean = false) = DateCandidate(
        date = LocalDate.of(2026, 8, day),
        rawText = "August $day, 2026",
        snippet = snippet,
        cue = null,
        isPast = past,
    )

    @Test
    fun `deadline candidates read each date with the letter's own words`() {
        val script = BenefitsReadAloudScripts.deadlineCandidates(
            intro = "Dates found in your letter",
            candidates = listOf(
                candidate(31, "Please respond by August 31, 2026"),
                candidate(1, "Notice mailed August 1, 2026", past = true),
            ),
            formatDate = { "August ${it.date.dayOfMonth}" },
            candidateLine = { n, date, snippet -> "Date $n: $date. The letter says: $snippet" },
            pastNote = "This date has already passed.",
        )
        assertEquals(
            "Dates found in your letter. " +
                "Date 1: August 31. The letter says: Please respond by August 31, 2026. " +
                "Date 2: August 1. The letter says: Notice mailed August 1, 2026. " +
                "This date has already passed.",
            script,
        )
    }

    @Test
    fun `deadline draft names every field and flags a blank message`() {
        val script = BenefitsReadAloudScripts.deadlineDraft(
            labelLabel = "Reminder name",
            label = "CalFresh renewal",
            deadlineLabel = "Deadline",
            deadline = "August 31, 2026",
            bodyLabel = "Message",
            body = " ",
            emptyValue = "not filled in",
        )
        assertEquals(
            "Reminder name: CalFresh renewal. Deadline: August 31, 2026. Message: not filled in.",
            script,
        )
    }

    // ── Fillable PDF (#1512) ──────────────────────────────────────────

    @Test
    fun `form fields are read in order with numbered text boxes and checkmarks`() {
        val fields = listOf(
            FormField.Text(id = 0, x = 0f, y = 0f, text = "Ana Lopez"),
            FormField.Check(id = 1, x = 0f, y = 0f),
            FormField.Text(id = 2, x = 0f, y = 0f, text = ""),
            FormField.Signature(
                id = 3, x = 0f, y = 0f, widthFraction = 0.3f, heightFraction = 0.1f,
                strokes = listOf(listOf(NormPoint(0f, 0f), NormPoint(1f, 1f))),
            ),
            FormField.Check(id = 4, x = 0f, y = 0f),
        )
        val script = BenefitsReadAloudScripts.formFields(
            header = "Fields: 5",
            fields = fields,
            textFieldLabel = { "Text box $it" },
            checkField = { "Checkmark $it." },
            signatureDrawn = "Signature added",
            signatureEmpty = "Signature (not drawn yet)",
            emptyValue = "not filled in",
            titleLabel = "File name",
            title = "SNAP form",
        )
        assertEquals(
            "Fields: 5. Text box 1: Ana Lopez. Checkmark 1. Text box 2: not filled in. " +
                "Signature added. Checkmark 2. File name: SNAP form.",
            script,
        )
    }

    @Test
    fun `an undrawn signature and a blank title read honestly`() {
        val script = BenefitsReadAloudScripts.formFields(
            header = "Fields: 1",
            fields = listOf(
                FormField.Signature(
                    id = 0, x = 0f, y = 0f, widthFraction = 0.3f, heightFraction = 0.1f,
                    strokes = listOf(listOf(NormPoint(0f, 0f))),
                ),
            ),
            textFieldLabel = { "Text box $it" },
            checkField = { "Checkmark $it." },
            signatureDrawn = "Signature added",
            signatureEmpty = "Signature (not drawn yet)",
            emptyValue = "not filled in",
            titleLabel = "File name",
            title = "",
        )
        assertEquals("Fields: 1. Signature (not drawn yet).", script)
    }

    // ── Household profile (#1519) ─────────────────────────────────────

    @Test
    fun `household profile reads labels in form order and names missing fields`() {
        val profile = HouseholdProfile(fullName = "Ana Lopez", householdSize = "3", phone = "555 0100")
        val script = BenefitsReadAloudScripts.householdProfile(
            profile = profile,
            fields = listOf(
                ProfileField.FULL_NAME to "Full name",
                ProfileField.ADDRESS to "Address",
                ProfileField.HOUSEHOLD_SIZE to "Household size",
                ProfileField.PHONE to "Phone",
            ),
            emptyValue = "not filled in",
        )
        assertEquals(
            "Full name: Ana Lopez. Address: not filled in. Household size: 3. Phone: 555 0100.",
            script,
        )
    }
}
