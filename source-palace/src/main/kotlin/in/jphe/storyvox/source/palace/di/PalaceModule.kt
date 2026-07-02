package `in`.jphe.storyvox.source.palace.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.palace.InMemoryPalaceLibraryConfig
import `in`.jphe.storyvox.source.palace.PalaceLibraryConfig
import `in`.jphe.storyvox.source.palace.PalaceSource
import `in`.jphe.storyvox.source.palace.net.PalaceApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Identifies the dedicated cache directory for downloaded Palace
 * EPUBs. Scoping the file root to its own qualifier prevents any
 * future module-cleanup pass from confusing Palace downloads with
 * `:source-gutenberg` / `:source-standard-ebooks` / `:source-ao3`
 * caches, the exports/ subdirectory (#117), or anyone else's files.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PalaceCache

/**
 * Dedicated OkHttp client for Palace OPDS catalogs + EPUB downloads.
 * Generous timeouts because Palace deployments range from
 * well-resourced (NYPL) to small-library shared hosting; EPUB
 * downloads can be 2-10 MB and the per-library origin varies in
 * response time. retryOnConnectionFailure smooths over transient
 * hiccups without forcing the user to tap a retry button. Same shape
 * as the Gutenberg / Standard Ebooks clients.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PalaceHttp

@Module
@InstallIn(SingletonComponent::class)
internal object PalaceHttpModule {

    @Provides
    @Singleton
    @PalaceHttp
    fun provideClient(
        @UserAgentHeader userAgent: Interceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // EPUB downloads dominate the read budget — give them
            // headroom for the slower Palace deployments (small-library
            // shared-hosting setups can take 30+ seconds for 8 MB
            // titles).
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
        @PalaceHttp client: OkHttpClient,
    ): PalaceApi = PalaceApi(client)

    @Provides
    @Singleton
    @PalaceCache
    fun provideCacheDir(@ApplicationContext ctx: Context): File =
        File(ctx.cacheDir, "palace").apply { mkdirs() }
}

/**
 * Binds [PalaceLibraryConfig] to the in-memory default. The
 * DataStore-backed real implementation lives in `:app` / `:feature`
 * and is wired up by issue #501's settings refactor — until then, the
 * in-memory binding returns `null` for the library URL and the source
 * surfaces "configure a library" copy on every browse call.
 *
 * When #501's settings binding lands, it will provide its own
 * `@Module @InstallIn(SingletonComponent::class)` with a `@Provides`
 * for `PalaceLibraryConfig` and a `@TestInstallIn` to replace this
 * binding. We don't put it behind a `@Singleton` rebinder here so that
 * a downstream module-level override works without a `@TestInstallIn`
 * pragma in the production code path. The
 * `(@Suppress("DaggerProductionScope"))` posture mirrors how
 * `:source-radio` provides a default `RadioConfig` overridden by
 * `:app`'s real implementation.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class PalaceConfigBindings {

    /** Default in-memory binding. Replace with a DataStore-backed
     *  implementation in `:app` / `:feature` (issue #501). */
    @Binds
    @Singleton
    abstract fun bindConfig(impl: InMemoryPalaceLibraryConfig): PalaceLibraryConfig
}
