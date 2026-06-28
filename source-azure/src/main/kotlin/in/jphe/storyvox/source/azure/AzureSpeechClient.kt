package `in`.jphe.storyvox.source.azure

import android.os.SystemClock
import `in`.jphe.storyvox.data.network.UserAgent
import `in`.jphe.storyvox.data.source.AzureVoiceDescriptor
import `in`.jphe.storyvox.source.azure.di.AzureHttp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Errors emitted by [AzureSpeechClient.synthesize]. The PR-5 (Solara's
 * plan) error-handling pass elevates these into the `PlaybackState.error`
 * channel and drives the picker's offline / auth-error UI; for PR-2
 * we just thread the failure shape so `AzureVoiceEngine` and its
 * tests can branch on it.
 */
sealed class AzureError(message: String, cause: Throwable? = null) :
    IOException(message, cause) {

    /** 401 / 403 — bad / revoked subscription key. The Settings flow
     *  invalidates the key and prompts the user to re-paste. */
    class AuthFailed(message: String) : AzureError(message)

    /** 429 — throttled. Retried with backoff inside the client; this
     *  type only escapes after the retry budget is exhausted. */
    class Throttled(message: String) : AzureError(message)

    /** 4xx that isn't 401/403/429 — bad SSML, unsupported voice,
     *  region mismatch. Surfaces as a one-shot toast; not retryable. */
    class BadRequest(val httpCode: Int, message: String) : AzureError(message)

    /** 5xx — Azure-side outage. Retried with backoff; this type
     *  escapes only after retries fail. */
    class ServerError(val httpCode: Int, message: String) : AzureError(message)

    /** TCP / TLS / DNS failure — treated as offline by callers
     *  (fallback path or error state). */
    class NetworkError(cause: Throwable) :
        AzureError(cause.message ?: cause::class.java.simpleName, cause)
}

/**
 * Thin OkHttp wrapper around Azure Speech Services' synthesis
 * endpoint. One operation: POST SSML, return raw PCM bytes.
 *
 * **Endpoint:**
 * `https://{region}.tts.speech.microsoft.com/cognitiveservices/v1`
 *
 * **Auth:** `Ocp-Apim-Subscription-Key: {key}` header. Single header,
 * no token refresh, no signer — the BYOK simplicity Solara's spec
 * cited as the primary reason for picking Azure over GCP/AWS.
 *
 * **Output format:** `raw-24khz-16bit-mono-pcm` per Solara's
 * recommendation in open question #7. 24 kHz matches Kokoro's
 * sample rate so the AudioTrack rebuild on voice swap stays cheap;
 * Azure renders HD voices at 24 kHz natively, so the lower 16 kHz
 * option would just downsample server-side anyway.
 *
 * **Retries are NOT implemented here.** PR-5 in Solara's plan adds
 * the 429/5xx exponential backoff; PR-2 stops at "map the response
 * code to an [AzureError] type". A retrying wrapper around
 * [AzureSpeechClient.synthesize] is the right shape for the retry
 * policy and stays a follow-up PR.
 *
 * **Logging redaction.** The `Ocp-Apim-Subscription-Key` header is
 * redacted by the [HttpLoggingInterceptor] configured in
 * `AzureModule.provideAzureHttp` so even at BODY level no key bytes
 * land in logcat. The class itself never prints — debug logging of
 * the SSML body is fine (chapter text, not secret), but headers are
 * scrubbed.
 *
 * **Body buffering.** Solara's spec picks "Option A — read fully into
 * ByteArray" for v1 (one sentence per request, ~80 KB at 24 kHz mono
 * for ~5 s of audio). Sub-sentence streaming (Option B) is a PR-9
 * follow-up if real-world latency proves problematic. The full
 * sentence in memory drops cleanly into the existing `PcmAppender`
 * pipeline shape that Piper/Kokoro already use.
 */
