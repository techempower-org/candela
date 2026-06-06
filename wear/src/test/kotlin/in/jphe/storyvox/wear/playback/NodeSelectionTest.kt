package `in`.jphe.storyvox.wear.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the node-selection rule that backs [WearPlaybackBridge]'s
 * connectivity state and command targeting.
 *
 * The GMS `Node` type is mapped to [PhoneNode] before this logic runs, so these
 * tests need neither Robolectric nor Play Services — they exercise the decision
 * in isolation (the seam pattern, mirroring DocumentImportClassifier for #1000).
 */
class NodeSelectionTest {

    private fun node(id: String, nearby: Boolean) = PhoneNode(id = id, isNearby = nearby)

    @Test
    fun `no nodes means disconnected and no target`() {
        val nodes = emptyList<PhoneNode>()
        assertFalse(NodeSelection.isConnected(nodes))
        assertNull(NodeSelection.preferredTarget(nodes))
    }

    @Test
    fun `any node means connected`() {
        val nodes = listOf(node("a", nearby = false))
        assertTrue(NodeSelection.isConnected(nodes))
    }

    @Test
    fun `prefers a nearby node over a cloud-only node`() {
        val nodes = listOf(
            node("cloud", nearby = false),
            node("watch-paired", nearby = true),
        )
        assertEquals("watch-paired", NodeSelection.preferredTarget(nodes)?.id)
    }

    @Test
    fun `falls back to first node when none are nearby`() {
        val nodes = listOf(
            node("cloud-1", nearby = false),
            node("cloud-2", nearby = false),
        )
        // No nearby node exists, but the phone is still reachable via the cloud
        // relay — we target the first and report connected rather than dropping
        // the command.
        assertEquals("cloud-1", NodeSelection.preferredTarget(nodes)?.id)
        assertTrue(NodeSelection.isConnected(nodes))
    }

    @Test
    fun `single nearby node is the target`() {
        val nodes = listOf(node("only", nearby = true))
        assertEquals("only", NodeSelection.preferredTarget(nodes)?.id)
    }
}
