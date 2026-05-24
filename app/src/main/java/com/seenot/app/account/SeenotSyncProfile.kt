package com.seenot.app.account

import com.google.gson.annotations.SerializedName
import com.seenot.app.domain.AppEntryIntentMode
import com.seenot.app.domain.SessionConstraint

private const val SYNC_PROFILE_SCHEMA_VERSION = 1
private const val MAX_SYNC_HISTORY_PER_APP = 10

data class SyncProfileResponse(
    @SerializedName("profile_version") val profileVersion: Int,
    val profile: SyncProfileDocument,
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("updated_by_device_id") val updatedByDeviceId: String? = null
)

data class SyncProfilePutRequest(
    @SerializedName("profile_version") val profileVersion: Int,
    val profile: SyncProfileDocument
)

data class SyncProfileDocument(
    @SerializedName("schema_version") val schemaVersion: Int = SYNC_PROFILE_SCHEMA_VERSION,
    @SerializedName("global_preferences") val globalPreferences: Map<String, Any?> = emptyMap(),
    val apps: Map<String, SyncAppConfig> = emptyMap()
)

data class SyncAppConfig(
    @SerializedName("entry_mode") val entryMode: AppEntryIntentMode? = null,
    @SerializedName("last_intent") val lastIntent: List<SessionConstraint>? = null,
    @SerializedName("preset_rules") val presetRules: List<SessionConstraint> = emptyList(),
    @SerializedName("intent_history") val intentHistory: List<List<SessionConstraint>> = emptyList()
)

class SeenotSyncConflictException(
    message: String,
    val currentProfileVersion: Int,
    val serverProfile: SyncProfileDocument
) : Exception(message)

object SeenotSyncProfileMerger {
    fun merge(
        server: SyncProfileDocument,
        local: SyncProfileDocument,
        deletedPackages: Set<String> = emptySet()
    ): SyncProfileDocument {
        val mergedApps = linkedMapOf<String, SyncAppConfig>()
        (server.apps.keys + local.apps.keys - deletedPackages).sorted().forEach { packageName ->
            val serverApp = server.apps[packageName]
            val localApp = local.apps[packageName]
            mergedApps[packageName] = when {
                serverApp == null -> normalize(localApp ?: SyncAppConfig())
                localApp == null -> normalize(serverApp)
                else -> mergeApp(serverApp, localApp)
            }
        }
        return SyncProfileDocument(
            schemaVersion = SYNC_PROFILE_SCHEMA_VERSION,
            globalPreferences = server.globalPreferences + local.globalPreferences,
            apps = mergedApps
        )
    }

    private fun mergeApp(server: SyncAppConfig, local: SyncAppConfig): SyncAppConfig {
        return normalize(
            SyncAppConfig(
                entryMode = local.entryMode ?: server.entryMode,
                lastIntent = local.lastIntent ?: server.lastIntent,
                presetRules = mergeRulesById(server.presetRules, local.presetRules),
                intentHistory = dedupeHistory(local.intentHistory + server.intentHistory)
            )
        )
    }

    private fun normalize(app: SyncAppConfig): SyncAppConfig {
        return app.copy(
            presetRules = dedupeRulesById(app.presetRules),
            intentHistory = dedupeHistory(app.intentHistory)
        )
    }

    private fun mergeRulesById(server: List<SessionConstraint>, local: List<SessionConstraint>): List<SessionConstraint> {
        val merged = linkedMapOf<String, SessionConstraint>()
        server.forEach { merged[it.id] = it }
        local.forEach { merged[it.id] = it }
        return merged.values.toList()
    }

    private fun dedupeRulesById(rules: List<SessionConstraint>): List<SessionConstraint> {
        val deduped = linkedMapOf<String, SessionConstraint>()
        rules.forEach { deduped[it.id] = it }
        return deduped.values.toList()
    }

    private fun dedupeHistory(history: List<List<SessionConstraint>>): List<List<SessionConstraint>> {
        val seen = mutableSetOf<String>()
        return history
            .filter { it.isNotEmpty() }
            .filter { seen.add(historyFingerprint(it)) }
            .take(MAX_SYNC_HISTORY_PER_APP)
    }

    private fun historyFingerprint(constraints: List<SessionConstraint>): String {
        return constraints
            .sortedWith(compareBy<SessionConstraint> { it.type.name }.thenBy { it.description.trim().lowercase() })
            .joinToString(";") { constraint ->
                listOf(
                    constraint.type.name,
                    constraint.description.trim().replace(Regex("\\s+"), " ").lowercase(),
                    constraint.timeLimitMs?.toString().orEmpty(),
                    constraint.timeScope?.name.orEmpty(),
                    constraint.interventionLevel.name,
                    constraint.isActive.toString()
                ).joinToString("|")
            }
    }
}

object SeenotSyncDecision {
    fun shouldUpload(
        isDirty: Boolean,
        deletedPackages: Set<String>
    ): Boolean {
        return isDirty || deletedPackages.isNotEmpty()
    }
}
