package com.roderickqiu.seenot.data

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

/** Represents a monitoring rule with condition and action */
data class Rule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val condition: RuleCondition, 
    val action: RuleAction,
    val timeConstraint: TimeConstraint? = null // Optional time constraint
)

/** Represents the condition part of a rule */
data class RuleCondition(
    val type: ConditionType,
    val timeInterval: Int? = null, // in minutes, null if not applicable
    val parameter: String? = null // app-specific parameter for ON_PAGE and ON_CONTENT
)

/** Represents the action part of a rule */
data class RuleAction(
    val type: ActionType,
    val parameter: String? = null // app-specific parameter for actions that need it
)

/** Represents optional time constraint for when to trigger the action */
sealed class TimeConstraint {
    /** Trigger after continuously matching condition for X minutes */
    data class Continuous(val minutes: Double) : TimeConstraint()
    
    /** Trigger after total time matching condition reaches X minutes today */
    data class DailyTotal(val minutes: Double) : TimeConstraint()
    
    /** Trigger after total time matching condition reaches X minutes in the last Y hours */
    data class RecentTotal(val hours: Int, val minutes: Double) : TimeConstraint()
}

/** Types of conditions */
enum class ConditionType {
    TIME_INTERVAL, // every X minutes
    ON_ENTER, // every time enter
    ON_PAGE, // if on specific page
    ON_CONTENT // if content about specific topic
}

/** Types of actions */
enum class ActionType {
    REMIND,
    AUTO_CLICK,
    AUTO_SCROLL_UP,
    AUTO_SCROLL_DOWN,
    AUTO_BACK,
    ASK
}

/**
 * Custom Gson adapter for TimeConstraint sealed class
 */
class TimeConstraintAdapter : JsonSerializer<TimeConstraint>, JsonDeserializer<TimeConstraint> {
    override fun serialize(
        src: TimeConstraint?,
        typeOfSrc: Type?,
        context: JsonSerializationContext?
    ): JsonElement {
        if (src == null) {
            return JsonNull.INSTANCE
        }
        val jsonObject = JsonObject()
        when (src) {
            is TimeConstraint.Continuous -> {
                jsonObject.addProperty("type", "Continuous")
                jsonObject.addProperty("minutes", src.minutes)
            }
            is TimeConstraint.DailyTotal -> {
                jsonObject.addProperty("type", "DailyTotal")
                jsonObject.addProperty("minutes", src.minutes)
            }
            is TimeConstraint.RecentTotal -> {
                jsonObject.addProperty("type", "RecentTotal")
                jsonObject.addProperty("hours", src.hours)
                jsonObject.addProperty("minutes", src.minutes)
            }
        }
        return jsonObject
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): TimeConstraint? {
        if (json == null || json.isJsonNull || !json.isJsonObject) {
            return null
        }
        val jsonObject = json.asJsonObject
        val type = jsonObject.get("type")?.asString ?: return null
        
        return when (type) {
            "Continuous" -> {
                val minutes = jsonObject.get("minutes")?.asDouble
                    ?: throw JsonParseException("Missing 'minutes' field for Continuous TimeConstraint")
                TimeConstraint.Continuous(minutes)
            }
            "DailyTotal" -> {
                val minutes = jsonObject.get("minutes")?.asDouble
                    ?: throw JsonParseException("Missing 'minutes' field for DailyTotal TimeConstraint")
                TimeConstraint.DailyTotal(minutes)
            }
            "RecentTotal" -> {
                val hours = jsonObject.get("hours")?.asInt
                    ?: throw JsonParseException("Missing 'hours' field for RecentTotal TimeConstraint")
                val minutes = jsonObject.get("minutes")?.asDouble
                    ?: throw JsonParseException("Missing 'minutes' field for RecentTotal TimeConstraint")
                TimeConstraint.RecentTotal(hours, minutes)
            }
            else -> throw JsonParseException("Unknown TimeConstraint type: $type")
        }
    }
}
