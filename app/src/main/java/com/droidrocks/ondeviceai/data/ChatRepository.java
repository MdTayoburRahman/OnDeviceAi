package com.droidrocks.ondeviceai.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Repository for managing chat message data operations.
 * Provides async methods for database operations.
 */
public class ChatRepository {

    private final ChatMessageDao chatMessageDao;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public ChatRepository(Context context) {
        ChatDatabase database = ChatDatabase.getInstance(context);
        this.chatMessageDao = database.chatMessageDao();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    // Callback interfaces
    public interface OnMessagesLoadedCallback {
        void onMessagesLoaded(List<ChatMessage> messages);
    }

    public interface OnMessageInsertedCallback {
        void onMessageInserted(long messageId);
    }

    public interface OnOperationCompleteCallback {
        void onComplete();
    }

    public interface OnChatSessionsLoadedCallback {
        void onSessionsLoaded(List<ChatSession> sessions);
    }

    // Async insert
    public void insertMessage(ChatMessage message, OnMessageInsertedCallback callback) {
        executor.execute(() -> {
            long id = chatMessageDao.insert(message);
            if (callback != null) {
                mainHandler.post(() -> callback.onMessageInserted(id));
            }
        });
    }

    // Sync insert (for use within executor)
    public long insertMessageSync(ChatMessage message) {
        return chatMessageDao.insert(message);
    }

    // Get all messages async
    public void getAllMessages(OnMessagesLoadedCallback callback) {
        executor.execute(() -> {
            List<ChatMessage> messages = chatMessageDao.getAllMessages();
            mainHandler.post(() -> callback.onMessagesLoaded(messages));
        });
    }

    // Get messages by session async
    public void getMessagesBySession(String sessionId, OnMessagesLoadedCallback callback) {
        executor.execute(() -> {
            List<ChatMessage> messages = chatMessageDao.getMessagesBySession(sessionId);
            mainHandler.post(() -> callback.onMessagesLoaded(messages));
        });
    }

    // Get recent messages async
    public void getRecentMessages(int limit, OnMessagesLoadedCallback callback) {
        executor.execute(() -> {
            List<ChatMessage> messages = chatMessageDao.getRecentMessages(limit);
            mainHandler.post(() -> callback.onMessagesLoaded(messages));
        });
    }

    // Get all sessions async
    public void getAllSessions(OnSessionsLoadedCallback callback) {
        executor.execute(() -> {
            List<String> sessions = chatMessageDao.getAllSessions();
            mainHandler.post(() -> callback.onSessionsLoaded(sessions));
        });
    }

    public interface OnSessionsLoadedCallback {
        void onSessionsLoaded(List<String> sessions);
    }

    // Get all chat sessions with details async
    public void getAllChatSessions(OnChatSessionsLoadedCallback callback) {
        executor.execute(() -> {
            List<String> sessionIds = chatMessageDao.getAllSessions();
            List<ChatSession> sessions = new ArrayList<>();

            for (String sessionId : sessionIds) {
                ChatMessage firstMessage = chatMessageDao.getFirstUserMessageBySession(sessionId);
                int messageCount = chatMessageDao.getMessageCountBySession(sessionId);
                long timestamp = chatMessageDao.getSessionStartTime(sessionId);

                String preview = firstMessage != null ? firstMessage.getContent() : "";
                sessions.add(new ChatSession(sessionId, preview, timestamp, messageCount));
            }

            mainHandler.post(() -> callback.onSessionsLoaded(sessions));
        });
    }

    // Delete session async
    public void deleteSession(String sessionId, OnOperationCompleteCallback callback) {
        executor.execute(() -> {
            chatMessageDao.deleteSession(sessionId);
            if (callback != null) {
                mainHandler.post(callback::onComplete);
            }
        });
    }

    // Delete all messages async
    public void deleteAllMessages(OnOperationCompleteCallback callback) {
        executor.execute(() -> {
            chatMessageDao.deleteAll();
            if (callback != null) {
                mainHandler.post(callback::onComplete);
            }
        });
    }

    // Update message async
    public void updateMessage(ChatMessage message, OnOperationCompleteCallback callback) {
        executor.execute(() -> {
            chatMessageDao.update(message);
            if (callback != null) {
                mainHandler.post(callback::onComplete);
            }
        });
    }

    // Delete message async
    public void deleteMessage(ChatMessage message, OnOperationCompleteCallback callback) {
        executor.execute(() -> {
            chatMessageDao.delete(message);
            if (callback != null) {
                mainHandler.post(callback::onComplete);
            }
        });
    }
}

