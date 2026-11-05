import com.android.build.api.artifact.SingleArtifact
import com.swastik.mbfitness.build.VerifyCoverageTask
import com.swastik.mbfitness.build.VerifyDependenciesTask
import com.swastik.mbfitness.build.collectModules
import com.swastik.mbfitness.build.VerifyMergedManifestTask
import com.swastik.mbfitness.build.VerifySourceRulesTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.swastik.mbfitness"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.swastik.mbfitness"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testCoverage {
        jacocoVersion = libs.versions.jacoco.get()
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Robolectric reaches into JDK internals; JDK 17+ blocks that unless opened.
                test.jvmArgs(
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                )
            }
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        lintConfig = file("lint.xml")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // SPEC 14 asks for this in domain/. The compiler can only do it per module, and the
        // app is one module, so it applies everywhere (docs/DECISIONS.md).
        allWarningsAsErrors.set(true)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.compose.ui.test.junit4)
}

// ---- Build-failing rule checks (hard rules 1, 2, 3, 4, 7, 8, 11; SPEC 5.2 and 11.2) ----

val permissionAllowList = setOf(
    // Declared by us (SPEC 11.2).
    "android.permission.SEND_SMS",
    "android.permission.READ_PHONE_STATE",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.SCHEDULE_EXACT_ALARM",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "android.permission.USE_BIOMETRIC",
    // Merged from AndroidX: normal permissions, no data leaves the phone (SPEC 11.2).
    "android.permission.WAKE_LOCK",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.USE_FINGERPRINT",
    "com.swastik.mbfitness.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
)

val permissionDenyList = setOf(
    "android.permission.INTERNET",
    "android.permission.RECEIVE_SMS",
    "android.permission.READ_SMS",
    "android.permission.READ_CALL_LOG",
    "android.permission.USE_EXACT_ALARM",
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
)

val dependencyDenyPrefixes = setOf(
    "com.google.firebase",
    "com.google.android.gms",
    "com.google.android.play",
    "com.google.android.ads",
    "com.google.ads",
    "com.google.dagger",
    "io.insert-koin",
    "com.crashlytics",
    "io.sentry",
    "com.squareup.okhttp3",
    "com.squareup.retrofit2",
    "io.ktor",
    "com.facebook",
    "com.appsflyer",
    "com.github.PhilJay",
    "com.patrykandpatrick.vico",
    "co.yml",
)

val dependencyDenyModules = setOf("androidx.compose.ui:ui-text-google-fonts")

val verifySourceRules = tasks.register<VerifySourceRulesTask>("verifySourceRules") {
    sourceDir.set(layout.projectDirectory.dir("src/main/kotlin"))
    versionCatalog.set(rootProject.layout.projectDirectory.file("gradle/libs.versions.toml"))
    report.set(layout.buildDirectory.file("reports/verify/source-rules.txt"))
}

tasks.register<VerifyCoverageTask>("verifyDomainCoverage") {
    dependsOn("createDebugUnitTestCoverageReport")
    coverageReport.set(layout.buildDirectory.file("reports/coverage/test/debug/report.xml"))
    packagePath.set("com/swastik/mbfitness/domain")
    minimumLinePercent.set(90)
    report.set(layout.buildDirectory.file("reports/verify/domain-coverage.txt"))
}

androidComponents {
    onVariants { variant ->
        val suffix = variant.name.replaceFirstChar { it.uppercase() }

        val manifestCheck = tasks.register<VerifyMergedManifestTask>("verify${suffix}MergedManifest") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            allowedPermissions.set(permissionAllowList)
            bannedPermissions.set(permissionDenyList)
            report.set(layout.buildDirectory.file("reports/verify/${variant.name}-permissions.txt"))
        }

        val dependencyCheck = tasks.register<VerifyDependenciesTask>("verify${suffix}Dependencies") {
            modules.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent.map(::collectModules))
            bannedGroupPrefixes.set(dependencyDenyPrefixes)
            bannedModules.set(dependencyDenyModules)
            report.set(layout.buildDirectory.file("reports/verify/${variant.name}-dependencies.txt"))
        }

        tasks.matching { it.name in setOf("assemble$suffix", "lint$suffix", "test${suffix}UnitTest") }
            .configureEach {
                dependsOn(verifySourceRules, manifestCheck, dependencyCheck)
            }
    }
}
