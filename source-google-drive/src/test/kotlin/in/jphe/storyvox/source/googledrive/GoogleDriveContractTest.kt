package `in`.jphe.storyvox.source.googledrive

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.googledrive.config.GoogleDriveConfig
import `in`.jphe.storyvox.source.googledrive.config.GoogleDriveConfigState
import `in`.jphe.storyvox.source.googledrive.net.GoogleDriveApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient

/**
 * Issue #1496 — Google Drive against the shared contract kit: IO-pin (#585),
 * 401→AuthRequired, 429→RateLimited, CF-challenge→NetworkError, blank-search
 * sanity. The source is built with a fake config carrying a non-blank token
 * so `popular()` actually reaches the network (a blank token would
 * short-circuit to AuthRequired before any HTTP, defeating the IO-pin probe).
 */
class GoogleDriveContractTest : FictionSourceContractTest() {

    /** Fake session — always "connected" with a dummy token. */
    private class FakeConfig(private val token: String) : GoogleDriveConfig {
        override val state: Flow<GoogleDriveConfigState> =
            flowOf(GoogleDriveConfigState(accessToken = token))
        override suspend fun current(): GoogleDriveConfigState =
            GoogleDriveConfigState(accessToken = token)
    }

    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return GoogleDriveSource(
            object : GoogleDriveApi(client) {
                override val baseUrl: String get() = host
            },
            FakeConfig(token = "test-access-token"),
        )
    }

    /** A trimmed real `files.list` response: one Google Doc + one text file. */
    override fun happyListBody(): String =
        """
        {
          "files": [
            {
              "id": "1AbCdEfGhIjK",
              "name": "The Clockwork Archivist",
              "mimeType": "application/vnd.google-apps.document",
              "modifiedTime": "2026-07-01T12:00:00.000Z"
            },
            {
              "id": "2LmNoPqRsTuV",
              "name": "field-notes.txt",
              "mimeType": "text/plain",
              "modifiedTime": "2026-06-30T09:15:00.000Z"
            }
          ]
        }
        """.trimIndent()

    /** `popular()` / `search()` both hit `GET /drive/v3/files?...`. */
    override fun listPathFragment(): String = "files"
}
