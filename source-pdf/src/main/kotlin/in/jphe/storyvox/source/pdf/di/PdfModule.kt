package `in`.jphe.storyvox.source.pdf.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.source.pdf.PdfSource
import `in`.jphe.storyvox.source.pdf.parse.NoOpPdfOcrTextProvider
import `in`.jphe.storyvox.source.pdf.parse.PdfOcrTextProvider
import javax.inject.Singleton

/**
 * Provides the Phase-1 default [PdfOcrTextProvider]
 * ([NoOpPdfOcrTextProvider]) — scanned/image PDFs surface their
 * born-digital pages and skip image-only ones until #995's ML Kit OCR
 * provider replaces this binding. The binding is intentionally a plain
 * `@Provides` (not `@Binds`) so #995 can override it with a higher-
 * precedence module / replacement without touching this file.
 *
 * PDF's repository routing and registry descriptor are both generated
 * from the `@SourcePlugin` annotation on [PdfSource] (#1400); this
 * module no longer hand-writes an `@IntoMap` binding.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class PdfBindings {

    companion object {
        /** Default OCR seam (#996 Phase 1). #995 replaces this with an
         *  ML Kit-backed provider; until then scanned pages return no
         *  text and are skipped. */
        @Provides
        @Singleton
        fun provideDefaultOcrProvider(): PdfOcrTextProvider = NoOpPdfOcrTextProvider()
    }
}
