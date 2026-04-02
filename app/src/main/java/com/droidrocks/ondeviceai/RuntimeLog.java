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
 */
public class RuntimeLog {
    private static WeakReference<TextView> tvRef = null;
    private static WeakReference<ScrollView> svRef = null;

    public static void bind(TextView textView, ScrollView scrollView) {
        tvRef = new WeakReference<>(textView);
        svRef = new WeakReference<>(scrollView);
    }

    public static void append(final String line) {
        final TextView tv = tvRef == null ? null : tvRef.get();
        final ScrollView sv = svRef == null ? null : svRef.get();
        if (tv == null) return;
        try {
            tv.post(() -> {
                try {
                    tv.append(line);
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

    public static void clear() {
        final TextView tv = tvRef == null ? null : tvRef.get();
        if (tv == null) return;
        tv.post(() -> tv.setText(""));
    }
}


