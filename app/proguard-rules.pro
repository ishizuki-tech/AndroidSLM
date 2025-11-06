# ============================================================
# 📘 proguard-rules.pro — Safe Optimized Full Version (Final)
# ------------------------------------------------------------
# ✅ AndroidSLM / Compose + Kotlin + MediaPipe + LLM + JNI
# ✅ Verified: AGP 8.13.0 / Kotlin 2.2.21 / R8 (minifyEnabled=true)
# ============================================================

# ============================================================
# 🧩 Kotlin / Compose / Lifecycle
# ============================================================

# Keep Kotlin metadata & essential attributes (annotations, generics, stack traces)
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, SourceFile, LineNumberTable

# Compose runtime/UI (safe, broad keep to avoid recomposition stripping in release)
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Lifecycle / ViewModel
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# Keep @Composable methods (method names may be referenced in tooling)
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Kotlin stdlib / kotlinx warnings off (harmless if some artifacts absent)
-dontwarn kotlin.**
-dontwarn kotlinx.**

# ============================================================
# ⚙️ Kotlinx Serialization (reflection-less, keep generated serializers)
# ============================================================

# Keep generated serializer classes and companion references
-keep class **$$serializer { *; }
-keepclassmembers class * {
    static ** Companion;
}
# Library API surface (lightweight keep)
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# ============================================================
# 🧰 WorkManager
# ============================================================

# Keep all Workers (instantiated by framework)
-keep class * extends androidx.work.ListenableWorker { *; }
-dontwarn androidx.work.**

# ============================================================
# 🤖 MediaPipe / ML / TensorFlow / ONNX (as used)
# ============================================================

# MediaPipe Tasks / GenAI
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# TFLite (if bundled via dependencies)
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**

# ONNX Runtime JNI (if used)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Google Generative AI client (if present alongside MediaPipe)
-keep class com.google.ai.client.generative.** { *; }
-dontwarn com.google.ai.client.generative.**

# ============================================================
# 🧠 LLM / Whisper / llama.cpp / JNI bridges
# ============================================================

# JNI entry points
-keepclasseswithmembernames class * {
    native <methods>;
}

# Your JNI/Kotlin bridges (adjust package if different)
-keep class com.negi.androidslm.nativelib.** { *; }
-dontwarn com.negi.androidslm.nativelib.**

# Whisper / llama glue (adjust if not used)
-keep class com.negi.androidslm.whisper.** { *; }
-dontwarn com.negi.androidslm.whisper.**

# ggml (dynamic loading names)
-keep class ggml.** { *; }
-dontwarn ggml.**

# ============================================================
# 🌐 Networking / JSON（任意：使っている場合のみ）
# ============================================================

# Retrofit / OkHttp / Gson
# （未使用でも -dontwarn は無害。完全に未使用なら丸ごと削除可）
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# ============================================================
# 📱 App entrypoints / Android framework components
# ============================================================

# Main entry classes（パッケージは appId に合わせる）
-keep class com.negi.androidslm.MainActivity { *; }

# Manifest で宣言されるコンポーネントは安全に保持
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application

# BuildConfig は参照されやすいので保持
-keep class **.BuildConfig { *; }

# ============================================================
# 🧪 AndroidX Test / Compose Preview（任意）
# ============================================================

# Compose Preview providers
-keep class androidx.compose.ui.tooling.preview.PreviewParameterProvider { *; }

# AndroidX Test runner (warnings off)
-dontwarn androidx.test.**
-keep class androidx.test.runner.** { *; }

# ============================================================
# ✅ Notes
# ------------------------------------------------------------
# • 本ファイルは「安全寄り」。未使用ブロックは削ってさらに縮小可。
# • まずはこのまま release ビルド → 正常動作確認後に段階的縮小を推奨。
# ============================================================
