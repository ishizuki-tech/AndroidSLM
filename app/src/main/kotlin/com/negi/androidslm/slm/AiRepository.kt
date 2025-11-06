/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SlmDirectRepository.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Provides a coroutine-based streaming repository that connects directly
 *  to the on-device Small Language Model (SLM). It ensures serialized
 *  access, graceful cleanup, and structured streaming for real-time text
 *  inference responses.
 *
 *  Features:
 *   • Prompt construction with configurable system preambles.
 *   • Flow-based streaming of partial model outputs.
 *   • Auto cleanup via SLM.cancel() and SLM.resetSession().
 *   • Global semaphore to serialize concurrent model access.
 *   • Watchdog timer for idle-finish detection.
 * =====================================================================
 */

package com.negi.androidslm.slm

import android.os.SystemClock
import android.util.Log
import com.negi.androidslm.config.SurveyConfig.SlmMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Contract defining a unified interface for SLM text generation repositories.
 */
interface Repository {
    /**
     * Sends the prompt to the model and returns a [Flow] emitting streaming text fragments.
     *
     * @param prompt The prepared prompt including system preamble and formatting markers.
     * @return [Flow] emitting incremental text fragments until the model finishes.
     */
    suspend fun request(prompt: String): Flow<String>

    /**
     * Builds a system-consistent prompt string from user input.
     *
     * @param userPrompt Raw text from user or survey response.
     * @return A fully structured prompt string ready for SLM inference.
     */
    fun buildPrompt(userPrompt: String): String
}

/**
 * Direct repository implementation communicating with the on-device SLM engine.
 *
 * Manages concurrency, lifecycle, and cleanup around [SLM.runInference].
 *
 * @property model Model configuration instance.
 * @property slm Metadata and prompt templates defined in [SlmMeta].
 */
