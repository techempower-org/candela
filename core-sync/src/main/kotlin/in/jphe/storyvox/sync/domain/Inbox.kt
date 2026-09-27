package `in`.jphe.storyvox.sync.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Issue #1469 — push-to-Candela inbox.
 *
 * A desktop / homelab client (`tools/candela-push`, or anything that speaks
 * the same wire shape) appends items to ONE InstantDB row per user:
 *
 *   entity `blobs`, id = [in.jphe.storyvox.sync.SyncIds.rowUuid]("inbox", userId)
 *
 * — the exact row family every other blob domain uses, so there is **no
 * InstantDB schema change**: the `blobs` entity and its `payload` /
 * `updatedAt` attributes already exist. The row's `payload` string is the
 * JSON-encoded [InboxPayload].
 *
 * ## Ownership model (why the phone never writes the row)
 *
 * Pushers own the row: they read-modify-write it (append + prune). The phone
 * is a pure READER — it remembers what it already imported in a local
 * [InboxSeenStore] and never writes back. That keeps the phone out of the
 * read-modify-write race entirely (a phone "ack by deleting" racing a desktop
 * append would silently lose the new item). The row stays bounded because
 * pushers prune by age and total size ([InboxLimits]); the phone's seen-set
 * stays bounded because it is intersected with the ids still on the row.
 *
 * Wire-shape is versioned ([InboxPayload.v]) and tolerant: unknown fields are
 * ignored, and an item of an unknown [InboxItem.kind] is left untouched (NOT
 * marked seen) so a later app version can still import it.
 */
@Serializable
data class InboxPayload(
    val v: Int = 1,
    val items: List<InboxItem> = emptyList(),
    val updatedAt: Long = 0L,
)

/**
 * One pushed thing. [id] is a pusher-minted UUID (dedup key). [kind] is
 * [KIND_URL] (import via the same resolver as Share → Candela / Magic-add) or
 * [KIND_TEXT] (a plain-text document). [from] is a free-form label for the
 * pushing host ("katana", "outline-webhook") — informational only.
 */
@Serializable
data class InboxItem(
    val id: String,
    val kind: String,
    val url: String? = null,
    val text: String? = null,
    val title: String? = null,
    val createdAt: Long = 0L,
    val from: String? = null,
) {
    companion object {
        const val KIND_URL: String = "url"
        const val KIND_TEXT: String = "text"
    }
}

/** Limits shared (by documentation — see docs/push-to-candela.md) with the
 *  desktop pusher. The phone enforces the per-item ones defensively too. */
object InboxLimits {
    /** Pushers drop items older than this on every write. */
    const val MAX_AGE_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** A fresh install / fresh sign-in only imports items younger than this;
     *  older ones are assumed consumed on the user's previous device. */
    const val FRESH_DEVICE_WINDOW_MS: Long = 24L * 60 * 60 * 1000

    /** Per-text-item cap (chars). Longer text is rejected, not truncated —
     *  a silently-truncated article is worse than a visible refusal. */
    const val MAX_TEXT_CHARS: Int = 200_000

    const val MAX_URL_CHARS: Int = 4_096
    const val MAX_TITLE_CHARS: Int = 300

    /** A retryable item (network blip on import) is retried on later polls
     *  at most this many times before being given up on. */
    const val MAX_ATTEMPTS: Int = 5

    /** Minimum gap between foreground-triggered inbox polls. */
    const val MIN_POLL_INTERVAL_MS: Long = 60_000L
}

/** What the app-side [InboxSink] did with one item. */
enum class InboxDelivery {
    /** Imported (or already present). Mark seen. */
    Delivered,

    /** Transient failure (offline, source 5xx). Try again next poll. */
    Retry,

    /** Can never be imported (unrecognised URL, unsupported source). Mark
     *  seen so it isn't re-attempted forever. */
    Rejected,
}

