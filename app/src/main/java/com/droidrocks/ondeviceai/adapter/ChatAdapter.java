package com.droidrocks.ondeviceai.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.droidrocks.ondeviceai.R;
import com.droidrocks.ondeviceai.data.ChatMessage;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for displaying chat messages.
 */
public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_USER = 1;
    private static final int VIEW_TYPE_AI = 2;

    private final List<ChatMessage> messages = new ArrayList<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).isUser() ? VIEW_TYPE_USER : VIEW_TYPE_AI;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_USER) {
            View view = inflater.inflate(R.layout.item_chat_user, parent, false);
            return new UserMessageViewHolder(view);
        } else {
            View view = inflater.inflate(R.layout.item_chat_ai, parent, false);
            return new AiMessageViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && holder instanceof AiMessageViewHolder) {
            // Partial bind for streaming updates — just set the text, skip full rebind
            ((AiMessageViewHolder) holder).tvMessage.setText(messages.get(position).getContent());
            return;
        }
        onBindViewHolder(holder, position);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage message = messages.get(position);
        String time = timeFormat.format(new Date(message.getTimestamp()));

        if (holder instanceof UserMessageViewHolder) {
            UserMessageViewHolder userHolder = (UserMessageViewHolder) holder;
            userHolder.tvMessage.setText(message.getContent());
            userHolder.tvTime.setText(time);
        } else if (holder instanceof AiMessageViewHolder) {
            AiMessageViewHolder aiHolder = (AiMessageViewHolder) holder;
            aiHolder.tvMessage.setText(message.getContent());
            aiHolder.tvTime.setText(time);
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    public void setMessages(List<ChatMessage> newMessages) {
        messages.clear();
        if (newMessages != null) {
            messages.addAll(newMessages);
        }
        notifyDataSetChanged();
    }

    public void addMessage(ChatMessage message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    public void clearMessages() {
        messages.clear();
        notifyDataSetChanged();
    }

    /**
     * Update the content of the last AI message in-place (used for streaming tokens).
     * Uses a payload so RecyclerView skips the default cross-fade animation.
     * Must be called on the UI thread.
     */
    public void updateLastMessage(String newContent) {
        if (messages.isEmpty()) return;
        int lastIdx = messages.size() - 1;
        ChatMessage last = messages.get(lastIdx);
        if (!last.isUser()) {
            last.setContent(newContent);
            notifyItemChanged(lastIdx, "streaming");
        }
    }

    public List<ChatMessage> getMessages() {
        return new ArrayList<>(messages);
    }

    // ViewHolder for user messages
    static class UserMessageViewHolder extends RecyclerView.ViewHolder {
        TextView tvMessage;
        TextView tvTime;

        UserMessageViewHolder(@NonNull View itemView) {
            super(itemView);
            tvMessage = itemView.findViewById(R.id.tvUserMessage);
            tvTime = itemView.findViewById(R.id.tvUserTime);
        }
    }

    // ViewHolder for AI messages
    static class AiMessageViewHolder extends RecyclerView.ViewHolder {
        TextView tvMessage;
        TextView tvTime;

        AiMessageViewHolder(@NonNull View itemView) {
            super(itemView);
            tvMessage = itemView.findViewById(R.id.tvAiMessage);
            tvTime = itemView.findViewById(R.id.tvAiTime);
        }
    }
}

