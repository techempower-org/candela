package `in`.jphe.storyvox.data.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #786 — covers the [ConnectivityState] derivation contract and the
 * [ConnectivityObserver] StateFlow semantics that the foreground UI collects.
 *
 * [AndroidConnectivityObserver] itself wraps the framework
 * [android.net.ConnectivityManager] and is exercised on-device; these JVM
 * tests pin the parts the UI actually depends on — the always-has-a-value
 * StateFlow and the offline/online predicate the banner gates on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityObserverTest {

    @Test
    fun `isOnline is true for connected and metered, false for offline`() {
        assertTrue(ConnectivityState.Connected.isOnline)
        assertTrue(ConnectivityState.ConnectedMetered.isOnline)
        assertFalse(ConnectivityState.Offline.isOnline)
    }

    @Test
    fun `observer emits its seeded value immediately`() = runTest {
        val observer = FakeConnectivityObserver(initial = ConnectivityState.Offline)
        assertEquals(ConnectivityState.Offline, observer.state.first())
        assertEquals(ConnectivityState.Offline, observer.state.value)
    }

    @Test
    fun `observer reflects transitions through the state flow`() = runTest {
        val observer = FakeConnectivityObserver(initial = ConnectivityState.Connected)

        observer.emit(ConnectivityState.Offline)
        assertEquals(ConnectivityState.Offline, observer.state.value)
        assertFalse(observer.state.value.isOnline)

        observer.emit(ConnectivityState.ConnectedMetered)
        assertEquals(ConnectivityState.ConnectedMetered, observer.state.value)
        assertTrue(observer.state.value.isOnline)
    }
}
