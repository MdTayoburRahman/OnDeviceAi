package com.droidrocks.ondeviceai.Utils;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

public class RamMonitor {

    public interface OnRamUpdateListener {
        void onUpdate(RamInfo info);
    }

    public static class RamInfo {
        public final long appUsedRamMB;
        public final long appMaxHeapMB;
        public final long appFreeHeapMB;

        public final long deviceTotalRamMB;
        public final long deviceAvailableRamMB;
        public final long deviceUsedRamMB;

        public RamInfo(long appUsedRamMB,
                       long appMaxHeapMB,
                       long appFreeHeapMB,
                       long deviceTotalRamMB,
                       long deviceAvailableRamMB,
                       long deviceUsedRamMB) {
            this.appUsedRamMB = appUsedRamMB;
            this.appMaxHeapMB = appMaxHeapMB;
            this.appFreeHeapMB = appFreeHeapMB;
            this.deviceTotalRamMB = deviceTotalRamMB;
            this.deviceAvailableRamMB = deviceAvailableRamMB;
            this.deviceUsedRamMB = deviceUsedRamMB;
        }

        public String toDisplayString() {
            return "App Used RAM: " + appUsedRamMB + " MB" +
                   // "\nApp Max Heap: " + appMaxHeapMB + " MB" +
                 //   "\nApp Free Heap: " + appFreeHeapMB + " MB" +
                    "\nDevice RAM: " + deviceTotalRamMB + " MB" +
                    "\nAvailable RAM: " + deviceAvailableRamMB + " MB" +
                    "\nUsed RAM: " + deviceUsedRamMB + " MB";
        }
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long intervalMs;
    private final OnRamUpdateListener listener;
    private boolean running = false;

    public RamMonitor(Context context, long intervalMs, OnRamUpdateListener listener) {
        this.context = context.getApplicationContext();
        this.intervalMs = intervalMs;
        this.listener = listener;
    }

    private final Runnable runnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;

            RamInfo info = getRamInfo(context);
            if (listener != null) {
                listener.onUpdate(info);
            }

            handler.postDelayed(this, intervalMs);
        }
    };

    public void start() {
        if (running) return;
        running = true;

        if (listener != null) {
            listener.onUpdate(getRamInfo(context));
        }

        handler.postDelayed(runnable, intervalMs);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(runnable);
    }

    public static RamInfo getRamInfo(Context context) {
        Runtime runtime = Runtime.getRuntime();

        long usedRam = runtime.totalMemory() - runtime.freeMemory();
        long maxHeap = runtime.maxMemory();
        long freeHeap = runtime.freeMemory();

        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo);
        }

        long totalRam = memoryInfo.totalMem;
        long availableRam = memoryInfo.availMem;
        long deviceUsedRam = totalRam - availableRam;

        return new RamInfo(
                bytesToMB(usedRam),
                bytesToMB(maxHeap),
                bytesToMB(freeHeap),
                bytesToMB(totalRam),
                bytesToMB(availableRam),
                bytesToMB(deviceUsedRam)
        );
    }

    private static long bytesToMB(long bytes) {
        return bytes / (1024 * 1024);
    }

}