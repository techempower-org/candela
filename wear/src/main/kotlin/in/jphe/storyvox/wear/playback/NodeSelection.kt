package `in`.jphe.storyvox.wear.playback

/**
 * Minimal, GMS-free projection of a connected Wear node.
 *
 * [WearPlaybackBridge] maps `com.google.android.gms.wearable.Node` into this
 * shape so the connectivity / targeting decision in [NodeSelection] can be unit
 * tested without Robolectric or Play Services on the classpath.
 */
data class PhoneNode(val id: String, val isNearby: Boolean)

/**
 * The pure decision that backs both the watch's connectivity state and which
 * node a transport command is sent to.
 *
 * "Connected" means at least one node is reachable. The preferred target is a
 * *nearby* node (direct Bluetooth/Wi-Fi) when one exists, otherwise the first
 * reachable node (cloud relay) — never dropping a command just because no node
 * is nearby, while still reporting honestly when nothing is reachable at all.
 */
object NodeSelection {

    fun isConnected(nodes: List<PhoneNode>): Boolean = nodes.isNotEmpty()

    fun preferredTarget(nodes: List<PhoneNode>): PhoneNode? =
        nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
}

/**
 * Outcome of a transport command send. The UI consults this (and the
 * connectivity [kotlinx.coroutines.flow.StateFlow]) to decide whether to show a
 * "Phone not connected" hint instead of pretending the tap worked.
 */
enum class SendResult {
    /** Delivered to a reachable node. */
    Sent,

    /** No node is reachable — the phone is out of range or Bluetooth is off. */
    Disconnected,

    /** A node was reachable but the send threw (e.g. it dropped mid-send). */
    Failed,
}
