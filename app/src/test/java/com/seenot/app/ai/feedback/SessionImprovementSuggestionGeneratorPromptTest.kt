package com.seenot.app.ai.feedback

import androidx.test.core.app.ApplicationProvider
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.MediaContentContext
import com.seenot.app.data.model.MediaContentStatus
import com.seenot.app.data.model.RuleRecord
import com.seenot.app.domain.SessionImprovementCandidate
import com.seenot.app.domain.SessionImprovementConfidence
import com.seenot.app.domain.SessionImprovementTrigger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionImprovementSuggestionGeneratorPromptTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun promptRequiresShortActionableOutputAndRejectsGenericRecap() {
        val generator = SessionImprovementSuggestionGenerator(context)
        val method = SessionImprovementSuggestionGenerator::class.java.getDeclaredMethod(
            "buildPrompt",
            SessionImprovementCandidate::class.java
        )
        method.isAccessible = true

        val prompt = method.invoke(generator, candidate()) as String

        assertTrue(prompt.contains("不要写会话总结文章"))
        assertTrue(prompt.contains("如果只能说泛泛鼓励或保持专注，返回 no_suggestion"))
        assertTrue(prompt.contains("next_intent_suggestion"))
        assertTrue(prompt.contains("supplemental_rule_candidate"))
        assertTrue(prompt.contains("evidence_record_ids"))
        assertFalse(prompt.contains("鸡汤"))
    }

    @Test
    fun promptCarriesMediaContextWithoutTreatingItAsAbsoluteTruth() {
        val prompt = SessionImprovementSuggestionGenerator(context).let { generator ->
            val method = SessionImprovementSuggestionGenerator::class.java.getDeclaredMethod(
                "buildPrompt",
                SessionImprovementCandidate::class.java
            )
            method.isAccessible = true
            method.invoke(generator, candidate()) as String
        }

        assertTrue(prompt.contains("媒体信息只能作为辅助证据"))
        assertTrue(prompt.contains("标题：Decorating a small apartment"))
        assertTrue(prompt.contains("频道/作者：Home Lab"))
    }

    private fun candidate(): SessionImprovementCandidate {
        return SessionImprovementCandidate(
            sessionId = 20L,
            appPackageName = "com.example.video",
            appDisplayName = "Video",
            shouldGenerate = true,
            primaryTrigger = SessionImprovementTrigger.VIOLATION_ACTION,
            confidence = SessionImprovementConfidence.MEDIUM,
            evidenceRecords = listOf(
                RuleRecord(
                    id = "r1",
                    sessionId = 20L,
                    appName = "Video",
                    packageName = "com.example.video",
                    constraintType = ConstraintType.DENY,
                    constraintContent = "只查装修内容，不看推荐流",
                    isConditionMatched = false,
                    aiResult = "进入了推荐流。",
                    confidence = 0.62,
                    mediaContext = MediaContentContext(
                        status = MediaContentStatus.MATCHED_CURRENT_APP,
                        packageName = "com.example.video",
                        title = "Decorating a small apartment",
                        artist = "Home Lab",
                        playbackState = "STATE_PLAYING",
                        durationMs = 300000L
                    )
                )
            )
        )
    }
}
