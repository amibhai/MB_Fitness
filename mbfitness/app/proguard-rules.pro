# Filled in during Phase 8, once the release build can be exercised end to end.

# kotlinx.coroutines creates this through ServiceLoader, from META-INF/services (FailureGuard.kt).
-keep class com.swastik.mbfitness.CoroutineFailureMarker { public <init>(); }

# The error log's crash details name real lines once retraced with this release's mapping.txt
# (docs/HANDOVER.md). Source file names stay hidden.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
