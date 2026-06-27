package `in`.jphe.storyvox.source.github.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.github.GitHubAuthedSource
import `in`.jphe.storyvox.source.github.GitHubSource
import `in`.jphe.storyvox.source.github.auth.DeviceFlowApi
import `in`.jphe.storyvox.source.github.auth.GitHubAuthInterceptor
import `in`.jphe.storyvox.source.github.auth.GitHubAuthRepository
import `in`.jphe.storyvox.source.github.auth.GitHubAuthRepositoryImpl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class GitHubHttp

/**
 * Unauthenticated OkHttpClient for the Device Flow endpoints
 * (`github.com/login/device/code` + `github.com/login/oauth/access_token`).
 * Distinct from [GitHubHttp] so the auth interceptor doesn't fire on
 * the token-issuing endpoints — which take only `client_id` in the form
 * body and have nothing to authenticate against. Issue #91.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class GitHubDeviceFlowHttp

/**
 * Provides the OkHttpClient used by [`in`.jphe.storyvox.source.github
 * .net.GitHubApi]. Qualified [GitHubHttp] so it doesn't collide with
 * the unqualified app-wide client (or the @RoyalRoadHttp one).
 *
 * Issue #91 wired the [GitHubAuthInterceptor] in here so authed REST
 * calls automatically attach `Authorization: Bearer <token>` when a
 * session exists. The interceptor pins to `api.github.com` only — see
 * [GitHubAuthInterceptor] for the host-leak defense.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object GitHubHttpModule {

    @Provides
    @Singleton
    @GitHubHttp
    fun provideClient(
        authInterceptor: GitHubAuthInterceptor,
        @UserAgentHeader userAgent: Interceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            // #1204 — shared descriptive UA on every request
            // (see in.jphe.storyvox.data.network.UserAgent).
            .addInterceptor(userAgent)
            .build()

    /**
     * No-auth client for the Device Flow `github.com/login/...` endpoints.
     * The auth interceptor is intentionally absent: we have no token yet
     * (we're requesting one), and the device-code endpoint takes only
     * `client_id` + `scope` in the form body.
     */
    @Provides
    @Singleton
    @GitHubDeviceFlowHttp
    fun provideDeviceFlowClient(
        @UserAgentHeader userAgent: Interceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            // #1204 — shared descriptive UA on every request
            // (see in.jphe.storyvox.data.network.UserAgent).
            .addInterceptor(userAgent)
            .build()

    /**
     * The DeviceFlowApi takes a vanilla [OkHttpClient] in its constructor.
     * Hilt resolves the `@GitHubDeviceFlowHttp`-qualified one here so the
     * `@Inject` constructor doesn't need a qualifier annotation (which
     * Kotlin makes verbose for `@Inject constructor` parameters).
     */
    @Provides
    @Singleton
    fun provideDeviceFlowApi(@GitHubDeviceFlowHttp http: OkHttpClient): DeviceFlowApi =
        DeviceFlowApi(http)
}

/**
 * Contributes [GitHubSource] into the multi-source `Map<String,
 * FictionSource>` from PR #35. With this binding active,
 * `addByUrl(github URL)` flows end-to-end through the data layer:
 * `UrlRouter` returns sourceId="github", `FictionRepository.addByUrl`
 * looks up `sources[SourceIds.GITHUB]`, and `GitHubSource
 * .fictionDetail` resolves the manifest + chapters.
 *
 * Issue #91 added the [GitHubAuthRepository] binding alongside, so
 * `:feature` and `:app` can inject the GitHub session state for the
 * Settings UI sign-in row.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class GitHubBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.GITHUB)
    abstract fun bindFictionSource(impl: GitHubSource): FictionSource

    /**
     * Cross-module binding so the Browse adapter (in `:app`) can route
     * auth-gated GitHub listings (`/user/repos`, `/user/starred`, ...)
     * without exposing the package-internal [GitHubSource] type. #200.
     */
    @Binds
    @Singleton
    abstract fun bindGitHubAuthedSource(impl: GitHubSource): GitHubAuthedSource

    @Binds
    @Singleton
    abstract fun bindGitHubAuthRepository(impl: GitHubAuthRepositoryImpl): GitHubAuthRepository
}
