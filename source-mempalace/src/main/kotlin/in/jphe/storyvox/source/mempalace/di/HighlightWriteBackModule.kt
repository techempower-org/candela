package `in`.jphe.storyvox.source.mempalace.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.annotation.HighlightWriteBack
import `in`.jphe.storyvox.source.mempalace.writeback.PalaceHighlightWriteBack

/**
 * Issue #1468 — binds the core-data [HighlightWriteBack] seam (consumed by
 * the reader in `:feature`) to the Memory Palace implementation.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class HighlightWriteBackModule {

    @Binds
    abstract fun bindHighlightWriteBack(impl: PalaceHighlightWriteBack): HighlightWriteBack
}
