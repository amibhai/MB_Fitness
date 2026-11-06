# Filled in during Phase 8, once the release build can be exercised end to end.

# kotlinx.coroutines creates this through ServiceLoader, from META-INF/services (StorageFullGuard.kt).
-keep class com.swastik.mbfitness.CoroutineFailureMarker { public <init>(); }
