# Generated from the current R8 missing class report for release builds.
# These references come from optional annotation/service metadata shipped by
# transitive dependencies and are not required by the runtime app.

# Gson TypeToken resolves anonymous subclass type arguments from generic
# signatures at runtime. R8 strips those signatures unless told otherwise,
# which makes release builds crash during SharedPrefsSyncStore initialization.
-keepattributes Signature,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Transitional release optimization: keep shrinking enabled for size, but avoid
# obfuscation/optimization until release-only reflection paths are covered.
-dontobfuscate
-dontoptimize

# Gson creates these models reflectively. R8 shrinking must not remove fields or
# constructors that are only referenced through JSON serialization.
-keep class com.seenot.app.account.** { *; }
-keep class com.seenot.app.data.model.** { *; }
-keep class com.seenot.app.observability.RuntimeEvent { *; }
-keep class com.seenot.app.observability.RuntimeEventType { *; }

-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
-dontwarn lombok.**
