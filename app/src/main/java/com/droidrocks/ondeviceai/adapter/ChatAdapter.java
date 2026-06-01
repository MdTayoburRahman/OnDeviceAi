package com.droidrocks.ondeviceai.adapter;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

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
            AiMessageViewHolder aiHolder = (AiMessageViewHolder) holder;
            String content = messages.get(position).getContent();
            aiHolder.tvMessage.setText(content);
            aiHolder.btnCopy.setVisibility(content.endsWith("▍") ? View.GONE : View.VISIBLE);
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
            boolean streaming = message.getContent().endsWith("▍");
            aiHolder.btnCopy.setVisibility(streaming ? View.GONE : View.VISIBLE);
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
     * Searches backwards for the last non-user message to handle any ordering edge cases.
     * Uses a payload so RecyclerView skips the default cross-fade animation.
     * Must be called on the UI thread.
     */
    public void updateLastMessage(String newContent) {
        // Search backwards for the last AI (non-user) message
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (!msg.isUser()) {
                msg.setContent(newContent);
                notifyItemChanged(i, "streaming");
                return;
            }
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
        ImageView btnCopy;

        AiMessageViewHolder(@NonNull View itemView) {
            super(itemView);
            tvMessage = itemView.findViewById(R.id.tvAiMessage);
            tvTime = itemView.findViewById(R.id.tvAiTime);
            btnCopy = itemView.findViewById(R.id.btnCopyAi);
            btnCopy.setOnClickListener(v -> {
                String text = tvMessage.getText().toString();
                if (!text.isEmpty()) {
                    ClipboardManager cm = (ClipboardManager) itemView.getContext()
                            .getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("AI response", text));
                    Toast.makeText(itemView.getContext(), "Copied", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
}

