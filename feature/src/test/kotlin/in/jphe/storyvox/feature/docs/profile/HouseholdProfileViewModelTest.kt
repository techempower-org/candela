package `in`.jphe.storyvox.feature.docs.profile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #1519 — household profile VM orchestration with a fake store.
 * Save / load / edit / revert / delete, all plain-JVM (no Android, no
 * network; the profile never leaves the device).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HouseholdProfileViewModelTest {

    private lateinit var store: FakeStore

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `empty store yields empty draft`() = runTest {
        store = FakeStore()
        val vm = HouseholdProfileViewModel(store)
        assertTrue(vm.state.value.draft.isEmpty)
        assertFalse(vm.state.value.hasSavedProfile)
    }

    @Test
    fun `editing a field marks unsaved changes`() = runTest {
        store = FakeStore()
        val vm = HouseholdProfileViewModel(store)
        vm.onFieldChange(ProfileField.ADDRESS, "123 Main St")
        assertTrue(vm.state.value.hasUnsavedChanges)
        assertEquals("123 Main St", vm.state.value.draft.address)
    }

    @Test
    fun `save persists to the store and clears unsaved flag`() = runTest {
        store = FakeStore()
        val vm = HouseholdProfileViewModel(store)
        vm.onFieldChange(ProfileField.FULL_NAME, "Ada Lovelace")
        vm.onFieldChange(ProfileField.HOUSEHOLD_SIZE, "3")
        vm.save()

        assertEquals("Ada Lovelace", store.current().fullName)
        assertEquals("3", store.current().householdSize)
        assertTrue(vm.state.value.hasSavedProfile)
        assertFalse(vm.state.value.hasUnsavedChanges)
        assertTrue(vm.state.value.justSaved)
    }

    @Test
    fun `existing profile loads into state`() = runTest {
        store = FakeStore(HouseholdProfile(fullName = "Grace", email = "g@example.org"))
        val vm = HouseholdProfileViewModel(store)
        assertTrue(vm.state.value.hasSavedProfile)
        assertEquals("Grace", vm.state.value.draft.fullName)
        assertEquals("g@example.org", vm.state.value.draft.email)
    }

    @Test
    fun `revert restores the saved values`() = runTest {
        store = FakeStore(HouseholdProfile(fullName = "Grace"))
        val vm = HouseholdProfileViewModel(store)
        vm.onFieldChange(ProfileField.FULL_NAME, "Changed")
        assertTrue(vm.state.value.hasUnsavedChanges)
        vm.revert()
        assertFalse(vm.state.value.hasUnsavedChanges)
        assertEquals("Grace", vm.state.value.draft.fullName)
    }

    @Test
    fun `delete wipes the store and the state`() = runTest {
        store = FakeStore(HouseholdProfile(fullName = "Grace"))
        val vm = HouseholdProfileViewModel(store)
        vm.deleteProfile()
        assertTrue(store.current().isEmpty)
        assertFalse(vm.state.value.hasSavedProfile)
        assertTrue(vm.state.value.draft.isEmpty)
    }

    private class FakeStore(initial: HouseholdProfile = HouseholdProfile()) : HouseholdProfileStore {
        private val flow = MutableStateFlow(initial)
        fun current(): HouseholdProfile = flow.value
        override fun profile(): Flow<HouseholdProfile> = flow
        override suspend fun save(profile: HouseholdProfile) { flow.value = profile }
        override suspend fun clear() { flow.value = HouseholdProfile() }
    }
}
