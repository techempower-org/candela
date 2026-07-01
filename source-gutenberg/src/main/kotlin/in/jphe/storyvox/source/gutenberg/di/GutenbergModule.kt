package `in`.jphe.storyvox.source.gutenberg.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.gutenberg.GutenbergSource
import `in`.jphe.storyvox.source.gutenberg.net.GutendexApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Identifies the dedicated cache directory for downloaded Gutenberg
 * EPUBs. Scoping the file root to its own qualifier prevents any
 * future module-cleanup pass from confusing PG downloads with the
 * exports/ subdirectory (#117) or anyone else's cache files.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GutenbergCache

/**
 * Dedicated OkHttp client for Gutendex + gutenberg.org. Generous
 * timeouts because EPUB downloads can be 1-10 MB and PG mirrors vary
 * in speed; retryOnConnectionFailure smooths over transient mirror
 * hiccups without forcing the user to tap a retry button.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GutenbergHttp

@Module
@InstallIn(SingletonComponent::class)
internal object GutenbergHttpModule {

    @Provides
    @Singleton
    @GutenbergHttp
    fun provideClient(
        @UserAgentHeader userAgent: Interceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // EPUB downloads dominate the read budget — give them
            // headroom for the slower PG mirrors.
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            // #1204 — PG's robot policy asks clients to identify themselves;
            // apply the shared descriptive UA on every request (see
            // in.jphe.storyvox.data.network.UserAgent).
            .addInterceptor(userAgent)
            .build()

    @Provides
    @Singleton
    fun provideGutendexApi(
        @GutenbergHttp client: OkHttpClient,
    ): GutendexApi = GutendexApi(client)

    @Provides
    @Singleton
    @GutenbergCache
    fun provideCacheDir(@ApplicationContext ctx: Context): File =
        File(ctx.cacheDir, "gutenberg").apply { mkdirs() }

}
