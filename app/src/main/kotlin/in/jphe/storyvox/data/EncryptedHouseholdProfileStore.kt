package `in`.jphe.storyvox.data

import android.content.SharedPreferences
import `in`.jphe.storyvox.feature.docs.profile.HouseholdProfile
import `in`.jphe.storyvox.feature.docs.profile.HouseholdProfileStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Issue #1519 — production [HouseholdProfileStore]. Persists the profile
 * as a JSON blob under a single key in the app's existing
 * `EncryptedSharedPreferences` bag (`storyvox.secrets`), the same
 * encrypted store the source tokens use. That store is already **excluded
 * from cloud backup and device transfer** (`backup_rules.xml` /
 * `data_extraction_rules.xml`, #951), so the profile is encrypted at rest
 * and never leaves the device — no new backup rules or storage needed.
 *
 * TODO(#1514): migrate to the "My Documents" wallet's shared encrypted
 * storage seam once that lane lands (it's the benefits suite's canonical
 * encrypted store); until then this lane owns its key, per the wave brief.
 */
@Singleton
class EncryptedHouseholdProfileStore @Inject constructor(
    private val secrets: SharedPreferences,
) : HouseholdProfileStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _profile = MutableStateFlow(load())

    override fun profile(): Flow<HouseholdProfile> = _profile.asStateFlow()

    override suspend fun save(profile: HouseholdProfile) = withContext(Dispatchers.IO) {
        secrets.edit().putString(KEY, json.encodeToString(profile.toDto())).apply()
        _profile.value = profile
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        secrets.edit().remove(KEY).apply()
        _profile.value = HouseholdProfile()
    }

    private fun load(): HouseholdProfile = runCatching {
        secrets.getString(KEY, null)?.let { json.decodeFromString<Dto>(it).toModel() }
    }.getOrNull() ?: HouseholdProfile()

    @Serializable
    private data class Dto(
        val fullName: String = "",
        val address: String = "",
        val householdSize: String = "",
        val monthlyIncome: String = "",
        val phone: String = "",
        val email: String = "",
    )

    private fun Dto.toModel() = HouseholdProfile(
        fullName = fullName,
        address = address,
        householdSize = householdSize,
        monthlyIncome = monthlyIncome,
        phone = phone,
        email = email,
    )

    private fun HouseholdProfile.toDto() = Dto(
        fullName = fullName,
        address = address,
        householdSize = householdSize,
        monthlyIncome = monthlyIncome,
        phone = phone,
        email = email,
    )

    private companion object {
        const val KEY = "household.profile"
    }
}
