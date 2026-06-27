package `in`.jphe.storyvox.source.outline.di

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
import `in`.jphe.storyvox.source.outline.OutlineSource
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OutlineHttp

/**
 * OkHttpClient for Outline calls. Self-hosted Outline instances are
 * usually on the same network as the user (or on Tailscale / VPN),
 * so timeouts are generous like MemPalace's. Cloud-hosted
 * (getoutline.com) is also fine with these — they're conservative.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object OutlineHttpModule {

    @Provides
    @Singleton
    @OutlineHttp
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
            // #1204 — shared descriptive UA on every request (see
            // in.jphe.storyvox.data.network.UserAgent).
            .addInterceptor(userAgent)
            .build()

    @Provides
    @Singleton
    fun provideOutlineApi(
        @OutlineHttp client: OkHttpClient,
        config: `in`.jphe.storyvox.source.outline.config.OutlineConfig,
    ): `in`.jphe.storyvox.source.outline.net.OutlineApi =
        `in`.jphe.storyvox.source.outline.net.OutlineApi(client, config)
}

/**
 * Contributes [OutlineSource] into the multi-source `Map<String,
 * FictionSource>` (#245). Adds an "Outline" entry to the segmented
 * source picker; persisted fictions with sourceId="outline" route
 * through this source.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class OutlineBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.OUTLINE)
    abstract fun bindFictionSource(impl: OutlineSource): FictionSource
}
