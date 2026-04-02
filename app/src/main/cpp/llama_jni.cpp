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

static const char *TAG = "llama_jni";
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

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

        g_vulkan_device_name = props.deviceName ? props.deviceName : "unknown";
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

// Simple platform-agnostic helper to pick a reasonable default thread count.
// Strategy:
//  - use std::thread::hardware_concurrency() when available
//  - if the reported count is zero, fall back to 1
//  - prefer leaving one core free (use hw_concurrency - 1) when possible
//  - clamp to a small upper bound to avoid extreme oversubscription on
//    devices with many logical cores
static int detect_default_n_threads() {
    unsigned int hw = std::thread::hardware_concurrency();
    if (hw == 0) hw = 1;
    unsigned int pick = hw > 1 ? std::max(1u, hw - 1) : 1u;
    // Cap to avoid too many threads on mobile devices; tune as needed.
    const unsigned int MAX_THREADS = 8;
    if (pick > MAX_THREADS) pick = MAX_THREADS;
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

    LOGI("[llama_jni] loadModel called path=%s contextSize=%d threads=%d", path.c_str(), (int) contextSize, (int) threads);

    if (g_ctx) {
        LOGI("[llama_jni] model already loaded, releasing previous instance");
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }

    // prepare model params
    struct llama_model_params mparams = llama_model_default_params();
    // keep defaults; caller may tune mparams if needed

    // Load dynamic ggml backends (Vulkan, Metal, CUDA, etc.) so llama.cpp can discover GPU devices
    // This mirrors how upstream examples call ggml_backend_load_all() before loading models.
    ggml_backend_load_all();

    // If Vulkan probe succeeded, try to select a Vulkan GPU device and pass it via mparams.devices
    // so the model loader will prefer GPU offload. We allocate a small array terminated by NULL.
    ggml_backend_dev_t * devs_alloc = nullptr;
    if (g_vulkan_available.load()) {
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
        if (chosen) {
            // allocate a small NULL-terminated array of devices
            devs_alloc = (ggml_backend_dev_t *) malloc(sizeof(ggml_backend_dev_t) * 2);
            if (devs_alloc) {
                devs_alloc[0] = chosen;
                devs_alloc[1] = nullptr;
                mparams.devices = devs_alloc;
                LOGI("[llama_jni] selected ggml Vulkan device for model load");
            }
        } else {
            LOGI("[llama_jni] no ggml Vulkan GPU device found - model will select devices automatically");
        }
    }

    g_model = llama_model_load_from_file(path.c_str(), mparams);

    // free temporary devices array if we allocated one - llama copies device handles internally
    if (devs_alloc) {
        free(devs_alloc);
        devs_alloc = nullptr;
        mparams.devices = nullptr;
    }
    if (!g_model) {
        LOGE("[llama_jni] failed to load model file: %s", path.c_str());
        return JNI_FALSE;
    }

    struct llama_context_params cparams = llama_context_default_params();
    if (contextSize > 0) cparams.n_ctx = (uint32_t) contextSize;
    // If the Java caller provided a positive thread count, use it. Otherwise
    // detect a reasonable default for the device.
    if ((int) threads > 0) {
        g_n_threads = (int) threads;
    } else {
        g_n_threads = detect_default_n_threads();
        LOGI("[llama_jni] auto-detected thread count = %d (hardware_concurrency=%u)", g_n_threads, std::thread::hardware_concurrency());
    }

    cparams.n_threads = g_n_threads;
    // Allow more threads for batch/prompt processing inside the context
    // This value is advisory; the runtime may cap it depending on available resources
    cparams.n_threads_batch = (int32_t) std::max(1, g_n_threads * 2);

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("[llama_jni] failed to initialize context from model: %s", path.c_str());
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    // set number of threads for runtime (if supported)
    // give more threads for batch/prompt processing (tunable)
    llama_set_n_threads(g_ctx, g_n_threads, g_n_threads * 4);

    // optionally enable warmup: this pre-activates tensors which may improve
    // per-token generation time after an initial warmup overhead
    llama_set_warmup(g_ctx, true);

    // clear any previous stop request when loading a new model
    g_stop_requested.store(false);
    // attempt to probe Vulkan availability for possible combined CPU+GPU usage
    if (try_init_vulkan()) {
        LOGI("[llama_jni] Vulkan is available on this device: %s", g_vulkan_device_name.c_str());
    } else {
        LOGI("[llama_jni] Vulkan not available or probe failed; continuing with CPU-only path");
    }
    LOGI("[llama_jni] model and context initialized successfully");
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

    const char *promptChars = env->GetStringUTFChars(prompt, nullptr);
    std::string input = promptChars ? promptChars : "";
    env->ReleaseStringUTFChars(prompt, promptChars);

    LOGI("[llama_jni] generate called prompt='%s' maxTokens=%d temp=%f topP=%f", input.c_str(), (int) maxTokens, temperature, topP);

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

    // Tokenize prompt (text_len = input.size())
    const int max_prompt_tokens = 4096;
    std::vector<llama_token> prompt_tokens(max_prompt_tokens);
    int32_t n_prompt = llama_tokenize(vocab, input.c_str(), (int32_t) input.size(), prompt_tokens.data(), (int32_t) prompt_tokens.size(), is_first, false);
    if (n_prompt <= 0) {
        LOGE("[llama_jni] tokenization failed or returned %d", n_prompt);
        return env->NewStringUTF("Tokenization failed.");
    }

    // base position to place the prompt tokens in the context memory. If this
    // is the first message, start at 0; otherwise continue after the last
    // position currently in memory for sequence 0.
    llama_pos base_pos = is_first ? 0 : (mem_pos_max + 1);

    // Evaluate prompt using batch API
    // llama_batch_init allocates auxiliary arrays (pos, seq_id, n_seq_id, logits).
    struct llama_batch batch = llama_batch_init(n_prompt, 0, 1);
    if (!batch.token) {
        LOGE("[llama_jni] failed to init batch for prompt");
        return env->NewStringUTF("Batch init failed.");
    }

    // populate token ids and request logits only for the last token
    for (int i = 0; i < n_prompt; ++i) {
        batch.token[i]   = prompt_tokens[i];
        batch.logits[i]  = (i == n_prompt - 1) ? 1 : 0;
    }
    // tell the batch how many tokens we actually filled
    batch.n_tokens = n_prompt;

    // initialize the per-token metadata that llama_batch_init preallocated.
    // For a single-sequence prompt we set pos incrementally starting from
    // base_pos and mark each seq_id array to contain a single sequence id = 0.
    if (batch.pos) {
        for (int i = 0; i < n_prompt; ++i) {
            batch.pos[i] = base_pos + i;
        }
    }
    if (batch.n_seq_id) {
        for (int i = 0; i < n_prompt; ++i) {
            batch.n_seq_id[i] = 1; // one seq id for this simple single-sequence input
        }
    }
    if (batch.seq_id) {
        for (int i = 0; i < n_prompt; ++i) {
            if (batch.seq_id[i]) {
                // n_seq_max was set to 1 at init time, so we can safely set index 0
                batch.seq_id[i][0] = 0;
            }
        }
    }

    if (g_stop_requested.load()) {
        LOGI("[llama_jni] generation interrupted before prompt decode");
        llama_batch_free(batch);
        return env->NewStringUTF("[Generation cancelled]");
    }
    int64_t t_decode_start = llama_time_us();
    int32_t res = llama_decode(g_ctx, batch);
    int64_t t_decode_end = llama_time_us();
    LOGI("[llama_jni] prompt decode time = %lld ms", (long long) ((t_decode_end - t_decode_start) / 1000));
    llama_batch_free(batch);
    if (res != 0) {
        // Log detailed info to help debugging
        LOGE("[llama_jni] llama_decode returned %d", res);
        // Interpret common return codes
        if (res == 1) LOGE("[llama_jni] decode result: could not find a KV slot for the batch (try reducing batch size or increase context)");
        else if (res == 2) LOGE("[llama_jni] decode result: aborted during processing");
        else if (res == -1) LOGE("[llama_jni] decode result: invalid input batch");
        else if (res < -1) LOGE("[llama_jni] decode result: fatal error (code=%d)", res);

        // Dump some context/mode statistics
        if (g_ctx) {
            LOGI("[llama_jni] ctx stats: n_ctx=%u n_ctx_seq=%u n_batch=%u n_threads=%d", llama_n_ctx(g_ctx), llama_n_ctx_seq(g_ctx), llama_n_batch(g_ctx), llama_n_threads(g_ctx));
            const struct llama_model * m = llama_get_model(g_ctx);
            if (m) {
                LOGI("[llama_jni] model stats: n_embd=%d n_layer=%d n_head=%d", llama_model_n_embd(m), llama_model_n_layer(m), llama_model_n_head(m));
            }
        }

        return env->NewStringUTF("Evaluation failed.");
    }

    std::string output;

    // Build sampler chain
    struct llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Add samplers according to temperature/topP
    if (temperature <= 0.0f && topP >= 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        if (topP < 1.0f) {
            llama_sampler_chain_add(chain, llama_sampler_init_top_p(topP, 1));
        }
        if (temperature > 0.0f) {
            llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        }
        // final sampler to pick from distribution
        llama_sampler_chain_add(chain, llama_sampler_init_dist((uint32_t)time(nullptr)));
    }

    // generation loop: sample from logits produced by last decode, then decode the sampled token
    for (int t = 0; t < maxTokens; ++t) {
        if (g_stop_requested.load()) {
            LOGI("[llama_jni] generation interrupted at token %d", t);
            break; // exit generation loop and return partial output
        }
        int64_t t_sample_start = llama_time_us();
        llama_token id = llama_sampler_sample(chain, g_ctx, -1);
        int64_t t_sample_end = llama_time_us();
        LOGI("[llama_jni] sample time for token %d = %lld ms", t, (long long) ((t_sample_end - t_sample_start) / 1000));
        if (id == LLAMA_TOKEN_NULL) {
            LOGI("[llama_jni] sampler returned NULL token, finishing");
            break;
        }

        // append token text
        const char * piece = llama_vocab_get_text(vocab, id);
        if (piece) output += piece;

        // if token is EOS/EOT stop
        if (llama_vocab_is_eog(vocab, id)) {
            LOGI("[llama_jni] end-of-generation token received");
            break;
        }

        // evaluate the chosen token to advance state
        struct llama_batch one = llama_batch_init(1, 0, 1);
        if (!one.token) {
            LOGE("[llama_jni] failed to init batch for token");
            break;
        }
        one.token[0] = id;
        one.logits[0] = 1; // request logits for this token (needed for sampling)
        one.n_tokens = 1;

        // initialize per-token metadata that llama_batch_init preallocated
        // so we don't depend on freeing internals. Set pos to the next token
        // index and mark a single seq_id = 0 for this new token.
        if (one.pos) {
            // position of the new token should follow the prompt tokens and any
            // previously existing tokens in the context
            one.pos[0] = base_pos + n_prompt + t; // next position in the sequence
        }
        if (one.n_seq_id) {
            one.n_seq_id[0] = 1;
        }
        if (one.seq_id && one.seq_id[0]) {
            one.seq_id[0][0] = 0;
        }

        if (g_stop_requested.load()) {
            LOGI("[llama_jni] generation interrupted before token decode %d", t);
            llama_batch_free(one);
            break;
        }
        int64_t t_decode_start_token = llama_time_us();
        int32_t r = llama_decode(g_ctx, one);
        int64_t t_decode_end_token = llama_time_us();
        LOGI("[llama_jni] decode time for token %d = %lld ms", t, (long long) ((t_decode_end_token - t_decode_start_token) / 1000));
        llama_batch_free(one);
        if (r != 0) {
            LOGE("[llama_jni] decode failed during generation step %d: %d", t, r);
            break;
        }
    }

    llama_sampler_free(chain);

    // clear stop flag so subsequent calls are not auto-cancelled
    g_stop_requested.store(false);

    LOGI("[llama_jni] generation completed, output length=%zu", output.size());
    // Log a truncated preview of the output to help debugging (cap at 256 chars)
    { size_t cap = 256; std::string preview = output.size() > cap ? output.substr(0, cap) + "..." : output; LOGI("[llama_jni] output preview: %s", preview.c_str()); }
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
JNIEXPORT void JNICALL
Java_com_droidrocks_ondeviceai_LlamaBridge_releaseModel(
        JNIEnv *env,
        jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_model_loaded = false;
    LOGI("[llama_jni placeholder] Model released");
}

#endif // HAVE_LLAMA_CPP

