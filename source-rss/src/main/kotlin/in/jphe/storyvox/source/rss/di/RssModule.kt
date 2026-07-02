package `in`.jphe.storyvox.source.rss.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.Lazy
import `in`.jphe.storyvox.data.repository.net.NetworkPatience
import `in`.jphe.storyvox.data.repository.net.NetworkPatienceConfig
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.rss.RssSource
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RssHttp

/**
 * OkHttpClient for RSS fetches. Separate from the app-wide client so
 * a slow feed server doesn't poison shared timeouts. 10s connect /
 * 30s read is generous because some serial-fiction Substacks
 * legitimately take that long for cold reads.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object RssHttpModule {

    @Provides
    @Singleton
    @RssHttp
    fun provideClient(
        patienceConfig: Lazy<NetworkPatienceConfig>,
        @UserAgentHeader userAgent: Interceptor,
    ): OkHttpClient {
        // Issue #597 — user-tunable patience preset. `Lazy<>` breaks
        // a Dagger graph cycle (NetworkPatienceConfig ↔ source-map
        // closure); see [RoyalRoadHttpModule.provideClient] for the
        // full explanation. Initial timeouts use the [Default]
        // preset; the per-call Interceptor below re-reads the pref
        // so Settings flips apply on the next call.
        val defaultPatience = NetworkPatience.Default
        return OkHttpClient.Builder()
            .connectTimeout(defaultPatience.connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(defaultPatience.readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(defaultPatience.writeTimeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            // #1204 — shared descriptive UA on every request
            // (see in.jphe.storyvox.data.network.UserAgent).
            .addInterceptor(userAgent)
            .addInterceptor { chain ->
                val patience = patienceConfig.get().currentPatienceSync()
                chain
                    .withConnectTimeout(patience.connectTimeoutSeconds.toInt(), TimeUnit.SECONDS)
                    .withReadTimeout(patience.readTimeoutSeconds.toInt(), TimeUnit.SECONDS)
                    .withWriteTimeout(patience.writeTimeoutSeconds.toInt(), TimeUnit.SECONDS)
                    .proceed(chain.request())
            }
            .build()
    }
}
