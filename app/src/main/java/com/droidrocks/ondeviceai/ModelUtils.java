package com.droidrocks.ondeviceai;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import android.util.Log;

public class ModelUtils {

    public static File getModelsDir(Context context) {
        File dir = new File(context.getExternalFilesDir(null), "models");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File getDefaultModelFile(Context context) {
        return new File(getModelsDir(context), "tinyllama.gguf");
    }

    public static String formatPrompt(String userInput) {
        return "<|user|>\n" + userInput.trim() + "\n<|assistant|>\n";
    }

    /**
     * Download a model from the given URL into the destination file.
     * This method performs network IO and should be called off the main thread.
     * It reports progress via the provided listener and returns true on success.
     */
    public interface DownloadListener {
        void onProgress(int percent);
    }

    public static boolean downloadModel(Context context, String urlString, File destFile, DownloadListener listener) {
        final String TAG = "ModelUtils";
        // lastError contains the last error message from a failed download attempt
        lastError = null;
        InputStream in = null;
        FileOutputStream out = null;
        HttpURLConnection conn = null;
        boolean ok = false;
        try {
            File parent = destFile.getParentFile();
            if (parent != null && !parent.exists()) {
                boolean created = parent.mkdirs();
                Log.i(TAG, "Ensured model parent dir exists: " + parent.getAbsolutePath() + " created=" + created);
            }

            URL url = new URL(urlString);
            Log.i(TAG, "Starting download from: " + urlString + " -> " + destFile.getAbsolutePath());
            conn = (HttpURLConnection) url.openConnection();
            // Some servers require a User-Agent header; set a simple one
            conn.setRequestProperty("User-Agent", "OnDeviceAi/1.0");
            // do not use gzip encoding for large binary downloads
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setConnectTimeout(15000);
            // Increase read timeout for large model downloads
            conn.setReadTimeout(120000);
            conn.setRequestMethod("GET");
            conn.connect();

            int code = conn.getResponseCode();
            Log.i(TAG, "Download HTTP response code=" + code + " for " + urlString);
            if (code < 200 || code >= 300) {
                lastError = "HTTP response code=" + code;
                return false;
            }

            int contentLength = conn.getContentLength();

            // Inform listener whether content-length is known. If unknown, send -1 so UI can show
            // an indeterminate progress bar.
            if (listener != null) {
                try {
                    if (contentLength > 0) listener.onProgress(0);
                    else listener.onProgress(-1);
                } catch (Exception ex) { Log.w(TAG, "Listener error init", ex); }
            }

            // Log remote filename and headers if provided by server
            String contentDisposition = conn.getHeaderField("Content-Disposition");
            if (contentDisposition != null) {
                Log.i(TAG, "Content-Disposition: " + contentDisposition);
            }
            Log.i(TAG, "Final URL after redirects: " + conn.getURL());
            try {
                java.util.Map<String, java.util.List<String>> headers = conn.getHeaderFields();
                if (headers != null) {
                    for (java.util.Map.Entry<String, java.util.List<String>> e : headers.entrySet()) {
                        Log.i(TAG, "Header: " + e.getKey() + " = " + e.getValue());
                    }
                }
            } catch (Exception hx) {
                Log.w(TAG, "Failed to read response headers", hx);
            }

            in = conn.getInputStream();
            // Write to a temporary file first, then move to final name to avoid incomplete files with final name
            File tmpFile = new File(destFile.getAbsolutePath() + ".download");
            out = new FileOutputStream(tmpFile);
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            int lastPercent = 0;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
                if (contentLength > 0 && listener != null) {
                    int percent = (int) ((total * 100) / contentLength);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        try { listener.onProgress(percent); } catch (Exception ex) { Log.w(TAG, "Listener error", ex); }
                    }
                }
            }
            out.flush();
            // move tmp to dest
            File tmpFileRef = new File(destFile.getAbsolutePath() + ".download");
            if (tmpFileRef.exists()) {
                // delete existing dest if present
                if (destFile.exists()) {
                    boolean d = destFile.delete();
                    Log.i(TAG, "Deleted existing dest file before rename: " + destFile.getAbsolutePath() + " deleted=" + d);
                }
                boolean renamed = tmpFileRef.renameTo(destFile);
                Log.i(TAG, "Renamed temp download to final name: " + destFile.getAbsolutePath() + " success=" + renamed);
            }
            // If content length unknown, report 100% at end
            if (listener != null) try { listener.onProgress(100); } catch (Exception ignored) {}
            ok = true;
            return true;
        } catch (Exception e) {
            Log.e("ModelUtils", "downloadModel failed: " + e.getMessage(), e);
            lastError = e.getMessage();
            ok = false;
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
            if (!ok && destFile != null && destFile.exists()) {
                // remove incomplete file
                try { destFile.delete(); } catch (Exception ignored) {}
            }
        }
    }

    private static volatile String lastError = null;

    /**
     * Return the last error message recorded by downloadModel, or null if none.
     */
    public static String getLastError() {
        return lastError;
    }

    /**
     * Return a friendly model name derived from a filename or path.
     * Examples:
     *  - tinyllama-1.1b-chat-v1.0-q4_k_m.gguf -> Tinyllama 1 1b chat v1 0
     *  - /.../models/gemma-3-it-1B-Q2_K.gguf -> Gemma 3 it 1B
     */
    public static String getFriendlyModelName(String pathOrFilename) {
        if (pathOrFilename == null) return "(unknown)";
        String filename = pathOrFilename;
        // if it's a full path, extract last segment
        int s = filename.lastIndexOf('/');
        if (s >= 0) filename = filename.substring(s + 1);
        // strip query
        int q = filename.indexOf('?'); if (q >= 0) filename = filename.substring(0, q);
        // reuse existing prettify logic: remove common extensions and tokens
        String name = filename.replaceAll("\\.(gguf|ggml|bin|pt|pth|safetensors)$", "");
        name = name.replaceAll("[_\\-.]+", " ");
        name = name.replaceAll("\\bq[0-9](_[a-z0-9_]+)?\\b", "");
        name = name.replaceAll("\\bQ[0-9](_[A-Z0-9_]+)?\\b", "");
        name = name.trim().replaceAll("\\s{2,}", " ");
        if (!name.isEmpty()) name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return name;
    }

    /**
     * Return an array of model files found in the models directory.
     * Files are filtered by common model extensions and returned sorted by name.
     */
    public static File[] listAvailableModels(Context context) {
        File dir = getModelsDir(context);
        File[] all = dir.listFiles();
        if (all == null) return new File[0];
        java.util.ArrayList<File> keep = new java.util.ArrayList<>();
        for (File f : all) {
            if (f == null || !f.isFile()) continue;
            String name = f.getName().toLowerCase();
            if (name.endsWith(".gguf") || name.endsWith(".ggml") || name.endsWith(".bin") || name.endsWith(".pt") || name.endsWith(".pth") || name.endsWith(".safetensors")) {
                keep.add(f);
            }
        }
        File[] res = keep.toArray(new File[0]);
        java.util.Arrays.sort(res, (a,b) -> a.getName().compareToIgnoreCase(b.getName()));
        return res;
    }
}
