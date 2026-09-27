package `in`.jphe.storyvox.feature.notes.ui

import `in`.jphe.storyvox.data.notes.NoteEntity
import `in`.jphe.storyvox.data.notes.TranscriptionStatus
import `in`.jphe.storyvox.playback.transcribe.offline.TranscriptionModelProvider.DownloadProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1657 — pure decision logic for the transcription model download / retry prompt. */
class TranscriptionModelPromptTest {

    private fun prompt(
        hasAudio: Boolean = true,
        hasTranscript: Boolean = false,
        status: TranscriptionStatus = TranscriptionStatus.PENDING,
        modelReady: Boolean = false,
        download: ModelDownloadUi = ModelDownloadUi.Idle,
    ) = transcriptPromptFor(hasAudio, hasTranscript, status, modelReady, download)

    @Test
    fun pendingWithoutModel_offersDownload() {
        assertEquals(TranscriptPrompt.DownloadModel, prompt())
    }

    @Test
    fun pendingWithModel_offersTranscribeNow() {
        assertEquals(TranscriptPrompt.TranscribeNow, prompt(modelReady = true))
    }

    @Test
    fun downloadInFlight_showsProgress() {
        assertEquals(
            TranscriptPrompt.Downloading(0.5f),
            prompt(download = ModelDownloadUi.InProgress(0.5f)),
        )
    }

    @Test
    fun downloadFailed_offersRetryDownload() {
        assertEquals(
            TranscriptPrompt.DownloadFailed("HTTP 404"),
            prompt(download = ModelDownloadUi.Failed("HTTP 404")),
        )
    }

    @Test
    fun staleFailureIsIgnoredOnceModelIsReady() {
        assertEquals(
            TranscriptPrompt.TranscribeNow,
            prompt(modelReady = true, download = ModelDownloadUi.Failed("old")),
        )
    }

    @Test
    fun failedTranscription_offersRetry_regardlessOfModel() {
        assertEquals(TranscriptPrompt.Retry, prompt(status = TranscriptionStatus.FAILED))
        assertEquals(TranscriptPrompt.Retry, prompt(status = TranscriptionStatus.FAILED, modelReady = true))
    }

    @Test
    fun nothingOffered_forTypedNotes_transcribedNotes_orActiveStates() {
        assertEquals(TranscriptPrompt.None, prompt(hasAudio = false))
        assertEquals(TranscriptPrompt.None, prompt(hasTranscript = true))
        assertEquals(TranscriptPrompt.None, prompt(status = TranscriptionStatus.RUNNING))
        assertEquals(TranscriptPrompt.None, prompt(status = TranscriptionStatus.DONE))
        assertEquals(TranscriptPrompt.None, prompt(status = TranscriptionStatus.NONE))
    }

    @Test
    fun progressFrames_mapToUi() {
        assertEquals(ModelDownloadUi.InProgress(null), DownloadProgress.Resolving.toUi())
        assertEquals(ModelDownloadUi.InProgress(0.25f), DownloadProgress.Downloading(25, 100).toUi())
        assertEquals(ModelDownloadUi.InProgress(1f), DownloadProgress.Downloading(150, 100).toUi())
        assertNull((DownloadProgress.Downloading(5, 0).toUi() as ModelDownloadUi.InProgress).fraction)
        assertEquals(ModelDownloadUi.Idle, DownloadProgress.Done.toUi())
        assertEquals(ModelDownloadUi.Failed("x"), DownloadProgress.Failed("x").toUi())
    }

    @Test
    fun awaitingModel_isPendingRecordingsOnly() {
        fun n(id: String, audio: String?, s: TranscriptionStatus) =
            NoteEntity(id = id, createdAt = 0, updatedAt = 0, audioPath = audio, transcriptionStatus = s)
        val notes = listOf(
            n("a", "/r/a.m4a", TranscriptionStatus.PENDING),
            n("b", null, TranscriptionStatus.PENDING),
            n("c", "/r/c.m4a", TranscriptionStatus.DONE),
            n("d", "/r/d.m4a", TranscriptionStatus.FAILED),
            n("e", "/r/e.m4a", TranscriptionStatus.PENDING),
        )
        assertEquals(listOf("a", "e"), notesAwaitingModel(notes))
    }
}
