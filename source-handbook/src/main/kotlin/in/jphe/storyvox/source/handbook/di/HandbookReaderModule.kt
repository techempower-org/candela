package `in`.jphe.storyvox.source.handbook.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.source.handbook.AssetCandelaHandbookReader
import `in`.jphe.storyvox.source.handbook.CandelaHandbookReader

/**
 * Binds the handbook read seam to its asset-backed implementation.
 *
 * dx note (#1526 dogfood): `new-source.sh --local` deliberately emits NO di/
 * module (its Reader is a concrete `@Inject` class). But a Context-backed local
 * source that wants pure-JVM tests uses the source-ocr shape — an interface with
 * a bound impl — which needs this one `@Binds`. That's the honest delta from the
 * scaffold's "zero DI" claim for Context-backed local providers.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class HandbookReaderModule {
    @Binds
    abstract fun bindReader(impl: AssetCandelaHandbookReader): CandelaHandbookReader
}
