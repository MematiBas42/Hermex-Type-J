package io.github.jcartu.hermex.model.hermes

import io.github.jcartu.hermex.model.JSONValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Port of `BotJSON` (`Networking/Bots/BotProtocol.swift`): direct Hermes payloads, kept
 * apart from the webui endpoint models. Accessors tolerate absent and future fields;
 * required capabilities are checked at use. `get(key)` on anything but an object, or for a
 * missing member, is [NULL], exactly like the Swift subscript, so chains such as
 * `status["components"]["platforms"]["connected"].integer` never throw.
 *
 * The raw [element] is kept so a value re-encodes verbatim.
 */
@JvmInline
value class BotJSON(val element: JsonElement) {

    /** `subscript(_ key:)` — the member, or [NULL] when this isn't an object or lacks it. */
    operator fun get(key: String): BotJSON =
        BotJSON((element as? JsonObject)?.get(key) ?: JsonNull)

    /** `.text` — the string when this is a JSON string. */
    val text: String?
        get() = (element as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    /** `.list` — the elements when this is a JSON array. */
    val list: List<BotJSON>?
        get() = (element as? JsonArray)?.map { BotJSON(it) }

    /** `.fields` — the members when this is a JSON object. */
    val fields: Map<String, BotJSON>?
        get() = (element as? JsonObject)?.mapValues { BotJSON(it.value) }

    /** `.flag` — the boolean when this is a JSON `true`/`false` token. */
    val flag: Boolean?
        get() = (element as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.booleanOrNull

    /** `.number` — the number when this is a JSON number token. */
    val number: Double?
        get() = (element as? JsonPrimitive)
            ?.takeIf { it !is JsonNull && !it.isString && it.booleanOrNull == null }
            ?.doubleOrNull

    /** `.integer` — `Int(exactly:)` of the number: a whole value in `Int` range. */
    val integer: Int?
        get() {
            val primitive = (element as? JsonPrimitive)
                ?.takeIf { it !is JsonNull && !it.isString && it.booleanOrNull == null } ?: return null
            primitive.longOrNull?.let { long ->
                return if (long in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) long.toInt() else null
            }
            val value = primitive.doubleOrNull ?: return null
            if (!value.isFinite()) return null
            val truncated = kotlin.math.truncate(value)
            if (truncated != value) return null
            if (truncated < Int.MIN_VALUE.toDouble() || truncated > Int.MAX_VALUE.toDouble()) return null
            return truncated.toInt()
        }

    /** `== .null` */
    val isNull: Boolean
        get() = element is JsonNull

    val isObject: Boolean
        get() = element is JsonObject

    /** The shared transcript model's JSON type (`BotJSON.jsonValue`). */
    val jsonValue: JSONValue
        get() = JSONValue(element)

    /** `argumentDictionary` — tool arguments as the shared transcript model's JSON type. */
    val argumentDictionary: Map<String, JSONValue>?
        get() = (element as? JsonObject)?.takeIf { it.isNotEmpty() }?.mapValues { JSONValue(it.value) }

    /**
     * `toolResultPreview` — a tool result as the text the shared tool row formatter parses:
     * a string as is, any other JSON re-encoded so envelope fields such as `error` are read.
     */
    val toolResultPreview: String?
        get() = when {
            element is JsonNull -> null
            text != null -> text?.takeIf { it.isNotEmpty() }
            else -> element.toString()
        }

    /** The compact wire text of this value. */
    val encoded: String
        get() = element.toString()

    override fun toString(): String = element.toString()

    companion object {
        val NULL: BotJSON = BotJSON(JsonNull)

        fun string(value: String): BotJSON = BotJSON(JsonPrimitive(value))

        fun number(value: Double): BotJSON = BotJSON(JsonPrimitive(value))

        fun number(value: Int): BotJSON = BotJSON(JsonPrimitive(value))

        fun bool(value: Boolean): BotJSON = BotJSON(JsonPrimitive(value))

        fun array(values: List<BotJSON>): BotJSON = BotJSON(JsonArray(values.map { it.element }))

        fun objectOf(values: Map<String, BotJSON>): BotJSON =
            BotJSON(JsonObject(values.mapValues { it.value.element }))

        fun objectOf(vararg values: Pair<String, BotJSON>): BotJSON = objectOf(values.toMap())

        /**
         * `JSONDecoder().decode(BotJSON.self, from:)` — any JSON text, or a
         * [SerializationException] when the bytes aren't JSON.
         */
        fun parse(text: String): BotJSON = BotJSON(Json.parseToJsonElement(text))

        fun parse(bytes: ByteArray): BotJSON = parse(bytes.toString(Charsets.UTF_8))

        /** `try? JSONDecoder().decode(BotJSON.self, from:)` — null when the bytes aren't JSON. */
        fun parseOrNull(bytes: ByteArray): BotJSON? =
            runCatching { parse(bytes) }.getOrNull()

        fun parseOrNull(text: String): BotJSON? =
            runCatching { parse(text) }.getOrNull()
    }
}

/** Builds a JSON object from `key to value` pairs, dropping null values. */
internal fun botObject(vararg values: Pair<String, BotJSON?>): BotJSON =
    BotJSON.objectOf(values.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())
