package com.example.arpiagrama.operational.audio;

import com.example.arpiagrama.operational.preferences.TalkbackPreferences;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SpeechController {
    private final TextToSpeech textToSpeech;
    private final Map<String, Runnable> utteranceCallbacks = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Queue<SpeechRequest> pendingRequests = new ArrayDeque<>();
    private final Object pendingLock = new Object();
    private volatile boolean initialized = false;

    public SpeechController(Context context) {
        final TextToSpeech[] holder = new TextToSpeech[1];
        holder[0] = new TextToSpeech(context, status -> {
            TextToSpeech initializedTts = holder[0];
            if (status == TextToSpeech.SUCCESS && initializedTts != null) {
                initializedTts.setLanguage(Locale.getDefault());
                initializedTts.setSpeechRate(TalkbackPreferences.getSpeechRate(context));
                initialized = true;
                initializedTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        Runnable callback = utteranceCallbacks.remove(utteranceId);
                        if (callback != null) {
                            mainHandler.post(callback);
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        utteranceCallbacks.remove(utteranceId);
                    }
                });
                flushPendingRequests();
            }
        });
        this.textToSpeech = holder[0];
    }

    public void speakImmediate(String text) {
        speak(text, TextToSpeech.QUEUE_FLUSH, "tts_flush", null);
    }

    public void speakQueued(String text) {
        speak(text, TextToSpeech.QUEUE_ADD, "tts_queue", null);
    }

    public void speakQueued(String text, Runnable onDone) {
        speak(text, TextToSpeech.QUEUE_ADD, "tts_queue_" + UUID.randomUUID(), onDone);
    }

    public void runAfterQueue(Runnable onDone) {
        if (onDone == null) {
            return;
        }
        if (textToSpeech == null) {
            mainHandler.post(onDone);
            return;
        }
        String utteranceId = "tts_after_queue_" + UUID.randomUUID();
        if (!initialized) {
            synchronized (pendingLock) {
                pendingRequests.add(SpeechRequest.silence(1L, TextToSpeech.QUEUE_ADD, utteranceId, onDone));
            }
            return;
        }
        if (utteranceId != null) {
            utteranceCallbacks.put(utteranceId, onDone);
        }
        try {
            textToSpeech.playSilentUtterance(1L, TextToSpeech.QUEUE_ADD, utteranceId);
        } catch (Exception ignored) {
            utteranceCallbacks.remove(utteranceId);
            mainHandler.post(onDone);
        }
    }

    public void setSpeechRate(float speechRate) {
        if (textToSpeech == null) {
            return;
        }
        try {
            textToSpeech.setSpeechRate(speechRate);
        } catch (Exception ignored) {
        }
    }

    public void stopAll() {
        if (textToSpeech == null) {
            return;
        }
        try {
            textToSpeech.stop();
        } catch (Exception ignored) {
        }
        utteranceCallbacks.clear();
    }

    private void speak(String text, int queueMode, String utteranceId, Runnable onDone) {
        if (textToSpeech == null) {
            return;
        }
        if (text == null || text.isEmpty()) {
            return;
        }
        if (!initialized) {
            synchronized (pendingLock) {
                pendingRequests.add(new SpeechRequest(text, 0L, queueMode, utteranceId, onDone));
            }
            return;
        }
        if (onDone != null && utteranceId != null) {
            utteranceCallbacks.put(utteranceId, onDone);
        }
        try {
            textToSpeech.speak(text, queueMode, null, utteranceId);
        } catch (Exception ignored) {
        }
    }

    public void shutdown() {
        try {
            textToSpeech.stop();
            textToSpeech.shutdown();
        } catch (Exception ignored) {
        }
        initialized = false;
        utteranceCallbacks.clear();
        synchronized (pendingLock) {
            pendingRequests.clear();
        }
    }

    private void flushPendingRequests() {
        if (!initialized || textToSpeech == null) {
            return;
        }
        synchronized (pendingLock) {
            while (!pendingRequests.isEmpty()) {
                SpeechRequest request = pendingRequests.poll();
                if (request == null) {
                    continue;
                }
                if (request.onDone != null && request.utteranceId != null) {
                    utteranceCallbacks.put(request.utteranceId, request.onDone);
                }
                try {
                    if (request.isSilenceRequest()) {
                        textToSpeech.playSilentUtterance(request.silenceDurationMs, request.queueMode, request.utteranceId);
                    } else {
                        textToSpeech.speak(request.text, request.queueMode, null, request.utteranceId);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static class SpeechRequest {
        private final String text;
        private final long silenceDurationMs;
        private final int queueMode;
        private final String utteranceId;
        private final Runnable onDone;

        private SpeechRequest(String text, long silenceDurationMs, int queueMode, String utteranceId, Runnable onDone) {
            this.text = text;
            this.silenceDurationMs = silenceDurationMs;
            this.queueMode = queueMode;
            this.utteranceId = utteranceId;
            this.onDone = onDone;
        }

        private static SpeechRequest silence(long silenceDurationMs, int queueMode, String utteranceId, Runnable onDone) {
            return new SpeechRequest(null, Math.max(1L, silenceDurationMs), queueMode, utteranceId, onDone);
        }

        private boolean isSilenceRequest() {
            return text == null;
        }
    }
}