/**
 * App-side import seam. Implemented in `:app` on top of FictionRepository
 * (URLs) and the local text-document store (text). Kept as an interface so
 * `:core-sync` stays free of the source graph and the syncer is JVM-testable.
 */
interface InboxSink {
    suspend fun deliver(item: InboxItem): InboxDelivery
}

/**
 * Device-local memory of which inbox items were already handled. Survives
 * process death; does NOT sync (it's precisely per-device state).
 */
interface InboxSeenStore {
    suspend fun read(): InboxSeenState
    suspend fun write(state: InboxSeenState)
}

@Serializable
data class InboxSeenState(
    /** False until the first successful pull on this device. */
    val initialized: Boolean = false,
    val seen: Set<String> = emptySet(),
    /** Retry counts for items that returned [InboxDelivery.Retry]. */
    val attempts: Map<String, Int> = emptyMap(),
)

/** Pure decision logic for the inbox — no IO, unit-tested directly. */
object InboxLogic {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    /** Decode a row payload. Null on garbage (caller treats as empty inbox
     *  rather than crashing the sync round — a bad pusher must not wedge
     *  the phone). */
    fun decode(payload: String): InboxPayload? =
        runCatching { json.decodeFromString(InboxPayload.serializer(), payload) }.getOrNull()

    fun encode(payload: InboxPayload): String =
        json.encodeToString(InboxPayload.serializer(), payload)

    fun encodeSeen(state: InboxSeenState): String =
        json.encodeToString(InboxSeenState.serializer(), state)

    fun decodeSeen(raw: String?): InboxSeenState =
        raw?.let { runCatching { json.decodeFromString(InboxSeenState.serializer(), it) }.getOrNull() }
            ?: InboxSeenState()

    /** Whether this build knows how to import [item]'s kind. */
    fun isKnownKind(item: InboxItem): Boolean =
        item.kind == InboxItem.KIND_URL || item.kind == InboxItem.KIND_TEXT

    /**
     * Structural validation, independent of whether a source can resolve it.
     * Returns null when valid, else a short reason (for logs).
     */
    fun invalidReason(item: InboxItem): String? {
        if (item.id.isBlank()) return "blank id"
        if ((item.title?.length ?: 0) > InboxLimits.MAX_TITLE_CHARS) return "title too long"
        return when (item.kind) {
            InboxItem.KIND_URL -> {
                val url = item.url?.trim().orEmpty()
                when {
                    url.isEmpty() -> "missing url"
                    url.length > InboxLimits.MAX_URL_CHARS -> "url too long"
                    !isHttpUrl(url) -> "not an http(s) url"
                    else -> null
                }
            }
            InboxItem.KIND_TEXT -> {
                val text = item.text.orEmpty()
                when {
                    text.isBlank() -> "empty text"
                    text.length > InboxLimits.MAX_TEXT_CHARS -> "text too long"
                    else -> null
                }
            }
            else -> "unknown kind"
        }
    }

    fun isHttpUrl(s: String): Boolean {
        val t = s.trim()
        val rest = when {
            t.startsWith("https://", ignoreCase = true) -> t.substring(8)
            t.startsWith("http://", ignoreCase = true) -> t.substring(7)
            else -> return false
        }
        // Needs a host and no whitespace anywhere.
        return rest.isNotEmpty() && rest[0] != '/' && t.none { it.isWhitespace() }
    }

