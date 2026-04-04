package com.droidrocks.ondeviceai.fragment;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.droidrocks.ondeviceai.ModelUtils;
import com.droidrocks.ondeviceai.R;
import com.droidrocks.ondeviceai.adapter.DownloadedModelsAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Fragment for displaying downloaded models.
 */
public class DownloadedModelsFragment extends Fragment {

    private RecyclerView rvModels;
    private LinearLayout emptyState;
    private DownloadedModelsAdapter adapter;

    public static DownloadedModelsFragment newInstance() {
        return new DownloadedModelsFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_downloaded_models, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvModels = view.findViewById(R.id.rvModels);
        emptyState = view.findViewById(R.id.emptyState);

        setupRecyclerView();
        loadModels();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadModels();
    }

    private void setupRecyclerView() {
        adapter = new DownloadedModelsAdapter();
        rvModels.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvModels.setAdapter(adapter);

        adapter.setOnModelClickListener(model -> {
            // Return selected model to activity
            Intent result = new Intent();
            result.putExtra("modelPath", model.getAbsolutePath());
            requireActivity().setResult(Activity.RESULT_OK, result);
            requireActivity().finish();
        });

        adapter.setOnModelDeleteListener(this::confirmDeleteModel);
    }

    private void confirmDeleteModel(File model, int position) {
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.btn_delete_model)
            .setMessage(getString(R.string.confirm_delete_model_desc) + "\n\n" + model.getName())
            .setPositiveButton(R.string.yes, (dialog, which) -> deleteModel(model, position))
            .setNegativeButton(R.string.no, null)
            .show();
    }

    private void deleteModel(File model, int position) {
        boolean deleted = model.delete();
        if (deleted) {
            adapter.removeModel(position);
            Toast.makeText(requireContext(), R.string.model_deleted, Toast.LENGTH_SHORT).show();

            // Check if list is now empty
            if (adapter.getItemCount() == 0) {
                emptyState.setVisibility(View.VISIBLE);
                rvModels.setVisibility(View.GONE);
            }
        } else {
            Toast.makeText(requireContext(), "Failed to delete model", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadModels() {
        File dir = ModelUtils.getModelsDir(requireContext());
        File[] files = dir.listFiles();
        List<File> modelFiles = new ArrayList<>();

        if (files != null) {
            for (File f : files) {
                if (f.isFile() && isModelFile(f.getName())) {
                    modelFiles.add(f);
                }
            }
        }

        if (modelFiles.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            rvModels.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            rvModels.setVisibility(View.VISIBLE);
            adapter.setModels(modelFiles);
        }
    }

    private boolean isModelFile(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".gguf") || lower.endsWith(".ggml") ||
               lower.endsWith(".bin") || lower.endsWith(".pt") ||
               lower.endsWith(".pth") || lower.endsWith(".safetensors");
    }

    /**
     * Refresh the list of downloaded models.
     */
    public void refresh() {
        loadModels();
    }
}


