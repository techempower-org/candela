package `in`.jphe.storyvox.source.endless.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Dedicated OkHttp client qualifier for the endless-litrpg daemon. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EndlessLitrpgHttp

/**
 * Provides the `OkHttpClient` that
 * [`in`.jphe.storyvox.source.endless.net.EndlessLitrpgApi] uses. Qualified
 * [EndlessLitrpgHttp] so it can't collide with the other per-source
 * clients — there is no global unqualified client, so this module is
 * required for the `:app` Hilt graph to resolve (#1522). Don't delete it.
 *
 * No `@Provides` for the Api itself: it carries `@Singleton` +
 * `@Inject constructor`, so Hilt builds it directly (adding a `@Provides`
 * for the same type on top of that is a duplicate binding). Same shape as
 * `source-mempalace`'s `PalaceHttpModule`, which likewise provides only
 * the client.
 *
 * Timeouts are tuned for a LAN daemon, following the mempalace precedent:
 * a short connect timeout so "I'm off the home network" is discovered in
 * a second and a half rather than ten, and a generous read timeout
 * because a chapter payload carries the full markdown plus a manifest of
 * up to a few hundred segments.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object EndlessLitrpgHttpModule {

    @Provides
    @Singleton
    @EndlessLitrpgHttp
    fun provideClient(
        @UserAgentHeader userAgent: Interceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // The daemon serves its own absolute media URLs and we re-host
            // them on the configured authority (see EndlessLitrpgApi.mediaUrl);
            // following a redirect to an unvetted host would route around
            // the LAN guard, so redirects stay off.
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            // #1204 — shared descriptive User-Agent on every request.
            .addInterceptor(userAgent)
            .build()
}
