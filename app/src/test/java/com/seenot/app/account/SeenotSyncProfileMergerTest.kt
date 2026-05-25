package com.seenot.app.account

import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.data.model.TimeScope
import com.seenot.app.domain.AppEntryIntentMode
import com.seenot.app.domain.SessionConstraint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeenotSyncProfileMergerTest {
    @Test
    fun serverVersionChangeWithoutLocalDirtyStateDownloadsOnly() {
        assertEquals(
            false,
            SeenotSyncDecision.shouldUpload(
                isDirty = false,
                deletedPackages = emptySet(),
                localProfileVersion = 3,
                server = SyncProfileDocument(
                    apps = mapOf(
                        "com.synced" to SyncAppConfig(
                            presetRules = listOf(rule(id = "synced-rule", description = "Already synced"))
                        )
                    )
                ),
                local = SyncProfileDocument(
                    apps = mapOf(
                        "com.synced" to SyncAppConfig(
                            presetRules = listOf(rule(id = "synced-rule", description = "Already synced"))
                        )
                    )
                )
            )
        )
    }

    @Test
    fun localDirtyStateUploadsEvenWhenVersionsMatch() {
        assertEquals(
            true,
            SeenotSyncDecision.shouldUpload(
                isDirty = true,
                deletedPackages = emptySet(),
                localProfileVersion = 3,
                server = SyncProfileDocument(),
                local = SyncProfileDocument()
            )
        )
    }

    @Test
    fun pendingDeletionUploadsEvenWithoutDirtyFlag() {
        assertEquals(
            true,
            SeenotSyncDecision.shouldUpload(
                isDirty = false,
                deletedPackages = setOf("com.removed"),
                localProfileVersion = 3,
                server = SyncProfileDocument(),
                local = SyncProfileDocument()
            )
        )
    }

    @Test
    fun firstSyncUploadsExistingLocalConfigurationEvenWithoutDirtyFlag() {
        val local = SyncProfileDocument(
            apps = mapOf(
                "com.existing" to SyncAppConfig(
                    presetRules = listOf(rule(id = "existing-rule", description = "Existing config"))
                )
            )
        )

        assertEquals(
            true,
            SeenotSyncDecision.shouldUpload(
                isDirty = false,
                deletedPackages = emptySet(),
                localProfileVersion = 0,
                server = SyncProfileDocument(),
                local = local
            )
        )
    }

    @Test
    fun localConfigurationMissingFromServerUploadsEvenAfterPreviousDownloadOnlySync() {
        val local = SyncProfileDocument(
            apps = mapOf(
                "com.existing" to SyncAppConfig(
                    entryMode = AppEntryIntentMode.USE_PRESET,
                    presetRules = listOf(rule(id = "existing-rule", description = "Existing config"))
                )
            )
        )

        assertEquals(
            true,
            SeenotSyncDecision.shouldUpload(
                isDirty = false,
                deletedPackages = emptySet(),
                localProfileVersion = 5,
                server = SyncProfileDocument(),
                local = local
            )
        )
    }

    @Test
    fun firstSyncWithEmptyLocalConfigurationDownloadsOnly() {
        assertEquals(
            false,
            SeenotSyncDecision.shouldUpload(
                isDirty = false,
                deletedPackages = emptySet(),
                localProfileVersion = 0,
                server = SyncProfileDocument(),
                local = SyncProfileDocument()
            )
        )
    }

    @Test
    fun dirtyLocalConfigMergesWithServerOnlyAppsWithoutOverwritingThem() {
        val serverOnlyRule = rule(id = "server-rule", description = "Only short videos")
        val localRule = rule(id = "local-rule", description = "Only messages")

        val server = SyncProfileDocument(
            apps = mapOf(
                "com.video" to SyncAppConfig(
                    entryMode = AppEntryIntentMode.USE_PRESET,
                    presetRules = listOf(serverOnlyRule)
                )
            )
        )
        val local = SyncProfileDocument(
            apps = mapOf(
                "com.chat" to SyncAppConfig(
                    entryMode = AppEntryIntentMode.USE_LAST_INTENT,
                    presetRules = listOf(localRule)
                )
            )
        )

        val merged = SeenotSyncProfileMerger.merge(server = server, local = local)

        assertEquals(setOf("com.video", "com.chat"), merged.apps.keys)
        assertEquals(serverOnlyRule, merged.apps.getValue("com.video").presetRules.single())
        assertEquals(localRule, merged.apps.getValue("com.chat").presetRules.single())
    }

    @Test
    fun localDirtyRuleWithSameIdWinsAndHistoryIsDeduped() {
        val serverRule = rule(id = "same-rule", description = "Old rule", interventionLevel = InterventionLevel.GENTLE)
        val localRule = rule(id = "same-rule", description = "New rule", interventionLevel = InterventionLevel.STRICT)
        val duplicateHistory = listOf(rule(id = "history-a", description = "Avoid recommendations"))

        val server = SyncProfileDocument(
            apps = mapOf(
                "com.social" to SyncAppConfig(
                    entryMode = AppEntryIntentMode.ASK_EVERY_TIME,
                    presetRules = listOf(serverRule),
                    intentHistory = listOf(duplicateHistory)
                )
            )
        )
        val local = SyncProfileDocument(
            apps = mapOf(
                "com.social" to SyncAppConfig(
                    entryMode = AppEntryIntentMode.USE_PRESET,
                    presetRules = listOf(localRule),
                    intentHistory = listOf(duplicateHistory, listOf(localRule))
                )
            )
        )

        val merged = SeenotSyncProfileMerger.merge(server = server, local = local)
        val mergedApp = merged.apps.getValue("com.social")

        assertEquals(AppEntryIntentMode.USE_PRESET, mergedApp.entryMode)
        assertEquals(localRule, mergedApp.presetRules.single())
        assertEquals(2, mergedApp.intentHistory.size)
        assertTrue(mergedApp.intentHistory.first().any { it.description == "Avoid recommendations" })
    }

    @Test
    fun localDirtySettingsMergeGlobalPreferencesAndPerAppSettings() {
        val serverRule = rule(id = "same-rule", description = "Server default")
        val localRule = rule(id = "same-rule", description = "Local default")
        val serverHint = SyncAppHint(
            id = "server-hint",
            scopeType = "APP_GENERAL",
            scopeKey = "app:com.social",
            intentId = "app:com.social",
            intentLabel = "Whole app",
            hintText = "Server hint"
        )
        val localHint = SyncAppHint(
            id = "local-hint",
            scopeType = "INTENT_SPECIFIC",
            scopeKey = "intent:focus",
            intentId = "intent:focus",
            intentLabel = "Focus",
            hintText = "Local hint"
        )
        val server = SyncProfileDocument(
            globalPreferences = mapOf(
                "show_home_timeline" to true,
                "fixed_intervention_level" to "GENTLE"
            ),
            apps = mapOf(
                "com.social" to SyncAppConfig(
                    defaultRuleId = serverRule.id,
                    presetRules = listOf(serverRule),
                    supplementalHints = listOf(serverHint)
                )
            )
        )
        val local = SyncProfileDocument(
            globalPreferences = mapOf(
                "fixed_intervention_level" to "STRICT",
                "analysis_result_toast" to true
            ),
            apps = mapOf(
                "com.social" to SyncAppConfig(
                    defaultRuleId = localRule.id,
                    presetRules = listOf(localRule),
                    supplementalHints = listOf(localHint)
                )
            )
        )

        val merged = SeenotSyncProfileMerger.merge(server = server, local = local)
        val app = merged.apps.getValue("com.social")

        assertEquals(true, merged.globalPreferences["show_home_timeline"])
        assertEquals("STRICT", merged.globalPreferences["fixed_intervention_level"])
        assertEquals(true, merged.globalPreferences["analysis_result_toast"])
        assertEquals(localRule.id, app.defaultRuleId)
        assertEquals(localRule, app.presetRules.single())
        assertEquals(listOf(serverHint, localHint), app.supplementalHints)
    }

    @Test
    fun deletedLocalAppsAreNotResurrectedFromServerDuringMerge() {
        val server = SyncProfileDocument(
            apps = mapOf(
                "com.removed" to SyncAppConfig(presetRules = listOf(rule(id = "removed-rule", description = "Removed"))),
                "com.kept" to SyncAppConfig(presetRules = listOf(rule(id = "kept-rule", description = "Kept")))
            )
        )
        val local = SyncProfileDocument(
            apps = mapOf(
                "com.kept" to SyncAppConfig(presetRules = listOf(rule(id = "local-kept-rule", description = "Local kept")))
            )
        )

        val merged = SeenotSyncProfileMerger.merge(
            server = server,
            local = local,
            deletedPackages = setOf("com.removed")
        )

        assertEquals(setOf("com.kept"), merged.apps.keys)
        assertTrue(merged.apps.getValue("com.kept").presetRules.any { it.description == "Local kept" })
    }

    @Test
    fun syncSuccessLogSummaryKeepsImportantNonRuleContent() {
        val response = SyncProfileResponse(
            profileVersion = 7,
            updatedAt = "2026-05-25T12:00:00+08:00",
            updatedByDeviceId = "device-2",
            profile = SyncProfileDocument(
                apps = mapOf(
                    "com.chat" to SyncAppConfig(
                        presetRules = listOf(rule(id = "chat-rule", description = "Keep chat focused")),
                        intentHistory = listOf(listOf(rule(id = "chat-history", description = "Only reply to messages")))
                    ),
                    "com.video" to SyncAppConfig(
                        presetRules = listOf(
                            rule(id = "video-rule-1", description = "No shorts"),
                            rule(id = "video-rule-2", description = "Learning only")
                        )
                    )
                )
            )
        )

        val summary = SyncProfileLogSummary.from(
            response = response,
            uploaded = true,
            deletedPackageCount = 1
        )

        assertEquals(7, summary.profileVersion)
        assertEquals("2026-05-25T12:00:00+08:00", summary.updatedAt)
        assertEquals("device-2", summary.updatedByDeviceId)
        assertEquals(2, summary.appCount)
        assertEquals(listOf("com.chat", "com.video"), summary.packages)
        assertEquals(3, summary.presetRuleCount)
        assertEquals(1, summary.intentHistoryCount)
        assertEquals(true, summary.uploaded)
        assertEquals(1, summary.deletedPackageCount)
        assertTrue(summary.toLogMessage().contains("profileVersion=7"))
        assertTrue(summary.toLogMessage().contains("packages=com.chat,com.video"))
    }

    private fun rule(
        id: String,
        description: String,
        interventionLevel: InterventionLevel = InterventionLevel.MODERATE
    ) = SessionConstraint(
        id = id,
        type = ConstraintType.DENY,
        description = description,
        timeLimitMs = 60_000L,
        timeScope = TimeScope.SESSION,
        interventionLevel = interventionLevel,
        isActive = true
    )
}
