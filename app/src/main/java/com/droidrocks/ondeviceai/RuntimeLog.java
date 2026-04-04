package com.droidrocks.ondeviceai;

import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Runtime log helper for displaying detailed AI/native logs in the app.
 * Thread-safe logging with timestamps and categorized output.
 */
public class RuntimeLog {
    private static WeakReference<TextView> tvRef = null;
    private static WeakReference<ScrollView> svRef = null;
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    // Filter tag - only messages containing this will be shown
    private static final String LLAMA_JNI_TAG = "[llama_jni]";

    // Log categories for visual distinction
    private static final String ICON_INFO = "ℹ️";
    private static final String ICON_SUCCESS = "✅";
    private static final String ICON_ERROR = "❌";
    private static final String ICON_WARN = "⚠️";
    private static final String ICON_TIMING = "⏱️";
    private static final String ICON_MODEL = "🧠";
    private static final String ICON_GPU = "🎮";
    private static final String ICON_TOKEN = "📝";
    private static final String ICON_THINKING = "💭";
    private static final String ICON_PROMPT = "📥";
    private static final String ICON_RESPONSE = "📤";

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
     * Format log line with timestamp, icon, and cleaned message.
     */
    private static String formatLogLine(String line) {
        if (line == null) return "";

        String timestamp = TIME_FORMAT.format(new Date());
        String icon = getLogIcon(line);

        // Extract message after [llama_jni]
        int tagIndex = line.indexOf(LLAMA_JNI_TAG);
        String message = "";
        if (tagIndex >= 0) {
            message = line.substring(tagIndex + LLAMA_JNI_TAG.length()).trim();
        } else {
            message = line;
        }

        // Clean up the message
        message = cleanMessage(message);

        return String.format("[%s] %s %s", timestamp, icon, message);
    }

    /**
     * Get appropriate icon based on log content.
     */
    private static String getLogIcon(String line) {
        String lower = line.toLowerCase();

        // Check for specific emoji indicators first (already has the right icon)
        if (line.contains("💭")) {
            return ICON_THINKING;
        }
        if (line.contains("📥") || lower.contains("prompt:") || lower.contains("prompt (")) {
            return ICON_PROMPT;
        }
        if (line.contains("📤") || lower.contains("response preview")) {
            return ICON_RESPONSE;
        }
        if (line.contains("🏁")) {
            return ICON_SUCCESS;
        }
        if (line.contains("⏹️")) {
            return ICON_WARN;
        }

        if (lower.contains("error") || lower.contains("failed") || lower.contains("could not")) {
            return ICON_ERROR;
        }
        if (lower.contains("warning") || lower.contains("warn")) {
            return ICON_WARN;
        }
        if (lower.contains("success") || lower.contains("initialized") || lower.contains("complete") || lower.contains("ready")) {
            return ICON_SUCCESS;
        }
        if (lower.contains("tok/s") || lower.contains("tokens/sec") || lower.contains(" ms") || lower.contains("speed:") || lower.contains("time:")) {
            return ICON_TIMING;
        }
        if (lower.contains("vulkan") || lower.contains("gpu") || lower.contains("backend") || lower.contains("vram")) {
            return ICON_GPU;
        }
        if (lower.contains("model") || lower.contains("context") || lower.contains("load")) {
            return ICON_MODEL;
        }
        if (lower.contains("token") || lower.contains("generate") || lower.contains("sample")) {
            return ICON_TOKEN;
        }
        return ICON_INFO;
    }

    /**
     * Clean up message for better readability.
     */
    private static String cleanMessage(String message) {
        if (message == null) return "";

        // Remove duplicate spaces
        message = message.replaceAll("\\s+", " ").trim();

        // Format key metrics for readability
        message = message.replace("prompt=", "Prompt: ");
        message = message.replace("gen=", "Gen: ");
        message = message.replace("tok/s", " tokens/sec");
        message = message.replace("prompt_len=", "Prompt length: ");
        message = message.replace("maxTokens=", "Max tokens: ");
        message = message.replace("temp=", "Temp: ");
        message = message.replace("topP=", "Top-P: ");

        return message;
    }

    public static void clear() {
        final TextView tv = tvRef == null ? null : tvRef.get();
        if (tv == null) return;
        tv.post(() -> tv.setText(""));
    }

    /**
     * Log a separator line for visual grouping.
     */
    public static void logSeparator() {
        append("llama_jni: [llama_jni] ────────────────────");
    }
}
