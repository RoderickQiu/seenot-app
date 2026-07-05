package com.seenot.app.ai.stt

class RealtimeSttStopCoordinator {
    private var lastRecognizedText: String? = null
    private var stopRequested = false
    private var finished = false

    fun onIntermediateResult(text: String): Decision? {
        return rememberText(text)
    }

    fun onFinalResult(text: String): Decision? {
        return rememberText(text)
    }

    fun onStopRequested(): Decision? {
        if (finished) return null
        stopRequested = true
        return null
    }

    fun onComplete(): Decision? {
        if (!stopRequested || finished) return null
        return decide(lastRecognizedText)
    }

    fun onError(): Decision? {
        if (finished) return null
        finished = true
        return null
    }

    fun onTimeout(): Decision? {
        if (!stopRequested || finished) return null
        return decide(lastRecognizedText)
    }

    private fun rememberText(text: String): Decision? {
        val normalized = text.trim()
        if (normalized.isNotBlank()) {
            lastRecognizedText = normalized
        }
        return if (stopRequested && normalized.isNotBlank() && !finished) {
            decide(normalized)
        } else {
            null
        }
    }

    private fun decide(text: String?): Decision {
        finished = true
        val normalized = text?.trim().orEmpty()
        return if (normalized.isBlank()) {
            Decision.EmptyRecognition
        } else {
            Decision.UseText(normalized)
        }
    }

    sealed class Decision {
        data class UseText(val text: String) : Decision()
        object EmptyRecognition : Decision()
    }
}
