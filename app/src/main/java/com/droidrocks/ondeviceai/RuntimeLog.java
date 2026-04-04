package com.droidrocks.ondeviceai;

import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.ref.WeakReference;

/**
 * Small runtime log helper to append lines to an in-app TextView/ScrollView.
 * Use RuntimeLog.bind(tvLog, scrollLog) from your Activity to attach the view,
 * then call RuntimeLog.append("message") from any Java code to display logs.
 * This is thread-safe.
 *
 * Only logs containing "[llama_jni]" will be displayed in the AI Log panel.
 */
public class RuntimeLog {
    private static WeakReference<TextView> tvRef = null;
    private static WeakReference<ScrollView> svRef = null;

    // Filter tag - only messages containing this will be shown
    private static final String LLAMA_JNI_TAG = "[llama_jni]";

    public static void bind(TextView textView, ScrollView scrollView) {
        tvRef = new WeakReference<>(textView);
        svRef = new WeakReference<>(scrollView);
    }

    public static void append(final String line) {
        // Only show lines that contain the llama_jni tag
        if (line == null || !line.contains(LLAMA_JNI_TAG)) {
            return;
        }

        final TextView tv = tvRef == null ? null : tvRef.get();
        final ScrollView sv = svRef == null ? null : svRef.get();
        if (tv == null) return;
        try {
            tv.post(() -> {
                try {
                    // Format the log line for better readability
                    String formattedLine = formatLogLine(line);
                    tv.append(formattedLine);
                    tv.append("\n");
                    if (sv != null) {
                        sv.fullScroll(View.FOCUS_DOWN);
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    /**
     * Format log line for better readability in the UI.
     * Extracts the message part after the tag prefix.
     */
    private static String formatLogLine(String line) {
        if (line == null) return "";

        // Extract just the message part after [llama_jni]
        int tagIndex = line.indexOf(LLAMA_JNI_TAG);
        if (tagIndex >= 0) {
            String message = line.substring(tagIndex + LLAMA_JNI_TAG.length()).trim();
            // Add timestamp prefix for better logging
            return "▸ " + message;
        }
        return line;
    }

    public static void clear() {
        final TextView tv = tvRef == null ? null : tvRef.get();
        if (tv == null) return;
        tv.post(() -> tv.setText(""));
    }
}
