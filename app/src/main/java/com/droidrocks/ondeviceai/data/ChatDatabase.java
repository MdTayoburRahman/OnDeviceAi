package com.droidrocks.ondeviceai.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Room database for storing chat history.
 */
@Database(entities = {ChatMessage.class}, version = 1, exportSchema = true)
public abstract class ChatDatabase extends RoomDatabase {

    private static final String DATABASE_NAME = "chat_database";
    private static volatile ChatDatabase INSTANCE;

    public abstract ChatMessageDao chatMessageDao();

    public static ChatDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ChatDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            ChatDatabase.class,
                            DATABASE_NAME
                    )
                    .fallbackToDestructiveMigration()
                    .build();
                }
            }
        }
        return INSTANCE;
    }
}

