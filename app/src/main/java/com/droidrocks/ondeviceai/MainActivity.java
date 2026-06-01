package com.droidrocks.ondeviceai;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.inputmethod.InputMethodManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
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
import android.widget.ProgressBar;

import com.droidrocks.ondeviceai.databinding.ActivityMainBinding;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends BaseActivity {

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
    private Button btnNewChat; // dynamically-created or from layout: starts a fresh chat and resets native session
    private ProgressBar progressResetSession;
    private Snackbar loadingSnackbar;
    private TextView tvGenTimer;
    private TextView tvSysUsage;

    // Separate RAM chips
    private TextView tvAppRam;
    private TextView tvDeviceRam;
    private TextView tvAvailableRam;

    // GPU chips
    private TextView tvGpuChip;
    private TextView tvVulkanChip;

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
    // Monotonically increasing counter to identify each generation.
    // Old tasks compare their captured ID against the current value to
    // avoid overwriting a newer generation's streaming message.
    private volatile int generationId = 0;
    private CpuMonitor cpuMonitor;
    private RamMonitor ramMonitor;


    // Temporary in-memory conversation history (keeps last N turns)
    private final java.util.Deque<String> convoHistory = new java.util.ArrayDeque<>();
    private static final int MAX_HISTORY_ENTRIES = 6; // tune as needed
    private static final int MAX_PROMPT_CHARS = 1024; // truncate combined prompt to this many chars (from the end)
    private volatile boolean isModelLoaded = false;
    private File modelFile;
    private SharedPreferences prefs;
    // whether we've already sent the initial system prompt and earlier conversation to the native context
    private boolean contextPrimed = false;
    // one-time system prompt to prime the model/context when first sending text
    private static final String SYSTEM_PROMPT = "<|system|>\nYou are a helpful assistant. Answer concisely.\n<|end|>\n";
    private GpuInfo gpuInfo;

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applyEdgeToEdgeInsets(binding.getRoot());


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
        btnGenerate = findViewById(R.id.btnGenerate);
        btnClearChat = findViewById(R.id.btnClearChat);
        btnHistory = findViewById(R.id.btnHistory);

        // Initially hide interactive buttons until a model is successfully loaded.
        // Keep Models visible so the user can open model picker if needed.
        updateButtonsForModelLoaded(false);

        // New Chat button: try to find one in the layout, otherwise create it next to the models button
        btnNewChat = findViewById(getResources().getIdentifier("btnNewChat", "id", getPackageName()));
        // progress indicator (may be null if layout doesn't include it)
        progressResetSession = findViewById(getResources().getIdentifier("progressResetSession", "id", getPackageName()));
        if (btnNewChat == null) {
            try {
                android.view.View parent = (android.view.View) btnModels.getParent();
                if (parent instanceof android.view.ViewGroup) {
                    android.widget.Button b = new android.widget.Button(this);
                    b.setText("New Chat");
                    b.setId(android.view.View.generateViewId());
                    btnNewChat = b;
                    android.view.ViewGroup vg = (android.view.ViewGroup) parent;
                    // insert the new button near the models button if possible
                    int idx = vg.indexOfChild(btnModels);
                    if (idx >= 0 && idx < vg.getChildCount()) vg.addView(btnNewChat, idx + 1);
                    else vg.addView(btnNewChat);
                }
            } catch (Throwable ignored) {
                btnNewChat = null;
            }
        }

        // RAM chips
        tvAppRam = findViewById(R.id.tvAppRam);
        tvDeviceRam = findViewById(R.id.tvDeviceRam);
        tvAvailableRam = findViewById(R.id.tvAvailableRam);

        // GPU chips
        tvGpuChip = findViewById(R.id.tvGpuChip);
        tvVulkanChip = findViewById(R.id.tvVulkanChip);

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

        // Settings button
        Button btnSettings = findViewById(R.id.btnSettings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, SettingsActivity.class)));
        }

        prefs = App.getPrefs(this);

        // Wire the New Chat button to start a fresh conversation and reset native session state.
        if (btnNewChat != null) {
            btnNewChat.setOnClickListener(v -> {
                // If a generation is in progress, request cooperative stop silently
                try {
                    generationId++; // mark older generation tasks as stale
                    stopRequested = true;
                    if (llamaBridge != null && LlamaBridge.isNativeLoaded()) {
                        try {
                            llamaBridge.interruptGeneration();
                        } catch (Throwable ignored) {}
                    }
                    try {
                        if (currentGenFuture != null) currentGenFuture.cancel(true);
                    } catch (Throwable ignored) {}
                    // stop monitor and UI busy indicators
                    mainHandler.post(() -> {
                        try {
                            stopGenerationMonitor();
                            setBusy(false);
                        } catch (Throwable ignored) {}
                    });
                } catch (Throwable ignored) {}

                // Start UI-side new chat immediately
                startNewChat();
                toast(getString(R.string.btn_new_chat));

                // Show progress indicator and disable the button while native reset runs
                mainHandler.post(() -> {
                    try {
                        if (progressResetSession != null) progressResetSession.setVisibility(View.VISIBLE);
                        btnNewChat.setEnabled(false);
                    } catch (Throwable ignored) {}
                });

                // Reset native session in background so UI remains responsive
                executor.execute(() -> {
                    boolean ok = false;
                    try {
                        RuntimeLog.append("llama_jni: [llama_jni] resetSession: starting");
                        if (LlamaBridge.isNativeLoaded()) {
                            if (llamaBridge == null) llamaBridge = new LlamaBridge();
                            try {
                                ok = llamaBridge.resetSession();
                                Log.i(TAG, "resetSession returned " + ok);
                                RuntimeLog.append("llama_jni: [llama_jni] resetSession returned " + ok);
                            } catch (Throwable t) {
                                Log.w(TAG, "resetSession failed", t);
                                RuntimeLog.append("llama_jni: [llama_jni] resetSession failed: " + t.getMessage());
                                ok = false;
                            }
                        } else {
                            RuntimeLog.append("llama_jni: [llama_jni] resetSession skipped: native not loaded");
                        }
                    } catch (Throwable ignored) {
                        ok = false;
                    }

                    final boolean success = ok;
                    mainHandler.post(() -> {
                        try {
                            if (progressResetSession != null) progressResetSession.setVisibility(View.GONE);
                            btnNewChat.setEnabled(true);
                            if (success) {
                                toast(getString(R.string.reset_success));
                            } else {
                                toast(getString(R.string.reset_failed));
                            }
                        } catch (Throwable ignored) {}
                    });
                });
            });
        }

        // If a model was found earlier, attempt to load it; otherwise redirect to download
        if (modelFile != null) {
            loadModel();
        } else {
            // No model available - redirect to ModelListActivity to download one
            redirectToModelDownload();
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

    // Disable change animations so streaming token updates don't cause
    // the default cross-fade flicker on every notifyItemChanged call.
    RecyclerView.ItemAnimator animator = rvChat.getItemAnimator();
    if (animator instanceof androidx.recyclerview.widget.DefaultItemAnimator) {
        ((androidx.recyclerview.widget.DefaultItemAnimator) animator)
                .setSupportsChangeAnimations(false);
    }
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
        // Add to adapter IMMEDIATELY so the message appears in the UI right away.
        // This also guarantees correct ordering: the user message is always in the
        // adapter BEFORE the AI placeholder that the streaming code will post later.
        chatAdapter.addMessage(message);
        scrollToBottom();
        // Persist to database asynchronously (ID will be set when the write completes)
        chatRepository.insertMessage(message, messageId -> {
            message.setId(messageId);
        });
    }

    private void monitorGPU() {
        GpuInfo.Info info = GpuInfo.getGpuInfo();

        // GPU Chip
        if (info != null) {
            String gpuText = info.getShortName();
            String vendor = info.getCleanVendor();
            if (!vendor.equals("Unknown")) {
                gpuText += " (" + vendor + ")";
            }
            tvGpuChip.setText(gpuText);
            Log.i("MainActivity", "GPU vendor: " + info.getVendor() + " renderer: " + info.getRenderer() + " version: " + info.getVersion());
        } else {
            Log.w("MainActivity", "GPU detection failed");
            tvGpuChip.setText("Not detected");
        }

        // Vulkan Chip
        try {
            if (LlamaBridge.isNativeLoaded()) {
                if (llamaBridge == null) llamaBridge = new LlamaBridge();
                try {
                    boolean vk = llamaBridge.isVulkanAvailable();
                    StringBuilder vkText = new StringBuilder();
                    vkText.append(vk ? "✓ " : "✗ ");
                    if (vk) {
                        long mem = llamaBridge.getVulkanDeviceLocalMemory();
                        if (mem > 0) {
                            long memMB = mem / (1024 * 1024);
                            if (memMB >= 1024) {
                                vkText.append(String.format(java.util.Locale.US, "%.1fGB", memMB / 1024.0));
                            } else {
                                vkText.append(memMB).append("MB");
                            }
                        } else {
                            vkText.append("Yes");
                        }
                        String dev = llamaBridge.getVulkanDeviceName();
                        Log.i(TAG, "Vulkan available on device: " + dev + " mem=" + mem);
                    } else {
                        vkText.append("No");
                    }
                    tvVulkanChip.setText(vkText.toString());
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to query native Vulkan info", t);
                    tvVulkanChip.setText("⚠ Error");
                }
            } else {
                tvVulkanChip.setText("⏳ Loading");
            }
        } catch (Throwable ignored) {
            tvVulkanChip.setText("N/A");
        }
    }

    private void monitorRAM() {
        ramMonitor = new RamMonitor(this, 1000, info -> {
            // Update each RAM chip separately
            tvAppRam.setText(String.format(java.util.Locale.US, "%dMB/%dMB",
                    info.getAppUsedRamMB(), info.getAppMaxHeapMB()));
            tvDeviceRam.setText(String.format(java.util.Locale.US, "%dMB/%dMB",
                    info.getDeviceUsedRamMB(), info.getDeviceTotalRamMB()));
            tvAvailableRam.setText(String.format(java.util.Locale.US, "%dMB",
                    info.getDeviceAvailableRamMB()));
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
        // hide all interactive buttons while model is loading
        mainHandler.post(() -> {
            updateButtonsForModelLoaded(false);
            try {
                View root = findViewById(R.id.main);
                if (root != null) {
                    loadingSnackbar = Snackbar.make(root, getString(R.string.loading_model), Snackbar.LENGTH_INDEFINITE);
                    loadingSnackbar.show();
                }
            } catch (Throwable ignored) {}
        });
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
                            loaded = llamaBridge.loadModel(modelFile.getAbsolutePath(), 2048, threads);
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
                // Show appropriate buttons only after a successful model load
                try {
                    if (loadingSnackbar != null) loadingSnackbar.dismiss();
                } catch (Throwable ignored) {}
                updateButtonsForModelLoaded(result);
                if (result) {
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

    private void monitorCpu() {
        cpuMonitor = new CpuMonitor(1000, info -> {
            // Compact display: "8 cores | 12.5%"
            tvSysUsage.setText(info.toChipString());
        });

    }

    private void generateText() {
        String input = etPrompt.getText().toString().trim();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etPrompt.getWindowToken(), 0);
        }
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

        // Increment generation ID so any still-running old task knows it's stale
        final int thisGenId = ++generationId;
        // Clear any leftover stop state from a previous generation
        stopRequested = false;

        setBusy(true);

        // start timer/UI monitor
        mainHandler.post(this::startGenerationMonitor);

        currentGenFuture = executor.submit(() -> {
            String result = "";
            try {
                Log.i(TAG, "Calling native generate (streaming) genId=" + thisGenId);
                RuntimeLog.append("Calling native generateStreaming");
                if (!LlamaBridge.isNativeLoaded()) {
                    throw new IllegalStateException("Native library not loaded");
                }
                if (llamaBridge == null) llamaBridge = new LlamaBridge();

                // If this task is already stale (user started a newer generation), skip
                if (thisGenId != generationId) {
                    Log.i(TAG, "Skipping stale generation task genId=" + thisGenId);
                    return;
                }

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

                // Add a placeholder AI message to chat so streaming tokens appear immediately
                final ChatMessage aiMsg = new ChatMessage("▍", false, currentSessionId);
                mainHandler.post(() -> {
                    if (thisGenId != generationId) return; // stale
                    chatAdapter.addMessage(aiMsg);
                    scrollToBottom();
                });

                // Accumulator for streamed tokens
                final StringBuilder streamed = new StringBuilder();
                final int[] tokenCount = {0};

                // Read sampling params from preferences
                float temperature = prefs.getFloat(App.KEY_TEMPERATURE, 0.4f);
                float topP = prefs.getFloat(App.KEY_TOP_P, 0.95f);
                int maxTokens = prefs.getInt(App.KEY_MAX_TOKENS, 150);

                // Use streaming generation — callback fires per token on this background thread
                result = llamaBridge.generateStreaming(toSend, maxTokens, temperature, topP,
                        token -> {
                            streamed.append(token);
                            tokenCount[0]++;
                            final String snapshot = formatAiResponse(streamed.toString());
                            if (tokenCount[0] <= 3 || tokenCount[0] % 20 == 0) {
                                Log.d(TAG, "onToken #" + tokenCount[0] + " len=" + token.length()
                                        + " snapshot_len=" + snapshot.length()
                                        + " preview=" + (snapshot.length() > 40 ? snapshot.substring(0, 40) + "..." : snapshot));
                            }
                            mainHandler.post(() -> {
                                if (thisGenId != generationId) return; // stale
                                chatAdapter.updateLastMessage(snapshot + "▍");
                                scrollToBottom();
                            });
                            return !stopRequested && thisGenId == generationId;
                        });

                // Final update: remove cursor and set final text
                final String finalText = formatAiResponse(result != null ? result : streamed.toString());
                mainHandler.post(() -> {
                    if (thisGenId != generationId) return; // stale
                    chatAdapter.updateLastMessage(finalText);
                });

                Log.i(TAG, "Native generateStreaming returned length=" + (result != null ? result.length() : 0));
                RuntimeLog.append("Native generateStreaming returned length=" + (result != null ? result.length() : 0));
            } catch (Throwable t) {
                result = "Error: " + t.getMessage();
                Log.e(TAG, "Exception during generate", t);
                final String errMsg = result;
                mainHandler.post(() -> {
                    if (thisGenId != generationId) return; // stale
                    chatAdapter.updateLastMessage(errMsg);
                });
            } finally {
                currentGenFuture = null;
                // Only clear stopRequested if this is still the current generation
                if (thisGenId == generationId) {
                    stopRequested = false;
                } else {
                    // Generation was stopped — native context may be corrupted.
                    // Auto-reset the session so the next turn starts clean.
                    try {
                        if (llamaBridge != null && LlamaBridge.isNativeLoaded()) {
                            llamaBridge.resetSession();
                            contextPrimed = false;
                            Log.i(TAG, "Auto-reset session after stopped generation");
                            RuntimeLog.append("Session reset after stop");
                        }
                    } catch (Throwable ignored) {}
                }
            }

            final String finalResult = result != null ? result : "";
            mainHandler.post(() -> {
                // Only update UI if this is still the current generation
                if (thisGenId != generationId) return;

                // stop timer/UI monitor for generation
                stopGenerationMonitor();

                setBusy(false);

                // Format the response nicely
                String cleaned = formatAiResponse(finalResult);

                // Final update on the streamed message (remove cursor etc.)
                chatAdapter.updateLastMessage(cleaned);

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

                // Save the AI message to database (it was already added to the adapter during streaming)
                ChatMessage dbMsg = new ChatMessage(cleaned, false, currentSessionId);
                chatRepository.insertMessage(dbMsg, messageId -> {});

                RuntimeLog.append("Generation finished; output length=" + cleaned.length());
            });
        });
    }

    private void setBusy(boolean busy) {
        // While busy (generation or long native operation) only show the Stop button.
        if (busy) {
            if (btnGenerate != null) {
                btnGenerate.setVisibility(View.GONE);
                btnGenerate.setEnabled(false);
            }
            if (btnNewChat != null) {
                btnNewChat.setVisibility(View.GONE);
                btnNewChat.setEnabled(false);
            }
            if (btnStop != null) {
                btnStop.setVisibility(View.VISIBLE);
                btnStop.setEnabled(true);
            }
        } else {
            // Not busy — show primary actions and hide Stop
            if (btnGenerate != null) {
                btnGenerate.setVisibility(View.VISIBLE);
                btnGenerate.setEnabled(true);
            }
            if (btnNewChat != null) {
                btnNewChat.setVisibility(View.VISIBLE);
                btnNewChat.setEnabled(true);
            }
            if (btnStop != null) {
                btnStop.setVisibility(View.GONE);
                btnStop.setEnabled(false);
            }
        }
    }

    /** Control visibility of primary buttons depending on whether a model is loaded.
     * When loaded==false we hide Generate/NewChat/Stop/Clear/History to avoid user actions
     * until a model is ready; Models button remains visible so user can pick a model.
     */
    private void updateButtonsForModelLoaded(boolean loaded) {
        try {
            if (!loaded) {
                // Hide all buttons until a model is successfully loaded
                if (btnGenerate != null) { btnGenerate.setVisibility(View.GONE); btnGenerate.setEnabled(false); }
                if (btnNewChat != null) { btnNewChat.setVisibility(View.GONE); btnNewChat.setEnabled(false); }
                if (btnStop != null) { btnStop.setVisibility(View.GONE); btnStop.setEnabled(false); }
                if (btnClearChat != null) { btnClearChat.setVisibility(View.GONE); btnClearChat.setEnabled(false); }
                if (btnHistory != null) { btnHistory.setVisibility(View.GONE); btnHistory.setEnabled(false); }
                if (btnModels != null) { btnModels.setVisibility(View.VISIBLE); btnModels.setEnabled(true); }
                if (progressResetSession != null) progressResetSession.setVisibility(View.GONE);
            } else {
                // model loaded — show primary actions (generate/new chat) and keep stop hidden
                if (btnModels != null) { btnModels.setVisibility(View.VISIBLE); btnModels.setEnabled(true); }
                if (btnGenerate != null) { btnGenerate.setVisibility(View.VISIBLE); btnGenerate.setEnabled(true); }
                if (btnNewChat != null) { btnNewChat.setVisibility(View.VISIBLE); btnNewChat.setEnabled(true); }
                if (btnClearChat != null) { btnClearChat.setVisibility(View.VISIBLE); btnClearChat.setEnabled(true); }
                if (btnHistory != null) { btnHistory.setVisibility(View.VISIBLE); btnHistory.setEnabled(true); }
                if (btnStop != null) { btnStop.setVisibility(View.GONE); btnStop.setEnabled(false); }
            }
        } catch (Throwable ignored) {}
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

        // Increment generationId so the old task's callbacks become stale
        // and won't overwrite UI when they eventually fire.
        generationId++;

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

        // Update the existing AI streaming placeholder to show it was stopped
        // (don't add a new message — that would break updateLastMessage ordering)
        chatAdapter.updateLastMessage("[Generation stopped by user]");
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }


    /**
     * Redirect user to ModelListActivity to download a model
     */
    private void redirectToModelDownload() {
        mainHandler.postDelayed(() -> {
            toast("No model found. Please download a model to get started.");
            try {
                startActivityForResult(new Intent(MainActivity.this, ModelListActivity.class), REQUEST_MODEL_LIST);
            } catch (Exception ex) {
                Log.e(TAG, "Failed to launch ModelListActivity for download", ex);
            }
        }, 500); // Small delay to let the UI load first
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
    protected void onStop() {
        super.onStop();
        if (generating || currentGenFuture != null) {
            stopGeneration();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        cpuMonitor.stop();
        ramMonitor.stop();
    }

    /**
     * Format AI response for nice display
     */
    private String formatAiResponse(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        // Remove ALL special tokens and markers
        String cleaned = text
            // Remove role markers
            .replace("<|user|>", "")
            .replace("<|assistant|>", "")
            .replace("<|system|>", "")
            // Remove end tokens
            .replace("<|end|>", "")
            .replace("<|endoftext|>", "")
            .replace("<|im_end|>", "")
            .replace("<|im_start|>", "")
            .replace("</s>", "")
            // Replace space markers
            .replace("Ġ", " ")        // Replace Ġ (sentencepiece space marker) with space
            .replace('\u2581', ' ')  // Replace ▁ (sentence piece marker) with space
            .replace("_", " ")        // Replace underscores with space
            // Replace hex newlines
            .replace("<0x0A>", "\n")
            .replace("<br>", "\n")
            .replace("<br/>", "\n");

        // Fix multiple consecutive spaces
        cleaned = cleaned.replaceAll("[ \t]{2,}", " ");  // Replace multiple spaces with single space

        // Fix multiple consecutive newlines
        cleaned = cleaned.replaceAll("\\n[ \t]+", "\n"); // Remove leading spaces on new lines
        cleaned = cleaned.replaceAll("(\\n\\s*){3,}", "\n\n"); // Replace 3+ newlines with 2

        // Clean up common formatting issues
        cleaned = cleaned.replaceAll("([.!?])([A-Z])", "$1 $2"); // Space after punctuation before capital
        cleaned = cleaned.replaceAll("([,;:])([^ ])", "$1 $2"); // Space after punctuation

        // Remove extra quotes and clean them up
        cleaned = cleaned.replaceAll("^[\"']+", "").replaceAll("[\"']+$", ""); // Remove quotes at start/end
        cleaned = cleaned.replaceAll("``", "\"");    // Replace `` with "
        cleaned = cleaned.replaceAll("''", "\"");    // Replace '' with "

        // Final cleanup
        cleaned = cleaned.trim();

        // If result is empty after cleaning, return placeholder
        if (cleaned.isEmpty()) {
            return "(No response generated)";
        }

        return cleaned;
    }
}
