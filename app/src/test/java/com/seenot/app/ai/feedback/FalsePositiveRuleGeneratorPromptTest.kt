package com.seenot.app.ai.feedback

import androidx.test.core.app.ApplicationProvider
import com.seenot.app.data.model.AppHintScopeType
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.EffectiveIntent
import com.seenot.app.data.model.MediaContentContext
import com.seenot.app.data.model.MediaContentStatus
import com.seenot.app.data.model.RuleRecord
import com.seenot.app.domain.SessionConstraint
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FalsePositiveRuleGeneratorPromptTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun buildPromptIncludesStructuredEffectiveIntentForComplementDenyConstraints() {
        val generator = FalsePositiveRuleGenerator(context)
        val method = FalsePositiveRuleGenerator::class.java.getDeclaredMethod(
            "buildPrompt",
            String::class.java,
            String::class.java,
            RuleRecord::class.java,
            SessionConstraint::class.java,
            List::class.java,
            List::class.java,
            String::class.java
        )
        method.isAccessible = true

        val constraint = SessionConstraint(
            id = "rule-1",
            type = ConstraintType.DENY,
            description = "禁止查看和租房、住宿无关的内容",
            effectiveIntent = EffectiveIntent(
                raw = "禁止查看和租房、住宿无关的内容",
                type = ConstraintType.DENY,
                prohibitedSet = "与租房、住宿无关的单个内容或功能",
                allowedSet = "租房、住宿相关的单个内容或功能",
                evaluationScope = "feature_or_behavior",
                aggregatePagePolicy = "follow_constraint_target",
                decisionRule = "Only treat the current screen as allowed when there is direct evidence that the user is already in rental or accommodation content."
            )
        )
        val record = RuleRecord(
            sessionId = 1L,
            appName = "YouTube",
            packageName = "com.google.android.youtube",
            constraintType = ConstraintType.DENY,
            constraintContent = constraint.description,
            isConditionMatched = true,
            aiResult = "系统误把生活成本话题当成租房相关。"
        )

        val prompt = method.invoke(
            generator,
            "YouTube",
            "com.google.android.youtube",
            record,
            constraint,
            emptyList<Any>(),
            emptyList<Any>(),
            null
        ) as String

        assertTrue(prompt.contains("结构化 intent 语义"))
        assertTrue(prompt.contains("allowed_set: 租房、住宿相关的单个内容或功能"))
        assertTrue(prompt.contains("prohibited_set: 与租房、住宿无关的单个内容或功能"))
        assertTrue(prompt.contains("aggregate_page_policy: follow_constraint_target"))
        assertTrue(prompt.contains("Only treat the current screen as allowed"))
        assertTrue(prompt.contains("必须先以这里的 allowed_set / prohibited_set / decision_rule 为准"))
    }

    @Test
    fun buildPromptIncludesSavedMediaContextWhenMetadataIsUsable() {
        val generator = FalsePositiveRuleGenerator(context)
        val method = FalsePositiveRuleGenerator::class.java.getDeclaredMethod(
            "buildPrompt",
            String::class.java,
            String::class.java,
            RuleRecord::class.java,
            SessionConstraint::class.java,
            List::class.java,
            List::class.java,
            String::class.java
        )
        method.isAccessible = true

        val constraint = SessionConstraint(
            id = "rule-2",
            type = ConstraintType.DENY,
            description = "禁止查看和租房、住宿无关的内容"
        )
        val record = RuleRecord(
            sessionId = 2L,
            appName = "YouTube",
            packageName = "com.google.android.youtube",
            constraintType = ConstraintType.DENY,
            constraintContent = constraint.description,
            isConditionMatched = true,
            mediaContext = MediaContentContext(
                status = MediaContentStatus.MATCHED_CURRENT_APP,
                packageName = "com.google.android.youtube",
                playbackState = "STATE_PLAYING",
                title = "Seattle Cost of Living Explained",
                artist = "City Career Lab",
                durationMs = 845000L
            )
        )

        val prompt = method.invoke(
            generator,
            "YouTube",
            "com.google.android.youtube",
            record,
            constraint,
            emptyList<Any>(),
            emptyList<Any>(),
            null
        ) as String

        assertTrue(prompt.contains("当前媒体播放信息"))
        assertTrue(prompt.contains("标题：Seattle Cost of Living Explained"))
        assertTrue(prompt.contains("作者/频道：City Career Lab"))
        assertTrue(prompt.contains("播放状态：STATE_PLAYING"))
        assertTrue(prompt.contains("媒体信息使用规则"))
    }
}
