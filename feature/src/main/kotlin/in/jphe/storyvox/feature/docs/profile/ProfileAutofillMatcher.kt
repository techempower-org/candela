package `in`.jphe.storyvox.feature.docs.profile

/**
 * Issue #1519 — maps a scanned form's detected field label to a saved
 * [ProfileField] (or flags it sensitive), bilingually.
 *
 * Pure JVM (no Android, no network) so it's fully unit-testable and safe
 * to run entirely on-device — which is the whole point: form contents
 * never leave the phone.
 *
 * ## Program-awareness
 *
 * The dictionary is program-agnostic today (the common EN/ES vocabulary
 * covers CalFresh, Medi-Cal, LifeLine, LIHEAP forms alike). [match]
 * accepts an optional `programId` hook so program-specific synonyms can
 * be layered in later without changing call sites; the seed corpus keys
 * off the same plain program-id strings the rest of the benefits suite
 * uses. TODO(#1517): fold in program-specific label synonyms from the
 * screener corpus when it lands.
 */
object ProfileAutofillMatcher {

    /**
     * Sensitive labels we must NEVER offer a suggestion for and must warn
     * about — SSN/ITIN/Tax-ID stay type-to-fill only, never persisted
     * (issue #1519 policy). Accent-free, lowercased.
     */
    private val SENSITIVE = listOf(
        "social security number", "social security", "ssn", "ss number",
        "itin", "tax id", "taxpayer id", "ein",
        // Spanish
        "numero de seguro social", "seguro social", "seguridad social",
        "numero de identificacion personal del contribuyente",
    )

    /**
     * EN + ES synonym lists per field, accent-free + lowercased. Short,
     * unambiguous forms ("income", "phone") act as the catch-all; longer
     * forms disambiguate. Ordered most-specific first within each field.
     */
    private val SYNONYMS: Map<ProfileField, List<String>> = mapOf(
        ProfileField.FULL_NAME to listOf(
            "full name", "first and last name", "applicant name", "your name", "name",
            "nombre completo", "nombre y apellido", "nombre del solicitante", "nombre",
        ),
        ProfileField.ADDRESS to listOf(
            "mailing address", "home address", "street address", "residential address",
            "residence", "address",
            "direccion postal", "domicilio postal", "domicilio", "direccion",
        ),
        ProfileField.HOUSEHOLD_SIZE to listOf(
            "household size", "number in household", "people in household",
            "household members", "family size", "size of household",
            "tamano del hogar", "numero de personas en el hogar", "personas en el hogar",
            "integrantes del hogar", "miembros del hogar", "tamano de la familia",
        ),
        ProfileField.MONTHLY_INCOME to listOf(
            "gross monthly income", "monthly income", "household income",
            "monthly earnings", "monthly wages", "income",
            "ingreso mensual bruto", "ingreso mensual", "ingresos del hogar",
            "ingresos mensuales", "ingresos", "ingreso",
        ),
        ProfileField.PHONE to listOf(
            "phone number", "telephone number", "contact number", "mobile number",
            "cell phone", "telephone", "phone",
            "numero de telefono", "telefono de contacto", "telefono celular",
            "celular", "telefono",
        ),
        ProfileField.EMAIL to listOf(
            "email address", "e mail address", "email", "e mail",
            "correo electronico", "correo",
        ),
    )

    // Pre-normalized for cheap matching.
    private val NORMALIZED_SENSITIVE = SENSITIVE.map(::normalize)
    private val NORMALIZED_SYNONYMS: List<Pair<ProfileField, String>> =
        SYNONYMS.entries.flatMap { (field, syns) -> syns.map { field to normalize(it) } }

    /**
     * True when [fieldLabel] looks like an SSN / tax-id field. Such fields
     * must never be autofilled and the UI warns that the value is not
     * saved.
     */
    fun isSensitive(fieldLabel: String): Boolean {
        val n = normalize(fieldLabel)
        if (n.isEmpty()) return false
        return NORMALIZED_SENSITIVE.any { n.contains(it) }
    }

    /**
     * The [ProfileField] a detected [fieldLabel] maps to, or null when it
     * doesn't match anything we hold (or is [isSensitive], which always
     * returns null — never a suggestion). `programId` is a forward hook
     * for program-specific synonyms (unused in the seed dictionary).
     */
    fun match(fieldLabel: String, programId: String? = null): ProfileField? {
        val n = normalize(fieldLabel)
        if (n.isEmpty()) return null
        if (isSensitive(fieldLabel)) return null
        // Longest synonym that the label contains wins, so "monthly income"
        // beats the bare "income" catch-all when both are present.
        return NORMALIZED_SYNONYMS
            .filter { (_, syn) -> n.contains(syn) }
            .maxByOrNull { (_, syn) -> syn.length }
            ?.first
    }

    /** lowercase, strip accents, drop punctuation, collapse whitespace. */
    private fun normalize(s: String): String {
        val lowered = stripAccents(s.lowercase())
        val cleaned = buildString(lowered.length) {
            for (ch in lowered) append(if (ch.isLetterOrDigit()) ch else ' ')
        }
        return cleaned.trim().replace(Regex("\\s+"), " ")
    }

    private fun stripAccents(s: String): String = buildString(s.length) {
        for (ch in s) {
            append(java.text.Normalizer.normalize(ch.toString(), java.text.Normalizer.Form.NFD)[0])
        }
    }
}
