// file: settings.gradle.kts
// ============================================================
// ✅ Root Project Settings — Gradle 8.14.3 / AGP 8.13.0 / Kotlin 2.2.21
// ------------------------------------------------------------
// • Centralized plugin & repository management
// • Deterministic CI, reproducible local builds
// • Secure, minimal scope dependency resolution
// ============================================================

pluginManagement {
    // --------------------------------------------------------
    // Plugin repositories (scoped for safety)
    // --------------------------------------------------------
    repositories {
        google {
            // Restrict scope for Android / Google / AndroidX artifacts
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }

    // --------------------------------------------------------
    // Explicit plugin versions (reproducible builds)
    // --------------------------------------------------------
    plugins {
        id("com.android.application") version "8.13.0" apply false
        id("com.android.library") version "8.13.0" apply false
        id("org.jetbrains.kotlin.android") version "2.2.21" apply false
        id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
        // id("com.gradle.enterprise") version "3.17.7" apply false // Optional: Build scans
    }
}

dependencyResolutionManagement {
    // --------------------------------------------------------
    // Enforce central repositories (no per-project repos)
    // --------------------------------------------------------
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()

        // Optional: Compose preview builds (only if enabled)
        if (System.getenv("USE_COMPOSE_DEV") == "true") {
            maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        }
    }

    // Version catalog auto-loads from gradle/libs.versions.toml
}

// ------------------------------------------------------------
// Build cache & type-safe accessors
// ------------------------------------------------------------

// Enable libs.versions.toml type-safe accessors
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// ------------------------------------------------------------
// 🧱 Build Cache Configuration
// ------------------------------------------------------------
// • Local cache enabled on developer machines for speed
// • Disabled on CI for deterministic outputs
// ------------------------------------------------------------
buildCache {
    local {
        isEnabled = System.getenv("CI") == null
    }
    // Optional: remote cache configuration example
    // remote<HttpBuildCache> {
    //     url = uri("https://your-cache.example.com/cache/")
    //     isPush = System.getenv("CI") == "true"
    // }
}

// ============================================================
// 🧩 Project identity and module structure
// ============================================================
rootProject.name = "AndroidSLM"

include(":app")
// include(":nativelib")      // Uncomment for JNI/native module
// include(":feature:survey")  // Example feature module

// ============================================================
// 🧠 Debug utilities (guarded to avoid noise on large builds)
// ============================================================
if (System.getenv("VERBOSE_SYNC") == "true") {
    gradle.beforeProject {
        logger.lifecycle("🔧 Configuring project: ${project.name} (Gradle ${gradle.gradleVersion})")
    }
    gradle.afterProject {
        logger.lifecycle("✅ Finished configuring: ${project.name}")
    }
}

// ============================================================
// 🌐 Optional: Build Scan (Gradle Enterprise)
// ============================================================
// plugins {
//     id("com.gradle.enterprise") version "3.17.7"
// }
// gradleEnterprise {
//     buildScan {
//         termsOfServiceUrl = "https://gradle.com/terms-of-service"
//         termsOfServiceAgree = "yes"
//         // Publish scans only on CI for safety
//         publishing.onlyIf { System.getenv("CI") != null }
//     }
// }

// ------------------------------------------------------------
// 📝 Notes
// ------------------------------------------------------------
// • AGP 8.13 requires Gradle 8.13+ (8.14.3 preferred).
// • Ensure wrapper alignment:
//     $ ./gradlew wrapper --gradle-version 8.14.3
// • distributionUrl should be set in:
//     gradle/wrapper/gradle-wrapper.properties
// ------------------------------------------------------------
