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
#include <cstdarg>
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

// Cache of the last tokenized prompt to avoid re-decoding identical prefixes.
// This helps reduce prompt decoding time when subsequent prompts share prefixes
// or when the same prompt is generated repeatedly.
static std::vector<llama_token> g_last_prompt_tokens;
static std::mutex g_last_prompt_mutex;
// Limit cached tokens to avoid uncontrolled memory growth
static const size_t G_PROMPT_TOKEN_CACHE_LIMIT = 16 * 1024;
// Session/versioning to ensure cached tokens match the active context
static std::atomic<uint64_t> g_session_id{1};
static uint64_t g_cached_session_id = 0; // protected by g_last_prompt_mutex
static size_t g_last_prompt_token_count = 0; // protected by g_last_prompt_mutex
// Store active context capacity so append/checks can validate available slots
static int g_context_n_ctx = 2048;
// chosen batch settings (initialized at load time)
static int g_opt_n_batch = 0;
static int g_opt_n_ubatch = 0;

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
    g_vulkan_available.store(g_vulkan_available.load());
    return g_vulkan_available.load();
}

static struct llama_model * g_model = nullptr;
static struct llama_context * g_ctx = nullptr;
static int g_n_threads = 1;
// cooperative cancellation flag - set by interruptGeneration() JNI call
static std::atomic<bool> g_stop_requested(false);
// Path to currently loaded model file (used for reloads)
static std::string g_model_path;

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
        if (f.is_open()) {
            if (!(f >> freq)) {
                freq = 0;
            }
        }
        core_freqs.emplace_back((int)i, freq);
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
    int rv = sched_setaffinity(0, sizeof(mask), &mask);
    if (rv != 0) {
        int err = errno;
        LOGI("[llama_jni] sched_setaffinity failed: %d (%s)", err, strerror(err));
        // fallback: return count but do not enforce affinity
        return (int)big_cores.size();
    }

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

// Utility: compute length of common prefix of two token vectors
static int common_prefix_tokens(const std::vector<llama_token> &a, const std::vector<llama_token> &b) {
    size_t n = std::min(a.size(), b.size());
    size_t i = 0;
    for (; i < n; ++i) {
        if (a[i] != b[i]) break;
    }
    return (int)i;
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

    // Defer Vulkan probe and heavy backend initialization from the critical
    // load path to reduce time-to-ready. The app can call initVulkanBackend()
    // explicitly later; for now proceed with CPU-only model load which is the
    // lowest-latency path on many Android devices.
    LOGI("[llama_jni] Vulkan/backend probe deferred to initVulkanBackend() to reduce load latency");

    // ── Step 3: Prepare model params with GPU offloading ──
    struct llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true;    // memory-map the model for faster loading

    // CPU-first loadModel: we intentionally avoid probing or selecting GPU
    // devices here to keep model load latency low on Android. GPU/backends
    // may be enabled later via enableGpuBackendAsync() or initVulkanBackend().
    mparams.devices = nullptr; // ensure CPU-only load

    // ── Step 4: Load model file ──
    g_model = llama_model_load_from_file(path.c_str(), mparams);

    // store model path for potential GPU-reload later
    g_model_path = path;

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
    // Cap thread use to avoid excessive parallelism on mobile which can hurt
    // latency due to scheduling and memory bandwidth contention.
    const int MAX_MOBILE_THREADS = 4;
    if (g_n_threads > MAX_MOBILE_THREADS) {
        LOGI("[llama_jni] Capping threads from %d to %d for mobile latency reasons", g_n_threads, MAX_MOBILE_THREADS);
        g_n_threads = MAX_MOBILE_THREADS;
    }
    LOGI("[llama_jni] Threads: %d (pinned to perf cores, CPU hw_concurrency: %u)",
         g_n_threads, std::thread::hardware_concurrency());

    cparams.n_threads       = g_n_threads;
    cparams.n_threads_batch = g_n_threads;

    // Tune batching for Android devices. Very large batch sizes can increase
    // latency for the first token because they force the runtime to assemble
    // huge work units before producing any output. Use conservative values
    // adapted to the detected thread count.
    if (g_n_threads <= 2) {
        cparams.n_batch = 64;
        cparams.n_ubatch = 64;
    } else if (g_n_threads <= 4) {
        cparams.n_batch = 128;
        cparams.n_ubatch = 128;
    } else {
        cparams.n_batch = 256;
        cparams.n_ubatch = 256;
    }
    // store chosen batch settings for later retrieval/adaptive tuning
    g_opt_n_batch = cparams.n_batch;
    g_opt_n_ubatch = cparams.n_ubatch;

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

    // Store active context capacity and bump session id so any cached token
    // lists that belonged to a previous session are invalidated.
    g_context_n_ctx = (int)cparams.n_ctx;
    g_session_id.fetch_add(1);
    {
        std::lock_guard<std::mutex> lock(g_last_prompt_mutex);
        g_last_prompt_tokens.clear();
        g_cached_session_id = g_session_id.load();
        g_last_prompt_token_count = 0;
    }

    LOGI("[llama_jni] === MODEL READY === (n_ctx=%d)", g_context_n_ctx);
    return JNI_TRUE;
}

