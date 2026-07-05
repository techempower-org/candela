package `in`.jphe.storyvox.feature.docs

import `in`.jphe.storyvox.data.docs.DocPdfExporter
import `in`.jphe.storyvox.data.docs.DocPdfRequest
import `in`.jphe.storyvox.data.docs.DocPdfResult
import `in`.jphe.storyvox.data.wallet.WalletDoc
import `in`.jphe.storyvox.data.wallet.WalletDocType
import `in`.jphe.storyvox.data.wallet.WalletProgramCatalog
import `in`.jphe.storyvox.data.wallet.WalletStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #1514 — wallet logic over fakes (no EncryptedFile, no biometric,
 * no Android). Covers the lock/unlock gate, add/delete/list, staleness,
 * the verified program lookup, and re-export.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeStore : WalletStore {
        val docs = mutableListOf<WalletDoc>()
        var materialized: List<String> = listOf("file:///cache/p0.jpg", "file:///cache/p1.jpg")
        var cleared = 0
        var counter = 0
        var throwOnSave = false
        var throwOnMaterialize = false
        override suspend fun list(): List<WalletDoc> = docs.sortedByDescending { it.capturedAtEpochMs }
        override suspend fun save(type: WalletDocType, title: String, note: String, pageUris: List<String>): WalletDoc {
            if (throwOnSave) throw IOException("encrypt/write failed")
            val doc = WalletDoc("id${counter++}", type, title.ifBlank { "Doc" }, 1000L + counter, pageUris.size, note)
            docs += doc
            return doc
        }
        override suspend fun delete(docId: String) { docs.removeAll { it.id == docId } }
        override suspend fun materializePagesToCache(docId: String): List<String> {
            if (throwOnMaterialize) throw IOException("cache write failed")
            return if (docs.any { it.id == docId }) materialized else emptyList()
        }
        override suspend fun clearMaterialized() { cleared++ }
    }

    private class FakeExporter(var next: DocPdfResult) : DocPdfExporter {
        var lastRequest: DocPdfRequest? = null
        var throwOnExport = false
        override suspend fun exportToPdf(request: DocPdfRequest): DocPdfResult {
            lastRequest = request
            if (throwOnExport) throw IOException("pdf compose failed")
            return next
        }
    }

    private fun okExport() = DocPdfResult.Success("/cache/exports/Doc.pdf", "Doc.pdf", 2, 190_000L)

    // ── pure staleness (AC: 46-day income proof) ───────────────────────

    @Test
    fun `income proof older than 45 days is stale, 44 days is not`() {
        val now = 1_000_000_000_000L
        val day = WalletDoc.MILLIS_PER_DAY
        val stale = WalletDoc("a", WalletDocType.PROOF_OF_INCOME, "stub", now - 46 * day, 1)
        val fresh = WalletDoc("b", WalletDocType.PROOF_OF_INCOME, "stub", now - 44 * day, 1)
        assertTrue(stale.isStale(now))
        assertFalse(fresh.isStale(now))
        assertEquals(46L, stale.ageDays(now))
    }

    @Test
    fun `types without a staleness window are never stale`() {
        val now = 1_000_000_000_000L
        val old = WalletDoc("a", WalletDocType.AWARD_LETTER, "NOA", now - 900 * WalletDoc.MILLIS_PER_DAY, 2)
        assertFalse(old.isStale(now))
    }

    // ── verified program catalog (AC: "what does this prove?") ─────────

    @Test
    fun `benefit card maps to accepting programs, unknown type maps to none`() {
        val vm = WalletViewModel(FakeStore(), FakeExporter(okExport()))
        val programs = vm.programsFor(WalletDocType.BENEFIT_CARD)
        assertTrue(programs.isNotEmpty())
        // uses the canonical screener ids (mirror)
        assertTrue(programs.any { it.id == WalletProgramCatalog.ProgramIds.LIHEAP })
        // a type with no verified mapping → empty (honest "no list yet")
        assertTrue(vm.programsFor(WalletDocType.ID).isEmpty())
    }

    // ── lock gate + list ───────────────────────────────────────────────

    @Test
    fun `wallet stays empty until unlocked, then loads`() = runTest(dispatcher) {
        val store = FakeStore().apply {
            docs += WalletDoc("x", WalletDocType.ID, "License", 5000L, 1)
        }
        val vm = WalletViewModel(store, FakeExporter(okExport()))
        assertFalse(vm.state.value.unlocked)
        assertTrue(vm.state.value.docs.isEmpty())

        vm.onUnlocked()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.unlocked)
        assertEquals(1, vm.state.value.docs.size)
    }

    @Test
    fun `add then delete a document`() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = WalletViewModel(store, FakeExporter(okExport()))
        vm.onUnlocked()
        dispatcher.scheduler.advanceUntilIdle()

        vm.addDocument(WalletDocType.AWARD_LETTER, "SNAP letter", "note", listOf("content://a", "content://b"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, vm.state.value.docs.size)
        val id = vm.state.value.docs.first().id

        vm.delete(id)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.docs.isEmpty())
    }

    @Test
    fun `addDocument with no pages is a no-op`() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = WalletViewModel(store, FakeExporter(okExport()))
        vm.onUnlocked()
        dispatcher.scheduler.advanceUntilIdle()
        vm.addDocument(WalletDocType.OTHER, "x", "", emptyList())
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.docs.isEmpty())
    }

    // ── re-export (AC: re-export a stored letter) ──────────────────────

    @Test
    fun `reExport composes a PDF, shares once, and wipes decrypted temp files`() = runTest(dispatcher) {
        val store = FakeStore().apply {
            docs += WalletDoc("doc1", WalletDocType.AWARD_LETTER, "Award letter", 5000L, 2)
        }
        val exporter = FakeExporter(okExport())
        val vm = WalletViewModel(store, exporter)
        vm.onUnlocked()
        dispatcher.scheduler.advanceUntilIdle()

        vm.reExport("doc1", "Award letter")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, exporter.lastRequest?.pages?.size)
        assertEquals(1, store.cleared) // decrypted temp files wiped
        assertEquals("Doc.pdf", vm.state.value.shareRequest?.fileName)
        vm.onShareHandled()
        assertNull(vm.state.value.shareRequest)
    }

    @Test
    fun `reExport of a missing doc surfaces an error and wipes nothing shared`() = runTest(dispatcher) {
        val vm = WalletViewModel(FakeStore(), FakeExporter(okExport()))
        vm.onUnlocked()
        dispatcher.scheduler.advanceUntilIdle()
        vm.reExport("nope", "x")
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.state.value.shareRequest)
        assertTrue(vm.state.value.error != null)
    }

    // ── error handling (#1593): a throwing store/exporter must not crash ─
    //  the VM or wedge a spinner, and re-export must still wipe temp files.

    @Test
    fun `addDocument surfaces an error and clears the saving flag when the store throws`() = runTest(dispatcher) {
        val store = FakeStore().apply { throwOnSave = true }
        val vm = WalletViewModel(store, FakeExporter(okExport()))
        vm.onUnlocked()
        dispatcher.scheduler.advanceUntilIdle()

        vm.addDocument(WalletDocType.ID, "License", "", listOf("content://a"))
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.state.value.isSaving)
        assertTrue(vm.state.value.error != null)
        assertTrue(vm.state.value.docs.isEmpty())
    }

    @Test
    fun `reExport recovers, clears exporting, and still wipes temp files when materialize throws`() =
        runTest(dispatcher) {
            val store = FakeStore().apply {
                docs += WalletDoc("doc1", WalletDocType.AWARD_LETTER, "Award letter", 5000L, 2)
                throwOnMaterialize = true
            }
            val vm = WalletViewModel(store, FakeExporter(okExport()))
            vm.onUnlocked()
            dispatcher.scheduler.advanceUntilIdle()

            vm.reExport("doc1", "Award letter")
            dispatcher.scheduler.advanceUntilIdle()

            assertFalse(vm.state.value.isExporting)
            assertTrue(vm.state.value.error != null)
            assertNull(vm.state.value.shareRequest)
            assertEquals(1, store.cleared) // decrypted temp files wiped even on failure
        }

    @Test
    fun `reExport recovers and still wipes temp files when the exporter throws`() = runTest(dispatcher) {
        val store = FakeStore().apply {
            docs += WalletDoc("doc1", WalletDocType.AWARD_LETTER, "Award letter", 5000L, 2)
        }
        val exporter = FakeExporter(okExport()).apply { throwOnExport = true }
        val vm = WalletViewModel(store, exporter)
        vm.onUnlocked()
        dispatcher.scheduler.advanceUntilIdle()

        vm.reExport("doc1", "Award letter")
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.state.value.isExporting)
        assertTrue(vm.state.value.error != null)
        assertNull(vm.state.value.shareRequest)
        assertEquals(1, store.cleared)
    }
}
