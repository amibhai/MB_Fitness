// AGP 9 has Kotlin built in, so org.jetbrains.kotlin.android is not applied.
// This classpath entry moves the built-in Kotlin up to the version pinned in the catalog.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
