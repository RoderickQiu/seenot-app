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
                deletedPackages = emptySet()
            )
        )
    }

    @Test
    fun localDirtyStateUploadsEvenWhenVersionsMatch() {
        assertEquals(
            true,
            SeenotSyncDecision.shouldUpload(
                isDirty = true,
                deletedPackages = emptySet()
            )
        )
    }

    @Test
    fun pendingDeletionUploadsEvenWithoutDirtyFlag() {
        assertEquals(
            true,
            SeenotSyncDecision.shouldUpload(
                isDirty = false,
                deletedPackages = setOf("com.removed")
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
