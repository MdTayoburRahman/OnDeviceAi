package com.droidrocks.ondeviceai.Utils;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.util.Log;

public final class GpuInfo {
    private static final String TAG = "GpuInfo";

    public static class Info {
        private final String vendor;
        private final String renderer;
        private final String version;

        public Info(String vendor, String renderer, String version) {
            this.vendor = vendor;
            this.renderer = renderer;
            this.version = version;
        }

        /** GPU vendor (e.g., "Qualcomm", "ARM", "Imagination Technologies") */
        public String getVendor() {
            return vendor;
        }

        /** GPU renderer/model name (e.g., "Adreno (TM) 730", "Mali-G78") */
        public String getRenderer() {
            return renderer;
        }

        /** OpenGL ES version string */
        public String getVersion() {
            return version;
        }

        /** Short GPU name suitable for display */
        public String getShortName() {
            if (renderer != null && !renderer.isEmpty() && !renderer.equals("unknown")) {
                // Clean up common patterns like "(TM)" or extra spaces
                return renderer.replace("(TM)", "").replace("(R)", "").trim();
            }
            if (vendor != null && !vendor.isEmpty() && !vendor.equals("unknown")) {
                return vendor;
            }
            return "Unknown GPU";
        }

        /** Get clean vendor name */
        public String getCleanVendor() {
            if (vendor == null || vendor.isEmpty() || vendor.equals("unknown")) {
                return "Unknown";
            }
            // Shorten common vendor names
            if (vendor.toLowerCase().contains("qualcomm")) return "Qualcomm";
            if (vendor.toLowerCase().contains("arm")) return "ARM";
            if (vendor.toLowerCase().contains("imagination")) return "PowerVR";
            if (vendor.toLowerCase().contains("nvidia")) return "NVIDIA";
            if (vendor.toLowerCase().contains("mali")) return "ARM Mali";
            return vendor;
        }

        /** Get OpenGL ES version number only */
        public String getGLVersionShort() {
            if (version == null || version.isEmpty() || version.equals("unknown")) {
                return "N/A";
            }
            // Extract version number (e.g., "OpenGL ES 3.2" -> "ES 3.2")
            if (version.contains("OpenGL ES")) {
                return version.replace("OpenGL ", "");
            }
            return version;
        }

        /** Short chip display for status bar */
        public String toChipString() {
            return getShortName();
        }

        /** Nice formatted display with icon-friendly layout */
        public String toNiceString() {
            StringBuilder sb = new StringBuilder();
            sb.append("🎮 ").append(getShortName());
            String cleanVendor = getCleanVendor();
            if (!cleanVendor.equals("Unknown")) {
                sb.append(" (").append(cleanVendor).append(")");
            }
            return sb.toString();
        }

        /** Full single-line display */
        public String toDisplayString() {
            return renderer + " (" + vendor + ")";
        }

        /** Multi-line detailed display */
        @Override
        public String toString() {
            return "GPU vendor=" + vendor + "\nrenderer=" + renderer + "\nversion=" + version;
        }
    }

    // Returns Info or null if detection failed
    public static Info getGpuInfo() {
        if (Build.VERSION.SDK_INT < 17) {
            Log.w(TAG, "EGL14 not available (API < 17)");
            return null;
        }

        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay failed");
            return null;
        }
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) {
            Log.e(TAG, "eglInitialize failed");
            return null;
        }

        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
        };
        android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, configs.length, numConfigs, 0)) {
            Log.e(TAG, "eglChooseConfig failed");
            EGL14.eglTerminate(display);
            return null;
        }
        EGLConfig config = configs[0];

        int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        EGLContext context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext failed");
            EGL14.eglTerminate(display);
            return null;
        }

        int[] surfAttribs = { EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE };
        EGLSurface surface = EGL14.eglCreatePbufferSurface(display, config, surfAttribs, 0);
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreatePbufferSurface failed");
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
            return null;
        }

        boolean madeCurrent = EGL14.eglMakeCurrent(display, surface, surface, context);
        if (!madeCurrent) {
            Log.e(TAG, "eglMakeCurrent failed");
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
            return null;
        }

        String vendor = GLES20.glGetString(GLES20.GL_VENDOR);
        String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
        String version = GLES20.glGetString(GLES20.GL_VERSION);

        // cleanup
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(display, surface);
        EGL14.eglDestroyContext(display, context);
        EGL14.eglTerminate(display);

        if (vendor == null) vendor = "unknown";
        if (renderer == null) renderer = "unknown";
        if (version == null) version = "unknown";

        Log.i(TAG, "GPU vendor=" + vendor + " renderer=" + renderer + " version=" + version);
        return new Info(vendor, renderer, version);
    }
}

