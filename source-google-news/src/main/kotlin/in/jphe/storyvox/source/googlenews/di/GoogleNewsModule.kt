package `in`.jphe.storyvox.source.googlenews.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.googlenews.GoogleNewsSource
import `in`.jphe.storyvox.source.googlenews.article.ArticleResolver
import `in`.jphe.storyvox.source.googlenews.article.NoOpArticleResolver
import `in`.jphe.storyvox.source.googlenews.net.GoogleNewsApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Dedicated OkHttp client for the Google News backend (#1238). Modest
 * timeouts — the RSS endpoints return small XML payloads; a slow
 * response should surface a network error rather than hang a Browse open.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GoogleNewsHttp

@Module
@InstallIn(SingletonComponent::class)
internal object GoogleNewsHttpModule {

    @Provides
    @Singleton
    @GoogleNewsHttp
    fun provideClient(
        @UserAgentHeader userAgent: Interceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            // #1204 — shared descriptive UA on every request (see
            // in.jphe.storyvox.data.network.UserAgent).
            .addInterceptor(userAgent)
            .build()

    @Provides
    @Singleton
    fun provideGoogleNewsApi(
        @GoogleNewsHttp client: OkHttpClient,
    ): GoogleNewsApi = GoogleNewsApi(client)
}

/**
 * Contributes [GoogleNewsSource] into the multi-source
 * `Map<String, FictionSource>` (legacy routing) alongside the
 * plugin-seam descriptor KSP emits from the `@SourcePlugin` annotation —
 * both coexist, the same pattern as `:source-rss` / `:source-hackernews`.
 * Also binds the v1 no-op [ArticleResolver]; a future full-text resolver
 * swaps this single binding.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class GoogleNewsBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.GOOGLE_NEWS)
    abstract fun bindFictionSource(impl: GoogleNewsSource): FictionSource

    @Binds
    @Singleton
    abstract fun bindArticleResolver(impl: NoOpArticleResolver): ArticleResolver
}
