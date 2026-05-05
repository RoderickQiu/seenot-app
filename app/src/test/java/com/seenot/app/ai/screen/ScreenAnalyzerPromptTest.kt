package com.seenot.app.ai.screen

import androidx.test.core.app.ApplicationProvider
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.EffectiveIntent
import com.seenot.app.domain.SessionConstraint
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScreenAnalyzerPromptTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun promptIncludesInternalEffectiveIntentWithoutReplacingVisibleRule() {
        val analyzer = ScreenAnalyzer(context)
        val prompt = analyzer.buildAnalysisPrompt(
            constraints = listOf(
                SessionConstraint(
                    id = "rule-1",
                    type = ConstraintType.DENY,
                    description = "除目标主题外的其他内容",
                    effectiveIntent = EffectiveIntent(
                        raw = "除目标主题外的其他内容",
                        type = ConstraintType.DENY,
                        prohibitedSet = "与目标主题无关的单个内容",
                        allowedSet = "与目标主题相关的单个内容",
                        evaluationScope = "single_content_only",
                        aggregatePagePolicy = "candidate_exposure_is_safe",
                        decisionRule = "Only single actively consumed content can violate."
                    )
                )
            ),
            appName = "Example",
            packageName = "com.example"
        )

        assertTrue(prompt.contains("[禁止] 除目标主题外的其他内容"))
        assertTrue(prompt.contains("effective_intent"))
        assertTrue(prompt.contains("candidate_exposure_is_safe"))
        assertTrue(prompt.contains("只有进入单个详情页、播放页、文章页、商品页"))
        assertTrue(prompt.contains("如果约束禁止的是信息流/推荐列表/聚合容器本身"))
    }
}
