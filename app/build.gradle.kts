// file: app/build.gradle.kts
// ============================================================
// ✅ Android Application Build (Compose + Kotlin 2.2.21)
// ------------------------------------------------------------
// • Android Studio Koala (AI-252.*) | Gradle 8.14 | AGP 8.13.0
// • Compose BOM 2025.10.01 + Material3
// • MinSdk 26 / TargetSdk 36 / Java 17 toolchain
// • WorkManager + Orchestrator + MediaPipe Tasks supported
// • Deterministic CI builds (no timestamp variance)
// • 🔒 Always sign with DEBUG keystore (release も固定)
// ============================================================

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // ------------------------------------------------------------
    // 🔧 Load local.properties
    // ------------------------------------------------------------
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

    fun prop(name: String, default: String = ""): String =
        (project.findProperty(name) as String?)
            ?.takeIf { it.isNotBlank() }
            ?: localProps.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: default

    fun quote(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ------------------------------------------------------------
    // 🏷️ App Identity
    // ------------------------------------------------------------
    val appId = prop("appId", "com.negi.androidslm")

    namespace = appId
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
    }

    // ------------------------------------------------------------
    // 🔐 Signing（常に debug 署名）
    // ------------------------------------------------------------
    signingConfigs {
        getByName("debug")
    }

    // ------------------------------------------------------------
    // 🧱 Build Types
    // ------------------------------------------------------------
    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            buildConfigField("String", "GH_OWNER",       quote(prop("gh.owner")))
            buildConfigField("String", "GH_REPO",        quote(prop("gh.repo")))
            buildConfigField("String", "GH_BRANCH",      quote(prop("gh.branch", "main")))
            buildConfigField("String", "GH_PATH_PREFIX", quote(prop("gh.pathPrefix", "exports")))
            buildConfigField("String", "GH_TOKEN",       quote(prop("gh.token")))
            buildConfigField("String", "HF_TOKEN",       quote(prop("HF_TOKEN")))
        }

        // 🔒 release も常に debug 署名
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            println("🔁 [buildTypes.release] Always using DEBUG signing for RELEASE build.")

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            buildConfigField("String", "GH_OWNER",       quote(prop("gh.owner")))
            buildConfigField("String", "GH_REPO",        quote(prop("gh.repo")))
            buildConfigField("String", "GH_BRANCH",      quote(prop("gh.branch", "main")))
            buildConfigField("String", "GH_PATH_PREFIX", quote(prop("gh.pathPrefix", "exports")))
            buildConfigField("String", "GH_TOKEN",       quote(prop("gh.token")))
            buildConfigField("String", "HF_TOKEN",       quote(prop("HF_TOKEN")))
        }
    }

    // ------------------------------------------------------------
    // ⚙️ Java/Kotlin
    // ------------------------------------------------------------
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    // 🧠 Kotlin Compiler Options — 2.2.21+
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
            freeCompilerArgs.addAll(
                listOf(
                    "-opt-in=kotlin.RequiresOptIn",
                    "-Xcontext-parameters",                // replaces -Xcontext-receivers
                    "-Xannotation-default-target=param-property",
                    "-Xjsr305=strict"
                )
            )
        }
    }

    // 🧩 Compose
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "2.2.21"
    }

    // 🧪 Test / Orchestrator
    testBuildType = "debug"
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
    }

    // 📦 Packaging
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "/META-INF/LICENSE.md",
                "/META-INF/LICENSE-notice.md",
                "META-INF/*.kotlin_module"
            )
        }
    }

    // 🧹 Lint
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        textReport = true
        textOutput = file("build/reports/lint-results.txt")
    }
}

// ============================================================
// 🔁 Deterministic outputs
// ============================================================
tasks.withType<org.gradle.api.tasks.bundling.AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// ============================================================
// 📦 Dependencies
// ============================================================
dependencies {
    // --- Core AndroidX ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // --- Jetpack Compose ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.compose.foundation.layout)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // --- Navigation 3 ---
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation.compose)

    // --- Kotlin / Coroutines / Serialization ---
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)

    // --- WorkManager ---
    implementation(libs.androidx.work.runtime.ktx)

    // --- Media3 & MediaPipe ---
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.mediapipe.tasks.genai)

    // --- Desugaring ---
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // --- Testing ---
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.documentfile)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestUtil(libs.androidx.test.orchestrator)
}
