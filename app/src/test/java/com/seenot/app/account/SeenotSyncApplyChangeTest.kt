package com.seenot.app.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.seenot.app.config.InterventionDialogPrefs
import com.seenot.app.config.InterventionLevelPrefs
import com.seenot.app.config.RuleRecordingPrefs
import com.seenot.app.data.model.APP_HINT_SOURCE_MANUAL
import com.seenot.app.data.model.AppHintScopeType
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.data.model.TimeScope
import com.seenot.app.data.repository.AppHintRepository
import com.seenot.app.domain.AppEntryIntentMode
import com.seenot.app.domain.SessionConstraint
import com.seenot.app.domain.SessionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SeenotSyncApplyChangeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val packageName = "com.example.focus"

    @Before
    fun setUp() {
        runBlocking {
            SeenotAccountSession.init(context)
            SeenotAccountSession.clear()
            listOf(
                "seenot_prefs",
                "seenot_rule_recording",
                "seenot_intervention_level",
                "seenot_intervention_dialog",
                "seenot_sync_v2"
            ).forEach { prefsName ->
                context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
            }
            AppHintRepository(context).deleteHintsForPackage(packageName)
        }
    }

    @Test
    fun applyRemoteEntitiesRestoresSettingsThatAreNotPresetRules() = runBlocking {
        val sessionManager = SessionManager(context)
        val rule = rule(id = "server-rule", description = "Messages only")

        sessionManager.applySyncChange(
            change(
                entityType = SyncEntityType.GLOBAL_PREF.value,
                entityId = "global",
                payload = mapOf(
                    "show_home_timeline" to false,
                    "analysis_result_toast" to true,
                    "fixed_intervention_level_enabled" to true,
                    "fixed_intervention_level" to "STRICT",
                    "non_gentle_allow_ignore_once" to true
                )
            )
        )
        sessionManager.applySyncChange(
            change(
                entityType = SyncEntityType.MONITORED_APP.value,
                entityId = packageName,
                payload = mapOf(
                    "package_name" to packageName,
                    "entry_mode" to AppEntryIntentMode.USE_PRESET.name
                )
            )
        )
        sessionManager.applySyncChange(
            change(
                entityType = SyncEntityType.APP_RULES.value,
                entityId = packageName,
                payload = mapOf("preset_rules" to listOf(rule.toPayload()))
            )
        )
        sessionManager.applySyncChange(
            change(
                entityType = SyncEntityType.APP_HINTS.value,
                entityId = packageName,
                payload = mapOf(
                    "supplemental_hints" to listOf(
                        mapOf(
                            "id" to "server-hint",
                            "scope_type" to AppHintScopeType.APP_GENERAL.name,
                            "scope_key" to "app:$packageName",
                            "intent_id" to "app:$packageName",
                            "intent_label" to "Whole app",
                            "hint_text" to "Server hint",
                            "source" to APP_HINT_SOURCE_MANUAL,
                            "created_at" to 100L,
                            "updated_at" to 200L
                        )
                    )
                )
            )
        )

        assertFalse(RuleRecordingPrefs.isHomeTimelineEnabled(context))
        assertTrue(RuleRecordingPrefs.isAnalysisResultToastEnabled(context))
        assertTrue(InterventionLevelPrefs.isFixedLevelEnabled(context))
        assertEquals(InterventionLevel.STRICT, InterventionLevelPrefs.getFixedLevel(context))
        assertTrue(InterventionDialogPrefs.isNonGentleAllowIgnoreOnceEnabled(context))
        assertEquals(AppEntryIntentMode.USE_PRESET, sessionManager.getAppEntryIntentMode(packageName))
        assertEquals(rule.id, sessionManager.loadPresetRules(packageName).single().id)
        assertEquals("Server hint", AppHintRepository(context).getHintsForPackage(packageName).single().hintText)
    }

    @Test
    fun remoteTombstoneRemovesControlledAppWithoutRequeueingDelete() {
        val sessionManager = SessionManager(context)
        sessionManager.addControlledApp(packageName)
        SharedPrefsSyncStore(context).pendingOps().forEach { SharedPrefsSyncStore(context).removePendingOp(it.opId) }

        sessionManager.applySyncChange(
            change(
                entityType = SyncEntityType.MONITORED_APP.value,
                entityId = packageName,
                operation = SyncOperation.DELETE,
                payload = null,
                deletedAt = "2026-05-25T00:00:00Z"
            )
        )

        assertFalse(sessionManager.getControlledAppsSnapshot().contains(packageName))
        assertTrue(SharedPrefsSyncStore(context).pendingOps().isEmpty())
    }

    @Test
    fun malformedPersistedPendingOpsAreDropped() {
        context.getSharedPreferences("seenot_sync_v2", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "pending_ops",
                """{"bad-op":{"base_revision":0},"also-bad":{"base_revision":2}}"""
            )
            .commit()

        assertTrue(SharedPrefsSyncStore(context).pendingOps().isEmpty())
        assertEquals(
            "{}",
            context.getSharedPreferences("seenot_sync_v2", Context.MODE_PRIVATE)
                .getString("pending_ops", null)
        )
    }

    private fun change(
        entityType: String,
        entityId: String,
        operation: SyncOperation = SyncOperation.UPSERT,
        payload: Map<String, Any?>?,
        deletedAt: String? = null
    ) = SyncChange(
        serverSequence = 1,
        changeId = "change-$entityType-$entityId",
        entityType = entityType,
        entityId = entityId,
        operation = operation,
        revision = 1,
        payload = payload,
        deletedAt = deletedAt,
        deviceId = "device-a",
        createdAt = "2026-05-25T00:00:00Z"
    )

    private fun SessionConstraint.toPayload(): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to type.name,
        "description" to description,
        "timeLimitMs" to timeLimitMs,
        "timeScope" to (timeScope?.name ?: "SESSION"),
        "interventionLevel" to interventionLevel.name,
        "isActive" to isActive,
        "isDefault" to isDefault
    )

    private fun rule(id: String, description: String) = SessionConstraint(
        id = id,
        type = ConstraintType.DENY,
        description = description,
        timeLimitMs = 60_000L,
        timeScope = TimeScope.SESSION,
        interventionLevel = InterventionLevel.MODERATE,
        isActive = true,
        isDefault = true
    )
}
