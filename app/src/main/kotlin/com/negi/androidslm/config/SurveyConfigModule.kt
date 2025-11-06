/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyConfigModule.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Lightweight facade to load and access SurveyConfig (YAML/JSON)
 *  providing unified entry points for:
 *   • model_defaults
 *   • slm
 *   • system prompt composition
 *   • SLM runtime parameter maps
 *
 *  Supports multiple input sources:
 *   - assets/<file>
 *   - res/raw/<resource>
 *   - external file path (debug/dev only)
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.androidslm.config

import android.content.Context
import android.util.Log
import androidx.annotation.RawRes
import com.negi.androidslm.slm.ConfigKey
import java.io.File

/**
 * Central entry point for loading and validating [SurveyConfig].
 *
 * This module wraps [SurveyConfigLoader] and provides a minimal API for
 * retrieving configuration-dependent components such as:
 *
 *  - Composed system prompt (for model initialization)
 *  - Runtime parameter maps (ConfigKey or string-keyed)
 *  - Model downloader defaults
 *
 * The default source is `assets/slm_config.yaml`.
 *
 * ---
 * ### Usage Example
 * ```kotlin
 * val cfg = SurveyConfigModule.load(context)
 * val systemPrompt = cfg.composeSystemPrompt()
 * val params = cfg.toSlmStringKeyMap()
 * val model = cfg.modelDefaultsOrFallback()
 * ```
 */
object SurveyConfigModule {

    private const val TAG = "SurveyConfigModule"

    // ---------------------------------------------------------------------
    // 📦 Source abstraction
    // ---------------------------------------------------------------------

    /**
     * Represents the source location for loading [SurveyConfig].
     *
     * - `Assets` → load from assets directory (default: `slm_config.yaml`)
     * - `Raw` → load from res/raw resource ID
     * - `FilePath` → load from absolute file path (for dev/debug builds)
     */
    sealed class Source {

        /** Load from `assets/<fileName>` (default: `slm_config.yaml`). */
        data class Assets(val fileName: String = "slm_config.yaml") : Source()

        /** Load from `res/raw/<resource>` using its integer ID. */
        data class Raw(@RawRes val resId: Int) : Source()

        /** Load from absolute file path (requires READ permissions if external). */
        data class FilePath(val path: String) : Source()
    }

    // ---------------------------------------------------------------------
    // ⚙️ Loader Core
    // ---------------------------------------------------------------------

    /**
     * Loads and validates [SurveyConfig] from the specified [Source].
     *
     * @param context The Android context used to resolve resources or assets.
     * @param source Source type (defaults to [Source.Assets]).
     * @param strictValidation If true, throws when validation fails.
     * @return Fully parsed and optionally validated [SurveyConfig].
     * @throws IllegalStateException If validation fails and `strictValidation = true`.
     */
    @JvmStatic
    fun load(
        context: Context,
        source: Source = Source.Assets(),
        strictValidation: Boolean = true
    ): SurveyConfig {
        val cfg = when (source) {
            is Source.Assets -> SurveyConfigLoader.fromAssets(context, source.fileName)
            is Source.Raw -> SurveyConfigLoader.fromRawResource(context, source.resId)
            is Source.FilePath -> {
                val file = File(source.path)
                require(file.exists()) { "Config file not found: ${source.path}" }
                SurveyConfigLoader.fromFile(file.path)
            }
        }

        val issues = cfg.validate()
        if (issues.isNotEmpty()) {
            val message = buildString {
                appendLine("⚠️ SurveyConfig validation issues:")
                issues.forEach { appendLine(" - $it") }
            }.trimEnd()

            if (strictValidation) {
                throw IllegalStateException(message)
            } else {
                Log.w(TAG, message)
            }
        }

        Log.i(TAG, "✅ SurveyConfig loaded successfully from ${describeSource(source)}")
        return cfg
    }

    // ---------------------------------------------------------------------
    // 🎛 Convenience Accessors
    // ---------------------------------------------------------------------

    /**
     * Returns a fully composed system prompt for the model runtime.
     */
    @JvmStatic
    fun systemPrompt(
        context: Context,
        source: Source = Source.Assets(),
        strictValidation: Boolean = true
    ): String = load(context, source, strictValidation).composeSystemPrompt()

    /**
     * Returns a runtime configuration map keyed by [ConfigKey].
     */
    @JvmStatic
    fun slmMpConfigMap(
        context: Context,
        source: Source = Source.Assets(),
        strictValidation: Boolean = true
    ): Map<ConfigKey, Any> =
        load(context, source, strictValidation).toSlmMpConfigMap()

    /**
     * Returns a runtime configuration map keyed by strings.
     * (Alternative for engines that lack [ConfigKey] enums.)
     */
    @JvmStatic
    fun slmStringKeyMap(
        context: Context,
        source: Source = Source.Assets(),
        strictValidation: Boolean = true
    ): Map<String, Any> =
        load(context, source, strictValidation).toSlmStringKeyMap()

    /**
     * Returns model downloader defaults (with safe fallback).
     */
    @JvmStatic
    fun modelDefaults(
        context: Context,
        source: Source = Source.Assets(),
        strictValidation: Boolean = true
    ): SurveyConfig.ModelDefaults =
        load(context, source, strictValidation).modelDefaultsOrFallback()

    // ---------------------------------------------------------------------
    // 🧭 Helpers
    // ---------------------------------------------------------------------

    /** Human-readable source description for logs. */
    private fun describeSource(source: Source): String = when (source) {
        is Source.Assets -> "assets/${source.fileName}"
        is Source.Raw -> "res/raw (id=${source.resId})"
        is Source.FilePath -> source.path
    }
}

/* ---------------------------------------------------------------------
 *  Optional: Hilt DI Module
 *  ---------------------------------------------------------------------
 *  Enable this block if your project uses Hilt dependency injection.
 *  This provides singleton bindings for SLM-related configuration objects.
 * --------------------------------------------------------------------- */
/*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SurveyConfigHiltModule {

    @Provides
    @Singleton
    fun provideSurveyConfig(@ApplicationContext context: Context): SurveyConfig =
        SurveyConfigModule.load(context)

    @Provides
    @Singleton
    fun provideSystemPrompt(cfg: SurveyConfig): String =
        cfg.composeSystemPrompt()

    @Provides
    @Singleton
    fun provideSlmMpConfigMap(cfg: SurveyConfig): Map<ConfigKey, Any> =
        cfg.toSlmMpConfigMap()

    @Provides
    @Singleton
    fun provideSlmStringKeyMap(cfg: SurveyConfig): Map<String, Any> =
        cfg.toSlmStringKeyMap()

    @Provides
    @Singleton
    fun provideModelDefaults(cfg: SurveyConfig): SurveyConfig.ModelDefaults =
        cfg.modelDefaultsOrFallback()
}
*/
