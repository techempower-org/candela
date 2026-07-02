package `in`.jphe.storyvox.source.readability.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
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
