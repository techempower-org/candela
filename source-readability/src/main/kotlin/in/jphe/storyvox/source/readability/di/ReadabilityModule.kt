package `in`.jphe.storyvox.source.readability.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.readability.ReadabilitySource
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifier for the OkHttp client used by the Readability fetcher.
 *  Article fetches can be slow (cold blog posts, syndicated news on
 *  origin servers) so the timeouts are intentionally generous; keeping
 *  them off the shared app client means a slow article doesn't poison
 *  unrelated requests. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ReadabilityHttp

@Module
@InstallIn(SingletonComponent::class)
internal object ReadabilityHttpModule {

    @Provides
    @Singleton
    @ReadabilityHttp
    fun provideClient(
        @UserAgentHeader userAgent: Interceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            // #1204 — shared descriptive UA on every request (see
            // in.jphe.storyvox.data.network.UserAgent).
            .addInterceptor(userAgent)
            .build()
}

/**
 * Contributes [ReadabilitySource] into the multi-source
 * `Map<String, FictionSource>` so a persisted readability fiction
 * row resolves back to this source after process restart. The
 * matching `@SourcePlugin` annotation on [ReadabilitySource] adds the
 * parallel `@IntoSet` SourcePluginDescriptor binding the registry
 * consumes.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ReadabilityBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.READABILITY)
    abstract fun bindFictionSource(impl: ReadabilitySource): FictionSource
}
