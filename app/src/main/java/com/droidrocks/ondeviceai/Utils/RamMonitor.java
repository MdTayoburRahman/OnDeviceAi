package com.droidrocks.ondeviceai.Utils;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

public class RamMonitor {

    public interface OnRamUpdateListener {
        void onUpdate(RamInfo info);
    }

    public static class RamInfo {
        private final long appUsedRamMB;
        private final long appMaxHeapMB;
        private final long appFreeHeapMB;
        private final long deviceTotalRamMB;
        private final long deviceAvailableRamMB;
        private final long deviceUsedRamMB;

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

        /** App's currently used RAM in MB */
        public long getAppUsedRamMB() {
            return appUsedRamMB;
        }

        /** App's maximum heap size in MB */
        public long getAppMaxHeapMB() {
            return appMaxHeapMB;
        }

        /** App's free heap in MB */
        public long getAppFreeHeapMB() {
            return appFreeHeapMB;
        }

        /** Device total RAM in MB */
        public long getDeviceTotalRamMB() {
            return deviceTotalRamMB;
        }

        /** Device available RAM in MB */
        public long getDeviceAvailableRamMB() {
            return deviceAvailableRamMB;
        }

        /** Device used RAM in MB */
        public long getDeviceUsedRamMB() {
            return deviceUsedRamMB;
        }

        /** Device RAM usage as percentage (0-100) */
        public double getDeviceUsagePercent() {
            if (deviceTotalRamMB <= 0) return 0;
            return (deviceUsedRamMB * 100.0) / deviceTotalRamMB;
        }

        /** App heap usage as percentage (0-100) */
        public double getAppHeapUsagePercent() {
            if (appMaxHeapMB <= 0) return 0;
            return (appUsedRamMB * 100.0) / appMaxHeapMB;
        }

        /** Short display for status chip - shows app usage and device available */
        public String toChipString() {
            return String.format(Locale.US, "App: %dMB | Free: %dMB",
                    appUsedRamMB, deviceAvailableRamMB);
        }

        /** Compact chip string - just app usage */
        public String toAppChipString() {
            return appUsedRamMB + "MB";
        }

        /** Compact chip string - device info */
        public String toDeviceChipString() {
            return String.format(Locale.US, "%d/%dMB",
                    deviceUsedRamMB, deviceTotalRamMB);
        }

        /** Full display string with all RAM info */
        public String toDisplayString() {
            return "App: " + appUsedRamMB + "MB" +
                    " | Device: " + deviceUsedRamMB + "/" + deviceTotalRamMB + "MB" +
                    " | Free: " + deviceAvailableRamMB + "MB";
        }

        /** Nice formatted multi-line display with emojis */
        public String toNiceString() {
            double devicePercent = getDeviceUsagePercent();
            double appPercent = getAppHeapUsagePercent();
            
            return String.format(Locale.US, 
                    "📱 App: %dMB / %dMB (%.0f%%)\n" +
                    "💾 Device: %dMB / %dMB (%.0f%%)\n" +
                    "✨ Available: %dMB",
                    appUsedRamMB, appMaxHeapMB, appPercent,
                    deviceUsedRamMB, deviceTotalRamMB, devicePercent,
                    deviceAvailableRamMB);
        }

        /** Detailed multi-line display */
        public String toDetailedString() {
            return "App Used RAM: " + appUsedRamMB + " MB" +
                    "\nApp Max Heap: " + appMaxHeapMB + " MB" +
                    "\nApp Free Heap: " + appFreeHeapMB + " MB" +
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