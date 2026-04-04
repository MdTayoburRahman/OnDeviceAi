package com.droidrocks.ondeviceai.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.droidrocks.ondeviceai.R;
import com.droidrocks.ondeviceai.data.AvailableModel;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for displaying available models to download.
 */
public class AvailableModelsAdapter extends RecyclerView.Adapter<AvailableModelsAdapter.ViewHolder> {

    private final List<AvailableModel> models = new ArrayList<>();
    private OnDownloadClickListener listener;

    public interface OnDownloadClickListener {
        void onDownloadClick(AvailableModel model);
    }

    public void setOnDownloadClickListener(OnDownloadClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_available_model, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AvailableModel model = models.get(position);

        holder.tvModelName.setText(model.getName());
        holder.tvModelDescription.setText(model.getDescription() + " • " + model.getSize());

        // Show downloaded badge if already downloaded
        if (model.isDownloaded()) {
            holder.tvDownloaded.setVisibility(View.VISIBLE);
            holder.btnDownload.setEnabled(false);
            holder.btnDownload.setText(R.string.already_downloaded);
            holder.btnDownload.setIconResource(R.drawable.ic_check_circle);
            holder.ivModelIcon.setImageResource(R.drawable.ic_check_circle);
        } else {
            holder.tvDownloaded.setVisibility(View.GONE);
            holder.btnDownload.setEnabled(true);
            holder.btnDownload.setText(R.string.btn_download);
            holder.btnDownload.setIconResource(R.drawable.ic_download);
            holder.ivModelIcon.setImageResource(R.drawable.ic_cloud_download);
        }

        holder.btnDownload.setOnClickListener(v -> {
            if (listener != null && !model.isDownloaded()) {
                listener.onDownloadClick(model);
            }
        });

        holder.itemView.setOnClickListener(v -> {
            if (listener != null && !model.isDownloaded()) {
                listener.onDownloadClick(model);
            }
        });
    }

    @Override
    public int getItemCount() {
        return models.size();
    }

    public void setModels(List<AvailableModel> newModels) {
        models.clear();
        if (newModels != null) {
            models.addAll(newModels);
        }
        notifyDataSetChanged();
    }

    public void updateDownloadStatus(String filename, boolean downloaded) {
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).getFilename().equals(filename)) {
                models.get(i).setDownloaded(downloaded);
                notifyItemChanged(i);
                break;
            }
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvModelName;
        TextView tvModelDescription;
        TextView tvDownloaded;
        MaterialButton btnDownload;
        ImageView ivModelIcon;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvModelName = itemView.findViewById(R.id.tvModelName);
            tvModelDescription = itemView.findViewById(R.id.tvModelDescription);
            tvDownloaded = itemView.findViewById(R.id.tvDownloaded);
            btnDownload = itemView.findViewById(R.id.btnDownload);
            ivModelIcon = itemView.findViewById(R.id.ivModelIcon);
        }
    }
}

