package `in`.jphe.storyvox.source.bookshare.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
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
