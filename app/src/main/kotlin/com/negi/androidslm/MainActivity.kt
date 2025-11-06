/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: MainActivity.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Entry point for the AndroidSLM application.
 *  Manages YAML-driven configuration, model download lifecycle,
 *  and SLM inference workflow via Compose UI.
 *
 *  Features:
 *   • Model downloader with progress and force mode
 *   • On-device LLM initialization with YAML parameters
 *   • Streaming AI inference + structured result rendering
 *   • Reactive ViewModel-based state control
 * =====================================================================
 */

package com.negi.androidslm

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.negi.androidslm.config.SurveyConfig
import com.negi.androidslm.config.SurveyConfigLoader
import com.negi.androidslm.slm.ConfigKey
import com.negi.androidslm.slm.Model
import com.negi.androidslm.slm.SLM
import com.negi.androidslm.slm.SlmDirectRepository
import com.negi.androidslm.ui.theme.AndroidSLMTheme
import com.negi.androidslm.vm.AiViewModel
import com.negi.androidslm.vm.AppViewModel
import com.negi.androidslm.vm.AppViewModel.DlState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main entry point for AndroidSLM.
 * Loads YAML configuration and orchestrates downloader → inference phases.
 */
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Load YAML configuration (fallback to defaults if missing)
        val config = runCatching {
            SurveyConfigLoader.fromAssets(this, "slm_config.yaml").also { it.validateOrThrow() }
        }.onFailure { e ->
            Log.e("Config", "⚠️ Failed to load config: ${e.message}")
        }.getOrDefault(SurveyConfig())

        // Create AppViewModel for model management
        val md = config.modelDefaultsOrFallback()
        val factory = AppViewModel.factory(
            url = md.defaultModelUrl,
            fileName = md.defaultFileName,
            timeoutMs = md.timeoutMs,
            uiThrottleMs = md.uiThrottleMs,
            uiMinDeltaBytes = md.uiMinDeltaBytes
        )
        val appVm = ViewModelProvider(this, factory)[AppViewModel::class.java]

        setContent {
            AndroidSLMTheme {
                var showDownloader by remember { mutableStateOf(true) }

                Scaffold(
                    topBar = { TopAppBar(title = { Text("AndroidSLM — AI Model Runner") }) }
                ) { padding ->
                    Crossfade(targetState = showDownloader, label = "phase") { show ->
                        if (show) {
                            DownloadScreen(padding, appVm) { showDownloader = false }
                        } else {
                            AiInferenceScreen(padding, appVm, config.slm)
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// 📦 Downloader UI
// =====================================================================

/**
 * Displays model download screen and transitions to AI initialization.
 */
@Composable
private fun DownloadScreen(
    padding: PaddingValues,
    vm: AppViewModel,
    onDone: () -> Unit
) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsState()

    val expectedFile = remember { vm.expectedLocalFile(ctx) }
    var existsNow by remember { mutableStateOf(expectedFile.exists()) }

    // Auto-detect pre-downloaded model on first launch
    LaunchedEffect(Unit) {
        if (vm.publishDoneIfLocalPresent(ctx)) {
            existsNow = true
            Log.i("Downloader", "✅ Local model detected — using cached file")
        }
    }

    Column(
        Modifier
            .padding(padding)
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("📦 Model Downloader", style = MaterialTheme.typography.headlineSmall)
        Text(vm.modelUrl, style = MaterialTheme.typography.bodySmall)

        // Expected path info card
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Expected Path", style = MaterialTheme.typography.labelSmall)
                Text(expectedFile.absolutePath, style = MaterialTheme.typography.bodySmall)
                val existsLabel = if (existsNow) "exists ✅" else "missing ⚠️"
                val color =
                    if (existsNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                Text(
                    "Local Status: $existsLabel",
                    color = color,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // --- State Block ---
        Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val s = state) {
                    is DlState.Idle -> {
                        Text("Idle — waiting", style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    is DlState.Downloading -> {
                        val pct = s.total?.let { (s.downloaded.toFloat() / it).coerceIn(0f, 1f) }
                        Text("Downloading...", style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(
                            progress = { pct ?: 0f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${((pct ?: 0f) * 100).toInt()}%  (${prettyBytes(s.downloaded)} / ${
                                s.total?.let {
                                    prettyBytes(
                                        it
                                    )
                                } ?: "?"
                            })",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    is DlState.Done -> {
                        Text(
                            "✅ Done",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleSmall
                        )
                        InfoRow("File", s.file.absolutePath)
                        InfoRow("Size", prettyBytes(s.file.length()))
                    }

                    is DlState.Error -> {
                        Text("❌ Error", color = MaterialTheme.colorScheme.error)
                        Text(
                            s.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    is DlState.Canceled -> {
                        Text("⏹️ Canceled", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // --- Action Buttons ---
        FlowButtons {
            when (state) {
                is DlState.Done -> {
                    Button(onClick = onDone) { Text("Initialize → AI") }
                    OutlinedButton(onClick = {
                        vm.reset(); existsNow = expectedFile.exists()
                    }) { Text("Reset") }
                    OutlinedButton(onClick = {
                        val found = vm.publishDoneIfLocalPresent(ctx)
                        existsNow = expectedFile.exists()
                        Toast.makeText(
                            ctx,
                            if (found) "Local model found" else "No local file",
                            Toast.LENGTH_SHORT
                        ).show()
                    }) { Text("Check Local") }
                }

                is DlState.Downloading -> {
                    OutlinedButton(onClick = { vm.cancelDownload() }) { Text("Cancel") }
                }

                else -> {
                    Button(onClick = {
                        vm.ensureModelDownloaded(ctx, false)
                        existsNow = expectedFile.exists()
                    }) { Text("Download") }

                    OutlinedButton(onClick = {
                        vm.ensureModelDownloaded(ctx, true)
                        existsNow = expectedFile.exists()
                    }) { Text("Force Download") }

                    OutlinedButton(onClick = {
                        val found = vm.publishDoneIfLocalPresent(ctx)
                        existsNow = expectedFile.exists()
                        Toast.makeText(
                            ctx,
                            if (found) "Local model found" else "No local file",
                            Toast.LENGTH_SHORT
                        ).show()
                    }) { Text("Check Local") }
                }
            }
        }
    }
}

// =====================================================================
// 🧠 AI Inference Screen
// =====================================================================

/**
 * Handles SLM initialization and evaluation lifecycle.
 */
@Composable
private fun AiInferenceScreen(
    padding: PaddingValues,
    appVm: AppViewModel,
    slmMeta: SurveyConfig.SlmMeta
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val modelFile = remember { appVm.expectedLocalFile(ctx) }
    val modelPath = modelFile.absolutePath

    val slmModel = remember(modelPath, slmMeta) {
        Model(
            name = modelFile.name,
            taskPath = modelPath,
            config = mapOf(
                ConfigKey.ACCELERATOR to (slmMeta.accelerator ?: "GPU"),
                ConfigKey.MAX_TOKENS to (slmMeta.maxTokens ?: 4096),
                ConfigKey.TOP_K to (slmMeta.topK ?: 10),
                ConfigKey.TOP_P to (slmMeta.topP ?: 0.2),
                ConfigKey.TEMPERATURE to (slmMeta.temperature ?: 0.5)
            )
        )
    }

    val repo = remember(slmModel, slmMeta) { SlmDirectRepository(slmModel, slmMeta) }

    val aiVm: AiViewModel = viewModel(
        key = "AiViewModel",
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AiViewModel(repo) as T
        }
    )

    // Collect state flows
    val stream by aiVm.stream.collectAsState()
    val score by aiVm.score.collectAsState()
    val followups by aiVm.followups.collectAsState()
    val loading by aiVm.loading.collectAsState()
    val error by aiVm.error.collectAsState()
    val ui by aiVm.ui.collectAsState()

    var prompt by remember { mutableStateOf("") }

    // Initialize SLM once
    LaunchedEffect(modelPath) {
        if (!ui.initialized && !ui.isInitializing) {
            aiVm.setInitializing(true)
            withContext(Dispatchers.IO) {
                SLM.initialize(ctx.applicationContext, slmModel) { msg ->
                    scope.launch {
                        if (msg.isEmpty()) {
                            Toast.makeText(ctx, "SLM initialized", Toast.LENGTH_SHORT).show()
                            aiVm.setInitialized(true)
                        } else {
                            Toast.makeText(ctx, "Init failed: $msg", Toast.LENGTH_LONG).show()
                            aiVm.setInitialized(false)
                        }
                    }
                }
            }
        }
    }

    if (ui.isInitializing) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Loading model...")
            }
        }
    } else {
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("🧠 On-device AI Inference", style = MaterialTheme.typography.headlineSmall)

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Enter a prompt") },
                modifier = Modifier.fillMaxWidth()
            )

            FlowButtons {
                Button(
                    onClick = { aiVm.evaluateAsync(prompt) },
                    enabled = prompt.isNotBlank() && !loading
                ) {
                    Text(if (loading) "Running..." else "Evaluate")
                }
                OutlinedButton(onClick = { aiVm.cancel() }) { Text("Cancel") }
                OutlinedButton(onClick = { aiVm.resetStates() }) { Text("Reset") }
            }

            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            if (stream.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("📡 Streaming Output", style = MaterialTheme.typography.titleSmall)
                Text(stream, style = MaterialTheme.typography.bodyMedium)
            }

            score?.let {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("🎯 Score", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(progress = { it / 100f })
                Text("$it / 100", style = MaterialTheme.typography.labelSmall)
            }

            if (followups.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("💬 Follow-up Questions", style = MaterialTheme.typography.titleSmall)
                followups.forEach { q ->
                    Card(
                        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("• $q", Modifier.padding(8.dp))
                    }
                }
            }

            error?.let {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("⚠️ Error: $it", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// =====================================================================
// 🔧 Common UI Helpers
// =====================================================================

@Composable
private fun FlowButtons(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) { content() }
}

@Composable
private fun InfoRow(key: String, value: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(bottom = 4.dp)
    ) {
        Text(
            key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Human-readable byte formatting. */
private fun prettyBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val kb = n / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}
