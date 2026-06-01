# AGENTS.md — OnDeviceAi

## What this is

Android app (Java + C++17 via JNI) that runs GGUF LLMs on-device using vendored llama.cpp. Single-module Gradle project (`:app`) with a native CMake build.

## Build commands

```bash
# Build debug APK (from repo root)
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Clean
./gradlew clean

# Run unit tests
./gradlew test

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest
```

First build compiles llama.cpp from source via CMake/NDK and is slow (several minutes).

## Critical setup requirement

llama.cpp is **not** included in the repo. You must clone it before building:

```bash
git clone https://github.com/ggerganov/llama.cpp.git app/src/main/cpp/llama.cpp
```

Without it, CMake builds a placeholder JNI library that returns demo strings — no real inference. The native code is conditionally compiled via `HAVE_LLAMA_CPP` define, set automatically when `app/src/main/cpp/llama.cpp/CMakeLists.txt` exists.

## Project structure

```
app/src/main/
├── java/com/droidrocks/ondeviceai/
│   ├── MainActivity.java           # Chat screen
│   ├── ModelListActivity.java      # Model browser & download manager
│   ├── ChatHistoryActivity.java    # Past conversations
│   ├── LlamaBridge.java            # JNI bridge (all native method declarations)
│   ├── ModelUtils.java             # Model download, discovery & formatting
│   ├── RuntimeLog.java             # In-app log view (also called from native via JNI)
│   ├── BaseActivity.java           # Shared activity base class
│   ├── adapter/                    # RecyclerView adapters
│   ├── data/                       # Room entities, DAO, database, repository
│   ├── fragment/                   # Available/Downloaded model fragments
│   └── Utils/                      # CpuMonitor, RamMonitor, GpuInfo
├── cpp/
│   ├── CMakeLists.txt              # Native build config
│   ├── llama_jni.cpp               # JNI implementation (~1700 lines)
│   └── llama.cpp/                  # Vendored llama.cpp (git submodule, NOT in repo)
└── res/                            # Layouts, drawables, values
```

## Key architecture facts

### Native layer (llama_jni.cpp)
- All JNI exports follow `Java_com_droidrocks_ondeviceai_LlamaBridge_*` naming
- Global state: single model (`g_model`), single context (`g_ctx`), mutex-protected
- Context capped at 2048 tokens (hardcoded `MAX_CONTEXT_SIZE`)
- Threads capped at 4 for mobile (`MAX_MOBILE_THREADS`), with ARM big.LITTLE core pinning via `sched_setaffinity`
- Prompt token cache (16K tokens max) avoids re-decoding shared prefixes across calls
- Session ID tracking invalidates cache on model reload/reset
- Streaming batches tokens (4 tokens or 128 bytes or on punctuation) before JNI callback to reduce overhead
- Cooperative cancellation via `g_stop_requested` atomic flag + llama.cpp abort callback

### Vulkan GPU
- **Optional**. Build-time: uncomment `set(GGML_VULKAN ON ...)` in `app/src/main/cpp/CMakeLists.txt` and install LunarG Vulkan SDK on the host machine (provides `glslc.exe` for shader compilation)
- Runtime: Vulkan probe in native code detects GPU availability independently of GGML_VULKAN build flag
- GPU backend enabled asynchronously via `enableGpuBackendAsync()` which reloads the model with device mapping
- The NDK Vulkan headers/library for runtime probing do NOT require the host Vulkan SDK

### Java layer
- `LlamaBridge.java`: static `System.loadLibrary("llama_jni")` with try/catch; `isNativeLoaded()` guards calls
- Room DB (`ChatDatabase`): single database `chat_database`, version 1, `fallbackToDestructiveMigration()`, schema exported to `app/schemas/`
- Package: `com.droidrocks.ondeviceai`

## Build config details

- **AGP**: 9.2.1 (requires Android Studio Ladybug+)
- **Min SDK**: 30 (Android 11), **Target SDK**: 36
- **ABI filter**: `arm64-v8a` only by default (speeds up dev builds; change in `app/build.gradle.kts` for production)
- **Java**: 11 source/target compatibility
- **Version catalog**: `gradle/libs.versions.toml`
- **Room schema export**: configured via `annotationProcessorOptions` in `app/build.gradle.kts`, outputs to `app/schemas/`

## Gotchas

- The `llama.cpp/` subdir is a git-ignored vendored dependency, not a submodule tracked by this repo. If missing, the app still builds and runs but returns placeholder text only
- `local.properties` (contains SDK path) is gitignored — must exist on each dev machine
- Native build uses C++17 (`CMAKE_CXX_STANDARD 17`)
- `RuntimeLog.append()` is called from native threads via JNI — it's a static Java method bridged through cached `JavaVM*`
- Room uses `ChatMessage` entity only (no `ChatSession` table despite the class existing in `data/`)

## Vendored llama.cpp has its own AGENTS.md

The file at `app/src/main/cpp/llama.cpp/AGENTS.md` contains llama.cpp upstream contribution guidelines (AI-generated PR policy, etc.). Read it if modifying the vendored llama.cpp code directly.
