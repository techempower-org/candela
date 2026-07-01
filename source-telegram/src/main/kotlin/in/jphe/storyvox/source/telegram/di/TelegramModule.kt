package `in`.jphe.storyvox.source.telegram.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.source.telegram.TelegramSource
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TelegramHttp

/**
 * Dedicated OkHttp client for the Telegram Bot API. Slightly longer
 * read timeout than Discord because `getUpdates` long-polls when the
 * bot is idle — though v1 calls it synchronously with `timeout=0`
 * (the default), a future long-polling follow-up needs the headroom.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object TelegramHttpModule {

    @Provides
    @Singleton
    @TelegramHttp
    fun provideClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

    @Provides
    @Singleton
    fun provideTelegramApi(
        @TelegramHttp client: OkHttpClient,
        config: `in`.jphe.storyvox.source.telegram.config.TelegramConfig,
    ): `in`.jphe.storyvox.source.telegram.net.TelegramApi =
        `in`.jphe.storyvox.source.telegram.net.TelegramApi(client, config)
}

/**
 * Public-visibility DI for `:source-telegram`: exposes
 * [TelegramChannelDirectory][in.jphe.storyvox.source.telegram.TelegramChannelDirectory]
 * so `:app` can render the authentication probe without depending on
 * the module's internal wire types.
 *
 * Telegram's repository routing and registry descriptor are both
 * generated from the `@SourcePlugin` annotation on [TelegramSource]
 * (#1400); this module no longer hand-writes an `@IntoMap` binding.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class TelegramBindings {

    /** Public-visibility wrapper around the internal TelegramApi so
     *  :app can render the Settings authentication probe without
     *  depending on the internal wire types. */
    @Binds
    @Singleton
    abstract fun bindTelegramChannelDirectory(
        impl: `in`.jphe.storyvox.source.telegram.TelegramChannelDirectoryImpl,
    ): `in`.jphe.storyvox.source.telegram.TelegramChannelDirectory
}
