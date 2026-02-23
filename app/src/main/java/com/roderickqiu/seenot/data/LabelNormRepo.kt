package com.roderickqiu.seenot.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.roderickqiu.seenot.service.AITranslationUtil
import com.roderickqiu.seenot.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class LabelNormalizationRepo(private val context: Context) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val observationsDir = File(context.filesDir, "observations").apply { mkdirs() }
    private val labelsFile = File(context.filesDir, "content_labels.json")
    private val mergesFile = File(context.filesDir, "label_merge_suggestions.json")
    private val actionExecutionRepo = ActionExecutionRepo(context)

    companion object {
        private const val TAG = "LabelNormalizationRepo"
        const val UNKNOWN_LABEL_ID = "unknown"
        const val OBSERVATIONS_DIR = "observations"
        const val EXPORT_VERSION = "2.0"
    }

    // ------------------------------------------------------------------
    // Date utilities
    // ------------------------------------------------------------------

    private fun getDateString(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(timestamp)
    }

    private fun getDateRangeFiles(startMs: Long, endMs: Long): List<File> {
        val files = mutableListOf<File>()
        val calendar = Calendar.getInstance().apply { timeInMillis = startMs }
        val endCalendar = Calendar.getInstance().apply { timeInMillis = endMs }

        while (calendar.timeInMillis <= endCalendar.timeInMillis) {
            val dateString = getDateString(calendar.timeInMillis)
            val file = File(observationsDir, "$dateString.json")
            if (file.exists()) {
                files.add(file)
            }
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return files
    }

    // ------------------------------------------------------------------
    // Observations - Daily Storage
    // ------------------------------------------------------------------

    fun loadObservations(): List<ScreenObservation> {
        return try {
            observationsDir.listFiles { _, name -> name.endsWith(".json") }
                ?.sortedBy { it.name }
                ?.flatMap { file ->
                    try {
                        val json = file.readText()
                        if (json.isBlank()) return@flatMap emptyList()
                        val type = object : TypeToken<List<ScreenObservation>>() {}.type
                        gson.fromJson<List<ScreenObservation>>(json, type) ?: emptyList()
                    } catch (e: Exception) {
                        Logger.e(TAG, "Failed to load observations from ${file.name}", e)
                        emptyList()
                    }
                } ?: emptyList()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load observations", e)
            emptyList()
        }
    }

    fun loadObservations(startMs: Long, endMs: Long): List<ScreenObservation> {
        val files = getDateRangeFiles(startMs, endMs)
        if (files.isEmpty()) return emptyList()

        return files.flatMap { file ->
            try {
                val json = file.readText()
                if (json.isBlank()) return@flatMap emptyList()
                val type = object : TypeToken<List<ScreenObservation>>() {}.type
                val all = gson.fromJson<List<ScreenObservation>>(json, type) ?: emptyList()
                all.filter { it.timestamp in startMs..endMs }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load observations from ${file.name}", e)
                emptyList()
            }
        }.sortedBy { it.timestamp }
    }

    private fun saveObservationsForDate(dateString: String, observations: List<ScreenObservation>) {
        try {
            val file = File(observationsDir, "$dateString.json")
            if (observations.isEmpty()) {
                file.delete()
            } else {
                file.writeText(gson.toJson(observations))
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to save observations for $dateString", e)
        }
    }

    fun upsertObservationFromRecord(record: RuleRecord, rawReason: String): ScreenObservation? {
        val hash = record.screenshotHash ?: return null
        val bucketMinute = record.timestamp / 60_000L
        val observationId = "${record.appName}|$hash|$bucketMinute"
        val dateString = getDateString(record.timestamp)

        // Load only observations for this date
        val file = File(observationsDir, "$dateString.json")
        val observations = if (file.exists()) {
            try {
                val json = file.readText()
                val type = object : TypeToken<List<ScreenObservation>>() {}.type
                gson.fromJson<List<ScreenObservation>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }.toMutableList()

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
        saveObservationsForDate(dateString, observations)
        return updated
    }

    // Legacy save method - used for import compatibility
    fun saveObservations(observations: List<ScreenObservation>) {
        // Group by date and save to separate files
        observations.groupBy { getDateString(it.timestamp) }
            .forEach { (dateString, dailyObs) ->
                saveObservationsForDate(dateString, dailyObs)
            }
    }

    // ------------------------------------------------------------------
    // Labels & Merge Suggestions (unchanged - single files)
    // ------------------------------------------------------------------

    fun loadLabels(): List<ContentLabel> {
        return try {
            if (!labelsFile.exists()) return emptyList()
            val json = labelsFile.readText()
            if (json.isBlank()) return emptyList()
            val type = object : TypeToken<List<ContentLabel>>() {}.type
            (gson.fromJson<List<ContentLabel>>(json, type) ?: emptyList()).map { label ->
                @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
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
        observationsDir.listFiles { _, name -> name.endsWith(".json") }?.forEach { it.delete() }
        saveLabels(emptyList())
        saveMergeSuggestions(emptyList())
    }

    // ------------------------------------------------------------------
    // ZIP Export / Import
    // ------------------------------------------------------------------

    suspend fun exportToZip(outputStream: OutputStream): Result<Int> = withContext(Dispatchers.IO) {
        try {
            var totalObservations = 0

            ZipOutputStream(outputStream).use { zipOut ->
                // 1. Write manifest/metadata
                val manifest = mapOf(
                    "version" to EXPORT_VERSION,
                    "exportedAt" to System.currentTimeMillis(),
                    "packageName" to context.packageName
                )
                zipOut.putNextEntry(ZipEntry("manifest.json"))
                zipOut.write(gson.toJson(manifest).toByteArray())
                zipOut.closeEntry()

                // 2. Write labels
                val labels = loadLabels()
                if (labels.isNotEmpty()) {
                    zipOut.putNextEntry(ZipEntry("labels.json"))
                    zipOut.write(gson.toJson(labels).toByteArray())
                    zipOut.closeEntry()
                }

                // 3. Write merge suggestions
                val suggestions = loadMergeSuggestions()
                if (suggestions.isNotEmpty()) {
                    zipOut.putNextEntry(ZipEntry("merges.json"))
                    zipOut.write(gson.toJson(suggestions).toByteArray())
                    zipOut.closeEntry()
                }

                // 4. Write observations (daily files)
                observationsDir.listFiles { _, name -> name.endsWith(".json") }
                    ?.sortedBy { it.name }
                    ?.forEach { file ->
                        val entryName = "observations/${file.name}"
                        zipOut.putNextEntry(ZipEntry(entryName))
                        FileInputStream(file).use { input ->
                            input.copyTo(zipOut)
                        }
                        zipOut.closeEntry()

                        // Count observations
                        val count = try {
                            val json = file.readText()
                            val type = object : TypeToken<List<ScreenObservation>>() {}.type
                            gson.fromJson<List<ScreenObservation>>(json, type)?.size ?: 0
                        } catch (e: Exception) { 0 }
                        totalObservations += count
                    }
            }

            Result.success(totalObservations)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to export to zip", e)
            Result.failure(e)
        }
    }

    suspend fun importFromZip(inputStream: InputStream, merge: Boolean = false): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            var labels: List<ContentLabel>? = null
            var suggestions: List<LabelMergeSuggestion>? = null
            val dailyObservations = mutableMapOf<String, MutableList<ScreenObservation>>()
            var version: String? = null

            ZipInputStream(inputStream).use { zipIn ->
                var entry: ZipEntry?
                while (zipIn.nextEntry.also { entry = it } != null) {
                    val entryName = entry!!.name
                    val content = zipIn.bufferedReader().use { it.readText() }

                    when {
                        entryName == "manifest.json" -> {
                            val manifest = gson.fromJson(content, Map::class.java)
                            version = manifest["version"] as? String
                        }
                        entryName == "labels.json" -> {
                            val type = object : TypeToken<List<ContentLabel>>() {}.type
                            labels = gson.fromJson(content, type)
                        }
                        entryName == "merges.json" -> {
                            val type = object : TypeToken<List<LabelMergeSuggestion>>() {}.type
                            suggestions = gson.fromJson(content, type)
                        }
                        entryName.startsWith("observations/") && entryName.endsWith(".json") -> {
                            val dateString = entryName.substringAfter("observations/").substringBefore(".json")
                            val type = object : TypeToken<List<ScreenObservation>>() {}.type
                            val obs = gson.fromJson<List<ScreenObservation>>(content, type) ?: emptyList()
                            dailyObservations.getOrPut(dateString) { mutableListOf() }.addAll(obs)
                        }
                    }
                    zipIn.closeEntry()
                }
            }

            // Apply imported data
            labels?.let {
                if (merge) {
                    val existing = loadLabels().associateBy { it.labelId }.toMutableMap()
                    it.forEach { label -> existing[label.labelId] = label }
                    saveLabels(existing.values.toList())
                } else {
                    saveLabels(it)
                }
            }

            suggestions?.let {
                if (merge) {
                    val existing = loadMergeSuggestions().toMutableSet()
                    existing.addAll(it)
                    saveMergeSuggestions(existing.toList())
                } else {
                    saveMergeSuggestions(it)
                }
            }

            var totalObservations = 0
            dailyObservations.forEach { (dateString, observations) ->
                if (merge) {
                    val existingFile = File(observationsDir, "$dateString.json")
                    val existing = if (existingFile.exists()) {
                        try {
                            val json = existingFile.readText()
                            val type = object : TypeToken<List<ScreenObservation>>() {}.type
                            gson.fromJson<List<ScreenObservation>>(json, type) ?: emptyList()
                        } catch (e: Exception) {
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                    val mergedMap = (existing + observations).associateBy { it.observationId }
                    saveObservationsForDate(dateString, mergedMap.values.toList())
                    totalObservations += mergedMap.size
                } else {
                    saveObservationsForDate(dateString, observations)
                    totalObservations += observations.size
                }
            }

            Result.success(ImportResult(
                version = version ?: "unknown",
                labelsCount = labels?.size ?: 0,
                observationsCount = totalObservations,
                mergeSuggestionsCount = suggestions?.size ?: 0
            ))
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to import from zip", e)
            Result.failure(e)
        }
    }

    data class ImportResult(
        val version: String,
        val labelsCount: Int,
        val observationsCount: Int,
        val mergeSuggestionsCount: Int
    )

    // Legacy import support (JSON format)
    fun importFromLegacyJson(jsonString: String, merge: Boolean = false): Boolean {
        return try {
            val type = object : TypeToken<Map<String, Any?>>() {}.type
            val data = gson.fromJson<Map<String, Any?>>(jsonString, type) ?: return false

            // Import observations
            @Suppress("UNCHECKED_CAST")
            val observations = (data["observations"] as? List<Map<String, Any?>>)?.mapNotNull { obsMap ->
                try {
                    gson.fromJson(gson.toJson(obsMap), ScreenObservation::class.java)
                } catch (e: Exception) { null }
            } ?: emptyList()

            if (observations.isNotEmpty()) {
                if (merge) {
                    val existing = loadObservations().associateBy { it.observationId }.toMutableMap()
                    observations.forEach { obs -> existing[obs.observationId] = obs }
                    saveObservations(existing.values.toList())
                } else {
                    saveObservations(observations)
                }
            }

            // Import labels
            @Suppress("UNCHECKED_CAST")
            val labels = (data["labels"] as? List<Map<String, Any?>>)?.mapNotNull { labelMap ->
                try {
                    gson.fromJson(gson.toJson(labelMap), ContentLabel::class.java)
                } catch (e: Exception) { null }
            } ?: emptyList()

            if (labels.isNotEmpty()) {
                if (merge) {
                    val existing = loadLabels().associateBy { it.labelId }.toMutableMap()
                    labels.forEach { label -> existing[label.labelId] = label }
                    saveLabels(existing.values.toList())
                } else {
                    saveLabels(labels)
                }
            }

            // Import merge suggestions
            @Suppress("UNCHECKED_CAST")
            val suggestions = (data["mergeSuggestions"] as? List<Map<String, Any?>>)?.mapNotNull { sugMap ->
                try {
                    gson.fromJson(gson.toJson(sugMap), LabelMergeSuggestion::class.java)
                } catch (e: Exception) { null }
            } ?: emptyList()

            if (suggestions.isNotEmpty()) {
                if (merge) {
                    val existing = loadMergeSuggestions().toMutableSet()
                    existing.addAll(suggestions)
                    saveMergeSuggestions(existing.toList())
                } else {
                    saveMergeSuggestions(suggestions)
                }
            }

            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to import legacy JSON", e)
            false
        }
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

    fun computeSummary(
        startMs: Long,
        endMs: Long,
        languageCode: String? = null,
        sampleIntervalMs: Long = 5_000L
    ): UsageSummary {
        val labelById = loadLabels().associateBy { it.labelId }
        val observations = loadObservations(startMs, endMs)

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

    fun segmentObservations(
        startMs: Long,
        endMs: Long,
        languageCode: String? = null,
        gapThresholdMs: Long = 5 * 60_000L,
        sampleIntervalMs: Long = 5_000L
    ): List<UsageSegment> {
        val labelById = loadLabels().associateBy { it.labelId }
        val obs = loadObservations(startMs, endMs)

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

    // ------------------------------------------------------------------
    // buildMixedTimeline
    // ------------------------------------------------------------------

    fun buildMixedTimeline(
        startMs: Long,
        endMs: Long,
        languageCode: String? = null,
        gapThresholdMs: Long = 5 * 60_000L,
        sampleIntervalMs: Long = 5_000L
    ): List<TimelineEvent> {
        val segments = segmentObservations(startMs, endMs, languageCode, gapThresholdMs, sampleIntervalMs)
        val actionExecutions = actionExecutionRepo.loadExecutionsInRange(startMs, endMs)
        val sessions = groupSegmentsIntoSessionsWithActions(segments, actionExecutions)
        return sessions.map { session ->
            TimelineEvent(session = session, timestamp = session.startMs)
        }
    }

    private fun groupSegmentsIntoSessionsWithActions(
        segments: List<UsageSegment>,
        actions: List<ActionExecution>
    ): List<AppSession> {
        if (segments.isEmpty()) return emptyList()

        val sessions = mutableListOf<AppSession>()
        var currentSessionSegments = mutableListOf(segments[0])

        for (i in 1 until segments.size) {
            val current = segments[i]
            val lastInSession = currentSessionSegments.last()

            if (current.appName == lastInSession.appName) {
                currentSessionSegments.add(current)
            } else {
                val session = createSessionWithActions(currentSessionSegments, actions)
                sessions.add(session)
                currentSessionSegments = mutableListOf(current)
            }
        }

        if (currentSessionSegments.isNotEmpty()) {
            val session = createSessionWithActions(currentSessionSegments, actions)
            sessions.add(session)
        }

        return sessions
    }

    private fun createSessionWithActions(
        segments: List<UsageSegment>,
        allActions: List<ActionExecution>
    ): AppSession {
        val sessionStart = segments.first().startMs
        val sessionEnd = segments.last().endMs
        val appName = segments.first().appName

        val sessionActions = allActions.filter { action ->
            action.appName == appName && action.timestamp in sessionStart..sessionEnd
        }

        val segmentItems = segments.map { SessionItem.SegmentItem(it) }
        val actionItems = sessionActions.map { SessionItem.ActionItem(it) }
        val interleavedItems = (segmentItems + actionItems).sortedBy { it.timestamp }

        return AppSession(
            appName = appName,
            startMs = sessionStart,
            totalDurationMs = segments.sumOf { it.durationMs },
            items = interleavedItems
        )
    }

    // ------------------------------------------------------------------
    // ensureLocalizedNames
    // ------------------------------------------------------------------

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
