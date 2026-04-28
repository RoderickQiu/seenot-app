package com.seenot.app.config

import android.content.Context
import android.content.SharedPreferences
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.domain.SessionConstraint

object InterventionLevelPrefs {

    private const val PREFS_NAME = "seenot_intervention_level"
    private const val KEY_USE_FIXED_LEVEL = "use_fixed_level"
    private const val KEY_FIXED_LEVEL = "fixed_level"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isFixedLevelEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_USE_FIXED_LEVEL, false)
    }

    fun setFixedLevelEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_USE_FIXED_LEVEL, enabled).apply()
    }

    fun getFixedLevel(context: Context): InterventionLevel {
        val raw = getPrefs(context).getString(KEY_FIXED_LEVEL, InterventionLevel.MODERATE.name)
        return runCatching { InterventionLevel.valueOf(raw ?: InterventionLevel.MODERATE.name) }
            .getOrDefault(InterventionLevel.MODERATE)
    }

    fun setFixedLevel(context: Context, level: InterventionLevel) {
        getPrefs(context).edit().putString(KEY_FIXED_LEVEL, level.name).apply()
    }

    fun resolveLevel(context: Context, current: InterventionLevel): InterventionLevel {
        return if (isFixedLevelEnabled(context)) getFixedLevel(context) else current
    }

    fun applyToConstraint(context: Context, constraint: SessionConstraint): SessionConstraint {
        val resolved = resolveLevel(context, constraint.interventionLevel)
        return if (resolved == constraint.interventionLevel) {
            constraint
        } else {
            constraint.copy(interventionLevel = resolved)
        }
    }

    fun applyToConstraints(
        context: Context,
        constraints: List<SessionConstraint>
    ): List<SessionConstraint> {
        if (!isFixedLevelEnabled(context)) return constraints
        val fixedLevel = getFixedLevel(context)
        return constraints.map { constraint ->
            if (constraint.interventionLevel == fixedLevel) {
                constraint
            } else {
                constraint.copy(interventionLevel = fixedLevel)
            }
        }
    }
}
