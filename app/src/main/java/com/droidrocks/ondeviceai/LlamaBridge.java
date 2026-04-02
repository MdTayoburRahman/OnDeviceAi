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
}