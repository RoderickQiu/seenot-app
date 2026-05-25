package com.seenot.app.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.seenot.app.config.InterventionDialogPrefs
import com.seenot.app.config.InterventionLevelPrefs
import com.seenot.app.config.RuleRecordingPrefs
import com.seenot.app.data.model.APP_HINT_SOURCE_MANUAL
import com.seenot.app.data.model.AppHint
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SeenotSyncSnapshotTest {
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
                "seenot_intervention_dialog"
            ).forEach { prefsName ->
                context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
            }
            AppHintRepository(context).deleteHintsForPackage(packageName)
        }
    }

    @Test
    fun syncSnapshotCarriesSettingsThatAreNotPresetRules() = runBlocking {
        val sessionManager = SessionManager(context)
        val rule = rule(id = "rule-default", description = "Study videos only")
        sessionManager.setControlledApps(setOf(packageName))
        sessionManager.savePresetRules(packageName, listOf(rule))
        sessionManager.setDefaultRule(packageName, rule.id)
        sessionManager.setAppEntryIntentMode(packageName, AppEntryIntentMode.USE_PRESET)
        RuleRecordingPrefs.setHomeTimelineEnabled(context, false)
        RuleRecordingPrefs.setAnalysisResultToastEnabled(context, true)
        InterventionLevelPrefs.setFixedLevel(context, InterventionLevel.STRICT)
        InterventionLevelPrefs.setFixedLevelEnabled(context, true)
        InterventionDialogPrefs.setNonGentleAllowIgnoreOnceEnabled(context, true)
        AppHintRepository(context).saveHint(
            AppHint(
                id = "hint-1",
                packageName = packageName,
                scopeType = AppHintScopeType.APP_GENERAL,
                scopeKey = "app:$packageName",
                intentId = "app:$packageName",
                intentLabel = "Whole app",
                hintText = "Only count actively watched videos",
                source = APP_HINT_SOURCE_MANUAL,
                createdAt = 100L,
                updatedAt = 200L
            )
        )

        val profile = sessionManager.buildSyncProfileSnapshot()
        val app = profile.apps.getValue(packageName)

        assertEquals(false, profile.globalPreferences["show_home_timeline"])
        assertEquals(true, profile.globalPreferences["analysis_result_toast"])
        assertEquals(true, profile.globalPreferences["fixed_intervention_level_enabled"])
        assertEquals("STRICT", profile.globalPreferences["fixed_intervention_level"])
        assertEquals(true, profile.globalPreferences["non_gentle_allow_ignore_once"])
        assertEquals(rule.id, app.defaultRuleId)
        assertEquals(AppEntryIntentMode.USE_PRESET, app.entryMode)
        assertEquals("Only count actively watched videos", app.supplementalHints.single().hintText)
    }

    @Test
    fun applySyncSnapshotRestoresSettingsThatAreNotPresetRules() = runBlocking {
        val sessionManager = SessionManager(context)
        val rule = rule(id = "server-rule", description = "Messages only")
        val profile = SyncProfileDocument(
            globalPreferences = mapOf(
                "show_home_timeline" to false,
                "analysis_result_toast" to true,
                "fixed_intervention_level_enabled" to true,
                "fixed_intervention_level" to "STRICT",
                "non_gentle_allow_ignore_once" to true
            ),
            apps = mapOf(
                packageName to SyncAppConfig(
                    entryMode = AppEntryIntentMode.USE_PRESET,
                    defaultRuleId = rule.id,
                    presetRules = listOf(rule),
                    supplementalHints = listOf(
                        SyncAppHint(
                            id = "server-hint",
                            scopeType = AppHintScopeType.APP_GENERAL.name,
                            scopeKey = "app:$packageName",
                            intentId = "app:$packageName",
                            intentLabel = "Whole app",
                            hintText = "Server hint",
                            source = APP_HINT_SOURCE_MANUAL,
                            createdAt = 100L,
                            updatedAt = 200L
                        )
                    )
                )
            )
        )

        sessionManager.applySyncProfileSnapshot(profile)

        assertEquals(false, RuleRecordingPrefs.isHomeTimelineEnabled(context))
        assertTrue(RuleRecordingPrefs.isAnalysisResultToastEnabled(context))
        assertTrue(InterventionLevelPrefs.isFixedLevelEnabled(context))
        assertEquals(InterventionLevel.STRICT, InterventionLevelPrefs.getFixedLevel(context))
        assertTrue(InterventionDialogPrefs.isNonGentleAllowIgnoreOnceEnabled(context))
        assertEquals(rule.id, sessionManager.getDefaultRule(packageName)?.id)
        assertEquals(AppEntryIntentMode.USE_PRESET, sessionManager.getAppEntryIntentMode(packageName))
        assertEquals("Server hint", AppHintRepository(context).getHintsForPackage(packageName).single().hintText)
        assertEquals(false, SeenotAccountSession.isSyncDirty())
    }

    private fun rule(id: String, description: String) = SessionConstraint(
        id = id,
        type = ConstraintType.DENY,
        description = description,
        timeLimitMs = 60_000L,
        timeScope = TimeScope.SESSION,
        interventionLevel = InterventionLevel.MODERATE,
        isActive = true
    )
}
