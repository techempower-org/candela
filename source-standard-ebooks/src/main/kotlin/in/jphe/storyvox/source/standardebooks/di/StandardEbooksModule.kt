package `in`.jphe.storyvox.source.standardebooks.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.standardebooks.StandardEbooksSource
import `in`.jphe.storyvox.source.standardebooks.net.StandardEbooksApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Identifies the dedicated cache directory for downloaded Standard
 * Ebooks EPUBs. Scoping the file root to its own qualifier prevents
 * any future module-cleanup pass from confusing SE downloads with
 * `:source-gutenberg`'s cache, the exports/ subdirectory (#117), or
 * anyone else's cache files.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StandardEbooksCache

/**
 * Dedicated OkHttp client for standardebooks.org. Generous timeouts
 * because EPUB downloads can be 1-5 MB and the SE single-mirror serves
 * everything (no CDN); retryOnConnectionFailure smooths transient
 * hiccups without forcing the user to tap a retry button. Same shape
 * as the Gutenberg client (#237).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StandardEbooksHttp

@Module
@InstallIn(SingletonComponent::class)
internal object StandardEbooksHttpModule {

    @Provides
    @Singleton
    @StandardEbooksHttp
    fun provideClient(
        @UserAgentHeader userAgent: Interceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // EPUB downloads dominate the read budget — give them
            // headroom for the SE origin's single-server response time.
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            // #1204 — shared descriptive UA on every request (see
            // in.jphe.storyvox.data.network.UserAgent).
            .addInterceptor(userAgent)
            .build()

    @Provides
    @Singleton
    fun provideApi(
        @StandardEbooksHttp client: OkHttpClient,
    ): StandardEbooksApi = StandardEbooksApi(client)

    @Provides
    @Singleton
    @StandardEbooksCache
    fun provideCacheDir(@ApplicationContext ctx: Context): File =
        File(ctx.cacheDir, "standardebooks").apply { mkdirs() }
}

/**
 * Contributes [StandardEbooksSource] into the multi-source
 * `Map<String, FictionSource>`. Adds a "Standard Ebooks" entry to
 * the segmented source picker; persisted fictions with
 * `sourceId="standardebooks"` route through this source.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class StandardEbooksBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.STANDARD_EBOOKS)
    abstract fun bindFictionSource(impl: StandardEbooksSource): FictionSource
}