// Append prompt tokens directly into the active context. Useful for incremental
// prompt feeding from the Java side to avoid re-tokenizing or rebuilding the
// whole prompt in one shot. Returns JNI_TRUE on success.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_appendPromptTokens(
        JNIEnv *env,
        jobject /* thiz */,
        jstring prompt) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_ctx || !g_model) return JNI_FALSE;

    const char *promptChars = env->GetStringUTFChars(prompt, nullptr);
    std::string input = promptChars ? promptChars : "";
    env->ReleaseStringUTFChars(prompt, promptChars);

    const struct llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (!vocab) return JNI_FALSE;

    llama_memory_t mem = llama_get_memory(g_ctx);
    llama_pos mem_pos_max = llama_memory_seq_pos_max(mem, 0);
    bool is_first = (mem_pos_max == -1);

    const int max_prompt_tokens = 4096;
    std::vector<llama_token> prompt_tokens_buf(max_prompt_tokens);
    int32_t n_prompt = llama_tokenize(vocab, input.c_str(), (int32_t) input.size(),
                                       prompt_tokens_buf.data(), (int32_t) prompt_tokens_buf.size(), is_first, false);
    if (n_prompt <= 0) return JNI_FALSE;

    std::vector<llama_token> prompt_tokens(prompt_tokens_buf.begin(), prompt_tokens_buf.begin() + n_prompt);

    llama_pos base_pos = is_first ? 0 : mem_pos_max + 1;

    // Check available context slots
    int used = (mem_pos_max == -1) ? 0 : (int)(mem_pos_max + 1);
    int free_slots = g_context_n_ctx - used;
    if (free_slots <= 0) {
        LOGE("[llama_jni] appendPromptTokens: no free context slots");
        return JNI_FALSE;
    }
    if (n_prompt > free_slots) {
        LOGE("[llama_jni] appendPromptTokens: prompt (%d tokens) exceeds free slots (%d)", n_prompt, free_slots);
        return JNI_FALSE;
    }

    // Chunk large fragments to reduce single-call latency
    const int CHUNK_SIZE = 128;
    int processed = 0;
    while (processed < n_prompt) {
        int this_chunk = std::min(CHUNK_SIZE, n_prompt - processed);
        struct llama_batch batch = llama_batch_init(this_chunk, 0, 1);
        if (!batch.token) return JNI_FALSE;
        for (int i = 0; i < this_chunk; ++i) {
            batch.token[i] = prompt_tokens[processed + i];
            batch.logits[i] = (processed + i == n_prompt - 1) ? 1 : 0;
        }
        batch.n_tokens = this_chunk;
        if (batch.pos) for (int i = 0; i < this_chunk; ++i) batch.pos[i] = base_pos + processed + i;
        if (batch.n_seq_id) for (int i = 0; i < this_chunk; ++i) batch.n_seq_id[i] = 1;
        if (batch.seq_id) for (int i = 0; i < this_chunk; ++i) { if (batch.seq_id[i]) batch.seq_id[i][0] = 0; }

        int32_t res = llama_decode(g_ctx, batch);
        llama_batch_free(batch);
        if (res != 0) {
            LOGE("[llama_jni] appendPromptTokens: llama_decode failed for chunk at %d (res=%d)", processed, res);
            return JNI_FALSE;
        }
        processed += this_chunk;
    }

    // Update cache: append new tokens, bounded
    {
        std::lock_guard<std::mutex> lock(g_last_prompt_mutex);
        if (g_last_prompt_tokens.size() + (size_t)n_prompt > G_PROMPT_TOKEN_CACHE_LIMIT) {
            // keep the tail of tokens that fit
            size_t keep = G_PROMPT_TOKEN_CACHE_LIMIT > (size_t)n_prompt ? G_PROMPT_TOKEN_CACHE_LIMIT - (size_t)n_prompt : 0;
            std::vector<llama_token> newcache;
            if (keep > 0 && g_last_prompt_tokens.size() > keep) {
                newcache.insert(newcache.end(), g_last_prompt_tokens.end() - keep, g_last_prompt_tokens.end());
            }
            newcache.insert(newcache.end(), prompt_tokens.begin(), prompt_tokens.end());
            g_last_prompt_tokens.swap(newcache);
        } else {
            g_last_prompt_tokens.insert(g_last_prompt_tokens.end(), prompt_tokens.begin(), prompt_tokens.end());
        }
        g_last_prompt_token_count = g_last_prompt_tokens.size();
        g_cached_session_id = g_session_id.load();
    }

    return JNI_TRUE;
}

