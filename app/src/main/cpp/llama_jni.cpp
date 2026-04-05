#// JNI skeleton for integrating llama.cpp
#include <jni.h>
#include <string>
#include <mutex>
#include <atomic>
#include <android/log.h>
#include <vector>
#include <ctime>
// detect number of hardware threads
#include <thread>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <stdarg.h>
// performance core detection & thread affinity
#include <sched.h>
#include <unistd.h>
#include <fstream>

static const char *TAG = "llama_jni";
// Cache JavaVM so native code can call back into Java (RuntimeLog.append)
static JavaVM *g_jvm = nullptr;

// Helper to append a message into the in-app AI log (RuntimeLog.append)
static void jni_append_runtime_log(const char *message) {
    if (!g_jvm || !message) return;
    JNIEnv *env = nullptr;
    bool attached = false;
    // Try to get JNIEnv for this thread; attach if needed
    if (g_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }

    // Find the RuntimeLog class and call RuntimeLog.append(String)
    jclass cls = env->FindClass("com/droidrocks/ondeviceai/RuntimeLog");
    if (cls) {
        jmethodID mid = env->GetStaticMethodID(cls, "append", "(Ljava/lang/String;)V");
        if (mid) {
            jstring jstr = env->NewStringUTF(message);
            env->CallStaticVoidMethod(cls, mid, jstr);
            env->DeleteLocalRef(jstr);
        }
        env->DeleteLocalRef(cls);
    }

    if (attached) g_jvm->DetachCurrentThread();
}

// Generic logger that forwards to Android log and the in-app RuntimeLog
static void LogMsg(int prio, const char *tag, const char *fmt, ...) {
    char buf[2048];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);

    // Android log
    __android_log_print(prio, tag, "%s", buf);

    // Also send to in-app runtime log (prefix with tag)
    char outbuf[2112];
    snprintf(outbuf, sizeof(outbuf), "%s: %s", tag ? tag : "", buf);
    jni_append_runtime_log(outbuf);
}

#define LOGI(...) LogMsg(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) LogMsg(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Cache JavaVM on load so native threads can call back into Java
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    (void) vm; (void) reserved;
    g_jvm = nullptr;
}

static std::mutex g_mutex;

#ifdef HAVE_LLAMA_CPP
// ====== REAL LLAMA.CPP INTEGRATION (assumes recent upstream APIs) ======
// This implementation assumes the llama.cpp C API with functions such as:
//   llama_context *llama_init_from_file(const char *path, const struct llama_context_params *params);
//   void llama_free(llama_context *ctx);
//   int llama_eval(llama_context *ctx, const llama_token *tokens, int n_tokens, int n_past, int n_threads);
//   size_t llama_tokenize(llama_context *ctx, const char *text, llama_token *tokens, size_t n_max_tokens, bool add_bos);
//   const char *llama_token_to_str(const llama_context *ctx, llama_token token);
//   const float *llama_get_logits(llama_context *ctx);
//   int llama_n_vocab(llama_context *ctx);
// If upstream API differs, adjust names accordingly.

#include "llama.h"

// Vulkan probe (optional; used to detect whether Vulkan compute is available
// on the device and to report device name / memory). This probe only
// enumerates devices and inspects properties; it does not create logical
// devices or perform any GPU work.
#include <vulkan/vulkan.h>

static std::atomic<bool> g_vulkan_available(false);
static std::string g_vulkan_device_name;
static uint32_t g_vulkan_vendor_id = 0;
static uint64_t g_vulkan_device_local_mem = 0; // bytes

static ggml_backend_t g_vk_backend = nullptr;

