package `in`.jphe.storyvox.playback

import android.support.v4.media.session.MediaSessionCompat
import androidx.media3.session.SessionToken
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridge so that [auto.StoryvoxAutoBrowserService] (a legacy
 * `MediaBrowserServiceCompat`) can find the playback service's session token
 * without a direct service-to-service dependency. The playback service writes
 * both tokens on its `onCreate`; the auto browser reads them on demand.
 *
 * Issue #1232 — Android Auto binds transport controls through the **legacy**
 * [compatToken] (`MediaSessionCompat.Token`), which is what
 * `MediaBrowserServiceCompat.setSessionToken()` requires; the Media3 [token]
 * (`SessionToken`) is the wrong type for that call. [compatToken] is a
 * [StateFlow] so the browser service — which can be created before the playback
 * service — can await the token rather than racing it.
 */
@Singleton
class MediaSessionLocator @Inject constructor() {
    /** Media3 session token. Retained for any Media3-side consumer. */
    @Volatile var token: SessionToken? = null

    private val _compatToken = MutableStateFlow<MediaSessionCompat.Token?>(null)

    /** Legacy token the Auto browser service hands to `setSessionToken()`.
     *  Null until the playback service has built its session. */
    val compatToken: StateFlow<MediaSessionCompat.Token?> = _compatToken.asStateFlow()

    fun setCompatToken(value: MediaSessionCompat.Token?) {
        _compatToken.value = value
    }
}
