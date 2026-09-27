package `in`.jphe.storyvox.source.localhelp

import `in`.jphe.storyvox.data.text.htmlToInlineText
import `in`.jphe.storyvox.source.localhelp.net.Two11Resource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Issue #1465 — turns a 211 service record into a short, listenable script.
 *
 * Written for the people this feature exists for (low-literacy / low-vision
 * listeners): short sentences, the most actionable facts first (what it is,
 * who runs it, how to reach it, when it's open, who qualifies), and a closing
 * "call or text 2-1-1" so the narration never dead-ends.
 *
 * The detail payload (`ServiceAtLocation/v2`) is read **tolerantly**: its schema
 * isn't published anonymously and records vary by contributing 211, so this
 * walks the JSON tree by key name (case-insensitive) rather than binding a
 * rigid model. Anything missing is simply skipped; the search-result
 * [Two11Resource] fills the basics when the detail call is unavailable.
 */
internal object Two11Narration {

    /** A normalized, narration-ready view of one service-at-location. */
    data class Facts(
        val serviceName: String = "",
        val organization: String = "",
        val description: String = "",
        val address: String = "",
        val phones: List<String> = emptyList(),
        val hours: String = "",
        val eligibility: String = "",
        val fees: String = "",
        val howToApply: String = "",
        val languages: String = "",
        val website: String = "",
        val email: String = "",
        val dataOwner: String = "",
    )

    fun factsFrom(resource: Two11Resource?): Facts {
        if (resource == null) return Facts()
        return Facts(
            serviceName = resource.nameService.clean(),
            organization = resource.nameOrganization.clean(),
            description = resource.descriptionService.clean()
                .ifBlank { resource.descriptionOrganization.clean() },
            address = joinAddress(
                resource.address1PhysicalAddress,
                resource.cityPhysicalAddress,
                resource.statePhysicalAddress,
                null,
            ),
            dataOwner = resource.dataOwner.clean(),
        )
    }

    /** Facts from the detail tree, with [fallback] filling any gaps. */
    fun factsFrom(detail: JsonElement?, fallback: Facts): Facts {
        if (detail == null) return fallback
        val service = child(detail, "service") ?: detail
        val org = child(detail, "organization") ?: child(service, "organization")
        val location = child(detail, "location") ?: child(service, "location")

        val serviceName = str(detail, "nameService", "serviceName")
            .ifBlank { str(service, "name") }
        val orgName = str(detail, "nameOrganization", "organizationName")
            .ifBlank { org?.let { str(it, "name") }.orEmpty() }
        val description = str(detail, "descriptionService", "serviceDescription")
            .ifBlank { str(service, "description") }

        val addressNode = findFirst(location ?: detail) { k, v ->
            k.contains("address", ignoreCase = true) && v is JsonObject
        } ?: firstArrayObject(location ?: detail) { it.contains("address", ignoreCase = true) }
        val address = addressNode?.let {
            joinAddress(
                str(it, "address1", "address_1", "street"),
                str(it, "city"),
                str(it, "stateProvince", "state_province", "state"),
                str(it, "postalCode", "postal_code", "zip"),
            )
        }.orEmpty()

        val phones = collectPhones(detail)
        val hours = str(detail, "hoursOfOperation", "hours", "scheduleDescription")
            .ifBlank { scheduleText(detail) }

        return Facts(
            serviceName = serviceName.ifBlank { fallback.serviceName },
            organization = orgName.ifBlank { fallback.organization },
            description = description.ifBlank { fallback.description },
            address = address.ifBlank { fallback.address },
            phones = phones.ifEmpty { fallback.phones },
            hours = hours.ifBlank { fallback.hours },
            eligibility = str(detail, "eligibility", "eligibilityDescription", "eligibility_description"),
            fees = str(detail, "fees", "feesDescription", "fees_description"),
            howToApply = str(detail, "applicationProcess", "application_process", "intakeProcedure"),
            languages = languagesText(detail),
            website = str(service, "url", "website").ifBlank { org?.let { str(it, "url", "website") }.orEmpty() },
            email = str(service, "email").ifBlank { org?.let { str(it, "email") }.orEmpty() },
            dataOwner = str(detail, "dataOwner", "dataOwnerDisplayName").ifBlank { fallback.dataOwner },
        )
    }

    /** The narrated script, one paragraph per fact, blank facts skipped. */
    fun script(f: Facts): List<String> = buildList {
        val name = f.serviceName.ifBlank { f.organization }.ifBlank { "This service" }
        add(
            if (f.organization.isNotBlank() && !f.organization.equals(name, ignoreCase = true)) {
                "$name, offered by ${f.organization}."
            } else {
                "$name."
            },
        )
        if (f.description.isNotBlank()) add(f.description.endSentence())
        if (f.address.isNotBlank()) add("Where: ${f.address}.")
        if (f.phones.isNotEmpty()) {
            val joined = f.phones.joinToString(", or ")
            add(if (f.phones.size == 1) "Phone: $joined." else "Phone numbers: $joined.")
        }
        if (f.hours.isNotBlank()) add("Hours: ${f.hours.endSentence()}")
        if (f.eligibility.isNotBlank()) add("Who can get help: ${f.eligibility.endSentence()}")
        if (f.fees.isNotBlank()) add("Cost: ${f.fees.endSentence()}")
        if (f.howToApply.isNotBlank()) add("How to get help: ${f.howToApply.endSentence()}")
        if (f.languages.isNotBlank()) add("Languages: ${f.languages.endSentence()}")
        if (f.website.isNotBlank()) add("Website: ${f.website}")
        if (f.email.isNotBlank()) add("Email: ${f.email}")
        add(
            "Details can change, so it's a good idea to call before you go. " +
                "For more help near you, call or text 2-1-1.",
        )
        val owner = f.dataOwner.ifBlank { "a local 2-1-1" }
        add("Information from the 211 National Data Platform, provided by $owner.")
    }

    // ── tolerant JSON helpers ────────────────────────────────────────────

    private const val MAX_DEPTH = 6

    /** First non-blank string for any of [keys] found anywhere under [el]. */
    fun str(el: JsonElement, vararg keys: String): String {
        for (k in keys) {
            val hit = findFirst(el) { key, v -> key.equals(k, ignoreCase = true) && v.asText().isNotBlank() }
            if (hit != null) return hit.asText().clean()
        }
        return ""
    }

    private fun child(el: JsonElement, key: String): JsonElement? {
        val o = el as? JsonObject ?: return null
        return o.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
            ?.let { if (it is JsonArray) it.firstOrNull() else it }
            ?.takeIf { it is JsonObject }
    }

    private fun findFirst(
        el: JsonElement,
        depth: Int = 0,
        match: (String, JsonElement) -> Boolean,
    ): JsonElement? {
        if (depth > MAX_DEPTH) return null
        when (el) {
            is JsonObject -> {
                for ((k, v) in el) if (match(k, v)) return v
                for ((_, v) in el) findFirst(v, depth + 1, match)?.let { return it }
            }
            is JsonArray -> for (v in el) findFirst(v, depth + 1, match)?.let { return it }
            else -> Unit
        }
        return null
    }

    private fun firstArrayObject(el: JsonElement, keyMatch: (String) -> Boolean): JsonElement? =
        findFirst(el) { k, v -> keyMatch(k) && v is JsonArray && v.firstOrNull() is JsonObject }
            ?.let { (it as JsonArray).first() }

    private fun collectPhones(el: JsonElement): List<String> {
        val out = LinkedHashSet<String>()
        fun walk(node: JsonElement, underPhone: Boolean, depth: Int) {
            if (depth > MAX_DEPTH) return
            when (node) {
                is JsonObject -> for ((k, v) in node) {
                    val phoneKey = k.contains("phone", ignoreCase = true)
                    if ((underPhone || phoneKey) && (k.equals("number", true) || (phoneKey && v is JsonPrimitive))) {
                        v.asText().clean().takeIf { it.any(Char::isDigit) }?.let(out::add)
                    } else {
                        walk(v, underPhone || phoneKey, depth + 1)
                    }
                }
                is JsonArray -> node.forEach { walk(it, underPhone, depth + 1) }
                else -> Unit
            }
        }
        walk(el, false, 0)
        return out.take(3)
    }

    private fun scheduleText(el: JsonElement): String {
        val sched = findFirst(el) { k, _ -> k.contains("schedule", ignoreCase = true) } ?: return ""
        return when (sched) {
            is JsonPrimitive -> sched.asText().clean()
            else -> str(sched, "description", "hours", "text")
        }
    }

    private fun languagesText(el: JsonElement): String {
        val node = findFirst(el) { k, _ -> k.equals("languages", true) || k.equals("language", true) }
            ?: return ""
        return when (node) {
            is JsonArray -> node.mapNotNull { v ->
                when (v) {
                    is JsonPrimitive -> v.asText().clean()
                    is JsonObject -> str(v, "name", "language")
                    else -> null
                }?.takeIf { it.isNotBlank() }
            }.distinct().joinToString(", ")
            else -> node.asText().clean()
        }
    }

    private fun JsonElement.asText(): String =
        (this as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content.orEmpty()

    private fun joinAddress(line1: String?, city: String?, state: String?, zip: String?): String {
        val street = line1.clean()
        val cityState = listOf(city.clean(), listOf(state.clean(), zip.clean()).filter { it.isNotBlank() }.joinToString(" "))
            .filter { it.isNotBlank() }.joinToString(", ")
        return listOf(street, cityState).filter { it.isNotBlank() }.joinToString(", ")
    }

    private fun String?.clean(): String =
        this?.takeIf { it.isNotBlank() && it != "null" }?.htmlToInlineText()?.trim().orEmpty()

    private fun String.endSentence(): String {
        val t = trim()
        return if (t.isEmpty() || t.last() in ".!?") t else "$t."
    }
}
