package `in`.jphe.storyvox.sync

import `in`.jphe.storyvox.sync.client.FakeInstantBackend
import `in`.jphe.storyvox.sync.client.InstantBackend
import `in`.jphe.storyvox.sync.client.RowSnapshot
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.domain.InboxDelivery
import `in`.jphe.storyvox.sync.domain.InboxItem
import `in`.jphe.storyvox.sync.domain.InboxLimits
import `in`.jphe.storyvox.sync.domain.InboxLogic
import `in`.jphe.storyvox.sync.domain.InboxPayload
import `in`.jphe.storyvox.sync.domain.InboxSeenState
import `in`.jphe.storyvox.sync.domain.InboxSeenStore
import `in`.jphe.storyvox.sync.domain.InboxSink
import `in`.jphe.storyvox.sync.domain.InboxSyncer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1469 — push-to-Candela inbox: wire decoding, validation, the
 * seen-set / fresh-device / retry bookkeeping, and the syncer round-trip
 * against the in-memory backend.
 */
class InboxSyncerTest {

    private val user = SignedInUser(userId = "u-1", email = null, refreshToken = "rt-1")
    private val now = 10L * 24 * 60 * 60 * 1000 // day 10, in ms

    private fun url(id: String, at: Long = now - 1_000, u: String = "https://example.com/$id") =
        InboxItem(id = id, kind = InboxItem.KIND_URL, url = u, createdAt = at)

    private fun text(id: String, body: String = "Hello there.\n\nSecond para.", at: Long = now - 1_000) =
        InboxItem(id = id, kind = InboxItem.KIND_TEXT, text = body, createdAt = at)

    private class MemSeen(var state: InboxSeenState = InboxSeenState()) : InboxSeenStore {
        override suspend fun read() = state
        override suspend fun write(state: InboxSeenState) { this.state = state }
    }

    private class RecordingSink(private val answer: (InboxItem) -> InboxDelivery = { InboxDelivery.Delivered }) : InboxSink {
        val got = mutableListOf<String>()
        override suspend fun deliver(item: InboxItem): InboxDelivery {
            got += item.id
            return answer(item)
        }
    }

    private suspend fun seed(backend: InstantBackend, vararg items: InboxItem) {
        backend.upsert(
            user,
            InboxSyncer.ENTITY,
            SyncIds.rowUuid(InboxSyncer.DOMAIN, user.userId),
            InboxLogic.encode(InboxPayload(items = items.toList(), updatedAt = now)),
            now,
        )
    }

    // ── wire ────────────────────────────────────────────────────────────

    @Test
    fun `decode tolerates unknown fields and a future version`() {
        val raw = """{"v":2,"future":true,"items":[{"id":"a","kind":"url","url":"https://x.org","createdAt":5,"extra":1}],"updatedAt":9}"""
        val p = InboxLogic.decode(raw)!!
        assertEquals(2, p.v)
        assertEquals("https://x.org", p.items.single().url)
    }

    @Test
    fun `decode of garbage is null not a crash`() {
        assertNull(InboxLogic.decode("not json"))
        assertNull(InboxLogic.decode("""{"items":"nope"}"""))
    }

    @Test
    fun `encode then decode round-trips`() {
        val p = InboxPayload(items = listOf(url("a"), text("b")), updatedAt = 42)
        assertEquals(p, InboxLogic.decode(InboxLogic.encode(p)))
    }

    @Test
    fun `row id is the documented v3 uuid of inbox colon user id`() {
        // Pinned so tools/candela-push (Python) computes the same row id —
        // tools/test_candela_push.py asserts the identical value.
        assertEquals(
            "4ab5af9e-fcbb-34d2-9edc-12a70bd4e082",
            SyncIds.rowUuid("inbox", "11111111-2222-3333-4444-555555555555"),
        )
    }

    // ── validation ─────────────────────────────────────────────────────

    @Test
    fun `url validation accepts http and https only`() {
        assertNull(InboxLogic.invalidReason(url("a", u = "https://example.com/x")))
        assertNull(InboxLogic.invalidReason(url("a", u = "HTTP://example.com")))
        assertEquals("not an http(s) url", InboxLogic.invalidReason(url("a", u = "ftp://example.com")))
        assertEquals("not an http(s) url", InboxLogic.invalidReason(url("a", u = "https://")))
        assertEquals("not an http(s) url", InboxLogic.invalidReason(url("a", u = "https://a b.com")))
        assertEquals("missing url", InboxLogic.invalidReason(url("a", u = "  ")))
    }

