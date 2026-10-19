package `in`.jphe.storyvox.source.sufficientvelocity.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.sufficientvelocity.net.SufficientvelocityApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Dedicated OkHttp client qualifier for the Sufficient Velocity backend. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SufficientvelocityHttp

@Module
@InstallIn(SingletonComponent::class)
internal object SufficientvelocityHttpModule {

    @Provides
    @Singleton
    @SufficientvelocityHttp
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
    fun provideSufficientvelocityApi(
        @SufficientvelocityHttp client: OkHttpClient,
    ): SufficientvelocityApi = SufficientvelocityApi(client)
}
