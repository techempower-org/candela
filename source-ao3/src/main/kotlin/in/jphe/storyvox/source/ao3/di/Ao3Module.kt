package `in`.jphe.storyvox.source.ao3.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.auth.AuthSource
import `in`.jphe.storyvox.data.auth.SessionHydrator
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.ao3.Ao3AuthedSource
import `in`.jphe.storyvox.source.ao3.Ao3Source
import `in`.jphe.storyvox.source.ao3.auth.Ao3AuthSource
import `in`.jphe.storyvox.source.ao3.auth.Ao3SessionHydrator
import `in`.jphe.storyvox.source.ao3.net.Ao3Api
import `in`.jphe.storyvox.source.ao3.net.Ao3CookieJar
import `in`.jphe.storyvox.source.ao3.net.Ao3RateLimitInterceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Issue #381 — Hilt wiring for the AO3 fiction backend.
 *
 * Mirrors [GutenbergModule][in.jphe.storyvox.source.gutenberg.di.GutenbergHttpModule]
 * exactly — dedicated OkHttp client with generous read timeouts
 * (AO3 EPUBs span 4 KB drabbles to 100 MB epics), dedicated cache
 * directory scoped to its own qualifier so future cleanup passes
 * don't confuse AO3 downloads with anyone else's bytes.
 *
 * #426 PR2 — adds the authed OkHttp client with [Ao3CookieJar],
 * the [Ao3AuthSource] / [Ao3SessionHydrator] cross-source
 * contributions, and the AO3 cookie jar singleton.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Ao3Cache

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Ao3Http

/**
 * #426 PR2 — qualifier for the *authed* AO3 OkHttp client. The
 * authed client carries the [Ao3CookieJar] so signed-in surfaces
 * (subscriptions, Marked-for-Later, Archive-Warning-gated EPUBs)
 * attach the `_otwarchive_session` cookie. The anonymous client
 * ([Ao3Http]) stays cookieless so catalog requests don't leak the
 * session to AO3's edge logs.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Ao3AuthedHttp

@Module
@InstallIn(SingletonComponent::class)
internal object Ao3HttpModule {

    @Provides
    @Singleton
    @Ao3Http
    fun provideClient(rateLimit: Ao3RateLimitInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // EPUB downloads dominate the read budget. AO3's longest
            // works (multi-million-word epics) take measurable time
            // even on fast connections — 60s matches the Gutenberg
            // client's headroom and absorbs the occasional slow
            // response without surfacing a spurious timeout.
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            // #1141 — process-wide 1 req/sec politeness gate. Shared
            // singleton across both clients so anonymous catalog fetches
            // and authed reads draw from one budget. OTW is publicly
            // anti-scraping; AO3 was the only high-risk source with no
            // throttle (Royal Road already gates at 1 req/sec).
            .addInterceptor(rateLimit)
            .build()

    /**
     * #426 PR2 — authed AO3 client. Same timeouts as the anonymous
     * client; the difference is the [Ao3CookieJar] hooked up to
     * the builder, so any request through this client gets the
     * `_otwarchive_session` + optional `remember_user_token`
     * automatically attached once the user signs in.
     *
     * Identifies as the same `USER_AGENT` as the anonymous client
     * — keeping a single UA across both clients means OTW Ops sees
     * one storyvox identity in their logs, not two.
     */
    @Provides
    @Singleton
    @Ao3AuthedHttp
    fun provideAuthedClient(
        jar: Ao3CookieJar,
        rateLimit: Ao3RateLimitInterceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(jar)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            // #1141 — same shared politeness gate as the anonymous
            // client (see provideClient). One budget across both.
            .addInterceptor(rateLimit)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", Ao3Api.USER_AGENT)
                    .build()
                chain.proceed(req)
            }
            .build()

    @Provides
    @Singleton
    fun provideAo3Api(
        @Ao3Http client: OkHttpClient,
        @Ao3AuthedHttp authedClient: OkHttpClient,
    ): Ao3Api = Ao3Api(client, authedClient)

    @Provides
    @Singleton
    @Ao3Cache
    fun provideCacheDir(@ApplicationContext ctx: Context): File =
        File(ctx.cacheDir, "ao3").apply { mkdirs() }
}

/**
 * Contributes [Ao3Source] into the multi-source `Map<String,
 * FictionSource>`. Persisted fictions with sourceId="ao3" route
 * through this source.
 *
 * #426 PR2 — also contributes [Ao3AuthSource] into the cross-source
 * `Map<String, AuthSource>` so the AO3 sign-in WebView surface
 * picks the right config (URL + identity-cookie name) by sourceId,
 * and [Ao3SessionHydrator] into the cross-source `Map<String,
 * SessionHydrator>` so [`AuthViewModel`][in.jphe.storyvox.feature.auth.AuthViewModel]
 * hands captured cookies to the right OkHttp jar by sourceId.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class Ao3Bindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.AO3)
    abstract fun bindFictionSource(impl: Ao3Source): FictionSource

    /**
     * #426 PR2 — surfaces [Ao3Source]'s subscription / Marked-for-Later
     * methods to the app-module Browse adapter
     * ([`RealBrowseRepositoryUi`][in.jphe.storyvox.di.RealBrowseRepositoryUi])
     * without exposing the internal [Ao3Source] type itself. Same
     * pattern [GitHubAuthedSource][in.jphe.storyvox.source.github.GitHubAuthedSource]
     * uses for #200/#201/#202.
     */
    @Binds
    @Singleton
    abstract fun bindAuthedSource(impl: Ao3Source): Ao3AuthedSource

    /**
     * #426 PR2 — AO3's contribution to the cross-source
     * [AuthSource] map. Mirrors RR's binding shape exactly; the
     * AuthRepository / AuthViewModel infrastructure was already
     * map-keyed by PR1, so this is the only change needed to make
     * `AuthWebViewScreen` (after the sourceId-arg generalization in
     * `:app`) drive the AO3 sign-in flow correctly.
     */
    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.AO3)
    abstract fun bindAuthSource(impl: Ao3AuthSource): AuthSource

    /**
     * #426 PR2 — AO3's contribution to the cross-source
     * [SessionHydrator] map. Looked up by sourceId in
     * `AuthViewModel.captureCookies(sourceId = AO3, ...)` so the
     * captured cookies land in the AO3 OkHttp jar, not RR's.
     */
    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.AO3)
    abstract fun bindSessionHydrator(impl: Ao3SessionHydrator): SessionHydrator
}
