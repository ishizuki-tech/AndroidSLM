/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyConfig.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  The SLM Integration Framework provides a modular foundation for
 *  embedding Small Language Models (SLM) in Android apps.
 *
 *  Highlights:
 *   • Portable configuration in JSON or YAML.
 *   • Safe, forward-compatible parsing via kotlinx.serialization + KAML.
 *   • Unified model downloader defaults and prompt definitions.
 *   • Robust validation and developer-friendly error handling.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.androidslm.config

import android.content.Context
import androidx.annotation.RawRes
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import com.negi.androidslm.slm.ConfigKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

/**
 * Root configuration model for **SLM runtime and model downloader defaults**.
 *
 * This model can be stored and loaded in both JSON and YAML formats.
 * It defines three primary sections:
 *
 * | Section | Description |
 * |----------|-------------|
 * | `prompts` | Optional list of text prompts (no graph dependencies). |
 * | `slm` | Runtime hyperparameters and structured system prompt components. |
 * | `model_defaults` | Optional defaults for downloader configuration. |
 *
 * ---
 * Example (`assets/slm_config.yaml`):
 * ```yaml
 * model_defaults:
 *   default_model_url: "https://..."
 *   default_file_name: "Gemma3n4B.litertlm"
 *   timeout_ms: 1800000
 *   ui_throttle_ms: 250
 *   ui_min_delta_bytes: 1048576
 *
 * slm:
 *   accelerator: "GPU"
 *   max_tokens: 4096
 *   top_k: 10
 *   top_p: 0.2
 *   temperature: 0.5
 *   preamble: "You are a farmer survey expert."
 *   key_contract: |
 *     OUTPUT FORMAT:
 *       ...
 *   scoring_rule: |
 *     Score based on clarity and correctness.
 * ```
 */