    @Test
    fun `text validation rejects empty and oversized text`() {
        assertNull(InboxLogic.invalidReason(text("a")))
        assertEquals("empty text", InboxLogic.invalidReason(text("a", body = " \n ")))
        val huge = "x".repeat(InboxLimits.MAX_TEXT_CHARS + 1)
        assertEquals("text too long", InboxLogic.invalidReason(text("a", body = huge)))
    }

    // ── selection + bookkeeping ────────────────────────────────────────

    @Test
    fun `select skips seen ids and unknown kinds and orders oldest first`() {
        val payload = InboxPayload(
            items = listOf(
                url("new", at = now - 10),
                url("old", at = now - 500),
                url("seen", at = now - 50),
                InboxItem(id = "future", kind = "podcast", createdAt = now - 5),
            ),
        )
        val sel = InboxLogic.select(payload, InboxSeenState(initialized = true, seen = setOf("seen")), now)
        assertEquals(listOf("old", "new"), sel.deliver.map { it.id })
        assertTrue(sel.silentlySeen.isEmpty())
    }

    @Test
    fun `fresh device silently skips items older than the window`() {
        val stale = url("stale", at = now - InboxLimits.FRESH_DEVICE_WINDOW_MS - 1)
        val fresh = url("fresh", at = now - 1_000)
        val sel = InboxLogic.select(InboxPayload(items = listOf(stale, fresh)), InboxSeenState(), now)
        assertEquals(listOf("fresh"), sel.deliver.map { it.id })
        assertEquals(setOf("stale"), sel.silentlySeen)
    }

    @Test
    fun `initialized device imports old items it has never seen`() {
        val stale = url("stale", at = now - InboxLimits.FRESH_DEVICE_WINDOW_MS - 1)
        val sel = InboxLogic.select(InboxPayload(items = listOf(stale)), InboxSeenState(initialized = true), now)
        assertEquals(listOf("stale"), sel.deliver.map { it.id })
    }

    @Test
    fun `duplicate ids in one payload are delivered once`() {
        val sel = InboxLogic.select(
            InboxPayload(items = listOf(url("a"), url("a"))),
            InboxSeenState(initialized = true),
            now,
        )
        assertEquals(1, sel.deliver.size)
    }

    @Test
    fun `fold bounds seen-set to ids still on the row and gives up after max attempts`() {
        val payload = InboxPayload(items = listOf(url("a"), url("b"), url("c")))
        val prior = InboxSeenState(
            initialized = true,
            seen = setOf("gone-from-row"),
            attempts = mapOf("c" to InboxLimits.MAX_ATTEMPTS - 1),
        )
        val next = InboxLogic.fold(
            prior,
            payload,
            silentlySeen = emptySet(),
            results = mapOf(
                "a" to InboxDelivery.Delivered,
                "b" to InboxDelivery.Retry,
                "c" to InboxDelivery.Retry,
            ),
        )
        assertTrue(next.initialized)
        assertEquals(setOf("a", "c"), next.seen)
        assertEquals(mapOf("b" to 1), next.attempts)
    }

    @Test
    fun `poll throttle`() {
        assertTrue(InboxLogic.shouldPoll(0L, 5L))
        assertFalse(InboxLogic.shouldPoll(1_000L, 1_000L + InboxLimits.MIN_POLL_INTERVAL_MS - 1))
        assertTrue(InboxLogic.shouldPoll(1_000L, 1_000L + InboxLimits.MIN_POLL_INTERVAL_MS))
    }

    // ── text shaping ───────────────────────────────────────────────────

    @Test
    fun `title comes from the pusher or the first line`() {
        assertEquals("Given", InboxLogic.titleForText(text("a").copy(title = " Given ")))
        assertEquals("Hello there.", InboxLogic.titleForText(text("a")))
        assertEquals("Pushed text", InboxLogic.titleForText(text("a", body = "")))
        val long = "w".repeat(200)
        assertTrue(InboxLogic.titleForText(text("a", body = long)).endsWith("…"))
    }

