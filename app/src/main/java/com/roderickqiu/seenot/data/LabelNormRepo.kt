package com.roderickqiu.seenot.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.roderickqiu.seenot.utils.Logger
import java.io.File

class LabelNormalizationRepo(private val context: Context) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val observationsFile = File(context.filesDir, "screen_observations.json")
    private val labelsFile = File(context.filesDir, "content_labels.json")
    private val mergesFile = File(context.filesDir, "label_merge_suggestions.json")

    companion object {
        private const val TAG = "LabelNormalizationRepo"
        const val UNKNOWN_LABEL_ID = "unknown"
    }

    fun loadObservations(): List<ScreenObservation> {
        return try {
            if (!observationsFile.exists()) return emptyList()
            val json = observationsFile.readText()
            if (json.isBlank()) return emptyList()
            val type = object : TypeToken<List<ScreenObservation>>() {}.type
            gson.fromJson<List<ScreenObservation>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load observations", e)
            emptyList()
        }
    }

    fun saveObservations(observations: List<ScreenObservation>) {
        try {
            observationsFile.writeText(gson.toJson(observations))
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to save observations", e)
        }
    }

    fun upsertObservationFromRecord(record: RuleRecord, rawReason: String): ScreenObservation? {
        val hash = record.screenshotHash ?: return null
        val bucketMinute = record.timestamp / 60_000L
        val observationId = "${record.appName}|$hash|$bucketMinute"

        val observations = loadObservations().toMutableList()
        val idx = observations.indexOfFirst { it.observationId == observationId }
        val existing = if (idx >= 0) observations[idx] else null

        val mergedIds = ((existing?.recordIds ?: emptyList()) + record.id).distinct()
        val confidence = record.confidence ?: 0.0
        val shouldReplaceReason = existing == null || confidence >= existing.confidence

        val updated = if (existing == null) {
            ScreenObservation(
                observationId = observationId,
                timestamp = record.timestamp,
                appName = record.appName,
                packageName = record.packageName,
                screenshotHash = hash,
                rawReason = rawReason,
                confidence = confidence,
                recordIds = mergedIds,
                labelId = null,
                normalizedAt = null
            )
        } else {
            existing.copy(
                timestamp = minOf(existing.timestamp, record.timestamp),
                packageName = record.packageName ?: existing.packageName,
                rawReason = if (shouldReplaceReason) rawReason else existing.rawReason,
                confidence = maxOf(existing.confidence, confidence),
                recordIds = mergedIds
            )
        }

        if (idx >= 0) {
            observations[idx] = updated
        } else {
            observations.add(updated)
        }
        saveObservations(observations)
        return updated
    }

    fun loadLabels(): List<ContentLabel> {
        return try {
            if (!labelsFile.exists()) return emptyList()
            val json = labelsFile.readText()
            if (json.isBlank()) return emptyList()
            val type = object : TypeToken<List<ContentLabel>>() {}.type
            gson.fromJson<List<ContentLabel>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load labels", e)
            emptyList()
        }
    }

    fun saveLabels(labels: List<ContentLabel>) {
        try {
            labelsFile.writeText(gson.toJson(labels))
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to save labels", e)
        }
    }

    fun ensureUnknownLabel() {
        val labels = loadLabels().toMutableList()
        if (labels.none { it.labelId == UNKNOWN_LABEL_ID }) {
            labels.add(
                ContentLabel(
                    labelId = UNKNOWN_LABEL_ID,
                    displayName = "Unknown",
                    description = "Fallback label for uncertain observations",
                    createdInBatch = -1
                )
            )
            saveLabels(labels)
        }
    }

    fun upsertLabel(newLabel: ContentLabel) {
        val labels = loadLabels().toMutableList()
        val idx = labels.indexOfFirst { it.labelId == newLabel.labelId }
        if (idx >= 0) {
            labels[idx] = newLabel
        } else {
            labels.add(newLabel)
        }
        saveLabels(labels)
    }

    fun loadMergeSuggestions(): List<LabelMergeSuggestion> {
        return try {
            if (!mergesFile.exists()) return emptyList()
            val json = mergesFile.readText()
            if (json.isBlank()) return emptyList()
            val type = object : TypeToken<List<LabelMergeSuggestion>>() {}.type
            gson.fromJson<List<LabelMergeSuggestion>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load merge suggestions", e)
            emptyList()
        }
    }

    fun saveMergeSuggestions(suggestions: List<LabelMergeSuggestion>) {
        try {
            mergesFile.writeText(gson.toJson(suggestions))
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to save merge suggestions", e)
        }
    }
}
