package `in`.jphe.storyvox.feature.docs.profile

import androidx.compose.runtime.Immutable

/**
 * Issue #1519 — the saved household profile.
 *
 * An optional, on-device set of the facts people re-type into every
 * benefits form. Stored encrypted-at-rest (see the store impl) and never
 * synced or backed up. **SSN is deliberately NOT a field** — by policy it
 * stays type-to-fill only, never persisted (see [ProfileAutofillMatcher.isSensitive]).
 *
 * All fields are plain strings so they drop straight into a scanned
 * form's text field with no parsing (household size and income are typed
 * as the user would write them on the form).
 */
@Immutable
data class HouseholdProfile(
    val fullName: String = "",
    val address: String = "",
    val householdSize: String = "",
    val monthlyIncome: String = "",
    val phone: String = "",
    val email: String = "",
) {
    val isEmpty: Boolean
        get() = fullName.isBlank() && address.isBlank() && householdSize.isBlank() &&
            monthlyIncome.isBlank() && phone.isBlank() && email.isBlank()

    /** The stored value for [field] ("" when unset). */
    fun valueFor(field: ProfileField): String = when (field) {
        ProfileField.FULL_NAME -> fullName
        ProfileField.ADDRESS -> address
        ProfileField.HOUSEHOLD_SIZE -> householdSize
        ProfileField.MONTHLY_INCOME -> monthlyIncome
        ProfileField.PHONE -> phone
        ProfileField.EMAIL -> email
    }

    /** Copy with [field] set to [value]. */
    fun with(field: ProfileField, value: String): HouseholdProfile = when (field) {
        ProfileField.FULL_NAME -> copy(fullName = value)
        ProfileField.ADDRESS -> copy(address = value)
        ProfileField.HOUSEHOLD_SIZE -> copy(householdSize = value)
        ProfileField.MONTHLY_INCOME -> copy(monthlyIncome = value)
        ProfileField.PHONE -> copy(phone = value)
        ProfileField.EMAIL -> copy(email = value)
    }
}

/** The fillable profile fields. SSN is intentionally absent (never persisted). */
enum class ProfileField {
    FULL_NAME,
    ADDRESS,
    HOUSEHOLD_SIZE,
    MONTHLY_INCOME,
    PHONE,
    EMAIL,
}