@Serializable
data class SurveyConfig(
    /** Optional prompt list. Kept flat for simplicity. */
    val prompts: List<Prompt> = emptyList(),

    /** SLM runtime parameters and system prompt metadata. */
    val slm: SlmMeta = SlmMeta(),

    /** Optional model download defaults (top-level `model_defaults`). */
    @SerialName("model_defaults")
    val modelDefaults: ModelDefaults? = null
) {
    // ---------------------------------------------------------------------
    // 🗒 Prompt
    // ---------------------------------------------------------------------

    /**
     * Represents a single prompt entry in configuration.
     * @property nodeId Unique identifier for the prompt.
     * @property prompt Prompt text body.
     */
    @Serializable
    data class Prompt(
        val nodeId: String,
        val prompt: String
    )

    // ---------------------------------------------------------------------
    // ⚙️ SLM Meta
    // ---------------------------------------------------------------------

    /**
     * Encapsulates all runtime and system prompt metadata for SLM.
     * All fields are optional and safely defaulted.
     */
    @Serializable
    data class SlmMeta(
        // Runtime parameters
        @SerialName("accelerator") val accelerator: String? = null, // "CPU" or "GPU"
        @SerialName("max_tokens") val maxTokens: Int? = null,
        @SerialName("top_k") val topK: Int? = null,
        @SerialName("top_p") val topP: Double? = null,
        @SerialName("temperature") val temperature: Double? = null,

        // System prompt fragments
        @SerialName("user_turn_prefix") val user_turn_prefix: String? = null,
        @SerialName("model_turn_prefix") val model_turn_prefix: String? = null,
        @SerialName("turn_end") val turn_end: String? = null,
        @SerialName("empty_json_instruction") val empty_json_instruction: String? = null,
        @SerialName("preamble") val preamble: String? = null,
        @SerialName("key_contract") val key_contract: String? = null,
        @SerialName("length_budget") val length_budget: String? = null,
        @SerialName("scoring_rule") val scoring_rule: String? = null,
        @SerialName("strict_output") val strict_output: String? = null
    )

    // ---------------------------------------------------------------------
    // 📦 Model Defaults
    // ---------------------------------------------------------------------

    /**
     * Default parameters for model downloader UI and network layer.
     * Each field can be overridden from YAML.
     */
    @Serializable
    data class ModelDefaults(
        @SerialName("default_model_url")
        val defaultModelUrl: String =
            "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm",

        @SerialName("default_file_name")
        val defaultFileName: String = "Gemma3n4B.litertlm",

        @SerialName("timeout_ms")
        val timeoutMs: Long = 30L * 60 * 1000, // 30 minutes

        @SerialName("ui_throttle_ms")
        val uiThrottleMs: Long = 250L,

        @SerialName("ui_min_delta_bytes")
        val uiMinDeltaBytes: Long = 1L * 1024L * 1024L // 1 MiB
    )

    /** Returns non-null defaults (safe fallback). */
    fun modelDefaultsOrFallback(): ModelDefaults = modelDefaults ?: ModelDefaults()

    // ---------------------------------------------------------------------
    // 🧩 Validation & Conversion Utilities
    // ---------------------------------------------------------------------

    /**
     * Validates configuration consistency and sanity.
     *
     * @return List of validation messages (empty if valid).
     */
    fun validate(): List<String> {
        val issues = mutableListOf<String>()

        // Check for duplicate prompt IDs
        val duplicates = prompts.groupingBy { it.nodeId }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            issues += "Duplicate prompt IDs: ${duplicates.joinToString(", ")}"
        }

        // Validate SLM runtime parameters
        slm.accelerator?.let {
            val normalized = it.trim().uppercase()
            if (normalized !in listOf("CPU", "GPU"))
                issues += "Invalid accelerator: '$it'. Expected 'CPU' or 'GPU'."
        }
        slm.maxTokens?.takeIf { it <= 0 }?.let { issues += "max_tokens must be > 0 (got $it)" }
        slm.topK?.takeIf { it < 0 }?.let { issues += "top_k must be >= 0 (got $it)" }
        slm.topP?.takeIf { it !in 0.0..1.0 }?.let { issues += "top_p must be in [0,1] (got $it)" }
        slm.temperature?.takeIf { it < 0.0 }?.let { issues += "temperature must be >= 0 (got $it)" }

        // Validate ModelDefaults if present
        modelDefaults?.let { md ->
            if (md.defaultModelUrl.isBlank()) issues += "default_model_url cannot be blank"
            if (md.defaultFileName.isBlank()) issues += "default_file_name cannot be blank"
            if (md.timeoutMs <= 0) issues += "timeout_ms must be > 0"
        }

        return issues
    }

    /** Throws [IllegalStateException] if validation fails. */
    fun validateOrThrow() {
        val problems = validate()
        if (problems.isNotEmpty()) {
            throw IllegalStateException(
                "SurveyConfig validation failed:\n - " + problems.joinToString(
                    "\n - "
                )
            )
        }
    }

    /** Converts [prompts] into compact JSON Lines for lightweight export. */
    fun toJsonl(): List<String> =
        SurveyConfigLoader.jsonCompact.let { j ->
            prompts.map {
                j.encodeToString(
                    Prompt.serializer(),
                    it
                )
            }
        }

    /** Serializes the entire config as JSON (pretty or compact). */
    fun toJson(pretty: Boolean = true): String =
        (if (pretty) SurveyConfigLoader.jsonPretty else SurveyConfigLoader.jsonCompact)
            .encodeToString(serializer(), this)

    /** Serializes the entire config to YAML (strict mode optional). */
    fun toYaml(strict: Boolean = false): String =
        SurveyConfigLoader.yaml(strict).encodeToString(serializer(), this)

    /**
     * Saves the configuration to disk in the specified format.
     * Defaults to JSON when `format = AUTO`.
     */
    fun toFile(path: String, format: ConfigFormat = ConfigFormat.JSON, pretty: Boolean = true) {
        val text = when (format) {
            ConfigFormat.JSON -> toJson(pretty)
            ConfigFormat.YAML -> toYaml(strict = false)
            ConfigFormat.AUTO -> toJson(pretty)
        }
        runCatching {
            val f = File(path)
            f.parentFile?.mkdirs()
            f.writeText(text, Charsets.UTF_8)
        }.getOrElse { e ->
            throw IOException("Failed to write SurveyConfig to '$path': ${e.message}", e)
        }
    }

    /**
     * Builds a unified system prompt string by concatenating
     * non-empty instruction fragments in logical order.
     */
    fun composeSystemPrompt(): String {
        fun String?.appendTo(sb: StringBuilder) {
            if (!this.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.appendLine()
                sb.append(this)
            }
        }
        return buildString {
            slm.preamble.appendTo(this)
            slm.key_contract.appendTo(this)
            slm.length_budget.appendTo(this)
            slm.scoring_rule.appendTo(this)
            slm.strict_output.appendTo(this)
            slm.empty_json_instruction.appendTo(this)
        }
    }

    /** Retrieves a prompt text by its ID, or returns `null` if not found. */
    fun promptFor(id: String): String? = prompts.firstOrNull { it.nodeId == id }?.prompt
}

