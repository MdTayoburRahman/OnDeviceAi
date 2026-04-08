package com.droidrocks.ondeviceai;

public class LlamaBridge {

    private static boolean nativeLoaded = false;

    static {
        try {
            System.loadLibrary("llama_jni");
            nativeLoaded = true;
        } catch (Throwable t) {
            // library not available; record state and allow app to continue without crashing
            nativeLoaded = false;
        }
    }

    public static boolean isNativeLoaded() {
        return nativeLoaded;
    }

    // Native methods - calling these when nativeLoaded==false will throw UnsatisfiedLinkError
    public native boolean loadModel(String modelPath, int contextSize, int threads);

    public native String generate(String prompt, int maxTokens, float temperature, float topP);

    // GPU status enum values returned by isGpuEnabled()
    public static final int GPU_STATUS_DISABLED = 0;
    public static final int GPU_STATUS_AVAILABLE = 1;
    public static final int GPU_STATUS_ENABLED = 2;

    /** Callback interface for enableGpuBackendAsync progress/completion */
    public interface GpuEnableCallback {
        /** Progress update in percent 0..100 (may be called from native thread) */
        void onProgress(int percent);
        /** Called when enableGpuBackendAsync completes; success=true on success */
        void onComplete(boolean success);
    }

    /** Asynchronously attempt to enable a GPU backend. freeBytes is a hint of free Java heap/native memory in bytes.
     *  The callback (may be null) will receive progress and completion notifications. */
    public native boolean enableGpuBackendAsync(long freeBytes, GpuEnableCallback callback);

    /** Returns one of GPU_STATUS_* constants describing GPU availability/enabled state. */
    public native int isGpuEnabled();

    /** Returns an int[2] with {n_batch, n_ubatch} optimal settings discovered at load time. */
    public native int[] getOptimalBatchSettings();

    /** Trim internal native context to keep only last keepTokens tokens (rebuilds context). */
    public native boolean trimContextTo(int keepTokens);

    /** Callback interface for streaming token-by-token output */
    public interface TokenCallback {
        /** Called from native for each generated token. Return false to stop generation. */
        boolean onToken(String token);
    }

    /**
     * Streaming generation — calls {@code callback.onToken()} for every token as it is produced.
     * Returns the full concatenated output when finished.
     */
    public native String generateStreaming(String prompt, int maxTokens, float temperature, float topP, TokenCallback callback);

    // Request cooperative interruption of a running generation (best-effort).
    // Implemented in native code; sets a flag the native loop checks.
    public native void interruptGeneration();

    public native void releaseModel();

    /** Reset the native session/context and clear cached prompt tokens. Safe to call from background thread. */
    public native boolean resetSession();

    // Vulkan probe getters (implemented in native code). May return empty/0 when Vulkan isn't available.
    public native boolean isVulkanAvailable();
    public native String getVulkanDeviceName();
    public native long getVulkanDeviceLocalMemory();
    public native boolean initVulkanBackend();

    // Called from native code (via JNI) to append a line to the in-app runtime log.
    // Native can call the static Java method com.droidrocks.ondeviceai.LlamaBridge.appendNativeLog(msg)
    // using CallStaticVoidMethod or a generated JNI bridge.
    public static void appendNativeLog(String msg) {
        try {
            RuntimeLog.append(msg == null ? "" : msg);
        } catch (Throwable ignored) {
        }
    }
}