package com.droidrocks.ondeviceai.Utils;


import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.util.Locale;

public class CpuMonitor {

    public interface OnCpuUpdateListener {
        void onUpdate(CpuInfo info);
    }

    public static class CpuInfo {
        private final int cpuCores;
        private final double appCpuPercent;
        private final String appCpuUsageText;

        public CpuInfo(int cpuCores, double appCpuPercent, String appCpuUsageText) {
            this.cpuCores = cpuCores;
            this.appCpuPercent = appCpuPercent;
            this.appCpuUsageText = appCpuUsageText;
        }

        /** Number of CPU cores available */
        public int getCpuCores() {
            return cpuCores;
        }

        /** App CPU usage as percentage (0-100) */
        public double getAppCpuPercent() {
            return appCpuPercent;
        }

        /** App CPU usage as formatted text (e.g., "12.34%") */
        public String getAppCpuUsageText() {
            return appCpuUsageText;
        }

        /** Short display for status chip */
        public String toChipString() {
            return cpuCores + " cores | " + appCpuUsageText;
        }

        /** Full display string */
        public String toDisplayString() {
            return "Cores: " + cpuCores + " | App CPU: " + appCpuUsageText;
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final long intervalMillis;
    private final OnCpuUpdateListener listener;

    private boolean isRunning = false;
    private long lastAppCpuTimeMs = 0;
    private long lastWallTimeMs = 0;

    public CpuMonitor(long intervalMillis, OnCpuUpdateListener listener) {
        this.intervalMillis = intervalMillis;
        this.listener = listener;
    }

    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;

            CpuInfo info = readCpuInfo();
            if (listener != null && info != null) {
                listener.onUpdate(info);
            }

            mainHandler.postDelayed(this, intervalMillis);
        }
    };

    public void start() {
        if (isRunning) return;
        isRunning = true;

        lastAppCpuTimeMs = Process.getElapsedCpuTime();
        lastWallTimeMs = SystemClock.elapsedRealtime();

        if (listener != null) {
            listener.onUpdate(new CpuInfo(
                    Runtime.getRuntime().availableProcessors(),
                    0.0,
                    "..."
            ));
        }

        mainHandler.postDelayed(updateRunnable, intervalMillis);
    }

    public void stop() {
        isRunning = false;
        mainHandler.removeCallbacks(updateRunnable);
    }

    private CpuInfo readCpuInfo() {
        int cpuCores = Runtime.getRuntime().availableProcessors();

        long currentAppCpuTimeMs = Process.getElapsedCpuTime();
        long currentWallTimeMs = SystemClock.elapsedRealtime();

        long appCpuDiffMs = currentAppCpuTimeMs - lastAppCpuTimeMs;
        long wallDiffMs = currentWallTimeMs - lastWallTimeMs;

        double appCpuPercent = 0.0;
        String appCpuText = "0.0%";

        if (wallDiffMs > 0 && cpuCores > 0) {
            appCpuPercent = (appCpuDiffMs * 100.0) / (wallDiffMs * cpuCores);

            if (appCpuPercent < 0) appCpuPercent = 0;
            if (appCpuPercent > 100) appCpuPercent = 100;

            appCpuText = String.format(Locale.US, "%.1f%%", appCpuPercent);
        }

        lastAppCpuTimeMs = currentAppCpuTimeMs;
        lastWallTimeMs = currentWallTimeMs;

        return new CpuInfo(cpuCores, appCpuPercent, appCpuText);
    }
}