// ---------------------------------------------------------------------
// ⚙️ Format Enum
// ---------------------------------------------------------------------

/** Supported input/output formats for [SurveyConfig]. */
enum class ConfigFormat { JSON, YAML, AUTO }

// ---------------------------------------------------------------------
// 🔧 Mapping Utilities
// ---------------------------------------------------------------------

/**
 * Converts SLM metadata to a [ConfigKey]-typed configuration map.
 * Useful for engines that use a predefined enum configuration schema.
 */
fun SurveyConfig.toSlmMpConfigMap(): Map<ConfigKey, Any> {
    val s = this.slm
    val acc = (s.accelerator ?: "GPU").uppercase()
    return buildMap {
        put(ConfigKey.ACCELERATOR, acc)
        put(ConfigKey.MAX_TOKENS, s.maxTokens ?: 8192)
        put(ConfigKey.TOP_K, s.topK ?: 50)
        put(ConfigKey.TOP_P, s.topP ?: 0.95)
        put(ConfigKey.TEMPERATURE, s.temperature ?: 0.7)
    }
}

/**
 * Converts SLM metadata to a generic string-keyed configuration map.
 * Intended for lightweight or external engines without enum bindings.
 */
fun SurveyConfig.toSlmStringKeyMap(): Map<String, Any> {
    val s = this.slm
    return buildMap {
        put("accelerator", (s.accelerator ?: "GPU").uppercase())
        put("max_tokens", s.maxTokens ?: 8192)
        put("top_k", s.topK ?: 50)
        put("top_p", s.topP ?: 0.95)
        put("temperature", s.temperature ?: 0.7)
        s.user_turn_prefix?.let { put("user_turn_prefix", it) }
        s.model_turn_prefix?.let { put("model_turn_prefix", it) }
        s.turn_end?.let { put("turn_end", it) }
    }
}

// ---------------------------------------------------------------------
// 🧠 Loader (Assets / Raw / Files / Strings)
// ---------------------------------------------------------------------

/**
 * Unified, resilient loader for [SurveyConfig].
 *
 * Supports loading from:
 * - Android raw resources
 * - Asset files
 * - File paths
 * - Direct string input
 *
 * Performs normalization (BOM stripping, newline unification) and
 * automatically detects file format (JSON/YAML) when `AUTO`.
 */
object SurveyConfigLoader {