    @Test
    fun `chapters normalise newlines and pack paragraphs under the cap`() {
        val body = "one\r\n\r\ntwo\n\n\n\nthree"
        assertEquals(listOf("one\n\ntwo\n\nthree"), InboxLogic.chaptersForText(body))
        val packed = InboxLogic.chaptersForText("aaaa\n\nbbbb\n\ncccc", maxChars = 10)
        assertEquals(listOf("aaaa\n\nbbbb", "cccc"), packed)
        // One over-long paragraph stays whole.
        assertEquals(listOf("x".repeat(30)), InboxLogic.chaptersForText("x".repeat(30), maxChars = 10))
        assertTrue(InboxLogic.chaptersForText("  \n\n ").isEmpty())
    }

    // ── syncer round-trip ──────────────────────────────────────────────

    @Test
    fun `pull delivers once and a second pull is a no-op`() = runTest {
        val backend = FakeInstantBackend()
        seed(backend, url("a"), text("b"))
        val sink = RecordingSink()
        val seen = MemSeen()
        val syncer = InboxSyncer(backend, sink, seen, clock = { now })

        assertEquals(SyncOutcome.Ok(2), syncer.pull(user))
        assertEquals(listOf("a", "b"), sink.got)

        assertEquals(SyncOutcome.Ok(0), syncer.pull(user))
        assertEquals(2, sink.got.size)
    }

    @Test
    fun `retry items are re-offered on the next pull, rejected ones are not`() = runTest {
        val backend = FakeInstantBackend()
        seed(backend, url("retry"), url("bad", u = "ftp://nope"))
        var calls = 0
        val sink = RecordingSink { calls++; InboxDelivery.Retry }
        val syncer = InboxSyncer(backend, sink, MemSeen(), clock = { now })

        syncer.pull(user)
        syncer.pull(user)
        // Invalid item never reaches the sink; the retry item reached it twice.
        assertEquals(listOf("retry", "retry"), sink.got)
        assertEquals(2, calls)
    }

    @Test
    fun `a throwing sink counts as retry and does not fail the round`() = runTest {
        val backend = FakeInstantBackend()
        seed(backend, url("a"))
        val seen = MemSeen()
        val syncer = InboxSyncer(backend, RecordingSink { error("boom") }, seen, clock = { now })
        assertEquals(SyncOutcome.Ok(0), syncer.pull(user))
        assertEquals(mapOf("a" to 1), seen.state.attempts)
    }

    @Test
    fun `missing row still initializes the device`() = runTest {
        val seen = MemSeen()
        val syncer = InboxSyncer(FakeInstantBackend(), RecordingSink(), seen, clock = { now })
        assertEquals(SyncOutcome.Ok(0), syncer.pull(user))
        assertTrue(seen.state.initialized)
    }

    @Test
    fun `fetch failure is transient and leaves seen-state untouched`() = runTest {
        val failing = object : InstantBackend {
            override val isConfigured = true
            override suspend fun fetch(user: SignedInUser, entity: String, id: String): Result<RowSnapshot?> =
                Result.failure(IllegalStateException("offline"))
            override suspend fun upsert(user: SignedInUser, entity: String, id: String, payload: String, updatedAt: Long) =
                Result.success(Unit)
            override suspend fun delete(user: SignedInUser, entity: String, id: String) = Result.success(Unit)
        }
        val seen = MemSeen()
        val outcome = InboxSyncer(failing, RecordingSink(), seen, clock = { now }).pull(user)
        assertTrue(outcome is SyncOutcome.Transient)
        assertFalse(seen.state.initialized)
    }

    @Test
    fun `push never writes the row and purge deletes it`() = runTest {
        val backend = FakeInstantBackend()
        seed(backend, url("a"))
        val syncer = InboxSyncer(backend, RecordingSink(), MemSeen(), clock = { now })
        val rowId = SyncIds.rowUuid(InboxSyncer.DOMAIN, user.userId)
        val before = backend.fetch(user, InboxSyncer.ENTITY, rowId).getOrNull()

        syncer.push(user)
        assertEquals(before, backend.fetch(user, InboxSyncer.ENTITY, rowId).getOrNull())

        assertTrue(syncer.purge(user) is SyncOutcome.Ok)
        assertNull(backend.fetch(user, InboxSyncer.ENTITY, rowId).getOrNull())
    }
}