static bool try_init_vulkan() {
    VkResult r;
    VkInstance instance = VK_NULL_HANDLE;

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "OnDeviceAiProbe";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "ProbeEngine";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    r = vkCreateInstance(&createInfo, nullptr, &instance);
    if (r != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        LOGI("[llama_jni] Vulkan instance creation failed (vkCreateInstance=%d)", (int) r);
        return false;
    }

    uint32_t count = 0;
    r = vkEnumeratePhysicalDevices(instance, &count, nullptr);
    if (r != VK_SUCCESS || count == 0) {
        LOGI("[llama_jni] No Vulkan physical devices found (vkEnumeratePhysicalDevices=%d)", (int) r);
        vkDestroyInstance(instance, nullptr);
        return false;
    }

    std::vector<VkPhysicalDevice> devices(count);
    r = vkEnumeratePhysicalDevices(instance, &count, devices.data());
    if (r != VK_SUCCESS) {
        LOGI("[llama_jni] vkEnumeratePhysicalDevices failed second call=%d", (int) r);
        vkDestroyInstance(instance, nullptr);
        return false;
    }

    for (uint32_t i = 0; i < count; ++i) {
        VkPhysicalDevice dev = devices[i];
        VkPhysicalDeviceProperties props;
        vkGetPhysicalDeviceProperties(dev, &props);

        VkPhysicalDeviceMemoryProperties memProps;
        vkGetPhysicalDeviceMemoryProperties(dev, &memProps);

        uint64_t deviceLocalBytes = 0;
        for (uint32_t h = 0; h < memProps.memoryHeapCount; ++h) {
            if (memProps.memoryHeaps[h].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) {
                deviceLocalBytes += memProps.memoryHeaps[h].size;
            }
        }

        uint32_t qCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &qCount, nullptr);
        if (qCount == 0) continue;
        std::vector<VkQueueFamilyProperties> qprops(qCount);
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &qCount, qprops.data());
        bool hasCompute = false;
        for (uint32_t q = 0; q < qCount; ++q) {
            if (qprops[q].queueFlags & VK_QUEUE_COMPUTE_BIT) { hasCompute = true; break; }
        }
        if (!hasCompute) continue;

        g_vulkan_device_name = props.deviceName;
        g_vulkan_vendor_id = props.vendorID;
        g_vulkan_device_local_mem = deviceLocalBytes;
        g_vulkan_available.store(true);

        LOGI("[llama_jni] Vulkan device selected: %s vendor=0x%08x local_mem=%llu bytes",
             g_vulkan_device_name.c_str(), (unsigned int) g_vulkan_vendor_id, (unsigned long long) g_vulkan_device_local_mem);

        break;
    }

    vkDestroyInstance(instance, nullptr);
    return g_vulkan_available.load();
}

static struct llama_model * g_model = nullptr;
static struct llama_context * g_ctx = nullptr;
static int g_n_threads = 1;
// cooperative cancellation flag - set by interruptGeneration() JNI call
static std::atomic<bool> g_stop_requested(false);

// Detect ARM big.LITTLE performance (big) cores by reading cpufreq max frequency.
// Returns the number of big cores found, and sets the current thread's CPU affinity
// to only those cores so llama.cpp threads stay on the fast cores.
static int detect_and_pin_perf_cores() {
    unsigned int hw = std::thread::hardware_concurrency();
    if (hw == 0) hw = 4;

    // Read max frequency for each core
    std::vector<std::pair<int, long>> core_freqs; // (core_id, max_freq_khz)
    for (unsigned int i = 0; i < hw; ++i) {
        char path[128];
        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%u/cpufreq/cpuinfo_max_freq", i);
        std::ifstream f(path);
        long freq = 0;
        if (f.is_open()) { f >> freq; }
        core_freqs.push_back({(int)i, freq});
    }

    // Find the maximum frequency among all cores
    long max_freq = 0;
    for (auto &cf : core_freqs) {
        if (cf.second > max_freq) max_freq = cf.second;
    }

    // Big cores are those within 80% of the maximum frequency
    // (handles 3-tier designs: prime > big > little)
    long big_threshold = (long)(max_freq * 0.75);
    std::vector<int> big_cores;
    for (auto &cf : core_freqs) {
        if (cf.second >= big_threshold && cf.second > 0) {
            big_cores.push_back(cf.first);
        }
    }

    // If detection failed or all cores look the same, use up to 4 cores
    if (big_cores.empty() || big_cores.size() == hw) {
        unsigned int pick = std::min(hw, (unsigned int)4);
        return (int)pick;
    }

    // Pin current thread (and child threads) to big cores only
    cpu_set_t mask;
    CPU_ZERO(&mask);
    for (int c : big_cores) {
        CPU_SET(c, &mask);
    }
    sched_setaffinity(0, sizeof(mask), &mask);

    return (int)big_cores.size();
}

