package io.github.jcartu.hermex.ui.feature.bots

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The phone-local key/value file the Bot inbox stores its device-only marks in: the
 * Android stand-in for Swift's `UserDefaults.standard`. Values never leave the phone and
 * Desktop has no matching state to sync. Tolerant: an unreadable value reads as absent.
 */
interface BotLocalDefaults {
    fun string(key: String): String?
    fun put(key: String, value: String?)

    /** A `SharedPreferences`-backed file, `bot_inbox_defaults`, private to the app. */
    class Preferences(private val preferences: SharedPreferences) : BotLocalDefaults {
        constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

        override fun string(key: String): String? = runCatching { preferences.getString(key, null) }.getOrNull()

        override fun put(key: String, value: String?) {
            preferences.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }

        companion object {
            const val FILE = "bot_inbox_defaults"
        }
    }

    /** Tests and previews. */
    class InMemory : BotLocalDefaults {
        val values = LinkedHashMap<String, String>()
        override fun string(key: String): String? = values[key]
        override fun put(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }
}

/**
 * Port of `BotUnreadStore` (`Features/Bots/BotInbox.swift`): the canonical `last_active` the
 * user last saw per bot, keyed by connection UUID so equal Profile names on two hosts never
 * share a mark.
 */
class BotUnreadStore(private val defaults: BotLocalDefaults) {
    private fun key(connectionID: String) = "bot-inbox-seen.$connectionID"

    fun load(connectionID: String): Map<String, Double> {
        val raw = defaults.string(key(connectionID)) ?: return emptyMap()
        val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyMap()
        return obj.mapNotNull { (profile, value) -> (value as? JsonPrimitive)?.doubleOrNull?.let { profile to it } }.toMap()
    }

    fun save(seen: Map<String, Double>, connectionID: String) {
        defaults.put(key(connectionID), JsonObject(seen.mapValues { JsonPrimitive(it.value) }).toString())
    }

    fun remove(connectionID: String) = defaults.put(key(connectionID), null)
}

/**
 * Port of `BotSectionOrderStore`: the phone-local order of Desktop's named sections, keyed by
 * configured server and connection UUID. Desktop's own order lives in its plugin storage and is
 * never read or written from here.
 */
class BotSectionOrderStore(private val defaults: BotLocalDefaults) {
    private fun key(server: String, connectionID: String) = "bot-inbox-section-order.$connectionID|$server"

    fun load(server: String, connectionID: String): List<String> {
        val raw = defaults.string(key(server, connectionID)) ?: return emptyList()
        return runCatching { Json.parseToJsonElement(raw).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }
            .getOrDefault(emptyList())
    }

    fun save(ids: List<String>, server: String, connectionID: String) {
        if (ids.isEmpty()) remove(server, connectionID)
        else defaults.put(key(server, connectionID), JsonArray(ids.map(::JsonPrimitive)).toString())
    }

    fun remove(server: String, connectionID: String) = defaults.put(key(server, connectionID), null)
}

/**
 * Port of `BotRoomOrganizeStore`: phone-local pin and hide marks for group rooms. The host
 * keeps no such state for rooms, so a pin made here never shows in Desktop or on another phone.
 */
class BotRoomOrganizeStore(private val defaults: BotLocalDefaults) {
    data class Flags(val pinned: Set<String> = emptySet(), val hidden: Set<String> = emptySet())

    private fun key(connectionID: String) = "bot-inbox-rooms.$connectionID"

    fun load(connectionID: String): Flags {
        val raw = defaults.string(key(connectionID)) ?: return Flags()
        val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return Flags()
        fun ids(name: String): Set<String> =
            (obj[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet()
        return Flags(pinned = ids("pinned"), hidden = ids("hidden"))
    }

    fun save(flags: Flags, connectionID: String) {
        val value = JsonObject(
            mapOf(
                "pinned" to JsonArray(flags.pinned.sorted().map(::JsonPrimitive)),
                "hidden" to JsonArray(flags.hidden.sorted().map(::JsonPrimitive)),
            ),
        )
        defaults.put(key(connectionID), value.toString())
    }

    fun remove(connectionID: String) = defaults.put(key(connectionID), null)
}
