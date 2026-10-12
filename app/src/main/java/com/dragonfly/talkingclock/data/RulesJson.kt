package com.dragonfly.talkingclock.data

import org.json.JSONArray
import org.json.JSONObject

/** Serializes rules into the JSON string stored in DataStore. */
internal fun serializeRules(rules: List<ScheduleRule>): String {
    val arr = JSONArray()
    for (r in rules) {
        arr.put(
            JSONObject().apply {
                put("id", r.id)
                put("enabled", r.enabled)
                put("start", r.startMinuteOfDay)
                put("end", r.endMinuteOfDay)
                put("interval", r.intervalMinutes)
                put("silent", r.silent)
                put("oneShot", r.oneShot)
                if (r.oneShotAnchorMinute > 0L) put("oneShotAnchor", r.oneShotAnchorMinute)
                // ИИ-комментарий: groupId пишем только для нестандартных групп (>0), чтобы legacy-JSON
                // оставался компактным; отсутствие ключа при парсинге = Стандартная (0).
                if (r.groupId != STANDARD_GROUP_ID) put("groupId", r.groupId)
            }
        )
    }
    return arr.toString()
}

/**
 * ИИ-комментарий: сериализация именованных групп правил в JSON для DataStore
 * (`rule_groups_json`). Стандартная группа (id=0) сюда никогда не пишется — она виртуальная.
 */
internal fun serializeGroups(groups: List<RuleGroup>): String {
    val arr = JSONArray()
    for (g in groups) {
        if (g.id == STANDARD_GROUP_ID) continue
        arr.put(
            JSONObject().apply {
                put("id", g.id)
                put("name", g.name)
            }
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
            // Legacy rules stored before the one-shot feature must keep repeating forever.
            oneShot = o.optBoolean("oneShot", false),
            oneShotAnchorMinute = o.optLong("oneShotAnchor", 0L),
            // ИИ-комментарий: legacy-JSON без ключа groupId → Стандартная (0), без миграции.
            groupId = o.optLong("groupId", STANDARD_GROUP_ID),
        )
    }
}.getOrDefault(emptyList())

/**
 * ИИ-комментарий: парсинг групп из DataStore JSON; битый ввод → пустой список
 * (остаётся только виртуальная Стандартная). Пустые имена отбрасываются.
 */
internal fun parseGroups(json: String): List<RuleGroup> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        val id = o.getLong("id")
        if (id == STANDARD_GROUP_ID) return@mapNotNull null
        val name = o.optString("name", "").trim()
        if (name.isEmpty()) return@mapNotNull null
        RuleGroup(id = id, name = name)
    }
}.getOrDefault(emptyList())
