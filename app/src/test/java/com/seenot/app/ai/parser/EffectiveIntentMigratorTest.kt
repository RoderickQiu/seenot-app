package com.seenot.app.ai.parser

import androidx.test.core.app.ApplicationProvider
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.TimeScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EffectiveIntentMigratorTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val migrator = EffectiveIntentMigrator({ context })

    @Test
    fun fallbackForDenyIsConservativeAndInternal() {
        val effective = migrator.buildFallback(
            type = ConstraintType.DENY,
            description = "除消息外的其他内容",
            timeScope = TimeScope.SESSION
        )

        assertEquals("除消息外的其他内容", effective.raw)
        assertEquals(ConstraintType.DENY, effective.type)
        assertEquals("除消息外的其他内容", effective.prohibitedSet)
        assertNull(effective.allowedSet)
        assertEquals("follow_constraint_target", effective.aggregatePagePolicy)
    }

    @Test
    fun fallbackForTimeCapUsesScopeSemantics() {
        val effective = migrator.buildFallback(
            type = ConstraintType.TIME_CAP,
            description = "使用时长限制",
            timeScope = TimeScope.PER_CONTENT
        )

        assertEquals(ConstraintType.TIME_CAP, effective.type)
        assertEquals(TimeScope.PER_CONTENT.name, effective.evaluationScope)
        assertEquals("not_applicable", effective.aggregatePagePolicy)
    }
}