@Singleton
open class AzureSpeechClient @Inject constructor(
    @AzureHttp private val http: OkHttpClient,
    private val credentials: AzureCredentials,
) {

    /**
     * Issue #291 — wall-clock round-trip latency for the most recent
     * synthesize() (buffered or streaming). null until the first call
     * returns. Resets on every call entry — observers see the
     * `pendingRequests > 0` flag alongside to distinguish "in flight"
     * from "this is the value for the call that just completed".
     *
     * Read by RealDebugRepositoryUi for the Debug overlay's Azure
     * latency row. Off the hot path; updating a StateFlow is a single
     * volatile write.
     */
    private val _lastSynthLatencyMs = MutableStateFlow<Long?>(null)
    open val lastSynthLatencyMs: StateFlow<Long?> = _lastSynthLatencyMs.asStateFlow()

    /**
     * Issue #291 — count of in-flight synthesize() calls. Buffered
     * synthesize() is normally single-flight per chapter; the
     * streaming variant can briefly overlap with a follow-up sentence
     * while the audio pipeline drains the previous one. Useful for
     * spotting "Azure is stuck and we keep firing new requests".
     */
    private val _pendingRequests = MutableStateFlow(0)
    open val pendingRequests: StateFlow<Int> = _pendingRequests.asStateFlow()

    /** Open for tests — MockWebServer overrides this to point at a
     *  local URL. Production callers leave it at the default. */
    protected open fun endpointUrlFor(regionId: String): String =
        "https://$regionId.tts.speech.microsoft.com/cognitiveservices/v1"

    /** Open for tests — MockWebServer overrides this. The voices/list
     *  endpoint is a cheap GET that exercises auth + DNS + TLS without
     *  billing any synthesis characters; Settings → Cloud Voices →
     *  Azure → "Test connection" hits it. */
    protected open fun voicesListUrlFor(regionId: String): String =
        "https://$regionId.tts.speech.microsoft.com/cognitiveservices/voices/list"

    /**
     * Sleep override for tests — production [Thread.sleep], unit
     * tests substitute a no-op so the backoff timing doesn't slow
     * CI runs by 1-2s per retry test.
     */
    protected open fun sleepMillis(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            // Reset interrupt flag so cancellation propagates cleanly.
            Thread.currentThread().interrupt()
        }
    }

    /**
     * PR-5 (#184) — exponential-backoff retry helper for transient
     * Azure failures (429 throttle, 5xx server errors). [synthesize]
     * and [voicesList] both wrap their HTTP calls in this. AuthFailed
     * and BadRequest are NOT retried (re-issuing won't help — the key
     * is bad or the SSML is wrong); NetworkError isn't retried either
     * since OkHttp's `retryOnConnectionFailure` handles transient
     * socket faults at a lower layer.
     *
     * Backoff schedule per Solara's spec: 250ms / 500ms / 1s, max 3
     * attempts (initial + 2 retries). Jitter via a small random
     * additive offset so multiple devices recovering from the same
     * Azure incident don't synchronize their retries.
     */
    private inline fun <T> withRetry(crossinline block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: AzureError.Throttled) {
                if (attempt >= MAX_RETRIES) throw e
                sleepMillis(retryDelayMs(attempt))
                attempt++
            } catch (e: AzureError.ServerError) {
                if (attempt >= MAX_RETRIES) throw e
                sleepMillis(retryDelayMs(attempt))
                attempt++
            }
        }
    }

    /** Backoff delay for the given attempt index (0-based). 250ms,
     *  500ms, 1s with up to +25% jitter. */
    private fun retryDelayMs(attempt: Int): Long {
        val base = RETRY_DELAYS_MS[attempt.coerceAtMost(RETRY_DELAYS_MS.size - 1)]
        val jitter = (base / 4 * Math.random()).toLong()
        return base + jitter
    }

    /**
     * POST [ssml] to Azure, return the raw PCM body.
     *
     * @throws AzureError.AuthFailed   on 401 / 403 (bad key)
     * @throws AzureError.Throttled    on 429
     * @throws AzureError.BadRequest   on other 4xx (bad SSML, etc.)
     * @throws AzureError.ServerError  on 5xx
     * @throws AzureError.NetworkError on TCP / TLS / DNS failure
     */
    open fun synthesize(ssml: String): ByteArray {
        // Issue #291 — instrument the buffered synthesis path so the
        // Debug overlay can show last-latency + pendingRequests. The
        // wall-clock measurement spans the retry budget (any 429 /
        // server-error backoff is counted) — what the user actually
        // waited for an audible result. try/finally guarantees the
        // counter and latency get updated even when AzureError types
        // propagate up.
        val start = SystemClock.elapsedRealtime()
        _pendingRequests.value = _pendingRequests.value + 1
        try {
            return withRetry { synthesizeOnce(ssml) }
        } finally {
            _lastSynthLatencyMs.value = SystemClock.elapsedRealtime() - start
            _pendingRequests.value = (_pendingRequests.value - 1).coerceAtLeast(0)
        }
    }

    /**
     * Streaming variant — emits PCM bytes as they arrive over the
     * wire instead of buffering the full sentence first. Lets callers
     * begin writing to AudioTrack on the first frame, dropping
     * first-audio latency from "TLS + full server-side render"
     * (~2–4 s for Dragon HD) to "TLS + first-byte" (~200–400 ms).
     *
     * **Auth + region** identical to [synthesize]. **Errors thrown**
     * before the first emit (auth / network / 4xx); errors mid-stream
     * close the flow with the typed [AzureError].
     *
     * **Chunk size**: emits whatever OkHttp's source hands us per
     * read — typically ~8 KB on the SSL transport. ~165 ms of audio
     * at 24 kHz mono.
     *
     * **No retry.** Mid-stream retry would replay sentence audio
     * already heard by the user; the [withRetry] wrapper around
     * [synthesize] only makes sense when we can hand back a fresh
     * ByteArray. Streaming callers handle errors by aborting that
     * sentence and moving on.
     */
    open fun synthesizeStreaming(ssml: String): Flow<ByteArray> = flow {
        // Issue #291 — instrument the streaming synth path. The
        // measurement here is "time from request start to flow
        // completion (or cancellation)" — what callers experienced
        // end-to-end, not just first-byte. onStart/onCompletion
        // would also work but inline lets us read `start` in the
        // failure path that re-throws after recording latency.
        val streamStart = SystemClock.elapsedRealtime()
        _pendingRequests.value = _pendingRequests.value + 1
        try {
        val key = credentials.key()
            ?: throw AzureError.AuthFailed("No Azure subscription key configured")
        val regionId = credentials.regionId()

        val request = Request.Builder()
            .url(endpointUrlFor(regionId))
            .header(HEADER_KEY, key)
            .header(HEADER_OUTPUT_FORMAT, OUTPUT_FORMAT_PCM_24KHZ)
            .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_SSML)
            .header(HEADER_USER_AGENT, USER_AGENT)
            .post(ssml.toRequestBody(SSML_MEDIA_TYPE))
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw AzureError.NetworkError(e)
        }

        response.use { resp ->
            when {
                resp.isSuccessful -> {
                    val source = resp.body?.source() ?: return@use
                    val buf = ByteArray(STREAM_CHUNK_BYTES)
                    while (!source.exhausted()) {
                        val n = try {
                            source.read(buf, 0, buf.size).toInt()
                        } catch (e: IOException) {
                            throw AzureError.NetworkError(e)
                        }
                        if (n <= 0) break
                        emit(buf.copyOfRange(0, n))
                    }
                }
                resp.code == 401 || resp.code == 403 ->
                    throw AzureError.AuthFailed(
                        "Azure rejected key (HTTP ${resp.code}): " +
                            (resp.message.takeIf { it.isNotBlank() }
                                ?: "authentication failed"),
                    )
                resp.code == 429 ->
                    throw AzureError.Throttled(
                        "Azure throttled the request (HTTP 429). " +
                            "Free-tier quota exhausted, or burst limit hit.",
                    )
                resp.code in 400..499 -> {
                    val excerpt = resp.body?.string()?.take(256) ?: resp.message
                    throw AzureError.BadRequest(
                        resp.code,
                        "Azure rejected request (HTTP ${resp.code}): $excerpt",
                    )
                }
                resp.code in 500..599 ->
                    throw AzureError.ServerError(
                        resp.code,
                        "Azure server error (HTTP ${resp.code}): " +
                            (resp.message.takeIf { it.isNotBlank() } ?: "unknown"),
                    )
                else ->
                    throw AzureError.ServerError(
                        resp.code,
                        "Unexpected HTTP ${resp.code} from Azure",
                    )
            }
        }
        } finally {
            _lastSynthLatencyMs.value = SystemClock.elapsedRealtime() - streamStart
            _pendingRequests.value = (_pendingRequests.value - 1).coerceAtLeast(0)
        }
    }

    private fun synthesizeOnce(ssml: String): ByteArray {
        val key = credentials.key()
            ?: throw AzureError.AuthFailed("No Azure subscription key configured")
        val regionId = credentials.regionId()

        val request = Request.Builder()
            .url(endpointUrlFor(regionId))
            .header(HEADER_KEY, key)
            .header(HEADER_OUTPUT_FORMAT, OUTPUT_FORMAT_PCM_24KHZ)
            .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_SSML)
            .header(HEADER_USER_AGENT, USER_AGENT)
            .post(ssml.toRequestBody(SSML_MEDIA_TYPE))
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw AzureError.NetworkError(e)
        }

        return response.use { resp ->
            when {
                resp.isSuccessful -> {
                    // The whole sentence body comes back as one chunked
                    // response. Reading it fully here matches the
                    // existing engine-handle contract (return ByteArray).
                    // For ~5 s of audio at 24 kHz mono that's ~240 KB —
                    // bounded, fits in a single RequestBody, no need to
                    // page through chunks.
                    resp.body?.bytes() ?: ByteArray(0)
                }
                resp.code == 401 || resp.code == 403 -> {
                    throw AzureError.AuthFailed(
                        "Azure rejected key (HTTP ${resp.code}): " +
                            (resp.message.takeIf { it.isNotBlank() }
                                ?: "authentication failed"),
                    )
                }
                resp.code == 429 -> {
                    throw AzureError.Throttled(
                        "Azure throttled the request (HTTP 429). " +
                            "Free-tier quota exhausted, or burst limit hit.",
                    )
                }
                resp.code in 400..499 -> {
                    val excerpt = resp.body?.string()?.take(256) ?: resp.message
                    throw AzureError.BadRequest(
                        resp.code,
                        "Azure rejected request (HTTP ${resp.code}): $excerpt",
                    )
                }
                resp.code in 500..599 -> {
                    throw AzureError.ServerError(
                        resp.code,
                        "Azure server error (HTTP ${resp.code}): " +
                            (resp.message.takeIf { it.isNotBlank() } ?: "unknown"),
                    )
                }
                else -> {
                    throw AzureError.ServerError(
                        resp.code,
                        "Unexpected HTTP ${resp.code} from Azure",
                    )
                }
            }
        }
    }

    /**
     * GET the `voices/list` endpoint and return the parsed voice count.
     * No SSML, no billing — just a cheap auth/connectivity probe that
     * Settings → Cloud Voices → Azure → "Test connection" calls.
     *
     * Returns the count of voice entries in the JSON array on success.
     * Throws the same [AzureError] hierarchy as [synthesize] so the
     * caller can branch on auth-fail vs network-fail vs server-fail
     * with the same shape it'd see during real synthesis.
     *
     * The body parse is intentionally cheap — we don't deserialize the
     * full voice schema (Azure's voices/list payload is ~150 KB of JSON
     * with 600+ voices), just walk the response counting top-level
     * `{` braces inside the array. PR-7 (full roster fetch) parses the
     * full payload; for the test-connection probe, "200 OK + non-empty
     * array" is enough.
     *
     * @throws AzureError.AuthFailed   on 401 / 403
     * @throws AzureError.BadRequest   on other 4xx
     * @throws AzureError.ServerError  on 5xx
     * @throws AzureError.NetworkError on TCP / TLS / DNS failure
     */
    open fun voicesList(): Int = withRetry {
        voicesListOnce()
    }

    /**
     * Like [voicesList] but parses the response into structured
     * [AzureVoiceDescriptor]s instead of just counting `{` braces.
     * Used by the live-roster path that feeds the picker. Same auth /
     * region handling as [voicesList]; same retry/error taxonomy.
     *
     * Returns an empty list rather than throwing on a parse failure —
     * the caller should surface "no voices in this region" the same
     * way it does for the no-key case. Network and auth failures
     * still throw [AzureError] so the caller can distinguish "your
     * key is bad" from "your region has no voices".
     */
    open fun voicesListDetailed(): List<AzureVoiceDescriptor> = withRetry {
        voicesListDetailedOnce()
    }

    private fun voicesListDetailedOnce(): List<AzureVoiceDescriptor> {
        val key = credentials.key()
            ?: throw AzureError.AuthFailed("No Azure subscription key configured")
        val regionId = credentials.regionId()

        val request = Request.Builder()
            .url(voicesListUrlFor(regionId))
            .header(HEADER_KEY, key)
            .header(HEADER_USER_AGENT, USER_AGENT)
            .get()
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw AzureError.NetworkError(e)
        }

        return response.use { resp ->
            when {
                resp.isSuccessful -> {
                    val body = resp.body?.string().orEmpty()
                    AzureVoiceListParser.parse(body)
                }
                resp.code == 401 || resp.code == 403 -> {
                    throw AzureError.AuthFailed(
                        "Azure rejected key (HTTP ${resp.code}): " +
                            (resp.message.takeIf { it.isNotBlank() }
                                ?: "authentication failed"),
                    )
                }
                resp.code in 400..499 -> {
                    val excerpt = resp.body?.string()?.take(256) ?: resp.message
                    throw AzureError.BadRequest(
                        resp.code,
                        "Azure rejected request (HTTP ${resp.code}): $excerpt",
                    )
                }
                resp.code in 500..599 -> {
                    throw AzureError.ServerError(
                        resp.code,
                        "Azure server error (HTTP ${resp.code}): " +
                            (resp.message.takeIf { it.isNotBlank() } ?: "unknown"),
                    )
                }
                else -> {
                    throw AzureError.ServerError(
                        resp.code,
                        "Unexpected HTTP ${resp.code} from Azure",
                    )
                }
            }
        }
    }

    private fun voicesListOnce(): Int {
        val key = credentials.key()
            ?: throw AzureError.AuthFailed("No Azure subscription key configured")
        val regionId = credentials.regionId()

        val request = Request.Builder()
            .url(voicesListUrlFor(regionId))
            .header(HEADER_KEY, key)
            .header(HEADER_USER_AGENT, USER_AGENT)
            .get()
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw AzureError.NetworkError(e)
        }

        return response.use { resp ->
            when {
                resp.isSuccessful -> {
                    // Cheap voice-count: count top-level objects in the
                    // array. Azure's payload is `[{...},{...}]` —
                    // counting `{` is correct because JSON object
                    // values inside a voice are flat (no nested
                    // objects) per the published schema. Worst case a
                    // future schema change inflates the count; the
                    // user sees a successful Reachable result with a
                    // confusing number, never a wrong-direction
                    // failure. PR-7 swaps this for a real parse.
                    val body = resp.body?.string().orEmpty()
                    body.count { it == '{' }
                }
                resp.code == 401 || resp.code == 403 -> {
                    throw AzureError.AuthFailed(
                        "Azure rejected key (HTTP ${resp.code}): " +
                            (resp.message.takeIf { it.isNotBlank() }
                                ?: "authentication failed"),
                    )
                }
                resp.code in 400..499 -> {
                    val excerpt = resp.body?.string()?.take(256) ?: resp.message
                    throw AzureError.BadRequest(
                        resp.code,
                        "Azure rejected request (HTTP ${resp.code}): $excerpt",
                    )
                }
                resp.code in 500..599 -> {
                    throw AzureError.ServerError(
                        resp.code,
                        "Azure server error (HTTP ${resp.code}): " +
                            (resp.message.takeIf { it.isNotBlank() } ?: "unknown"),
                    )
                }
                else -> {
                    throw AzureError.ServerError(
                        resp.code,
                        "Unexpected HTTP ${resp.code} from Azure",
                    )
                }
            }
        }
    }

    companion object {
        /** PR-5 (#184) — backoff schedule for [withRetry]. Index 0 =
         *  delay before the 1st retry, index 1 = delay before the 2nd
         *  retry. Total wall time on full exhaustion ≈ 0.75 s + jitter.
         *  Tuned so a transient Azure 429 burst (typical recovery < 1 s)
         *  clears without the user noticing, but a sustained outage
         *  doesn't tie up the producer for >1 s before the error
         *  reaches the UI. The 1 s slot in Solara's original spec was
         *  trimmed because the producer-side retry budget needs to fit
         *  inside the engineMutex window — anything longer stalls the
         *  pipeline visibly. */
        internal val RETRY_DELAYS_MS: LongArray = longArrayOf(250L, 500L)
        /** PR-5 — initial attempt + this many retries = 3 total attempts.
         *  One retry covers a single in-flight burst; the second covers
         *  a brief sustained incident. After that we give up and let
         *  the typed error surface to the UI. */
        internal const val MAX_RETRIES: Int = 2

        internal const val HEADER_KEY = "Ocp-Apim-Subscription-Key"
        internal const val HEADER_OUTPUT_FORMAT = "X-Microsoft-OutputFormat"
        internal const val HEADER_CONTENT_TYPE = "Content-Type"
        internal const val HEADER_USER_AGENT = "User-Agent"

        /** 24 kHz mono 16-bit PCM. Matches Kokoro's native sample rate
         *  so AudioTrack rebuilds on voice swap stay cheap. Per Solara's
         *  open-question #7 recommendation. */
        internal const val OUTPUT_FORMAT_PCM_24KHZ = "raw-24khz-16bit-mono-pcm"

        /** Sample rate of the PCM bytes returned by [synthesize]. The
         *  engine handle reports this to AudioTrack so playback runs
         *  at the right speed. */
        const val SAMPLE_RATE_HZ: Int = 24_000

        internal const val CONTENT_TYPE_SSML = "application/ssml+xml"
        internal val SSML_MEDIA_TYPE = "application/ssml+xml".toMediaType()

        /** Per Azure docs, a meaningful User-Agent helps with their
         *  service-side debugging when a request misbehaves. The
         *  version suffix is filled by Hilt at runtime; tests use
         *  the literal string. */
        internal const val USER_AGENT =
            UserAgent.APP_NAME + "/azure-tts (" + UserAgent.CONTACT_URL + ")"

        /** Read size for the streaming variant. 8 KB lines up with TLS
         *  record sizes (most chunks come in at one record per read)
         *  and amortizes AudioTrack.write() overhead in the consumer.
         *  ~165 ms of audio per chunk at 24 kHz mono. */
        internal const val STREAM_CHUNK_BYTES = 8192
    }
}
