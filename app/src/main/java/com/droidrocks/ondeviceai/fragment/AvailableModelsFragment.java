package com.droidrocks.ondeviceai.fragment;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.droidrocks.ondeviceai.ModelUtils;
import com.droidrocks.ondeviceai.R;
import com.droidrocks.ondeviceai.adapter.AvailableModelsAdapter;
import com.droidrocks.ondeviceai.data.AvailableModel;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fragment for displaying available models to download.
 */
public class AvailableModelsFragment extends Fragment {

    private static final String TAG = "AvailableModelsFragment";

    private RecyclerView rvModels;
    private LinearLayout downloadProgress;
    private ProgressBar progressBar;
    private TextView tvDownloadingModel;
    private TextView tvProgressPercent;
    private AvailableModelsAdapter adapter;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isDownloading = false;

    // Preset models available for download - optimized for LOW and MEDIUM-END mobile devices
    private static final AvailableModel[] PRESET_MODELS = {
        // === ULTRA-COMPACT (< 400 MB) - Best for low-end devices (2GB RAM) ===
        new AvailableModel(
            "SmolLM2 360M Instruct (Q8_0)",
            "📱 PURPOSE: Budget phones • Quick responses • Basic Q&A",
            "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/smollm2-360m-instruct-q8_0.gguf?download=true",
            "~380 MB"
        ),
        new AvailableModel(
            "Qwen2.5 0.5B Instruct (Q4_K_M)",
            "📱 PURPOSE: Low-end devices • Fast inference • Simple tasks",
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true",
            "~400 MB"
        ),

        // === COMPACT (400-600 MB) - Great for low-end to entry-level mid-range ===
        new AvailableModel(
            "Qwen2.5 0.5B Instruct (Q8_0)",
            "💬 PURPOSE: Better quality • Low RAM usage • Longer responses",
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q8_0.gguf?download=true",
            "~530 MB"
        ),
        new AvailableModel(
            "TinyLlama 1.1B Chat (Q2_K)",
            "💬 PURPOSE: Ultra-fast chat • Conversational • Minimal footprint",
            "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q2_K.gguf?download=true",
            "~480 MB"
        ),

        // === SMALL (600 MB - 1 GB) - For mid-range devices (4-6GB RAM) ===
        new AvailableModel(
            "TinyLlama 1.1B Chat (Q4_K_M)",
            "🎯 PURPOSE: Balanced quality • Mid-range phones • Better understanding",
            "https://huggingface.co/hieupt/TinyLlama-1.1B-Chat-v1.0-Q4_K_M-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0-q4_k_m.gguf?download=true",
            "~670 MB"
        ),
        new AvailableModel(
            "Qwen2.5 1.5B Instruct (Q4_K_M)",
            "🚀 PURPOSE: Instructions & tasks • Writing content • Complex reasoning",
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf?download=true",
            "~1.0 GB"
        ),

        // === MEDIUM (1-1.6 GB) - For better mid-range devices (6GB+ RAM) ===
        new AvailableModel(
            "Llama 3.2 1B Instruct (Q4_K_M)",
            "⭐ PURPOSE: Latest model • Better accuracy • Mid-range devices",
            "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf?download=true",
            "~800 MB"
        ),
        new AvailableModel(
            "Gemma 2 2B Instruct (Q4_K_M)",
            "🎓 PURPOSE: Quality responses • Better reasoning • Knowledge tasks",
            "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf?download=true",
            "~1.6 GB"
        ),
        new AvailableModel(
            "Llama 3.2 3B Instruct (Q2_K)",
            "💪 PURPOSE: Powerful responses • Complex tasks • Compressed for mobile",
            "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q2_K.gguf?download=true",
            "~1.2 GB"
        )
    };

    public static AvailableModelsFragment newInstance() {
        return new AvailableModelsFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_available_models, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvModels = view.findViewById(R.id.rvModels);
        downloadProgress = view.findViewById(R.id.downloadProgress);
        progressBar = view.findViewById(R.id.progressBar);
        tvDownloadingModel = view.findViewById(R.id.tvDownloadingModel);
        tvProgressPercent = view.findViewById(R.id.tvProgressPercent);

        setupRecyclerView();
        loadModels();
    }

    @Override
    public void onResume() {
        super.onResume();
        checkDownloadedStatus();
    }

    private void setupRecyclerView() {
        adapter = new AvailableModelsAdapter();
        rvModels.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvModels.setAdapter(adapter);

        adapter.setOnDownloadClickListener(this::confirmDownload);
    }

