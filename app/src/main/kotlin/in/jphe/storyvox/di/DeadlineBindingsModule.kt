package `in`.jphe.storyvox.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.deadline.AlarmDeadlineReminderScheduler
import `in`.jphe.storyvox.deadline.JsonFileDeadlineReminderStore
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.techempower.deadline.DeadlineClock
import `in`.jphe.storyvox.feature.techempower.deadline.DeadlineReminderScheduler
import `in`.jphe.storyvox.feature.techempower.deadline.DeadlineRemindersEnabledSource
import `in`.jphe.storyvox.feature.techempower.deadline.DeadlineReminderStore
import java.time.LocalDate
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Issue #1515 — binds the deadline-keeper seams (defined in :feature) to
 * their :app-side implementations. Lives in :app because the impls need
 * `AlarmManager` / `Context` / Android storage, which the feature module
 * can't reach — same layering as the OCR seam bindings in [AppBindings].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DeadlineBindingsModule {

    @Binds
    @Singleton
    abstract fun bindScheduler(impl: AlarmDeadlineReminderScheduler): DeadlineReminderScheduler

    @Binds
    @Singleton
    abstract fun bindStore(impl: JsonFileDeadlineReminderStore): DeadlineReminderStore

    companion object {
        /** Production clock — real "today"; tests inject a fixed date. */
        @Provides
        @Singleton
        fun provideClock(): DeadlineClock = DeadlineClock { LocalDate.now() }

        /**
         * Issue #1631 — reads the master deadline-reminders enable pref off
         * the settings DataStore (same singleton [SettingsRepositoryUi] as
         * the rest of the app). Device-local pref; default true.
         */
        @Provides
        @Singleton
        fun provideRemindersEnabledSource(
            settings: SettingsRepositoryUi,
        ): DeadlineRemindersEnabledSource =
            DeadlineRemindersEnabledSource { settings.settings.first().deadlineRemindersEnabled }
    }
}
