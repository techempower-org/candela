package `in`.jphe.storyvox.playback.voice

import android.app.Application
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import `in`.jphe.storyvox.data.source.AzureVoiceDescriptor
import `in`.jphe.storyvox.data.source.AzureVoiceProvider
import `in`.jphe.storyvox.data.source.SystemTtsVoiceDescriptor
import `in`.jphe.storyvox.data.source.SystemTtsVoiceProvider
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pins the partial-cleanup policy unified by issue #28. Two paths to keep
 * straight:
 *
 * 1. **Real failure** (network error, 5xx, disk full): Both Kokoro and Piper
 *    wipe their voice/shared dir before emitting `Failed`. Trade-off: re-fetch
 *    cost (≤325 MB on Kokoro) vs. risk of a corrupted partial onnx surviving
 *    a SIGKILL and tricking the next `if (!exists())` retry skip into
 *    treating a half-written file as installed. JP picked deterministic
 *    retries over the bandwidth saving.
 * 2. **User cancel** (collector dropped, scope cancelled): Both branches
 *    re-throw `CancellationException` and leave partials in place. Aligns
 *    with PR #20/#26 where the user-facing cancel was made side-effect-free
 *    so a deliberate cancel doesn't surface a phantom error toast and
 *    doesn't punish the user with a re-download of a sibling that already
 *    completed before they tapped Cancel.
 *
 * If either path regresses we want a loud test failure here, not a silent
 * UX surprise on a flaky-network device.
 *
 * Robolectric runner: VoiceManager needs `Context.filesDir` (for the
 * `voices/` directory layout) and a real `DataStore<Preferences>` for the
 * installed-id set — both backed by libcore under Robolectric.
 *
 * `application = Application::class` skips any Hilt bootstrap that the
 * production app would do; we instantiate VoiceManager directly with the
 * vanilla Application context the runner provides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VoiceManagerTest {

    private lateinit var context: Application
    private lateinit var voicesRoot: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        voicesRoot = File(context.filesDir, "voices")
        // Belt + suspenders: Robolectric's filesDir is per-test but a
        // previously-failed run can leave residue in the same JVM — wipe so
        // each test sees a deterministic empty starting state.
        voicesRoot.deleteRecursively()
    }

    @After
    fun tearDown() {
        voicesRoot.deleteRecursively()
    }

    /**
     * Real-failure path on Kokoro: HTTP 500 from the first asset must wipe
     * the shared dir before Failed emits. Pre-seeding `voices.bin` mimics a
     * prior interrupted run that left a sibling on disk; if cleanup is
     * skipped that sibling survives the `if (!exists())` check on the next
     * retry and we'd ship a half-state to the engine.
     */
    @Test
    fun kokoro_realFailure_wipesSharedDir() = runBlocking {
        val vm = VoiceManager(context, EmptyAzureProvider, EmptySystemTtsProvider)
        vm.http = httpClientReturning500()

        // Seed a "prior partial run" file inside the shared dir.
        val sharedDir = vm.kokoroSharedDir().also { it.mkdirs() }
        val seeded = File(sharedDir, "voices.bin").apply { writeText("partial-from-prior-run") }
        assertTrue("precondition: seeded sibling exists", seeded.exists())

        val progress = vm.download("kokoro_aoede_en_US_1").toList()

        // Failed must have terminated the flow.
        val failed = progress.last()
        assertTrue("expected Failed terminal, got $failed", failed is VoiceManager.DownloadProgress.Failed)

        // #28 policy: shared dir wiped on real failure. The seeded sibling
        // is collateral damage by design — deterministic retries beat
        // bandwidth.
        assertFalse(
            "shared dir must be wiped on real failure (#28 Option A)",
            sharedDir.exists(),
        )
    }

    /**
     * Cancel path on Kokoro: a deliberate user cancel must NOT delete the
     * shared dir. PRs #20/#26 made this side-effect-free so the user
     * doesn't lose an already-completed sibling to a tap on Cancel.
     *
     * The cancel is timed to land while the download loop is genuinely
     * inside the try-block (not before it) — otherwise the test would
     * pass even if the catch (CancellationException) branch was deleted.
     * We do that by waiting for the first Downloading emission (which
     * means downloadFile entered, the response body has streamed at least
     * one buffer, and onProgress suspended back into the flow) then
     * cancelling at that exact suspension point.
     */
    @Test
    fun kokoro_cancel_preservesSharedDir() = runBlocking {
        val vm = VoiceManager(context, EmptyAzureProvider, EmptySystemTtsProvider)
        // 256 KiB body → at least four 64 KiB read iterations → multiple
        // onProgress suspend points to land the cancel on. The bytes are
        // garbage; nothing in the test reads what gets written.
        vm.http = httpClientReturningBody(ByteArray(256 * 1024))

        val sharedDir = vm.kokoroSharedDir().also { it.mkdirs() }
        val seeded = File(sharedDir, "voices.bin").apply { writeText("partial-from-prior-run") }
        assertTrue("precondition: seeded sibling exists", seeded.exists())

        val firstDownloading = CountDownLatch(1)
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val job = scope.launch {
            try {
                vm.download("kokoro_aoede_en_US_1").collect { p ->
                    if (p is VoiceManager.DownloadProgress.Downloading) {
                        firstDownloading.countDown()
                        // Park the collector so the producer is suspended
                        // on emit() when we call cancelAndJoin from the
                        // outer scope. This is the precise state that
                        // exercises the catch (CancellationException)
                        // branch inside the try-block.
                        kotlinx.coroutines.delay(60_000)
                    }
                }
            } catch (_: CancellationException) {
                // expected — re-thrown from the catch (CancellationException) branch
            }
        }
        assertTrue(
            "flow never reached Downloading — cancel test would not exercise the in-try cancel path",
            firstDownloading.await(5, TimeUnit.SECONDS),
        )
        job.cancelAndJoin()

        // #20/#26 policy: cancel path keeps partials.
        assertTrue(
            "cancel path must NOT wipe shared dir (regression guard for #20/#26)",
            sharedDir.exists(),
        )
        assertTrue(
            "cancel path must preserve seeded sibling (regression guard for #20/#26)",
            seeded.exists(),
        )
    }

    /**
     * Real-failure path on Piper: pre-existing per-voice dir is wiped before
     * Failed emits. This path was already correct before #28 — the test
     * locks it in so a future "unification" refactor doesn't accidentally
     * regress it while touching the Kokoro branch.
     */
    @Test
    fun piper_realFailure_wipesVoiceDir() = runBlocking {
        val vm = VoiceManager(context, EmptyAzureProvider, EmptySystemTtsProvider)
        vm.http = httpClientReturning500()

        val voiceId = "piper_lessac_en_US_high"
        val voiceDir = vm.voiceDirFor(voiceId).also { it.mkdirs() }
        val seeded = File(voiceDir, "model.onnx").apply { writeText("partial") }
        assertTrue("precondition: seeded onnx exists", seeded.exists())

        val progress = vm.download(voiceId).toList()
        val failed = progress.last()
        assertTrue("expected Failed terminal, got $failed", failed is VoiceManager.DownloadProgress.Failed)
        assertFalse(
            "Piper voice dir must be wiped on real failure",
            voiceDir.exists(),
        )
    }

    /**
     * Sanity check that the `Resolving` → `Failed` shape holds when the very
     * first asset fetch hits a 5xx. If the catch ever stops emitting Failed
     * (e.g. someone replaces it with a plain rethrow), the UI silently never
     * leaves the spinner — this catches that.
     */
    @Test
    fun kokoro_realFailure_emitsResolvingThenFailed() = runBlocking {
        val vm = VoiceManager(context, EmptyAzureProvider, EmptySystemTtsProvider)
        vm.http = httpClientReturning500()

        val progress = vm.download("kokoro_aoede_en_US_1").toList()
        assertTrue("first emission must be Resolving, got ${progress.firstOrNull()}",
            progress.first() is VoiceManager.DownloadProgress.Resolving)
        assertTrue("last emission must be Failed, got ${progress.lastOrNull()}",
            progress.last() is VoiceManager.DownloadProgress.Failed)
    }

    /**
     * Issue #119 — Kitten path mirrors Kokoro's shape (one shared model
     * underpins all 8 speakers), and so must mirror Kokoro's #28 partial-
     * cleanup policy: HTTP 500 on the first asset wipes the shared dir
     * before Failed emits. Pre-seeding `voices.bin` simulates a partially-
     * completed prior run; if the wipe is skipped that sibling silently
     * survives the `if (!exists())` check on retry and we'd ship a
     * half-state to the engine — same failure mode as Kokoro.
     */
    @Test
    fun kitten_realFailure_wipesSharedDir() = runBlocking {
        val vm = VoiceManager(context, EmptyAzureProvider, EmptySystemTtsProvider)
        vm.http = httpClientReturning500()

        val sharedDir = vm.kittenSharedDir().also { it.mkdirs() }
        val seeded = File(sharedDir, "voices.bin").apply { writeText("partial-from-prior-run") }
        assertTrue("precondition: seeded sibling exists", seeded.exists())

        val progress = vm.download("kitten_f1_en_US_0").toList()

        val failed = progress.last()
        assertTrue("expected Failed terminal, got $failed", failed is VoiceManager.DownloadProgress.Failed)

        assertFalse(
            "Kitten shared dir must be wiped on real failure (#28 policy carries over from Kokoro)",
            sharedDir.exists(),
        )
    }

    /**
     * Issue #119 — Kitten download Resolving→…→Done shape with a happy-
     * path 200. Mirrors Kokoro's shape test. With a 200 OkHttpClient
     * returning a small body, all three asset fetches (model, voices,
     * tokens) succeed, the markInstalled call lands, and the terminal
     * Done emits. Catches regressions like a missing branch in the
     * `when (engineType)` block silently falling through to a no-op.
     */
    @Test
    fun kitten_happyPath_emitsResolvingDownloadingDone() = runBlocking {
        val vm = VoiceManager(context, EmptyAzureProvider, EmptySystemTtsProvider)
        // 16 KiB body — small enough to keep the test fast, big enough
        // that the OkHttp body iterator emits at least one Downloading
        // tick before EOF.
        vm.http = httpClientReturningBody(ByteArray(16 * 1024))

        val progress = vm.download("kitten_f1_en_US_0").toList()

        assertTrue(
            "first emission must be Resolving, got ${progress.firstOrNull()}",
            progress.first() is VoiceManager.DownloadProgress.Resolving,
        )
        assertTrue(
            "must contain at least one Downloading frame",
            progress.any { it is VoiceManager.DownloadProgress.Downloading },
        )
        assertTrue(
            "last emission must be Done, got ${progress.lastOrNull()}",
            progress.last() is VoiceManager.DownloadProgress.Done,
        )

        // Shared dir + the three asset files exist after Done — the
        // engine load path will find them in place when EnginePlayer
        // resolves `kittenSharedDir() / "model.onnx"` etc.
        val sharedDir = vm.kittenSharedDir()
        assertTrue("shared dir should exist", sharedDir.exists())
        assertTrue("model.onnx should land", File(sharedDir, "model.onnx").exists())
        assertTrue("voices.bin should land", File(sharedDir, "voices.bin").exists())
        assertTrue("tokens.txt should land", File(sharedDir, "tokens.txt").exists())
    }

    // ----- helpers -----

    /**
     * OkHttpClient whose interceptor returns a synthetic HTTP 500 for every
     * request, surfacing into [VoiceManager.downloadFile] as the
     * `IOException("HTTP 500 …")` that the catch-Throwable branch handles.
     * No socket, no DNS, no actual network — Robolectric's lack of
     * networking primitives is a non-issue.
     */

    /** Empty roster — no live Azure voices. The download-policy tests in
     *  this class never exercise Azure paths (the static catalog covers
     *  Piper + Kokoro), so a no-op provider is sufficient. */
    private object EmptyAzureProvider : AzureVoiceProvider {
        override val voices: Flow<List<AzureVoiceDescriptor>> = flowOf(emptyList())
        override suspend fun refresh() = Unit
    }

    /** Empty roster — no live System TTS voices. Mirror of
     *  [EmptyAzureProvider] for the #676 plumbing; the download-policy
     *  tests don't exercise System TTS paths. */
    private object EmptySystemTtsProvider : SystemTtsVoiceProvider {
        override val voices: Flow<List<SystemTtsVoiceDescriptor>> = flowOf(emptyList())
        override suspend fun refresh() = Unit
    }

    private fun httpClientReturning500(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Server Error (test)")
                    .body("".toResponseBody(null))
                    .build()
            })
            .build()

    /**
     * OkHttpClient whose interceptor returns a 200 with [bytes] as the
     * response body. For `.gz` URLs, serves a valid gzip-compressed
     * version of the payload (so the gzip-first download path in
     * `downloadFile` succeeds). For non-`.gz` URLs, serves raw bytes.
     *
     * The body is sized to produce at least four 64 KiB read iterations
     * (when called with 256 KiB) → multiple onProgress suspend points
     * to land the cancel on.
     */
    private fun httpClientReturningBody(bytes: ByteArray): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                if (url.endsWith(".gz")) {
                    val gzipped = gzipBytes(bytes)
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK (gzip test)")
                        .body(gzipped.toResponseBody("application/gzip".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK (test)")
                        .body(bytes.toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                }
            })
            .build()

    // ---- Issue #1112: gzip download + fallback tests ----

    /**
     * Issue #1112 — when the server has a `.gz` variant, downloadFile
     * must decompress it transparently and land the uncompressed payload
     * on disk. Verifies the gzip-first path end-to-end: the interceptor
     * serves valid gzip for `.gz` URLs and the written file matches the
     * original uncompressed bytes.
     */
    @Test
    fun downloadFile_gzipAvailable_decompressesCorrectly() = runBlocking {
        val original = ByteArray(32 * 1024) { (it % 256).toByte() }
        val vm = VoiceManager(context, EmptyAzureProvider, EmptySystemTtsProvider)
        vm.http = httpClientWithGzSupport(original)

        val target = File(voicesRoot, "test_gz.onnx").also { voicesRoot.mkdirs() }
        vm.downloadFile(
            url = "https://example.com/test.onnx",
            target = target,
            knownTotalBytes = original.size.toLong(),
        ) { _, _ -> }

        assertTrue("target must exist after gzip download", target.exists())
        assertArrayEquals(
            "decompressed content must match original",
            original,
            target.readBytes(),
        )
    }

    /**
     * Issue #1112 — when the server returns 404 for the `.gz` URL,
     * downloadFile must silently fall back to the raw (uncompressed) URL
     * and still land the file. No user-visible error, no crash.
     */
    @Test
    fun downloadFile_gzipNotAvailable_fallsBackToRaw() = runBlocking {
        val rawPayload = ByteArray(16 * 1024) { (it % 128).toByte() }
        val vm = VoiceManager(context, EmptyAzureProvider, EmptySystemTtsProvider)
        vm.http = httpClientGz404FallbackRaw(rawPayload)

        val target = File(voicesRoot, "test_fallback.onnx").also { voicesRoot.mkdirs() }
        vm.downloadFile(
            url = "https://example.com/test.onnx",
            target = target,
            knownTotalBytes = rawPayload.size.toLong(),
        ) { _, _ -> }

        assertTrue("target must exist after fallback download", target.exists())
        assertArrayEquals(
            "raw fallback content must match original",
            rawPayload,
            target.readBytes(),
        )
    }

    /**
     * Issue #1112 — Piper happy-path with gzip-aware download. Confirms
     * that the full Piper download flow (model.onnx via gzip + tokens.txt
     * raw) lands both files and emits Resolving → Downloading → Done.
     */
    @Test
    fun piper_happyPath_withGzipDownload() = runBlocking {
        val modelPayload = ByteArray(16 * 1024) { (it % 200).toByte() }
        val vm = VoiceManager(context, EmptyAzureProvider, EmptySystemTtsProvider)
        vm.http = httpClientWithGzSupport(modelPayload)

        val voiceId = "piper_lessac_en_US_high"
        val progress = vm.download(voiceId).toList()

        assertTrue(
            "first emission must be Resolving",
            progress.first() is VoiceManager.DownloadProgress.Resolving,
        )
        assertTrue(
            "last emission must be Done",
            progress.last() is VoiceManager.DownloadProgress.Done,
        )

        val voiceDir = vm.voiceDirFor(voiceId)
        assertTrue("model.onnx should exist", File(voiceDir, "model.onnx").exists())
        assertTrue("tokens.txt should exist", File(voiceDir, "tokens.txt").exists())
    }

    // ---- gzip-aware interceptor helpers ----

    /**
     * Interceptor that serves valid gzip for `.gz` URLs and raw bytes for
     * non-`.gz` URLs. Used to test the gzip-first download path. The
     * [uncompressed] payload is gzipped on the fly for `.gz` requests.
     */
    private fun httpClientWithGzSupport(uncompressed: ByteArray): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                if (url.endsWith(".gz")) {
                    val gzipped = gzipBytes(uncompressed)
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK (gzip test)")
                        .body(gzipped.toResponseBody("application/gzip".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK (raw test)")
                        .body(uncompressed.toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                }
            })
            .build()

    /**
     * Interceptor that returns 404 for `.gz` URLs and 200 with [rawPayload]
     * for non-`.gz` URLs. Tests the fallback path where gzip assets haven't
     * been uploaded to the release yet.
     */
    private fun httpClientGz404FallbackRaw(rawPayload: ByteArray): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                if (url.endsWith(".gz")) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found (test)")
                        .body("".toResponseBody(null))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK (raw fallback test)")
                        .body(rawPayload.toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                }
            })
            .build()

    /** Gzip a byte array in memory — test helper only. */
    private fun gzipBytes(input: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz -> gz.write(input) }
        return baos.toByteArray()
    }
}