    private void loadModels() {
        List<AvailableModel> models = new ArrayList<>();
        Set<String> downloadedFilenames = getDownloadedFilenames();

        for (AvailableModel preset : PRESET_MODELS) {
            AvailableModel model = new AvailableModel(
                preset.getName(),
                preset.getDescription(),
                preset.getUrl(),
                preset.getSize()
            );
            // Check if already downloaded
            model.setDownloaded(downloadedFilenames.contains(model.getFilename()));
            models.add(model);
        }

        adapter.setModels(models);
    }

    private void checkDownloadedStatus() {
        Set<String> downloadedFilenames = getDownloadedFilenames();
        for (AvailableModel preset : PRESET_MODELS) {
            adapter.updateDownloadStatus(preset.getFilename(),
                downloadedFilenames.contains(preset.getFilename()));
        }
    }

    private Set<String> getDownloadedFilenames() {
        Set<String> filenames = new HashSet<>();
        File dir = ModelUtils.getModelsDir(requireContext());
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    filenames.add(f.getName());
                }
            }
        }
        return filenames;
    }

    private void confirmDownload(AvailableModel model) {
        if (isDownloading) {
            Toast.makeText(requireContext(), "Download already in progress", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check if already downloaded
        Set<String> downloaded = getDownloadedFilenames();
        if (downloaded.contains(model.getFilename())) {
            Toast.makeText(requireContext(), R.string.model_already_exists, Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_download)
            .setMessage("Download " + model.getName() + "?\n\nSize: " + model.getSize())
            .setPositiveButton(R.string.btn_download, (dialog, which) -> startDownload(model))
            .setNegativeButton(R.string.no, null)
            .show();
    }

    private void startDownload(AvailableModel model) {
        isDownloading = true;

        // Show progress
        downloadProgress.setVisibility(View.VISIBLE);
        tvDownloadingModel.setText(getString(R.string.downloading_model) + " " + model.getName());
        progressBar.setIndeterminate(true);
        progressBar.setProgress(0);
        tvProgressPercent.setText("Starting...");

        File dest = new File(ModelUtils.getModelsDir(requireContext()), model.getFilename());

        Log.i(TAG, "Starting download: " + model.getUrl() + " -> " + dest.getAbsolutePath());

        new Thread(() -> {
            boolean success = ModelUtils.downloadModel(requireContext(), model.getUrl(), dest, percent -> {
                mainHandler.post(() -> {
                    if (!isAdded()) return;

                    if (percent < 0) {
                        progressBar.setIndeterminate(true);
                        tvProgressPercent.setText("Downloading...");
                    } else {
                        if (progressBar.isIndeterminate()) {
                            progressBar.setIndeterminate(false);
                        }
                        progressBar.setProgress(percent);
                        tvProgressPercent.setText(percent + "%");
                    }
                });
            });

            mainHandler.post(() -> {
                if (!isAdded()) return;

                isDownloading = false;
                downloadProgress.setVisibility(View.GONE);

                if (success && dest.exists()) {
                    Toast.makeText(requireContext(), R.string.download_complete, Toast.LENGTH_SHORT).show();

                    // Update UI
                    adapter.updateDownloadStatus(model.getFilename(), true);

                    // Return the downloaded model
                    Intent result = new Intent();
                    result.putExtra("modelPath", dest.getAbsolutePath());
                    requireActivity().setResult(Activity.RESULT_OK, result);
                    requireActivity().finish();
                } else {
                    String err = ModelUtils.getLastError();
                    String msg = getString(R.string.download_failed) + (err != null ? ": " + err : "");
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show();
                    Log.e(TAG, "Download failed: " + msg);

                    // Offer to open Wi-Fi settings if DNS failed
                    if (err != null && err.contains("Unable to resolve host")) {
                        new AlertDialog.Builder(requireContext())
                            .setTitle("Network Error")
                            .setMessage("Cannot connect to download server. Check your internet connection.")
                            .setPositiveButton("Open Wi-Fi Settings", (d, w) -> {
                                try {
                                    startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
                                } catch (Exception ex) {
                                    Log.e(TAG, "Failed to open Wi-Fi settings", ex);
                                }
                            })
                            .setNegativeButton(R.string.no, null)
                            .show();
                    }
                }
            });
        }).start();
    }

    /**
     * Refresh the download status of models.
     */
    public void refresh() {
        checkDownloadedStatus();
    }
}

