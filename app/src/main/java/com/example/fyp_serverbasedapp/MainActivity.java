package com.example.fyp_serverbasedapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.AudioFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Switch;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;
import org.json.JSONException;
import android.util.Base64;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AR_GLASSES_APP";
    private static final String PREFS_NAME = "VoiceRegistrationPrefs";
    private static final String PREF_USER_VOICE_ID = "userVoiceId";

    // WebSocket configuration
    private static final String SERVER_URL = "ws://192.168.0.112:8000";
    private WebSocketClient webSocketClient;
    private static final int RECORD_AUDIO_PERMISSION_CODE = 1;
    private boolean isRecording = false;
    private boolean isConnected = false;
    private boolean isInConversation = false;
    private boolean isRecordingState = false; // Visual recording state indicator
    private boolean shouldReconnect = true; // Auto-reconnect flag
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private long lastReconnectTime = 0; // Track last reconnect to prevent loops
    private static final long MIN_RECONNECT_INTERVAL_MS = 5000; // Min 5 seconds between reconnects
    private Handler pingHandler = new Handler(Looper.getMainLooper());
    private Runnable pingRunnable;
    private AudioRecord audioRecorder;

    // Audio recording parameters
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE_MULTIPLIER = 4; // Increased buffer size

    // Real-time chunk parameters
    private static final int CHUNK_INTERVAL_MS = 3000; // Send chunks every 3 seconds
    private static final int BYTES_PER_SECOND = SAMPLE_RATE * 2; // 16-bit = 2 bytes per sample, mono
    private static final int CHUNK_SIZE_BYTES = (CHUNK_INTERVAL_MS * BYTES_PER_SECOND) / 1000; // ~96KB for 3 seconds

    private static final int MIN_RECORDING_DURATION_MS = 300; // Catch very quick speech
    private static final int OVERLAP_DURATION_MS = 500; // More overlap to prevent cutting

    // Audio data collection with buffering - Minimize downtime
    private List<byte[]> audioChunks = new ArrayList<>();
    private BlockingQueue<byte[]> audioBuffer = new LinkedBlockingQueue<>(50); // Audio buffer
    private int totalBytesRecorded = 0;
    private long lastSpeechTime = 0;
    private long recordingStartTime = 0;

    // Real-time chunk tracking
    private List<byte[]> currentChunkBuffer = new ArrayList<>(); // Buffer for current 3-second chunk
    private int currentChunkBytes = 0; // Bytes in current chunk
    private Handler chunkHandler = new Handler(Looper.getMainLooper()); // Handler for chunk timer

    private boolean hasDetectedSpeech = false;

    // Processing control
    private AtomicBoolean isProcessing = new AtomicBoolean(false);
    private boolean canRecordNext = true;

    // Essential counters only
    private int audioSentCount = 0;
    private int audioReceivedCount = 0;

    // UI Components
    private TextView connectionStatus;
    private TextView processingStatus;
    private LinearLayout conversationContainer;
    private ScrollView chatScrollView;
    private Button requestButton, stopButton, testServerButton, disconnectButton;
    private Button voiceRegistrationButton;

    // speaker diaruzatui language(default to zh)
    private String translate_Lang = "zh";

    // Simple speaker tracking
    private int speakerCount = 0;

    // Keep track of segments we've already displayed to avoid duplicates
    private Set<String> seenSegments;

    // Voice registration settings
    private String userVoiceId = null;

    // Voice registration recording state
    private boolean isRecordingVoice = false;
    private String recordedVoiceData = null;
    private boolean hasRecordedVoice = false;
    private static final int VOICE_RECORDING_DURATION_MS = 5000; // 5 seconds for voice registration

    // ========== UI STYLING CONSTANTS ==========
    // Speaker Name Styling
    private static final float SPEAKER_NAME_TEXT_SIZE = 15f;  // Speaker name font size
    private static final int SPEAKER_NAME_PADDING_H = 10;     // Horizontal padding
    private static final int SPEAKER_NAME_PADDING_V = 6;      // Vertical padding
    private static final int SPEAKER_NAME_BORDER_WIDTH = 2;   // Border thickness
    private static final float SPEAKER_NAME_CORNER_RADIUS = 8f; // Corner roundness

    // Speech Text Styling
    private static final float SPEECH_TEXT_SIZE = 16f;        // Speech text font size
    private static final int SPEECH_PADDING_H = 12;           // Horizontal padding
    private static final int SPEECH_PADDING_V = 8;            // Vertical padding
    private static final float SPEECH_LINE_SPACING = 6f;      // Extra space between lines
    private static final float SPEECH_LINE_SPACING_MULT = 1.1f; // Line spacing multiplier
    private static final float SPEECH_CORNER_RADIUS = 8f;     // Corner roundness

    // Message Layout Styling
    private static final int MESSAGE_PADDING_H = 6;           // Horizontal padding for message
    private static final int MESSAGE_PADDING_V = 4;           // Vertical padding for message
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Load saved user voice ID from persistent storage
        loadUserVoiceId();

        // Initialize UI components
        connectionStatus = findViewById(R.id.connectionStatus);
        processingStatus = findViewById(R.id.processingStatus);
        conversationContainer = findViewById(R.id.conversationContainer);
        chatScrollView = findViewById(R.id.chatScrollView);

        requestButton = findViewById(R.id.requestButton);
        stopButton = findViewById(R.id.stopButton);
        testServerButton = findViewById(R.id.testServerButton);
        disconnectButton = findViewById(R.id.disconnectButton);
        voiceRegistrationButton = findViewById(R.id.voiceRegistrationButton);

        // Initialize dedupe set
        seenSegments = Collections.synchronizedSet(new HashSet<String>());

        // Set initial states
        updateButtonStates();
        connectionStatus.setText("Disconnected");
        connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));

        // Update processing status based on voice registration
        if (userVoiceId != null) {
            processingStatus.setText("Voice registered. Connect to start.");
        } else {
            processingStatus.setText("Please Connect first");
        }

        setupWebSocket();

        if (!checkAudioPermission()) {
            requestAudioPermission();
        }

        testServerButton.setOnClickListener(v -> {
            if (!isConnected) {
                Log.i(TAG, "Manual reconnect requested");
                reconnectWebSocket();
                processingStatus.setText("Connecting to server...");
            } else {
                sendPingMessage();
                processingStatus.setText("Manual ping sent to server...");
            }
        });

        requestButton.setOnClickListener(v -> {
            if (checkAudioPermission()) {
                if (!isRecording) {
                    startRecording();
                } else {
                    processingStatus.setText("Already recording! Click Stop to finish.");
                }
            } else {
                requestAudioPermission();
            }
        });

        stopButton.setOnClickListener(v -> {
            stopRecording();
        });

        disconnectButton.setOnClickListener(v -> {
            Log.i(TAG, "Manual disconnect requested");
            stopClientPing(); // Stop ping when disconnecting
            leaveConversation();
            closeWebSocket();
        });

        // Translation language selector
        LinearLayout languageRow = new LinearLayout(this);
        languageRow.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowParams.topMargin = 16;
        languageRow.setLayoutParams(rowParams);

        TextView langLabel = new TextView(this);
        langLabel.setText("Language for translation");
        langLabel.setTextSize(14f);
        languageRow.addView(langLabel);

        RadioGroup langGroup = new RadioGroup(this);
        langGroup.setOrientation(RadioGroup.HORIZONTAL);

        RadioButton rbCantonese = new RadioButton(this);
        rbCantonese.setText("Cantonese");
        rbCantonese.setId(View.generateViewId());
        langGroup.addView(rbCantonese);

        RadioButton rbEnglish = new RadioButton(this);
        rbEnglish.setText("English");
        rbEnglish.setId(View.generateViewId());
        langGroup.addView(rbEnglish);

        // Initialize selection based on current translate_Lang
        if ("en".equalsIgnoreCase(translate_Lang)) {
            rbEnglish.setChecked(true);
        } else {
            // default to Cantonese
            rbCantonese.setChecked(true);
            translate_Lang = "yue"; // ensure we use Cantonese code
        }

        langGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == rbEnglish.getId()) {
                translate_Lang = "en";
            } else if (checkedId == rbCantonese.getId()) {
                translate_Lang = "yue";
            }
            processingStatus.setText("Language set to: " + ("en".equals(translate_Lang) ? "English" : "Cantonese") + " (" + translate_Lang + ")");
            if (isConnected && webSocketClient != null && webSocketClient.isOpen()) {
                sendJoinConversationMessage();
            }
        });

        languageRow.addView(langGroup);

        // Place the selector below the existing buttons by adding to the same parent layout
        View buttonsParent = (View) requestButton.getParent();
        if (buttonsParent instanceof LinearLayout) {
            ((LinearLayout) buttonsParent).addView(languageRow);
        } else {
            // Fallback: attach to conversation container if buttons' parent isn't linear
            conversationContainer.addView(languageRow, 0);
        }

        voiceRegistrationButton.setOnClickListener(v -> {
            if (checkAudioPermission()) {
                if (!isRecordingVoice) {
                    startVoiceRecording();
                } else {
                    stopVoiceRecording();
                }
            } else {
                requestAudioPermission();
            }
        });

        // Start audio buffer processing
        startAudioBufferProcessor();
    }

    private void startAudioBufferProcessor() {
        // Process audio buffer in background thread
        new Thread(() -> {
            while (true) {
                try {
                    byte[] audioData = audioBuffer.take(); // Blocking take
                    if (audioData != null) {
                        processAudioChunk(audioData);
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                }
            }
        }).start();
    }

    private void processAudioChunk(byte[] audioData) {
        // Don't process audio if not in conversation
        if (!isInConversation) {
            return;
        }

        // Add to audio chunks
        audioChunks.add(audioData);
        totalBytesRecorded += audioData.length;

        // Log every 10 chunks to avoid spam
        if (audioChunks.size() % 10 == 0) {
        }

        // Continuous recording - no speech detection, just record everything
        long currentTime = System.currentTimeMillis();
        lastSpeechTime = currentTime;
        if (!hasDetectedSpeech) {
            hasDetectedSpeech = true;
        }

        // Continuous recording - no UI status updates needed
    }

    private void joinConversation() {
        if (!checkAudioPermission()) {
            requestAudioPermission();
            return;
        }

        if (isInConversation) {
            return;
        }

        isInConversation = true;
        audioSentCount = 0;
        audioReceivedCount = 0;
        canRecordNext = true;


        // Clear the conversation chatbox
        clearConversationChatbox();

        // Send reset session message
        sendResetSessionMessage();

        processingStatus.setText("Ready");
        updateButtonStates();

        // Start single recording
        startSingleRecording();
    }

    private void startRecording() {
        try {

            if (!checkAudioPermission()) {
                requestAudioPermission();
                return;
            }

            if (isRecording) {
                return;
            }

            isInConversation = true;
            audioSentCount = 0;
            audioReceivedCount = 0;
            canRecordNext = true;

            // Clear the conversation chatbox
            clearConversationChatbox();

            // Send reset session message
            sendResetSessionMessage();

            processingStatus.setText("Ready to record");

            // Set recording state
            isRecording = true;
            updateButtonStates();

            // Start recording
            startSingleRecording();

        } catch (Exception e) {
            runOnUiThread(() -> {
                processingStatus.setText("Error starting recording: " + e.getMessage());
            });
        }
    }

    private void stopRecording() {

        if (!isRecording) {
            return;
        }


        // Update status to show processing
        runOnUiThread(() -> {
            processingStatus.setText("Stopping recording and processing...");
        });

        // Stop the recording but don't reset isRecording yet
        if (audioRecorder != null) {
            try {
                if (audioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecorder.stop();
                }
            } catch (Exception e) {
            } finally {
                try {
                    audioRecorder.release();
                } catch (Exception e) {
                }
                audioRecorder = null;
            }
        }

        // Reset recording state
        isRecording = false;
        isRecordingState = false;

        // Update button states - this will change button to "Start Recording"
        updateButtonStates();

        // Process and send the recorded audio
        processAndSendAudio();

    }

    private void leaveConversation() {
        isInConversation = false;
        isRecording = false;
        isRecordingState = false; // Reset recording state indicator
        canRecordNext = false; // Stop any further recording attempts
        hasDetectedSpeech = false; // Reset speech detection

        // Send reset session message
        sendResetSessionMessage();

        if (audioRecorder != null) {
            try {
                if (audioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecorder.stop();
                }
            } catch (SecurityException e) {
            } catch (Exception e) {
            } finally {
                try {
                    audioRecorder.release();
                } catch (Exception e) {
                }
                audioRecorder = null;
            }
        }


        // Clear audio buffer
        audioBuffer.clear();
        audioChunks.clear();

        runOnUiThread(() -> {
            processingStatus.setText("Ready");
            updateButtonStates();
        });

        // Add multiple delayed updates to ensure the status is set correctly
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            runOnUiThread(() -> {
                processingStatus.setText("Ready");
            });
        }, 100);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            runOnUiThread(() -> {
                processingStatus.setText("Ready");
            });
        }, 500);

    }

    private void startClientPing() {
        // Stop any existing ping
        stopClientPing();

        // Start new ping every 20 seconds
        pingRunnable = new Runnable() {
            @Override
            public void run() {
                if (isConnected && webSocketClient != null && webSocketClient.isOpen()) {
                    sendPingMessage();
                    // Schedule next ping
                    pingHandler.postDelayed(this, 20000); // 20 seconds
                }
            }
        };
        pingHandler.postDelayed(pingRunnable, 20000); // Start first ping after 20 seconds
    }

    private void stopClientPing() {
        if (pingRunnable != null) {
            pingHandler.removeCallbacks(pingRunnable);
            pingRunnable = null;
        }
    }

    private void sendJoinConversationMessage() {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                JSONObject joinMessage = new JSONObject();
                joinMessage.put("type", "join_conversation");
                joinMessage.put("translation_language", translate_Lang);
                joinMessage.put("timestamp", System.currentTimeMillis());
                webSocketClient.send(joinMessage.toString());
                Log.i(TAG, "Sent join_conversation message to server");
            } catch (JSONException e) {
                Log.e(TAG, "Failed to send join_conversation message: " + e.getMessage());
            }
        }
    }

    private void sendPingMessage() {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                JSONObject pingMessage = new JSONObject();
                pingMessage.put("type", "ping");
                pingMessage.put("timestamp", System.currentTimeMillis());
                webSocketClient.send(pingMessage.toString());
                // Don't update UI for automatic pings
            } catch (JSONException e) {
                Log.e(TAG, "Failed to send ping: " + e.getMessage());
            }
        }
    }

    private void sendResetSessionMessage() {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                JSONObject resetMessage = new JSONObject();
                resetMessage.put("type", "reset_session");
                resetMessage.put("timestamp", System.currentTimeMillis());

                webSocketClient.send(resetMessage.toString());
                processingStatus.setText("Session reset - ready to start fresh");
            } catch (JSONException e) {
                processingStatus.setText("Failed to reset session");
            }
        }
    }


    private boolean checkAudioPermission() {
        boolean hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        return hasPermission;
    }

    private void requestAudioPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                RECORD_AUDIO_PERMISSION_CODE);
    }

    private void reconnectWebSocket() {
        Log.i(TAG, "Reconnecting WebSocket...");

        // Properly close existing connection
        if (webSocketClient != null) {
            try {
                stopClientPing(); // Stop ping first
                if (webSocketClient.isOpen()) {
                    Log.i(TAG, "Closing existing connection before reconnect");
                    webSocketClient.close();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error closing WebSocket during reconnect: " + e.getMessage());
            }
            webSocketClient = null;
        }

        isConnected = false;

        // Small delay to ensure old connection is fully closed
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            setupWebSocket();
        }, 500); // 500ms delay
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                processingStatus.setText("Microphone permission granted. Ready to join conversation!");
                updateButtonStates();
            } else {
                processingStatus.setText("Microphone permission is required to use this feature!");
            }
        }
    }

    private boolean isConnecting = false; // Prevent multiple simultaneous connections

    private void setupWebSocket() {
        // Prevent connection spam
        if (isConnecting) {
            Log.w(TAG, "Already connecting, ignoring duplicate setupWebSocket call");
            return;
        }

        try {
            isConnecting = true;

            // Close existing connection if any
            if (webSocketClient != null) {
                Log.i(TAG, "Closing existing WebSocket before creating new one");
                try {
                    if (webSocketClient.isOpen()) {
                        webSocketClient.close();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error closing old WebSocket: " + e.getMessage());
                }
                webSocketClient = null;
            }

            URI serverUri = new URI(SERVER_URL);

            webSocketClient = new WebSocketClient(serverUri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    Log.i(TAG, "========== WebSocket OPENED ==========");
                    Log.i(TAG, "Connection state - isConnected: " + isConnected + ", isOpen: " + isOpen());
                    isConnected = true;
                    isConnecting = false; // Connection established

                    // Start keep-alive pings (20 second interval)
                    startClientPing();

                    // Send join_conversation message to initialize session with server
                    sendJoinConversationMessage();

                    runOnUiThread(() -> {
                        connectionStatus.setText("Connected");
                        connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                        processingStatus.setText("Connected to server. Ready to record!");
                        updateButtonStates();
                    });
                }

                @Override
                public void onMessage(String message) {
                    try {
                        // Log raw message for debugging parsing issues
                        android.util.Log.d(TAG, "RAW_WS_MSG: " + message);

                        JSONObject response = new JSONObject(message);
                        String type = response.optString("type", "");

                        runOnUiThread(() -> {
                            switch (type) {
                                case "conversation_joined":
                                    connectionStatus.setText("Connected");
                                    connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                                    processingStatus.setText("Joined conversation - Ready to record!");
                                    Log.i(TAG, "Successfully joined conversation");
                                    break;

                                case "pong":
                                    connectionStatus.setText("Connected");
                                    connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                                    processingStatus.setText("Server is alive! (Status: " + response.optInt("status_code", 0) + ")");
                                    break;

                                case "keep_alive":
                                    // Update connection status to show it's alive
                                    connectionStatus.setText("Connected");
                                    connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                                    break;

                                case "processing_started":
                                    break;

                                case "processing_status":
                                    // Server is still processing - update UI and reset client ping timer
                                    connectionStatus.setText("Connected");
                                    connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                                    processingStatus.setText("Processing on server... (working)");
                                    // Restart client ping so the handler doesn't trigger a reconnect while server is busy
                                    startClientPing();
                                    break;

                                case "segment_result":
                                    audioReceivedCount++;
                                    try {
                                        JSONObject segment = response.getJSONObject("segment");
                                        // Support both 'speaker_id' (new) and 'speaker' (legacy)
                                        String speaker = segment.optString("speaker_id", segment.optString("speaker", "UNKNOWN"));
                                        // Support both 'text' (new) and 'transcription' (legacy)
                                        String text = segment.optString("text", segment.optString("transcription", ""));

                                        // Check if this is the wearer's voice
                                        boolean isWearer = segment.optBoolean("is_wearer", false);

                                        // Simple speaker name formatting
                                        String speakerName;
                                        if (isWearer) {
                                            speakerName = "YOU";  // Display as "YOU" for the wearer
                                        } else if (speaker.startsWith("SPEAKER_")) {
                                            speakerName = "Speaker " + speaker.substring(8); // Remove "SPEAKER_" prefix
                                        } else {
                                            speakerName = speaker;
                                        }

                                        // Add message (no validation needed)
                                        String key = makeSegmentKey(segment, response.optString("chunk_id", null));
                                        if (!seenSegments.contains(key)) {
                                            seenSegments.add(key);
                                            addMessageToConversation(speakerName, text);
                                            Log.d(TAG, "Added message - " + speakerName + ": " + text);
                                        } else {
                                            Log.d(TAG, "Duplicate segment ignored - " + key);
                                        }

                                    } catch (JSONException e) {
                                        processingStatus.setText("Error parsing server response");
                                        Log.e(TAG, "Error parsing segment_result: " + e.getMessage());
                                    }
                                    break;

                                case "processing_result":
                                    // Server sent the full array of segments in one message
                                    try {
                                        org.json.JSONArray segs = response.optJSONArray("segments");
                                        if (segs != null) {
                                            for (int si = 0; si < segs.length(); si++) {
                                                JSONObject segment = segs.optJSONObject(si);
                                                if (segment == null) continue;
                                                String speaker = segment.optString("speaker_id", segment.optString("speaker", "UNKNOWN"));
                                                String text = segment.optString("text", segment.optString("transcription", ""));

                                                // Check if this is the wearer's voice
                                                boolean isWearer = segment.optBoolean("is_wearer", false);

                                                String speakerName;
                                                if (isWearer) {
                                                    speakerName = "YOU";  // Display as "YOU" for the wearer
                                                } else if (speaker.startsWith("SPEAKER_")) {
                                                    speakerName = "Speaker " + speaker.substring(8);
                                                } else {
                                                    speakerName = speaker;
                                                }

                                                // Add message (no validation needed)
                                                String key = makeSegmentKey(segment, response.optString("chunk_id", null));
                                                if (!seenSegments.contains(key)) {
                                                    seenSegments.add(key);
                                                    addMessageToConversation(speakerName, text);
                                                }
                                            }
                                        }
                                    } catch (Exception e) {
                                        processingStatus.setText("Error parsing processing_result");
                                    }
                                    break;

                                case "no_speech":
                                    break;

                                case "audio_received":
                                    // Server confirmed it received the audio
                                    canRecordNext = true;


                                    break;

                                case "audio_processed":

                                    // Update status to show processing completed
                                    runOnUiThread(() -> {
                                        processingStatus.setText("Processing completed - Ready to record again");
                                    });

                                    updateButtonStates();
                                    break;

                                case "speakers_list":
                                    processingStatus.setText("Speakers auto-detected during conversation");
                                    break;

                                case "voice_registered":
                                    // Handle voice registration from VoiceRegistrationActivity
                                    String voiceId = response.optString("voice_id", null);
                                    int numSamples = response.optInt("num_samples", 1);
                                    String regMethod = response.optString("registration_method", "single");

                                    if (voiceId != null) {
                                        userVoiceId = voiceId;
                                        saveUserVoiceId(voiceId);  // Save persistently

                                        if ("multi-sample".equals(regMethod)) {
                                            processingStatus.setText("Voice registered with " + numSamples + " samples (Robust)");
                                        } else {
                                            processingStatus.setText("Voice registered! You'll show as 'WEARER'");
                                        }
                                    }
                                    break;

                                case "processing_error":
                                    try {
                                        String error = response.getString("error");
                                        String chunkId = response.optString("chunk_id", "");
                                        processingStatus.setText("Processing Error: " + error);
                                        connectionStatus.setText("Connected");
                                        connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                                        Log.e(TAG, "Server processing error for chunk " + chunkId + ": " + error);
                                    } catch (JSONException e) {
                                        processingStatus.setText("Unknown processing error occurred");
                                    }
                                    isProcessing.set(false);
                                    updateButtonStates();
                                    break;

                                case "error":
                                    try {
                                        String error = response.getString("error");
                                        processingStatus.setText("Error: " + error);
                                    } catch (JSONException e) {
                                        processingStatus.setText("Unknown error occurred");
                                    }
                                    isProcessing.set(false);
                                    updateButtonStates();
                                    break;



                                default:
                            }
                        });
                    } catch (JSONException e) {
                        runOnUiThread(() -> {
                            processingStatus.setText("Received non-JSON message");
                        });
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.i(TAG, "========== WebSocket CLOSED ==========");
                    Log.i(TAG, "Close Code: " + code + ", Reason: " + reason + ", Remote: " + remote);
                    Log.i(TAG, "Connection state before - isConnected: " + isConnected + ", isOpen: " + isOpen());
                    isConnected = false;
                    isConnecting = false; // Reset connecting flag
                    leaveConversation();
                    runOnUiThread(() -> {
                        connectionStatus.setText("Disconnected");
                        connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                        updateButtonStates();
                    });

                    // No auto-reconnect - keep-alive pings should maintain connection
                    // If connection is lost, user should manually reconnect
                    Log.i(TAG, "Connection closed - no auto-reconnect (use keep-alive pings instead)");
                    runOnUiThread(() -> {
                        processingStatus.setText("Connection lost. Click 'Test Server' to reconnect.");
                    });
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "========== WebSocket ERROR ==========");
                    Log.e(TAG, "Error: " + ex.getMessage());
                    ex.printStackTrace();
                    isConnected = false;
                    isConnecting = false; // Reset connecting flag
                    leaveConversation();
                    runOnUiThread(() -> {
                        connectionStatus.setText("Error");
                        connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                        processingStatus.setText("WebSocket Error: " + ex.getMessage());
                        updateButtonStates();
                    });
                }
            };

            Log.i(TAG, "Creating new WebSocket connection to: " + SERVER_URL);
            webSocketClient.connect();
            Log.i(TAG, "Connection initiated");

        } catch (Exception e) {
            isConnecting = false; // Reset on error
            Log.e(TAG, "Failed to create WebSocket: " + e.getMessage());
            runOnUiThread(() -> {
                connectionStatus.setText("Connection Failed");
                connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                processingStatus.setText("Failed to connect: " + e.getMessage());
            });
        }
    }



    private void startSingleRecording() {
        try {

            // Clear previous recording data
            audioChunks.clear();
            totalBytesRecorded = 0;
            hasDetectedSpeech = false;
            recordingStartTime = System.currentTimeMillis();
            lastSpeechTime = recordingStartTime;

            // Set recording state
            isRecordingState = true;
            runOnUiThread(() -> {
                processingStatus.setText("RECORDING - Speak now...");
            });


            if (!checkAudioPermission()) {
                runOnUiThread(() -> {
                    processingStatus.setText("Error: Audio permission required");
                });
                return;
            }

            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_MULTIPLIER;
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
                return;
            }


            try {
                audioRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
            } catch (SecurityException e) {
                runOnUiThread(() -> {
                    processingStatus.setText("Error: Audio permission denied");
                });
                return;
            }

            if (audioRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
                audioRecorder.release();
                audioRecorder = null;
                return;
            }

            runOnUiThread(() -> {
            });

            new Thread(() -> {
                byte[] buffer = new byte[bufferSize];

                try {
                    audioRecorder.startRecording();
                } catch (SecurityException e) {
                    runOnUiThread(() -> {
                        processingStatus.setText("Error: Audio permission denied");
                    });
                    return;
                }

                // Reset chunk tracking
                currentChunkBuffer.clear();
                currentChunkBytes = 0;
                recordingStartTime = System.currentTimeMillis();

                // Start chunk timer - send chunks every 3 seconds
                chunkHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (isRecording && isInConversation) {
                            sendRealTimeChunk();
                            // Schedule next chunk
                            chunkHandler.postDelayed(this, CHUNK_INTERVAL_MS);
                        }
                    }
                }, CHUNK_INTERVAL_MS);

                int totalBytesRead = 0;
                int chunkCount = 0;

                while (isRecording && isInConversation) {
                    if (audioRecorder == null) {
                        break;
                    }

                    int bytesRead = audioRecorder.read(buffer, 0, buffer.length);

                    if (bytesRead < 0) {
                        break;
                    }

                    totalBytesRead += bytesRead;
                    chunkCount++;

                    // Store audio data in buffer for processing
                    byte[] audioChunk = new byte[bytesRead];
                    System.arraycopy(buffer, 0, audioChunk, 0, bytesRead);

                    // Add to main buffer (for final processing)
                    try {
                        audioBuffer.put(audioChunk);
                    } catch (InterruptedException e) {
                        break;
                    }

                    // Also add to current chunk buffer for real-time processing
                    synchronized (currentChunkBuffer) {
                        currentChunkBuffer.add(audioChunk);
                        currentChunkBytes += bytesRead;
                    }

                    // Log every 50 chunks to avoid spam
                    if (chunkCount % 50 == 0) {
                    }
                }

                // Stop chunk timer
                chunkHandler.removeCallbacksAndMessages(null);

                // Send any remaining audio as final chunk
                if (currentChunkBytes > 0) {
                    sendRealTimeChunk();
                }

            }).start();
        } catch (Exception e) {
            runOnUiThread(() -> {
                processingStatus.setText("Error starting recording: " + e.getMessage());
            });
        }
    }

    private void stopSingleRecording() {
        // Stop chunk timer
        chunkHandler.removeCallbacksAndMessages(null);

        if (audioRecorder != null) {
            try {
                if (audioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecorder.stop();
                }
            } catch (Exception e) {
            } finally {
                try {
                    audioRecorder.release();
                } catch (Exception e) {
                }
                audioRecorder = null;
            }
        }

        // Reset recording state
        isRecording = false;
        isRecordingState = false;
        runOnUiThread(() -> {
            processingStatus.setText("Sending final audio to server...");
        });
        updateButtonStates();

        // Process and send the final recorded audio (full audio, not chunk)
        processAndSendAudio();

        // Clear chunk buffer
        synchronized (currentChunkBuffer) {
            currentChunkBuffer.clear();
            currentChunkBytes = 0;
        }
    }

    private void processAndSendAudio() {

        if (!canRecordNext) {
            return;
        }

        if (audioChunks.isEmpty()) {
            canRecordNext = true;
            isProcessing.set(false);
            return;
        }

        // Mark that we're sending audio
        canRecordNext = false;
        isProcessing.set(true);

        new Thread(() -> {
            try {

                // Combine all audio chunks into one array
                byte[] completeAudio = new byte[totalBytesRecorded];
                int offset = 0;
                for (byte[] chunk : audioChunks) {
                    System.arraycopy(chunk, 0, completeAudio, offset, chunk.length);
                    offset += chunk.length;
                }


                // Create WAV header and combine with audio data
                byte[] wavBytes = createWavBytes(completeAudio);

                if (wavBytes == null) {
                    canRecordNext = true;
                    isProcessing.set(false);
                    return;
                }


                // Convert to base64 and send directly
                String base64Audio = Base64.encodeToString(wavBytes, Base64.DEFAULT);

                sendWavAudioToServer(base64Audio);

            } catch (Exception e) {
                // Reset flags on error
                canRecordNext = true;
                isProcessing.set(false);
            }
        }).start();
    }

    private byte[] createWavBytes(byte[] audioData) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            writeWavHeader(baos, audioData.length, SAMPLE_RATE);
            baos.write(audioData);
            return baos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    // Conversation Methods
    private void clearConversationChatbox() {
        runOnUiThread(() -> {
            if (conversationContainer != null) {
                conversationContainer.removeAllViews();

                // Clear dedupe set when conversation is cleared
                if (seenSegments != null) seenSegments.clear();

                // Add initial message
                LinearLayout initialMessage = new LinearLayout(this);
                initialMessage.setOrientation(LinearLayout.HORIZONTAL);
                initialMessage.setPadding(4, 2, 4, 2);

                TextView initialText = new TextView(this);
                initialText.setText("Conversation started - listening for speech...");
                initialText.setTextSize(12);
                initialText.setTextColor(getResources().getColor(android.R.color.darker_gray));
                initialText.setPadding(4, 2, 4, 2);

                initialMessage.addView(initialText);
                conversationContainer.addView(initialMessage);

                // Scroll to top after clearing
                scrollToBottom();
            }
        });
    }

    // Create a stable key for a segment to detect duplicates
    private String makeSegmentKey(org.json.JSONObject segment, String chunkId) {
        double start = segment.optDouble("start_time", segment.optDouble("start", -1.0));
        double end = segment.optDouble("end_time", segment.optDouble("end", -1.0));
        String speaker = segment.optString("speaker_id", segment.optString("speaker", "UNKNOWN"));
        String text = segment.optString("text", segment.optString("transcription", ""));
        String shortText = text.length() > 40 ? text.substring(0, 40) : text;
        return String.format("%s:%.3f-%.3f:%s:%s", chunkId == null ? "" : chunkId, start, end, speaker, shortText);
    }

    private void scrollToBottom() {
        if (chatScrollView != null) {
            chatScrollView.post(() -> {
                chatScrollView.fullScroll(ScrollView.FOCUS_DOWN);
                // Additional smooth scroll for better UX
                chatScrollView.smoothScrollTo(0, chatScrollView.getChildAt(0).getHeight());
            });
        }
    }

    private void addMessageToConversation(String speakerId, String speechText) {

        runOnUiThread(() -> {
            if (conversationContainer != null) {
                // Remove initial message if it exists
                if (conversationContainer.getChildCount() == 1) {
                    try {
                        View firstChild = conversationContainer.getChildAt(0);
                        if (firstChild instanceof TextView) {
                            TextView firstTextView = (TextView) firstChild;
                            if (firstTextView.getText().toString().contains("Conversation started")) {
                                conversationContainer.removeViewAt(0);
                            }
                        } else if (firstChild instanceof LinearLayout) {
                            LinearLayout firstLayout = (LinearLayout) firstChild;
                            if (firstLayout.getChildCount() > 0) {
                                View firstSubChild = firstLayout.getChildAt(0);
                                if (firstSubChild instanceof TextView) {
                                    TextView firstTextView = (TextView) firstSubChild;
                                    if (firstTextView.getText().toString().contains("Conversation started")) {
                                        conversationContainer.removeViewAt(0);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                    }
                }

                // Check if this is the wearer's message
                boolean isWearerMessage = speakerId.equals("YOU");

                // Create outer horizontal container for alignment
                LinearLayout outerLayout = new LinearLayout(this);
                outerLayout.setOrientation(LinearLayout.HORIZONTAL);
                outerLayout.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));

                // Set gravity to align messages: right for wearer, left for others
                if (isWearerMessage) {
                    outerLayout.setGravity(android.view.Gravity.END);  // Right align
                } else {
                    outerLayout.setGravity(android.view.Gravity.START);  // Left align
                }

                // Create message layout - use vertical for better text display
                LinearLayout messageLayout = new LinearLayout(this);
                messageLayout.setOrientation(LinearLayout.VERTICAL);
                messageLayout.setPadding(MESSAGE_PADDING_H, MESSAGE_PADDING_V,
                        MESSAGE_PADDING_H, MESSAGE_PADDING_V);

                // Set message layout to wrap content with max width (85% of screen)
                LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                messageParams.setMargins(8, 4, 8, 4);  // Add margins between messages
                messageLayout.setLayoutParams(messageParams);

                // Speaker name with color-coded labels
                TextView speakerName = new TextView(this);
                String displaySpeakerName = speakerId;
                int speakerColor = getResources().getColor(android.R.color.holo_blue_light);

                // speaker name formatting with color coding
                if (speakerId.equals("YOU")) {
                    // Special handling for wearer - use distinct color
                    displaySpeakerName = "YOU";
                    speakerColor = getResources().getColor(android.R.color.holo_green_light);  // Green for YOU
                } else if (speakerId.equals("USER")) {
                    // Legacy support for USER
                    displaySpeakerName = "YOU";
                    speakerColor = getResources().getColor(android.R.color.holo_green_light);
                } else if (speakerId.startsWith("SPEAKER_") || speakerId.startsWith("Speaker ")) {
                    // Other speakers
                    if (speakerId.startsWith("SPEAKER_")) {
                        String speakerNumber = speakerId.substring(8);
                        displaySpeakerName = "Speaker " + speakerNumber;
                    } else {
                        displaySpeakerName = speakerId;
                    }

                    // Use different colors for other speakers
                    speakerColor = getResources().getColor(android.R.color.holo_blue_light);
                } else {
                    // Default for any other speaker ID
                    displaySpeakerName = speakerId;
                }

                speakerName.setText(displaySpeakerName);
                speakerName.setTextSize(SPEAKER_NAME_TEXT_SIZE);
                speakerName.setTextColor(speakerColor);
                speakerName.setPadding(SPEAKER_NAME_PADDING_H, SPEAKER_NAME_PADDING_V,
                        SPEAKER_NAME_PADDING_H, SPEAKER_NAME_PADDING_V);
                speakerName.setTypeface(null, android.graphics.Typeface.BOLD);

                // Set width to wrap content for speaker name
                LinearLayout.LayoutParams speakerParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                speakerName.setLayoutParams(speakerParams);

                // Add background for better visibility
                speakerName.setBackgroundColor(getResources().getColor(R.color.speaker_name_bg));

                // Add subtle border effect
                android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable();
                border.setColor(getResources().getColor(R.color.speaker_name_bg));
                border.setStroke(SPEAKER_NAME_BORDER_WIDTH, speakerColor);
                border.setCornerRadius(SPEAKER_NAME_CORNER_RADIUS);
                speakerName.setBackground(border);

                // Speech text with better readability
                TextView speechTextView = new TextView(this);

                // Check if speech text is empty or null
                String displayText = speechText;
                if (speechText == null || speechText.trim().isEmpty()) {
                    displayText = "[No speech detected]";
                }

                speechTextView.setText(displayText);
                speechTextView.setTextSize(SPEECH_TEXT_SIZE);
                speechTextView.setTextColor(getResources().getColor(android.R.color.white));
                speechTextView.setPadding(SPEECH_PADDING_H, SPEECH_PADDING_V,
                        SPEECH_PADDING_H, SPEECH_PADDING_V);
                speechTextView.setMaxLines(0);
                speechTextView.setSingleLine(false);
                speechTextView.setLineSpacing(SPEECH_LINE_SPACING, SPEECH_LINE_SPACING_MULT);

                // Add background for speech text with different colors for wearer vs others
                android.graphics.drawable.GradientDrawable textBg = new android.graphics.drawable.GradientDrawable();
                if (isWearerMessage) {
                    // Green-tinted background for wearer's messages
                    textBg.setColor(0xFF2D5016);  // Dark green background
                } else {
                    // Default background for other speakers
                    textBg.setColor(getResources().getColor(R.color.speaker_text_bg));
                }
                textBg.setCornerRadius(SPEECH_CORNER_RADIUS);
                speechTextView.setBackground(textBg);

                // Set width to wrap content for speech text with max width constraint
                LinearLayout.LayoutParams speechParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                // Set max width to 85% of screen width for better chat bubble appearance
                android.view.Display display = getWindowManager().getDefaultDisplay();
                android.graphics.Point size = new android.graphics.Point();
                display.getSize(size);
                speechTextView.setMaxWidth((int) (size.x * 0.85));  // 85% of screen width
                speechTextView.setLayoutParams(speechParams);

                messageLayout.addView(speakerName);
                messageLayout.addView(speechTextView);

                // Add message layout to outer layout, then add outer layout to container
                outerLayout.addView(messageLayout);
                conversationContainer.addView(outerLayout);

                // Auto-scroll to bottom with better scrolling behavior
                scrollToBottom();

                // Limit conversation history to prevent memory issues during long conversations
                if (conversationContainer.getChildCount() > 15) {
                    conversationContainer.removeViewAt(0);
                }
            }
        });
    }

    private void updateButtonStates() {
        runOnUiThread(() -> {
            // Sync isConnected with actual WebSocket state
            boolean actuallyConnected = webSocketClient != null && webSocketClient.isOpen();
            if (actuallyConnected != isConnected) {
                Log.w(TAG, "Connection state mismatch! isConnected=" + isConnected + ", actuallyConnected=" + actuallyConnected);
                isConnected = actuallyConnected; // Sync the state
            }

            // Connection button states
            testServerButton.setEnabled(!isConnected);
            disconnectButton.setEnabled(isConnected);

            // Voice Registration button - only enabled when connected and not recording conversation
            if (isConnected && !isRecording) {
                voiceRegistrationButton.setEnabled(true);
                if (isRecordingVoice) {
                    voiceRegistrationButton.setText("Recording Voice...");
                } else if (userVoiceId != null) {
                    voiceRegistrationButton.setText("Re-register Voice");
                } else {
                    voiceRegistrationButton.setText("Register Voice");
                }
            } else {
                voiceRegistrationButton.setEnabled(false);
                if (userVoiceId != null) {
                    voiceRegistrationButton.setText("Voice Registered");
                } else {
                    voiceRegistrationButton.setText("Register Voice");
                }
            }

            // Recording button states
            if (isConnected && !isRecordingVoice) {
                if (!isRecording) {
                    requestButton.setEnabled(true);
                    requestButton.setText("Start Recording");
                    stopButton.setEnabled(false);
                    stopButton.setText("Stop Recording");
                } else {
                    requestButton.setEnabled(false);
                    requestButton.setText("Recording...");
                    stopButton.setEnabled(true);
                    stopButton.setText("Stop Recording");
                }
            } else {
                requestButton.setEnabled(false);
                requestButton.setText("Start Recording");
                stopButton.setEnabled(false);
                stopButton.setText("Stop Recording");
            }
        });
    }

    private void writeWavHeader(ByteArrayOutputStream baos, int audioDataLength, int sampleRate) throws IOException {
        int totalDataLen = audioDataLength + 36;
        int bitDepth = 16;
        int channels = 1;


        // WAV header
        baos.write("RIFF".getBytes());
        baos.write(intToByteArray(totalDataLen), 0, 4);
        baos.write("WAVE".getBytes());
        baos.write("fmt ".getBytes());
        baos.write(intToByteArray(16), 0, 4); // Sub-chunk size
        baos.write(shortToByteArray((short) 1), 0, 2); // Audio format (PCM)
        baos.write(shortToByteArray((short) channels), 0, 2); // Number of channels
        baos.write(intToByteArray(sampleRate), 0, 4); // Sample rate
        baos.write(intToByteArray(sampleRate * channels * bitDepth / 8), 0, 4); // Byte rate
        baos.write(shortToByteArray((short) (channels * bitDepth / 8)), 0, 2); // Block align
        baos.write(shortToByteArray((short) bitDepth), 0, 2); // Bits per sample
        baos.write("data".getBytes());
        baos.write(intToByteArray(audioDataLength), 0, 4);
    }

    private byte[] intToByteArray(int value) {
        return new byte[] {
                (byte) (value & 0xff),
                (byte) ((value >> 8) & 0xff),
                (byte) ((value >> 16) & 0xff),
                (byte) ((value >> 24) & 0xff)
        };
    }

    private byte[] shortToByteArray(short value) {
        return new byte[] {
                (byte) (value & 0xff),
                (byte) ((value >> 8) & 0xff)
        };
    }

    private void sendRealTimeChunk() {
        // Send accumulated chunk buffer as real-time chunk
        synchronized (currentChunkBuffer) {
            if (currentChunkBuffer.isEmpty() || currentChunkBytes == 0) {
                return;
            }

            // Combine chunk buffer into single array
            byte[] chunkAudio = new byte[currentChunkBytes];
            int offset = 0;
            for (byte[] chunk : currentChunkBuffer) {
                System.arraycopy(chunk, 0, chunkAudio, offset, chunk.length);
                offset += chunk.length;
            }

            // Clear chunk buffer for next chunk
            currentChunkBuffer.clear();
            currentChunkBytes = 0;

            // Send chunk in background thread
            new Thread(() -> {
                try {
                    // Create WAV header and combine with audio data
                    byte[] wavBytes = createWavBytes(chunkAudio);
                    if (wavBytes == null) {
                        return;
                    }

                    // Convert to base64
                    String base64Audio = Base64.encodeToString(wavBytes, Base64.DEFAULT);

                    // Send as real-time chunk
                    sendWavAudioToServer(base64Audio, true);

                } catch (Exception e) {
                    Log.e(TAG, "Error sending real-time chunk: " + e.getMessage());
                }
            }).start();
        }
    }

    private void sendWavAudioToServer(String base64Audio) {
        sendWavAudioToServer(base64Audio, false);
    }

    private void sendWavAudioToServer(String base64Audio, boolean isChunk) {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                audioSentCount++;
                String chunkId = (isChunk ? "chunk_" : "android_wav_") + System.currentTimeMillis();

                JSONObject message = new JSONObject();
                message.put("type", "audio_from_glasses");
                message.put("chunk_id", chunkId);
                message.put("audio_data", base64Audio);
                message.put("timestamp", System.currentTimeMillis());
                message.put("format", "wav");
                message.put("sample_rate", SAMPLE_RATE);
                message.put("translation_language", translate_Lang);
                message.put("is_chunk", isChunk); // Mark as real-time chunk

                webSocketClient.send(message.toString());

                // Update status to show audio sent
                runOnUiThread(() -> {
                    if (isChunk) {
                        processingStatus.setText("Real-time processing...");
                    } else {
                        processingStatus.setText("Audio sent to server - Processing...");
                    }
                });

            } catch (JSONException e) {
                runOnUiThread(() -> {
                    processingStatus.setText("Error sending audio to server");
                });
                isProcessing.set(false);
            }
        } else {
            runOnUiThread(() -> {
                processingStatus.setText("Error: Not connected to server");
            });
            isProcessing.set(false);
        }
    }

    private void closeWebSocket() {
        Log.i(TAG, "Closing WebSocket connection...");
        stopClientPing(); // Stop ping when closing

        if (webSocketClient != null) {
            try {
                if (webSocketClient.isOpen()) {
                    Log.i(TAG, "WebSocket is open, closing now");
                    webSocketClient.close();
                } else {
                    Log.i(TAG, "WebSocket already closed");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error closing WebSocket: " + e.getMessage());
            }
            webSocketClient = null;
        } else {
            Log.i(TAG, "WebSocket is null, nothing to close");
        }

        isConnected = false;
        runOnUiThread(() -> {
            connectionStatus.setText("Disconnected");
            connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            processingStatus.setText("Disconnected from server.");
            updateButtonStates();
        });
    }

    private void loadUserVoiceId() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        userVoiceId = prefs.getString(PREF_USER_VOICE_ID, null);

        if (userVoiceId != null) {
            Log.i(TAG, "Loaded saved voice ID: " + userVoiceId);
        }
    }

    private void saveUserVoiceId(String voiceId) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PREF_USER_VOICE_ID, voiceId);
        editor.apply();

        Log.i(TAG, "Saved voice ID to persistent storage: " + voiceId);
    }

    // =============== VOICE REGISTRATION METHODS ===============

    private void startVoiceRecording() {
        try {
            if (!checkAudioPermission()) {
                processingStatus.setText("Audio permission required");
                return;
            }

            // Clear previous recording
            audioChunks.clear();
            totalBytesRecorded = 0;
            hasRecordedVoice = false;
            recordedVoiceData = null;

            isRecordingVoice = true;
            updateButtonStates();
            processingStatus.setText("Recording voice... Speak clearly for 5 seconds");

            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_MULTIPLIER;
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
                Log.e(TAG, "Invalid buffer size for AudioRecord: " + bufferSize);
                return;
            }

            try {
                audioRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException when creating AudioRecord: " + e.getMessage());
                processingStatus.setText("Audio permission denied");
                return;
            }

            if (audioRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecorder failed to initialize");
                audioRecorder.release();
                audioRecorder = null;
                return;
            }

            new Thread(() -> {
                byte[] buffer = new byte[bufferSize];
                long startTime = System.currentTimeMillis();

                try {
                    audioRecorder.startRecording();
                    Log.d(TAG, "Voice recording started");

                    while (isRecordingVoice && (System.currentTimeMillis() - startTime) < VOICE_RECORDING_DURATION_MS) {
                        if (audioRecorder == null) {
                            break;
                        }

                        int bytesRead = audioRecorder.read(buffer, 0, buffer.length);
                        if (bytesRead < 0) {
                            Log.e(TAG, "AudioRecord read failed: " + bytesRead);
                            break;
                        }

                        byte[] audioChunk = new byte[bytesRead];
                        System.arraycopy(buffer, 0, audioChunk, 0, bytesRead);
                        audioChunks.add(audioChunk);
                        totalBytesRecorded += bytesRead;

                        // Update progress
                        long elapsed = System.currentTimeMillis() - startTime;
                        int progress = (int) ((elapsed * 100) / VOICE_RECORDING_DURATION_MS);
                        runOnUiThread(() -> {
                            processingStatus.setText("Recording voice... " + progress + "%");
                        });
                    }

                    // Auto-stop after duration
                    runOnUiThread(() -> {
                        stopVoiceRecording();
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Error during voice recording: " + e.getMessage());
                    runOnUiThread(() -> {
                        stopVoiceRecording();
                    });
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "Error in startVoiceRecording: " + e.getMessage());
            processingStatus.setText("Error starting recording: " + e.getMessage());
        }
    }

    private void stopVoiceRecording() {
        isRecordingVoice = false;

        if (audioRecorder != null) {
            try {
                if (audioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecorder.stop();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping AudioRecorder: " + e.getMessage());
            } finally {
                try {
                    audioRecorder.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing AudioRecorder: " + e.getMessage());
                }
                audioRecorder = null;
            }
        }

        processRecordedVoice();
        updateButtonStates();
    }

    private void processRecordedVoice() {
        if (audioChunks.isEmpty()) {
            processingStatus.setText("No audio recorded. Please try again.");
            return;
        }

        new Thread(() -> {
            try {
                // Combine all audio chunks
                byte[] completeAudio = new byte[totalBytesRecorded];
                int offset = 0;
                for (byte[] chunk : audioChunks) {
                    System.arraycopy(chunk, 0, completeAudio, offset, chunk.length);
                    offset += chunk.length;
                }

                // Create WAV header and combine with audio data
                byte[] wavBytes = createWavBytes(completeAudio);
                if (wavBytes != null) {
                    recordedVoiceData = Base64.encodeToString(wavBytes, Base64.DEFAULT);
                    hasRecordedVoice = true;

                    runOnUiThread(() -> {
                        processingStatus.setText("Voice recorded! Registering with server...");
                        registerVoiceWithServer();
                    });
                } else {
                    runOnUiThread(() -> {
                        processingStatus.setText("Failed to process audio. Please try again.");
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "Error processing recorded voice: " + e.getMessage());
                runOnUiThread(() -> {
                    processingStatus.setText("Error processing audio: " + e.getMessage());
                });
            }
        }).start();
    }

    private void registerVoiceWithServer() {
        if (!isConnected || recordedVoiceData == null) {
            processingStatus.setText("Not connected or no voice data");
            return;
        }

        isProcessing.set(true);
        updateButtonStates();
        processingStatus.setText("Registering voice with server...");

        try {
            JSONObject message = new JSONObject();
            message.put("type", "register_voice");
            message.put("voice_data", recordedVoiceData);
            message.put("sample_rate", SAMPLE_RATE);
            message.put("timestamp", System.currentTimeMillis());

            webSocketClient.send(message.toString());
            Log.i(TAG, "Voice registration request sent to server");

        } catch (JSONException e) {
            Log.e(TAG, "Failed to create registration message: " + e.getMessage());
            isProcessing.set(false);
            updateButtonStates();
            processingStatus.setText("Failed to create registration message");
        }
    }

    // =============== END VOICE REGISTRATION METHODS ===============

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeWebSocket();
    }
}
