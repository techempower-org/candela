package `in`.jphe.storyvox.source.plos.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.plos.PlosSource
import `in`.jphe.storyvox.source.plos.net.PlosApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlosHttp

/**
 * Dedicated OkHttp client for the PLOS API. Generous read timeout
 * because the JATS-rendered article HTML pages can be 200kB-1MB for
 * articles with lots of figures + supplementary refs; connect
 * timeout stays tight because api.plos.org + journals.plos.org are
 * reliably reachable on any reasonable connection.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object PlosHttpModule {

    @Provides
    @Singleton
    @PlosHttp
    fun provideClient(
        @UserAgentHeader userAgent: Interceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            // #1204 — shared descriptive UA on every request (see
            // in.jphe.storyvox.data.network.UserAgent).
            .addInterceptor(userAgent)
            .build()

    @Provides
    @Singleton
    fun providePlosApi(@PlosHttp client: OkHttpClient): PlosApi = PlosApi(client)
}
