package `in`.jphe.storyvox.source.bookshare.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.bookshare.BookshareConfig
import `in`.jphe.storyvox.source.bookshare.BookshareSource
import `in`.jphe.storyvox.source.bookshare.InMemoryBookshareConfig
import `in`.jphe.storyvox.source.bookshare.net.BookshareApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Dedicated OkHttp client for the Bookshare API v2 (JSON catalog calls). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BookshareHttp

@Module
@InstallIn(SingletonComponent::class)
internal object BookshareHttpModule {

    @Provides
    @Singleton
    @BookshareHttp
    fun provideClient(
        @UserAgentHeader userAgent: Interceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            // #1204 — shared descriptive User-Agent on every request.
            .addInterceptor(userAgent)
            .build()

    @Provides
    @Singleton
    fun provideBookshareApi(
        @BookshareHttp client: OkHttpClient,
    ): BookshareApi = BookshareApi(client)
}

/**
 * Default credentials binding — the in-memory config returns no api_key, so the
 * source stays gated until a DataStore-backed implementation in :app supplies
 * one (mirrors `:source-palace`'s `PalaceConfigBindings` / #501 pattern).
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class BookshareConfigBindings {

    @Binds
    @Singleton
    abstract fun bindConfig(impl: InMemoryBookshareConfig): BookshareConfig
}
