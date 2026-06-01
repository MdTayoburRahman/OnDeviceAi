package com.droidrocks.ondeviceai.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.droidrocks.ondeviceai.R;
import com.droidrocks.ondeviceai.data.ChatSession;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for displaying chat session history.
 */
public class ChatHistoryAdapter extends RecyclerView.Adapter<ChatHistoryAdapter.ViewHolder> {

    private final List<ChatSession> sessions = new ArrayList<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault());
    private OnSessionClickListener listener;

    public interface OnSessionClickListener {
        void onSessionClick(ChatSession session);
        void onDeleteClick(ChatSession session);
        void onRenameClick(ChatSession session);
    }

    public void setOnSessionClickListener(OnSessionClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat_history, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ChatSession session = sessions.get(position);

        holder.tvPreview.setText(session.getPreview());
        holder.tvDate.setText(dateFormat.format(new Date(session.getTimestamp())));
        holder.tvMessageCount.setText(session.getMessageCount() + " messages");

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onSessionClick(session);
            }
        });

        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDeleteClick(session);
            }
        });

        if (holder.btnRename != null) {
            holder.btnRename.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onRenameClick(session);
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return sessions.size();
    }

    public void setSessions(List<ChatSession> newSessions) {
        sessions.clear();
        if (newSessions != null) {
            sessions.addAll(newSessions);
        }
        notifyDataSetChanged();
    }

    public void removeSession(ChatSession session) {
        int position = sessions.indexOf(session);
        if (position != -1) {
            sessions.remove(position);
            notifyItemRemoved(position);
        }
    }

    public void clearSessions() {
        sessions.clear();
        notifyDataSetChanged();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvPreview;
        TextView tvDate;
        TextView tvMessageCount;
        ImageButton btnDelete;
        ImageButton btnRename;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvPreview = itemView.findViewById(R.id.tvSessionPreview);
            tvDate = itemView.findViewById(R.id.tvSessionDate);
            tvMessageCount = itemView.findViewById(R.id.tvMessageCount);
            btnDelete = itemView.findViewById(R.id.btnDeleteSession);
            btnRename = itemView.findViewById(R.id.btnRenameSession);
        }
    }
}

