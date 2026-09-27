package `in`.jphe.storyvox.source.github.inbox

import `in`.jphe.storyvox.source.github.auth.GitHubAuthRepository
import `in`.jphe.storyvox.source.github.auth.GitHubSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Hand-rolled [GitHubAuthRepository] fake for the inbox tests. */
internal class FakeGitHubAuth(signedIn: Boolean) : GitHubAuthRepository {
    private val state = MutableStateFlow<GitHubSession>(
        if (signedIn) {
            GitHubSession.Authenticated(
                token = "gho_test",
                login = "octocat",
                scopes = "read:user public_repo gist notifications",
                grantedAt = 0L,
            )
        } else {
            GitHubSession.Anonymous
        },
    )
    override val sessionState: StateFlow<GitHubSession> = state

    override suspend fun captureSession(token: String, login: String?, scopes: String) {
        state.value = GitHubSession.Authenticated(token, login, scopes, 0L)
    }

    override suspend fun clearSession() {
        state.value = GitHubSession.Anonymous
    }

    override fun markExpired() {
        state.value = GitHubSession.Expired
    }
}
