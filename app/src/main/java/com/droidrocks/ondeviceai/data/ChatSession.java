package com.droidrocks.ondeviceai.data;

import java.util.List;

/**
 * Represents a chat session with summary info for display in history list.
 */
public class ChatSession {
    private String sessionId;
    private String firstMessage;
    private long timestamp;
    private int messageCount;
    private String alias;

    public ChatSession(String sessionId, String firstMessage, long timestamp, int messageCount) {
        this.sessionId = sessionId;
        this.firstMessage = firstMessage;
        this.timestamp = timestamp;
        this.messageCount = messageCount;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getFirstMessage() {
        return firstMessage;
    }

    public void setFirstMessage(String firstMessage) {
        this.firstMessage = firstMessage;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    /**
     * Get a preview of the first message (truncated if too long), or alias if set.
     */
    public String getPreview() {
        if (alias != null && !alias.trim().isEmpty()) {
            return alias;
        }
        if (firstMessage == null || firstMessage.isEmpty()) {
            return "Empty conversation";
        }
        String preview = firstMessage.replace("\n", " ").trim();
        if (preview.length() > 80) {
            return preview.substring(0, 77) + "...";
        }
        return preview;
    }
}

