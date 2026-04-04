package com.droidrocks.ondeviceai.data;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/**
 * Entity representing a single chat message in the conversation.
 */
@Entity(tableName = "chat_messages")
public class ChatMessage {

    @PrimaryKey(autoGenerate = true)
    private long id;

    private String content;
    private boolean isUser; // true = user message, false = AI response
    private long timestamp;
    private String sessionId; // to group messages by conversation session

    // No-arg constructor for Room
    public ChatMessage() {
        this.timestamp = System.currentTimeMillis();
    }

    // Constructor for app use - ignored by Room
    @Ignore
    public ChatMessage(String content, boolean isUser, String sessionId) {
        this.content = content;
        this.isUser = isUser;
        this.sessionId = sessionId;
        this.timestamp = System.currentTimeMillis();
    }

    // Getters and Setters
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isUser() {
        return isUser;
    }

    public void setUser(boolean user) {
        isUser = user;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}

