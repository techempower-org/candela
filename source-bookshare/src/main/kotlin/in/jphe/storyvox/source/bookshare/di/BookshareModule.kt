package `in`.jphe.storyvox.source.bookshare.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.bookshare.BookshareSource
import javax.inject.Singleton

/**
 * Issue #1002 — contributes [BookshareSource] into the multi-source
 * `Map<String, FictionSource>` so persisted rows with `sourceId="bookshare"`
 * resolve through it.
 *
 * The legacy `@IntoMap @StringKey` binding runs in parallel with the
 * auto-generated `@SourcePlugin` descriptor binding emitted by
 * `:core-plugin-ksp` — the same Phase 2 transitional pattern every other
 * source module uses (#384). Phase 3 will remove the legacy bindings across
 * the board.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class BookshareBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.BOOKSHARE)
    abstract fun bindFictionSource(impl: BookshareSource): FictionSource
}
