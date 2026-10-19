package `in`.jphe.storyvox.source.questionablequesting.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.questionablequesting.net.QuestionablequestingApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Dedicated OkHttp client qualifier for the Questionable Questing backend. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class QuestionablequestingHttp

@Module
@InstallIn(SingletonComponent::class)
internal object QuestionablequestingHttpModule {

    @Provides
    @Singleton
    @QuestionablequestingHttp
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
    fun provideQuestionablequestingApi(
        @QuestionablequestingHttp client: OkHttpClient,
    ): QuestionablequestingApi = QuestionablequestingApi(client)
}
