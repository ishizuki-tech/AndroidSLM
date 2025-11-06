/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SLM.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Concurrency-safe manager for on-device MediaPipe LLM inference sessions.
 *  Provides lifecycle-safe initialization, inference execution, cancellation,
 *  and cleanup with support for GPU/CPU fallback and session reuse.
 *
 *  Highlights:
 *   • Thread-safe model initialization and teardown.
 *   • Streaming partial result callbacks with MediaPipe’s async interface.
 *   • Graceful fallback from GPU → CPU if initialization fails.
 *   • Safe cancel/reset/cleanup orchestration with synthetic onClean events.
 *   • Configurable inference parameters (topK, topP, temperature, tokens).
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.androidslm.slm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

// ---------------------------------------------------------------------
// 🔧 Configuration & Enums
// ---------------------------------------------------------------------

/** Hardware accelerator preference (CPU or GPU). */
enum class Accelerator(val label: String) { CPU("CPU"), GPU("GPU") }

/** Supported runtime configuration keys for LLM inference. */
enum class ConfigKey { MAX_TOKENS, TOP_K, TOP_P, TEMPERATURE, ACCELERATOR }

/** Default model parameter values. */
private const val DEFAULT_MAX_TOKEN = 256
private const val DEFAULT_TOP_K = 40
private const val DEFAULT_TOP_P = 0.9f
private const val DEFAULT_TEMPERATURE = 0.7f
private const val TAG = "SLM"

/** Callback for incremental inference output. */
typealias ResultListener = (partialResult: String, done: Boolean) -> Unit

/** Callback invoked when the model session is cleaned up. */
typealias CleanUpListener = () -> Unit

/** Execution state of a loaded model session. */
enum class RunState { IDLE, RUNNING, CANCELLING }

// ---------------------------------------------------------------------
// 📦 Model and Session Data Structures
// ---------------------------------------------------------------------

/**
 * Represents a configured model and its runtime parameters.
 *
 * @property name Unique identifier for this model.
 * @property taskPath Path to the `.task` file or model bundle.
 * @property config Optional map of configuration values.
 * @property instance Current active [LlmModelInstance] or `null` if uninitialized.
 */
data class Model(
    val name: String,
    private val taskPath: String,
    val config: Map<ConfigKey, Any> = emptyMap(),
    @Volatile var instance: LlmModelInstance? = null
) {
    fun getPath() = taskPath

    fun getIntConfigValue(key: ConfigKey, default: Int) =
        (config[key] as? Number)?.toInt()
            ?: (config[key] as? String)?.toIntOrNull()
            ?: default

    fun getFloatConfigValue(key: ConfigKey, default: Float) =
        when (val v = config[key]) {
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull() ?: default
            else -> default
        }

    fun getStringConfigValue(key: ConfigKey, default: String) =
        (config[key] as? String) ?: default
}

/**
 * Holds initialized [LlmInference] engine and active [LlmInferenceSession].
 *
 * @property engine The underlying MediaPipe inference engine.
 * @property session Active session instance for text generation.
 * @property state Atomic state tracking for concurrency control.
 */
data class LlmModelInstance(
    val engine: LlmInference,
    @Volatile var session: LlmInferenceSession,
    val state: AtomicReference<RunState> = AtomicReference(RunState.IDLE)
)

// ---------------------------------------------------------------------
// 🧠 Safe Language Model (SLM) Manager
// ---------------------------------------------------------------------

/**
 * Thread-safe static manager for MediaPipe-based LLM inference.
 *
 * Handles initialization, streaming, cancellation, and cleanup with
 * proper state transitions and fallback behavior.
 */
object SLM {

    private val cleanUpListeners = ConcurrentHashMap<String, CleanUpListener>()

    /** Returns true if the model is currently busy running inference. */
    fun isBusy(model: Model): Boolean =
        model.instance?.state?.get()?.let { it != RunState.IDLE } == true

    // -----------------------------------------------------------------
    // 🚀 Initialization
    // -----------------------------------------------------------------

