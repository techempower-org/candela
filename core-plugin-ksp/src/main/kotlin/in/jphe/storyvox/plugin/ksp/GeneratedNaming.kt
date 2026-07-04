package `in`.jphe.storyvox.plugin.ksp

/**
 * Collision-free base name for a generated Hilt module (#1506).
 *
 * Both [SourcePluginProcessor] and [VoicePluginProcessor] emit their
 * `@Module` classes into the single flat package
 * `in.jphe.storyvox.plugin.generated`, and Hilt aggregates `@Module`
 * classes by their fully-qualified name when it builds the
 * `@HiltAndroidApp` graph. Keying the generated name on the plugin's
 * *simple* name alone (`FooSource` → `FooSource_SourcePluginModule`) means
 * two same-named plugin classes in different packages — `a.FooSource` and
 * `b.FooSource` — would generate the same file **and** the same class FQN
 * and collide at compile time. Before this fix that hazard was latent:
 * every shipped source/voice plugin happens to have a unique simple name.
 *
 * Disambiguate by suffixing the simple name with a short, deterministic
 * hash of the declaring package: `FooSource_<pkgHash>`. Two classes in the
 * same package cannot share a simple name, so `(simpleName, packageHash)`
 * is unique per class. The hash is derived from [String.hashCode], whose
 * formula is fixed by the Java Language Specification, so the generated
 * names are stable across compiler runs and machines — an incremental KSP
 * re-run produces byte-identical output.
 *
 * Callers append the module-role suffix, e.g.
 * `"${generatedModuleBaseName(fqn)}_SourcePluginModule"`.
 *
 * @param qualifiedName the plugin class's raw (un-escaped) fully-qualified
 *   name, e.g. `in.jphe.storyvox.source.kvmr.KvmrSource`.
 */
internal fun generatedModuleBaseName(qualifiedName: String): String {
    val simpleName = qualifiedName.substringAfterLast('.')
    val packageName = qualifiedName.substringBeforeLast('.', missingDelimiterValue = "")
    // Unsigned hex → 1..8 chars, [0-9a-f] only. Never negative, so no '-'
    // that would break the identifier; safe to embed mid-name.
    val packageHash = Integer.toHexString(packageName.hashCode())
    return "${simpleName}_$packageHash"
}