// Pretokenize a prompt and return the token ids as a Java int[] (jintArray).
// Useful for Java-side inspection or incremental streaming where tokens are
// prepared on the Java side before being sent to appendPromptTokens.
extern "C"
JNIEXPORT jintArray JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_pretokenizePrompt(
        JNIEnv *env,
        jobject /* thiz */,
        jstring prompt) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_ctx || !g_model) return nullptr;

    const char *promptChars = env->GetStringUTFChars(prompt, nullptr);
    std::string input = promptChars ? promptChars : "";
    env->ReleaseStringUTFChars(prompt, promptChars);

    const struct llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (!vocab) return nullptr;

    const int max_prompt_tokens = 4096;
    std::vector<llama_token> prompt_tokens_buf(max_prompt_tokens);
    int32_t n_prompt = llama_tokenize(vocab, input.c_str(), (int32_t) input.size(),
                                       prompt_tokens_buf.data(), (int32_t) prompt_tokens_buf.size(), true, false);
    if (n_prompt <= 0) return nullptr;

    jintArray out = env->NewIntArray(n_prompt);
    if (!out) return nullptr;
    std::vector<jint> tmp(n_prompt);
    for (int i = 0; i < n_prompt; ++i) tmp[i] = (jint)prompt_tokens_buf[i];
    env->SetIntArrayRegion(out, 0, n_prompt, tmp.data());
    return out;
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
    // Try to detect Vulkan at the Vulkan API level first (may set g_vulkan_available)
    if (!g_vulkan_available.load()) {
        LOGI("[llama_jni] initVulkanBackend: probing Vulkan via try_init_vulkan()...");
        try_init_vulkan();
        if (!g_vulkan_available.load()) {
            LOGI("[llama_jni] initVulkanBackend: try_init_vulkan did not find a usable Vulkan device");
            // We'll still attempt to load ggml backends and see if any GPU backend (vulkan) is available
        }
    }

    // Ensure ggml backends are loaded so we can enumerate available device backends
    ggml_backend_load_all();

    size_t dev_count = ggml_backend_dev_count();
    ggml_backend_dev_t chosen = nullptr;
    for (size_t i = 0; i < dev_count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_GPU) {
            ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
            const char * regname = reg ? ggml_backend_reg_name(reg) : nullptr;
            if (regname && strcmp(regname, "vulkan") == 0) {
                chosen = dev;
                break;
            }
            // fallback to any GPU backend if Vulkan wasn't found yet
            if (!chosen) chosen = dev;
        }
    }

    if (!chosen) {
        LOGI("[llama_jni] initVulkanBackend: no ggml GPU backend found");
        return JNI_FALSE;
    }

    // initialize backend for chosen device
    g_vk_backend = ggml_backend_dev_init(chosen, nullptr);
    if (!g_vk_backend) {
        LOGE("[llama_jni] ggml_backend_dev_init failed for chosen GPU device");
        return JNI_FALSE;
    }

    // Mark Vulkan available if chosen backend is Vulkan (and Vulkan API probe succeeded earlier)
    ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(chosen);
    const char * regname = reg ? ggml_backend_reg_name(reg) : nullptr;
    if (regname && strcmp(regname, "vulkan") == 0) {
        // try_init_vulkan may have already filled device properties; if not, attempt quickly
        if (!g_vulkan_available.load()) try_init_vulkan();
    }

    LOGI("[llama_jni] ggml GPU backend initialized: reg=%s", regname ? regname : "unknown");
    return JNI_TRUE;
}

// Return GPU status enum: 0=DISABLED, 1=AVAILABLE (but not enabled), 2=ENABLED
extern "C"
JNIEXPORT jint JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_isGpuEnabled(
        JNIEnv *env,
        jobject /* thiz */) {
    if (g_vk_backend != nullptr) return 2;
    // if Vulkan API reported available or ggml has GPU backends loaded, mark available
    if (g_vulkan_available.load()) return 1;
    // try to see if ggml backends expose GPUs (non-blocking)
    size_t dev_count = ggml_backend_dev_count();
    for (size_t i = 0; i < dev_count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_GPU) return 1;
    }
    return 0;
}

// Return optimal batch settings chosen at load time as an int[2] {n_batch, n_ubatch}
extern "C"
JNIEXPORT jintArray JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_getOptimalBatchSettings(
        JNIEnv *env,
        jobject /* thiz */) {
    jintArray out = env->NewIntArray(2);
    if (!out) return nullptr;
    jint tmp[2]; tmp[0] = (jint) g_opt_n_batch; tmp[1] = (jint) g_opt_n_ubatch;
    env->SetIntArrayRegion(out, 0, 2, tmp);
    return out;
}

