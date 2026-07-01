package `in`.jphe.storyvox.source.wikisource.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.wikisource.WikisourceSource
import `in`.jphe.storyvox.source.wikisource.net.WikisourceApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WikisourceHttp

/**
 * Dedicated OkHttp client for the Wikisource Action API + REST
 * endpoints. Same timeout profile as `:source-wikipedia` — Parsoid
 * HTML for a long single-page work (e.g. a full novella transcluded
 * onto one mainspace page) can run into the hundreds of KB, so the
 * read timeout is generous.
 *
 * Wikimedia traffic is heavily edge-cached and uses HTTP/2 keep-alive;
 * pooling a dedicated client lets Search → tap-work → fetch-subpages
 * share TLS handshakes without competing with the Wikipedia client.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object WikisourceHttpModule {

    @Provides
    @Singleton
    @WikisourceHttp
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
            // #1141 — Wikimedia *requires* a descriptive UA with contact
            // info per https://meta.wikimedia.org/wiki/User-Agent_policy;
            // anonymous traffic without one is 403'd into a restrictive tier.
            .addInterceptor(userAgent)
            .build()

    @Provides
    @Singleton
    fun provideWikisourceApi(
        @WikisourceHttp client: OkHttpClient,
    ): WikisourceApi = WikisourceApi(client)
}
