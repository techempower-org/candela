# Keep Kotlinx serializer companions for the wire-format models. Required
# under R8 because the @Serializable generated `Companion.serializer()` is
# only referenced reflectively from the call site shape we use.
-keep,allowobfuscation,allowshrinking @kotlinx.serialization.Serializable class **
-keep,allowobfuscation,allowshrinking class **$$serializer { *; }
-keepclassmembers class **$Companion {
    public static *** serializer(...);
}
