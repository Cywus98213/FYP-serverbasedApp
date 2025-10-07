package com.example.fyp_serverbasedapp;

import android.Manifest;
import android.content.pm.PackageManager;
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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.json.JSONException;

import java.net.URI;
import java.nio.ByteBuffer;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AR_GLASSES_APP";
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
    private Handler pingHandler = new Handler(Looper.getMainLooper());
    private Runnable pingRunnable;
    private AudioRecord audioRecorder;

    // Audio recording parameters
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE_MULTIPLIER = 4; // Increased buffer size


    private static final int MIN_RECORDING_DURATION_MS = 300; // Catch very quick speech
    private static final int OVERLAP_DURATION_MS = 500; // More overlap to prevent cutting


    // Audio data collection with buffering - Minimize downtime
    private List<byte[]> audioChunks = new ArrayList<>();
    private BlockingQueue<byte[]> audioBuffer = new LinkedBlockingQueue<>(50); // Audio buffer
    private int totalBytesRecorded = 0;
    private long lastSpeechTime = 0;
    private long recordingStartTime = 0;

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
    private Switch excludeMyVoiceSwitch;

    // Simple speaker tracking
    private int speakerCount = 0;

    // Voice registration settings
    private boolean excludeMyVoice = false;
    private String userVoiceId = null;

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
        excludeMyVoiceSwitch = findViewById(R.id.excludeMyVoiceSwitch);

        // Set initial states
        updateButtonStates();
        connectionStatus.setText("Disconnected");
        connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
        processingStatus.setText("Please Connect first");

        setupWebSocket();

        if (!checkAudioPermission()) {
            requestAudioPermission();
        }

        testServerButton.setOnClickListener(v -> {
            if (!isConnected) {
                shouldReconnect = true; // Enable auto-reconnect when user manually connects
                reconnectAttempts = 0; // Reset attempts
                reconnectWebSocket();
                processingStatus.setText("Trying to reconnect...");
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
            shouldReconnect = false; // Disable auto-reconnect when user manually disconnects
            stopClientPing(); // Stop ping when disconnecting
            leaveConversation();
            closeWebSocket();
        });

        voiceRegistrationButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, VoiceRegistrationActivity.class);
            startActivity(intent);
        });

        excludeMyVoiceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            excludeMyVoice = isChecked;
            if (isConnected) {
                sendVoiceExclusionSetting();
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

    private void sendPingMessage() {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                JSONObject pingMessage = new JSONObject();
                pingMessage.put("type", "ping");
                pingMessage.put("timestamp", System.currentTimeMillis());
                webSocketClient.send(pingMessage.toString());
                // Don't update UI for automatic pings
            } catch (JSONException e) {
            }
        } else {
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
        } else {
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
        if (webSocketClient != null) {
            webSocketClient.close();
        }
        setupWebSocket();
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

    private void setupWebSocket() {
        try {
            URI uri = new URI(SERVER_URL);
            webSocketClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    isConnected = true;
                    reconnectAttempts = 0; // Reset reconnection attempts on successful connection

                    // Start client-side ping to keep connection alive
                    startClientPing();

                    runOnUiThread(() -> {
                        connectionStatus.setText("Connected");
                        connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                        processingStatus.setText("Connected to server. Ready to join conversation!");
                        updateButtonStates();
                    });
                }

                @Override
                public void onMessage(String message) {
                    try {
                        JSONObject response = new JSONObject(message);
                        String type = response.getString("type");

                        runOnUiThread(() -> {
                            switch (type) {
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
                                        String speaker = segment.getString("speaker_id");
                                        String text = segment.getString("text");

                                        // Simple speaker name formatting
                                        String speakerName = speaker;
                                        if (speaker.startsWith("SPEAKER_")) {
                                            speakerName = "Speaker " + speaker.substring(8); // Remove "SPEAKER_" prefix
                                        }
                                        int segmentNumber = segment.optInt("segment_number", 0);


                                        if (isValidSpeech(text)) {
                                            addMessageToConversation(speakerName, text);
                                        } else {
                                        }

                                    } catch (JSONException e) {
                                        processingStatus.setText("Error parsing server response");
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
                                    if (voiceId != null) {
                                        userVoiceId = voiceId;

                                        // Auto-enable voice identification (show as "YOU")
                                        excludeMyVoice = false;
                                        excludeMyVoiceSwitch.setChecked(false);
                                        sendVoiceExclusionSetting();

                                        processingStatus.setText("Voice registered! You'll show as 'YOU'");
                                    }
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
                    isConnected = false;
                    leaveConversation();
                    runOnUiThread(() -> {
                        connectionStatus.setText("Disconnected");
                        connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                        // Don't override processingStatus here - let leaveConversation() handle it
                        updateButtonStates();
                    });

                    // Auto-reconnect if needed
                    if (shouldReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        reconnectAttempts++;
                        runOnUiThread(() -> {
                            processingStatus.setText("Reconnecting... (attempt " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ")");
                        });

                        // Delay before reconnecting
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (shouldReconnect) {
                                setupWebSocket();
                            }
                        }, 3000); // 3 second delay
                    } else if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                        runOnUiThread(() -> {
                            processingStatus.setText("Connection lost. Please reconnect manually.");
                        });
                    }
                }

                @Override
                public void onError(Exception ex) {
                    ex.printStackTrace();
                    isConnected = false;
                    leaveConversation();
                    runOnUiThread(() -> {
                        connectionStatus.setText("Error");
                        connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                        processingStatus.setText("WebSocket Error: " + ex.getMessage());
                        updateButtonStates();
                    });
                }
            };
            webSocketClient.connect();
        } catch (Exception e) {
            e.printStackTrace();
            isConnected = false;
            runOnUiThread(() -> {
                connectionStatus.setText("Connection Failed");
                connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                processingStatus.setText("WebSocket Connection Failed: " + e.getMessage());
                updateButtonStates();
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

                    // Add to buffer for processing
                    try {
                        audioBuffer.put(audioChunk);
                    } catch (InterruptedException e) {
                        break;
                    }

                    // Log every 50 chunks to avoid spam
                    if (chunkCount % 50 == 0) {
                    }
                }


            }).start();
        } catch (Exception e) {
            runOnUiThread(() -> {
                processingStatus.setText("Error starting recording: " + e.getMessage());
            });
        }
    }

    private void stopSingleRecording() {
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
            processingStatus.setText("Sending audio to server...");
        });
        updateButtonStates();

        // Process and send the recorded audio
        processAndSendAudio();
    }

    // Android-side speech validation
    private boolean isValidSpeech(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }

        String cleanText = text.trim().toLowerCase();

        // Filter out common background noise patterns
        String[] noisePatterns = {
                "um", "uh", "ah", "eh", "oh", "mm", "hmm",
                "background", "noise", "static", "silence",
                "breathing", "cough", "sigh", "yawn"
        };

        for (String pattern : noisePatterns) {
            if (cleanText.equals(pattern)) {
                return false;
            }
        }

        // Filter out very short text (be more lenient with Cantonese)
        if (cleanText.length() < 1) {
            return false;
        }

        // Filter out repetitive characters
        if (cleanText.matches("(.)\\1{2,}")) {
            return false;
        }

        return true;
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

                // Create message layout - use vertical for better text display
                LinearLayout messageLayout = new LinearLayout(this);
                messageLayout.setOrientation(LinearLayout.VERTICAL);
                messageLayout.setPadding(MESSAGE_PADDING_H, MESSAGE_PADDING_V,
                        MESSAGE_PADDING_H, MESSAGE_PADDING_V);
                messageLayout.setBackgroundColor(0x10000000); // Very light background

                // Set message layout to wrap content
                messageLayout.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));

                // Speaker name with color-coded labels
                TextView speakerName = new TextView(this);
                String displaySpeakerName = speakerId;
                int speakerColor = getResources().getColor(android.R.color.holo_blue_light);

                // speaker name formatting with color coding
                if (speakerId.equals("USER")) {
                    // Special handling for registered user
                    displaySpeakerName = "YOU";
                    int colorId = getResources().getIdentifier("user_color", "color", getPackageName());
                    if (colorId != 0) {
                        speakerColor = getResources().getColor(colorId);
                    }
                } else if (speakerId.startsWith("SPEAKER_")) {
                    String speakerNumber = speakerId.substring(8);
                    displaySpeakerName = "Speaker " + speakerNumber;

                    // Get color for this speaker based on number
                    try {
                        int speakerNum = Integer.parseInt(speakerNumber);
                        String colorName = "speaker_" + (speakerNum % 10); // Cycle through 10 colors
                        int colorId = getResources().getIdentifier(colorName, "color", getPackageName());
                        if (colorId != 0) {
                            speakerColor = getResources().getColor(colorId);
                        }
                    } catch (NumberFormatException e) {
                        // Use default color if parsing fails
                    }
                }

                speakerName.setText(displaySpeakerName);
                speakerName.setTextSize(SPEAKER_NAME_TEXT_SIZE);
                speakerName.setTextColor(speakerColor);
                speakerName.setPadding(SPEAKER_NAME_PADDING_H, SPEAKER_NAME_PADDING_V,
                        SPEAKER_NAME_PADDING_H, SPEAKER_NAME_PADDING_V);
                speakerName.setTypeface(null, android.graphics.Typeface.BOLD);

                // Set width to wrap content for speaker name
                speakerName.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));

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

                // Add background for speech text
                android.graphics.drawable.GradientDrawable textBg = new android.graphics.drawable.GradientDrawable();
                textBg.setColor(getResources().getColor(R.color.speaker_text_bg));
                textBg.setCornerRadius(SPEECH_CORNER_RADIUS);
                speechTextView.setBackground(textBg);

                // Set width to wrap content for speech text
                speechTextView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));

                messageLayout.addView(speakerName);
                messageLayout.addView(speechTextView);
                conversationContainer.addView(messageLayout);


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
            // Connection button states
            testServerButton.setEnabled(!isConnected);
            disconnectButton.setEnabled(isConnected);

            // Recording button states
            if (isConnected) {
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

    private void sendWavAudioToServer(String base64Audio) {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                audioSentCount++;
                String chunkId = "android_wav_" + System.currentTimeMillis();

                JSONObject message = new JSONObject();
                message.put("type", "audio_from_glasses");
                message.put("chunk_id", chunkId);
                message.put("audio_data", base64Audio);
                message.put("timestamp", System.currentTimeMillis());
                message.put("format", "wav");
                message.put("sample_rate", SAMPLE_RATE);

                webSocketClient.send(message.toString());

                // Update status to show audio sent
                runOnUiThread(() -> {
                    processingStatus.setText("Audio sent to server - Processing...");
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

    private void sendVoiceExclusionSetting() {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                JSONObject message = new JSONObject();
                message.put("type", "set_voice_exclusion");
                message.put("exclude_voice", excludeMyVoice);
                if (userVoiceId != null) {
                    message.put("voice_id", userVoiceId);
                }
                message.put("timestamp", System.currentTimeMillis());

                webSocketClient.send(message.toString());
            } catch (JSONException e) {
            }
        } else {
        }
    }

    private void closeWebSocket() {
        stopClientPing(); // Stop ping when closing
        if (webSocketClient != null && webSocketClient.isOpen()) {
            webSocketClient.close();
        }

        isConnected = false;
        runOnUiThread(() -> {
            connectionStatus.setText("Disconnected");
            connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            processingStatus.setText("Disconnected from server.");
            updateButtonStates();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeWebSocket();
    }
}
