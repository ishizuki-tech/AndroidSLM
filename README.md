<p><a href="index.html" target=_self title="Seperate Page">Go to index</a></p>

# AndroidSLM — On‑device SLM Starter Kit (Compose + Kotlin)

[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.21-7F52FF)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202025.11-4285F4)](https://developer.android.com/jetpack/compose)
[![AGP](https://img.shields.io/badge/AGP-8.13.0-4285F4)](https://developer.android.com/studio/releases/gradle-plugin)
[![Gradle](https://img.shields.io/badge/Gradle-8.13%20~%208.14-02303A)](https://gradle.org/releases/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> **Zero‑cloud, zero‑drama.** AndroidSLM is a minimal yet production‑minded template for **on‑device** small language models. It ships with a **robust downloader**, clean **ViewModel** separation, and a **Compose** UI that flips from **Initialize → AI** when models are ready.

---

## ✨ Highlights

* ⚙️ **Rock‑solid downloads** — HEAD probe, manual redirect handling, ranged resume, exponential backoff (w/ `Retry‑After`), and **SHA‑256 verification** via `HttpUrlFileDownloader`.
* 📶 **Network guard** — Wi‑Fi detection + WorkManager‑friendly constraints.
* 🧠 **Clear layering** — `AppViewModel` for init/download, `AiViewModel` for inference hooks (SLM engines plug here).
* 🧵 **Safe concurrency** — `Mutex`‑based serialized starts, proper cancellation propagation, throttled progress.
* 🔐 **Secure by default** — tokens allowed only in **debug**; **release embeds none**.
* 🎨 **Modern UI** — Compose Material 3, simple primary action that reflects app state.

---

## 🧭 Architecture (at a glance)

```
[ UI (Compose) ]
      |
      v
[ AppViewModel ] --(state/progress)--> UI
      |
      +--> [ HeavyInitializer ]
      |          |
      |          +--> [ HttpUrlFileDownloader ] --(Range/ETag/SHA256)--> [ Model file ]
      |
      +--> [ AiViewModel ] --(your engine)--> [ SLM ]
```

---

## 📦 Tech & Targets

* **Android Studio**: Koala (AI‑252) or newer
* **Kotlin**: 2.2.21
* **AGP**: 8.13.0
* **Gradle**: 8.13 ~ 8.14 (wrapper 8.13+ recommended)
* **SDK**: `compile/target 36`, `min 26`
* **Device**: Android 10+ (≥ 6 GB RAM recommended for on‑device AI)

---

## 🚀 Quickstart

```bash
# 1) Clone
git clone https://github.com/ishizuki-tech/AndroidSLM.git
cd AndroidSLM

# 2) (Optional) unify wrapper for consistency
./gradlew wrapper --gradle-version 8.13.0

# 3) Build & install\./gradlew :app:installDebug
```

**Local dev config (debug only, do not commit):** add to `local.properties`

```properties
gh.owner=ishizuki-tech
gh.repo=AndroidSLM
# gh.token=ghp_xxx   # optional (debug only)
# HF_TOKEN=hf_xxx    # optional (debug only)
```

> Release builds must **not** embed tokens; the app falls back to anonymous/readonly.

**Release signing (optional):**

```bash
export USE_RELEASE_KEYSTORE=true
export KEYSTORE_PASSWORD=***
export KEY_ALIAS=release
export KEY_PASSWORD=***
./gradlew :app:assembleRelease
```

---

## 🔧 Configuration

### App/Model config (YAML example)

```yaml
app:
  requireWifi: true
  firstRunHint: "First run downloads the model"
model:
  url: "https://huggingface.co/<user>/<repo>/resolve/main/model.bin"
  sha256: "<expected_sha256_hex>"
  fileName: "model.bin"
ui:
  theme: "m3"
  primaryActionLabels:
    idle: "Initialize"
    ready: "AI"
```

* Loader: `SurveyConfigLoader` (JSON also supported)
* Place in assets or provide via remote/config at runtime.

### BuildConfig & tokens

* **Debug** may use tokens from `local.properties`.
* **Release** hardcodes empty tokens — **never** ship secrets.

---

## ⬇️ Model handling

* On first run `AppViewModel` triggers download → `HttpUrlFileDownloader` does **HEAD → ranged resume → SHA‑256 verify**.
* Safe resume `.part`/`.meta` sidecars; honors server `ETag`/`Last‑Modified` when present.
* To skip downloads, put models in `app/src/main/assets/models/`.

**Permissions:** `INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE_DATA_SYNC`.

---

## 🛡️ Security checklist

* [x] No secrets in **release** BuildConfig
* [x] SHA‑256 verification of downloaded artifacts
* [x] Explicit network constraints (Wi‑Fi, etc.)
* [x] Distinguish cancellation vs. failure and log accordingly

---

## ⚡ Performance notes

* Keep I/O on `Dispatchers.IO`; avoid blocking the main thread.
* Prefer one active download at a time (already serialized by `Mutex`).
* Gate inference with a simple queue in `AiViewModel` (WIP) to bound memory and latency.
* Track PSS/RSS via `dumpsys meminfo` when testing large models.

---

## 🧪 Testing

* JUnit 5 enabled (`useJUnitPlatform()` globally). Add per‑module:

```kotlin
testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
```

* For instrumented tests, use `androidx.test` stack as usual.

---

## 🧩 Common issues

* **Keystore missing (release)** → set the env vars above or build debug.
* **Unrelated histories (git)** → `git pull --rebase --allow-unrelated-histories`.
* **Deprecated property** → do **not** use `android.defaults.buildfeatures.buildconfig`; use `buildFeatures { buildConfig = true }` in module.
* **IDE quick‑fix (ReplaceWith) not showing** → try Alt+Enter; otherwise manual edit.

---

## 🗺️ Roadmap

* [ ] Inference queue/cancel in `AiViewModel`
* [ ] Cache versioning via `etag/lastModified`
* [ ] Init tracing/metrics and structured logs
* [ ] UI state split (Navigation / Business / UIState)
* [ ] Error dialogs w/ retry & log share

---

## 🤝 Contributing

PRs welcome. Use **KDoc** comments and `ktlint`. Keep CI green and avoid embedding secrets.

---

## 📜 License

**MIT** — see [LICENSE](LICENSE).
