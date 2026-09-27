package `in`.jphe.storyvox.feature.techempower.readaloud

import `in`.jphe.storyvox.feature.docs.FormField
import `in`.jphe.storyvox.feature.docs.profile.HouseholdProfile
import `in`.jphe.storyvox.feature.docs.profile.ProfileField
import `in`.jphe.storyvox.feature.techempower.deadline.DateCandidate

/**
 * Issue #1580 — what each benefits document surface reads aloud.
 *
 * Pure functions over the surface models. Every user-facing word arrives as an
 * already-localized string (or a formatter lambda over one) from the screen's
 * `stringResource` / `getString`, so the spoken language follows the app
 * language (invariant 2) and these stay plain-JVM testable.
 */
object BenefitsReadAloudScripts {

    /**
     * Deadline keeper (#1515) — the date candidates found in a scanned notice,
     * each with the letter's own words around it, so a user can *hear* which
     * date the app found and what the letter says next to it before confirming.
     *
     * @param formatDate localized long date ("August 31, 2026").
     * @param candidateLine "Date N: <date>. The letter says: <snippet>".
     */
    fun deadlineCandidates(
        intro: String,
        candidates: List<DateCandidate>,
        formatDate: (DateCandidate) -> String,
        candidateLine: (index: Int, date: String, snippet: String) -> String,
        pastNote: String,
    ): String = ReadAloudScript.build(
        listOf(intro) + candidates.flatMapIndexed { i, c ->
            listOf(
                candidateLine(i + 1, formatDate(c), c.snippet),
                if (c.isPast) pastNote else null,
            )
        },
    )

    /** Deadline keeper — the in-progress reminder the user is about to save. */
    fun deadlineDraft(
        labelLabel: String,
        label: String,
        deadlineLabel: String,
        deadline: String,
        bodyLabel: String,
        body: String,
        emptyValue: String,
    ): String = ReadAloudScript.build(
        ReadAloudScript.labeled(labelLabel, label, emptyValue),
        ReadAloudScript.labeled(deadlineLabel, deadline),
        ReadAloudScript.labeled(bodyLabel, body, emptyValue),
    )

    /**
     * Fillable PDF (#1512) — the fields the user has placed on the form, in
     * list order, with what they typed, so they can check each one by ear.
     * Text boxes are numbered in their own sequence (as are checkmarks), which
     * matches how a listener counts them.
     */
    fun formFields(
        header: String,
        fields: List<FormField>,
        textFieldLabel: (number: Int) -> String,
        checkField: (number: Int) -> String,
        signatureDrawn: String,
        signatureEmpty: String,
        emptyValue: String,
        titleLabel: String? = null,
        title: String? = null,
    ): String {
        var textN = 0
        var checkN = 0
        val lines = fields.map { f ->
            when (f) {
                is FormField.Text -> ReadAloudScript.labeled(textFieldLabel(++textN), f.text, emptyValue)
                is FormField.Check -> checkField(++checkN)
                is FormField.Signature -> if (f.strokes.any { it.size >= 2 }) signatureDrawn else signatureEmpty
            }
        }
        val titleLine = titleLabel?.let { ReadAloudScript.labeled(it, title) }
        return ReadAloudScript.build(listOf(header) + lines + titleLine)
    }

    /** Household profile (#1519) — each field's label and saved value, in form order. */
    fun householdProfile(
        profile: HouseholdProfile,
        fields: List<Pair<ProfileField, String>>,
        emptyValue: String,
    ): String = ReadAloudScript.build(
        fields.map { (field, label) -> ReadAloudScript.labeled(label, profile.valueFor(field), emptyValue) },
    )
}
