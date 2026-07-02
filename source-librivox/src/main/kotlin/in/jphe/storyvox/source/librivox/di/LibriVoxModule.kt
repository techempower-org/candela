package `in`.jphe.storyvox.source.librivox.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.librivox.LibriVoxSource
import `in`.jphe.storyvox.source.librivox.net.GutenbergTextApi
import `in`.jphe.storyvox.source.librivox.net.LibriVoxApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LibriVoxHttp

/**
 * Issue #1015 — dedicated OkHttp client for the LibriVox catalog API.
 *
 * Modest connect timeout (the user might be on cellular), generous read
 * timeout (an `extended=1` single-book response for a 128-section novel
 * can be a few hundred KB), redirect-following ON because the
 * archive.org `listen_url`s 301 to a CDN host. We don't pool the client
 * with the other source backends — mixing clients across hostnames
 * defeats OkHttp's keep-alive within a single host, so each backend gets
 * its own pool (same rationale as `:source-radio`).
 */
@Module
@InstallIn(SingletonComponent::class)
internal object LibriVoxHttpModule {

    @Provides
    @Singleton
    @LibriVoxHttp
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
            // #1204 — shared descriptive UA on every request
            // (see in.jphe.storyvox.data.network.UserAgent).
            .addInterceptor(userAgent)
            .build()

    @Provides
    @Singleton
    fun provideLibriVoxApi(
        @LibriVoxHttp client: OkHttpClient,
    ): LibriVoxApi = LibriVoxApi(client)

    /**
     * Issue #1046 — Project Gutenberg plain-text fetcher for the
     * open-domain text companion chapter. Reuses the same
     * redirect-following [LibriVoxHttp] client (the `.txt.utf-8` alias
     * 302s to the cache host, and the generous read timeout suits a
     * multi-hundred-KB book download).
     */
    @Provides
    @Singleton
    fun provideGutenbergTextApi(
        @LibriVoxHttp client: OkHttpClient,
    ): GutenbergTextApi = GutenbergTextApi(client)
}
