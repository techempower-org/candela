package `in`.jphe.storyvox.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * #1834: a new syncer entity must be in BOTH instant.attrs.json (pre-created
 * attrs) and instant.perms.json (owner-only rules). Otherwise the $default
 * deny fails it at runtime with a 400 that no other test catches. The
 * entity set comes from the `ENTITY = "…"` constants in core-sync's main
 * sources.
 */
class InstantEntityCoverageTest {

    private val entityConst = Regex("""\bENTITY\s*=\s*"([A-Za-z_]+)"""")

    @Test fun `attrs, perms and the syncers name the same entities`() {
        val fromCode = File("src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> entityConst.findAll(f.readText()).map { it.groupValues[1] } }
            .toSet()
        val attrs = Json.parseToJsonElement(File("instant.attrs.json").readText()).jsonObject.keys
        val perms = Json.parseToJsonElement(File("instant.perms.json").readText()).jsonObject.keys - "\$default"
        assertEquals("entities in syncers vs instant.attrs.json", fromCode, attrs)
        assertEquals("entities in syncers vs instant.perms.json", fromCode, perms)
    }
}
