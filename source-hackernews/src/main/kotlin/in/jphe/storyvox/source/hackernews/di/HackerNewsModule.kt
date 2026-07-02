package `in`.jphe.storyvox.source.hackernews.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.hackernews.HackerNewsSource
import `in`.jphe.storyvox.source.hackernews.net.HackerNewsApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Dedicated OkHttp client for the Hacker News backend (#379).
 * Modest timeouts because the Firebase endpoints + Algolia search
 * both return small JSON payloads; if either is slow to respond, an
 * AAS network error is better than a hung Browse open.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HackerNewsHttp

@Module
@InstallIn(SingletonComponent::class)
internal object HackerNewsHttpModule {

    @Provides
    @Singleton
    @HackerNewsHttp
    fun provideClient(
        @UserAgentHeader userAgent: Interceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            // #1204 — shared descriptive UA on every request (see
            // in.jphe.storyvox.data.network.UserAgent).
            .addInterceptor(userAgent)
            .build()

    @Provides
    @Singleton
    fun provideHackerNewsApi(
        @HackerNewsHttp client: OkHttpClient,
    ): HackerNewsApi = HackerNewsApi(client)
}
