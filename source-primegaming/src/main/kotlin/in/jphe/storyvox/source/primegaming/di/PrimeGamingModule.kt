package `in`.jphe.storyvox.source.primegaming.di

import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.primegaming.config.PrimeGamingConfig
import `in`.jphe.storyvox.source.primegaming.net.PrimeGamingApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Dedicated OkHttp client qualifier for the Prime Gaming backend. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PrimeGamingHttp

@Module
@InstallIn(SingletonComponent::class)
internal object PrimeGamingHttpModule {

    @Provides
    @Singleton
    @PrimeGamingHttp
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
    fun providePrimeGamingApi(
        @PrimeGamingHttp client: OkHttpClient,
        // dagger.Lazy breaks the FictionSource -> settings-config init cycle
        // (#1309); the PrimeGamingConfig binding is supplied by :app.
        config: Lazy<PrimeGamingConfig>,
    ): PrimeGamingApi = PrimeGamingApi(client, config)
}
