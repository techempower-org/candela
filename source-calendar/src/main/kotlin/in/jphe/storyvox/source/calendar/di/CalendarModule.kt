package `in`.jphe.storyvox.source.calendar.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.source.calendar.CalendarReader
import `in`.jphe.storyvox.source.calendar.DeviceCalendarReader

/**
 * Binds the on-device [DeviceCalendarReader] as the [CalendarReader] the
 * [`in`.jphe.storyvox.source.calendar.CalendarSource] injects (#1495).
 *
 * This is the *only* hand-written DI in the module: the `FictionSource`
 * multibindings themselves are emitted by KSP from `@SourcePlugin` (#1371).
 * `DeviceCalendarReader` only needs `@ApplicationContext Context`, which Hilt
 * provides for free — so no `@Provides`, and nothing to wire in `:app`.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class CalendarModule {

    @Binds
    abstract fun bindCalendarReader(impl: DeviceCalendarReader): CalendarReader
}
