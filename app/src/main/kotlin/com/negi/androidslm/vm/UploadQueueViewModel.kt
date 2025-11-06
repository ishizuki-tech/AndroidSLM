/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: UploadQueueViewModel.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Observes and manages asynchronous file uploads using WorkManager.
 *  Transforms [WorkInfo] states into UI-friendly data flow for Compose.
 *
 *  Features:
 *   • Reactive Flow of current uploads with progress updates
 *   • Deduplicated emissions via distinctUntilChanged()
 *   • Safe reading from WorkManager progress and outputData
 *   • Clean factory for Compose integration
 * =====================================================================
 */

package com.negi.androidslm.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asFlow
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.negi.androidslm.net.GitHubUploadWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Represents a single upload entry in the UI list.
 *
 * @property id Unique ID of the WorkManager task.
 * @property fileName Name of the file being uploaded.
 * @property percent Upload completion percentage (null if unknown).
 * @property state Current [WorkInfo.State] of the task.
 * @property fileUrl Resulting file URL after successful upload.
 * @property message Human-readable status message for UI display.
 */
data class UploadItemUi(
    val id: String,
    val fileName: String,
    val percent: Int?,
    val state: WorkInfo.State,
    val fileUrl: String?,
    val message: String? = null
)

/**
 * [UploadQueueViewModel]
 * ---------------------------------------------------------------------
 * Observes file upload tasks scheduled via WorkManager.
 * Converts [WorkInfo] entries into user-facing [UploadItemUi] states.
 *
 * Exposes a reactive [Flow] suitable for Compose UI observation.
 */
class UploadQueueViewModel(app: Application) : AndroidViewModel(app) {

    private val wm = WorkManager.getInstance(app)

    /**
     * Emits the current upload list as a [Flow], sorted by state priority.
     *
     * - Uses LiveData→Flow bridge for reliable updates
     * - Deduplicates via [distinctUntilChanged] to prevent UI flicker
     */
    val itemsFlow: Flow<List<UploadItemUi>> =
        wm.getWorkInfosByTagLiveData(GitHubUploadWorker.TAG)
            .asFlow()
            .map { list ->
                list.map { wi ->
                    // Safely extract progress and metadata
                    val pct: Int? = runCatching {
                        wi.progress.getInt(GitHubUploadWorker.PROGRESS_PCT, -1)
                            .takeIf { it >= 0 }
                    }.getOrNull()

                    val name: String = runCatching {
                        wi.progress.getString(GitHubUploadWorker.PROGRESS_FILE)
                            ?: wi.outputData.getString(GitHubUploadWorker.OUT_FILE_NAME)
                            ?: wi.tags.firstOrNull {
                                it.startsWith("${GitHubUploadWorker.TAG}:file:")
                            }?.substringAfter(":file:")
                            ?: "upload.json"
                    }.getOrDefault("upload.json")

                    val url: String? = runCatching {
                        wi.outputData.getString(GitHubUploadWorker.OUT_FILE_URL)
                            ?.takeIf { it.isNotBlank() }
                    }.getOrNull()

                    val msg = when (wi.state) {
                        WorkInfo.State.ENQUEUED -> "Waiting for network..."
                        WorkInfo.State.RUNNING -> "Uploading..."
                        WorkInfo.State.SUCCEEDED -> "Upload completed"
                        WorkInfo.State.FAILED -> "Upload failed"
                        WorkInfo.State.BLOCKED -> "Pending (blocked)"
                        WorkInfo.State.CANCELLED -> "Cancelled"
                    }

                    UploadItemUi(
                        id = wi.id.toString(),
                        fileName = name,
                        percent = pct,
                        state = wi.state,
                        fileUrl = url,
                        message = msg
                    )
                }.sortedWith(
                    // Sort by logical priority: running > queued > done > failed/cancelled
                    compareBy<UploadItemUi> {
                        when (it.state) {
                            WorkInfo.State.RUNNING -> 0
                            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> 1
                            WorkInfo.State.SUCCEEDED -> 2
                            else -> 3
                        }
                    }.thenBy { it.fileName }
                )
            }
            // Prevent unnecessary recomposition when the data is identical
            .distinctUntilChanged { old, new ->
                if (old.size != new.size) return@distinctUntilChanged false
                old.zip(new).all { (a, b) ->
                    a.id == b.id &&
                            a.state == b.state &&
                            (a.percent ?: -1) == (b.percent ?: -1) &&
                            a.fileUrl == b.fileUrl
                }
            }

    companion object {
        /**
         * Factory for creating [UploadQueueViewModel] in Compose environments.
         */
        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    UploadQueueViewModel(app) as T
            }
    }
}
