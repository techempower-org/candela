package `in`.jphe.storyvox.source.reddit.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.reddit.net.RedditApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Dedicated OkHttp client qualifier for the Reddit backend. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RedditHttp

@Module
@InstallIn(SingletonComponent::class)
internal object RedditHttpModule {

    @Provides
    @Singleton
    @RedditHttp
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
    fun provideRedditApi(
        @RedditHttp client: OkHttpClient,
        // RedditConfig is bound in :app (RedditConfigImpl) — the source
        // module only sees the interface, so no :app dep leaks here. The
        // config carries no back-reference to RedditApi/RedditSource, so
        // there's no Dagger cycle (#1309 shape) and no dagger.Lazy needed.
        config: `in`.jphe.storyvox.source.reddit.config.RedditConfig,
    ): RedditApi = RedditApi(client, config)
}
