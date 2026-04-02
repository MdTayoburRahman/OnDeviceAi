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

    // Request cooperative interruption of a running generation (best-effort).
    // Implemented in native code; sets a flag the native loop checks.
    public native void interruptGeneration();

    public native void releaseModel();

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