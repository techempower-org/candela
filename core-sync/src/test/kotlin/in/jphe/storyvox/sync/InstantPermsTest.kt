package `in`.jphe.storyvox.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * core-sync/instant.perms.json is the permission set a self-hosted (or
 * Cloud) Instant app must carry. Without it, any signed-in client could read
 * or overwrite another user's rows, because row ids are derivable from
 * "domain:userId" (docs/sync.md). Every synced entity must be owner-only for
 * every operation.
 */
class InstantPermsTest {

    private val entities = listOf("blobs", "positions", "sets")
    private val ops = listOf("view", "create", "update", "delete")

    @Test fun `every synced entity is owner-only for every operation`() {
        val file = File("instant.perms.json")
        assertTrue("core-sync/instant.perms.json exists", file.isFile)
        val perms = Json.parseToJsonElement(file.readText()).jsonObject
        for (entity in entities) {
            val allow = (perms[entity] as? JsonObject)?.get("allow")?.jsonObject
            assertTrue("$entity has an allow block", allow != null)
            for (op in ops) {
                val rule = allow!![op]?.jsonPrimitive?.content.orEmpty()
                assertTrue("$entity.$op checks the owner: '$rule'", "auth.id" in rule && "userId" in rule)
                assertTrue("$entity.$op is never open: '$rule'", rule.trim() != "true")
            }
        }
    }
}