    /**
     * Initializes or re-initializes a model instance with the given configuration.
     *
     * @param context Android context.
     * @param model Target model to initialize.
     * @param onDone Callback invoked with an empty string on success or an error message on failure.
     */
    @Synchronized
    fun initialize(context: Context, model: Model, onDone: (String) -> Unit) {
        var oldEngine: LlmInference? = null
        var oldSession: LlmInferenceSession? = null

        model.instance?.let { inst ->
            if (inst.state.get() != RunState.IDLE) {
                onDone("Model '${model.name}' is busy. Try again later or call cancel().")
                return
            }
            oldSession = inst.session
            oldEngine = inst.engine
            cleanUpListeners.remove(keyOf(model))?.invoke()
            model.instance = null
        }

        tryCloseQuietly(oldSession)
        safeClose(oldEngine)

        val maxTokens = model.getIntConfigValue(ConfigKey.MAX_TOKENS, DEFAULT_MAX_TOKEN)
        val topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOP_K))
        val topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOP_P))
        val temp = sanitizeTemperature(
            model.getFloatConfigValue(
                ConfigKey.TEMPERATURE,
                DEFAULT_TEMPERATURE
            )
        )
        val backendPref = model.getStringConfigValue(ConfigKey.ACCELERATOR, Accelerator.GPU.label)

        val backend = when (backendPref) {
            Accelerator.CPU.label -> LlmInference.Backend.CPU
            else -> LlmInference.Backend.GPU
        }

        val baseOpts = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(model.getPath())
            .setMaxTokens(maxTokens)

        val engine = try {
            LlmInference.createFromOptions(context, baseOpts.setPreferredBackend(backend).build())
        } catch (e: Exception) {
            if (backend == LlmInference.Backend.GPU) {
                Log.w(TAG, "GPU initialization failed. Falling back to CPU.")
                try {
                    LlmInference.createFromOptions(
                        context,
                        baseOpts.setPreferredBackend(LlmInference.Backend.CPU).build()
                    )
                } catch (e2: Exception) {
                    onDone(cleanError(e2.message)); return
                }
            } else {
                onDone(cleanError(e.message)); return
            }
        }

        try {
            val session = buildSessionFromModel(engine, topK, topP, temp)
            model.instance = LlmModelInstance(engine, session)
            onDone("")
        } catch (e: Exception) {
            safeClose(engine)
            onDone(cleanError(e.message))
        }
    }

    // -----------------------------------------------------------------
    // 🔄 Session Management
    // -----------------------------------------------------------------

    /**
     * Rebuilds the inference session while keeping the existing engine alive.
     *
     * @return `true` if successfully reset; `false` otherwise.
     */
    fun resetSession(model: Model): Boolean {
        val snap = synchronized(this) {
            val inst = model.instance ?: return false
            if (inst.state.get() != RunState.IDLE) return false
            Snap(
                inst.engine,
                inst.session,
                sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOP_K)),
                sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOP_P)),
                sanitizeTemperature(
                    model.getFloatConfigValue(
                        ConfigKey.TEMPERATURE,
                        DEFAULT_TEMPERATURE
                    )
                )
            )
        }

        tryCloseQuietly(snap.oldSession)
        val newSession = try {
            buildSessionFromModel(snap.engine, snap.topK, snap.topP, snap.temperature)
        } catch (e: Exception) {
            Log.e(TAG, "Session reset failed: ${e.message}")
            return false
        }

        synchronized(this) {
            val inst = model.instance ?: return false.also { tryCloseQuietly(newSession) }
            if (inst.engine != snap.engine || inst.state.get() != RunState.IDLE) {
                tryCloseQuietly(newSession)
                return false
            }
            inst.session = newSession
        }
        return true
    }

    // -----------------------------------------------------------------
    // 🧹 Cleanup & Cancellation
    // -----------------------------------------------------------------

    /** Fully disposes of engine and session resources for the given model. */
    @Synchronized
    fun cleanUp(model: Model, onDone: () -> Unit) {
        val inst = model.instance ?: return onDone()

        if (inst.state.get() != RunState.IDLE) {
            inst.session.cancelGenerateResponseAsync()
            inst.state.set(RunState.IDLE)
            cleanUpListeners.remove(keyOf(model))?.invoke()
        } else {
            cleanUpListeners.remove(keyOf(model))?.invoke()
        }

        model.instance = null
        tryCloseQuietly(inst.session)
        safeClose(inst.engine)
        onDone()
    }

    /** Cancels an active generation request, invoking [CleanUpListener] if present. */
    @Synchronized
    fun cancel(model: Model) {
        val inst = model.instance ?: return
        if (inst.state.get() != RunState.IDLE) {
            inst.state.set(RunState.CANCELLING)
            inst.session.cancelGenerateResponseAsync()
            inst.state.set(RunState.IDLE)
            cleanUpListeners.remove(keyOf(model))?.invoke()
        }
    }

    // -----------------------------------------------------------------
    // 🧩 Inference Execution
    // -----------------------------------------------------------------

    /**
     * Runs an asynchronous inference session, streaming partial outputs.
     *
     * @param model Target model (must be initialized).
     * @param input User or system prompt text.
     * @param listener Called for each partial result and final output.
     * @param onClean Invoked when the model session is cleaned up.
     */
    fun runInference(
        model: Model,
        input: String,
        listener: ResultListener,
        onClean: CleanUpListener
    ) {
        val inst = model.instance ?: return listener("Model not initialized.", true)

        if (!inst.state.compareAndSet(RunState.IDLE, RunState.RUNNING)) {
            cancel(model)
            inst.state.compareAndSet(RunState.IDLE, RunState.RUNNING)
        }

        Log.d(TAG, "runInference: model='${model.name}', input.len=${input.length}")
        cleanUpListeners[keyOf(model)] = {
            inst.state.set(RunState.IDLE)
            onClean()
        }

        val text = input.trim()
        if (text.isNotEmpty()) inst.session.addQueryChunk(text)

        inst.session.generateResponseAsync { partial, done ->
            val preview = if (partial.length > 256)
                partial.take(128) + " … " + partial.takeLast(64)
            else partial

            Log.d(TAG, "partial[len=${partial.length}, done=$done]: $preview")

            if (!done) listener(partial, false)
            else {
                listener(partial, true)
                cleanUpListeners.remove(keyOf(model))?.invoke()
            }
        }
    }

    // -----------------------------------------------------------------
    // 🧰 Internal Utilities
    // -----------------------------------------------------------------

    private fun buildSessionFromModel(
        engine: LlmInference,
        topK: Int,
        topP: Float,
        temp: Float
    ): LlmInferenceSession =
        LlmInferenceSession.createFromOptions(
            engine,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(topK)
                .setTopP(topP)
                .setTemperature(temp)
                .build()
        )

    private fun sanitizeTopK(k: Int) = k.coerceAtLeast(1)
    private fun sanitizeTopP(p: Float) = p.takeIf { it in 0f..1f } ?: DEFAULT_TOP_P
    private fun sanitizeTemperature(t: Float) = t.takeIf { it in 0f..2f } ?: DEFAULT_TEMPERATURE

    private fun keyOf(model: Model) = "${model.name}#${System.identityHashCode(model)}"

    private fun cleanError(msg: String?) =
        msg?.replace("INTERNAL:", "")?.replace("\\s+".toRegex(), " ")?.trim() ?: "Unknown error"

    private fun tryCloseQuietly(session: LlmInferenceSession?) = runCatching {
        session?.cancelGenerateResponseAsync()
        session?.close()
    }

    private fun safeClose(engine: LlmInference?) = runCatching { engine?.close() }

    /** Snapshot used for safe session recreation. */
    private data class Snap(
        val engine: LlmInference,
        val oldSession: LlmInferenceSession,
        val topK: Int,
        val topP: Float,
        val temperature: Float
    )
}
