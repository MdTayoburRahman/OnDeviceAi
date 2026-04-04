package com.droidrocks.ondeviceai.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.droidrocks.ondeviceai.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for displaying downloaded models.
 */
public class DownloadedModelsAdapter extends RecyclerView.Adapter<DownloadedModelsAdapter.ViewHolder> {

    private final List<File> models = new ArrayList<>();
    private OnModelClickListener listener;
    private OnModelDeleteListener deleteListener;
    private String selectedModelPath;

    public interface OnModelClickListener {
        void onModelClick(File model);
    }

    public interface OnModelDeleteListener {
        void onModelDelete(File model, int position);
    }

    public void setOnModelClickListener(OnModelClickListener listener) {
        this.listener = listener;
    }

    public void setOnModelDeleteListener(OnModelDeleteListener listener) {
        this.deleteListener = listener;
    }

    public void setSelectedModelPath(String path) {
        this.selectedModelPath = path;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_downloaded_model, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        File model = models.get(position);
        String filename = model.getName();

        // Create friendly name
        String friendlyName = prettifyName(filename);
        holder.tvModelName.setText(friendlyName);

        // Show file info
        long sizeKb = model.length() / 1024;
        String sizeStr;
        if (sizeKb > 1024) {
            sizeStr = String.format("%.1f MB", sizeKb / 1024.0);
        } else {
            sizeStr = sizeKb + " KB";
        }
        holder.tvModelInfo.setText(filename + " • " + sizeStr);

        // Show selection indicator
        boolean isSelected = selectedModelPath != null && selectedModelPath.equals(model.getAbsolutePath());
        holder.ivSelect.setVisibility(isSelected ? View.VISIBLE : View.GONE);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onModelClick(model);
            }
        });

        holder.btnDelete.setOnClickListener(v -> {
            if (deleteListener != null) {
                deleteListener.onModelDelete(model, holder.getBindingAdapterPosition());
            }
        });
    }

    @Override
    public int getItemCount() {
        return models.size();
    }

    public void setModels(List<File> newModels) {
        models.clear();
        if (newModels != null) {
            models.addAll(newModels);
        }
        notifyDataSetChanged();
    }

    public void removeModel(int position) {
        if (position >= 0 && position < models.size()) {
            models.remove(position);
            notifyItemRemoved(position);
        }
    }

    private String prettifyName(String filename) {
        if (filename == null || filename.isEmpty()) return "(unknown)";
        String name = filename;
        int q = name.indexOf('?');
        if (q >= 0) name = name.substring(0, q);
        name = name.replaceAll("\\.(gguf|ggml|bin|pt|pth|safetensors)$", "");
        name = name.replaceAll("[_\\-.]+", " ");
        name = name.replaceAll("\\bq[0-9](_[a-z0-9_]+)?\\b", "");
        name = name.replaceAll("\\bQ[0-9](_[A-Z0-9_]+)?\\b", "");
        name = name.trim().replaceAll("\\s{2,}", " ");
        if (name.length() > 0) {
            name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
        return name;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvModelName;
        TextView tvModelInfo;
        ImageView ivSelect;
        ImageButton btnDelete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvModelName = itemView.findViewById(R.id.tvModelName);
            tvModelInfo = itemView.findViewById(R.id.tvModelInfo);
            ivSelect = itemView.findViewById(R.id.ivSelect);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }
}

