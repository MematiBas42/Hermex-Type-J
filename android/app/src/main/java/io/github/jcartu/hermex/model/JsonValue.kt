package io.github.jcartu.hermex.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Port of `JSONValue.swift` — an arbitrary JSON value.
 *
 * Swift models this as an enum over `.string/.number/.bool/.object/.array/.null`;
 * on Android we wrap the raw [JsonElement] (which already preserves the exact
 * wire token) and expose the same case-inspection surface as accessor
 * properties. Encoding round-trips the element verbatim.
 */
@Serializable(with = JSONValueSerializer::class)
data class JSONValue(val element: JsonElement) {

    private val primitive: JsonPrimitive?
        get() = element as? JsonPrimitive

    /** `case .null` — true when the underlying value is JSON `null`. */
    val isNull: Boolean
        get() = element is JsonNull

    /** `case .string(let value)` — the string when this is a JSON string, else null. */
    val string: String?
        get() = primitive?.takeIf { it !is JsonNull && it.isString }?.content

    /**
     * `case .bool(let value)` — the boolean when this is a JSON `true`/`false`
     * token, else null. Mirrors Swift decode order: booleans are claimed before
     * numbers, and quoted `"true"` stays a string.
     */
    val boolean: Boolean?
        get() = primitive?.takeIf { it !is JsonNull && !it.isString }?.booleanOrNull

    /**
     * `case .number(let value)` — the number when this is a JSON number token,
     * else null. Quoted numbers stay strings, mirroring Swift's strict decoder.
     */
    val number: Double?
        get() = primitive
            ?.takeIf { it !is JsonNull && !it.isString && it.booleanOrNull == null }
            ?.doubleOrNull

    val isArray: Boolean
        get() = element is JsonArray

    val isObject: Boolean
        get() = element is JsonObject

    /** `case .array(let values)` — the elements when this is a JSON array, else null. */
    val arrayValue: List<JSONValue>?
        get() = (element as? JsonArray)?.map { JSONValue(it) }

    /** `case .object(let dict)` — the fields when this is a JSON object, else null. */
    val objectValue: Map<String, JSONValue>?
        get() = (element as? JsonObject)?.mapValues { JSONValue(it.value) }

    /** `object["key"]` convenience: member lookup when this is an object. */
    operator fun get(key: String): JSONValue? =
        (element as? JsonObject)?.get(key)?.let { JSONValue(it) }

    /**
     * Mirrors the private `JSONValue.stringValue` extensions used by
     * `ChatMessage.swift` / `ToolCall.swift`: strings verbatim, numbers and
     * booleans rendered as text, containers and null yield nil. Whole numbers
     * render without a decimal point (Swift's `formatted()` behavior).
     */
    val stringValue: String?
        get() {
            string?.let { return it }
            boolean?.let { return if (it) "true" else "false" }
            number?.let { value ->
                val whole = value.toLong()
                return if (value.isFinite() && whole.toDouble() == value) {
                    whole.toString()
                } else {
                    value.toString()
                }
            }
            return null
        }

    /**
     * Mirrors the private `compactJSONString` extension in `ChatMessage.swift`:
     * the value re-encoded as compact JSON.
     */
    val compactJSONString: String
        get() = element.toString()

    companion object {
        val NULL: JSONValue = JSONValue(JsonNull)

        fun string(value: String): JSONValue = JSONValue(JsonPrimitive(value))

        fun number(value: Double): JSONValue = JSONValue(JsonPrimitive(value))

        fun bool(value: Boolean): JSONValue = JSONValue(JsonPrimitive(value))

        fun array(values: List<JSONValue>): JSONValue =
            JSONValue(JsonArray(values.map { it.element }))

        fun objectOf(values: Map<String, JSONValue>): JSONValue =
            JSONValue(JsonObject(values.mapValues { it.value.element }))
    }
}

object JSONValueSerializer : KSerializer<JSONValue> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): JSONValue =
        JSONValue(JsonElement.serializer().deserialize(decoder))

    override fun serialize(encoder: Encoder, value: JSONValue) =
        JsonElement.serializer().serialize(encoder, value.element)
}

/**
 * Base for serializers porting Swift custom `init(from:)` logic: the whole
 * value is materialized as a [JsonObject] first, then fields are read
 * tolerantly (alternate keys, lossy scalar coercion) exactly like the Swift
 * `KeyedDecodingContainer` code. A non-object payload throws, matching
 * `decoder.container(keyedBy:)`.
 */
internal abstract class TolerantObjectSerializer<T>(serialName: String) : KSerializer<T> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(serialName)

    abstract fun fromJson(json: Json, obj: JsonObject): T

    abstract fun toJson(json: Json, value: T): JsonObject

    override fun deserialize(decoder: Decoder): T {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("${descriptor.serialName} supports JSON decoding only")
        val element = input.decodeJsonElement()
        val obj = element as? JsonObject
            ?: throw SerializationException("Expected JSON object for ${descriptor.serialName}")
        return fromJson(input.json, obj)
    }

    override fun serialize(encoder: Encoder, value: T) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("${descriptor.serialName} supports JSON encoding only")
        output.encodeJsonElement(toJson(output.json, value))
    }
}
