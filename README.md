# OnDeviceAi

An Android application that runs large language models (LLMs) **entirely on-device** using [llama.cpp](https://github.com/ggerganov/llama.cpp) via JNI. No cloud APIs, no internet required for inference — your conversations stay private on your phone.

## Features

- **On-device inference** — runs GGUF-format LLMs locally through a native C++ bridge (`llama.cpp`)
- **Streaming token output** — AI responses appear token-by-token in real time with a typing cursor
- **Model management** — browse, download, and switch between multiple models from a built-in catalog (Hugging Face)
- **Chat history** — persistent conversation storage powered by Room database with session management
- **Vulkan GPU acceleration** — optional GPU compute backend for supported devices (requires LunarG Vulkan SDK for shader compilation)
- **System monitoring** — live CPU usage, RAM (app / device / available), and GPU info displayed as chips
- **Generation control** — stop in-progress generation at any time; generation timer tracks elapsed time
- **Conversation context** — maintains recent conversation turns to provide context to the model
- **Multiple model support** — automatically detects `.gguf`, `.ggml`, `.bin`, `.pt`, `.pth`, and `.safetensors` files

## Screenshots

<!-- Add screenshots here -->

## Architecture

```
app/
├── src/main/
│   ├── java/com/droidrocks/ondeviceai/
│   │   ├── MainActivity.java          # Main chat screen
│   │   ├── ModelListActivity.java     # Model browser & download manager
│   │   ├── ChatHistoryActivity.java   # Past conversation sessions
│   │   ├── LlamaBridge.java           # JNI bridge to llama.cpp native code
│   │   ├── ModelUtils.java            # Model download, discovery & formatting
│   │   ├── RuntimeLog.java            # In-app runtime log view
│   │   ├── BaseActivity.java          # Shared activity base class
│   │   ├── adapter/                   # RecyclerView adapters (chat, models, history)
│   │   ├── data/                      # Room entities, DAO, database & repository
│   │   ├── fragment/                  # Available / Downloaded model fragments
│   │   └── Utils/                     # CpuMonitor, RamMonitor, GpuInfo
│   ├── cpp/
│   │   ├── CMakeLists.txt             # Native build config
│   │   ├── llama_jni.cpp              # JNI implementation
│   │   └── llama.cpp/                 # Vendored llama.cpp library (git submodule)
│   └── res/                           # Layouts, drawables, values
```

## Requirements

| Requirement | Details |
|---|---|
| **Android Studio** | Ladybug or newer (AGP 9.x) |
| **Min SDK** | 30 (Android 11) |
| **Target SDK** | 36 |
| **Java** | 11 |
| **NDK / CMake** | Required for native C++ build |
| **Vulkan SDK** *(optional)* | [LunarG Vulkan SDK](https://vulkan.lunarg.com/sdk/home#windows) for GPU shader compilation |

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/<your-username>/OnDeviceAi.git
cd OnDeviceAi
```

### 2. Vendor llama.cpp

The native inference engine depends on llama.cpp sources under `app/src/main/cpp/llama.cpp/`. If they are not already present:

```bash
git clone https://github.com/ggerganov/llama.cpp.git app/src/main/cpp/llama.cpp
```

### 3. Build & Run

1. Open the project in **Android Studio**.
2. Let Gradle sync and download dependencies.
3. Connect a physical device or start an emulator (ARM64 recommended).
4. Click **Run ▶**.

> **Note:** The first build compiles llama.cpp from source via CMake, which may take several minutes.

### 4. Download a model

On first launch, the app redirects you to the **Model List** screen where you can pick from pre-configured models optimized for mobile:

| Category | Example | Size |
|---|---|---|
| Ultra-compact | SmolLM2 360M Instruct (Q8) | ~380 MB |
| Compact | TinyLlama 1.1B Chat | ~670 MB |
| Mid-range | Gemma / Phi-3 Mini | 1–2 GB |

Models are downloaded from Hugging Face and stored in the app's external files directory (`<app>/models/`).

### New Chat button & native session reset

The app provides a New Chat action that clears the UI conversation and resets the native model session/context so the KV cache and any cached prompt tokens are discarded.

- UI: a `New Chat` button is placed next to the Models button in `activity_main.xml` with id `@id/btnNewChat` and a small indeterminate `ProgressBar` with id `@id/progressResetSession` is shown while the native reset runs.
- Native: the Java bridge calls a JNI method `LlamaBridge.resetSession()` which must be implemented in the native library. The Java declaration is present in `app/src/main/java/com/droidrocks/ondeviceai/LlamaBridge.java`.

How it behaves:
- Tap the `New Chat` button — the UI conversation clears immediately (new session id) so the user sees instant feedback.
- A small spinner appears and the button is disabled while the native side runs `resetSession()` in a background thread.
- When the native reset completes the spinner hides and a toast indicates success or failure.

Developer notes:
- Ensure your native `llama_jni` library exposes the JNI symbol `Java_com_droidrocks_ondeviceai_LlamaBridge_resetSession` (signature: `JNIEXPORT jboolean JNICALL ...`) so the Java bridge can call it. If the symbol is missing the app will throw UnsatisfiedLinkError when invoking the reset.
- If you prefer a confirmation dialog before clearing a conversation, modify `MainActivity` to show an AlertDialog before calling `startNewChat()` and `resetSession()`.

### 5. (Optional) Enable Vulkan GPU acceleration

1. Install the [LunarG Vulkan SDK](https://vulkan.lunarg.com/sdk/home#windows) on your build machine.
2. Uncomment the `GGML_VULKAN` line in `app/src/main/cpp/CMakeLists.txt`:
   ```cmake
   set(GGML_VULKAN ON CACHE BOOL "Enable ggml Vulkan backend" FORCE)
   ```
3. Rebuild the project. The app will automatically detect and use the Vulkan backend at runtime.

## Tech Stack

- **Language:** Java (Android) + C++17 (native)
- **UI:** Android Views, RecyclerView, Material Components
- **Database:** Room (SQLite) for chat history persistence
- **Native:** llama.cpp via JNI (CMake / NDK)
- **GPU:** Vulkan (optional)
- **Build:** Gradle with Version Catalogs (`libs.versions.toml`)

## Key Dependencies

| Library | Version |
|---|---|
| AndroidX AppCompat | 1.7.1 |
| Material Components | 1.13.0 |
| AndroidX Activity | 1.13.0 |
| ConstraintLayout | 2.2.1 |
| Room | 2.6.1 |
| llama.cpp | vendored (latest) |

## License

<!-- Add your license here -->

## Contributing

Contributions are welcome! Please open an issue or submit a pull request.

