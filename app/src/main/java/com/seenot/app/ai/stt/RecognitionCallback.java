package com.seenot.app.ai.stt;

import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;

/**
 * Callback interface for DashScope Recognition results.
 */
public interface RecognitionCallback {
    void onIntermediateResult(String text);
    void onFinalResult(String text);
    void onError(String error);
    void onComplete();
}
