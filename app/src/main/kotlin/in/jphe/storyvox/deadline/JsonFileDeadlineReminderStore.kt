package `in`.jphe.storyvox.deadline

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.feature.techempower.deadline.DeadlineReminder
import `in`.jphe.storyvox.feature.techempower.deadline.DeadlineReminderStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Issue #1515 — DI-bound [DeadlineReminderStore] impl backed by a private
 * JSON file (see [DeadlineReminderJson]). Holds a live [MutableStateFlow]
 * so the deadline keeper screen updates the moment a reminder is added or
 * deleted, and serialises writes behind a [Mutex].
 */
@Singleton
class JsonFileDeadlineReminderStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeadlineReminderStore {

    private val mutex = Mutex()

    // Initial load is a one-time read of a small private file; acceptable
    // at first injection. Subsequent reads come from the flow.
    private val _reminders = MutableStateFlow(DeadlineReminderJson.readAll(context))

    override fun reminders(): Flow<List<DeadlineReminder>> = _reminders.asStateFlow()

    override suspend fun all(): List<DeadlineReminder> = _reminders.value

    override suspend fun upsert(reminder: DeadlineReminder) = mutex.withLock {
        val next = _reminders.value.filterNot { it.id == reminder.id } + reminder
        persist(next)
    }

    override suspend fun delete(id: String) = mutex.withLock {
        val next = _reminders.value.filterNot { it.id == id }
        persist(next)
    }

    private suspend fun persist(list: List<DeadlineReminder>) {
        withContext(Dispatchers.IO) { DeadlineReminderJson.writeAll(context, list) }
        _reminders.value = list
    }
}
