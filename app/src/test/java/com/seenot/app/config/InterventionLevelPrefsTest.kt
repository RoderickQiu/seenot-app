package com.seenot.app.config

import androidx.test.core.app.ApplicationProvider
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.domain.SessionConstraint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InterventionLevelPrefsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearPrefs() {
        context.getSharedPreferences("seenot_intervention_level", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun fixedLevelDisabledByDefault() {
        assertFalse(InterventionLevelPrefs.isFixedLevelEnabled(context))
        assertEquals(InterventionLevel.MODERATE, InterventionLevelPrefs.getFixedLevel(context))
    }

    @Test
    fun applyToConstraintsOverridesAllLevelsWhenEnabled() {
        InterventionLevelPrefs.setFixedLevel(context, InterventionLevel.STRICT)
        InterventionLevelPrefs.setFixedLevelEnabled(context, true)

        val constraints = listOf(
            SessionConstraint(id = "1", type = ConstraintType.DENY, description = "foo", interventionLevel = InterventionLevel.GENTLE),
            SessionConstraint(id = "2", type = ConstraintType.TIME_CAP, description = "bar", interventionLevel = InterventionLevel.MODERATE)
        )

        val overridden = InterventionLevelPrefs.applyToConstraints(context, constraints)

        assertTrue(overridden.all { it.interventionLevel == InterventionLevel.STRICT })
    }
}
