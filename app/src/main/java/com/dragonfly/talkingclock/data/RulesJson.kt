package com.dragonfly.talkingclock.data

import org.json.JSONArray
import org.json.JSONObject

/** Serializes rules into the JSON string stored in DataStore. */
internal fun serializeRules(rules: List<ScheduleRule>): String {
    val arr = JSONArray()
    for (r in rules) {
        arr.put(
            JSONObject()
                .put("id", r.id)
                .put("enabled", r.enabled)
                .put("start", r.startMinuteOfDay)
                .put("end", r.endMinuteOfDay)
                .put("interval", r.intervalMinutes)
                .put("silent", r.silent)
        )
    }
    return arr.toString()
}

/** Parses rules from the DataStore JSON; malformed input yields an empty list. */
internal fun parseRules(json: String): List<ScheduleRule> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        ScheduleRule(
            id = o.getLong("id"),
            enabled = o.optBoolean("enabled", true),
            startMinuteOfDay = o.getInt("start"),
            endMinuteOfDay = o.getInt("end"),
            intervalMinutes = o.optInt("interval", 5).coerceIn(1, 24 * 60),
            silent = o.optBoolean("silent", false),
        )
    }
}.getOrDefault(emptyList())
