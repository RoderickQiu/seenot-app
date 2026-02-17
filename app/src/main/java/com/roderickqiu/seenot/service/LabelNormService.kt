package com.roderickqiu.seenot.service

import android.content.Context
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
import com.roderickqiu.seenot.data.ContentLabel
import com.roderickqiu.seenot.data.LabelMergeSuggestion
import com.roderickqiu.seenot.data.LabelNormalizationRepo
import com.roderickqiu.seenot.data.RuleRecord
import com.roderickqiu.seenot.data.ScreenObservation
import com.roderickqiu.seenot.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.time.Duration.Companion.seconds

class LabelNormalizationService(
    private val context: Context,
    private val repo: LabelNormalizationRepo = LabelNormalizationRepo(context)
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences("seenot_label_norm", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "LabelNormalizationService"
        private const val NORMALIZE_INTERVAL_MS = 60_000L
        private const val LAST_NORMALIZE_KEY = "last_normalize_at"
        private const val MAP_CONFIDENCE_THRESHOLD = 0.60
        private const val CREATE_CONFIDENCE_THRESHOLD = 0.75
        private const val MAX_BATCH_SIZE = 24
        private const val MAX_LABELS_IN_PROMPT = 80
    }

    fun onRuleRecordSaved(record: RuleRecord) {
        if (record.screenshotHash.isNullOrBlank()) {
            return
        }
        scope.launch {
            mutex.withLock {
                try {
                    val rawReason = AIServiceUtils.parseReason(record.aiResult)
                        ?: (record.aiResult ?: "")
                    repo.upsertObservationFromRecord(record, rawReason)
                    val normalized = maybeRunNormalization()
                    if (normalized) {
                        // Match demo behavior: run merge pass right after normalization.
                        maybeRunMerge()
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to process new rule record for normalization", e)
                }
            }
        }
    }

    private suspend fun maybeRunNormalization(): Boolean {
        val now = System.currentTimeMillis()
        val lastRun = prefs.getLong(LAST_NORMALIZE_KEY, 0L)
        if (now - lastRun < NORMALIZE_INTERVAL_MS) {
            return false
        }

        val apiKey = AIServiceUtils.loadAiKey(context)
        if (apiKey.isBlank()) {
            return false
        }

        repo.ensureUnknownLabel()

        val observations = repo.loadObservations().sortedBy { it.timestamp }
        val pending = observations.filter { it.labelId.isNullOrBlank() }
        if (pending.isEmpty()) {
            prefs.edit().putLong(LAST_NORMALIZE_KEY, now).apply()
            return false
        }

        val firstTs = pending.first().timestamp
        val windowEnd = firstTs + NORMALIZE_INTERVAL_MS
        val batch = pending
            .filter { it.timestamp <= windowEnd }
            .take(MAX_BATCH_SIZE)
        if (batch.isEmpty()) {
            prefs.edit().putLong(LAST_NORMALIZE_KEY, now).apply()
            return false
        }

        val labels = repo.loadLabels()
        val decisions = requestNormalizationDecisions(apiKey, labels, batch)
        applyNormalizationDecisions(labels, observations, batch, decisions)

        prefs.edit().putLong(LAST_NORMALIZE_KEY, now).apply()
        return true
    }

    private suspend fun maybeRunMerge() {
        val apiKey = AIServiceUtils.loadAiKey(context)
        if (apiKey.isBlank()) {
            return
        }

        val labels = repo.loadLabels()
            .filter { it.labelId != LabelNormalizationRepo.UNKNOWN_LABEL_ID }
        if (labels.size < 3) {
            return
        }

        val observations = repo.loadObservations()
        val usageMap = mutableMapOf<String, Int>()
        observations.forEach { obs ->
            val id = obs.labelId ?: return@forEach
            usageMap[id] = (usageMap[id] ?: 0) + 1
        }

        val suggestions = requestMergeSuggestions(apiKey, labels, usageMap)
        if (suggestions.isNotEmpty()) {
            applyMerges(suggestions)
            repo.saveMergeSuggestions(suggestions)
        }
    }

    private suspend fun requestNormalizationDecisions(
        apiKey: String,
        labels: List<ContentLabel>,
        batch: List<ScreenObservation>
    ): JSONObject {
        val normalizedLabels = labels
            .filter { it.mergedInto == null }
            .takeLast(MAX_LABELS_IN_PROMPT)
            .map {
                mapOf(
                    "labelId" to it.labelId,
                    "displayName" to it.displayName,
                    "description" to it.description
                )
            }

        val observationsJson = batch.map {
            mapOf(
                "observationId" to it.observationId,
                "appName" to it.appName,
                "rawReason" to it.rawReason
            )
        }

        val prompt = buildString {
            appendLine("You are a screen-content label normalizer.")
            appendLine("Map each observation to existing labels, or create a new label when necessary.")
            appendLine("Return strict JSON only.")
            appendLine()
            appendLine("Existing labels:")
            appendLine(JSONObject(mapOf("labels" to normalizedLabels)).toString())
            appendLine()
            appendLine("Observations:")
            appendLine(JSONObject(mapOf("observations" to observationsJson)).toString())
            appendLine()
            appendLine("Rules:")
            appendLine("1) Prefer mapping to existing labels.")
            appendLine("2) Use labelId in snake_case English.")
            appendLine("3) Include confidence 0.0-1.0 for each decision.")
            appendLine("4) Output format:")
            appendLine(
                """{"decisions":[{"action":"map","observationId":"...","labelId":"...","confidence":0.92},{"action":"create","observationId":"...","newLabel":{"labelId":"...","displayName":"...","description":"..."},"confidence":0.88}]}"""
            )
        }

        val response = callTextModel(apiKey, prompt)
        return parseJsonObjectFromResponse(response)
    }

    private fun applyNormalizationDecisions(
        allLabels: List<ContentLabel>,
        allObservations: List<ScreenObservation>,
        batch: List<ScreenObservation>,
        result: JSONObject
    ) {
        val labelsById = allLabels.associateBy { it.labelId }.toMutableMap()
        val decisions = result.optJSONArray("decisions") ?: JSONArray()
        val batchMap = batch.associateBy { it.observationId }

        data class CandidateCreate(
            val observationId: String,
            val label: ContentLabel,
            val confidence: Double
        )

        val mapAssignments = mutableMapOf<String, String>()
        val createCandidates = mutableListOf<CandidateCreate>()
        val createCountByLabel = mutableMapOf<String, Int>()

        for (i in 0 until decisions.length()) {
            val decision = decisions.optJSONObject(i) ?: continue
            val observationId = decision.optString("observationId")
            if (observationId.isBlank() || !batchMap.containsKey(observationId)) continue

            val action = decision.optString("action")
            val confidence = decision.optDouble("confidence", 0.0)
            if (action == "map") {
                val labelId = decision.optString("labelId")
                if (labelId.isNotBlank() && confidence >= MAP_CONFIDENCE_THRESHOLD) {
                    mapAssignments[observationId] = labelId
                }
                continue
            }

            if (action == "create") {
                val newLabelObj = decision.optJSONObject("newLabel") ?: continue
                val labelId = newLabelObj.optString("labelId")
                if (labelId.isBlank()) continue
                val displayName = newLabelObj.optString("displayName", labelId)
                val description = newLabelObj.optString("description", "")
                val label = ContentLabel(
                    labelId = labelId,
                    displayName = displayName,
                    description = description
                )
                createCandidates.add(CandidateCreate(observationId, label, confidence))
                createCountByLabel[labelId] = (createCountByLabel[labelId] ?: 0) + 1
            }
        }

        val isBootstrap = allLabels.size <= 2
        createCandidates.forEach { candidate ->
            val repeats = createCountByLabel[candidate.label.labelId] ?: 0
            val canCreate = if (isBootstrap) {
                candidate.confidence >= CREATE_CONFIDENCE_THRESHOLD
            } else {
                candidate.confidence >= CREATE_CONFIDENCE_THRESHOLD && repeats >= 2
            }

            if (canCreate) {
                if (!labelsById.containsKey(candidate.label.labelId)) {
                    labelsById[candidate.label.labelId] = candidate.label
                }
                mapAssignments[candidate.observationId] = candidate.label.labelId
            } else {
                mapAssignments[candidate.observationId] = LabelNormalizationRepo.UNKNOWN_LABEL_ID
            }
        }

        val updated = allObservations.map { obs ->
            val assigned = mapAssignments[obs.observationId]
            if (assigned == null) {
                obs
            } else {
                obs.copy(labelId = assigned, normalizedAt = System.currentTimeMillis())
            }
        }

        repo.saveLabels(labelsById.values.toList().sortedBy { it.createdAt })
        repo.saveObservations(updated)
    }

    private suspend fun requestMergeSuggestions(
        apiKey: String,
        labels: List<ContentLabel>,
        usageMap: Map<String, Int>
    ): List<LabelMergeSuggestion> {
        val labelPayload = labels.map {
            mapOf(
                "labelId" to it.labelId,
                "displayName" to it.displayName,
                "description" to it.description,
                "usageCount" to (usageMap[it.labelId] ?: 0)
            )
        }

        val prompt = buildString {
            appendLine("You are a taxonomy curator.")
            appendLine("Suggest conservative merges for redundant labels.")
            appendLine("Return strict JSON only.")
            appendLine()
            appendLine("Labels:")
            appendLine(JSONObject(mapOf("labels" to labelPayload)).toString())
            appendLine()
            appendLine("Rules:")
            appendLine("1) Keep labels separate across different apps unless truly identical.")
            appendLine("2) Prefer merging low-frequency labels into higher-frequency labels.")
            appendLine("3) Include confidence 0.0-1.0.")
            appendLine("4) Output format:")
            appendLine(
                """{"merges":[{"mergeFrom":"...","mergeInto":"...","reason":"...","confidence":0.93}]}"""
            )
        }

        val response = callTextModel(apiKey, prompt)
        val obj = parseJsonObjectFromResponse(response)
        val merges = obj.optJSONArray("merges") ?: JSONArray()
        val result = mutableListOf<LabelMergeSuggestion>()
        for (i in 0 until merges.length()) {
            val m = merges.optJSONObject(i) ?: continue
            val mergeFrom = m.optString("mergeFrom")
            val mergeInto = m.optString("mergeInto")
            if (mergeFrom.isBlank() || mergeInto.isBlank() || mergeFrom == mergeInto) continue
            result.add(
                LabelMergeSuggestion(
                    mergeFrom = mergeFrom,
                    mergeInto = mergeInto,
                    reason = m.optString("reason"),
                    confidence = m.optDouble("confidence", 0.0)
                )
            )
        }
        return result
    }

    private fun applyMerges(suggestions: List<LabelMergeSuggestion>) {
        if (suggestions.isEmpty()) return

        val labels = repo.loadLabels().associateBy { it.labelId }.toMutableMap()
        val observations = repo.loadObservations().toMutableList()

        suggestions.forEach { suggestion ->
            if ((suggestion.confidence ?: 0.0) < 0.90) {
                return@forEach
            }

            val from = labels[suggestion.mergeFrom] ?: return@forEach
            val into = labels[suggestion.mergeInto] ?: return@forEach

            labels[suggestion.mergeFrom] = from.copy(mergedInto = into.labelId)
            for (i in observations.indices) {
                val obs = observations[i]
                if (obs.labelId == suggestion.mergeFrom) {
                    observations[i] = obs.copy(labelId = suggestion.mergeInto)
                }
            }
        }

        repo.saveLabels(labels.values.toList())
        repo.saveObservations(observations)
    }

    private suspend fun callTextModel(apiKey: String, prompt: String): String {
        val modelId = AIServiceUtils.loadNormalizationModelId(context)
        val openAI = OpenAI(
            token = apiKey,
            timeout = Timeout(socket = 90.seconds),
            host = OpenAIHost(baseUrl = AIServiceUtils.AI_BASE_URL)
        )

        val request = ChatCompletionRequest(
            model = ModelId(modelId),
            messages = listOf(
                ChatMessage(
                    role = ChatRole.User,
                    content = prompt
                )
            ),
            maxTokens = 2000
        )
        val response = openAI.chatCompletion(request)
        return response.choices.firstOrNull()?.message?.content.orEmpty()
    }

    private fun parseJsonObjectFromResponse(response: String): JSONObject {
        val cleaned = response.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return JSONObject(cleaned.substring(start, end + 1))
        }
        return JSONObject(cleaned)
    }
}