// Simple fallback thread count detection.
static int detect_default_n_threads() {
    unsigned int hw = std::thread::hardware_concurrency();
    if (hw == 0) hw = 4;
    const unsigned int MAX_THREADS = 8;
    unsigned int pick = std::min(hw, MAX_THREADS);
    return (int) pick;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_loadModel(
        JNIEnv *env,
        jobject /* thiz */,
        jstring modelPath,
        jint contextSize,
        jint threads) {

    // convert Java string to std::string
    const char *pathChars = env->GetStringUTFChars(modelPath, nullptr);
    std::string path = pathChars ? pathChars : "";
    env->ReleaseStringUTFChars(modelPath, pathChars);

    LOGI("[llama_jni] === LOAD MODEL START ===");
    LOGI("[llama_jni] Model path: %s", path.c_str());
    LOGI("[llama_jni] Context size: %d, Threads: %d", (int) contextSize, (int) threads);

    if (g_ctx) {
        LOGI("[llama_jni] Releasing previous model instance...");
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }

    // clear any previous stop request
    g_stop_requested.store(false);

    // ── Step 1: Probe Vulkan BEFORE loading the model so GPU can be used ──
    if (!g_vulkan_available.load()) {
        LOGI("[llama_jni] Probing Vulkan GPU availability...");
        if (try_init_vulkan()) {
            LOGI("[llama_jni] Vulkan GPU available: %s", g_vulkan_device_name.c_str());
            LOGI("[llama_jni] Vulkan VRAM: %llu MB",
                 (unsigned long long)(g_vulkan_device_local_mem / (1024 * 1024)));
        } else {
            LOGI("[llama_jni] Vulkan not available, using CPU-only mode");
        }
    }

    // ── Step 2: Load ggml backends (Vulkan, etc.) ──
    LOGI("[llama_jni] Loading ggml backends...");
    ggml_backend_load_all();
    LOGI("[llama_jni] ggml backends loaded");

    // ── Step 3: Prepare model params with GPU offloading ──
    struct llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true;    // memory-map the model for faster loading

    // Log all available ggml devices for diagnostics
    {
        size_t dev_count = ggml_backend_dev_count();
        LOGI("[llama_jni] ggml devices found: %zu", dev_count);
        for (size_t i = 0; i < dev_count; ++i) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            const char * dname = ggml_backend_dev_name(dev);
            ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
            const char * rname = reg ? ggml_backend_reg_name(reg) : "null";
            int dtype = (int)ggml_backend_dev_type(dev);
            LOGI("[llama_jni]   device[%zu]: name=%s reg=%s type=%d (0=CPU,1=GPU)", i, dname ? dname : "?", rname ? rname : "?", dtype);
        }
    }

    // Select GPU device if available (any GPU, not just "vulkan"-named)
    ggml_backend_dev_t * devs_alloc = nullptr;
    {
        size_t dev_count = ggml_backend_dev_count();
        ggml_backend_dev_t chosen = nullptr;
        for (size_t i = 0; i < dev_count; ++i) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_GPU) {
                chosen = dev;
                const char * dname = ggml_backend_dev_name(dev);
                LOGI("[llama_jni] Selected GPU device: %s", dname ? dname : "unknown");
                break;
            }
        }
        if (chosen) {
            devs_alloc = (ggml_backend_dev_t *) malloc(sizeof(ggml_backend_dev_t) * 2);
            if (devs_alloc) {
                devs_alloc[0] = chosen;
                devs_alloc[1] = nullptr;
                mparams.devices = devs_alloc;
            }
        } else {
            LOGI("[llama_jni] No GPU device found — using CPU only");
        }
    }

    // ── Step 4: Load model file ──
    g_model = llama_model_load_from_file(path.c_str(), mparams);

    if (devs_alloc) { free(devs_alloc); devs_alloc = nullptr; mparams.devices = nullptr; }

    if (!g_model) {
        LOGE("[llama_jni] ERROR: Failed to load model file!");
        LOGE("[llama_jni] Path: %s", path.c_str());
        return JNI_FALSE;
    }
    LOGI("[llama_jni] Model file loaded successfully");

    // ── Step 5: Configure context for maximum speed ──
    struct llama_context_params cparams = llama_context_default_params();
    if (contextSize > 0) cparams.n_ctx = (uint32_t) contextSize;

    // Cap context size — smaller = faster KV cache operations
    const uint32_t MAX_CONTEXT_SIZE = 2048;
    if (cparams.n_ctx > MAX_CONTEXT_SIZE) {
        LOGI("[llama_jni] Capping context from %d to %d", cparams.n_ctx, MAX_CONTEXT_SIZE);
        cparams.n_ctx = MAX_CONTEXT_SIZE;
    }

    // Thread count — prefer pinning to performance (big) cores
    if ((int) threads > 0) {
        g_n_threads = (int) threads;
        // Still try to pin to big cores even with explicit thread count
        int big = detect_and_pin_perf_cores();
        if (big > 0 && big < g_n_threads) {
            LOGI("[llama_jni] Pinned to %d perf cores (requested %d threads, capping)", big, g_n_threads);
            g_n_threads = big;
        }
    } else {
        g_n_threads = detect_and_pin_perf_cores();
    }
    LOGI("[llama_jni] Threads: %d (pinned to perf cores, CPU hw_concurrency: %u)",
         g_n_threads, std::thread::hardware_concurrency());

    cparams.n_threads       = g_n_threads;
    cparams.n_threads_batch = g_n_threads;
    cparams.n_batch  = 512;   // larger batch = faster prompt processing
    cparams.n_ubatch = 512;

    // ── KEY SPEED OPTIMIZATIONS ──

    // Flash Attention: dramatically faster attention computation
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;

    // Quantize KV cache: Q8_0 halves memory bandwidth vs F16, ~same quality
    cparams.type_k = GGML_TYPE_Q8_0;
    cparams.type_v = GGML_TYPE_Q8_0;

    // Offload KQV ops (attention) to GPU when available
    cparams.offload_kqv = true;
    // Offload other tensor ops to device
    cparams.op_offload = true;

    // Use abort callback for instant cancellation (no polling delay)
    cparams.abort_callback = [](void * /*data*/) -> bool {
        return g_stop_requested.load(std::memory_order_relaxed);
    };
    cparams.abort_callback_data = nullptr;

    LOGI("[llama_jni] Context: n_ctx=%d n_batch=%d flash_attn=ON kv_cache=Q8_0 offload_kqv=ON",
         cparams.n_ctx, cparams.n_batch);

    // ── Step 6: Create context ──
    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("[llama_jni] ERROR: Failed to initialize context!");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }
    LOGI("[llama_jni] Context initialized successfully");

    llama_set_n_threads(g_ctx, g_n_threads, g_n_threads);
    llama_set_warmup(g_ctx, true);


    LOGI("[llama_jni] === MODEL READY ===");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_interruptGeneration(
        JNIEnv *env,
        jobject /* thiz */) {
    // set the cooperative cancellation flag - generation loop will check this
    g_stop_requested.store(true);
    LOGI("[llama_jni] interruptGeneration called: stop requested");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_isVulkanAvailable(
        JNIEnv *env,
        jobject /* thiz */) {
    return g_vulkan_available.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_initVulkanBackend(
        JNIEnv *env,
        jobject /* thiz */) {
    if (!g_vulkan_available.load()) return JNI_FALSE;
    // ensure backends loaded
    ggml_backend_load_all();

    size_t dev_count = ggml_backend_dev_count();
    ggml_backend_dev_t chosen = nullptr;
    for (size_t i = 0; i < dev_count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_GPU) {
            ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
            const char * regname = ggml_backend_reg_name(reg);
            if (regname && strcmp(regname, "vulkan") == 0) {
                chosen = dev;
                break;
            }
        }
    }
    if (!chosen) {
        LOGI("[llama_jni] initVulkanBackend: no ggml Vulkan device found");
        return JNI_FALSE;
    }

    // initialize backend for chosen device
    g_vk_backend = ggml_backend_dev_init(chosen, nullptr);
    if (!g_vk_backend) {
        LOGE("[llama_jni] ggml_backend_dev_init failed for Vulkan device");
        return JNI_FALSE;
    }
    LOGI("[llama_jni] ggml Vulkan backend initialized");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_getVulkanDeviceName(
        JNIEnv *env,
        jobject /* thiz */) {
    if (!g_vulkan_available.load()) return env->NewStringUTF("");
    return env->NewStringUTF(g_vulkan_device_name.c_str());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_getVulkanDeviceLocalMemory(
        JNIEnv *env,
        jobject /* thiz */) {
    return (jlong) g_vulkan_device_local_mem;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_generate(
        JNIEnv *env,
        jobject /* thiz */,
        jstring prompt,
        jint maxTokens,
        jfloat temperature,
        jfloat topP) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_ctx || !g_model) return env->NewStringUTF("Model is not loaded.");

    g_stop_requested.store(false); // clear stale stop state

    const char *promptChars = env->GetStringUTFChars(prompt, nullptr);
    std::string input = promptChars ? promptChars : "";
    env->ReleaseStringUTFChars(prompt, promptChars);

    LOGI("[llama_jni] Generating tokens (max: %d)...", (int) maxTokens);

    // Get vocab
    const struct llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (!vocab) {
        LOGE("[llama_jni] failed to get vocab from model");
        return env->NewStringUTF("No vocab available.");
    }

    // Determine whether this is the first sequence (no tokens yet in memory)
    llama_memory_t mem = llama_get_memory(g_ctx);
    llama_pos mem_pos_max = llama_memory_seq_pos_max(mem, 0);
    bool is_first = (mem_pos_max == -1);

    // Tokenize prompt
    const int max_prompt_tokens = 4096;
    std::vector<llama_token> prompt_tokens(max_prompt_tokens);
    int32_t n_prompt = llama_tokenize(vocab, input.c_str(), (int32_t) input.size(), prompt_tokens.data(), (int32_t) prompt_tokens.size(), is_first, false);
    if (n_prompt <= 0) {
        LOGE("[llama_jni] tokenization failed or returned %d", n_prompt);
        return env->NewStringUTF("Tokenization failed.");
    }

    // base position to place the prompt tokens in the context memory
    llama_pos base_pos = is_first ? 0 : mem_pos_max + 1;

    // Evaluate prompt using batch API
    struct llama_batch batch = llama_batch_init(n_prompt, 0, 1);
    if (!batch.token) {
        LOGE("[llama_jni] failed to init batch for prompt");
        return env->NewStringUTF("Batch init failed.");
    }
    for (int i = 0; i < n_prompt; ++i) {
        batch.token[i]   = prompt_tokens[i];
        batch.logits[i]  = (i == n_prompt - 1) ? 1 : 0;
    }
    batch.n_tokens = n_prompt;

    // initialize the per-token metadata
    if (batch.pos) {
        for (int i = 0; i < n_prompt; ++i) {
            batch.pos[i] = base_pos + i;
        }
    }
    if (batch.n_seq_id) {
        for (int i = 0; i < n_prompt; ++i) {
            batch.n_seq_id[i] = 1;
        }
    }
    if (batch.seq_id) {
        for (int i = 0; i < n_prompt; ++i) {
            if (batch.seq_id[i]) {
                batch.seq_id[i][0] = 0;
            }
        }
    }

    if (g_stop_requested.load()) {
        llama_batch_free(batch);
        return env->NewStringUTF("[Generation cancelled]");
    }

    int64_t t_start = llama_time_us();
    int32_t res = llama_decode(g_ctx, batch);
    int64_t t_prompt_done = llama_time_us();
    llama_batch_free(batch);

    if (res != 0) {
        LOGE("[llama_jni] llama_decode returned %d", res);
        if (res == 1) LOGE("[llama_jni] could not find KV slot (try reducing context)");
        return env->NewStringUTF("Evaluation failed.");
    }

    std::string output;
    output.reserve(maxTokens * 8); // pre-allocate for speed

    // Build sampler chain (optimized order for speed)
    struct llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.0f) {
        // Greedy sampling - fastest method (deterministic, no RNG)
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        // Temperature + advanced sampling for quality/speed balance
        if (topP < 1.0f) {
            // OPTIMIZATION: Use min-p instead of top-p for faster convergence
            // Min-p filters tokens more aggressively = faster sampling
            float min_p = 1.0f - topP;  // Convert top-p to min-p threshold
            llama_sampler_chain_add(chain, llama_sampler_init_min_p(std::max(0.01f, min_p), 1));
        }
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist((uint32_t)time(nullptr)));
    }

    // Pre-allocate single token batch outside loop for reuse
    struct llama_batch one = llama_batch_init(1, 0, 1);
    if (!one.token) {
        llama_sampler_free(chain);
        LOGE("[llama_jni] failed to init single-token batch");
        return env->NewStringUTF("Batch init failed.");
    }

    int tokens_generated = 0;

    // Generation loop
    for (int t = 0; t < maxTokens; ++t) {
        if (g_stop_requested.load(std::memory_order_relaxed)) break;

        llama_token id = llama_sampler_sample(chain, g_ctx, -1);
        if (id == LLAMA_TOKEN_NULL) break;

        // Convert token to text piece
        char piece_buf[128];
        int32_t piece_len = llama_token_to_piece(vocab, id, piece_buf, sizeof(piece_buf) - 1, 0, true);
        if (piece_len > 0) {
            piece_buf[piece_len] = '\0';
            output.append(piece_buf, piece_len);
        }
        tokens_generated++;

        if (llama_vocab_is_eog(vocab, id)) break;

        // Reuse the pre-allocated batch
        one.token[0] = id;
        one.logits[0] = 1;
        one.n_tokens = 1;
        if (one.pos) one.pos[0] = base_pos + n_prompt + t;
        if (one.n_seq_id) one.n_seq_id[0] = 1;
        if (one.seq_id && one.seq_id[0]) one.seq_id[0][0] = 0;

        int32_t r = llama_decode(g_ctx, one);
        if (r != 0) {
            LOGE("[llama_jni] decode failed at step %d: %d", t, r);
            break;
        }
    }

    llama_batch_free(one);
    llama_sampler_free(chain);
    g_stop_requested.store(false);

    int64_t t_end = llama_time_us();
    double prompt_ms = (t_prompt_done - t_start) / 1000.0;
    double gen_ms = (t_end - t_prompt_done) / 1000.0;
    double tok_per_sec = (tokens_generated > 0 && gen_ms > 0) ? (tokens_generated * 1000.0 / gen_ms) : 0;
    double prompt_tok_per_sec = (n_prompt > 0 && prompt_ms > 0) ? (n_prompt * 1000.0 / prompt_ms) : 0;

    LOGI("[llama_jni] prompt: %d tokens in %.0f ms (%.1f t/s) | gen: %d tokens in %.0f ms (%.1f t/s)",
         n_prompt, prompt_ms, prompt_tok_per_sec, tokens_generated, gen_ms, tok_per_sec);

    return env->NewStringUTF(output.c_str());
}

