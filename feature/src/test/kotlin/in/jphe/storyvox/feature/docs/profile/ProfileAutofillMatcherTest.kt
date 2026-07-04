package `in`.jphe.storyvox.feature.docs.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1519 — coverage for the bilingual autofill label matcher. These
 * ARE the acceptance criteria for the suggestion logic: EN + ES form
 * vocabulary maps to the right field, and SSN-like fields NEVER map (and
 * are flagged sensitive). Pure JVM, on-device by construction.
 */
class ProfileAutofillMatcherTest {

    @Test
    fun `english labels map to fields`() {
        assertEquals(ProfileField.FULL_NAME, ProfileAutofillMatcher.match("Full Name"))
        assertEquals(ProfileField.ADDRESS, ProfileAutofillMatcher.match("Mailing Address"))
        assertEquals(ProfileField.HOUSEHOLD_SIZE, ProfileAutofillMatcher.match("Household Size"))
        assertEquals(ProfileField.MONTHLY_INCOME, ProfileAutofillMatcher.match("Gross Monthly Income"))
        assertEquals(ProfileField.PHONE, ProfileAutofillMatcher.match("Phone Number"))
        assertEquals(ProfileField.EMAIL, ProfileAutofillMatcher.match("Email Address"))
    }

    @Test
    fun `spanish labels map to fields`() {
        assertEquals(ProfileField.FULL_NAME, ProfileAutofillMatcher.match("Nombre completo"))
        assertEquals(ProfileField.ADDRESS, ProfileAutofillMatcher.match("Domicilio"))
        assertEquals(ProfileField.HOUSEHOLD_SIZE, ProfileAutofillMatcher.match("Número de personas en el hogar"))
        assertEquals(ProfileField.MONTHLY_INCOME, ProfileAutofillMatcher.match("Ingreso mensual bruto"))
        assertEquals(ProfileField.PHONE, ProfileAutofillMatcher.match("Teléfono"))
        assertEquals(ProfileField.EMAIL, ProfileAutofillMatcher.match("Correo electrónico"))
    }

    @Test
    fun `bare short labels still map`() {
        assertEquals(ProfileField.ADDRESS, ProfileAutofillMatcher.match("Address"))
        assertEquals(ProfileField.MONTHLY_INCOME, ProfileAutofillMatcher.match("Income"))
        assertEquals(ProfileField.EMAIL, ProfileAutofillMatcher.match("E-mail"))
    }

    @Test
    fun `ssn fields are sensitive and never match`() {
        for (label in listOf(
            "Social Security Number",
            "SSN",
            "Social Security No.",
            "Número de Seguro Social",
            "Seguro Social",
        )) {
            assertTrue("$label should be sensitive", ProfileAutofillMatcher.isSensitive(label))
            assertNull("$label must not suggest", ProfileAutofillMatcher.match(label))
        }
    }

    @Test
    fun `non-sensitive fields are not flagged sensitive`() {
        assertFalse(ProfileAutofillMatcher.isSensitive("Full Name"))
        assertFalse(ProfileAutofillMatcher.isSensitive("Monthly Income"))
        assertFalse(ProfileAutofillMatcher.isSensitive(""))
    }

    @Test
    fun `unknown labels do not match`() {
        assertNull(ProfileAutofillMatcher.match("Signature"))
        assertNull(ProfileAutofillMatcher.match("Date of hearing"))
        assertNull(ProfileAutofillMatcher.match(""))
    }

    @Test
    fun `most specific synonym wins`() {
        // "Gross monthly household income" contains both "income" and the
        // more specific income phrases — still resolves to income, not a
        // spurious household-size hit from the word "household".
        assertEquals(
            ProfileField.MONTHLY_INCOME,
            ProfileAutofillMatcher.match("Gross monthly household income (before taxes)"),
        )
    }
}
