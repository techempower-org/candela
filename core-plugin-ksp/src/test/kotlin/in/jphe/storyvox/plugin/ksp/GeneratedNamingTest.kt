package `in`.jphe.storyvox.plugin.ksp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [generatedModuleBaseName] — the collision-free
 * naming introduced for issue 1506. Property-based (no hardcoded hash
 * literals) so they stay valid if the underlying hash ever changes, while
 * still pinning the invariant the fix exists to guarantee.
 */
class GeneratedNamingTest {

    @Test
    fun `is deterministic for the same qualified name`() {
        val fqn = "in.jphe.storyvox.source.kvmr.KvmrSource"
        assertEquals(generatedModuleBaseName(fqn), generatedModuleBaseName(fqn))
    }

    @Test
    fun `same simple name in different packages does not collide`() {
        // The actual bug (issue 1506): flat generated package keyed only on
        // the simple name would emit identical file + class FQN for both.
        val a = generatedModuleBaseName("in.jphe.storyvox.a.FooSource")
        val b = generatedModuleBaseName("in.jphe.storyvox.b.FooSource")
        assertNotEquals("same-named classes in different packages must differ", a, b)
    }

    @Test
    fun `different simple names in the same package stay distinct`() {
        val a = generatedModuleBaseName("in.jphe.storyvox.pkg.FooSource")
        val b = generatedModuleBaseName("in.jphe.storyvox.pkg.BarSource")
        assertNotEquals(a, b)
    }

    @Test
    fun `base name is prefixed with the simple class name`() {
        val name = generatedModuleBaseName("in.jphe.storyvox.source.kvmr.KvmrSource")
        assertTrue(name, name.startsWith("KvmrSource_"))
    }

    @Test
    fun `base name is a legal kotlin identifier`() {
        val name = generatedModuleBaseName("in.jphe.storyvox.a.FooSource")
        assertTrue(name, name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
    }

    @Test
    fun `handles a class in the default package`() {
        val name = generatedModuleBaseName("FooSource")
        assertTrue(name, name.startsWith("FooSource_"))
        assertTrue(name, name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
    }
}
