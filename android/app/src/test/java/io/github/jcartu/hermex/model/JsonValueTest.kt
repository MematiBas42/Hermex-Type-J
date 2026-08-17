package io.github.jcartu.hermex.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonValueTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    private fun decode(raw: String): JSONValue =
        json.decodeFromString(JSONValue.serializer(), raw)

    @Test
    fun `string case`() {
        val value = decode("\"hello\"")
        assertEquals("hello", value.string)
        assertNull(value.number)
        assertNull(value.boolean)
        assertFalse(value.isNull)
        assertFalse(value.isArray)
        assertFalse(value.isObject)
        assertEquals("hello", value.stringValue)
    }

    @Test
    fun `quoted number stays a string like the Swift strict decoder`() {
        val value = decode("\"42\"")
        assertEquals("42", value.string)
        assertNull(value.number)
        assertNull(value.boolean)
    }

    @Test
    fun `number case`() {
        val whole = decode("3")
        assertEquals(3.0, whole.number!!, 0.0)
        assertNull(whole.string)
        assertNull(whole.boolean)
        assertEquals("3", whole.stringValue)

        val fractional = decode("3.5")
        assertEquals(3.5, fractional.number!!, 0.0)
        assertEquals("3.5", fractional.stringValue)
    }

    @Test
    fun `bool case is claimed before number`() {
        val value = decode("true")
        assertEquals(true, value.boolean)
        assertNull(value.number)
        assertNull(value.string)
        assertEquals("true", value.stringValue)
    }

    @Test
    fun `null case`() {
        val value = decode("null")
        assertTrue(value.isNull)
        assertNull(value.string)
        assertNull(value.number)
        assertNull(value.boolean)
        assertNull(value.stringValue)
    }

    @Test
    fun `array case`() {
        val value = decode("[1, \"two\", true]")
        assertTrue(value.isArray)
        assertNull(value.stringValue)
        val items = value.arrayValue!!
        assertEquals(3, items.size)
        assertEquals(1.0, items[0].number!!, 0.0)
        assertEquals("two", items[1].string)
        assertEquals(true, items[2].boolean)
    }

    @Test
    fun `object case with member lookup`() {
        val value = decode("""{"type":"text","text":"hi","count":2}""")
        assertTrue(value.isObject)
        assertNull(value.stringValue)
        assertEquals("text", value["type"]?.stringValue)
        assertEquals("hi", value["text"]?.stringValue)
        assertEquals("2", value["count"]?.stringValue)
        assertNull(value["missing"])
        assertEquals(3, value.objectValue!!.size)
    }

    @Test
    fun `compactJSONString round trips nested structures`() {
        val raw = """{"a":[1,{"b":null}],"c":"x"}"""
        val value = decode(raw)
        assertEquals(raw, value.compactJSONString)
    }

    @Test
    fun `encode round trips the exact element`() {
        val raw = """{"nested":{"list":[1,2.5,"three",false,null]}}"""
        val value = decode(raw)
        assertEquals(raw, json.encodeToString(JSONValue.serializer(), value))
    }

    @Test
    fun `equality is structural`() {
        assertEquals(decode("""{"a":1}"""), decode("""{"a":1}"""))
        assertEquals(JSONValue.string("x"), decode("\"x\""))
        assertEquals(JSONValue.NULL, decode("null"))
    }

    @Test
    fun `lossy scalar helpers coerce like the Swift KeyedDecodingContainer extension`() {
        val obj = json.parseToJsonElement(
            """
            {
              "s_from_int": 7,
              "s_from_double": 1.5,
              "s_from_bool": true,
              "d_from_string": " 2.75 ",
              "i_from_double": 3.9,
              "i_from_string": " 42 ",
              "i_overflow": 99999999999999999999,
              "b_from_int": 1,
              "b_from_string": "Yes",
              "b_bad": "maybe",
              "arr": [1]
            }
            """.trimIndent(),
        ).let { it as kotlinx.serialization.json.JsonObject }

        assertEquals("7", obj.lossyString("s_from_int"))
        assertEquals("1.5", obj.lossyString("s_from_double"))
        assertEquals("true", obj.lossyString("s_from_bool"))
        assertEquals(2.75, obj.lossyDouble("d_from_string")!!, 0.0)
        assertEquals(3, obj.lossyInt("i_from_double"))
        assertEquals(42, obj.lossyInt("i_from_string"))
        assertNull(obj.lossyInt("i_overflow"))
        assertEquals(true, obj.lossyBool("b_from_int"))
        assertEquals(true, obj.lossyBool("b_from_string"))
        assertNull(obj.lossyBool("b_bad"))
        assertNull(obj.lossyString("arr"))
        // Result-chaining: an uncoercible first key falls through to the second.
        assertEquals("7", obj.lossyString("arr", "s_from_int"))
    }
}
