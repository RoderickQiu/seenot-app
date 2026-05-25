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
    @SerializedName("default_rule_id") val defaultRuleId: String? = null,
    @SerializedName("preset_rules") val presetRules: List<SessionConstraint> = emptyList(),
    @SerializedName("intent_history") val intentHistory: List<List<SessionConstraint>> = emptyList(),
    @SerializedName("supplemental_hints") val supplementalHints: List<SyncAppHint> = emptyList()
)

data class SyncAppHint(
    val id: String,
    @SerializedName("scope_type") val scopeType: String,
    @SerializedName("scope_key") val scopeKey: String,
    @SerializedName("intent_id") val intentId: String,
    @SerializedName("intent_label") val intentLabel: String,
    @SerializedName("hint_text") val hintText: String,
    val source: String? = null,
    @SerializedName("source_hint_id") val sourceHintId: String? = null,
    @SerializedName("is_active") val isActive: Boolean = true,
    @SerializedName("created_at") val createdAt: Long = 0L,
    @SerializedName("updated_at") val updatedAt: Long = 0L
)

data class SyncProfileLogSummary(
    val profileVersion: Int,
    val updatedAt: String?,
    val updatedByDeviceId: String?,
    val appCount: Int,
    val packages: List<String>,
    val presetRuleCount: Int,
    val intentHistoryCount: Int,
    val uploaded: Boolean,
    val deletedPackageCount: Int
) {
    fun toLogMessage(): String {
        return "Sync profile succeeded: " +
            "profileVersion=$profileVersion, " +
            "updatedAt=${updatedAt.orEmpty()}, " +
            "updatedByDeviceId=${updatedByDeviceId.orEmpty()}, " +
            "appCount=$appCount, " +
            "presetRuleCount=$presetRuleCount, " +
            "intentHistoryCount=$intentHistoryCount, " +
            "uploaded=$uploaded, " +
            "deletedPackageCount=$deletedPackageCount, " +
            "packages=${packages.joinToString(",")}"
    }

    fun toEventPayload(): Map<String, Any?> = mapOf(
        "profile_version" to profileVersion,
        "updated_at" to updatedAt,
        "updated_by_device_id" to updatedByDeviceId,
        "app_count" to appCount,
        "packages" to packages,
        "preset_rule_count" to presetRuleCount,
        "intent_history_count" to intentHistoryCount,
        "uploaded" to uploaded,
        "deleted_package_count" to deletedPackageCount
    )

    companion object {
        fun from(
            response: SyncProfileResponse,
            uploaded: Boolean,
            deletedPackageCount: Int
        ): SyncProfileLogSummary {
            val packages = response.profile.apps.keys
                .filter { it.isNotBlank() }
                .sorted()
            val appConfigs = response.profile.apps.values
            return SyncProfileLogSummary(
                profileVersion = response.profileVersion,
                updatedAt = response.updatedAt,
                updatedByDeviceId = response.updatedByDeviceId,
                appCount = packages.size,
                packages = packages,
                presetRuleCount = appConfigs.sumOf { it.presetRules.size },
                intentHistoryCount = appConfigs.sumOf { it.intentHistory.size },
                uploaded = uploaded,
                deletedPackageCount = deletedPackageCount.coerceAtLeast(0)
            )
        }
    }
}

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
                defaultRuleId = local.defaultRuleId ?: server.defaultRuleId,
                presetRules = mergeRulesById(server.presetRules, local.presetRules),
                intentHistory = dedupeHistory(local.intentHistory + server.intentHistory),
                supplementalHints = mergeHints(server.supplementalHints, local.supplementalHints)
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

    private fun mergeHints(server: List<SyncAppHint>, local: List<SyncAppHint>): List<SyncAppHint> {
        val merged = linkedMapOf<String, SyncAppHint>()
        server.forEach { merged[hintKey(it)] = it }
        local.forEach { merged[hintKey(it)] = it }
        return merged.values.toList()
    }

    private fun hintKey(hint: SyncAppHint): String {
        return hint.id.ifBlank {
            listOf(
                hint.scopeType,
                hint.scopeKey,
                hint.intentId,
                hint.hintText.trim().lowercase()
            ).joinToString("|")
        }
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
        deletedPackages: Set<String>,
        localProfileVersion: Int,
        server: SyncProfileDocument,
        local: SyncProfileDocument
    ): Boolean {
        return isDirty ||
            deletedPackages.isNotEmpty() ||
            (localProfileVersion <= 0 && local.hasSyncableContent()) ||
            local.hasContentMissingFrom(server)
    }

    private fun SyncProfileDocument.hasSyncableContent(): Boolean {
        return globalPreferences.isNotEmpty() ||
            apps.any { (packageName, appConfig) ->
                packageName.isNotBlank() && appConfig.hasSyncableContent()
            }
    }

    private fun SyncAppConfig.hasSyncableContent(): Boolean {
        return entryMode != null ||
            !lastIntent.isNullOrEmpty() ||
            !defaultRuleId.isNullOrBlank() ||
            presetRules.isNotEmpty() ||
            intentHistory.isNotEmpty() ||
            supplementalHints.isNotEmpty()
    }

    private fun SyncProfileDocument.hasContentMissingFrom(server: SyncProfileDocument): Boolean {
        if (globalPreferences.keys.any { it !in server.globalPreferences }) return true
        return apps.any { (packageName, localApp) ->
            if (packageName.isBlank()) return@any false
            val serverApp = server.apps[packageName] ?: return@any localApp.hasSyncableContent()
            localApp.hasContentMissingFrom(serverApp)
        }
    }

    private fun SyncAppConfig.hasContentMissingFrom(server: SyncAppConfig): Boolean {
        return (entryMode != null && server.entryMode == null) ||
            (!lastIntent.isNullOrEmpty() && server.lastIntent.isNullOrEmpty()) ||
            (!defaultRuleId.isNullOrBlank() && server.defaultRuleId.isNullOrBlank()) ||
            presetRules.any { localRule -> server.presetRules.none { it.id == localRule.id } } ||
            intentHistory.any { localHistory -> server.intentHistory.none { historyFingerprint(it) == historyFingerprint(localHistory) } } ||
            supplementalHints.any { localHint -> server.supplementalHints.none { hintKey(it) == hintKey(localHint) } }
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

    private fun hintKey(hint: SyncAppHint): String {
        return hint.id.ifBlank {
            listOf(
                hint.scopeType,
                hint.scopeKey,
                hint.intentId,
                hint.hintText.trim().lowercase()
            ).joinToString("|")
        }
    }
}