    /**
     * Pick the items to hand to the sink this round, in push order (oldest
     * first, so the Library ends up in the order the user sent them).
     *
     * - already-seen ids are skipped;
     * - unknown kinds are skipped *without* being marked (forward compat);
     * - on a device's first pull ([InboxSeenState.initialized] false), items
     *   older than [InboxLimits.FRESH_DEVICE_WINDOW_MS] are returned in
     *   [Selection.silentlySeen] instead — they were almost certainly
     *   imported on the user's previous phone.
     * - duplicate ids within one payload are collapsed (first wins).
     */
    fun select(payload: InboxPayload, state: InboxSeenState, now: Long): Selection {
        val deliver = mutableListOf<InboxItem>()
        val silent = mutableSetOf<String>()
        val ids = mutableSetOf<String>()
        payload.items
            .sortedBy { it.createdAt }
            .forEach { item ->
                if (!ids.add(item.id)) return@forEach
                if (item.id in state.seen) return@forEach
                if (!isKnownKind(item)) return@forEach
                if (!state.initialized && now - item.createdAt > InboxLimits.FRESH_DEVICE_WINDOW_MS) {
                    silent += item.id
                } else {
                    deliver += item
                }
            }
        return Selection(deliver = deliver, silentlySeen = silent)
    }

    data class Selection(val deliver: List<InboxItem>, val silentlySeen: Set<String>)

    /**
     * Fold one round's results into the next persisted state. The seen-set
     * and attempt counters are intersected with the ids still present on the
     * remote row, so local state can never grow past the row's own bound.
     */
    fun fold(
        prior: InboxSeenState,
        payload: InboxPayload,
        silentlySeen: Set<String>,
        results: Map<String, InboxDelivery>,
    ): InboxSeenState {
        val live = payload.items.mapTo(mutableSetOf()) { it.id }
        val seen = (prior.seen + silentlySeen).toMutableSet()
        val attempts = prior.attempts.toMutableMap()
        results.forEach { (id, r) ->
            when (r) {
                InboxDelivery.Delivered, InboxDelivery.Rejected -> {
                    seen += id
                    attempts.remove(id)
                }
                InboxDelivery.Retry -> {
                    val n = (attempts[id] ?: 0) + 1
                    if (n >= InboxLimits.MAX_ATTEMPTS) {
                        seen += id
                        attempts.remove(id)
                    } else {
                        attempts[id] = n
                    }
                }
            }
        }
        return InboxSeenState(
            initialized = true,
            seen = seen.filterTo(mutableSetOf()) { it in live },
            attempts = attempts.filterKeys { it in live },
        )
    }

    /**
     * Display title for a pushed text item: the pusher's [InboxItem.title]
     * when given, else the first non-blank line (clipped), else a fallback.
     */
    fun titleForText(item: InboxItem, fallback: String = "Pushed text"): String {
        item.title?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(InboxLimits.MAX_TITLE_CHARS) }
        val first = item.text.orEmpty().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: return fallback
        return if (first.length <= TITLE_FROM_TEXT_CHARS) first
        else first.take(TITLE_FROM_TEXT_CHARS).trimEnd() + "…"
    }

    /**
     * Normalise pushed plain text into chapters for the local text store:
     * CRLF → LF, runs of 3+ newlines collapsed to a paragraph break, then
     * paragraphs packed greedily into chapters of at most [maxChars] (a
     * single over-long paragraph becomes its own chapter rather than being
     * split mid-sentence). Paragraphs stay separated by a blank line, which is
     * what the text store's reader splits on.
     */
    fun chaptersForText(text: String, maxChars: Int = CHAPTER_CHARS): List<String> {
        val paragraphs = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return emptyList()
        val chapters = mutableListOf<String>()
        val current = StringBuilder()
        for (p in paragraphs) {
            if (current.isNotEmpty() && current.length + 2 + p.length > maxChars) {
                chapters += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(p)
        }
        if (current.isNotEmpty()) chapters += current.toString()
        return chapters
    }

    private const val TITLE_FROM_TEXT_CHARS = 80
    private const val CHAPTER_CHARS = 15_000

    /** Throttle for foreground-triggered polls. [lastPollAt] 0 = never. */
    fun shouldPoll(lastPollAt: Long, now: Long): Boolean =
        lastPollAt <= 0L || now - lastPollAt >= InboxLimits.MIN_POLL_INTERVAL_MS
}
