/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: AppViewModel.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  ViewModel responsible for ensuring that the on-device SLM model
 *  exists and is downloaded correctly. Integrates with [HeavyInitializer]
 *  to provide resumable downloads, timeout safety, throttled progress updates,
 *  and cancellation support.
 *
 *  Features:
 *   • Serialized download (Mutex-based) to prevent duplicates
 *   • Wi-Fi-only safeguard for large models
 *   • Progress throttling for smooth Compose UI rendering
 *   • Graceful cancellation and recovery logic
 * =====================================================================
 */

package com.negi.androidslm.vm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.negi.androidslm.BuildConfig
import com.negi.androidslm.utils.HeavyInitializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URI

/**
 * ViewModel responsible for ensuring that the model file
 * is downloaded and cached locally before inference starts.
 *
 * Handles concurrency, retries, cancellation, and state reporting.
 */
class AppViewModel(
    val modelUrl: String = DEFAULT_MODEL_URL,
    private val fileName: String = DEFAULT_FILE_NAME,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val uiThrottleMs: Long = DEFAULT_UI_THROTTLE_MS,
    private val uiMinDeltaBytes: Long = DEFAULT_UI_MIN_DELTA_BYTES
) : ViewModel() {

    companion object {
        /** Default model source (Google Gemma small variant). */
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm"

        /** Default filename (stored in app internal storage). */
        private const val DEFAULT_FILE_NAME = "model.litertlm"

        /** Maximum allowed initialization time (30 minutes). */
        private const val DEFAULT_TIMEOUT_MS = 30L * 60 * 1000

        /** Minimum interval between UI updates in milliseconds. */
        private const val DEFAULT_UI_THROTTLE_MS = 250L

        /** Minimum byte delta between progress updates. */
        private const val DEFAULT_UI_MIN_DELTA_BYTES = 1L * 1024L * 1024L

        /**
         * Simple default factory.
         */
        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel() as T
        }

        /**
         * Configurable ViewModel factory for dynamic initialization.
         */
        fun factory(
            url: String = DEFAULT_MODEL_URL,
            fileName: String = DEFAULT_FILE_NAME,
            timeoutMs: Long = DEFAULT_TIMEOUT_MS,
            uiThrottleMs: Long = DEFAULT_UI_THROTTLE_MS,
            uiMinDeltaBytes: Long = DEFAULT_UI_MIN_DELTA_BYTES
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(url, fileName, timeoutMs, uiThrottleMs, uiMinDeltaBytes) as T
        }
    }

    // =================================================================
    // 🔄 Download State Management
    // =================================================================

    /**
     * Represents high-level download state for the model.
     */
    sealed class DlState {
        /** Idle — no active download in progress. */
        data object Idle : DlState()

        /** Actively downloading, with optional total size. */
        data class Downloading(val downloaded: Long, val total: Long?) : DlState()

        /** Download completed successfully. */
        data class Done(val file: File) : DlState()

        /** Failure (network, timeout, or storage issue). */
        data class Error(val message: String) : DlState()

        /** Cancelled manually or due to app shutdown. */
        data object Canceled : DlState()
    }

    private val _state = MutableStateFlow<DlState>(DlState.Idle)
    val state: StateFlow<DlState> = _state.asStateFlow()

    private var currentJob: Job? = null
    private val startMutex = Mutex()

    // =================================================================
    // ⚙️ Public API
    // =================================================================

    /**
     * Ensures that the model is downloaded and ready for use.
     * Serialized via [startMutex] to prevent duplicate starts.
     *
     * @param appContext Android application context
     * @param forceRedownload Whether to delete and re-download even if a file exists
     */
    fun ensureModelDownloaded(appContext: Context, forceRedownload: Boolean = false) {
        val app = appContext.applicationContext
        val fileName = suggestFileName(modelUrl)
        val finalFile = File(app.filesDir, fileName)

        viewModelScope.launch(Dispatchers.IO) {
            startMutex.withLock {
                val st = _state.value
                if (!forceRedownload && (st is DlState.Downloading || st is DlState.Done)) return@withLock

                // Fast path: already present and non-empty
                if (!forceRedownload && finalFile.exists() && finalFile.length() > 0L) {
                    _state.value = DlState.Done(finalFile)
                    return@withLock
                }

                // Force mode → cancel, purge, and restart
                if (forceRedownload) {
                    currentJob?.cancelAndJoin(); currentJob = null
                    runCatching { HeavyInitializer.cancel() }
                    HeavyInitializer.resetForDebug()
                    purgeModelFiles(app, fileName)
                } else if (currentJob != null) {
                    // Prevent accidental parallel download
                    return@withLock
                }

                currentJob = launchDownloadJob(app, fileName, forceFresh = forceRedownload)
            }
        }
    }

    /**
     * Wi-Fi-only variant of [ensureModelDownloaded].
     * Returns `false` immediately if not connected to Wi-Fi.
     */
    fun ensureModelDownloadedWifiOnly(
        appContext: Context,
        forceRedownload: Boolean = false
    ): Boolean {
        val ctx = appContext.applicationContext
        return if (isWifiConnected(ctx)) {
            ensureModelDownloaded(ctx, forceRedownload)
            true
        } else {
            _state.value = DlState.Error("wifi_required")
            false
        }
    }

    /**
     * Cancels current download if active, including any in-flight
     * operation inside [HeavyInitializer].
     */
    fun cancelDownload() {
        viewModelScope.launch(Dispatchers.IO) {
            startMutex.withLock {
                currentJob?.cancel()
                currentJob = null
                runCatching { HeavyInitializer.cancel() }
                _state.value = DlState.Canceled
            }
        }
    }

    /**
     * Resets to idle state, clearing all transient status.
     */
    fun reset() {
        viewModelScope.launch(Dispatchers.IO) {
            startMutex.withLock {
                currentJob?.cancel()
                currentJob = null
                _state.value = DlState.Idle
            }
        }
    }

    override fun onCleared() {
        currentJob?.cancel()
        currentJob = null
        super.onCleared()
    }

    // =================================================================
    // ✅ Ready Checks (no network)
    // =================================================================

    /**
     * Checks whether the model file is already complete and valid.
     */
    fun verifyModelReady(appContext: Context): Boolean =
        HeavyInitializer.isAlreadyComplete(
            context = appContext.applicationContext,
            modelUrl = modelUrl,
            hfToken = BuildConfig.HF_TOKEN.takeIf { it.isNotBlank() },
            fileName = suggestFileName(modelUrl)
        )

    /** Returns true if model file exists locally with non-zero size. */
    fun isModelCachedLocally(appContext: Context): Boolean {
        val f = File(appContext.filesDir, suggestFileName(modelUrl))
        return f.exists() && f.length() > 0L
    }

    /**
     * If local model is already available, publishes [DlState.Done].
     */
    fun publishDoneIfLocalPresent(appContext: Context): Boolean {
        val f = File(appContext.filesDir, suggestFileName(modelUrl))
        return if (f.exists() && f.length() > 0L) {
            _state.value = DlState.Done(f)
            true
        } else false
    }

    // =================================================================
    // 📦 File Access Utilities
    // =================================================================

    /** Returns expected local file path for current [modelUrl]. */
    fun expectedLocalFile(appContext: Context): File =
        File(appContext.filesDir, suggestFileName(modelUrl))

    /** Returns the downloaded file if available. */
    fun downloadedFileOrNull(): File? = (state.value as? DlState.Done)?.file

    /** Emits the file when available, otherwise null. */
    val downloadedFileFlow = state.map { (it as? DlState.Done)?.file }.distinctUntilChanged()

    /**
     * Suspends until download completes or fails.
     * @return File if successful, or null on failure/cancel.
     */
    suspend fun awaitReadyFile(appContext: Context, forceRedownload: Boolean = false): File? {
        if (publishDoneIfLocalPresent(appContext))
            return (state.value as? DlState.Done)?.file

        if (forceRedownload || state.value !is DlState.Downloading)
            ensureModelDownloaded(appContext, forceRedownload)

        val terminal =
            state.first { it is DlState.Done || it is DlState.Error || it is DlState.Canceled }
        return (terminal as? DlState.Done)?.file
    }

    // =================================================================
    // 🧠 Internal Helpers
    // =================================================================

    /** Launches a new download coroutine with progress throttling. */
    private fun launchDownloadJob(app: Context, fileName: String, forceFresh: Boolean): Job =
        viewModelScope.launch(Dispatchers.IO) {
            val myJob = coroutineContext[Job]
            var lastUiEmitNs = 0L
            var lastUiBytes = 0L

            try {
                _state.value = DlState.Downloading(0L, null)

                val result = HeavyInitializer.ensureInitialized(
                    context = app,
                    modelUrl = modelUrl,
                    hfToken = BuildConfig.HF_TOKEN.takeIf { it.isNotBlank() },
                    fileName = fileName,
                    timeoutMs = timeoutMs,
                    forceFresh = forceFresh,
                    onProgress = { got, total ->
                        if (myJob?.isActive != true)
                            throw CancellationException("canceled before progress emit")

                        val now = System.nanoTime()
                        val elapsedMs = (now - lastUiEmitNs) / 1_000_000
                        val deltaBytes = got - lastUiBytes

                        val shouldEmit = elapsedMs >= uiThrottleMs ||
                                deltaBytes >= uiMinDeltaBytes ||
                                (total != null && got >= total)

                        if (shouldEmit) {
                            lastUiEmitNs = now
                            lastUiBytes = got
                            _state.value = DlState.Downloading(got, total)
                        }
                    }
                )

                result.fold(
                    onSuccess = { file -> _state.value = DlState.Done(file) },
                    onFailure = { e ->
                        _state.value = DlState.Error(e.message ?: "download failed")
                    }
                )
            } catch (ce: CancellationException) {
                _state.value = DlState.Canceled
                throw ce
            } catch (t: Throwable) {
                _state.value = DlState.Error(t.message ?: "download failed")
            } finally {
                startMutex.withLock { if (currentJob === this) currentJob = null }
            }
        }

    /** Checks whether device is currently connected via Wi-Fi. */
    private fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Derives a safe filename from the given URL. */
    private fun suggestFileName(url: String): String =
        runCatching {
            val uri = URI(url)
            (uri.path ?: "").substringAfterLast('/').ifBlank { fileName }
        }.getOrElse { fileName }

    /** Deletes all remnants (final + temp + partial files). */
    private fun purgeModelFiles(context: Context, finalName: String) {
        val dir = context.filesDir
        dir.listFiles()?.forEach { f ->
            val n = f.name
            if (n == finalName ||
                n == "$finalName.tmp" ||
                n == "$finalName.part" ||
                (n.startsWith(finalName) && (n.endsWith(".tmp") || n.endsWith(".part") || n.endsWith(
                    ".partial"
                )))
            ) {
                runCatching { f.delete() }
            }
        }
    }
}
