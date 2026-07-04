package `in`.jphe.storyvox.feature.docs.profile

import kotlinx.coroutines.flow.Flow

/**
 * Issue #1519 — on-device, encrypted-at-rest persistence for the saved
 * household profile.
 *
 * The production impl (in :app) stores the profile in the app's existing
 * `EncryptedSharedPreferences` bag (`storyvox.secrets`) — already
 * excluded from cloud backup + device transfer (#951) — so the profile is
 * encrypted at rest and never leaves the device.
 *
 * TODO(#1514): once the "My Documents" encrypted wallet lands, migrate
 * this to the wallet's shared storage seam instead of a private key in the
 * secrets bag (the wallet is the benefits suite's canonical encrypted
 * store). Until then this lane owns its storage, per the wave brief.
 */
interface HouseholdProfileStore {

    /** Live profile; emits [HouseholdProfile] (empty when nothing saved). */
    fun profile(): Flow<HouseholdProfile>

    /** Persist [profile] (replaces any previous). */
    suspend fun save(profile: HouseholdProfile)

    /** Erase the saved profile — also the hook a global "delete all my data" calls. */
    suspend fun clear()
}