// ── Streaming generation: calls Java callback.onToken(String) per token ──
extern "C"
JNIEXPORT jstring JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_generateStreaming(
        JNIEnv *env,
        jobject /* thiz */,
        jstring prompt,
        jint maxTokens,
        jfloat temperature,
        jfloat topP,
        jobject callback) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_ctx || !g_model) return env->NewStringUTF("Model is not loaded.");

    // Always clear stale stop state from any previous interrupted generation.
    // This is critical because early-exit paths (e.g. abort during prompt decode)
    // may leave g_stop_requested == true, which would immediately cancel this call.
    g_stop_requested.store(false);

    LOGI("[llama_jni] === STREAMING GENERATE START ===");

    // Resolve callback.onToken(String) method
    jclass cbClass = callback ? env->GetObjectClass(callback) : nullptr;
    jmethodID onTokenMethod = cbClass ? env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)Z") : nullptr;
    LOGI("[llama_jni] callback resolved: %s", onTokenMethod ? "yes" : "no");

    const char *promptChars = env->GetStringUTFChars(prompt, nullptr);
    std::string input = promptChars ? promptChars : "";
    env->ReleaseStringUTFChars(prompt, promptChars);

    LOGI("[llama_jni] prompt length: %zu chars, maxTokens=%d, temp=%.2f, topP=%.2f",
         input.size(), (int)maxTokens, (float)temperature, (float)topP);

    const struct llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (!vocab) return env->NewStringUTF("No vocab available.");

    llama_memory_t mem = llama_get_memory(g_ctx);
    llama_pos mem_pos_max = llama_memory_seq_pos_max(mem, 0);
    bool is_first = (mem_pos_max == -1);

    const int max_prompt_tokens = 4096;
    std::vector<llama_token> prompt_tokens(max_prompt_tokens);
    int32_t n_prompt = llama_tokenize(vocab, input.c_str(), (int32_t) input.size(),
                                       prompt_tokens.data(), (int32_t) prompt_tokens.size(), is_first, false);
    if (n_prompt <= 0) return env->NewStringUTF("Tokenization failed.");

    llama_pos base_pos = is_first ? 0 : mem_pos_max + 1;

    // Evaluate prompt
    struct llama_batch batch = llama_batch_init(n_prompt, 0, 1);
    if (!batch.token) return env->NewStringUTF("Batch init failed.");
    for (int i = 0; i < n_prompt; ++i) {
        batch.token[i]  = prompt_tokens[i];
        batch.logits[i] = (i == n_prompt - 1) ? 1 : 0;
    }
    batch.n_tokens = n_prompt;
    if (batch.pos)      for (int i = 0; i < n_prompt; ++i) batch.pos[i] = base_pos + i;
    if (batch.n_seq_id) for (int i = 0; i < n_prompt; ++i) batch.n_seq_id[i] = 1;
    if (batch.seq_id)   for (int i = 0; i < n_prompt; ++i) { if (batch.seq_id[i]) batch.seq_id[i][0] = 0; }

    if (g_stop_requested.load()) { llama_batch_free(batch); return env->NewStringUTF("[Cancelled]"); }

    LOGI("[llama_jni] Decoding prompt (%d tokens)... this may take a while", n_prompt);
    int64_t t_start = llama_time_us();
    int32_t res = llama_decode(g_ctx, batch);
    int64_t t_prompt_done = llama_time_us();
    llama_batch_free(batch);
    LOGI("[llama_jni] Prompt decoded in %.1f sec (result=%d)",
         (double)(t_prompt_done - t_start) / 1000000.0, (int)res);

    if (res != 0) return env->NewStringUTF("Evaluation failed.");

    LOGI("[llama_jni] Starting token-by-token streaming...");

    std::string output;
    output.reserve(maxTokens * 8);

    // Sampler chain
    struct llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        if (topP < 1.0f) {
            float min_p = 1.0f - topP;
            llama_sampler_chain_add(chain, llama_sampler_init_min_p(std::max(0.01f, min_p), 1));
        }
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist((uint32_t)time(nullptr)));
    }

    struct llama_batch one = llama_batch_init(1, 0, 1);
    if (!one.token) { llama_sampler_free(chain); return env->NewStringUTF("Batch init failed."); }

    int tokens_generated = 0;

    for (int t = 0; t < maxTokens; ++t) {
        if (g_stop_requested.load(std::memory_order_relaxed)) break;

        llama_token id = llama_sampler_sample(chain, g_ctx, -1);
        if (id == LLAMA_TOKEN_NULL) break;

        char piece_buf[128];
        int32_t piece_len = llama_token_to_piece(vocab, id, piece_buf, sizeof(piece_buf) - 1, 0, true);
        if (piece_len > 0) {
            piece_buf[piece_len] = '\0';
            output.append(piece_buf, piece_len);

            // Log streamed tokens (first few + periodic) without heavy JNI/RuntimeLog overhead
            if (t < 3 || t % 25 == 0) {
                __android_log_print(ANDROID_LOG_INFO, TAG,
                    "[llama_jni] token[%d]: \"%s\" (id=%d)", t, piece_buf, (int)id);
            }

            // Stream token to Java callback
            if (onTokenMethod) {
                jstring jtoken = env->NewStringUTF(piece_buf);
                jboolean cont = env->CallBooleanMethod(callback, onTokenMethod, jtoken);
                env->DeleteLocalRef(jtoken);
                if (!cont) {
                    LOGI("[llama_jni] callback returned false at token %d — stopping", t);
                    break; // callback returned false → stop
                }
            }
        }
        tokens_generated++;

        if (llama_vocab_is_eog(vocab, id)) break;

        one.token[0] = id;
        one.logits[0] = 1;
        one.n_tokens = 1;
        if (one.pos) one.pos[0] = base_pos + n_prompt + t;
        if (one.n_seq_id) one.n_seq_id[0] = 1;
        if (one.seq_id && one.seq_id[0]) one.seq_id[0][0] = 0;

        if (llama_decode(g_ctx, one) != 0) break;
    }

    llama_batch_free(one);
    llama_sampler_free(chain);
    g_stop_requested.store(false);

    int64_t t_end = llama_time_us();
    double prompt_ms = (t_prompt_done - t_start) / 1000.0;
    double gen_ms = (t_end - t_prompt_done) / 1000.0;
    double tok_per_sec = (tokens_generated > 0 && gen_ms > 0) ? (tokens_generated * 1000.0 / gen_ms) : 0;
    double prompt_tok_per_sec = (n_prompt > 0 && prompt_ms > 0) ? (n_prompt * 1000.0 / prompt_ms) : 0;

    LOGI("[llama_jni] STREAM prompt: %d tok %.0fms (%.1f t/s) | gen: %d tok %.0fms (%.1f t/s)",
         n_prompt, prompt_ms, prompt_tok_per_sec, tokens_generated, gen_ms, tok_per_sec);

    return env->NewStringUTF(output.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_releaseModel(
        JNIEnv *env,
        jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
        LOGI("[llama_jni] context released (llama_free)");
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
        LOGI("[llama_jni] model released (llama_model_free)");
    }
}

