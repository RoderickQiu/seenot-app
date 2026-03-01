package com.seenot.app.ai.stt;

import android.util.Log;
import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.common.ResultCallback;

/**
 * Java helper class for DashScope Recognition SDK.
 */
public class DashScopeRecognitionHelper {

    private static final String TAG = "DashScopeSTT";

    private Recognition recognition;
    private RecognitionCallback callback;

    public DashScopeRecognitionHelper() {
        this.recognition = new Recognition();
    }

    /**
     * Set callback for recognition results
     */
    public void setCallback(RecognitionCallback callback) {
        this.callback = callback;
    }

    /**
     * Start recognition with given parameters
     */
    public void startRecognition(RecognitionParam param) {
        Log.d(TAG, "Starting recognition with model: " + param.getModel());

        // Create ResultCallback adapter
        ResultCallback<RecognitionResult> resultCallback = new ResultCallback<RecognitionResult>() {
            @Override
            public void onEvent(RecognitionResult result) {
                Log.d(TAG, "onEvent called, isSentenceEnd: " + result.isSentenceEnd());
                if (callback == null) {
                    Log.w(TAG, "callback is null!");
                    return;
                }

                if (result.isSentenceEnd()) {
                    String text = result.getSentence() != null ? result.getSentence().getText() : "";
                    Log.d(TAG, "Final Result: [" + text + "]");
                    callback.onFinalResult(text);
                } else {
                    String text = result.getSentence() != null ? result.getSentence().getText() : "";
                    if (text != null && !text.isEmpty()) {
                        Log.d(TAG, "Intermediate Result: [" + text + "]");
                        callback.onIntermediateResult(text);
                    }
                }
            }

            @Override
            public void onComplete() {
                Log.d(TAG, "Recognition complete - all audio processed");
                if (callback != null) {
                    callback.onComplete();
                }
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Recognition error: " + e.getMessage());
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            }
        };

        // Use call() method with param and callback
        recognition.call(param, resultCallback);
    }

    /**
     * Send audio frame to recognition service (ByteBuffer)
     */
    public void sendAudioFrame(java.nio.ByteBuffer buffer) {
        // Silent send - no logging for performance
        try {
            recognition.sendAudioFrame(buffer);
        } catch (Exception e) {
            Log.e(TAG, "Error sending audio frame: " + e.getMessage());
        }
    }

    /**
     * Stop recognition
     */
    public void stop() {
        try {
            recognition.stop();
            Log.d(TAG, "Recognition stopped");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping recognition: " + e.getMessage());
        }
    }

    /**
     * Get the last request ID
     */
    public String getLastRequestId() {
        return recognition.getLastRequestId();
    }

    /**
     * Get first package delay in milliseconds
     */
    public long getFirstPackageDelay() {
        return recognition.getFirstPackageDelay();
    }
}
