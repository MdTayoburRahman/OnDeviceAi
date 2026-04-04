package com.droidrocks.ondeviceai;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.droidrocks.ondeviceai.Utils.CpuMonitor;
import com.droidrocks.ondeviceai.Utils.GpuInfo;
import com.droidrocks.ondeviceai.Utils.RamMonitor;
import com.droidrocks.ondeviceai.adapter.ChatAdapter;
import com.droidrocks.ondeviceai.data.ChatMessage;
import com.droidrocks.ondeviceai.data.ChatRepository;
import android.widget.ScrollView;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_MODEL_LIST = 1001;
    private static final int REQUEST_CHAT_HISTORY = 1002;

    private EditText etPrompt;
    private TextView tvOutput;
    private TextView tvModelPath;

    private Button btnGenerate;
    private Button btnStop;
    private Button btnModels;
    private Button btnClearChat;
    private Button btnHistory;
    private TextView tvGenTimer;
    private TextView tvSysUsage;
    private TextView tvRamUsage;

    private TextView tvGPU;
    private TextView tvLog;
    private ScrollView scrollLog;
    
    // Chat UI components
    private RecyclerView rvChat;
    private ChatAdapter chatAdapter;
    private ChatRepository chatRepository;
    private String currentSessionId;

    private LlamaBridge llamaBridge = null;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Generation monitoring
    private volatile boolean generating = false;
    private long genStartMs = 0;
    // track current generation task so we can cancel it
    private volatile Future<?> currentGenFuture = null;
    private volatile boolean stopRequested = false;
    private CpuMonitor cpuMonitor;
    private RamMonitor ramMonitor;


    // Temporary in-memory conversation history (keeps last N turns)
    private final java.util.Deque<String> convoHistory = new java.util.ArrayDeque<>();
    private static final int MAX_HISTORY_ENTRIES = 6; // tune as needed
    private static final int MAX_PROMPT_CHARS = 1024; // truncate combined prompt to this many chars (from the end)
    private volatile boolean isModelLoaded = false;
    private File modelFile;
    private boolean fastMode = false; // when true use greedy sampling for lower latency
    // whether we've already sent the initial system prompt and earlier conversation to the native context
    private boolean contextPrimed = false;
    // one-time system prompt to prime the model/context when first sending text
    private static final String SYSTEM_PROMPT = "<|system|>\nYou are a helpful assistant. Answer concisely.\n<|end|>\n";
    private GpuInfo gpuInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Handle window insets for both system bars and IME (keyboard)
        View mainView = findViewById(R.id.main);
        View inputContainer = findViewById(R.id.inputContainer);

        ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());

            // Apply top padding for status bar
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);

            // Apply bottom padding/margin for keyboard - use the larger of system bars or IME
            int bottomInset = Math.max(systemBars.bottom, ime.bottom);
            inputContainer.setPadding(
                inputContainer.getPaddingLeft(),
                inputContainer.getPaddingTop(),
                inputContainer.getPaddingRight(),
                bottomInset > 0 ? bottomInset : (int) (12 * getResources().getDisplayMetrics().density)
            );

            return insets;
        });

        // Initialize chat repository and session
        chatRepository = new ChatRepository(this);
        currentSessionId = UUID.randomUUID().toString();

        etPrompt = findViewById(R.id.etPrompt);
        tvOutput = findViewById(R.id.tvOutput);
        tvModelPath = findViewById(R.id.tvModelPath);
        tvGenTimer = findViewById(R.id.tvGenTimer);
        tvSysUsage = findViewById(R.id.tvSysUsage);
        btnModels = findViewById(R.id.btnModels);
        tvRamUsage = findViewById(R.id.tvRamUsage);
        btnGenerate = findViewById(R.id.btnGenerate);
        tvGPU = findViewById(R.id.tvGpuUsage);
        btnClearChat = findViewById(R.id.btnClearChat);
        btnHistory = findViewById(R.id.btnHistory);

        // Setup chat RecyclerView
        rvChat = findViewById(R.id.rvChat);
        setupChatRecyclerView();
        
        // Load chat history
        loadChatHistory();
        
        // find stop button if present in layout
        btnStop = findViewById(R.id.btnStop);
        if (btnStop == null) {
            // not critical if we cannot create it programmatically; just continue
            try {
                android.view.View parent = (android.view.View) btnGenerate.getParent();
                if (parent instanceof android.view.ViewGroup) {
                    android.widget.Button b = new android.widget.Button(this);
                    b.setText("Stop");
                    b.setId(android.view.View.generateViewId());
                    btnStop = b;
                    android.view.ViewGroup vg = (android.view.ViewGroup) parent;
                    int idx = vg.indexOfChild(btnGenerate);
                    if (idx >= 0 && idx < vg.getChildCount()) vg.addView(btnStop, idx + 1);
                    else vg.addView(btnStop);
                }
            } catch (Throwable ignored) {
                btnStop = null;
            }
        }


        // If any model files exist in the models directory, load the first one (index 0).
        File[] available = ModelUtils.listAvailableModels(this);
        if (available != null && available.length > 0) {
            modelFile = available[0];
            String initialModelLabel = ModelUtils.getFriendlyModelName(modelFile.getAbsolutePath());
            tvModelPath.setText(initialModelLabel);
            Log.i(TAG, "onCreate: found models, loading first: " + modelFile.getAbsolutePath());
        } else {
            modelFile = null;
            tvModelPath.setText("(no model)");
            Log.i(TAG, "onCreate: no model files found in models directory");
        }

        // Start loading model automatically in background if we found one
        if (btnStop != null) {
            btnStop.setVisibility(View.GONE);
            btnStop.setOnClickListener(v -> stopGeneration());
        }

        btnModels.setOnClickListener(v -> {
            try {
                startActivityForResult(new Intent(MainActivity.this, ModelListActivity.class), REQUEST_MODEL_LIST);
            } catch (Exception ex) {
                Log.e(TAG, "Failed to open ModelListActivity", ex);
                toast("Cannot open models list");
            }
        });

        // allow tapping the model label to change model as well
        tvModelPath.setOnClickListener(v -> {
            try {
                startActivityForResult(new Intent(MainActivity.this, ModelListActivity.class), REQUEST_MODEL_LIST);
            } catch (Exception ex) {
                Log.e(TAG, "Failed to open ModelListActivity from model label", ex);
                toast("Cannot open models list");
            }
        });

        if (btnGenerate != null) {
            btnGenerate.setOnClickListener(v -> generateText());
        }
        
        // Clear chat button
        if (btnClearChat != null) {
            btnClearChat.setOnClickListener(v -> showClearChatDialog());
        }

        // Chat history button
        if (btnHistory != null) {
            btnHistory.setOnClickListener(v -> openChatHistory());
        }

        // If a model was found earlier, attempt to load it
        if (modelFile != null) {
            loadModel();
        }
        
        monitorCpu();
        monitorRAM();
        monitorGPU();
        // bind runtime log view for live AI/native logs
        try {
            scrollLog = findViewById(R.id.scrollLog);
            tvLog = findViewById(R.id.tvLog);
            if (tvLog != null && scrollLog != null) {
                RuntimeLog.bind(tvLog, scrollLog);
                RuntimeLog.append("Log pane initialized");
            }
        } catch (Throwable ignored) {
        }
    }
    
    private void openChatHistory() {
        try {
            startActivityForResult(new Intent(this, ChatHistoryActivity.class), REQUEST_CHAT_HISTORY);
        } catch (Exception ex) {
            Log.e(TAG, "Failed to open ChatHistoryActivity", ex);
            toast("Cannot open chat history");
        }
    }

    private void setupChatRecyclerView() {
        chatAdapter = new ChatAdapter();
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        rvChat.setLayoutManager(layoutManager);
        rvChat.setAdapter(chatAdapter);
    }

    private void loadChatHistory() {
        chatRepository.getAllMessages(messages -> {
            if (messages != null && !messages.isEmpty()) {
                chatAdapter.setMessages(messages);
                scrollToBottom();
                
                // Get the session ID from the most recent message
                ChatMessage lastMessage = messages.get(messages.size() - 1);
                if (lastMessage.getSessionId() != null && !lastMessage.getSessionId().isEmpty()) {
                    currentSessionId = lastMessage.getSessionId();
                }

                // Rebuild conversation history for context
                convoHistory.clear();
                for (ChatMessage msg : messages) {
                    if (msg.isUser()) {
                        convoHistory.addLast(ModelUtils.formatPrompt(msg.getContent()));
                    } else {
                        // Append AI response to last entry if exists
                        if (!convoHistory.isEmpty()) {
                            String last = convoHistory.removeLast();
                            convoHistory.addLast(last + msg.getContent() + "\n");
                        }
                    }
                }
                // Trim to max entries
                while (convoHistory.size() > MAX_HISTORY_ENTRIES) {
                    convoHistory.removeFirst();
                }
            }
        });
    }
    
    private void loadSession(String sessionId) {
        currentSessionId = sessionId;
        contextPrimed = false;

        chatRepository.getMessagesBySession(sessionId, messages -> {
            chatAdapter.setMessages(messages);
            scrollToBottom();

            // Rebuild conversation history for context
            convoHistory.clear();
            if (messages != null) {
                for (ChatMessage msg : messages) {
                    if (msg.isUser()) {
                        convoHistory.addLast(ModelUtils.formatPrompt(msg.getContent()));
                    } else {
                        if (!convoHistory.isEmpty()) {
                            String last = convoHistory.removeLast();
                            convoHistory.addLast(last + msg.getContent() + "\n");
                        }
                    }
                }
                while (convoHistory.size() > MAX_HISTORY_ENTRIES) {
                    convoHistory.removeFirst();
                }
            }
        });
    }

    private void startNewChat() {
        currentSessionId = UUID.randomUUID().toString();
        contextPrimed = false;
        convoHistory.clear();
        chatAdapter.clearMessages();
    }

    private void scrollToBottom() {
        if (rvChat != null && chatAdapter.getItemCount() > 0) {
            rvChat.smoothScrollToPosition(chatAdapter.getItemCount() - 1);
        }
    }

    private void showClearChatDialog() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.btn_clear_chat)
            .setMessage(R.string.confirm_clear_chat)
            .setPositiveButton(R.string.yes, (dialog, which) -> clearChatHistory())
            .setNegativeButton(R.string.no, null)
            .show();
    }

    private void clearChatHistory() {
        chatRepository.deleteAllMessages(() -> {
            chatAdapter.clearMessages();
            convoHistory.clear();
            contextPrimed = false;
            currentSessionId = UUID.randomUUID().toString();
            toast(getString(R.string.chat_cleared));
        });
    }

    private void addMessageToChat(String content, boolean isUser) {
        ChatMessage message = new ChatMessage(content, isUser, currentSessionId);
        chatRepository.insertMessage(message, messageId -> {
            message.setId(messageId);
            chatAdapter.addMessage(message);
            scrollToBottom();
        });
    }

    private void monitorGPU() {
        GpuInfo.Info info = GpuInfo.getGpuInfo();
        StringBuilder sb = new StringBuilder();
        if (info != null) {
            sb.append("GL: ").append(info.renderer == null ? "<unknown>" : info.renderer);
            sb.append(" (").append(info.vendor == null ? "vendor?" : info.vendor).append(")\n");
            sb.append("GL ver: ").append(info.version == null ? "?" : info.version);
            Log.i("MainActivity", "GPU vendor: " + info.vendor + " renderer: " + info.renderer + " version: " + info.version);
        } else {
            Log.w("MainActivity", "GPU detection failed");
            sb.append("GPU info not available\n");
        }

        // If native library is present, probe the Vulkan/ggml info exposed by native JNI
        try {
            if (LlamaBridge.isNativeLoaded()) {
                if (llamaBridge == null) llamaBridge = new LlamaBridge();
                try {
                    boolean vk = llamaBridge.isVulkanAvailable();
                    sb.append("Vulkan: ").append(vk ? "available" : "not available");
                    if (vk) {
                        String dev = llamaBridge.getVulkanDeviceName();
                        long mem = llamaBridge.getVulkanDeviceLocalMemory();
                        sb.append("\nDevice: ").append(dev == null ? "<unknown>" : dev);
                        sb.append("\nLocal memory: ").append(mem > 0 ? (mem / (1024 * 1024)) + " MB" : "unknown");
                        Log.i(TAG, "Vulkan available on device: " + dev + " mem=" + mem);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to query native Vulkan info", t);
                    sb.append("\nVulkan probe failed");
                }
            } else {
                sb.append("\nNative library not loaded");
            }
        } catch (Throwable ignored) {
            // best-effort only
        }

        tvGPU.setText(sb.toString());

    }

    private void monitorRAM() {
       ramMonitor =  new RamMonitor(this, 1000, new RamMonitor.OnRamUpdateListener() {
            @Override
            public void onUpdate(RamMonitor.RamInfo info) {
                tvRamUsage.setText(info.toDisplayString());
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_MODEL_LIST && resultCode == RESULT_OK && data != null) {
            String path = data.getStringExtra("modelPath");
            if (path != null && !path.isEmpty()) {
                modelFile = new File(path);
                tvModelPath.setText(ModelUtils.getFriendlyModelName(modelFile.getAbsolutePath()));
                // reload the selected model automatically
                loadModel();
            }
        } else if (requestCode == REQUEST_CHAT_HISTORY) {
            if (resultCode == ChatHistoryActivity.RESULT_LOAD_SESSION && data != null) {
                String sessionId = data.getStringExtra(ChatHistoryActivity.EXTRA_SESSION_ID);
                if (sessionId != null && !sessionId.isEmpty()) {
                    loadSession(sessionId);
                }
            } else if (resultCode == ChatHistoryActivity.RESULT_NEW_CHAT) {
                startNewChat();
            }
        }
    }

    private void loadModel() {
        setBusy(true);
        // Show loading in chat
        Log.i(TAG, "loadModel: starting");
        RuntimeLog.append("loadModel: starting");

        // If the model file is missing, send the user to the ModelListActivity
        if (modelFile == null || !modelFile.exists() || modelFile.length() == 0) {
            Log.i(TAG, "Model file missing; prompting user to select or download a model");
            mainHandler.post(() -> {
                try {
                    setBusy(false);
                    startActivityForResult(new Intent(MainActivity.this, ModelListActivity.class), REQUEST_MODEL_LIST);
                } catch (Exception ex) {
                    Log.e(TAG, "Failed to launch ModelListActivity", ex);
                    toast("Cannot open models list");
                }
            });
            return;
        }

        executor.execute(() -> {
            boolean loaded = false;
            try {
                if (modelFile == null || !modelFile.exists() || modelFile.length() == 0) {
                    Log.i(TAG, "Model file missing in background load; skipping automatic download (handled by ModelListActivity)");
                    loaded = false;
                } else {
                    Log.i(TAG, "Model file present; calling native loadModel: " + modelFile.getAbsolutePath());
                    RuntimeLog.append("Model file present; loading: " + modelFile.getName());
                    if (LlamaBridge.isNativeLoaded()) {
                        if (llamaBridge == null) llamaBridge = new LlamaBridge();
                        try {
                            // If Vulkan is available according to the native probe, try initializing the ggml Vulkan backend
                            try {
                                if (llamaBridge.isVulkanAvailable()) {
                                    Log.i(TAG, "Native reports Vulkan available — initializing Vulkan backend");
                                    RuntimeLog.append("Native reports Vulkan available — initVulkanBackend()");
                                    boolean inited = llamaBridge.initVulkanBackend();
                                    Log.i(TAG, "initVulkanBackend returned " + inited);
                                    RuntimeLog.append("initVulkanBackend returned " + inited);
                                } else {
                                    Log.i(TAG, "Native reports Vulkan unavailable");
                                    RuntimeLog.append("Vulkan not available");
                                }
                            } catch (Throwable t2) {
                                Log.w(TAG, "Vulkan init probe failed", t2);
                                RuntimeLog.append("Vulkan init probe failed: " + t2.getMessage());
                            }

                            int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
                            loaded = llamaBridge.loadModel(modelFile.getAbsolutePath(), 1024, threads);
                            Log.i(TAG, "Native loadModel returned " + loaded);
                            RuntimeLog.append("Native loadModel returned " + loaded);
                        } catch (Throwable t) {
                            Log.e(TAG, "Native loadModel failed", t);
                            RuntimeLog.append("Native loadModel failed: " + t.getMessage());
                            loaded = false;
                        }
                    } else {
                        Log.e(TAG, "Native library not loaded; cannot load model via JNI");
                        loaded = false;
                    }
                }
            } catch (Throwable t) {
                loaded = false;
                Log.e(TAG, "Exception in loadModel background task", t);
            }

            final boolean result = loaded;
            mainHandler.post(() -> {
                isModelLoaded = result;
                contextPrimed = false;
                setBusy(false);
                if (result) {
                    toast("Model loaded");
                    RuntimeLog.append("Model loaded successfully");
                } else {
                    if (modelFile == null || !modelFile.exists() || modelFile.length() == 0) {
                        toast("Model file not found");
                        RuntimeLog.append("Model file not found in expected path");
                    } else {
                        toast("Load failed");
                        RuntimeLog.append("Failed to load model");
                    }
                }
            });
        });
    }

    private void monitorCpu(){
        cpuMonitor = new CpuMonitor(1000, new CpuMonitor.OnCpuUpdateListener() {
            @Override
            public void onUpdate(CpuMonitor.CpuInfo info) {
                tvSysUsage.setText(info.toDisplayString());
            }
        });

    }

    private void generateText() {
        String input = etPrompt.getText().toString().trim();
        etPrompt.setText("");
        Log.i(TAG, "generateText: prompt length=" + input.length());

        if (!isModelLoaded) {
            toast("Load the model first.");
            return;
        }

        if (input.isEmpty()) {
            toast("Please enter a prompt.");
            return;
        }

        // Add user message to chat UI and database
        addMessageToChat(input, true);

        String formattedPrompt = ModelUtils.formatPrompt(input);
        setBusy(true);

        // start timer/UI monitor
        mainHandler.post(this::startGenerationMonitor);

        currentGenFuture = executor.submit(() -> {
            String result = "";
            try {
                Log.i(TAG, "Calling native generate");
                RuntimeLog.append("Calling native generate");
                if (!LlamaBridge.isNativeLoaded()) {
                    throw new IllegalStateException("Native library not loaded");
                }
                if (llamaBridge == null) llamaBridge = new LlamaBridge();


                String toSend;
                if (!contextPrimed) {
                    StringBuilder combined = new StringBuilder();
                    combined.append(SYSTEM_PROMPT);
                    synchronized (convoHistory) {
                        for (String h : convoHistory) combined.append(h);
                    }
                    combined.append(formattedPrompt);
                    if (combined.length() > MAX_PROMPT_CHARS) {
                        int available = Math.max(0, MAX_PROMPT_CHARS - SYSTEM_PROMPT.length());
                        String rest = combined.substring(SYSTEM_PROMPT.length());
                        String tail = rest.length() > available ? rest.substring(rest.length() - available) : rest;
                        combined = new StringBuilder();
                        combined.append(SYSTEM_PROMPT);
                        combined.append(tail);
                    }
                    toSend = combined.toString();
                } else {
                    toSend = formattedPrompt;
                }

                result = llamaBridge.generate(toSend, 200, fastMode ? 0.0f : 0.7f, fastMode ? 1.0f : 0.9f);
                Log.i(TAG, "Native generate returned length=" + (result != null ? result.length() : 0));
                RuntimeLog.append("Native generate returned length=" + (result != null ? result.length() : 0));
            } catch (Throwable t) {
                result = "Error: " + t.getMessage();
                Log.e(TAG, "Exception during generate", t);
            } finally {
                currentGenFuture = null;
                stopRequested = false;
            }

            final String finalResult = result != null ? result : "";
            mainHandler.post(() -> {
                // stop timer/UI monitor for generation
                stopGenerationMonitor();

                setBusy(false);

                String cleaned = finalResult.replace('\u2581', ' ').replace("_", " ").replace("<0x0A>", "\n");

                cleaned = cleaned.replaceAll("[ \t]{2,}", " ");
                cleaned = cleaned.replaceAll("(\\n\\s*){2,}", "\\n\\n");
                cleaned = cleaned.trim();

                try {
                    String historyEntry = formattedPrompt + cleaned + "\n";
                    synchronized (convoHistory) {
                        convoHistory.addLast(historyEntry);
                        while (convoHistory.size() > MAX_HISTORY_ENTRIES)
                            convoHistory.removeFirst();
                    }
                } catch (Exception ignored) {
                }

                if (!finalResult.startsWith("Error:") && !finalResult.isEmpty()) {
                    contextPrimed = true;
                }

                // Add AI response to chat UI and database
                addMessageToChat(cleaned, false);
                
                RuntimeLog.append("Generation finished; output length=" + cleaned.length());
            });
        });
    }

    private void setBusy(boolean busy) {
        if (btnGenerate != null) btnGenerate.setEnabled(!busy);
        if (btnStop != null) {
            btnStop.setVisibility(busy ? View.VISIBLE : View.GONE);
            btnStop.setEnabled(busy);
        }
    }

    // Simple generation monitor helpers (keeps basic flags and timers).
    // Implement more detailed monitoring if needed (CPU/RAM/threads).
    // Runnable used to update the generation timer on the UI thread
    private final Runnable genTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!generating) return;
            long elapsed = System.currentTimeMillis() - genStartMs;
            long totalSeconds = elapsed / 1000;
            long minutes = totalSeconds / 60;
            long seconds = totalSeconds % 60;
            String text = String.format("%02d:%02d", minutes, seconds);
            if (tvGenTimer != null) tvGenTimer.setText(text);
            // update twice a second
            mainHandler.postDelayed(this, 500);
        }
    };

    private void startGenerationMonitor() {
        generating = true;
        genStartMs = System.currentTimeMillis();
        // start UI updates
        if (tvGenTimer != null) tvGenTimer.setText("00:00");
        mainHandler.post(genTimerRunnable);
    }

    private void stopGenerationMonitor() {
        generating = false;
        mainHandler.removeCallbacks(genTimerRunnable);
        if (tvGenTimer != null) tvGenTimer.setText("");
        genStartMs = 0;
    }


    private void stopGeneration() {
        if (!generating && currentGenFuture == null) {
            toast("No generation in progress");
            return;
        }
        Log.i(TAG, "stopGeneration: user requested stop");
        stopRequested = true;
        try {
            if (llamaBridge != null && LlamaBridge.isNativeLoaded()) {
                llamaBridge.interruptGeneration();
                Log.i(TAG, "stopGeneration: called native interruptGeneration()");
            }
        } catch (Throwable t) {
            Log.w(TAG, "stopGeneration: native interrupt failed", t);
        }
        try {
            if (currentGenFuture != null) currentGenFuture.cancel(true);
        } catch (Exception ignored) {
        }
        // stop timer/UI monitor
        stopGenerationMonitor();
        setBusy(false);
        
        // Add stop message to chat
        addMessageToChat("[Generation stopped by user]", false);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.execute(() -> {
            try {
                if (llamaBridge != null && LlamaBridge.isNativeLoaded()) {
                    try {
                        llamaBridge.releaseModel();
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        });
        executor.shutdown();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cpuMonitor.start();
        ramMonitor.start();

    }

    @Override
    protected void onPause() {
        super.onPause();
        cpuMonitor.stop();
        ramMonitor.stop();

    }


}
