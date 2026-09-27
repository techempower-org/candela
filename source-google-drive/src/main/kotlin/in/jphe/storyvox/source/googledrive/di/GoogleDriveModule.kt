package `in`.jphe.storyvox.source.googledrive.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.googledrive.net.GoogleDriveApi
import `in`.jphe.storyvox.source.googledrive.parse.DefaultDriveBookReader
import `in`.jphe.storyvox.source.googledrive.parse.DriveBookReader
import java.io.File
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Dedicated OkHttp client qualifier for the Google Drive backend. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GoogleDriveHttp

/** #1677 — cache dir for downloaded Drive PDFs (the PDF text provider reads
 *  by URI, so the bytes are staged on disk). Under cacheDir: evictable. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GoogleDriveCache

@Module
@InstallIn(SingletonComponent::class)
internal object GoogleDriveHttpModule {

    @Provides
    @Singleton
    @GoogleDriveHttp
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
    fun provideGoogleDriveApi(
        @GoogleDriveHttp client: OkHttpClient,
    ): GoogleDriveApi = GoogleDriveApi(client)

    @Provides
    @Singleton
    @GoogleDriveCache
    fun provideCacheDir(@ApplicationContext ctx: Context): File =
        File(ctx.cacheDir, "googledrive")
}

/** #1677 — binds the EPUB/PDF parsing seam to the production reader. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class GoogleDriveReaderModule {
    @Binds
    abstract fun bindReader(impl: DefaultDriveBookReader): DriveBookReader
}
