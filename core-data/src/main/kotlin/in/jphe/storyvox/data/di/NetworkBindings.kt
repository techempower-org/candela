package `in`.jphe.storyvox.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.AndroidConnectivityObserver
import `in`.jphe.storyvox.data.network.ConnectivityObserver
import javax.inject.Singleton

/**
 * Hilt graph for the `:core-data` network layer (issue #786). Kept in its own
 * module rather than folded into [DataModule] so the connectivity seam is
 * discoverable and so source modules can depend on the binding without pulling
 * the whole repository graph into view.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindings {

    @Binds
    @Singleton
    abstract fun bindConnectivityObserver(
        impl: AndroidConnectivityObserver,
    ): ConnectivityObserver
}