#else

// No llama.cpp available: keep a helpful placeholder so the app doesn't crash
static bool g_model_loaded = false;

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_loadModel(
        JNIEnv *env,
        jobject /* thiz */,
        jstring modelPath,
        jint /* contextSize */,
        jint /* threads */) {

    const char *pathChars = env->GetStringUTFChars(modelPath, nullptr);
    std::string path = pathChars ? pathChars : "";
    env->ReleaseStringUTFChars(modelPath, pathChars);

    std::lock_guard<std::mutex> lock(g_mutex);
    LOGI("[llama_jni placeholder] loadModel called path=%s - no llama.cpp integrated", path.c_str());

    // Mark model 'loaded' so Java code believes loadModel succeeded; real
    // inference will not work until you enable HAVE_LLAMA_CPP and implement
    // the JNI hooks.
    g_model_loaded = true;
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_generate(
        JNIEnv *env,
        jobject /* thiz */,
        jstring prompt,
        jint /* maxTokens */,
        jfloat /* temperature */,
        jfloat /* topP */) {

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model_loaded) {
        return env->NewStringUTF("Model is not loaded.");
    }

    const char *promptChars = env->GetStringUTFChars(prompt, nullptr);
    std::string input = promptChars ? promptChars : "";
    env->ReleaseStringUTFChars(prompt, promptChars);

    LOGI("[llama_jni placeholder] generate called. Returning placeholder text.");

    std::string output = "[Demo output]\n\nYou entered:\n" + input +
                         "\n\nNOTE: llama.cpp integration is not enabled.\nFollow README instructions to vendor llama.cpp and define HAVE_LLAMA_CPP in CMake to enable real inference.";

    // Log a truncated preview of the placeholder output to help debugging
    { size_t cap = 256; std::string preview = output.size() > cap ? output.substr(0, cap) + "..." : output; LOGI("[llama_jni placeholder] output preview: %s", preview.c_str()); }

    return env->NewStringUTF(output.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_generateStreaming(
        JNIEnv *env,
        jobject /* thiz */,
        jstring prompt,
        jint /* maxTokens */,
        jfloat /* temperature */,
        jfloat /* topP */,
        jobject /* callback */) {
    // Placeholder: just delegate to generate
    return Java_com_droidrocks_ondeviceai_LlamaBridge_generate(env, nullptr, prompt, 0, 0.0f, 0.0f);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_releaseModel(
        JNIEnv *env,
        jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_model_loaded = false;
    LOGI("[llama_jni placeholder] Model released");
}

#endif // HAVE_LLAMA_CPP

