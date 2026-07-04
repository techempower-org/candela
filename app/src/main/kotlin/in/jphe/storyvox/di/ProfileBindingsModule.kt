package `in`.jphe.storyvox.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.EncryptedHouseholdProfileStore
import `in`.jphe.storyvox.feature.docs.profile.HouseholdProfileStore
import javax.inject.Singleton

/**
 * Issue #1519 — binds the household-profile store seam (:feature) to its
 * encrypted :app impl. Lives in :app because the impl needs the
 * `EncryptedSharedPreferences` handle provided by `:core-data`'s
 * DataModule.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProfileBindingsModule {

    @Binds
    @Singleton
    abstract fun bindHouseholdProfileStore(
        impl: EncryptedHouseholdProfileStore,
    ): HouseholdProfileStore
}
