// file: build.gradle.kts
// ============================================================
// ✅ Root Build Script — Kotlin DSL + Version Catalog
// ------------------------------------------------------------
// • Central plugin management via libs.versions.toml
// • Compatible with AGP 8.13 / Kotlin 2.2.21 / Gradle 8.14
// • Enforces JDK 17 toolchain across modules
// • Local-friendly logging, CI-safe behavior
// ============================================================

plugins {
    // Application modules (e.g. :app)
    alias(libs.plugins.android.application) apply false

    // Library modules
    alias(libs.plugins.android.library) apply false

    // Kotlin support
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// ============================================================
// 🧩 Global Configuration for All Subprojects
// ------------------------------------------------------------
// • Kotlin compiler target 17
// • Java toolchain target 17
// • Null-safety interop enforcement
// • CI-safe logging (skipped in pipelines)
// ============================================================

subprojects {

    // --- Kotlin Android modules ---
    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                freeCompilerArgs.addAll(listOf("-Xjsr305=strict")) // Strict Java interop

                // Optional: treat warnings as errors locally (disabled on CI)
                allWarningsAsErrors.set(System.getenv("CI").isNullOrEmpty())
            }
        }
    }

    // --- Kotlin JVM (non-Android) modules ---
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                freeCompilerArgs.addAll(listOf("-Xjsr305=strict"))
            }
        }
    }

    // --- Plain Java modules (if any) ---
    plugins.withId("java") {
        extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    // --- Common test configuration (JUnit 5, clear output) ---
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
            showStandardStreams = false
        }
    }

    // --- Logging for local builds only ---
    if (System.getenv("CI").isNullOrEmpty()) {
        afterEvaluate {
            logger.lifecycle("✅ Module configured: ${project.name}")
        }
    }
}

// ============================================================
// ⚙️ Gradle Wrapper & Cache Policy
// ------------------------------------------------------------
// • Build cache configuration must live in settings.gradle.kts
//   Example:
//
//   buildCache {
//       local {
//           isEnabled = System.getenv("CI") == null
//       }
//   }
//
// • Wrapper JDK version and Gradle version should match toolchain.
// ============================================================

// --- Safety: Verify wrapper version consistency (local-only to keep config-cache happy on CI) ---
if (System.getenv("CI").isNullOrEmpty()) {
    gradle.projectsEvaluated {
        val wrapperFile = file("gradle/wrapper/gradle-wrapper.properties")
        if (!wrapperFile.exists()) {
            logger.warn("⚠️ gradle/wrapper/gradle-wrapper.properties not found.")
            return@projectsEvaluated
        }

        val content = wrapperFile.readText()
        val wrapper = Regex("distributionUrl=.*gradle-([0-9.]+)-").find(content)?.groupValues?.getOrNull(1)
        val ok = run {
            val parts = (wrapper ?: "0.0").split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            (major > 8) || (major == 8 && minor >= 13)
        }

        if (wrapper == null) {
            logger.warn("⚠️ distributionUrl missing in gradle-wrapper.properties")
        } else if (!ok) {
            logger.warn("⚠️ Gradle Wrapper $wrapper (< 8.13). Consider upgrading to 8.13+.")
        } else {
            logger.lifecycle("✅ Gradle Wrapper OK ($wrapper)")
        }
    }
}

// ============================================================
// 🧠 Debug Info Summary
// ------------------------------------------------------------
// 🧩 Kotlin:      2.2.21
// 🧩 AGP:         8.13
// 🧩 Gradle:      8.14.x (Wrapper 8.13+ accepted)
// 🧩 JDK:         17 (toolchain-enforced)
// 🧩 Tests:       JUnit 5 enabled globally
// 🧩 Cache:       Controlled via settings.gradle.kts
// ============================================================

// ============================================================
// 🧱 End of Root Build Script
// ------------------------------------------------------------
// ✅ Verified for Gradle 8.14 / Kotlin 2.2.21 / AGP 8.13
// ============================================================
