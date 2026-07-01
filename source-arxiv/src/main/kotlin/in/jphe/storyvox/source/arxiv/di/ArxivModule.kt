package `in`.jphe.storyvox.source.arxiv.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.arxiv.ArxivSource
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ArxivHttp

/**
 * Issue #378 — dedicated OkHttp client for the arXiv API. Generous read
 * timeout because the Atom-feed response can carry up to 50 entries (each
 * with a paragraph-length abstract); connect timeout stays tight because
 * `export.arxiv.org` is CDN-fronted and reliably reachable.
 *
 * Follow-redirects stays on as a belt-and-suspenders guard, but the
 * base URL is now HTTPS directly — Android's network-security-config
 * blocks cleartext before OkHttp can follow arXiv's HTTP→HTTPS 301.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object ArxivHttpModule {

    @Provides
    @Singleton
    @ArxivHttp
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
            // #1141 — arXiv asks automated clients to identify themselves
            // (https://info.arxiv.org/help/robots.html).
            .addInterceptor(userAgent)
            .build()

    @Provides
    @Singleton
    fun provideArxivApi(
        @ArxivHttp client: OkHttpClient,
    ): `in`.jphe.storyvox.source.arxiv.net.ArxivApi =
        `in`.jphe.storyvox.source.arxiv.net.ArxivApi(client)
}
