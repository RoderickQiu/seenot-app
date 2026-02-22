package com.roderickqiu.seenot.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.roderickqiu.seenot.service.AITranslationUtil
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

    /**
     * Load observations within a time range. More efficient than loading all
     * and filtering in memory, especially when data spans months.
     */
    fun loadObservations(startMs: Long, endMs: Long): List<ScreenObservation> {
        return try {
            if (!observationsFile.exists()) return emptyList()
            val json = observationsFile.readText()
            if (json.isBlank()) return emptyList()
            val type = object : TypeToken<List<ScreenObservation>>() {}.type
            val all = gson.fromJson<List<ScreenObservation>>(json, type) ?: emptyList()
            // Binary search for start index to avoid scanning from beginning
            val startIndex = all.binarySearchBy(startMs) { it.timestamp }
                .let { if (it < 0) -it - 1 else it }
                .coerceIn(0, all.size)
            val endIndex = all.binarySearchBy(endMs) { it.timestamp }
                .let { if (it < 0) -it - 2 else it }
                .coerceIn(-1, all.size - 1) + 1
            if (startIndex < endIndex) {
                all.subList(startIndex, endIndex.coerceAtMost(all.size))
                    .filter { it.timestamp in startMs..endMs }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load observations in range", e)
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
            // Gson bypasses Kotlin's non-null guarantees for fields missing in JSON.
            // Coerce any Gson-injected nulls to safe defaults here.
            (gson.fromJson<List<ContentLabel>>(json, type) ?: emptyList()).map { label ->
                @Suppress("SENSELESS_COMPARISON")
                label.copy(localizedNames = label.localizedNames ?: emptyMap())
            }
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

    fun clearAll() {
        saveObservations(emptyList())
        saveLabels(emptyList())
        saveMergeSuggestions(emptyList())
    }

    // ------------------------------------------------------------------
    // Shared label resolution helpers
    // ------------------------------------------------------------------

    private fun resolveLabel(labelById: Map<String, ContentLabel>, id: String?): String? {
        var cur = id ?: return null
        val visited = mutableSetOf<String>()
        while (true) {
            val label = labelById[cur] ?: return cur
            val next = label.mergedInto ?: return cur
            if (!visited.add(next)) return cur
            cur = next
        }
    }

    private fun resolvedDisplayName(
        labelById: Map<String, ContentLabel>,
        id: String?,
        languageCode: String?
    ): String {
        val resolved = resolveLabel(labelById, id) ?: return "未分类"
        val label = labelById[resolved] ?: return resolved
        if (languageCode != null) {
            label.localizedNames[languageCode]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return label.displayName
    }

    // ------------------------------------------------------------------
    // computeSummary
    // ------------------------------------------------------------------

    /**
     * Compute per-app, per-label time breakdown for a date range.
     * Each observation contributes [sampleIntervalMs] of usage time.
     * Labels with mergedInto set are resolved to their canonical target.
     *
     * @param languageCode  BCP-47 language tag (e.g. "zh"). When provided, the
     *                      localized name from [ContentLabel.localizedNames] is
     *                      preferred over the raw English [ContentLabel.displayName].
     */
    fun computeSummary(
        startMs: Long,
        endMs: Long,
        languageCode: String? = null,
        sampleIntervalMs: Long = 5_000L
    ): UsageSummary {
        val labelById = loadLabels().associateBy { it.labelId }

        // Use range query for performance when data spans months
        val observations = loadObservations(startMs, endMs)
            .sortedBy { it.timestamp }

        val counts = mutableMapOf<String, MutableMap<String, Int>>()
        for (obs in observations) {
            val app = obs.appName
            val labelId = resolveLabel(labelById, obs.labelId) ?: UNKNOWN_LABEL_ID
            counts.getOrPut(app) { mutableMapOf() }.merge(labelId, 1, Int::plus)
        }

        val appSummaries = counts.map { (app, labelCounts) ->
            val totalObs = labelCounts.values.sum()
            val totalMs = totalObs * sampleIntervalMs
            val categories = labelCounts.entries
                .sortedByDescending { it.value }
                .map { (labelId, count) ->
                    LabelDuration(
                        labelId = labelId,
                        displayName = resolvedDisplayName(labelById, labelId, languageCode),
                        durationMs = count * sampleIntervalMs,
                        percentage = if (totalObs > 0) count * 100f / totalObs else 0f
                    )
                }
            AppUsage(appName = app, totalDurationMs = totalMs, categories = categories)
        }.sortedByDescending { it.totalDurationMs }

        return UsageSummary(
            startMs = startMs,
            endMs = endMs,
            totalObservations = observations.size,
            totalDurationMs = observations.size * sampleIntervalMs,
            apps = appSummaries
        )
    }

    // ------------------------------------------------------------------
    // segmentObservations
    // ------------------------------------------------------------------

    /**
     * Build a chronological list of usage segments for a date range.
     *
     * A segment boundary is created when:
     * - the app name changes, OR
     * - the (resolved) label changes, OR
     * - the gap between consecutive observations exceeds [gapThresholdMs] (default 5 min)
     *
     * Duration = number of observations in the segment × [sampleIntervalMs].
     */
    fun segmentObservations(
        startMs: Long,
        endMs: Long,
        languageCode: String? = null,
        gapThresholdMs: Long = 5 * 60_000L,
        sampleIntervalMs: Long = 5_000L
    ): List<UsageSegment> {
        val labelById = loadLabels().associateBy { it.labelId }

        val obs = loadObservations()
            .filter { it.timestamp in startMs..endMs }
            .sortedBy { it.timestamp }

        if (obs.isEmpty()) return emptyList()

        val segments = mutableListOf<UsageSegment>()

        var segStart = obs[0].timestamp
        var segApp = obs[0].appName
        var segLabelId = resolveLabel(labelById, obs[0].labelId) ?: UNKNOWN_LABEL_ID
        var segCount = 1

        for (i in 1 until obs.size) {
            val prev = obs[i - 1]
            val curr = obs[i]
            val resolvedLabel = resolveLabel(labelById, curr.labelId) ?: UNKNOWN_LABEL_ID
            val gap = curr.timestamp - prev.timestamp
            val sameGroup = curr.appName == segApp &&
                resolvedLabel == segLabelId &&
                gap <= gapThresholdMs

            if (!sameGroup) {
                segments.add(
                    UsageSegment(
                        startMs = segStart,
                        endMs = prev.timestamp + sampleIntervalMs,
                        appName = segApp,
                        labelId = segLabelId,
                        displayName = resolvedDisplayName(labelById, segLabelId, languageCode),
                        durationMs = segCount * sampleIntervalMs
                    )
                )
                segStart = curr.timestamp
                segApp = curr.appName
                segLabelId = resolvedLabel
                segCount = 1
            } else {
                segCount++
            }
        }

        // Flush the last segment
        val lastObs = obs.last()
        segments.add(
            UsageSegment(
                startMs = segStart,
                endMs = lastObs.timestamp + sampleIntervalMs,
                appName = segApp,
                labelId = segLabelId,
                displayName = resolvedDisplayName(labelById, segLabelId, languageCode),
                durationMs = segCount * sampleIntervalMs
            )
        )

        return segments
    }

    /**
     * Translate label display names that are missing a translation for [languageCode],
     * then persist the result. Safe to call repeatedly - skips already-translated labels.
     *
     * @param languageCode   BCP-47 tag, e.g. "zh"
     * @param targetLanguage Human-readable language name for the AI prompt, e.g. "Simplified Chinese"
     * @param context        Android context
     * @param note           Extra instruction passed to the translation model, e.g. style hints
     */
    suspend fun ensureLocalizedNames(
        languageCode: String,
        targetLanguage: String,
        context: Context,
        note: String? = null
    ) {
        val labels = loadLabels()
        val toTranslate = labels.filter { label ->
            label.labelId != UNKNOWN_LABEL_ID &&
                label.mergedInto == null &&
                !label.localizedNames.containsKey(languageCode)
        }
        if (toTranslate.isEmpty()) return

        val displayNames = toTranslate.map { it.displayName }
        val translations = AITranslationUtil.translateBatch(context, displayNames, targetLanguage, note)

        val updated = labels.map { label ->
            val translation = translations[label.displayName]
            if (translation != null && translation != label.displayName) {
                label.copy(localizedNames = label.localizedNames + (languageCode to translation))
            } else {
                label
            }
        }
        saveLabels(updated)
    }
}

data class LabelDuration(
    val labelId: String,
    val displayName: String,
    val durationMs: Long,
    val percentage: Float
)

data class AppUsage(
    val appName: String,
    val totalDurationMs: Long,
    val categories: List<LabelDuration>
)

data class UsageSummary(
    val startMs: Long,
    val endMs: Long,
    val totalObservations: Int,
    val totalDurationMs: Long,
    val apps: List<AppUsage>
)

data class UsageSegment(
    val startMs: Long,
    val endMs: Long,
    val appName: String,
    val labelId: String,
    val displayName: String,
    val durationMs: Long
)
