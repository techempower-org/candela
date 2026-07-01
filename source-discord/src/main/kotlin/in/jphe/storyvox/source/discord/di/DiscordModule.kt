package `in`.jphe.storyvox.source.discord.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.discord.DiscordSource
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DiscordHttp

/**
 * Dedicated OkHttp client for the Discord REST API. Tight connect
 * timeout (discord.com is on a fast global edge), generous read
 * timeout (a 100-message page can be ~250KB once attachments and
 * embeds are inlined). Connect retries handle the occasional
 * transient TLS hiccup we see on cellular.
 *
 * Discord uses HTTP/2 keep-alive; OkHttp pools connections so a
 * fictionDetail → chapter flow shares the same TLS handshake.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object DiscordHttpModule {

    @Provides
    @Singleton
    @DiscordHttp
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
            // #1204 — shared descriptive UA on every request
            // (see in.jphe.storyvox.data.network.UserAgent).
            .addInterceptor(userAgent)
            .build()

    @Provides
    @Singleton
    fun provideDiscordApi(
        @DiscordHttp client: OkHttpClient,
        config: `in`.jphe.storyvox.source.discord.config.DiscordConfig,
    ): `in`.jphe.storyvox.source.discord.net.DiscordApi =
        `in`.jphe.storyvox.source.discord.net.DiscordApi(client, config)
}

/**
 * Public-visibility DI for `:source-discord`: exposes
 * [DiscordGuildDirectory][in.jphe.storyvox.source.discord.DiscordGuildDirectory]
 * so `:app` can render the server picker without depending on the
 * module's internal wire types.
 *
 * Discord's repository routing and registry descriptor are both
 * generated from the `@SourcePlugin` annotation on [DiscordSource]
 * (#1400); this module no longer hand-writes an `@IntoMap` binding.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DiscordBindings {

    /** Public-visibility wrapper around the internal DiscordApi so
     *  :app can render the server picker without depending on the
     *  internal wire types. */
    @Binds
    @Singleton
    abstract fun bindDiscordGuildDirectory(
        impl: `in`.jphe.storyvox.source.discord.DiscordGuildDirectoryImpl,
    ): `in`.jphe.storyvox.source.discord.DiscordGuildDirectory
}
