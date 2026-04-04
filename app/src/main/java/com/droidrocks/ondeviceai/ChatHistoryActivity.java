package com.droidrocks.ondeviceai;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.droidrocks.ondeviceai.adapter.ChatHistoryAdapter;
import com.droidrocks.ondeviceai.data.ChatRepository;
import com.droidrocks.ondeviceai.data.ChatSession;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.util.List;

public class ChatHistoryActivity extends BaseActivity {

    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_NEW_CHAT = "new_chat";
    public static final int RESULT_LOAD_SESSION = 100;
    public static final int RESULT_NEW_CHAT = 101;

    private RecyclerView rvChatHistory;
    private LinearLayout emptyState;
    private ChatHistoryAdapter adapter;
    private ChatRepository chatRepository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_history);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.headerSection).getRootView(), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        chatRepository = new ChatRepository(this);

        initViews();
        loadChatHistory();
    }

    private void initViews() {
        rvChatHistory = findViewById(R.id.rvChatHistory);
        emptyState = findViewById(R.id.emptyState);

        ImageButton btnBack = findViewById(R.id.btnBack);
        MaterialButton btnClearAll = findViewById(R.id.btnClearAll);
        ExtendedFloatingActionButton btnNewChat = findViewById(R.id.btnNewChat);

        // Setup RecyclerView
        adapter = new ChatHistoryAdapter();
        rvChatHistory.setLayoutManager(new LinearLayoutManager(this));
        rvChatHistory.setAdapter(adapter);

        adapter.setOnSessionClickListener(new ChatHistoryAdapter.OnSessionClickListener() {
            @Override
            public void onSessionClick(ChatSession session) {
                loadSession(session);
            }

            @Override
            public void onDeleteClick(ChatSession session) {
                confirmDeleteSession(session);
            }
        });

        btnBack.setOnClickListener(v -> finish());

        btnClearAll.setOnClickListener(v -> confirmClearAll());

        btnNewChat.setOnClickListener(v -> startNewChat());
    }

    private void loadChatHistory() {
        chatRepository.getAllChatSessions(sessions -> {
            updateUI(sessions);
        });
    }

    private void updateUI(List<ChatSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            rvChatHistory.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            rvChatHistory.setVisibility(View.VISIBLE);
            adapter.setSessions(sessions);
        }
    }

    private void loadSession(ChatSession session) {
        Intent result = new Intent();
        result.putExtra(EXTRA_SESSION_ID, session.getSessionId());
        setResult(RESULT_LOAD_SESSION, result);
        finish();
    }

    private void startNewChat() {
        Intent result = new Intent();
        result.putExtra(EXTRA_NEW_CHAT, true);
        setResult(RESULT_NEW_CHAT, result);
        finish();
    }

    private void confirmDeleteSession(ChatSession session) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.btn_delete_session)
            .setMessage(R.string.confirm_delete_session)
            .setPositiveButton(R.string.yes, (dialog, which) -> deleteSession(session))
            .setNegativeButton(R.string.no, null)
            .show();
    }

    private void deleteSession(ChatSession session) {
        chatRepository.deleteSession(session.getSessionId(), () -> {
            adapter.removeSession(session);
            Toast.makeText(this, R.string.session_deleted, Toast.LENGTH_SHORT).show();

            // Check if list is now empty
            if (adapter.getItemCount() == 0) {
                emptyState.setVisibility(View.VISIBLE);
                rvChatHistory.setVisibility(View.GONE);
            }
        });
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.btn_clear_all)
            .setMessage(R.string.confirm_clear_all_history)
            .setPositiveButton(R.string.yes, (dialog, which) -> clearAllHistory())
            .setNegativeButton(R.string.no, null)
            .show();
    }

    private void clearAllHistory() {
        chatRepository.deleteAllMessages(() -> {
            adapter.clearSessions();
            emptyState.setVisibility(View.VISIBLE);
            rvChatHistory.setVisibility(View.GONE);
            Toast.makeText(this, R.string.all_history_deleted, Toast.LENGTH_SHORT).show();
        });
    }
}

