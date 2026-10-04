package `in`.jphe.storyvox.sync

import `in`.jphe.storyvox.sync.client.HttpInstantBackend
import `in`.jphe.storyvox.sync.client.InstantHttpTransport
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.client.TransportResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * instant.perms.json's `$default` deny also stops clients from creating
 * attributes, so on a fresh Instant app every field Candela writes must be
 * pre-created by an admin (verified on the self-hosted staging app,
 * 2026-10-04). instant.attrs.json lists those fields. This test fails when
 * a write starts carrying a field the list doesn't have, which would mean
 * every sync write fails with 400 permission-denied in production.
 */
class InstantAttrsTest {

    private val user = SignedInUser(userId = "u-1", email = null, refreshToken = "rt-1")

    @Test fun `every field an upsert writes is a pre-created attr`() = runTest {
        val declared = Json.parseToJsonElement(File("instant.attrs.json").readText()).jsonObject
        for ((entity, fields) in declared) {
            var body = ""
            val transport = object : InstantHttpTransport {
                override suspend fun postJson(
                    url: String,
                    jsonBody: String,
                    headers: Map<String, String>,
                ): TransportResult {
                    body = jsonBody
                    return TransportResult(200, """{"tx-id":1}""")
                }
            }
            HttpInstantBackend("test-app", transport).upsert(user, entity, "id-1", "p", 1L)
            val step = Json.parseToJsonElement(body).jsonObject["steps"]!!.jsonArray[0].jsonArray
            val written = step[3].jsonObject.keys
            val expected = fields.jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertEquals("fields written to $entity", expected, written)
        }
    }
}
