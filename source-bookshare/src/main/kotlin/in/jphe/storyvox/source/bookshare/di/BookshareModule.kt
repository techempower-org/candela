package `in`.jphe.storyvox.source.bookshare.di

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

/**
 * Issue #1002 — contributes [BookshareSource] into the multi-source
 * `Map<String, FictionSource>` so persisted rows with `sourceId="bookshare"`
 * resolve through it. Runs in parallel with the auto-generated `@SourcePlugin`
 * descriptor binding (Phase 2 pattern, #384).
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class BookshareBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.BOOKSHARE)
    abstract fun bindFictionSource(impl: BookshareSource): FictionSource
}