// Asynchronously enable GPU backend by loading ggml backends, selecting a GPU,
// reloading the model with device mapping, creating a GPU-enabled context and
// atomically swapping it in. Returns JNI_TRUE if the background task started.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_enableGpuBackendAsync(
        JNIEnv *env,
        jobject /* thiz */,
        jlong freeBytes,
        jobject callback) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || g_model_path.empty()) {
        LOGE("[llama_jni] enableGpuBackendAsync: no model loaded");
        return JNI_FALSE;
    }

    // Low-memory guard: if freeBytes is provided and below threshold, skip
    const jlong MIN_FREE_BYTES = (jlong)(150 * 1024 * 1024); // 150MB heuristic
    if (freeBytes > 0 && freeBytes < MIN_FREE_BYTES) {
        LOGI("[llama_jni] enableGpuBackendAsync: skipping GPU reload due to low free memory: %lld bytes", (long long) freeBytes);
        return JNI_FALSE;
    }

    // Launch background thread to perform the heavy work
    // Create a global ref to callback if provided (we'll call methods on it)
    jobject g_callback = nullptr;
    if (callback) {
        g_callback = env->NewGlobalRef(callback);
    }

    std::thread([path = g_model_path, g_callback]() {
        LOGI("[llama_jni] GPU reload: background init started");

        auto call_progress = [&](int pct) {
            if (!g_callback) return;
            JavaVM *jvm = g_jvm; JNIEnv *env = nullptr;
            if (jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                jclass cbCls = env->GetObjectClass(g_callback);
                if (cbCls) {
                    jmethodID onProgress = env->GetMethodID(cbCls, "onProgress", "(I)V");
                    if (onProgress) env->CallVoidMethod(g_callback, onProgress, (jint)pct);
                }
                jvm->DetachCurrentThread();
            }
        };

        // Snapshot cached prompt tokens so we can replay them into new context
        std::vector<llama_token> cached_tokens;
        {
            std::lock_guard<std::mutex> lock(g_last_prompt_mutex);
            cached_tokens = g_last_prompt_tokens;
        }
        if (cached_tokens.size() > (size_t)g_context_n_ctx) {
            // keep only last n_ctx tokens
            cached_tokens.erase(cached_tokens.begin(), cached_tokens.end() - g_context_n_ctx);
        }

        // Load backends (may be slow)
        ggml_backend_load_all();
        call_progress(20);

        // Find a GPU device (prefer vulkan)
        size_t dev_count = ggml_backend_dev_count();
        ggml_backend_dev_t chosen = nullptr;
        for (size_t i = 0; i < dev_count; ++i) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_GPU) {
                ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
                const char * regname = reg ? ggml_backend_reg_name(reg) : nullptr;
                if (regname && strcmp(regname, "vulkan") == 0) { chosen = dev; break; }
                if (!chosen) chosen = dev; // fallback to any GPU
            }
        }

        if (!chosen) {
            LOGI("[llama_jni] GPU reload: no GPU devices available");
            // notify callback if present
            if (g_callback) {
                JavaVM *jvm = g_jvm;
                JNIEnv *env = nullptr;
                if (jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                    jclass cbCls = env->GetObjectClass(g_callback);
                    if (cbCls) {
                        jmethodID onComplete = env->GetMethodID(cbCls, "onComplete", "(Z)V");
                        if (onComplete) env->CallVoidMethod(g_callback, onComplete, JNI_FALSE);
                    }
                    env->DeleteGlobalRef(g_callback);
                    jvm->DetachCurrentThread();
                }
            }
            return;
        }

        // Allocate device array for model load
        ggml_backend_dev_t * devs_alloc = (ggml_backend_dev_t *) malloc(sizeof(ggml_backend_dev_t) * 2);
        if (!devs_alloc) {
            LOGE("[llama_jni] GPU reload: failed to allocate devs array");
            return;
        }
        devs_alloc[0] = chosen;
        devs_alloc[1] = nullptr;

        // Prepare model load params to attach device mapping
        struct llama_model_params mparams = llama_model_default_params();
        mparams.use_mmap = true;
        mparams.devices = devs_alloc;

        LOGI("[llama_jni] GPU reload: loading model with GPU device mapping (this may be slow)");
        call_progress(50);
        struct llama_model * new_model = llama_model_load_from_file(path.c_str(), mparams);
        if (!new_model) {
            LOGE("[llama_jni] GPU reload: failed to reload model with GPU mapping");
            free(devs_alloc);
            if (g_callback) {
                JavaVM *jvm = g_jvm;
                JNIEnv *env = nullptr;
                if (jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                    jclass cbCls = env->GetObjectClass(g_callback);
                    if (cbCls) {
                        jmethodID onComplete = env->GetMethodID(cbCls, "onComplete", "(Z)V");
                        if (onComplete) env->CallVoidMethod(g_callback, onComplete, JNI_FALSE);
                    }
                    env->DeleteGlobalRef(g_callback);
                    jvm->DetachCurrentThread();
                }
            }
            return;
        }

        // Create context params with offload enabled
        struct llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = (uint32_t) g_context_n_ctx;
        // threading: reuse existing recommendation
        int threads = g_n_threads > 0 ? g_n_threads : detect_default_n_threads();
        cparams.n_threads = threads;
        cparams.n_threads_batch = threads;
        if (threads <= 2) { cparams.n_batch = 64; cparams.n_ubatch = 64; }
        else if (threads <= 4) { cparams.n_batch = 128; cparams.n_ubatch = 128; }
        else { cparams.n_batch = 256; cparams.n_ubatch = 256; }
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
        cparams.type_k = GGML_TYPE_Q8_0;
        cparams.type_v = GGML_TYPE_Q8_0;
        cparams.offload_kqv = true;
        cparams.op_offload = true;
        cparams.abort_callback = [](void * /*data*/) -> bool { return g_stop_requested.load(std::memory_order_relaxed); };

        LOGI("[llama_jni] GPU reload: creating GPU-enabled context (this may be slow)");
        call_progress(80);
        struct llama_context * new_ctx = llama_init_from_model(new_model, cparams);
        if (!new_ctx) {
            LOGE("[llama_jni] GPU reload: failed to create context with GPU offload");
            llama_model_free(new_model);
            free(devs_alloc);
            if (g_callback) {
                JavaVM *jvm = g_jvm; JNIEnv *env = nullptr;
                if (jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                    jclass cbCls = env->GetObjectClass(g_callback);
                    if (cbCls) {
                        jmethodID onComplete = env->GetMethodID(cbCls, "onComplete", "(Z)V");
                        if (onComplete) env->CallVoidMethod(g_callback, onComplete, JNI_FALSE);
                    }
                    env->DeleteGlobalRef(g_callback);
                    jvm->DetachCurrentThread();
                }
            }
            return;
        }

        // initialize backend for chosen device (store handle)
        ggml_backend_t new_vk_backend = ggml_backend_dev_init(chosen, nullptr);

        // Replay cached tokens into the new context to preserve session state
        if (!cached_tokens.empty()) {
            LOGI("[llama_jni] GPU reload: replaying %zu cached tokens into new context", cached_tokens.size());
            int processed = 0;
            const int CHUNK = 128;
            while (processed < (int)cached_tokens.size()) {
                int this_chunk = std::min(CHUNK, (int)cached_tokens.size() - processed);
                struct llama_batch batch = llama_batch_init(this_chunk, 0, 1);
                if (!batch.token) { LOGE("[llama_jni] GPU reload: failed to alloc batch for replay"); break; }
                for (int i = 0; i < this_chunk; ++i) {
                    batch.token[i] = cached_tokens[processed + i];
                    batch.logits[i] = (processed + i == (int)cached_tokens.size() - 1) ? 1 : 0;
                }
                batch.n_tokens = this_chunk;
                if (batch.pos) for (int i = 0; i < this_chunk; ++i) batch.pos[i] = processed + i;
                int32_t r = llama_decode(new_ctx, batch);
                llama_batch_free(batch);
                if (r != 0) { LOGE("[llama_jni] GPU reload: replay decode failed (r=%d)", r); break; }
                processed += this_chunk;
            }
        }

        // Swap in new model/context atomically
        struct llama_model * old_model = nullptr;
        struct llama_context * old_ctx = nullptr;
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            old_model = g_model;
            old_ctx = g_ctx;
            g_model = new_model;
            g_ctx = new_ctx;
            g_vk_backend = new_vk_backend;
            // bump session and clear cache
            g_session_id.fetch_add(1);
            {
                std::lock_guard<std::mutex> lock2(g_last_prompt_mutex);
                g_last_prompt_tokens.clear();
                g_last_prompt_token_count = 0;
                g_cached_session_id = g_session_id.load();
            }
        }

        // free old context/model outside lock
        if (old_ctx) llama_free(old_ctx);
        if (old_model) llama_model_free(old_model);
        free(devs_alloc);

        LOGI("[llama_jni] GPU reload: successfully swapped in GPU-enabled context");
        call_progress(100);
        // notify callback success
        if (g_callback) {
            JavaVM *jvm = g_jvm; JNIEnv *env = nullptr;
            if (jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                jclass cbCls = env->GetObjectClass(g_callback);
                if (cbCls) {
                    jmethodID onComplete = env->GetMethodID(cbCls, "onComplete", "(Z)V");
                    if (onComplete) env->CallVoidMethod(g_callback, onComplete, JNI_TRUE);
                }
                env->DeleteGlobalRef(g_callback);
                jvm->DetachCurrentThread();
            }
        }
    }).detach();

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