class SlmDirectRepository(
    private val model: Model,
    private val slm: SlmMeta
) : Repository {

    companion object {
        private const val TAG = "SlmDirectRepository"

        // --- Conversation Markers ---
        private const val USER_TURN_PREFIX = "<start_of_turn>user"
        private const val MODEL_TURN_PREFIX = "<start_of_turn>model"
        private const val TURN_END = "<end_of_turn>"
        private const val EMPTY_JSON_INSTRUCTION = "Respond with an empty JSON object: {}"

        // --- Prompt Preamble / Contracts ---
        private const val PREAMBLE =
            "You are a well-known English survey expert. Read the Question and the Answer."
        private const val KEY_CONTRACT =
            "OUTPUT FORMAT:\n- In English.\n- Keys: \"analysis\", \"expected answer\", \"follow-up questions\" (Exactly 3 in an array), \"score\" (int 1–100)."
        private const val LENGTH_BUDGET =
            "LENGTH LIMITS:\n- analysis<=60 chars; each follow-up<=80; expected answer<=40."
        private const val SCORING_RULE =
            "Scoring rule: Judge ONLY content relevance/completeness/accuracy. Do NOT penalize style or formatting."
        private const val STRICT_OUTPUT =
            "STRICT OUTPUT (NO MARKDOWN):\n- RAW JSON only, ONE LINE.\n- Use COMPACT JSON (no spaces around ':' and ',').\n- No extra text.\n- Entire output<=512 chars."

        // --- Global concurrency gate ---
        private val globalGate = Semaphore(1)

        // --- Cleanup constants ---
        private const val CLEAN_WAIT_MS = 5_000L
        private const val CLEAN_STEP_MS = 500L

        // --- Watchdog constants ---
        private const val FINISH_WATCHDOG_DEFAULT_MS = 3_000L
        private const val FINISH_IDLE_GRACE_DEFAULT_MS = 250L
        private const val FINISH_WATCHDOG_STEP_MS = 100L

        private val FINISH_WATCHDOG_MS: Long by lazy {
            System.getProperty("slm.finish.watchdog.ms")?.toLongOrNull()
                ?: FINISH_WATCHDOG_DEFAULT_MS
        }
        private val FINISH_IDLE_GRACE_MS: Long by lazy {
            System.getProperty("slm.finish.idle.grace.ms")?.toLongOrNull()
                ?: FINISH_IDLE_GRACE_DEFAULT_MS
        }
    }

    // -------------------------------------------------------------------------
    // 🧩 Prompt Builder
    // -------------------------------------------------------------------------

    /**
     * Constructs the full prompt with system-level preamble and constraints.
     *
     * It merges user text with model and scoring metadata, ensuring consistent
     * formatting and termination tokens.
     */
    override fun buildPrompt(userPrompt: String): String {
        val userTurn = slm.user_turn_prefix ?: USER_TURN_PREFIX
        val modelTurn = slm.model_turn_prefix ?: MODEL_TURN_PREFIX
        val turnEnd = slm.turn_end ?: TURN_END
        val emptyJson = slm.empty_json_instruction ?: EMPTY_JSON_INSTRUCTION

        val preamble = slm.preamble ?: PREAMBLE
        val keyContract = slm.key_contract ?: KEY_CONTRACT
        val lengthBudget = slm.length_budget ?: LENGTH_BUDGET
        val scoringRule = slm.scoring_rule ?: SCORING_RULE
        val strictOutput = slm.strict_output ?: STRICT_OUTPUT

        val effective =
            if (userPrompt.isBlank()) emptyJson else userPrompt.trimIndent().normalize()

        val finalPrompt = compactJoin(
            userTurn,
            preamble,
            effective,
            keyContract,
            lengthBudget,
            scoringRule,
            strictOutput,
            turnEnd,
            modelTurn
        )

        Log.d(TAG, "buildPrompt: in.len=${userPrompt.length}, out.len=${finalPrompt.length}")
        return finalPrompt
    }

    // -------------------------------------------------------------------------
    // 🔄 Request Flow
    // -------------------------------------------------------------------------

    /**
     * Streams inference results as they are produced by the SLM.
     *
     * The coroutine-based flow ensures lifecycle safety and proper cleanup
     * even under cancellation or timeout scenarios.
     */
    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun request(prompt: String): Flow<String> = callbackFlow {
        globalGate.withPermit {
            Log.d(TAG, "SLM request start: model='${model.name}', prompt.len=${prompt.length}")

            val anchorScope = CoroutineScope(coroutineContext + SupervisorJob())
            val closed = AtomicBoolean(false)
            val seenFinished = AtomicBoolean(false)
            val seenOnClean = AtomicBoolean(false)

            fun isBusyNow(): Boolean = runCatching { SLM.isBusy(model) }.getOrElse { true }

            fun safeClose(reason: String? = null) {
                if (closed.compareAndSet(false, true)) {
                    reason?.let { Log.d(TAG, "safeClose: $it") }
                    close()
                }
            }

            try {
                SLM.runInference(
                    model = model,
                    input = prompt,
                    listener = { partial, finished ->
                        if (partial.isNotEmpty() && !isClosedForSend) {
                            trySend(partial).onFailure { e ->
                                Log.w(TAG, "trySend failed: ${e?.message}")
                            }
                        }

                        if (finished) {
                            seenFinished.set(true)
                            Log.d(TAG, "SLM inference finished (model='${model.name}')")

                            anchorScope.launch {
                                val deadline = SystemClock.elapsedRealtime() + FINISH_WATCHDOG_MS
                                var idleSince = -1L
                                while (SystemClock.elapsedRealtime() < deadline && !seenOnClean.get()) {
                                    if (closed.get()) return@launch
                                    val busy = isBusyNow()
                                    val now = SystemClock.elapsedRealtime()
                                    if (!busy) {
                                        if (idleSince < 0) idleSince = now
                                        val idleDur = now - idleSince
                                        if (idleDur >= FINISH_IDLE_GRACE_MS) {
                                            Log.d(
                                                TAG,
                                                "finish idle-grace (${idleDur}ms) → safeClose()"
                                            )
                                            safeClose("finished-idle-no-onClean")
                                            return@launch
                                        }
                                    } else idleSince = -1L
                                    delay(FINISH_WATCHDOG_STEP_MS)
                                }
                                if (!seenOnClean.get() && !closed.get()) {
                                    Log.w(TAG, "finish watchdog timeout → safeClose()")
                                    safeClose("watchdog-timeout")
                                }
                            }
                        }
                    },
                    onClean = {
                        seenOnClean.set(true)
                        Log.d(TAG, "SLM onClean (model='${model.name}')")
                        safeClose("onClean")
                    }
                )
            } catch (t: Throwable) {
                Log.e(TAG, "SLM.runInference threw: ${t.message}", t)
                cancel(CancellationException("SLM.runInference threw", t))
            }

            // --- Clean-up after flow collector cancellation ---
            awaitClose {
                anchorScope.cancel(CancellationException("callbackFlow closed"))

                val finished = seenFinished.get()
                val cleaned = seenOnClean.get()

                fun waitCleanOrIdle(tag: String) {
                    val deadline = SystemClock.elapsedRealtime() + CLEAN_WAIT_MS
                    var loops = 0
                    SystemClock.sleep(CLEAN_STEP_MS)
                    while (SystemClock.elapsedRealtime() < deadline) {
                        if (seenOnClean.get() || !isBusyNow()) break
                        SystemClock.sleep(CLEAN_STEP_MS)
                        loops++
                    }
                    Log.d(
                        TAG,
                        "awaitClose[$tag]: done (loops=$loops, cleaned=${seenOnClean.get()}, busy=${isBusyNow()})"
                    )
                }

                when {
                    cleaned -> waitCleanOrIdle("cleaned")
                    isBusyNow() -> {
                        runCatching { SLM.cancel(model) }
                            .onFailure { Log.w(TAG, "cancel() failed: ${it.message}") }
                        waitCleanOrIdle("after-cancel")

                        if (finished && !isBusyNow() && !seenOnClean.get()) {
                            runCatching { SLM.resetSession(model) }
                                .onFailure { Log.w(TAG, "resetSession() failed: ${it.message}") }
                        }
                    }

                    finished -> {
                        runCatching { SLM.resetSession(model) }
                            .onFailure { Log.w(TAG, "resetSession() failed: ${it.message}") }
                    }

                    else -> {
                        if (isBusyNow()) {
                            runCatching { SLM.cancel(model) }
                                .onFailure { Log.w(TAG, "early cancel() failed: ${it.message}") }
                            waitCleanOrIdle("early-cancel")
                        }
                    }
                }
            }
        }
    }.buffer(Channel.BUFFERED)
        .flowOn(Dispatchers.IO)

    // -------------------------------------------------------------------------
    // 🧰 Helper Extensions
    // -------------------------------------------------------------------------

    /** Normalizes line endings and trims trailing newlines. */
    private fun String.normalize(): String =
        this.replace("\r\n", "\n").replace("\r", "\n").trimEnd('\n')

    /** Joins non-blank parts with newline separation. */
    private fun compactJoin(vararg parts: String): String =
        parts.mapNotNull { it.normalize().takeIf { s -> s.isNotBlank() } }
            .joinToString("\n")
}
