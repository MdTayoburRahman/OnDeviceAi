package com.droidrocks.ondeviceai;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.droidrocks.ondeviceai.Utils.CpuMonitor;
import com.droidrocks.ondeviceai.Utils.RamMonitor;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private EditText etPrompt;
    private TextView tvOutput;
    private TextView tvModelPath;
    private ProgressBar progressBar;
    private Button btnGenerate;
    private Button btnStop;
    private Button btnModels;
    private TextView tvGenTimer;
    private TextView tvSysUsage;
    private TextView tvRamUsage;

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
 @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        etPrompt = findViewById(R.id.etPrompt);
        tvOutput = findViewById(R.id.tvOutput);
        tvModelPath = findViewById(R.id.tvModelPath);
        tvGenTimer = findViewById(R.id.tvGenTimer);
        tvSysUsage = findViewById(R.id.tvSysUsage);
        btnModels = findViewById(R.id.btnModels);
        tvRamUsage = findViewById(R.id.tvRamUsage);
        btnGenerate = findViewById(R.id.btnGenerate);
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
                startActivityForResult(new android.content.Intent(MainActivity.this, ModelListActivity.class), 1001);
            } catch (Exception ex) {
                Log.e(TAG, "Failed to open ModelListActivity", ex);
                toast("Cannot open models list");
            }
        });

        // allow tapping the model label to change model as well
        tvModelPath.setOnClickListener(v -> {
            try {
                startActivityForResult(new android.content.Intent(MainActivity.this, ModelListActivity.class), 1001);
            } catch (Exception ex) {
                Log.e(TAG, "Failed to open ModelListActivity from model label", ex);
                toast("Cannot open models list");
            }
        });

        if (btnGenerate != null) {
            btnGenerate.setOnClickListener(v -> generateText());
        }

        // If a model was found earlier, attempt to load it
        if (modelFile != null) {
            loadModel();
        }
        
        monitorCpu();
        monitorRAM();
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
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            String path = data.getStringExtra("modelPath");
            if (path != null && !path.isEmpty()) {
                modelFile = new File(path);
                tvModelPath.setText(ModelUtils.getFriendlyModelName(modelFile.getAbsolutePath()));
                // reload the selected model automatically
                loadModel();
            }
        }
    }

    private void loadModel() {
        setBusy(true);
        if (tvOutput != null) tvOutput.setText("Loading model...");
        Log.i(TAG, "loadModel: starting");

        // If the model file is missing, send the user to the ModelListActivity
        if (modelFile == null || !modelFile.exists() || modelFile.length() == 0) {
            Log.i(TAG, "Model file missing; prompting user to select or download a model");
            mainHandler.post(() -> {
                try {
                    if (tvOutput != null)
                        tvOutput.setText("Model not found. Please select or download a model.");
                    setBusy(false);
                    startActivityForResult(new android.content.Intent(MainActivity.this, ModelListActivity.class), 1001);
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
                    if (LlamaBridge.isNativeLoaded()) {
                        if (llamaBridge == null) llamaBridge = new LlamaBridge();
                        try {
                            int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
                            loaded = llamaBridge.loadModel(modelFile.getAbsolutePath(), 1024, threads);
                            Log.i(TAG, "Native loadModel returned " + loaded);
                        } catch (Throwable t) {
                            Log.e(TAG, "Native loadModel failed", t);
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
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (result) {
                    if (tvOutput != null) tvOutput.setText("Model loaded successfully.");
                    toast("Model loaded");
                } else {
                    if (modelFile == null || !modelFile.exists() || modelFile.length() == 0) {
                        if (tvOutput != null)
                            tvOutput.setText("Model file not found. Place the model at:\n" + ModelUtils.getDefaultModelFile(MainActivity.this).getAbsolutePath());
                        toast("Model file not found");
                    } else {
                        if (tvOutput != null) tvOutput.setText("Failed to load model.");
                        toast("Load failed");
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

        String formattedPrompt = ModelUtils.formatPrompt(input);
        setBusy(true);
        if (tvOutput != null) tvOutput.setText("Generating...");

        // start timer/UI monitor
        mainHandler.post(this::startGenerationMonitor);

        currentGenFuture = executor.submit(() -> {
            String result = "";
            try {
                Log.i(TAG, "Calling native generate");
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

                if (tvOutput != null) tvOutput.setText(cleaned);
            });
        });
    }

    private void setBusy(boolean busy) {
        if (progressBar != null) progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
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
            long seconds = elapsed / 1000;
            long tenths = (elapsed % 1000) / 100; // show tenths
            if (tvGenTimer != null) tvGenTimer.setText(seconds + "." + tenths + " s");
            mainHandler.postDelayed(this, 200);
        }
    };

    private void startGenerationMonitor() {
        generating = true;
        genStartMs = System.currentTimeMillis();
        // start UI updates
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
        if (tvOutput != null) tvOutput.append("\n\n[Generation stopped by user]");
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