// Trim context to keep only the last `keepTokens` tokens. Recreates context and
// re-appends recent tokens from the cache. Returns JNI_TRUE on success.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_trimContextTo(
        JNIEnv *env,
        jobject /* thiz */,
        jint keepTokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model) return JNI_FALSE;
    if (keepTokens <= 0) return JNI_FALSE;

    // gather last tokens from cache
    std::vector<llama_token> tokens;
    {
        std::lock_guard<std::mutex> lock2(g_last_prompt_mutex);
        if (!g_last_prompt_tokens.empty()) {
            if ((int)g_last_prompt_tokens.size() > keepTokens) {
                tokens.assign(g_last_prompt_tokens.end() - keepTokens, g_last_prompt_tokens.end());
            } else {
                tokens = g_last_prompt_tokens;
            }
        }
    }

    // create new context
    struct llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) g_context_n_ctx;
    cparams.n_threads = g_n_threads;
    cparams.n_threads_batch = g_n_threads;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    cparams.type_k = GGML_TYPE_Q8_0;
    cparams.type_v = GGML_TYPE_Q8_0;
    // preserve offload setting if GPU backend enabled
    cparams.offload_kqv = (g_vk_backend != nullptr);
    cparams.op_offload = (g_vk_backend != nullptr);
    cparams.abort_callback = [](void * /*data*/) -> bool { return g_stop_requested.load(std::memory_order_relaxed); };

    struct llama_context * new_ctx = llama_init_from_model(g_model, cparams);
    if (!new_ctx) {
        LOGE("[llama_jni] trimContextTo: failed to create new context");
        return JNI_FALSE;
    }

    // replay tokens if any
    if (!tokens.empty()) {
        int processed = 0;
        const int CHUNK = 128;
        while (processed < (int)tokens.size()) {
            int this_chunk = std::min(CHUNK, (int)tokens.size() - processed);
            struct llama_batch batch = llama_batch_init(this_chunk, 0, 1);
            if (!batch.token) { LOGE("[llama_jni] trimContextTo: failed to alloc batch"); break; }
            for (int i = 0; i < this_chunk; ++i) {
                batch.token[i] = tokens[processed + i];
                batch.logits[i] = (processed + i == (int)tokens.size() - 1) ? 1 : 0;
            }
            batch.n_tokens = this_chunk;
            if (batch.pos) for (int i = 0; i < this_chunk; ++i) batch.pos[i] = processed + i;
            int32_t r = llama_decode(new_ctx, batch);
            llama_batch_free(batch);
            if (r != 0) { LOGE("[llama_jni] trimContextTo: replay decode failed (r=%d)", r); break; }
            processed += this_chunk;
        }
    }

    // swap contexts
    struct llama_context * old_ctx = g_ctx;
    g_ctx = new_ctx;
    if (old_ctx) llama_free(old_ctx);

    // update session and cache to reflect trimmed state
    g_session_id.fetch_add(1);
    {
        std::lock_guard<std::mutex> lock2(g_last_prompt_mutex);
        if ((int)tokens.size() > keepTokens) {
            g_last_prompt_tokens.assign(tokens.end() - keepTokens, tokens.end());
        } else {
            g_last_prompt_tokens = tokens;
        }
        g_last_prompt_token_count = g_last_prompt_tokens.size();
        g_cached_session_id = g_session_id.load();
    }

    LOGI("[llama_jni] trimContextTo: kept %d tokens", (int)g_last_prompt_token_count);
    return JNI_TRUE;
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
    std::vector<llama_token> prompt_tokens_buf(max_prompt_tokens);
    int32_t n_prompt = llama_tokenize(vocab, input.c_str(), (int32_t) input.size(), prompt_tokens_buf.data(), (int32_t) prompt_tokens_buf.size(), is_first, false);
    if (n_prompt <= 0) {
        LOGE("[llama_jni] tokenization failed or returned %d", n_prompt);
        return env->NewStringUTF("Tokenization failed.");
    }

    std::vector<llama_token> prompt_tokens(prompt_tokens_buf.begin(), prompt_tokens_buf.begin() + n_prompt);

    // base position to place the prompt tokens in the context memory
    llama_pos base_pos = is_first ? 0 : mem_pos_max + 1;

    // Determine how many tokens are already present in the context by comparing
    // with the cached last prompt tokenization. We will only decode the delta
    // tokens to avoid re-processing large identical prefixes.
    int start_index = 0;
    {
        std::lock_guard<std::mutex> lock(g_last_prompt_mutex);
        // Only use cache if it belongs to the current session
        if (g_cached_session_id == g_session_id.load() && !g_last_prompt_tokens.empty()) {
            int common = common_prefix_tokens(prompt_tokens, g_last_prompt_tokens);
            int tokens_in_ctx = (mem_pos_max == -1) ? 0 : (int)(mem_pos_max + 1);
            // Don't assume tokens beyond what the runtime already contains or what we recorded
            if (g_last_prompt_token_count <= (size_t)tokens_in_ctx) {
                start_index = std::min(common, tokens_in_ctx);
            } else {
                // cached token count doesn't match context; invalidate cache
                g_last_prompt_tokens.clear();
                g_last_prompt_token_count = 0;
                g_cached_session_id = g_session_id.load();
            }
        }
    }

    int32_t res = 0;
    int64_t t_start = llama_time_us();
    int64_t t_prompt_done = t_start;

    if (start_index < n_prompt) {
        // Only decode the remaining tokens after start_index
        int delta = n_prompt - start_index;
        struct llama_batch batch = llama_batch_init(delta, 0, 1);
        if (!batch.token) {
            LOGE("[llama_jni] failed to init batch for prompt");
            return env->NewStringUTF("Batch init failed.");
        }
        for (int i = start_index; i < n_prompt; ++i) {
            batch.token[i - start_index] = prompt_tokens[i];
            batch.logits[i - start_index] = (i == n_prompt - 1) ? 1 : 0;
        }
        batch.n_tokens = delta;

        // initialize the per-token metadata
        if (batch.pos) {
            for (int i = 0; i < delta; ++i) {
                batch.pos[i] = base_pos + start_index + i;
            }
        }
        if (batch.n_seq_id) {
            for (int i = 0; i < delta; ++i) {
                batch.n_seq_id[i] = 1;
            }
        }
        if (batch.seq_id) {
            for (int i = 0; i < delta; ++i) {
                if (batch.seq_id[i]) batch.seq_id[i][0] = 0;
            }
        }

        if (g_stop_requested.load()) {
            llama_batch_free(batch);
            return env->NewStringUTF("[Generation cancelled]");
        }

        res = llama_decode(g_ctx, batch);
        t_prompt_done = llama_time_us();
        llama_batch_free(batch);

        if (res != 0) {
            LOGE("[llama_jni] llama_decode returned %d", res);
            if (res == 1) LOGE("[llama_jni] could not find KV slot (try reducing context)");
            return env->NewStringUTF("Evaluation failed.");
        }
    } else {
        // Nothing to do: prompt tokens already in context
        t_prompt_done = llama_time_us();
        LOGI("[llama_jni] prompt tokens already present in context - skipping decode");
    }

    // Update cached tokenization for next call (bounded) and tag with session id
    {
        std::lock_guard<std::mutex> lock(g_last_prompt_mutex);
        g_last_prompt_tokens = prompt_tokens;
        if (g_last_prompt_tokens.size() > G_PROMPT_TOKEN_CACHE_LIMIT) g_last_prompt_tokens.resize(G_PROMPT_TOKEN_CACHE_LIMIT);
        g_last_prompt_token_count = g_last_prompt_tokens.size();
        g_cached_session_id = g_session_id.load();
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

// Reset the current session/context while keeping the model loaded.
// Re-creates g_ctx with the same n_ctx and thread settings. Returns JNI_TRUE on success.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_resetSession(
        JNIEnv *env,
        jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model) return JNI_FALSE;

    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }

    struct llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) g_context_n_ctx;
    cparams.n_threads = g_n_threads;
    cparams.n_threads_batch = g_n_threads;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    cparams.type_k = GGML_TYPE_Q8_0;
    cparams.type_v = GGML_TYPE_Q8_0;
    cparams.offload_kqv = false; // start CPU-only for reset to be fast
    cparams.op_offload = false;
    cparams.abort_callback = [](void * /*data*/) -> bool { return g_stop_requested.load(std::memory_order_relaxed); };

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("[llama_jni] resetSession: failed to re-create context");
        return JNI_FALSE;
    }

    // update thread and warmup
    llama_set_n_threads(g_ctx, g_n_threads, g_n_threads);
    llama_set_warmup(g_ctx, true);

    // bump session id and clear token cache
    g_session_id.fetch_add(1);
    {
        std::lock_guard<std::mutex> lock(g_last_prompt_mutex);
        g_last_prompt_tokens.clear();
        g_last_prompt_token_count = 0;
        g_cached_session_id = g_session_id.load();
    }

    LOGI("[llama_jni] resetSession: context recreated (n_ctx=%d)", g_context_n_ctx);
    return JNI_TRUE;
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
    std::vector<llama_token> prompt_tokens_buf(max_prompt_tokens);
    int32_t n_prompt = llama_tokenize(vocab, input.c_str(), (int32_t) input.size(),
                                       prompt_tokens_buf.data(), (int32_t) prompt_tokens_buf.size(), is_first, false);
    if (n_prompt <= 0) return env->NewStringUTF("Tokenization failed.");

    std::vector<llama_token> prompt_tokens(prompt_tokens_buf.begin(), prompt_tokens_buf.begin() + n_prompt);

    llama_pos base_pos = is_first ? 0 : mem_pos_max + 1;

    // Only decode the delta tokens compared to the last cached prompt to reduce
    // time-to-first-token when prompts share prefixes or repeat.
    int start_index = 0;
    {
        std::lock_guard<std::mutex> lock(g_last_prompt_mutex);
        if (g_cached_session_id == g_session_id.load() && !g_last_prompt_tokens.empty()) {
            int common = common_prefix_tokens(prompt_tokens, g_last_prompt_tokens);
            int tokens_in_ctx = (mem_pos_max == -1) ? 0 : (int)(mem_pos_max + 1);
            if (g_last_prompt_token_count <= (size_t)tokens_in_ctx) {
                start_index = std::min(common, tokens_in_ctx);
            } else {
                // cache invalid for current context
                g_last_prompt_tokens.clear();
                g_last_prompt_token_count = 0;
                g_cached_session_id = g_session_id.load();
            }
        }
    }

    int64_t t_start = llama_time_us();
    int32_t res = 0;
    int64_t t_prompt_done = t_start;

    if (start_index < n_prompt) {
        int delta = n_prompt - start_index;
        struct llama_batch batch = llama_batch_init(delta, 0, 1);
        if (!batch.token) return env->NewStringUTF("Batch init failed.");
        for (int i = start_index; i < n_prompt; ++i) {
            batch.token[i - start_index] = prompt_tokens[i];
            batch.logits[i - start_index] = (i == n_prompt - 1) ? 1 : 0;
        }
        batch.n_tokens = delta;
        if (batch.pos)      for (int i = 0; i < delta; ++i) batch.pos[i] = base_pos + start_index + i;
        if (batch.n_seq_id) for (int i = 0; i < delta; ++i) batch.n_seq_id[i] = 1;
        if (batch.seq_id)   for (int i = 0; i < delta; ++i) { if (batch.seq_id[i]) batch.seq_id[i][0] = 0; }

        if (g_stop_requested.load()) { llama_batch_free(batch); return env->NewStringUTF("[Cancelled]"); }

        LOGI("[llama_jni] Decoding prompt delta (%d tokens)...", delta);
        res = llama_decode(g_ctx, batch);
        t_prompt_done = llama_time_us();
        llama_batch_free(batch);
        LOGI("[llama_jni] Prompt delta decoded in %.1f sec (result=%d)",
             (double)(t_prompt_done - t_start) / 1000000.0, (int)res);
    } else {
        t_prompt_done = llama_time_us();
        LOGI("[llama_jni] prompt tokens already present in context - skipping decode");
    }

    if (res != 0) return env->NewStringUTF("Evaluation failed.");

    // Update cached tokenization
    {
        std::lock_guard<std::mutex> lock(g_last_prompt_mutex);
        g_last_prompt_tokens = prompt_tokens;
        if (g_last_prompt_tokens.size() > G_PROMPT_TOKEN_CACHE_LIMIT) g_last_prompt_tokens.resize(G_PROMPT_TOKEN_CACHE_LIMIT);
        g_last_prompt_token_count = g_last_prompt_tokens.size();
        g_cached_session_id = g_session_id.load();
    }

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

            // Stream token to Java callback with batching to reduce JNI overhead
            if (onTokenMethod) {
                static const int STREAM_FLUSH_TOKENS = 4;
                static const int STREAM_FLUSH_BYTES = 128;
                static const std::string punctuation = ".!?\n";
                // accumulate tokens into a small buffer and flush under conditions
                static thread_local std::string stream_buffer;
                static thread_local int stream_token_count = 0;
                bool flush_now = false;

                stream_buffer.append(piece_buf, piece_len);
                if (stream_token_count == 0) {
                    // first token: flush immediately for responsiveness
                    flush_now = true;
                } else {
                    stream_token_count++;
                    // flush on token count, buffer size, or punctuation/newline
                    if (stream_token_count >= STREAM_FLUSH_TOKENS) flush_now = true;
                    if ((int)stream_buffer.size() >= STREAM_FLUSH_BYTES) flush_now = true;
                    char lastc = piece_buf[piece_len - 1];
                    if (punctuation.find(lastc) != std::string::npos) flush_now = true;
                }

                if (flush_now) {
                    jstring jtoken = env->NewStringUTF(stream_buffer.c_str());
                    jboolean cont = env->CallBooleanMethod(callback, onTokenMethod, jtoken);
                    env->DeleteLocalRef(jtoken);
                    stream_buffer.clear();
                    stream_token_count = 0;
                    if (!cont) {
                        LOGI("[llama_jni] callback returned false at token %d — stopping", t);
                        break; // callback returned false → stop
                    }
                }
                else {
                    // ensure we increment token counter for first append
                    if (stream_token_count == 0) stream_token_count = 1;
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
    // bump session id and clear token cache
    g_session_id.fetch_add(1);
    {
        std::lock_guard<std::mutex> lock(g_last_prompt_mutex);
        g_last_prompt_tokens.clear();
        g_last_prompt_token_count = 0;
        g_cached_session_id = g_session_id.load();
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
JNIEXPORT jboolean JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_appendPromptTokens(
        JNIEnv *env,
        jobject /* thiz */,
        jstring /* prompt */) {
    LOGI("[llama_jni placeholder] appendPromptTokens called but llama.cpp is not integrated");
    return JNI_FALSE;
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_pretokenizePrompt(
        JNIEnv *env,
        jobject /* thiz */,
        jstring /* prompt */) {
    LOGI("[llama_jni placeholder] pretokenizePrompt called but llama.cpp is not integrated");
    return nullptr;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_resetSession(
        JNIEnv *env,
        jobject /* thiz */) {
    LOGI("[llama_jni placeholder] resetSession called but llama.cpp is not integrated");
    return JNI_FALSE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_isGpuEnabled(
        JNIEnv *env,
        jobject /* thiz */) {
    return 0; // DISABLED
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_getOptimalBatchSettings(
        JNIEnv *env,
        jobject /* thiz */) {
    jintArray out = env->NewIntArray(2);
    if (!out) return nullptr;
    jint tmp[2] = {64, 64};
    env->SetIntArrayRegion(out, 0, 2, tmp);
    return out;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_trimContextTo(
        JNIEnv *env,
        jobject /* thiz */,
        jint /* keepTokens */) {
    LOGI("[llama_jni placeholder] trimContextTo called but llama.cpp is not integrated");
    return JNI_FALSE;
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

