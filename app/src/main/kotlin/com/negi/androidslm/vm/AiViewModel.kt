/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: AiViewModel.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  ViewModel for managing on-device SLM evaluation sessions.
 *  Handles streaming inference, UI state updates, and safe cancellation.
 *  Integrates directly with [Repository] to perform prompt-based scoring.
 *
 *  Features:
 *   • Coroutine-safe streaming collection and UI flow binding
 *   • Lifecycle-aware cancel/reset handling
 *   • Structured state flows for Compose UI
 *   • Graceful timeout and cancellation reporting
 * =====================================================================
 */

package com.negi.androidslm.vm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.negi.androidslm.slm.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis

/**
 * [AiViewModel]
 * ---------------------------------------------------------------------
 * Coordinates SLM-driven inference requests and streaming evaluation.
 *
 * Responsibilities:
 * - Build final prompt via [Repository.buildPrompt]
 * - Stream partial inference results via [Repository.request]
 * - Update UI state reactively for Compose-based views
 * - Enforce timeout, cancellation, and error safety
 */
class AiViewModel(
    private val repo: Repository,
    private val timeoutMs: Long = 120_000L,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    companion object {
        private const val TAG = "AiViewModel"
    }

    // -----------------------------------------------------------------
    // 🧭 UI State
    // -----------------------------------------------------------------
    /** Represents overall initialization and readiness. */
    data class UiState(
        val isInitializing: Boolean = false,
        val initialized: Boolean = false
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun setInitializing(v: Boolean) = _ui.update { it.copy(isInitializing = v) }
    fun setInitialized(v: Boolean) = _ui.update { it.copy(isInitializing = false, initialized = v) }

    // -----------------------------------------------------------------
    // 🔄 Evaluation / Stream State
    // -----------------------------------------------------------------
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _score = MutableStateFlow<Int?>(null)
    val score: StateFlow<Int?> = _score.asStateFlow()

    private val _stream = MutableStateFlow("")
    val stream: StateFlow<String> = _stream.asStateFlow()

    private val _raw = MutableStateFlow<String?>(null)
    val raw: StateFlow<String?> = _raw.asStateFlow()

    private val _followupQuestion = MutableStateFlow<String?>(null)
    val followupQuestion: StateFlow<String?> = _followupQuestion.asStateFlow()

    private val _followups = MutableStateFlow<List<String>>(emptyList())
    val followups: StateFlow<List<String>> = _followups.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // -----------------------------------------------------------------
    // ⚙️ Control States
    // -----------------------------------------------------------------
    private var evalJob: Job? = null
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    // -----------------------------------------------------------------
    // 🧠 Evaluate (streaming inference)
    // -----------------------------------------------------------------

    /**
     * Performs streaming evaluation for a given prompt.
     *
     * @param prompt The input prompt or answer text.
     * @param timeout Timeout for the entire inference (ms).
     * @return Optional final score (null if unavailable or cancelled).
     */
    suspend fun evaluate(prompt: String, timeout: Long = timeoutMs): Int? {
        if (prompt.isBlank()) {
            Log.i(TAG, "evaluate: blank prompt → reset states")
            resetStates(keepError = false)
            return null
        }

        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "evaluate: already running → returning existing score=${_score.value}")
            return _score.value
        }

        // Initialize UI and internal state
        _loading.value = true
        _score.value = null
        _stream.value = ""
        _raw.value = null
        _followupQuestion.value = null
        _followups.value = emptyList()
        _error.value = null

        val buffer = StringBuilder()
        var chunks = 0
        var totalChars = 0

        val elapsed = measureTimeMillis {
            try {
                evalJob = viewModelScope.launch(ioDispatcher) {
                    var timedOut = false
                    try {
                        withTimeout(timeout) {
                            val finalPrompt = repo.buildPrompt(prompt)
                            repo.request(finalPrompt).collect { part ->
                                if (part.isNotEmpty()) {
                                    chunks++
                                    totalChars += part.length
                                    buffer.append(part)
                                    _stream.update { it + part }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        val looksTimeout = e is TimeoutCancellationException ||
                                e.javaClass.name.contains("Timeout", ignoreCase = true)
                        if (looksTimeout) {
                            timedOut = true
                            Log.w(
                                TAG,
                                "evaluate: timeout after ${timeout}ms (${e::class.simpleName})"
                            )
                        } else {
                            Log.w(TAG, "evaluate: cancelled by user (${e::class.simpleName})")
                            _error.value = "cancelled"
                            throw e
                        }
                    }

                    val rawText = buffer.toString().ifBlank { _stream.value }
                    if (rawText.isNotBlank()) {
                        _raw.value = rawText
                        logSummary(prompt, rawText, chunks, totalChars)
                        parseStructuredOutput(rawText)
                    } else {
                        Log.w(TAG, "evaluate: empty raw output (no data)")
                    }

                    if (timedOut) _error.value = "timeout"
                }

                evalJob?.join()
            } finally {
                running.set(false)
                _loading.value = false
                evalJob?.cancel()
                evalJob = null
            }
        }

        Log.d(
            TAG,
            "evaluate: finished in ${elapsed}ms, score=${_score.value}, chunks=$chunks, chars=$totalChars, err=${_error.value}"
        )
        return _score.value
    }

    // -----------------------------------------------------------------
    // 🚀 Fire-and-forget Wrapper
    // -----------------------------------------------------------------

    /** Launches [evaluate] asynchronously. */
    fun evaluateAsync(prompt: String, timeout: Long = timeoutMs): Job =
        viewModelScope.launch {
            val result = evaluate(prompt, timeout)
            Log.i(TAG, "evaluateAsync: done, score=$result, error=${_error.value}")
        }

    // -----------------------------------------------------------------
    // ⏹ Cancel / Reset
    // -----------------------------------------------------------------

    /** Cancels the current running evaluation if active. */
    fun cancel() {
        Log.i(TAG, "cancel: requested (running=${running.get()}, loading=${_loading.value})")
        try {
            evalJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel: ignored exception", t)
        } finally {
            running.set(false)
            _loading.value = false
        }
    }

    /**
     * Resets all observable state to default values.
     * @param keepError If true, preserves the current error message.
     */
    fun resetStates(keepError: Boolean = false) {
        Log.d(TAG, "resetStates(keepError=$keepError) start")
        cancel()
        _score.value = null
        _stream.value = ""
        _raw.value = null
        _followupQuestion.value = null
        _followups.value = emptyList()
        if (!keepError) _error.value = null
        Log.d(TAG, "resetStates done (score=${_score.value}, err=${_error.value})")
    }

    override fun onCleared() {
        Log.i(TAG, "onCleared: cancelling active job")
        cancel()
        super.onCleared()
    }

    // -----------------------------------------------------------------
    // 🧩 Helper Methods
    // -----------------------------------------------------------------

    /** Safely clamps score to 0–100 range. */
    private fun clampScore(s: Int?): Int? {
        val c = s?.coerceIn(0, 100)
        if (s != c) Log.d(TAG, "clampScore: $s → $c")
        return c
    }

    /** SHA-256 digest utility for stable logs. */
    private fun sha256Hex(text: String): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        md.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrElse { "sha256_error" }

    /** Structured summary log for debugging. */
    private fun logSummary(prompt: String, raw: String, chunks: Int, chars: Int) {
        Log.d(TAG, "---- AI Eval Summary ----")
        Log.d(TAG, "Prompt.len=${prompt.length}, SHA=${sha256Hex(prompt)}")
        Log.d(TAG, "Raw.len=${raw.length}, chunks=$chunks, chars=$chars, SHA=${sha256Hex(raw)}")
        Log.v(TAG, "Prompt:\n$prompt")
        Log.v(TAG, "Raw:\n$raw")
    }

    /**
     * Parses model output JSON-like text to extract score and follow-up fields.
     * Future extension point for structured evaluation logic.
     */
    private fun parseStructuredOutput(raw: String) {
        runCatching {
            val trimmed = raw.trim()
            val json = Regex("\\{.*\\}").find(trimmed)?.value ?: return
            val score =
                Regex("\"score\"\\s*:\\s*(\\d{1,3})").find(json)?.groupValues?.get(1)?.toIntOrNull()
            val follows = Regex("\"follow-up questions\"\\s*:\\s*\\[(.*?)\\]")
                .find(json)?.groupValues?.get(1)?.split(Regex("\\s*,\\s*"))?.map {
                    it.trim('"', ' ')
                } ?: emptyList()

            _score.value = clampScore(score)
            _followups.value = follows
            _followupQuestion.value = follows.firstOrNull()
            Log.d(
                TAG,
                "Parsed structured output: score=${_score.value}, followups=${_followups.value.size}"
            )
        }.onFailure { e ->
            Log.w(TAG, "parseStructuredOutput failed: ${e.message}")
        }
    }
}
