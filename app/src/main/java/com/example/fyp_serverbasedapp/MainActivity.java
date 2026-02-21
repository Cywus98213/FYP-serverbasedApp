package com.example.fyp_serverbasedapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
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
import android.provider.MediaStore;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.camera2.CameraCharacteristics;
import java.io.ByteArrayOutputStream;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.HandlerThread;
import android.graphics.ImageFormat;
import java.nio.ByteBuffer;

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
import java.io.FileInputStream;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.Thread;
import android.speech.tts.TextToSpeech;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AR_GLASSES_APP";
    private static final String PREFS_NAME = "VoiceRegistrationPrefs";
    private static final String PREF_USER_VOICE_ID = "userVoiceId";

    // WebSocket configuration
    private static final String SERVER_URL = "wss://jovani-unbanded-benjamin.ngrok-free.dev";
    private WebSocketClient webSocketClient;
    private static final int RECORD_AUDIO_PERMISSION_CODE = 1;
    private static final int CAMERA_PERMISSION_CODE = 2;
    private static final int MULTIPLE_PERMISSIONS_CODE = 3;
    private static final int REQUEST_IMAGE_CAPTURE = 3;
    private boolean isRecording = false;
    private boolean isConnected = false;
    private boolean isInConversation = false;
    private boolean isRecordingState = false; // Visual recording state indicator
    private boolean isGestureDetectionActive = false; // Gesture detection toggle state
    private boolean shouldReconnect = true; // Auto-reconnect flag
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private long lastReconnectTime = 0; // Track last reconnect to prevent loops
    private static final long MIN_RECONNECT_INTERVAL_MS = 5000; // Min 5 seconds between reconnects
    private Handler pingHandler = new Handler(Looper.getMainLooper());
    private Runnable pingRunnable;
    private Handler gestureHandler = new Handler(Looper.getMainLooper());
    private Runnable gestureCaptureRunnable;
    private AudioRecord audioRecorder;

    // Background camera for gesture detection
    private android.hardware.camera2.CameraManager cameraManager;
    private android.hardware.camera2.CameraDevice cameraDevice;
    private android.hardware.camera2.CameraCaptureSession captureSession;
    private android.media.ImageReader imageReader;
    private android.os.HandlerThread backgroundThread;
    private android.os.Handler backgroundHandler;
    private String cameraId;
    private static final int GESTURE_SEND_INTERVAL_MS = 500; // Send images to server every 500ms (throttling)
    private long lastGestureSendTime = 0; // Track last time we sent a gesture image
    private Thread audioBufferProcessorThread;
    private volatile boolean isAppDestroyed = false;

    // Separate executor services for parallel processing (containerized)
    private ExecutorService audioProcessingExecutor; // Dedicated thread pool for audio processing
    private ExecutorService gestureProcessingExecutor; // Dedicated thread pool for gesture processing
    private ExecutorService webSocketSendExecutor; // Dedicated thread pool for WebSocket sends (thread-safe)

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
    private TextView gestureDisplay;  // Display box for gesture meaning
    private LinearLayout conversationContainer;
    private ScrollView chatScrollView;
    private Button requestButton, stopButton, testServerButton, disconnectButton;
    private Button voiceRegistrationButton;
    private Button gestureRecognitionButton;


    // Simple speaker tracking
    private int speakerCount = 0;

    // Voice registration settings
    private String userVoiceId = null;

    // Voice registration recording state
    private boolean isRecordingVoice = false;
    private String recordedVoiceData = null;
    private boolean hasRecordedVoice = false;
    private static final int VOICE_RECORDING_DURATION_MS = 5000; // 5 seconds for voice registration

    // Text-to-Speech for gesture announcements
    private TextToSpeech textToSpeech;
    private boolean ttsInitialized = false;
    private String lastSpokenGesture = ""; // Track last spoken gesture to avoid repeating
    private long lastSpokenGestureTime = 0; // Track when last gesture was spoken
    private static final long GESTURE_SPEAK_COOLDOWN_MS = 3000; // 3 seconds cooldown between same gestures


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Detect screen size - allow landscape/portrait on larger screens (AR glasses)
        // Force portrait only on smaller mobile devices
        android.view.Display display = getWindowManager().getDefaultDisplay();
        android.graphics.Point size = new android.graphics.Point();
        display.getSize(size);
        int screenWidth = size.x;
        int screenHeight = size.y;
        int smallerDimension = Math.min(screenWidth, screenHeight);

        // Only force portrait on smaller screens (< 600dp is typically phone)
        // Larger screens (AR glasses, tablets) can use any orientation
        if (smallerDimension < 600) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else {
            // Allow all orientations on larger screens (AR glasses)
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        }

        setContentView(R.layout.activity_main);

        // Load saved user voice ID from persistent storage
        loadUserVoiceId();

        // Initialize UI components
        connectionStatus = findViewById(R.id.connectionStatus);
        processingStatus = findViewById(R.id.processingStatus);
        conversationContainer = findViewById(R.id.conversationContainer);
        chatScrollView = findViewById(R.id.chatScrollView);

        // Configure ScrollView to stretch width and enable scrolling
        if (chatScrollView != null) {
            try {
                // Ensure ScrollView stretches to full width on all screen sizes
                ViewGroup.LayoutParams scrollParams = chatScrollView.getLayoutParams();
                if (scrollParams != null) {
                    scrollParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    chatScrollView.setLayoutParams(scrollParams);
                }

                // CRITICAL: Don't use fillViewport(true) - it prevents scrolling when content is larger
                // Only use fillViewport if content is smaller than viewport (we want scrolling)
                chatScrollView.setFillViewport(false);
                // Ensure smooth scrolling
                chatScrollView.setSmoothScrollingEnabled(true);
                // Enable nested scrolling for better touch handling
                chatScrollView.setNestedScrollingEnabled(true);
                // Enable vertical scrolling
                chatScrollView.setVerticalScrollBarEnabled(true);
                // Make sure ScrollView can scroll
                chatScrollView.setScrollContainer(true);
            } catch (Exception e) {
                Log.e(TAG, "Error configuring ScrollView: " + e.getMessage());
            }
        }

        // Configure conversationContainer to stretch width on all screen sizes
        if (conversationContainer != null) {
            try {
                // Set orientation explicitly (safe operation)
                conversationContainer.setOrientation(LinearLayout.VERTICAL);

                // Post layout parameter setting to ensure parent is available
                conversationContainer.post(() -> {
                    try {
                        ViewGroup parent = (ViewGroup) conversationContainer.getParent();
                        if (parent != null) {
                            // Get existing layout params and modify them safely
                            ViewGroup.LayoutParams existingParams = conversationContainer.getLayoutParams();
                            if (existingParams != null) {
                                // CRITICAL: Set width to MATCH_PARENT to stretch full width on all screens
                                // This allows the container to use full width on AR glasses and mobile
                                existingParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                                existingParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                                conversationContainer.setLayoutParams(existingParams);

                                // Force layout to ensure proper sizing
                                conversationContainer.requestLayout();
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error setting conversationContainer layout params: " + e.getMessage());
                        // Continue without setting layout params - app should still work
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error configuring conversationContainer: " + e.getMessage());
                // Continue without setting layout params - app should still work
            }
        }

        requestButton = findViewById(R.id.requestButton);
        stopButton = findViewById(R.id.stopButton);
        testServerButton = findViewById(R.id.testServerButton);
        disconnectButton = findViewById(R.id.disconnectButton);
        voiceRegistrationButton = findViewById(R.id.voiceRegistrationButton);

        // Create gesture recognition button dynamically with exact same style as recording buttons
        gestureRecognitionButton = new Button(this);
        gestureRecognitionButton.setText("Start Gesture Detection");

        // Copy exact layout params from requestButton
        ViewGroup.LayoutParams requestButtonParams = requestButton.getLayoutParams();
        if (requestButtonParams != null) {
            LinearLayout.LayoutParams gestureParams = new LinearLayout.LayoutParams(
                    requestButtonParams.width,
                    requestButtonParams.height
            );
            if (requestButtonParams instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams requestLinearParams = (LinearLayout.LayoutParams) requestButtonParams;
                gestureParams.setMargins(
                        requestLinearParams.leftMargin,
                        requestLinearParams.topMargin,
                        requestLinearParams.rightMargin,
                        requestLinearParams.bottomMargin
                );
            }
            gestureRecognitionButton.setLayoutParams(gestureParams);
        } else {
            gestureRecognitionButton.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }

        // Copy ALL styling from requestButton to match exactly - font, size, color, etc.
        gestureRecognitionButton.setBackground(requestButton.getBackground());
        gestureRecognitionButton.setTextColor(requestButton.getTextColors());

        // Copy text size (in scaled pixels)
        float textSize = requestButton.getTextSize();
        gestureRecognitionButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSize);

        // Copy typeface (font family and style)
        android.graphics.Typeface typeface = requestButton.getTypeface();
        if (typeface != null) {
            gestureRecognitionButton.setTypeface(typeface);
        }

        // Copy padding
        gestureRecognitionButton.setPadding(
                requestButton.getPaddingLeft(),
                requestButton.getPaddingTop(),
                requestButton.getPaddingRight(),
                requestButton.getPaddingBottom()
        );

        // Copy gravity
        gestureRecognitionButton.setGravity(requestButton.getGravity());

        // Copy min dimensions
        gestureRecognitionButton.setMinHeight(requestButton.getMinHeight());
        gestureRecognitionButton.setMinWidth(requestButton.getMinWidth());

        // Copy letter spacing if available (API 21+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            gestureRecognitionButton.setLetterSpacing(requestButton.getLetterSpacing());
        }

        // Copy text transformation (all caps, etc.)
        if (requestButton.getTransformationMethod() != null) {
            gestureRecognitionButton.setTransformationMethod(requestButton.getTransformationMethod());
        }


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

        // Set up uncaught exception handler for crash handling
        setupCrashHandler();

        // Initialize Text-to-Speech for gesture announcements
        initializeTextToSpeech();

        // Initialize executor services for parallel processing
        initializeExecutorServices();

        setupWebSocket();

        // Request permissions at startup - always request both together
        boolean needsAudio = !checkAudioPermission();
        boolean needsCamera = !checkCameraPermission();

        if (needsAudio || needsCamera) {
            // Always request both permissions together
            List<String> permissionsToRequest = new ArrayList<>();
            if (needsAudio) {
                permissionsToRequest.add(Manifest.permission.RECORD_AUDIO);
            }
            if (needsCamera) {
                permissionsToRequest.add(Manifest.permission.CAMERA);
            }

            if (!permissionsToRequest.isEmpty()) {
                ActivityCompat.requestPermissions(this,
                        permissionsToRequest.toArray(new String[0]),
                        MULTIPLE_PERMISSIONS_CODE);
            }
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
            boolean hasAudio = checkAudioPermission();
            boolean hasCamera = checkCameraPermission();

            if (hasAudio && hasCamera) {
                if (!isRecording) {
                    startRecording();
                } else {
                    processingStatus.setText("Already recording! Click Stop to finish.");
                }
            } else {
                // Request both permissions together
                List<String> permissionsToRequest = new ArrayList<>();
                if (!hasAudio) {
                    permissionsToRequest.add(Manifest.permission.RECORD_AUDIO);
                }
                if (!hasCamera) {
                    permissionsToRequest.add(Manifest.permission.CAMERA);
                }
                ActivityCompat.requestPermissions(this,
                        permissionsToRequest.toArray(new String[0]),
                        MULTIPLE_PERMISSIONS_CODE);
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

        voiceRegistrationButton.setOnClickListener(v -> {
            boolean hasAudio = checkAudioPermission();
            boolean hasCamera = checkCameraPermission();

            if (hasAudio && hasCamera) {
                if (!isRecordingVoice) {
                    startVoiceRecording();
                } else {
                    stopVoiceRecording();
                }
            } else {
                // Request both permissions together
                List<String> permissionsToRequest = new ArrayList<>();
                if (!hasAudio) {
                    permissionsToRequest.add(Manifest.permission.RECORD_AUDIO);
                }
                if (!hasCamera) {
                    permissionsToRequest.add(Manifest.permission.CAMERA);
                }
                ActivityCompat.requestPermissions(this,
                        permissionsToRequest.toArray(new String[0]),
                        MULTIPLE_PERMISSIONS_CODE);
            }
        });

        // Gesture recognition button setup (toggle on/off like recording)
        gestureRecognitionButton.setOnClickListener(v -> {
            boolean hasAudio = checkAudioPermission();
            boolean hasCamera = checkCameraPermission();

            if (hasAudio && hasCamera) {
                if (!isGestureDetectionActive) {
                    startGestureDetection();
                } else {
                    stopGestureDetection();
                }
            } else {
                // Request both permissions together
                List<String> permissionsToRequest = new ArrayList<>();
                if (!hasAudio) {
                    permissionsToRequest.add(Manifest.permission.RECORD_AUDIO);
                }
                if (!hasCamera) {
                    permissionsToRequest.add(Manifest.permission.CAMERA);
                }
                ActivityCompat.requestPermissions(this,
                        permissionsToRequest.toArray(new String[0]),
                        MULTIPLE_PERMISSIONS_CODE);
            }
        });

        // Create gesture display box
        gestureDisplay = new TextView(this);
        gestureDisplay.setText("No gesture detected");
        gestureDisplay.setTextSize(14f);
        gestureDisplay.setPadding(12, 8, 12, 8);
        gestureDisplay.setBackgroundColor(getResources().getColor(android.R.color.darker_gray));
        gestureDisplay.setTextColor(getResources().getColor(android.R.color.white));
        gestureDisplay.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams gestureDisplayParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        gestureDisplayParams.setMargins(0, 8, 0, 8);
        gestureDisplay.setLayoutParams(gestureDisplayParams);

        // Create a divider/separator between recording and gesture buttons
        View divider = new View(this);
        divider.setBackgroundColor(getResources().getColor(android.R.color.darker_gray));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                2  // 2dp height for the divider line
        );
        dividerParams.setMargins(0, 16, 0, 16);
        divider.setLayoutParams(dividerParams);

        // Add gesture button right after the recording buttons (under Start Recording)
        View buttonsParent = (View) requestButton.getParent();
        if (buttonsParent instanceof LinearLayout) {
            LinearLayout buttonsLayout = (LinearLayout) buttonsParent;

            // Find the index of stopButton to insert gesture button right after it
            int stopButtonIndex = buttonsLayout.indexOfChild(stopButton);
            if (stopButtonIndex >= 0) {
                // Insert divider first, then gesture button and display
                buttonsLayout.addView(divider, stopButtonIndex + 1);
                buttonsLayout.addView(gestureRecognitionButton, stopButtonIndex + 2);
                buttonsLayout.addView(gestureDisplay, stopButtonIndex + 3);
            } else {
                // Fallback: just add to end
                buttonsLayout.addView(divider);
                buttonsLayout.addView(gestureRecognitionButton);
                buttonsLayout.addView(gestureDisplay);
            }
        } else {
            // Fallback: attach to conversation container if buttons' parent isn't linear
            conversationContainer.addView(divider, 0);
            conversationContainer.addView(gestureRecognitionButton, 1);
            conversationContainer.addView(gestureDisplay, 2);
        }

        // Start audio buffer processing
        startAudioBufferProcessor();
    }

    private void startAudioBufferProcessor() {
        // Process audio buffer in background thread
        audioBufferProcessorThread = new Thread(() -> {
            while (!isAppDestroyed) {
                try {
                    byte[] audioData = audioBuffer.take(); // Blocking take
                    if (audioData != null && !isAppDestroyed) {
                        processAudioChunk(audioData);
                    }
                } catch (InterruptedException e) {
                    Log.i(TAG, "Audio buffer processor interrupted");
                    break;
                } catch (Exception e) {
                    if (!isAppDestroyed) {
                        Log.e(TAG, "Error in audio buffer processor: " + e.getMessage());
                    }
                }
            }
        });
        audioBufferProcessorThread.setName("AudioBufferProcessor");
        audioBufferProcessorThread.start();
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
        boolean hasAudio = checkAudioPermission();
        boolean hasCamera = checkCameraPermission();

        if (!hasAudio || !hasCamera) {
            // Request both permissions together
            List<String> permissionsToRequest = new ArrayList<>();
            if (!hasAudio) {
                permissionsToRequest.add(Manifest.permission.RECORD_AUDIO);
            }
            if (!hasCamera) {
                permissionsToRequest.add(Manifest.permission.CAMERA);
            }
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    MULTIPLE_PERMISSIONS_CODE);
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

        Log.i(TAG, "========== STOP RECORDING CALLED ==========");

        // Immediately stop recording first
        if (audioRecorder != null) {
            try {
                if (audioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecorder.stop();
                    Log.i(TAG, "AudioRecorder stopped");
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

        // Reset recording state immediately
        isRecording = false;
        isRecordingState = false;

        // Stop chunk timer to prevent sending more chunks
        chunkHandler.removeCallbacksAndMessages(null);
        Log.i(TAG, "Chunk timer stopped");

        // Send stop processing message to server to cancel queued chunks
        sendStopProcessingMessage();

        // Update status to show processing
        runOnUiThread(() -> {
            processingStatus.setText("Stopping recording and processing...");
        });

        // Recording already stopped above, skip duplicate stop

        // Update button states - this will change button to "Start Recording"
        updateButtonStates();

        // DO NOT process and send audio - we want to stop completely
        // Clear any pending audio chunks to prevent sending
        audioChunks.clear();
        totalBytesRecorded = 0;
        synchronized (currentChunkBuffer) {
            currentChunkBuffer.clear();
            currentChunkBytes = 0;
        }

        Log.i(TAG, "Recording stopped - audio chunks cleared, no final audio will be sent");

    }

    private void sendStopProcessingMessage() {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                JSONObject stopMessage = new JSONObject();
                stopMessage.put("type", "stop_processing");
                stopMessage.put("timestamp", System.currentTimeMillis());
                String messageStr = stopMessage.toString();
                webSocketClient.send(messageStr);
                Log.i(TAG, "========== SENT stop_processing MESSAGE ==========");
                Log.i(TAG, "Message: " + messageStr);
                Log.i(TAG, "WebSocket state - isOpen: " + webSocketClient.isOpen() + ", isConnected: " + isConnected);
            } catch (JSONException e) {
                Log.e(TAG, "Failed to create stop_processing message: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Failed to send stop_processing message: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            Log.w(TAG, "Cannot send stop_processing - WebSocket not open. isOpen: " +
                    (webSocketClient != null ? webSocketClient.isOpen() : "null") +
                    ", isConnected: " + isConnected);
        }
    }

    private void leaveConversation() {
        isInConversation = false;
        isRecording = false;
        isRecordingState = false; // Reset recording state indicator
        canRecordNext = false; // Stop any further recording attempts
        hasDetectedSpeech = false; // Reset speech detection

        // Stop gesture detection when leaving conversation
        if (isGestureDetectionActive) {
            stopGestureDetection();
        }

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

        // Start new ping every 10 seconds (more frequent for tunnel services like ngrok)
        pingRunnable = new Runnable() {
            @Override
            public void run() {
                if (isConnected && webSocketClient != null && webSocketClient.isOpen()) {
                    sendPingMessage();
                    // Schedule next ping
                    pingHandler.postDelayed(this, 10000); // 10 seconds (more frequent for tunnels)
                } else {
                    Log.w(TAG, "Ping handler: Connection not open, stopping ping");
                }
            }
        };
        // Start first ping immediately to keep connection alive right away
        pingHandler.postDelayed(pingRunnable, 2000); // Start first ping after 2 seconds (immediate keep-alive)
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
                Log.d(TAG, "Sent ping to server");
                // Don't update UI for automatic pings
            } catch (JSONException e) {
                Log.e(TAG, "Failed to send ping: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Error sending ping: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "Cannot send ping - WebSocket not open");
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

        if (requestCode == MULTIPLE_PERMISSIONS_CODE) {
            // Handle multiple permissions requested together
            boolean audioGranted = false;
            boolean cameraGranted = false;

            for (int i = 0; i < permissions.length; i++) {
                if (permissions[i].equals(Manifest.permission.RECORD_AUDIO)) {
                    audioGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                } else if (permissions[i].equals(Manifest.permission.CAMERA)) {
                    cameraGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                }
            }

            if (audioGranted && cameraGranted) {
                processingStatus.setText("All permissions granted. Ready to use!");
            } else if (audioGranted) {
                processingStatus.setText("Microphone permission granted. Camera permission denied.");
            } else if (cameraGranted) {
                processingStatus.setText("Camera permission granted. Microphone permission denied.");
            } else {
                processingStatus.setText("Permissions denied. Some features may not work.");
            }
            updateButtonStates();
        } else if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                processingStatus.setText("Microphone permission granted. Ready to join conversation!");
                updateButtonStates();
            } else {
                processingStatus.setText("Microphone permission is required to use this feature!");
            }
        } else if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                processingStatus.setText("Camera permission granted");
                updateButtonStates();
                // If user clicked gesture button, start gesture detection
                if (gestureRecognitionButton != null && gestureRecognitionButton.isPressed()) {
                    startGestureDetection();
                }
            } else {
                processingStatus.setText("Camera permission denied");
                updateButtonStates();
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
                // Override to set no connection lost timeout (keep alive forever)
                @Override
                public void setConnectionLostTimeout(int timeoutSeconds) {
                    // Set to 0 to disable connection lost timeout (keep connection alive forever)
                    super.setConnectionLostTimeout(0);
                }
                @Override
                public void onOpen(ServerHandshake handshake) {
                    Log.i(TAG, "========== WebSocket OPENED ==========");
                    Log.i(TAG, "Connection state - isConnected: " + isConnected + ", isOpen: " + isOpen());
                    isConnected = true;
                    isConnecting = false; // Connection established

                    // Start keep-alive pings (10 second interval for tunnel services)
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
                        android.util.Log.i(TAG, "========== RAW_WS_MSG RECEIVED ==========");
                        android.util.Log.i(TAG, "Message length: " + message.length());
                        android.util.Log.i(TAG, "Message: " + (message.length() > 500 ? message.substring(0, 500) + "..." : message));

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
                                    // Also send a ping immediately to keep connection alive
                                    if (isConnected && webSocketClient != null && webSocketClient.isOpen()) {
                                        sendPingMessage();
                                    }
                                    break;

                                case "processing_stopped":
                                    // Server confirmed stop processing request
                                    runOnUiThread(() -> {
                                        processingStatus.setText("Processing stopped - remaining chunks skipped");
                                    });
                                    Log.i(TAG, "Server confirmed: Processing stopped");
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

                                        // Add message directly without duplicate checking
                                        addMessageToConversation(speakerName, text);
                                        Log.d(TAG, "Added message - " + speakerName + ": " + text);

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

                                                // Add message directly without duplicate checking
                                                addMessageToConversation(speakerName, text);
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

                                case "gesture_result":
                                    try {
                                        android.util.Log.i(TAG, "========== GESTURE RESULT RECEIVED ==========");
                                        android.util.Log.i(TAG, "Full response: " + response.toString());

                                        int statusCode = response.optInt("status_code", 0);
                                        android.util.Log.i(TAG, "Status code: " + statusCode);

                                        if (statusCode == 200) {
                                            org.json.JSONArray gestures = response.optJSONArray("gestures");
                                            android.util.Log.i(TAG, "Gestures array: " + (gestures != null ? gestures.toString() : "null"));
                                            android.util.Log.i(TAG, "Gestures length: " + (gestures != null ? gestures.length() : 0));

                                            if (gestures != null && gestures.length() > 0) {
                                                StringBuilder gestureText = new StringBuilder();
                                                StringBuilder gestureNamesForTTS = new StringBuilder();

                                                for (int i = 0; i < gestures.length(); i++) {
                                                    JSONObject gesture = gestures.getJSONObject(i);
                                                    String categoryName = gesture.optString("category_name", "Unknown");
                                                    double score = gesture.optDouble("score", 0.0);

                                                    // Build display text with confidence
                                                    if (i > 0) gestureText.append(", ");
                                                    gestureText.append(categoryName).append(" (").append(String.format("%.0f%%", score * 100)).append(")");

                                                    // Build clean text for TTS (just names)
                                                    if (i > 0) gestureNamesForTTS.append(" and ");
                                                    gestureNamesForTTS.append(categoryName);

                                                    android.util.Log.i(TAG, "Gesture " + i + ": " + categoryName + " (" + score + ")");
                                                }

                                                android.util.Log.i(TAG, "Final gesture text: " + gestureText.toString());

                                                // Display in gesture display box
                                                if (gestureDisplay != null) {
                                                    gestureDisplay.setText("Gesture: " + gestureText.toString());
                                                    gestureDisplay.setBackgroundColor(getResources().getColor(android.R.color.holo_green_dark));
                                                    gestureDisplay.setVisibility(View.VISIBLE);
                                                    android.util.Log.i(TAG, "Gesture display updated");
                                                } else {
                                                    android.util.Log.e(TAG, "gestureDisplay is NULL!");
                                                }

                                                processingStatus.setText("Gesture detected: " + gestureText.toString());

                                                // Speak the gesture out loud using Text-to-Speech (clean names only)
                                                speakGesture(gestureNamesForTTS.toString());
                                            } else {
                                                android.util.Log.i(TAG, "No gestures detected in response");
                                                if (gestureDisplay != null) {
                                                    gestureDisplay.setText("No gesture detected");
                                                    gestureDisplay.setBackgroundColor(getResources().getColor(android.R.color.darker_gray));
                                                    gestureDisplay.setVisibility(View.VISIBLE);
                                                }
                                                processingStatus.setText("No gesture detected");
                                            }
                                        } else {
                                            String error = response.optString("error", "Unknown error");
                                            android.util.Log.e(TAG, "Gesture recognition error: " + error);
                                            if (gestureDisplay != null) {
                                                gestureDisplay.setText("Error: " + error);
                                                gestureDisplay.setBackgroundColor(getResources().getColor(android.R.color.holo_red_dark));
                                                gestureDisplay.setVisibility(View.VISIBLE);
                                            }
                                            processingStatus.setText("Gesture recognition error: " + error);
                                        }
                                        android.util.Log.i(TAG, "==========================================");
                                    } catch (JSONException e) {
                                        android.util.Log.e(TAG, "Error parsing gesture result: " + e.getMessage(), e);
                                        if (gestureDisplay != null) {
                                            gestureDisplay.setText("Error parsing gesture result");
                                            gestureDisplay.setBackgroundColor(getResources().getColor(android.R.color.holo_red_dark));
                                            gestureDisplay.setVisibility(View.VISIBLE);
                                        }
                                        processingStatus.setText("Error parsing gesture result");
                                    }
                                    break;

                                case "tts_audio":
                                    // Handle TTS audio from server to play on glasses
                                    try {
                                        String audioData = response.optString("audio_data", "");
                                        String format = response.optString("format", "wav");
                                        String text = response.optString("text", "");

                                        android.util.Log.i(TAG, "========== TTS AUDIO RECEIVED ==========");
                                        android.util.Log.i(TAG, "Text: " + text);
                                        android.util.Log.i(TAG, "Format: " + format);
                                        android.util.Log.i(TAG, "Audio data length: " + (audioData != null ? audioData.length() : 0));

                                        if (audioData != null && !audioData.isEmpty()) {
                                            android.util.Log.i(TAG, "Calling playTTSAudioOnGlasses...");
                                            playTTSAudioOnGlasses(audioData, format);
                                        } else {
                                            android.util.Log.w(TAG, "TTS audio message missing audio_data");
                                        }
                                        android.util.Log.i(TAG, "=====================================");
                                    } catch (Exception e) {
                                        android.util.Log.e(TAG, "Error handling tts_audio message: " + e.getMessage(), e);
                                        e.printStackTrace();
                                    }
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

                    // Stop ping when connection closes
                    stopClientPing();

                    isConnected = false;
                    isConnecting = false; // Reset connecting flag
                    leaveConversation();
                    runOnUiThread(() -> {
                        connectionStatus.setText("Disconnected");
                        connectionStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                        updateButtonStates();
                    });

                    // Log close code details for debugging
                    if (code == 1006) {
                        Log.w(TAG, "Abnormal closure (1006) - usually means connection timeout or network issue");
                        Log.w(TAG, "This might be due to:");
                        Log.w(TAG, "  - Tunnel timeout (ngrok free tier)");
                        Log.w(TAG, "  - Network interruption");
                        Log.w(TAG, "  - Server not responding to pings");
                    }

                    // Auto-reconnect for unexpected disconnects (not manual disconnects)
                    // Only reconnect if it was an unexpected closure (code 1006 = abnormal closure)
                    // and we're not already trying to reconnect
                    if (code == 1006 && !isConnecting && shouldReconnect) {
                        Log.i(TAG, "Unexpected disconnect detected - attempting auto-reconnect...");
                        runOnUiThread(() -> {
                            processingStatus.setText("Connection lost. Reconnecting...");
                        });

                        // Wait a bit before reconnecting to avoid immediate retry loops
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (!isConnected && !isConnecting) {
                                Log.i(TAG, "Auto-reconnecting...");
                                reconnectWebSocket();
                            }
                        }, 3000); // Wait 3 seconds before reconnecting
                    } else {
                        Log.i(TAG, "Connection closed - manual disconnect or reconnect disabled");
                        runOnUiThread(() -> {
                            processingStatus.setText("Connection lost. Click 'Test Server' to reconnect.");
                        });
                    }
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

            // Disable connection lost timeout - keep connection alive forever
            webSocketClient.setConnectionLostTimeout(0);

            webSocketClient.connect();
            Log.i(TAG, "Connection initiated with no timeout (stays alive forever)");

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

                // Reset chunk tracking - start fresh for real-time processing
                currentChunkBuffer.clear();
                currentChunkBytes = 0;
                recordingStartTime = System.currentTimeMillis();

                // Start chunk timer - send chunks every 3 seconds with NO overlap
                // For real-time, we want clean boundaries even if speech is cut off
                chunkHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (isRecording && isInConversation) {
                            sendRealTimeChunk();
                            // Clear buffer immediately after sending to prevent overlap
                            synchronized (currentChunkBuffer) {
                                currentChunkBuffer.clear();
                                currentChunkBytes = 0;
                            }
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

        // Use dedicated audio processing executor for parallel processing
        if (audioProcessingExecutor != null && !audioProcessingExecutor.isShutdown()) {
            audioProcessingExecutor.execute(() -> {
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
            });
        } else {
            android.util.Log.e(TAG, "Audio processing executor not available");
            canRecordNext = true;
            isProcessing.set(false);
        }
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
        if (chatScrollView != null && conversationContainer != null) {
            // Use post to ensure layout is complete
            chatScrollView.post(() -> {
                try {
                    // Force layout calculation
                    conversationContainer.measure(
                            View.MeasureSpec.makeMeasureSpec(chatScrollView.getWidth(), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    );

                    // Get the actual height of the content
                    int contentHeight = conversationContainer.getMeasuredHeight();
                    int scrollViewHeight = chatScrollView.getHeight();

                    if (contentHeight > scrollViewHeight) {
                        // Content is taller than viewport - scroll to bottom
                        int scrollAmount = contentHeight - scrollViewHeight;
                        chatScrollView.smoothScrollTo(0, scrollAmount);
                    } else {
                        // Content fits - just ensure we're at the bottom
                        chatScrollView.fullScroll(ScrollView.FOCUS_DOWN);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error scrolling to bottom: " + e.getMessage());
                    // Fallback: use fullScroll if any error occurs
                    try {
                        chatScrollView.fullScroll(ScrollView.FOCUS_DOWN);
                    } catch (Exception e2) {
                        Log.e(TAG, "Error in fallback scroll: " + e2.getMessage());
                    }
                }
            });
        }
    }

    private void addMessageToConversation(String speakerId, String speechText) {
        // Debug logging
        Log.d(TAG, "addMessageToConversation called - Speaker: " + speakerId + ", Text: " + speechText);

        if (speechText == null || speechText.trim().isEmpty()) {
            Log.w(TAG, "Warning: Attempted to add empty message, skipping");
            return;
        }

        runOnUiThread(() -> {
            Log.d(TAG, "Inside runOnUiThread - conversationContainer: " + (conversationContainer != null ? "exists" : "null"));
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
                messageLayout.setPadding(6, 4, 6, 4);

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
                    // Other speakers - extract speaker number for color coding
                    String speakerNumber = "";
                    if (speakerId.startsWith("SPEAKER_")) {
                        speakerNumber = speakerId.substring(8);
                        displaySpeakerName = "Speaker " + speakerNumber;
                    } else if (speakerId.startsWith("Speaker ")) {
                        // Extract number from "Speaker 00" or "Speaker 01" format
                        String[] parts = speakerId.split(" ");
                        if (parts.length > 1) {
                            speakerNumber = parts[1];
                            displaySpeakerName = speakerId;
                        } else {
                            displaySpeakerName = speakerId;
                        }
                    } else {
                        displaySpeakerName = speakerId;
                    }

                    // Assign different colors based on speaker number
                    speakerColor = getSpeakerColor(speakerNumber);
                } else {
                    // Default for any other speaker ID
                    displaySpeakerName = speakerId;
                    speakerColor = getSpeakerColor(""); // Use default color
                }

                speakerName.setText(displaySpeakerName);
                speakerName.setTextSize(15f);
                speakerName.setTextColor(speakerColor);
                speakerName.setPadding(10, 6, 10, 6);
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
                border.setStroke(2, speakerColor);
                border.setCornerRadius(8f);
                speakerName.setBackground(border);

                // Speech text with better readability
                TextView speechTextView = new TextView(this);

                // Check if speech text is empty or null
                String displayText = speechText;
                if (speechText == null || speechText.trim().isEmpty()) {
                    displayText = "[No speech detected]";
                }

                speechTextView.setText(displayText);
                speechTextView.setTextSize(16f);
                speechTextView.setTextColor(getResources().getColor(android.R.color.white));
                speechTextView.setPadding(12, 8, 12, 8);
                speechTextView.setMaxLines(0);
                speechTextView.setSingleLine(false);
                speechTextView.setLineSpacing(6f, 1.1f);

                // Add background for speech text with different colors for wearer vs others
                android.graphics.drawable.GradientDrawable textBg = new android.graphics.drawable.GradientDrawable();
                if (isWearerMessage) {
                    // Green-tinted background for wearer's messages
                    textBg.setColor(0xFF2D5016);  // Dark green background
                } else {
                    // Default background for other speakers
                    textBg.setColor(getResources().getColor(R.color.speaker_text_bg));
                }
                textBg.setCornerRadius(8f);
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

                // Force layout update to ensure proper sizing for scrolling
                conversationContainer.requestLayout();
                chatScrollView.requestLayout();

                // Auto-scroll to bottom with better scrolling behavior
                // Use a slight delay to ensure layout is complete
                chatScrollView.postDelayed(() -> scrollToBottom(), 100);

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

            // Gesture recognition button state (toggle on/off like recording buttons)
            if (isConnected && checkCameraPermission() && !isRecordingVoice) {
                if (!isGestureDetectionActive) {
                    gestureRecognitionButton.setEnabled(true);
                    gestureRecognitionButton.setText("Start Gesture Detection");
                } else {
                    // Keep button enabled so user can toggle it off
                    gestureRecognitionButton.setEnabled(true);
                    gestureRecognitionButton.setText("Stop Gesture Detection");
                }
            } else {
                gestureRecognitionButton.setEnabled(false);
                if (isGestureDetectionActive) {
                    gestureRecognitionButton.setText("Stop Gesture Detection");
                } else {
                    gestureRecognitionButton.setText("Start Gesture Detection");
                }
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

            // Send chunk using dedicated audio processing executor
            if (audioProcessingExecutor != null && !audioProcessingExecutor.isShutdown()) {
                audioProcessingExecutor.execute(() -> {
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
                });
            } else {
                android.util.Log.e(TAG, "Audio processing executor not available for real-time chunk");
            }
        }
    }

    private void sendWavAudioToServer(String base64Audio) {
        sendWavAudioToServer(base64Audio, false);
    }

    private void sendWavAudioToServer(String base64Audio, boolean isChunk) {
        if (webSocketClient == null || !webSocketClient.isOpen()) {
            return;
        }

        // Use dedicated WebSocket send executor for thread-safe parallel sending
        if (webSocketSendExecutor != null && !webSocketSendExecutor.isShutdown()) {
            webSocketSendExecutor.execute(() -> {
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
            });
        } else {
            android.util.Log.e(TAG, "WebSocket send executor not available for audio");
            runOnUiThread(() -> {
                processingStatus.setText("Error: Not connected to server");
            });
            isProcessing.set(false);
        }
    }

    private void closeWebSocket() {
        Log.i(TAG, "Closing WebSocket connection...");
        stopClientPing(); // Stop ping when closing

        // Stop gesture detection when disconnecting
        if (isGestureDetectionActive) {
            stopGestureDetection();
        }

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

    // =============== GESTURE RECOGNITION METHODS ===============

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA},
                CAMERA_PERMISSION_CODE);
    }

    private void startGestureDetection() {
        if (!checkCameraPermission()) {
            requestCameraPermission();
            return;
        }

        if (isGestureDetectionActive) {
            return;
        }

        isGestureDetectionActive = true;
        updateButtonStates();
        processingStatus.setText("Gesture detection started - click button again to stop");
        if (gestureDisplay != null) {
            gestureDisplay.setText("Gesture detection active...");
            gestureDisplay.setBackgroundColor(getResources().getColor(android.R.color.holo_blue_dark));
        }

        // Start background camera capture
        startBackgroundCameraCapture();
    }

    private void stopGestureDetection() {
        if (!isGestureDetectionActive) {
            return;
        }

        isGestureDetectionActive = false;

        // Stop repeating capture request
        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
            } catch (Exception e) {
                android.util.Log.e(TAG, "Error stopping repeating capture: " + e.getMessage());
            }
        }

        // Stop any pending gesture captures
        if (gestureHandler != null && gestureCaptureRunnable != null) {
            gestureHandler.removeCallbacks(gestureCaptureRunnable);
        }

        // Close background camera
        closeBackgroundCamera();

        updateButtonStates();
        processingStatus.setText("Gesture detection stopped");
        if (gestureDisplay != null) {
            gestureDisplay.setText("No gesture detected");
            gestureDisplay.setBackgroundColor(getResources().getColor(android.R.color.darker_gray));
        }
    }

    private void startBackgroundCameraCapture() {
        try {
            cameraManager = (CameraManager) getSystemService(android.content.Context.CAMERA_SERVICE);
            if (cameraManager == null) {
                runOnUiThread(() -> {
                    processingStatus.setText("Camera service not available");
                });
                stopGestureDetection();
                return;
            }

            // Get first available camera
            String[] cameraIds = cameraManager.getCameraIdList();
            if (cameraIds.length == 0) {
                runOnUiThread(() -> {
                    processingStatus.setText("No camera available");
                    if (gestureDisplay != null) {
                        gestureDisplay.setText("No camera available");
                        gestureDisplay.setBackgroundColor(getResources().getColor(android.R.color.holo_red_dark));
                    }
                });
                stopGestureDetection();
                return;
            }
            cameraId = cameraIds[0];

            // Start background thread
            startBackgroundThread();

            // Create ImageReader for capturing frames
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = reader.acquireLatestImage();
                    if (image != null && isGestureDetectionActive) {
                        processCameraImage(image);
                        // Note: image.close() is handled in processCameraImage's finally block
                    } else if (image != null) {
                        image.close();
                    }
                }
            }, backgroundHandler);

            // Open camera
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice camera) {
                        cameraDevice = camera;
                        createCaptureSession();
                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice camera) {
                        camera.close();
                        cameraDevice = null;
                    }

                    @Override
                    public void onError(@NonNull CameraDevice camera, int error) {
                        camera.close();
                        cameraDevice = null;
                        runOnUiThread(() -> {
                            processingStatus.setText("Camera error: " + error);
                            if (gestureDisplay != null) {
                                gestureDisplay.setText("Camera error");
                                gestureDisplay.setBackgroundColor(getResources().getColor(android.R.color.holo_red_dark));
                            }
                        });
                        stopGestureDetection();
                    }
                }, backgroundHandler);
            }
        } catch (Exception e) {
            runOnUiThread(() -> {
                processingStatus.setText("Error starting camera: " + e.getMessage());
            });
            stopGestureDetection();
        }
    }

    private void createCaptureSession() {
        try {
            if (cameraDevice == null || imageReader == null) {
                return;
            }

            CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(imageReader.getSurface());
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            cameraDevice.createCaptureSession(
                    java.util.Arrays.asList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) {
                                return;
                            }
                            captureSession = session;
                            startRepeatingCapture();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            runOnUiThread(() -> {
                                processingStatus.setText("Failed to configure camera");
                            });
                            stopGestureDetection();
                        }
                    },
                    backgroundHandler
            );
        } catch (Exception e) {
            Log.e(TAG, "Error creating capture session: " + e.getMessage());
            stopGestureDetection();
        }
    }

    private void startRepeatingCapture() {
        try {
            if (cameraDevice == null || captureSession == null) {
                return;
            }

            // Use continuous repeating capture for real-time scanning
            CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(imageReader.getSurface());
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            // Set repeating request - this will continuously capture frames
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);

            android.util.Log.i(TAG, "Continuous gesture capture started");
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error starting continuous capture: " + e.getMessage());
            stopGestureDetection();
        }
    }

    private void processCameraImage(Image image) {
        // Throttle: Only process images at a reasonable rate to avoid overwhelming the server
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastGestureSendTime < GESTURE_SEND_INTERVAL_MS) {
            // Skip this frame - too soon since last send
            image.close();
            return;
        }

        // Use dedicated gesture processing executor for parallel processing
        if (gestureProcessingExecutor != null && !gestureProcessingExecutor.isShutdown()) {
            gestureProcessingExecutor.execute(() -> {
                try {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);

                    // Convert to Bitmap
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap != null) {
                        // Fix orientation - rotate image based on camera sensor orientation
                        bitmap = rotateBitmapForCamera(bitmap);
                        lastGestureSendTime = System.currentTimeMillis();
                        sendGestureImageToServer(bitmap);
                    }
                } catch (Exception e) {
                    android.util.Log.e(TAG, "Error processing camera image: " + e.getMessage(), e);
                } finally {
                    image.close();
                }
            });
        } else {
            // Fallback: close image if executor is not available
            image.close();
        }
    }

    private Bitmap rotateBitmapForCamera(Bitmap bitmap) {
        if (bitmap == null || cameraManager == null || cameraId == null) {
            return bitmap;
        }

        try {
            // Get camera characteristics to determine sensor orientation
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (sensorOrientation == null) {
                return bitmap;
            }

            // Get device display rotation
            int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (displayRotation) {
                case android.view.Surface.ROTATION_0:
                    degrees = 0;
                    break;
                case android.view.Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case android.view.Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case android.view.Surface.ROTATION_270:
                    degrees = 270;
                    break;
            }

            // Calculate total rotation needed
            // For front camera, we need to mirror and rotate
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            boolean isFrontCamera = (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT);

            int rotation = (sensorOrientation - degrees + 360) % 360;

            // If front camera, we might need to flip horizontally
            if (isFrontCamera) {
                // For front camera, adjust rotation
                rotation = (360 - rotation) % 360;
            }

            // Only rotate if needed
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);

                // For front camera, also flip horizontally
                if (isFrontCamera) {
                    matrix.postScale(-1, 1, bitmap.getWidth() / 2f, bitmap.getHeight() / 2f);
                }

                Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotatedBitmap != bitmap) {
                    bitmap.recycle(); // Recycle original if new bitmap was created
                }
                return rotatedBitmap;
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error rotating bitmap: " + e.getMessage(), e);
        }

        return bitmap;
    }

    private void closeBackgroundCamera() {
        try {
            if (captureSession != null) {
                // Stop repeating requests first
                try {
                    captureSession.stopRepeating();
                } catch (Exception e) {
                    // Ignore - might already be stopped
                }
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            stopBackgroundThread();
        } catch (Exception e) {
            // Silent fail during cleanup
        }
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                // Thread interrupted, continue cleanup
            }
        }
    }

    private void sendGestureImageToServer(Bitmap imageBitmap) {
        android.util.Log.i(TAG, "========== SENDING GESTURE IMAGE ==========");
        android.util.Log.i(TAG, "isConnected: " + isConnected);
        android.util.Log.i(TAG, "imageBitmap: " + (imageBitmap != null ? "not null, size: " + imageBitmap.getWidth() + "x" + imageBitmap.getHeight() : "null"));
        android.util.Log.i(TAG, "webSocketClient: " + (webSocketClient != null ? "not null, isOpen: " + (webSocketClient.isOpen() ? "true" : "false") : "null"));

        if (!isConnected || imageBitmap == null) {
            android.util.Log.e(TAG, "Cannot send: isConnected=" + isConnected + ", imageBitmap=" + (imageBitmap != null));
            runOnUiThread(() -> {
                processingStatus.setText("Not connected or no image data");
                if (gestureDisplay != null) {
                    gestureDisplay.setText("Not connected or no image data");
                    gestureDisplay.setBackgroundColor(getResources().getColor(android.R.color.holo_red_dark));
                }
            });
            return;
        }

        if (webSocketClient == null || !webSocketClient.isOpen()) {
            android.util.Log.e(TAG, "WebSocket not available or not open");
            return;
        }

        // Use dedicated WebSocket send executor for thread-safe parallel sending
        if (webSocketSendExecutor != null && !webSocketSendExecutor.isShutdown()) {
            webSocketSendExecutor.execute(() -> {
                try {
                    // Convert bitmap to base64
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    imageBitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos); // 90% quality
                    byte[] imageBytes = baos.toByteArray();
                    String base64Image = Base64.encodeToString(imageBytes, Base64.DEFAULT);

                    android.util.Log.i(TAG, "Image converted to base64, length: " + base64Image.length());

                    // Create and send gesture recognition message
                    JSONObject message = new JSONObject();
                    message.put("type", "gesture_from_glasses");
                    message.put("image_data", base64Image);
                    message.put("timestamp", System.currentTimeMillis());

                    String messageStr = message.toString();
                    android.util.Log.i(TAG, "Sending gesture message, total length: " + messageStr.length());
                    webSocketClient.send(messageStr);
                    android.util.Log.i(TAG, "Gesture message sent successfully");

                    runOnUiThread(() -> {
                        processingStatus.setText("Gesture image sent to server...");
                        if (gestureDisplay != null) {
                            gestureDisplay.setText("Sending to server...");
                            gestureDisplay.setBackgroundColor(getResources().getColor(android.R.color.holo_blue_dark));
                            gestureDisplay.setVisibility(View.VISIBLE);
                        }
                    });

                } catch (Exception e) {
                    android.util.Log.e(TAG, "Error sending gesture image: " + e.getMessage(), e);
                    runOnUiThread(() -> {
                        processingStatus.setText("Error sending gesture image: " + e.getMessage());
                        if (gestureDisplay != null) {
                            gestureDisplay.setText("Error: " + e.getMessage());
                            gestureDisplay.setBackgroundColor(getResources().getColor(android.R.color.holo_red_dark));
                        }
                    });
                }
            });
        } else {
            android.util.Log.e(TAG, "WebSocket send executor not available");
        }
    }

    // =============== END GESTURE RECOGNITION METHODS ===============

    // =============== EXECUTOR SERVICES (PARALLEL PROCESSING) ===============

    private void initializeExecutorServices() {
        // Create named thread factories for better debugging
        ThreadFactory audioThreadFactory = new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "AudioProcessor-" + threadNumber.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };

        ThreadFactory gestureThreadFactory = new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "GestureProcessor-" + threadNumber.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };

        ThreadFactory webSocketThreadFactory = new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "WebSocketSender-" + threadNumber.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };

        // Create dedicated thread pools for each function
        // Audio processing: 2 threads (can handle multiple chunks)
        audioProcessingExecutor = Executors.newFixedThreadPool(2, audioThreadFactory);

        // Gesture processing: 2 threads (image processing can be CPU intensive)
        gestureProcessingExecutor = Executors.newFixedThreadPool(2, gestureThreadFactory);

        // WebSocket sending: 1 thread (ensures thread-safe sequential sending)
        webSocketSendExecutor = Executors.newSingleThreadExecutor(webSocketThreadFactory);

        android.util.Log.i(TAG, "Executor services initialized for parallel processing");
    }

    private void shutdownExecutorServices() {
        android.util.Log.i(TAG, "Shutting down executor services...");

        if (audioProcessingExecutor != null) {
            audioProcessingExecutor.shutdown();
            try {
                if (!audioProcessingExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    audioProcessingExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                audioProcessingExecutor.shutdownNow();
            }
            audioProcessingExecutor = null;
        }

        if (gestureProcessingExecutor != null) {
            gestureProcessingExecutor.shutdown();
            try {
                if (!gestureProcessingExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    gestureProcessingExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                gestureProcessingExecutor.shutdownNow();
            }
            gestureProcessingExecutor = null;
        }

        if (webSocketSendExecutor != null) {
            webSocketSendExecutor.shutdown();
            try {
                if (!webSocketSendExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    webSocketSendExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                webSocketSendExecutor.shutdownNow();
            }
            webSocketSendExecutor = null;
        }

        android.util.Log.i(TAG, "Executor services shut down");
    }

    // =============== END EXECUTOR SERVICES ===============

    // =============== TEXT-TO-SPEECH METHODS ===============

    private void initializeTextToSpeech() {
        textToSpeech = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    int result = textToSpeech.setLanguage(Locale.getDefault());
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        android.util.Log.e(TAG, "TTS Language not supported, using default");
                        textToSpeech.setLanguage(Locale.ENGLISH);
                    }
                    ttsInitialized = true;
                    android.util.Log.i(TAG, "Text-to-Speech initialized successfully");
                } else {
                    android.util.Log.e(TAG, "Text-to-Speech initialization failed");
                    ttsInitialized = false;
                }
            }
        });
    }

    private void speakGesture(String gestureText) {
        if (!ttsInitialized || textToSpeech == null) {
            android.util.Log.w(TAG, "TTS not initialized, cannot speak gesture");
            return;
        }

        if (gestureText == null || gestureText.trim().isEmpty()) {
            android.util.Log.w(TAG, "Empty gesture text, cannot speak");
            return;
        }

        long currentTime = System.currentTimeMillis();

        // Check if this is the same gesture as last spoken
        boolean isSameGesture = gestureText.equals(lastSpokenGesture);

        // Check if cooldown period has passed
        boolean cooldownPassed = (currentTime - lastSpokenGestureTime) >= GESTURE_SPEAK_COOLDOWN_MS;

        // Only speak if:
        // 1. It's a different gesture, OR
        // 2. It's the same gesture but cooldown has passed (allows re-speaking after time)
        if (isSameGesture && !cooldownPassed) {
            android.util.Log.i(TAG, "Skipping TTS - same gesture as last spoken (" + gestureText + ") and cooldown not passed");
            return;
        }

        // Update tracking
        lastSpokenGesture = gestureText;
        lastSpokenGestureTime = currentTime;

        // Synthesize TTS to audio file and send to glasses
        synthesizeAndSendToGlasses(gestureText);

        // Also play locally as fallback
        textToSpeech.speak(gestureText, TextToSpeech.QUEUE_FLUSH, null, null);
        android.util.Log.i(TAG, "Speaking gesture: " + gestureText);
    }

    private void synthesizeAndSendToGlasses(String text) {
        if (webSocketClient == null || !webSocketClient.isOpen()) {
            android.util.Log.w(TAG, "WebSocket not connected, cannot send TTS audio to glasses");
            return;
        }

        // Use audio processing executor to avoid blocking UI
        if (audioProcessingExecutor != null && !audioProcessingExecutor.isShutdown()) {
            audioProcessingExecutor.execute(() -> {
                try {
                    // Create temporary file for TTS synthesis
                    File tempFile = new File(getCacheDir(), "tts_gesture_" + System.currentTimeMillis() + ".wav");

                    // Synthesize TTS to file
                    int result = textToSpeech.synthesizeToFile(
                            text,
                            null,
                            tempFile,
                            "tts_gesture_" + System.currentTimeMillis()
                    );

                    if (result == TextToSpeech.SUCCESS) {
                        android.util.Log.i(TAG, "TTS synthesized successfully to: " + tempFile.getAbsolutePath());

                        // Wait a bit to ensure file is fully written
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            android.util.Log.w(TAG, "Interrupted while waiting for TTS file");
                        }

                        // Check if file exists and has content
                        if (!tempFile.exists() || tempFile.length() == 0) {
                            android.util.Log.e(TAG, "TTS file is empty or doesn't exist");
                            return;
                        }

                        android.util.Log.i(TAG, "TTS file size: " + tempFile.length() + " bytes");

                        // Read audio file as bytes
                        FileInputStream fis = new FileInputStream(tempFile);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        fis.close();

                        byte[] audioBytes = baos.toByteArray();
                        baos.close();

                        android.util.Log.i(TAG, "Read " + audioBytes.length + " bytes from TTS file");

                        // Convert to base64
                        String base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP);
                        android.util.Log.i(TAG, "Base64 encoded length: " + base64Audio.length());

                        // Send to glasses via WebSocket
                        sendTTSAudioToGlasses(base64Audio, audioBytes.length);

                        // Clean up temp file
                        tempFile.delete();

                        android.util.Log.i(TAG, "TTS audio sent to glasses: " + audioBytes.length + " bytes");
                    } else {
                        android.util.Log.e(TAG, "TTS synthesis failed with result code: " + result);
                    }
                } catch (Exception e) {
                    android.util.Log.e(TAG, "Error synthesizing and sending TTS to glasses: " + e.getMessage(), e);
                }
            });
        } else {
            android.util.Log.e(TAG, "Audio processing executor not available for TTS");
        }
    }

    private void sendTTSAudioToGlasses(String base64Audio, int audioSizeBytes) {
        if (webSocketClient == null || !webSocketClient.isOpen()) {
            android.util.Log.w(TAG, "WebSocket not connected, cannot send TTS audio");
            return;
        }

        // Use WebSocket send executor for thread-safe sending
        if (webSocketSendExecutor != null && !webSocketSendExecutor.isShutdown()) {
            webSocketSendExecutor.execute(() -> {
                try {
                    JSONObject message = new JSONObject();
                    message.put("type", "audio_to_glasses");
                    message.put("audio_data", base64Audio);
                    message.put("format", "wav");
                    message.put("sample_rate", 22050); // TTS typically uses 22050 Hz
                    message.put("timestamp", System.currentTimeMillis());
                    message.put("is_tts", true);
                    message.put("text", lastSpokenGesture); // Include the text for reference

                    webSocketClient.send(message.toString());
                    android.util.Log.i(TAG, "TTS audio message sent to glasses via WebSocket");
                } catch (JSONException e) {
                    android.util.Log.e(TAG, "Error creating TTS audio message: " + e.getMessage(), e);
                } catch (Exception e) {
                    android.util.Log.e(TAG, "Error sending TTS audio to glasses: " + e.getMessage(), e);
                }
            });
        } else {
            android.util.Log.e(TAG, "WebSocket send executor not available for TTS audio");
        }
    }

    private void playTTSAudioOnGlasses(String base64Audio, String format) {
        android.util.Log.i(TAG, "playTTSAudioOnGlasses called - base64 length: " + (base64Audio != null ? base64Audio.length() : 0));

        if (audioProcessingExecutor != null && !audioProcessingExecutor.isShutdown()) {
            audioProcessingExecutor.execute(() -> {
                android.media.MediaPlayer mediaPlayer = null;
                // Use final reference for lambda expressions
                final File[] tempFileRef = new File[1];
                try {
                    // Decode base64 audio
                    byte[] audioBytes = Base64.decode(base64Audio, Base64.NO_WRAP);
                    android.util.Log.i(TAG, "Decoded audio bytes: " + audioBytes.length + " bytes");

                    // Create temporary file for playback
                    tempFileRef[0] = new File(getCacheDir(), "tts_playback_" + System.currentTimeMillis() + ".wav");
                    FileOutputStream fos = new FileOutputStream(tempFileRef[0]);
                    fos.write(audioBytes);
                    fos.close();
                    android.util.Log.i(TAG, "Created temp audio file: " + tempFileRef[0].getAbsolutePath() + ", size: " + tempFileRef[0].length());

                    // Play using MediaPlayer with STREAM_MUSIC for glasses audio output
                    mediaPlayer = new android.media.MediaPlayer();
                    mediaPlayer.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
                    mediaPlayer.setDataSource(tempFileRef[0].getAbsolutePath());
                    mediaPlayer.prepare();

                    android.util.Log.i(TAG, "MediaPlayer prepared, starting playback...");

                    mediaPlayer.setOnCompletionListener(mp -> {
                        android.util.Log.i(TAG, "TTS audio playback completed");
                        mp.release();
                        // Clean up temp file after playback
                        if (tempFileRef[0] != null && tempFileRef[0].exists()) {
                            tempFileRef[0].delete();
                            android.util.Log.i(TAG, "Temp audio file deleted");
                        }
                    });

                    mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                        android.util.Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
                        mp.release();
                        if (tempFileRef[0] != null && tempFileRef[0].exists()) {
                            tempFileRef[0].delete();
                        }
                        return true;
                    });

                    mediaPlayer.setOnPreparedListener(mp -> {
                        android.util.Log.i(TAG, "MediaPlayer prepared, starting playback now");
                        mp.start();
                    });

                    // Start playback
                    mediaPlayer.start();
                    android.util.Log.i(TAG, "MediaPlayer.start() called - audio should be playing now");

                } catch (Exception e) {
                    android.util.Log.e(TAG, "Error playing TTS audio on glasses: " + e.getMessage(), e);
                    e.printStackTrace();
                    if (mediaPlayer != null) {
                        try {
                            mediaPlayer.release();
                        } catch (Exception ex) {
                            android.util.Log.e(TAG, "Error releasing MediaPlayer: " + ex.getMessage());
                        }
                    }
                    if (tempFileRef[0] != null && tempFileRef[0].exists()) {
                        tempFileRef[0].delete();
                    }
                }
            });
        } else {
            android.util.Log.e(TAG, "Audio processing executor not available for TTS playback");
        }
    }

    // =============== END TEXT-TO-SPEECH METHODS ===============

    // Helper method to get different colors for different speakers (00, 01, 02, etc.)
    private int getSpeakerColor(String speakerNumber) {
        // Array of distinct colors for different speakers
        int[] speakerColors = {
                getResources().getColor(android.R.color.holo_blue_light),      // Speaker 00 - Blue
                getResources().getColor(android.R.color.holo_orange_light),   // Speaker 01 - Orange
                getResources().getColor(android.R.color.holo_purple),          // Speaker 02 - Purple
                getResources().getColor(android.R.color.holo_red_light),       // Speaker 03 - Red
                getResources().getColor(android.R.color.holo_blue_dark),       // Speaker 04 - Dark Blue
                getResources().getColor(android.R.color.holo_orange_dark),     // Speaker 05 - Dark Orange
                getResources().getColor(android.R.color.darker_gray),          // Speaker 06 - Gray
                0xFF9C27B0,  // Speaker 07 - Deep Purple (custom color)
                0xFF00BCD4,  // Speaker 08 - Cyan (custom color)
                0xFFFF9800,  // Speaker 09 - Deep Orange (custom color)
                0xFF4CAF50,  // Speaker 10 - Green (custom color)
                0xFFE91E63,  // Speaker 11 - Pink (custom color)
                0xFF3F51B5,  // Speaker 12 - Indigo (custom color)
                0xFFFF5722,  // Speaker 13 - Deep Orange (custom color)
                0xFF009688,  // Speaker 14 - Teal (custom color)
                0xFF795548   // Speaker 15 - Brown (custom color)
        };

        try {
            if (speakerNumber != null && !speakerNumber.isEmpty()) {
                // Try to parse the speaker number (handle "00", "01", "0", "1", etc.)
                int speakerIndex = Integer.parseInt(speakerNumber);
                if (speakerIndex >= 0 && speakerIndex < speakerColors.length) {
                    return speakerColors[speakerIndex];
                }
            }
        } catch (NumberFormatException e) {
            // If parsing fails, use default color
        }

        // Default color if speaker number is invalid or out of range
        return getResources().getColor(android.R.color.holo_blue_light);
    }

    private void setupCrashHandler() {
        // Get the existing default handler
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        // Set default uncaught exception handler
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                Log.e(TAG, "========== APP CRASH DETECTED ==========");
                Log.e(TAG, "Thread: " + thread.getName());
                Log.e(TAG, "Exception: " + ex.getClass().getName());
                Log.e(TAG, "Message: " + ex.getMessage());
                Log.e(TAG, "Stack trace:", ex);
                Log.e(TAG, "=========================================");

                // Perform emergency cleanup
                performEmergencyCleanup();

                // Call the default handler to show crash dialog
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, ex);
                }
            }
        });
    }

    private void performEmergencyCleanup() {
        Log.i(TAG, "========== PERFORMING EMERGENCY CLEANUP ==========");
        isAppDestroyed = true;

        try {
            // Stop all recording
            if (isRecording) {
                stopRecording();
            }
            if (isRecordingVoice) {
                stopVoiceRecording();
            }
            if (isGestureDetectionActive) {
                stopGestureDetection();
            }

            // Stop ping handler
            stopClientPing();

            // Stop gesture handler
            if (gestureHandler != null && gestureCaptureRunnable != null) {
                gestureHandler.removeCallbacks(gestureCaptureRunnable);
            }

            // Interrupt audio buffer processor thread
            if (audioBufferProcessorThread != null && audioBufferProcessorThread.isAlive()) {
                audioBufferProcessorThread.interrupt();
            }

            // Release audio recorder
            if (audioRecorder != null) {
                try {
                    if (audioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecorder.stop();
                    }
                } catch (Exception e) {
                    // Ignore errors during emergency cleanup
                } finally {
                    try {
                        audioRecorder.release();
                    } catch (Exception e) {
                        // Ignore errors during emergency cleanup
                    }
                    audioRecorder = null;
                }
            }

            // Close WebSocket
            if (webSocketClient != null) {
                try {
                    if (webSocketClient.isOpen()) {
                        webSocketClient.close();
                    }
                } catch (Exception e) {
                    // Ignore errors during emergency cleanup
                }
                webSocketClient = null;
            }

            // Clear buffers
            audioBuffer.clear();
            audioChunks.clear();

            // Shutdown Text-to-Speech
            if (textToSpeech != null) {
                try {
                    textToSpeech.stop();
                    textToSpeech.shutdown();
                } catch (Exception e) {
                    // Ignore errors during emergency cleanup
                }
                textToSpeech = null;
                ttsInitialized = false;
            }

            // Shutdown executor services
            shutdownExecutorServices();

            Log.i(TAG, "Emergency cleanup completed");
        } catch (Exception e) {
            Log.e(TAG, "Error during emergency cleanup: " + e.getMessage());
        }
        Log.i(TAG, "================================================");
    }

    private void performCleanup() {
        Log.i(TAG, "========== PERFORMING CLEANUP ==========");
        isAppDestroyed = true;

        try {
            // Stop all recording
            if (isRecording) {
                stopRecording();
            }
            if (isRecordingVoice) {
                stopVoiceRecording();
            }
            if (isGestureDetectionActive) {
                stopGestureDetection();
            }

            // Leave conversation
            leaveConversation();

            // Stop ping handler
            stopClientPing();

            // Stop gesture handler
            if (gestureHandler != null && gestureCaptureRunnable != null) {
                gestureHandler.removeCallbacks(gestureCaptureRunnable);
            }

            // Interrupt audio buffer processor thread
            if (audioBufferProcessorThread != null && audioBufferProcessorThread.isAlive()) {
                audioBufferProcessorThread.interrupt();
                try {
                    audioBufferProcessorThread.join(1000); // Wait up to 1 second
                } catch (InterruptedException e) {
                    Log.w(TAG, "Interrupted while waiting for audio buffer processor to stop");
                }
            }

            // Close WebSocket
            closeWebSocket();

            // Clear buffers
            audioBuffer.clear();
            audioChunks.clear();

            // Shutdown Text-to-Speech
            if (textToSpeech != null) {
                try {
                    textToSpeech.stop();
                    textToSpeech.shutdown();
                } catch (Exception e) {
                    // Ignore errors during cleanup
                }
                textToSpeech = null;
                ttsInitialized = false;
            }

            // Shutdown executor services
            shutdownExecutorServices();

            Log.i(TAG, "Cleanup completed");
        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup: " + e.getMessage());
        }
        Log.i(TAG, "======================================");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause() called - app going to background");
        // Stop gesture detection when app goes to background
        if (isGestureDetectionActive) {
            stopGestureDetection();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "onStop() called - app stopped");
        // Stop recording when app goes to background
        if (isRecording) {
            stopRecording();
        }
        if (isRecordingVoice) {
            stopVoiceRecording();
        }
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "onDestroy() called - app being destroyed");
        performCleanup();
        super.onDestroy();
    }
}
