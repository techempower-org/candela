package `in`.jphe.storyvox.source.epicfreegames.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.epicfreegames.net.EpicFreeGamesApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Dedicated OkHttp client qualifier for the Epic Free Games backend. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EpicFreeGamesHttp

@Module
@InstallIn(SingletonComponent::class)
internal object EpicFreeGamesHttpModule {

    @Provides
    @Singleton
    @EpicFreeGamesHttp
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
    fun provideEpicFreeGamesApi(
        @EpicFreeGamesHttp client: OkHttpClient,
    ): EpicFreeGamesApi = EpicFreeGamesApi(client)
}
