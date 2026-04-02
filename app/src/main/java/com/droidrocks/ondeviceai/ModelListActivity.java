package com.droidrocks.ondeviceai;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ModelListActivity extends AppCompatActivity {

    private static final String TAG = "ModelListActivity";
    private ListView lvModels;
    private Button btnRefresh;
    private TextView tvEmpty;

    private List<File> modelFiles = new ArrayList<>();
    // Preset remote model URLs users can download from the list page
    private static final String[] PRESET_MODEL_URLS = new String[] {
            "https://huggingface.co/hieupt/TinyLlama-1.1B-Chat-v1.0-Q4_K_M-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0-q4_k_m.gguf?download=true",
            "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q2_K.gguf?download=true",
            "https://huggingface.co/lm-kit/gemma-3-1b-instruct-gguf/resolve/main/gemma-3-it-1B-Q2_K.gguf?download=true"
    };
    // Added: gemma-3 preset (user requested)
    // https://huggingface.co/lm-kit/gemma-3-1b-instruct-gguf/resolve/main/gemma-3-it-1B-Q2_K.gguf?download=true
    // Note: keep entries in desired order; users will see remote presets after local files.

    private boolean downloading = false;

    // Helper: create a readable display name from a filename
    private static String prettifyNameFromFilename(String filename) {
        if (filename == null || filename.isEmpty()) return "(unknown)";
        // remove query and extension-like suffixes
        String name = filename;
        int q = name.indexOf('?'); if (q >= 0) name = name.substring(0, q);
        // remove common extensions
        name = name.replaceAll("\\.(gguf|ggml|bin|pt|pth|safetensors)$", "");
        // replace separators with spaces
        name = name.replaceAll("[_\\-.]+", " ");
        // Remove quantization/flags like q4_k_m, Q2_K etc
        name = name.replaceAll("\\bq[0-9](_[a-z0-9_]+)?\\b", "");
        name = name.replaceAll("\\bQ[0-9](_[A-Z0-9_]+)?\\b", "");
        // collapse spaces
        name = name.trim().replaceAll("\\s{2,}", " ");
        // Capitalize first letter
        if (name.length() > 0) name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return name;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_list);

        lvModels = findViewById(R.id.lvModels);
        btnRefresh = findViewById(R.id.btnRefreshModels);
        tvEmpty = findViewById(R.id.tvEmptyModels);
        final android.widget.ProgressBar progressBar = findViewById(R.id.progressDownload);
        final android.widget.TextView tvProgress = findViewById(R.id.tvDownloadProgress);

        btnRefresh.setOnClickListener(v -> refreshList());

        lvModels.setOnItemClickListener((parent, view, position, id) -> {
            if (downloading) {
                Toast.makeText(ModelListActivity.this, "Download in progress, please wait...", Toast.LENGTH_SHORT).show();
                return;
            }
            // If position corresponds to a local file, return it. Otherwise handle preset remote URL download.
            if (position < modelFiles.size()) {
                File selected = modelFiles.get(position);
                Toast.makeText(ModelListActivity.this, "Selected: " + selected.getName(), Toast.LENGTH_SHORT).show();
                Intent result = new Intent();
                result.putExtra("modelPath", selected.getAbsolutePath());
                setResult(Activity.RESULT_OK, result);
                finish();
                return;
            }
            int remoteIndex = position - modelFiles.size();
            if (remoteIndex >= 0 && remoteIndex < PRESET_MODEL_URLS.length) {
                final String url = PRESET_MODEL_URLS[remoteIndex];
                // ask user to confirm download
                new android.app.AlertDialog.Builder(ModelListActivity.this)
                        .setTitle("Download Model")
                        .setMessage("Download model from:\n" + url + " ?")
                        .setPositiveButton("Download", (dlg, which) -> {
                            // start download in background
                            downloading = true;
                            progressBar.setVisibility(View.VISIBLE);
                            tvProgress.setVisibility(View.VISIBLE);
                            progressBar.setMax(100);
                            progressBar.setProgress(0);
                            // default to indeterminate until listener reports a percent >= 0
                            progressBar.setIndeterminate(true);
                            tvProgress.setText("Starting...");
                            // determine filename from URL
                            String filename = url;
                            int q = filename.indexOf('?'); if (q >= 0) filename = filename.substring(0, q);
                            int s = filename.lastIndexOf('/'); if (s >= 0) filename = filename.substring(s + 1);
                            final File dest = new File(ModelUtils.getModelsDir(ModelListActivity.this), filename);
                            // download on a background thread
                            Log.i(TAG, "Initiating download: " + url + " -> " + dest.getAbsolutePath());
                            new Thread(() -> {
                                boolean ok = ModelUtils.downloadModel(ModelListActivity.this, url, dest, percent -> {
                                    runOnUiThread(() -> {
                                        try {
                                            Log.d(TAG, "download progress: " + percent + "%");
                                            if (percent < 0) {
                                                // unknown content length -> indeterminate
                                                progressBar.setIndeterminate(true);
                                                tvProgress.setText("Downloading...");
                                            } else {
                                                if (progressBar.isIndeterminate()) progressBar.setIndeterminate(false);
                                                try { progressBar.setProgress(percent); } catch (Exception ignored) {}
                                                tvProgress.setText("Downloading: " + percent + "%");
                                            }
                                        } catch (Exception ignored) {}
                                    });
                                });
                                runOnUiThread(() -> {
                                    downloading = false;
                                    progressBar.setVisibility(View.GONE);
                                    tvProgress.setVisibility(View.GONE);
                                    progressBar.setProgress(0);
                                    Log.i(TAG, "Download finished ok=" + ok + " exists=" + (dest.exists()));
                                    if (ok && dest.exists()) {
                                        Toast.makeText(ModelListActivity.this, "Download complete: " + dest.getName(), Toast.LENGTH_SHORT).show();
                                        // return newly downloaded model immediately
                                        Intent result = new Intent();
                                        result.putExtra("modelPath", dest.getAbsolutePath());
                                        setResult(Activity.RESULT_OK, result);
                                        finish();
                                    } else {
                                        String err = ModelUtils.getLastError();
                                        String msg = "Download failed" + (err != null ? (": " + err) : "");
                                        Toast.makeText(ModelListActivity.this, msg, Toast.LENGTH_LONG).show();
                                        tvProgress.setVisibility(View.VISIBLE);
                                        tvProgress.setText(msg);
                                        Log.e(TAG, "Download failed: " + msg);

                                        // If DNS resolution failed, offer to open Wi-Fi settings to the user.
                                        if (err != null && err.contains("Unable to resolve host")) {
                                            new android.app.AlertDialog.Builder(ModelListActivity.this)
                                                    .setTitle("Network error")
                                                    .setMessage("Device cannot resolve the download host. Please check your network connection. Would you like to open Wi‑Fi settings?")
                                                    .setPositiveButton("Open Wi‑Fi", (d, w) -> {
                                                        try {
                                                            startActivity(new android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
                                                        } catch (Exception ex) {
                                                            Log.e(TAG, "Failed to open Wi-Fi settings", ex);
                                                        }
                                                    })
                                                    .setNegativeButton("OK", null)
                                                    .show();
                                        }

                                        // refresh list to show any partial changes
                                        refreshList();
                                    }
                                });
                            }).start();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });

        refreshList();
    }

    private void refreshList() {
        File dir = ModelUtils.getModelsDir(this);
        File[] files = dir.listFiles();
        modelFiles.clear();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) modelFiles.add(f);
            }
        }

        // Build a two-line list: title (friendly model name) and subtitle (filename or URL)
        java.util.List<java.util.Map<String, String>> data = new ArrayList<>();

        for (File f : modelFiles) {
            java.util.Map<String, String> item = new java.util.HashMap<>();
            String filename = f.getName();
            String title = prettifyNameFromFilename(filename);
            String subtitle = filename + " (" + (f.length() / 1024) + " KB)";
            item.put("title", title);
            item.put("subtitle", subtitle);
            data.add(item);
        }

        for (String url : PRESET_MODEL_URLS) {
            java.util.Map<String, String> item = new java.util.HashMap<>();
            String filename = url;
            int q = filename.indexOf('?'); if (q >= 0) filename = filename.substring(0, q);
            int s = filename.lastIndexOf('/'); if (s >= 0) filename = filename.substring(s + 1);
            String title = prettifyNameFromFilename(filename);
            item.put("title", title + " (remote)");
            item.put("subtitle", url);
            data.add(item);
        }

        if (data.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            lvModels.setAdapter(null);
        } else {
            tvEmpty.setVisibility(View.GONE);
            String[] from = new String[] {"title", "subtitle"};
            int[] to = new int[] {android.R.id.text1, android.R.id.text2};
            android.widget.SimpleAdapter adapter = new android.widget.SimpleAdapter(this, data, android.R.layout.simple_list_item_2, from, to);
            lvModels.setAdapter(adapter);
        }
        Log.i(TAG, "Found " + modelFiles.size() + " model files in " + dir.getAbsolutePath());
    }
}

