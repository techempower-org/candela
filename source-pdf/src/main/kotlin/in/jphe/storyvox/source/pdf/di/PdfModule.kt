package `in`.jphe.storyvox.source.pdf.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.pdf.PdfSource
import `in`.jphe.storyvox.source.pdf.parse.NoOpPdfOcrTextProvider
import `in`.jphe.storyvox.source.pdf.parse.PdfOcrTextProvider
import javax.inject.Singleton

/**
 * Contributes [PdfSource] into the multi-source `Map<String,
 * FictionSource>` (#996). With this binding active, the segmented
 * source picker in Browse gets a "Local PDFs" entry, and any persisted
 * fiction with sourceId="pdf" routes through this source.
 *
 * Also provides the Phase-1 default [PdfOcrTextProvider]
 * ([NoOpPdfOcrTextProvider]) — scanned/image PDFs surface their
 * born-digital pages and skip image-only ones until #995's ML Kit OCR
 * provider replaces this binding. The binding is intentionally a plain
 * `@Provides` (not `@Binds`) so #995 can override it with a higher-
 * precedence module / replacement without touching this file.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class PdfBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.PDF)
    abstract fun bindFictionSource(impl: PdfSource): FictionSource

    companion object {
        /** Default OCR seam (#996 Phase 1). #995 replaces this with an
         *  ML Kit-backed provider; until then scanned pages return no
         *  text and are skipped. */
        @Provides
        @Singleton
        fun provideDefaultOcrProvider(): PdfOcrTextProvider = NoOpPdfOcrTextProvider()
    }
}