    // Compact JSON (for production)
    internal val jsonCompact: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        isLenient = true
    }

    // Pretty JSON (for debug inspection)
    internal val jsonPretty: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
    }

    /** YAML encoder/decoder with optional strict mode. */
    internal fun yaml(strict: Boolean = false): Yaml =
        Yaml(configuration = YamlConfiguration(encodeDefaults = false, strictMode = strict))

    // -----------------------------------------------------------------
    // Source Loaders
    // -----------------------------------------------------------------

    /** Loads config from res/raw with format autodetection. */
    fun fromRawResource(
        context: Context,
        @RawRes resId: Int,
        charset: Charset = Charsets.UTF_8,
        format: ConfigFormat = ConfigFormat.AUTO
    ): SurveyConfig = try {
        val name = runCatching { context.resources.getResourceEntryName(resId) }.getOrNull()
        context.resources.openRawResource(resId).bufferedReader(charset).use { reader ->
            val text = reader.readText().normalize()
            fromString(text, format = pickFormat(format, name, text))
        }
    } catch (ex: Exception) {
        throw IllegalArgumentException(
            "Failed to load SurveyConfig from raw resource (id=$resId): ${ex.message}",
            ex
        )
    }

    /** Loads config from the app's assets directory. */
    fun fromAssets(
        context: Context,
        fileName: String,
        charset: Charset = Charsets.UTF_8,
        format: ConfigFormat = ConfigFormat.AUTO
    ): SurveyConfig = try {
        context.assets.open(fileName).bufferedReader(charset).use { reader ->
            val text = reader.readText().normalize()
            fromString(text, format = pickFormat(format, fileName, text))
        }
    } catch (ex: Exception) {
        throw IllegalArgumentException(
            "Failed to load SurveyConfig from assets/$fileName: ${ex.message}",
            ex
        )
    }

    /** Loads config from a file path. */
    fun fromFile(
        path: String,
        charset: Charset = Charsets.UTF_8,
        format: ConfigFormat = ConfigFormat.AUTO
    ): SurveyConfig = try {
        val file = File(path)
        require(file.exists()) { "Config file not found: $path" }
        file.bufferedReader(charset).use { reader ->
            val text = reader.readText().normalize()
            fromString(text, format = pickFormat(format, file.name, text))
        }
    } catch (ex: Exception) {
        throw IllegalArgumentException(
            "Failed to load SurveyConfig from file '$path': ${ex.message}",
            ex
        )
    }

    /** Parses config from raw text (detecting format automatically if needed). */
    fun fromString(
        text: String,
        format: ConfigFormat = ConfigFormat.AUTO,
        fileNameHint: String? = null
    ): SurveyConfig = try {
        val sanitized = text.normalize()
        val chosen = pickFormat(format, fileNameHint, sanitized)
        when (chosen) {
            ConfigFormat.JSON -> jsonCompact.decodeFromString(SurveyConfig.serializer(), sanitized)
            ConfigFormat.YAML -> yaml(strict = false).decodeFromString(
                SurveyConfig.serializer(),
                sanitized
            )

            ConfigFormat.AUTO -> error("AUTO should have been resolved; this is a bug.")
        }
    } catch (ex: SerializationException) {
        throw IllegalArgumentException(
            "JSON parsing error: ${text.safePreview()} :: ${ex.message}",
            ex
        )
    } catch (ex: YamlException) {
        throw IllegalArgumentException(
            "YAML parsing error: ${text.safePreview()} :: ${ex.message}",
            ex
        )
    } catch (ex: Exception) {
        throw IllegalArgumentException(
            "Unexpected error parsing SurveyConfig: ${text.safePreview()} :: ${ex.message}",
            ex
        )
    }

    // -----------------------------------------------------------------
    // Internal Helpers
    // -----------------------------------------------------------------

    /** Determines the final format when AUTO is requested. */
    private fun pickFormat(
        desired: ConfigFormat,
        fileName: String? = null,
        text: String? = null
    ): ConfigFormat {
        if (desired != ConfigFormat.AUTO) return desired
        val lower = fileName?.lowercase().orEmpty()
        if (lower.endsWith(".json")) return ConfigFormat.JSON
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return ConfigFormat.YAML
        return text?.let(::sniffFormat) ?: ConfigFormat.JSON
    }

    /** Simple heuristic for detecting JSON vs YAML from content. */
    private fun sniffFormat(text: String): ConfigFormat {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return when {
            trimmed.startsWith("{") || trimmed.startsWith("[") -> ConfigFormat.JSON
            trimmed.startsWith("---") -> ConfigFormat.YAML
            ":" in trimmed.lineSequence().firstOrNull().orEmpty() -> ConfigFormat.YAML
            else -> ConfigFormat.JSON
        }
    }

    /** Normalizes input text: removes BOM, unifies newlines, trims trailing lines. */
    private fun String.normalize(): String =
        removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n").trimEnd('\n')

    /** Produces a compact, single-line preview for debug output. */
    private fun String.safePreview(max: Int = 200): String =
        replace("\n", "\\n").replace("\r", "\\r")
            .let { if (it.length <= max) it else it.take(max) + "…" }
}
