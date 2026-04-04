package com.droidrocks.ondeviceai.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * Data Access Object for ChatMessage entity.
 */
@Dao
public interface ChatMessageDao {
    
    @Insert
    long insert(ChatMessage message);
    
    @Insert
    void insertAll(List<ChatMessage> messages);
    
    @Update
    void update(ChatMessage message);
    
    @Delete
    void delete(ChatMessage message);
    
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    List<ChatMessage> getAllMessages();
    
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    List<ChatMessage> getMessagesBySession(String sessionId);
    
    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC LIMIT :limit")
    List<ChatMessage> getRecentMessages(int limit);
    
    @Query("SELECT DISTINCT sessionId FROM chat_messages ORDER BY timestamp DESC")
    List<String> getAllSessions();
    
    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    void deleteSession(String sessionId);
    
    @Query("DELETE FROM chat_messages")
    void deleteAll();
    
    @Query("SELECT COUNT(*) FROM chat_messages")
    int getMessageCount();
    
    @Query("SELECT * FROM chat_messages WHERE id = :id")
    ChatMessage getMessageById(long id);
    
    @Query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId")
    int getMessageCountBySession(String sessionId);
    
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId AND isUser = 1 ORDER BY timestamp ASC LIMIT 1")
    ChatMessage getFirstUserMessageBySession(String sessionId);
    
    @Query("SELECT MIN(timestamp) FROM chat_messages WHERE sessionId = :sessionId")
    long getSessionStartTime(String sessionId);
}
