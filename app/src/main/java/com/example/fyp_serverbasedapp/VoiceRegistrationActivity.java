package com.example.fyp_serverbasedapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.AudioFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class VoiceRegistrationActivity extends AppCompatActivity {

    private static final String TAG = "VOICE_REGISTRATION";
    private static final String SERVER_URL = "ws://192.168.0.112:8000";
    private WebSocketClient webSocketClient;
    private static final int RECORD_AUDIO_PERMISSION_CODE = 1;

    // Audio recording parameters
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE_MULTIPLIER = 4;
    private static final int RECORDING_DURATION_MS = 5000; // 5 seconds for voice registration

    private boolean isRecording = false;
    private boolean isConnected = false;
    private AudioRecord audioRecorder;
    private List<byte[]> audioChunks = new ArrayList<>();
    private int totalBytesRecorded = 0;
    private AtomicBoolean isProcessing = new AtomicBoolean(false);

    // UI Components
    private TextView statusText;
    private TextView instructionText;
    private Button recordButton;
    private Button registerButton;
    private Button backButton;
    private LinearLayout progressContainer;
    private TextView progressText;

    // Registration state
    private boolean hasRecordedVoice = false;
    private String recordedVoiceData = null;
    private String userVoiceId = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_registration);

        Log.d(TAG, "=== VOICE REGISTRATION ACTIVITY STARTED ===");

        // Initialize UI components
        statusText = findViewById(R.id.statusText);
        instructionText = findViewById(R.id.instructionText);
        recordButton = findViewById(R.id.recordButton);
        registerButton = findViewById(R.id.registerButton);
        backButton = findViewById(R.id.backButton);
        progressContainer = findViewById(R.id.progressContainer);
        progressText = findViewById(R.id.progressText);

        // Set initial states
        updateButtonStates();
        statusText.setText("Connecting to server...");
        instructionText.setText("Please speak clearly for 5 seconds when recording starts");

        setupWebSocket();

        if (!checkAudioPermission()) {
            requestAudioPermission();
        }

        recordButton.setOnClickListener(v -> {
            if (checkAudioPermission()) {
                if (!isRecording) {
                    startVoiceRecording();
                } else {
                    stopVoiceRecording();
                }
            } else {
                requestAudioPermission();
            }
        });

        registerButton.setOnClickListener(v -> {
            if (hasRecordedVoice && recordedVoiceData != null) {
                registerVoiceWithServer();
            } else {
                Toast.makeText(this, "Please record your voice first", Toast.LENGTH_SHORT).show();
            }
        });

        backButton.setOnClickListener(v -> {
            finish();
        });
    }

    private void setupWebSocket() {
        try {
            Log.d(TAG, "Setting up WebSocket connection to: " + SERVER_URL);
            URI uri = new URI(SERVER_URL);
            webSocketClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    Log.i(TAG, "=== WEBSOCKET CONNECTED ===");
                    isConnected = true;
                    runOnUiThread(() -> {
                        statusText.setText("Connected to server");
                        statusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
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
                                case "processing_status":
                                    statusText.setText("Server processing... Please wait");
                                    statusText.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));
                                    break;
                                case "voice_registered":
                                    String voiceId = response.optString("voice_id", "unknown");
                                    userVoiceId = voiceId;
                                    statusText.setText("Voice registered successfully!");
                                    statusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                                    instructionText.setText("Your voice ID: " + voiceId);
                                    updateButtonStates();
                                    Toast.makeText(VoiceRegistrationActivity.this,
                                            "Voice registered successfully!", Toast.LENGTH_LONG).show();
                                    break;

                                case "voice_registration_error":
                                    String error = response.optString("error", "Unknown error");
                                    statusText.setText("Registration failed: " + error);
                                    statusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                                    updateButtonStates();
                                    break;

                                case "voice_verification":
                                    boolean isVerified = response.optBoolean("verified", false);
                                    if (isVerified) {
                                        statusText.setText("Voice verified successfully!");
                                        statusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                                    } else {
                                        statusText.setText("Voice verification failed");
                                        statusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                                    }
                                    break;

                                default:
                                    Log.w(TAG, "Unknown message type: " + type);
                            }
                        });
                    } catch (JSONException e) {
                        Log.e(TAG, "Failed to parse JSON message: " + e.getMessage());
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.w(TAG, "=== WEBSOCKET DISCONNECTED ===");
                    isConnected = false;
                    runOnUiThread(() -> {
                        statusText.setText("Disconnected from server");
                        statusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                        updateButtonStates();
                    });
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "=== WEBSOCKET ERROR ===");
                    Log.e(TAG, "Error: " + ex.getMessage());
                    isConnected = false;
                    runOnUiThread(() -> {
                        statusText.setText("Connection error");
                        statusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                        updateButtonStates();
                    });
                }
            };
            webSocketClient.connect();
        } catch (Exception e) {
            Log.e(TAG, "WebSocket setup failed: " + e.getMessage());
            isConnected = false;
            runOnUiThread(() -> {
                statusText.setText("Connection failed");
                statusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                updateButtonStates();
            });
        }
    }

    private void startVoiceRecording() {
        try {
            Log.d(TAG, "=== STARTING VOICE RECORDING ===");

            if (!checkAudioPermission()) {
                Log.e(TAG, "Audio permission not granted");
                Toast.makeText(this, "Audio permission required", Toast.LENGTH_SHORT).show();
                return;
            }

            // Clear previous recording
            audioChunks.clear();
            totalBytesRecorded = 0;
            hasRecordedVoice = false;
            recordedVoiceData = null;

            isRecording = true;
            updateButtonStates();
            statusText.setText("Recording... Speak clearly for 5 seconds");
            statusText.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));

            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_MULTIPLIER;
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
                Log.e(TAG, "Invalid buffer size for AudioRecord: " + bufferSize);
                return;
            }

            try {
                audioRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException when creating AudioRecord: " + e.getMessage());
                Toast.makeText(this, "Audio permission denied", Toast.LENGTH_SHORT).show();
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
                    Log.d(TAG, "AudioRecorder started successfully");

                    while (isRecording && (System.currentTimeMillis() - startTime) < RECORDING_DURATION_MS) {
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
                        int progress = (int) ((elapsed * 100) / RECORDING_DURATION_MS);
                        runOnUiThread(() -> {
                            progressText.setText("Recording... " + progress + "%");
                        });
                    }

                    // Auto-stop after duration
                    runOnUiThread(() -> {
                        stopVoiceRecording();
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Error during recording: " + e.getMessage());
                    runOnUiThread(() -> {
                        stopVoiceRecording();
                    });
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "Error in startVoiceRecording: " + e.getMessage());
            Toast.makeText(this, "Error starting recording: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopVoiceRecording() {
        Log.d(TAG, "=== STOPPING VOICE RECORDING ===");

        isRecording = false;

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

        // Process recorded audio
        processRecordedAudio();
        updateButtonStates();
    }

    private void processRecordedAudio() {
        if (audioChunks.isEmpty()) {
            statusText.setText("No audio recorded. Please try again.");
            statusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
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
                        statusText.setText("Voice recorded successfully! Click Register to save.");
                        statusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                        instructionText.setText("Voice data ready for registration");
                        updateButtonStates();
                    });
                } else {
                    runOnUiThread(() -> {
                        statusText.setText("Failed to process audio. Please try again.");
                        statusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "Error processing recorded audio: " + e.getMessage());
                runOnUiThread(() -> {
                    statusText.setText("Error processing audio: " + e.getMessage());
                    statusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                });
            }
        }).start();
    }

    private void registerVoiceWithServer() {
        if (!isConnected || recordedVoiceData == null) {
            Toast.makeText(this, "Not connected to server or no voice data", Toast.LENGTH_SHORT).show();
            return;
        }

        isProcessing.set(true);
        updateButtonStates();
        statusText.setText("Registering voice with server...");
        statusText.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));

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
            statusText.setText("Failed to create registration message");
            statusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
        }
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

    private void updateButtonStates() {
        runOnUiThread(() -> {
            if (isConnected) {
                if (isRecording) {
                    recordButton.setEnabled(true);
                    recordButton.setText("Stop Recording");
                    registerButton.setEnabled(false);
                } else if (hasRecordedVoice) {
                    recordButton.setEnabled(true);
                    recordButton.setText("Record Again");
                    registerButton.setEnabled(!isProcessing.get());
                } else {
                    recordButton.setEnabled(true);
                    recordButton.setText("Start Recording");
                    registerButton.setEnabled(false);
                }
            } else {
                recordButton.setEnabled(false);
                registerButton.setEnabled(false);
            }

            backButton.setEnabled(true);
        });
    }

    private boolean checkAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                RECORD_AUDIO_PERMISSION_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Microphone permission granted");
                updateButtonStates();
            } else {
                Log.e(TAG, "Microphone permission denied");
                Toast.makeText(this, "Microphone permission is required for voice registration", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webSocketClient != null && webSocketClient.isOpen()) {
            webSocketClient.close();
        }
    }
}
