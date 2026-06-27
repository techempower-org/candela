package `in`.jphe.storyvox.source.radio.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.radio.RadioSource
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RadioHttp

/**
 * Issue #417 — dedicated OkHttp client for the Radio Browser API.
 *
 * Tight connect timeout (Radio Browser's `de1` mirror is on a fast
 * European host but the user might be on cellular), generous read
 * timeout (a name-search response can be ~250KB). Connect retries
 * handle the occasional transient TLS hiccup. We don't pool the
 * client with `:source-discord` / `:source-notion` because mixing
 * clients across backends defeats OkHttp's connection-keepalive
 * within a single hostname — each backend gets its own pool.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object RadioHttpModule {

    @Provides
    @Singleton
    @RadioHttp
    fun provideClient(
        @UserAgentHeader userAgent: Interceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            // #1141 — Radio Browser asks callers to identify themselves
            // in the UA so abusive clients can be contacted directly.
            .addInterceptor(userAgent)
            .build()

    @Provides
    @Singleton
    fun provideRadioBrowserApi(
        @RadioHttp client: OkHttpClient,
    ): `in`.jphe.storyvox.source.radio.net.RadioBrowserApi =
        `in`.jphe.storyvox.source.radio.net.RadioBrowserApi(client)
}

/**
 * Issue #417 — contributes [RadioSource] into the multi-source
 * `Map<String, FictionSource>` under two keys:
 *
 *  - `SourceIds.RADIO` (`"radio"`) — the canonical new id introduced
 *    by the :source-kvmr → :source-radio rename.
 *  - `SourceIds.KVMR` (`"kvmr"`) — preserved as a migration alias so
 *    persisted v0.5.20+ fictions with `sourceId = "kvmr"` continue to
 *    resolve. Same [RadioSource] instance serves both keys; the
 *    station-id ("kvmr") inside the persisted fictionId is what the
 *    source layer actually matches on, so the duplicate map entry is
 *    a pure routing-table concern.
 *
 * The matching `@SourcePlugin` annotation on [RadioSource] adds the
 * registry-driven descriptor binding for the "radio" id alongside
 * these legacy `@IntoMap` bindings. A follow-up release can drop the
 * KVMR alias once one full release cycle has elapsed with the radio
 * key live; see the kdoc on [RadioSource.LEGACY_KVMR_FICTION_ID].
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RadioBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.RADIO)
    abstract fun bindFictionSource(impl: RadioSource): FictionSource

    /**
     * One-migration-cycle alias. Persisted KVMR rows from v0.5.20..0.5.31
     * carry `sourceId = "kvmr"`; without this binding their lookup
     * through the repository's `Map<String, FictionSource>` would 404
     * after the rename. The same `RadioSource` instance handles both
     * keys — the legacy `kvmr:live` fictionId resolves through
     * [RadioStations.byId("kvmr")] either way.
     */
    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.KVMR)
    abstract fun bindLegacyKvmrAlias(impl: RadioSource): FictionSource
}
