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
        assertTrue(prompt.contains("大图/视频卡片即使占据屏幕大部分，也不自动等于单项消费"))
        assertTrue(prompt.contains("顶部推荐/关注标签、底部导航、发布按钮、上下条目残留或信息流互动栏"))
    }

    @Test
    fun promptIncludesBuiltInXTimelineHintWhenProvided() {
        val analyzer = ScreenAnalyzer(context)
        val prompt = analyzer.buildAnalysisPrompt(
            constraints = listOf(
                SessionConstraint(
                    id = "rule-1",
                    type = ConstraintType.DENY,
                    description = "足球内容",
                    effectiveIntent = EffectiveIntent(
                        raw = "足球内容",
                        type = ConstraintType.DENY,
                        prohibitedSet = "足球内容",
                        allowedSet = null,
                        evaluationScope = "single_content_only",
                        aggregatePagePolicy = "candidate_exposure_is_safe",
                        decisionRule = "Only single actively consumed football content can violate."
                    )
                )
            ),
            appName = "X",
            packageName = "com.twitter.android",
            appGeneralHints = com.seenot.app.data.builtin.BuiltInAppHintRules
                .getAppGeneralHints("com.twitter.android")
        )

        assertTrue(prompt.contains("[禁止] 足球内容"))
        assertTrue(prompt.contains("X/Twitter 首页时间线"))
        assertTrue(prompt.contains("只要仍可见顶部推荐/正在关注标签、底部主导航、浮动发帖按钮"))
        assertTrue(prompt.contains("只有出现返回按钮、发帖/帖子详情标题、单条独立时间/浏览量"))
    }
}
