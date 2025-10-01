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

    // Simple speaker tracking
    private int speakerCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "=== OPTIMIZED AR GLASSES APP STARTED ===");

        // Initialize UI components
        connectionStatus = findViewById(R.id.connectionStatus);
        processingStatus = findViewById(R.id.processingStatus);
        conversationContainer = findViewById(R.id.conversationContainer);
        chatScrollView = findViewById(R.id.chatScrollView);

        requestButton = findViewById(R.id.requestButton);
        stopButton = findViewById(R.id.stopButton);
        testServerButton = findViewById(R.id.testServerButton);
        disconnectButton = findViewById(R.id.disconnectButton);

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
                Log.d(TAG, "User clicked reconnect button");
                shouldReconnect = true; // Enable auto-reconnect when user manually connects
                reconnectAttempts = 0; // Reset attempts
                reconnectWebSocket();
                processingStatus.setText("Trying to reconnect...");
            } else {
                Log.d(TAG, "User clicked ping button");
                sendPingMessage();
                processingStatus.setText("Manual ping sent to server...");
            }
        });

        requestButton.setOnClickListener(v -> {
            Log.d(TAG, "User clicked start recording button - isRecording: " + isRecording);
            if (checkAudioPermission()) {
                if (!isRecording) {
                    startRecording();
                } else {
                    processingStatus.setText("Already recording! Click Stop to finish.");
                }
            } else {
                Log.w(TAG, "Audio permission not granted, requesting...");
                requestAudioPermission();
            }
        });

        stopButton.setOnClickListener(v -> {
            Log.d(TAG, "User clicked stop recording button");
            stopRecording();
        });

        disconnectButton.setOnClickListener(v -> {
            Log.d(TAG, "User clicked disconnect button");
            shouldReconnect = false; // Disable auto-reconnect when user manually disconnects
            stopClientPing(); // Stop ping when disconnecting
            leaveConversation();
            closeWebSocket();
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
                    Log.d(TAG, "Audio buffer processor interrupted");
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error in audio buffer processor: " + e.getMessage());
                }
            }
        }).start();
    }

    private void processAudioChunk(byte[] audioData) {
        // Don't process audio if not in conversation
        if (!isInConversation) {
            Log.d(TAG, "Not in conversation, ignoring audio chunk");
            return;
        }

        // Add to audio chunks
        audioChunks.add(audioData);
        totalBytesRecorded += audioData.length;

        // Log every 10 chunks to avoid spam
        if (audioChunks.size() % 10 == 0) {
            Log.d(TAG, "Audio chunk processed: #" + audioChunks.size() + ", " + audioData.length + " bytes, total: " + totalBytesRecorded + " bytes");
        }

        // Continuous recording - no speech detection, just record everything
        long currentTime = System.currentTimeMillis();
        lastSpeechTime = currentTime;
        if (!hasDetectedSpeech) {
            hasDetectedSpeech = true;
            Log.d(TAG, "Continuous recording started - no silence detection");
        }

        // Continuous recording - no UI status updates needed
    }

    private void joinConversation() {
        if (!checkAudioPermission()) {
            Log.w(TAG, "Audio permission not granted, requesting...");
            requestAudioPermission();
            return;
        }

        if (isInConversation) {
            Log.d(TAG, "Already in conversation, ignoring join request");
            return;
        }

        Log.i(TAG, "=== JOINING CONVERSATION ===");
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
            Log.d(TAG, "=== START RECORDING CALLED ===");
            Log.d(TAG, "isRecording: " + isRecording);
            Log.d(TAG, "isConnected: " + isConnected);

            if (!checkAudioPermission()) {
                Log.w(TAG, "Audio permission not granted, requesting...");
                requestAudioPermission();
                return;
            }

            if (isRecording) {
                Log.d(TAG, "Already recording, ignoring start request");
                return;
            }

            Log.i(TAG, "=== STARTING RECORDING ===");
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
            Log.d(TAG, "Set isRecording = true");
            updateButtonStates();

            // Start recording
            startSingleRecording();

            Log.d(TAG, "=== START RECORDING COMPLETED ===");
        } catch (Exception e) {
            Log.e(TAG, "Error in startRecording: " + e.getMessage(), e);
            runOnUiThread(() -> {
                processingStatus.setText("Error starting recording: " + e.getMessage());
            });
        }
    }

    private void stopRecording() {
        Log.d(TAG, "=== STOP RECORDING CALLED ===");
        Log.d(TAG, "isRecording: " + isRecording);
        Log.d(TAG, "isInConversation: " + isInConversation);

        if (!isRecording) {
            Log.d(TAG, "Not recording, ignoring stop request");
            return;
        }

        Log.i(TAG, "=== STOPPING RECORDING ===");

        // Update status to show processing
        runOnUiThread(() -> {
            processingStatus.setText("📤 Stopping recording and processing...");
        });

        // Stop the recording but don't reset isRecording yet
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

        // Reset recording state
        isRecording = false;
        isRecordingState = false;

        // Update button states - this will change button to "Start Recording"
        updateButtonStates();

        // Process and send the recorded audio
        processAndSendAudio();

        Log.d(TAG, "=== STOP RECORDING COMPLETED ===");
    }

    private void leaveConversation() {
        Log.i(TAG, "=== LEAVING CONVERSATION ===");
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
                    Log.d(TAG, "AudioRecorder stopped successfully");
                }
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException when stopping AudioRecorder: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Exception when stopping AudioRecorder: " + e.getMessage());
            } finally {
                try {
                    audioRecorder.release();
                    Log.d(TAG, "AudioRecorder released successfully");
                } catch (Exception e) {
                    Log.e(TAG, "Exception when releasing AudioRecorder: " + e.getMessage());
                }
                audioRecorder = null;
            }
        }


        // Clear audio buffer
        audioBuffer.clear();
        audioChunks.clear();

        runOnUiThread(() -> {
            Log.d(TAG, "Setting processing status to: Left conversation. Click 'Join Conversation' to rejoin.");
            processingStatus.setText("Ready");
            updateButtonStates();
            Log.d(TAG, "Processing status set successfully");
        });

        // Add multiple delayed updates to ensure the status is set correctly
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            runOnUiThread(() -> {
                Log.d(TAG, "Delayed status update 1 - ensuring correct status");
                processingStatus.setText("Ready");
            });
        }, 100);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            runOnUiThread(() -> {
                Log.d(TAG, "Delayed status update 2 - forcing correct status");
                processingStatus.setText("Ready");
            });
        }, 500);

        Log.i(TAG, "=== CONVERSATION STATS ===");
        Log.i(TAG, "Audio sent: " + audioSentCount);
        Log.i(TAG, "Audio received: " + audioReceivedCount);
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
        Log.d(TAG, "Client ping started - will ping every 20 seconds");
    }

    private void stopClientPing() {
        if (pingRunnable != null) {
            pingHandler.removeCallbacks(pingRunnable);
            pingRunnable = null;
            Log.d(TAG, "Client ping stopped");
        }
    }

    private void sendPingMessage() {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                JSONObject pingMessage = new JSONObject();
                pingMessage.put("type", "ping");
                pingMessage.put("timestamp", System.currentTimeMillis());
                webSocketClient.send(pingMessage.toString());
                Log.d(TAG, "Ping message sent to server");
                // Don't update UI for automatic pings
            } catch (JSONException e) {
                Log.e(TAG, "Failed to create ping message: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "Cannot send ping - WebSocket not connected");
        }
    }

    private void sendResetSessionMessage() {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                JSONObject resetMessage = new JSONObject();
                resetMessage.put("type", "reset_session");
                resetMessage.put("timestamp", System.currentTimeMillis());

                webSocketClient.send(resetMessage.toString());
                Log.i(TAG, "Reset session message sent to server");
                processingStatus.setText("Session reset - ready to start fresh");
            } catch (JSONException e) {
                Log.e(TAG, "Failed to create reset session message: " + e.getMessage());
                processingStatus.setText("Failed to reset session");
            }
        } else {
            Log.w(TAG, "WebSocket not connected, cannot send reset session message");
        }
    }


    private boolean checkAudioPermission() {
        boolean hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        Log.d(TAG, "Audio permission check: " + hasPermission);
        return hasPermission;
    }

    private void requestAudioPermission() {
        Log.d(TAG, "Requesting audio permission...");
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                RECORD_AUDIO_PERMISSION_CODE);
    }

    private void reconnectWebSocket() {
        Log.d(TAG, "Reconnecting WebSocket...");
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
                Log.d(TAG, "Microphone permission granted");
                processingStatus.setText("Microphone permission granted. Ready to join conversation!");
                updateButtonStates();
            } else {
                Log.e(TAG, "Microphone permission denied");
                processingStatus.setText("Microphone permission is required to use this feature!");
            }
        }
    }

    private void setupWebSocket() {
        try {
            Log.d(TAG, "Setting up WebSocket connection to: " + SERVER_URL);
            URI uri = new URI(SERVER_URL);
            webSocketClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    Log.i(TAG, "=== WEBSOCKET CONNECTED ===");
                    Log.i(TAG, "Server: " + handshake.getHttpStatusMessage());
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
                                    Log.d(TAG, "Received pong from server");
                                    connectionStatus.setText("Connected");
                                    connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                                    processingStatus.setText("Server is alive! (Status: " + response.optInt("status_code", 0) + ")");
                                    break;

                                case "keep_alive":
                                    Log.d(TAG, "Received keep-alive from server");
                                    // Update connection status to show it's alive
                                    connectionStatus.setText("Connected");
                                    connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                                    break;

                                case "processing_started":
                                    Log.d(TAG, "Processing started");
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

                                        // Android-side filtering
                                        Log.i(TAG, "=== 📨 RECEIVED SEGMENT ===");
                                        Log.i(TAG, "Segment #" + audioReceivedCount + " received immediately");
                                        Log.i(TAG, "Speaker: " + speakerName);
                                        Log.i(TAG, "Text: '" + text + "'");
                                        Log.i(TAG, "Text length: " + (text != null ? text.length() : 0));
                                        Log.i(TAG, "Is valid speech: " + isValidSpeech(text));

                                        if (isValidSpeech(text)) {
                                            addMessageToConversation(speakerName, text);
                                        } else {
                                            Log.d(TAG, "Speech filtered out as background noise");
                                        }

                                    } catch (JSONException e) {
                                        Log.e(TAG, "Failed to parse segment: " + e.getMessage());
                                        processingStatus.setText("Error parsing server response");
                                    }
                                    break;

                                case "no_speech":
                                    Log.d(TAG, "No speech detected by server");
                                    break;

                                case "audio_received":
                                    // Server confirmed it received the audio
                                    canRecordNext = true;
                                    Log.d(TAG, "=== AUDIO RECEIVED CONFIRMATION ===");
                                    Log.d(TAG, "Server confirmed audio received");
                                    Log.d(TAG, "Can record next: " + canRecordNext);


                                    break;

                                case "audio_processed":
                                    Log.i(TAG, "=== AUDIO PROCESSING COMPLETED ===");
                                    Log.i(TAG, "Total segments: " + response.optInt("total_segments", 0));

                                    // Update status to show processing completed
                                    runOnUiThread(() -> {
                                        processingStatus.setText("✅ Processing completed - Ready to record again");
                                    });

                                    updateButtonStates();
                                    break;

                                case "speakers_list":
                                    Log.i(TAG, "=== SPEAKERS LIST RECEIVED ===");
                                    processingStatus.setText("Speakers auto-detected during conversation");
                                    break;

                                case "error":
                                    Log.e(TAG, "=== SERVER ERROR ===");
                                    try {
                                        String error = response.getString("error");
                                        Log.e(TAG, "Error message: " + error);
                                        processingStatus.setText("Error: " + error);
                                    } catch (JSONException e) {
                                        Log.e(TAG, "Unknown error occurred");
                                        processingStatus.setText("Unknown error occurred");
                                    }
                                    isProcessing.set(false);
                                    updateButtonStates();
                                    break;

                                default:
                                    Log.w(TAG, "Unknown message type: " + type);
                            }
                        });
                    } catch (JSONException e) {
                        Log.e(TAG, "Failed to parse JSON message: " + e.getMessage());
                        runOnUiThread(() -> {
                            processingStatus.setText("Received non-JSON message");
                        });
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.w(TAG, "=== WEBSOCKET DISCONNECTED ===");
                    Log.w(TAG, "Code: " + code + ", Reason: " + reason + ", Remote: " + remote);
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
                        Log.i(TAG, "Attempting to reconnect... (attempt " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ")");
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
                        Log.e(TAG, "Max reconnection attempts reached. Please reconnect manually.");
                        runOnUiThread(() -> {
                            processingStatus.setText("Connection lost. Please reconnect manually.");
                        });
                    }
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "=== WEBSOCKET ERROR ===");
                    Log.e(TAG, "Error: " + ex.getMessage());
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
            Log.e(TAG, "WebSocket setup failed: " + e.getMessage());
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
            Log.d(TAG, "=== STARTING SINGLE RECORDING ===");

            // Clear previous recording data
            audioChunks.clear();
            totalBytesRecorded = 0;
            hasDetectedSpeech = false;
            recordingStartTime = System.currentTimeMillis();
            lastSpeechTime = recordingStartTime;

            // Set recording state
            isRecordingState = true;
            runOnUiThread(() -> {
                processingStatus.setText("🔴 RECORDING - Speak now...");
            });

            Log.d(TAG, "Recording parameters:");
            Log.d(TAG, "Sample rate: " + SAMPLE_RATE);
            Log.d(TAG, "Min recording duration: " + MIN_RECORDING_DURATION_MS + "ms");
            Log.d(TAG, "Max recording duration: UNLIMITED (for speaker recognition testing)");

            if (!checkAudioPermission()) {
                Log.e(TAG, "Audio permission not granted");
                runOnUiThread(() -> {
                    processingStatus.setText("Error: Audio permission required");
                });
                return;
            }

            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_MULTIPLIER;
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
                Log.e(TAG, "Invalid buffer size for AudioRecord: " + bufferSize);
                return;
            }

            Log.d(TAG, "AudioRecord buffer size: " + bufferSize);

            try {
                audioRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
                Log.d(TAG, "AudioRecord created successfully");
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException when creating AudioRecord: " + e.getMessage());
                runOnUiThread(() -> {
                    processingStatus.setText("Error: Audio permission denied");
                });
                return;
            }

            if (audioRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecorder failed to initialize. State: " + audioRecorder.getState());
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
                    Log.d(TAG, "AudioRecorder started successfully");
                } catch (SecurityException e) {
                    Log.e(TAG, "SecurityException when starting recording: " + e.getMessage());
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
                        Log.e(TAG, "AudioRecord read failed: " + bytesRead);
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
                        Log.d(TAG, "Recording progress: " + chunkCount + " chunks, " + totalBytesRead + " bytes");
                    }
                }

                Log.d(TAG, "Recording finished: " + chunkCount + " chunks, " + totalBytesRead + " total bytes");

            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Error in startSingleRecording: " + e.getMessage(), e);
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

        // Reset recording state
        isRecording = false;
        isRecordingState = false;
        runOnUiThread(() -> {
            processingStatus.setText("📤 Sending audio to server...");
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
        Log.d(TAG, "=== PROCESS AND SEND AUDIO CALLED ===");
        Log.d(TAG, "canRecordNext: " + canRecordNext);
        Log.d(TAG, "audioChunks.size(): " + audioChunks.size());
        Log.d(TAG, "totalBytesRecorded: " + totalBytesRecorded);

        if (!canRecordNext) {
            Log.w(TAG, "Cannot record next, skipping audio processing");
            return;
        }

        if (audioChunks.isEmpty()) {
            Log.w(TAG, "No audio chunks recorded, skipping audio processing");
            canRecordNext = true;
            isProcessing.set(false);
            return;
        }

        // Mark that we're sending audio
        canRecordNext = false;
        isProcessing.set(true);

        new Thread(() -> {
            try {
                Log.d(TAG, "=== PROCESSING AUDIO IN BACKGROUND THREAD ===");

                // Combine all audio chunks into one array
                byte[] completeAudio = new byte[totalBytesRecorded];
                int offset = 0;
                for (byte[] chunk : audioChunks) {
                    System.arraycopy(chunk, 0, completeAudio, offset, chunk.length);
                    offset += chunk.length;
                }

                Log.d(TAG, "Combined audio length: " + completeAudio.length + " bytes");
                Log.d(TAG, "Expected total bytes: " + totalBytesRecorded);

                // Create WAV header and combine with audio data
                byte[] wavBytes = createWavBytes(completeAudio);

                if (wavBytes == null) {
                    Log.e(TAG, "Failed to create WAV bytes");
                    canRecordNext = true;
                    isProcessing.set(false);
                    return;
                }

                Log.d(TAG, "WAV bytes created: " + wavBytes.length + " bytes");

                // Convert to base64 and send directly
                String base64Audio = Base64.encodeToString(wavBytes, Base64.DEFAULT);
                Log.d(TAG, "Base64 audio length: " + base64Audio.length() + " characters");

                sendWavAudioToServer(base64Audio);

            } catch (Exception e) {
                Log.e(TAG, "Failed to process audio: " + e.getMessage(), e);
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
            Log.e(TAG, "Failed to create WAV bytes: " + e.getMessage());
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
        Log.d(TAG, "=== ADDING MESSAGE TO CONVERSATION ===");
        Log.d(TAG, "Speaker ID: " + speakerId);
        Log.d(TAG, "Speech Text: " + speechText);
        Log.d(TAG, "Conversation Container: " + (conversationContainer != null ? "NOT NULL" : "NULL"));

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
                        Log.w(TAG, "Error removing initial message: " + e.getMessage());
                    }
                }

                // Create message layout - use vertical for better text display
                LinearLayout messageLayout = new LinearLayout(this);
                messageLayout.setOrientation(LinearLayout.VERTICAL);
                messageLayout.setPadding(6, 4, 6, 4);
                messageLayout.setBackgroundColor(0x10000000); // Very light background

                // Speaker name (visible and bold with better formatting)
                TextView speakerName = new TextView(this);
                String displaySpeakerName = speakerId;

                // Simple speaker name formatting
                if (speakerId.startsWith("SPEAKER_")) {
                    displaySpeakerName = "Speaker " + speakerId.substring(8);
                }

                speakerName.setText(displaySpeakerName);
                speakerName.setTextSize(13);
                speakerName.setTextColor(getResources().getColor(android.R.color.holo_blue_light));
                speakerName.setPadding(6, 3, 6, 3);
                speakerName.setTypeface(null, android.graphics.Typeface.BOLD);

                // Add background for better visibility
                speakerName.setBackgroundColor(0x20000000);
                speakerName.setPadding(8, 4, 8, 4);

                // Speech text (readable)
                TextView speechTextView = new TextView(this);

                // Check if speech text is empty or null
                String displayText = speechText;
                if (speechText == null || speechText.trim().isEmpty()) {
                    displayText = "[No speech detected]";
                    Log.w(TAG, "Speech text is empty or null, using placeholder");
                }

                speechTextView.setText(displayText);
                speechTextView.setTextSize(13);
                speechTextView.setTextColor(getResources().getColor(android.R.color.white));
                speechTextView.setPadding(4, 2, 4, 4);
                speechTextView.setMaxLines(0);
                speechTextView.setSingleLine(false);
                speechTextView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));

                Log.d(TAG, "Created speech TextView with text: '" + displayText + "'");
                Log.d(TAG, "Original speech text: '" + speechText + "'");
                Log.d(TAG, "Speech text length: " + (speechText != null ? speechText.length() : 0));

                messageLayout.addView(speakerName);
                messageLayout.addView(speechTextView);
                conversationContainer.addView(messageLayout);

                Log.d(TAG, "Message added successfully. Total messages: " + conversationContainer.getChildCount());

                // Auto-scroll to bottom with better scrolling behavior
                scrollToBottom();

                // Limit conversation history to prevent memory issues during long conversations
                if (conversationContainer.getChildCount() > 15) {
                    conversationContainer.removeViewAt(0);
                    Log.d(TAG, "Removed oldest message. Current count: " + conversationContainer.getChildCount());
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

        Log.d(TAG, "Writing WAV header - Data length: " + audioDataLength + ", Sample rate: " + sampleRate);

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
                Log.i(TAG, "=== AUDIO SENT TO SERVER ===");
                Log.i(TAG, "Chunk ID: " + chunkId);
                Log.i(TAG, "Base64 length: " + base64Audio.length() + " characters");
                Log.i(TAG, "Audio sent count: " + audioSentCount);

                // Update status to show audio sent
                runOnUiThread(() -> {
                    processingStatus.setText("📡 Audio sent to server - Processing...");
                });

            } catch (JSONException e) {
                Log.e(TAG, "Failed to create audio message: " + e.getMessage());
                runOnUiThread(() -> {
                    processingStatus.setText("Error sending audio to server");
                });
                isProcessing.set(false);
            }
        } else {
            Log.e(TAG, "Cannot send audio - WebSocket not connected");
            runOnUiThread(() -> {
                processingStatus.setText("Error: Not connected to server");
            });
            isProcessing.set(false);
        }
    }

    private void closeWebSocket() {
        Log.d(TAG, "Closing WebSocket connection...");
        stopClientPing(); // Stop ping when closing
        if (webSocketClient != null && webSocketClient.isOpen()) {
            webSocketClient.close();
            Log.d(TAG, "WebSocket closed successfully");
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
        Log.d(TAG, "=== APP DESTROYED ===");
        closeWebSocket();
    }
}
