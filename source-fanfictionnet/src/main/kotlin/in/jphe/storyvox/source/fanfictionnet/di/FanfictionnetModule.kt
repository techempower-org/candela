package `in`.jphe.storyvox.source.fanfictionnet.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.fanfictionnet.net.FanfictionnetApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Dedicated OkHttp client qualifier for the FanFiction.net backend. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FanfictionnetHttp

@Module
@InstallIn(SingletonComponent::class)
internal object FanfictionnetHttpModule {

    @Provides
    @Singleton
    @FanfictionnetHttp
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
    fun provideFanfictionnetApi(
        @FanfictionnetHttp client: OkHttpClient,
    ): FanfictionnetApi = FanfictionnetApi(client)
}
