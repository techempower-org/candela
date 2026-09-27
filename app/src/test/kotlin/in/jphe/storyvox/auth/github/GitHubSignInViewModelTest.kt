package `in`.jphe.storyvox.auth.github

import `in`.jphe.storyvox.source.github.auth.DeviceCodeResult
import `in`.jphe.storyvox.source.github.auth.DeviceFlowApi
import `in`.jphe.storyvox.source.github.auth.GitHubAuthRepository
import `in`.jphe.storyvox.source.github.auth.GitHubProfileService
import `in`.jphe.storyvox.source.github.auth.GitHubScopePreferences
import `in`.jphe.storyvox.source.github.auth.GitHubSession
import `in`.jphe.storyvox.source.github.auth.ProfileResult
import `in`.jphe.storyvox.source.github.auth.TokenPollResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.LinkedBlockingDeque

/**
 * State-machine tests for [GitHubSignInViewModel] — the orchestrating
 * coroutine that drives the spec § Login flow UX state diagram. Issue #91.
 *
 * Stubs [DeviceFlowApi] and [GitHubProfileService] so each test enqueues
 * the polling responses it wants and asserts the resulting state. The
 * ViewModel uses [viewModelScope] which derives from Dispatchers.Main, so
 * we install a [StandardTestDispatcher] on Main.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GitHubSignInViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeVm(
        deviceFlow: DeviceFlowApi,
        auth: GitHubAuthRepository = FakeAuth(),
        profile: GitHubProfileService = FakeProfile(ProfileResult.Success("octocat", "Octo Cat", 1L)),
        scopePrefs: GitHubScopePreferences = FakeScopePrefs(),
    ): GitHubSignInViewModel =
        GitHubSignInViewModel(deviceFlow, auth, profile, scopePrefs).also {
            it.clientIdOverride = "test-client"
        }

    @Test
    fun `successful flow transitions Idle to AwaitingUser to Captured`() = runTest(dispatcher) {
        val flow = ScriptedDeviceFlow(
            deviceCode = DeviceCodeResult.Success(
                deviceCode = "dev-1",
                userCode = "ABCD-EFGH",
                verificationUri = "https://github.com/login/device",
                verificationUriComplete = "https://github.com/login/device?user_code=ABCD-EFGH",
                expiresInSeconds = 900,
                intervalSeconds = 5,
            ),
            tokenPolls = arrayListOf(
                TokenPollResult.Pending,
                TokenPollResult.Success("gho_real", "read:user public_repo", "bearer"),
            ),
        )
        val auth = FakeAuth()
        val vm = makeVm(flow, auth)

        vm.start()
        advanceUntilIdle()

        // After the device-code request resolves, we should be in AwaitingUser.
        // (advanceUntilIdle advances through the initial-interval delay too,
        // so we'll already have polled at least once.)
        // Loop: 5s wait → poll #1 (Pending) → 5s wait → poll #2 (Success) → Captured.
        assertTrue(
            "expected Captured, got ${vm.state.value}",
            vm.state.value is SignInState.Captured,
        )
        val captured = vm.state.value as SignInState.Captured
        assertEquals("octocat", captured.login)
        // Token persisted to repo.
        val sess = auth.sessionState.value
        assertTrue(sess is GitHubSession.Authenticated)
        sess as GitHubSession.Authenticated
        assertEquals("gho_real", sess.token)
        assertEquals("octocat", sess.login)
    }

    @Test
    fun `denied during polling transitions to Denied`() = runTest(dispatcher) {
        val flow = ScriptedDeviceFlow(
            deviceCode = DeviceCodeResult.Success(
                deviceCode = "dev-1",
                userCode = "ABCD-EFGH",
                verificationUri = "https://github.com/login/device",
                verificationUriComplete = null,
                expiresInSeconds = 900,
                intervalSeconds = 5,
            ),
            tokenPolls = arrayListOf(TokenPollResult.Denied),
        )
        val vm = makeVm(flow)
        vm.start()
        advanceUntilIdle()
        assertEquals(SignInState.Denied, vm.state.value)
    }

    @Test
    fun `expired during polling transitions to Expired`() = runTest(dispatcher) {
        val flow = ScriptedDeviceFlow(
            deviceCode = DeviceCodeResult.Success(
                deviceCode = "dev-1",
                userCode = "ABCD-EFGH",
                verificationUri = "https://github.com/login/device",
                verificationUriComplete = null,
                expiresInSeconds = 900,
                intervalSeconds = 5,
            ),
            tokenPolls = arrayListOf(TokenPollResult.Expired),
        )
        val vm = makeVm(flow)
        vm.start()
        advanceUntilIdle()
        assertEquals(SignInState.Expired, vm.state.value)
    }

    @Test
    fun `slow_down bumps interval but continues polling to success`() = runTest(dispatcher) {
        val flow = ScriptedDeviceFlow(
            deviceCode = DeviceCodeResult.Success(
                deviceCode = "dev-1",
                userCode = "ABCD-EFGH",
                verificationUri = "https://github.com/login/device",
                verificationUriComplete = null,
                expiresInSeconds = 900,
                intervalSeconds = 5,
            ),
            tokenPolls = arrayListOf(
                TokenPollResult.SlowDown,
                TokenPollResult.Success("gho_after_slowdown", "read:user", "bearer"),
            ),
        )
        val vm = makeVm(flow)
        vm.start()
        advanceUntilIdle()
        assertTrue(
            "expected Captured after slow_down + success, got ${vm.state.value}",
            vm.state.value is SignInState.Captured,
        )
    }

    @Test
    fun `device-code error surfaces as Failure`() = runTest(dispatcher) {
        val flow = ScriptedDeviceFlow(
            deviceCode = DeviceCodeResult.GitHubError(
                code = "device_flow_disabled",
                description = "Not configured",
            ),
            tokenPolls = arrayListOf(),
        )
        val vm = makeVm(flow)
        vm.start()
        advanceUntilIdle()
        assertTrue("expected Failure, got ${vm.state.value}", vm.state.value is SignInState.Failure)
        val failure = vm.state.value as SignInState.Failure
        assertTrue(
            "device_flow_disabled should be non-retryable",
            !failure.retryable,
        )
    }

    @Test
    fun `request network error surfaces as retryable Failure`() = runTest(dispatcher) {
        val flow = ScriptedDeviceFlow(
            deviceCode = DeviceCodeResult.NetworkError(IOException("dns failed")),
            tokenPolls = arrayListOf(),
        )
        val vm = makeVm(flow)
        vm.start()
        advanceUntilIdle()
        assertTrue(vm.state.value is SignInState.Failure)
        assertTrue((vm.state.value as SignInState.Failure).retryable)
    }

    @Test
    fun `cancel stops polling and resets to Idle`() = runTest(dispatcher) {
        val flow = ScriptedDeviceFlow(
            deviceCode = DeviceCodeResult.Success(
                deviceCode = "dev-1",
                userCode = "ABCD-EFGH",
                verificationUri = "https://github.com/login/device",
                verificationUriComplete = null,
                expiresInSeconds = 900,
                intervalSeconds = 5,
            ),
            // Always pending → polling would loop forever without cancel.
            tokenPolls = arrayListOf(TokenPollResult.Pending),
            repeatLast = true,
        )
        val vm = makeVm(flow)
        vm.start()
        // Advance just past the initial interval so we're in AwaitingUser
        // and have started polling.
        advanceTimeBy(6_000)
        assertTrue(
            "expected AwaitingUser before cancel, got ${vm.state.value}",
            vm.state.value is SignInState.AwaitingUser,
        )
        vm.cancel()
        // No more state changes after cancel.
        advanceTimeBy(60_000)
        assertEquals(SignInState.Idle, vm.state.value)
    }

    // ── #203 Private repos toggle ──────────────────────────────────

    @Test
    fun `private repos disabled requests public_repo scope`() = runTest(dispatcher) {
        val flow = ScriptedDeviceFlow(
            deviceCode = DeviceCodeResult.Success(
                deviceCode = "dev-1",
                userCode = "ABCD-EFGH",
                verificationUri = "https://github.com/login/device",
                verificationUriComplete = null,
                expiresInSeconds = 900,
                intervalSeconds = 5,
            ),
            tokenPolls = arrayListOf(TokenPollResult.Denied),
        )
        val vm = makeVm(flow, scopePrefs = FakeScopePrefs(privateRepos = false))
        vm.start()
        advanceUntilIdle()
        // `gist` is appended unconditionally since #202 (Gists in Browse).
        assertEquals("read:user public_repo gist notifications", flow.lastRequestedScopes)
    }

    @Test
    fun `private repos enabled requests repo scope`() = runTest(dispatcher) {
        val flow = ScriptedDeviceFlow(
            deviceCode = DeviceCodeResult.Success(
                deviceCode = "dev-1",
                userCode = "ABCD-EFGH",
                verificationUri = "https://github.com/login/device",
                verificationUriComplete = null,
                expiresInSeconds = 900,
                intervalSeconds = 5,
            ),
            tokenPolls = arrayListOf(TokenPollResult.Denied),
        )
        val vm = makeVm(flow, scopePrefs = FakeScopePrefs(privateRepos = true))
        vm.start()
        advanceUntilIdle()
        // `gist` is appended unconditionally since #202 (Gists in Browse).
        assertEquals("read:user repo gist notifications", flow.lastRequestedScopes)
    }

    // ── Test fakes ──────────────────────────────────────────────────

    /**
     * Test [DeviceFlowApi] that returns canned responses in the order they
     * were enqueued. When [repeatLast] is true, the last enqueued response
     * is returned for any subsequent polls (used by the cancel test to
     * simulate "always pending").
     */
    private class ScriptedDeviceFlow(
        private val deviceCode: DeviceCodeResult,
        tokenPolls: List<TokenPollResult>,
        private val repeatLast: Boolean = false,
    ) : DeviceFlowApi(OkHttpClient()) {
        private val polls = LinkedBlockingDeque(tokenPolls)
        private var lastPoll: TokenPollResult? = null

        /** Captures the scopes argument of the most recent [requestDeviceCode] call. */
        var lastRequestedScopes: String? = null
            private set

        override suspend fun requestDeviceCode(clientId: String, scopes: String): DeviceCodeResult {
            lastRequestedScopes = scopes
            return deviceCode
        }

        override suspend fun pollAccessToken(clientId: String, deviceCode: String): TokenPollResult {
            val next = polls.pollFirst() ?: lastPoll ?: TokenPollResult.Pending
            lastPoll = if (repeatLast) next else null
            return next
        }
    }

    private class FakeAuth : GitHubAuthRepository {
        private val state = MutableStateFlow<GitHubSession>(GitHubSession.Anonymous)
        override val sessionState: StateFlow<GitHubSession> = state.asStateFlow()
        override suspend fun captureSession(token: String, login: String?, scopes: String) {
            state.value = GitHubSession.Authenticated(token, login, scopes, 0L)
        }
        override suspend fun clearSession() { state.value = GitHubSession.Anonymous }
        override fun markExpired() { state.value = GitHubSession.Expired }
    }

    private class FakeProfile(private val result: ProfileResult) : GitHubProfileService(OkHttpClient()) {
        override suspend fun getCurrentUser(): ProfileResult = result
    }

    private class FakeScopePrefs(
        private val privateRepos: Boolean = false,
    ) : GitHubScopePreferences {
        override suspend fun privateReposEnabled(): Boolean = privateRepos
    }
}
