package `in`.jphe.storyvox.source.fictionpress.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.fictionpress.net.FictionpressApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Dedicated OkHttp client qualifier for the FictionPress backend. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FictionpressHttp

@Module
@InstallIn(SingletonComponent::class)
internal object FictionpressHttpModule {

    @Provides
    @Singleton
    @FictionpressHttp
    fun provideClient(
        @UserAgentHeader userAgent: Interceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            // #1204 — shared descriptive User-Agent on every request.
            .addInterceptor(userAgent)
            .build()

    @Provides
    @Singleton
    fun provideFictionpressApi(
        @FictionpressHttp client: OkHttpClient,
    ): FictionpressApi = FictionpressApi(client)
}
