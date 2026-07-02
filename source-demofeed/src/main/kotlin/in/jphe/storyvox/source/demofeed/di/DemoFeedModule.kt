package `in`.jphe.storyvox.source.demofeed.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.demofeed.net.DemoFeedApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Dedicated OkHttp client qualifier for the Demo Feed backend. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DemoFeedHttp

@Module
@InstallIn(SingletonComponent::class)
internal object DemoFeedHttpModule {

    @Provides
    @Singleton
    @DemoFeedHttp
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
    fun provideDemoFeedApi(
        @DemoFeedHttp client: OkHttpClient,
    ): DemoFeedApi = DemoFeedApi(client)
}
