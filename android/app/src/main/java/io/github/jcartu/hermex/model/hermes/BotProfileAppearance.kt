package io.github.jcartu.hermex.model.hermes

/**
 * Port of `BotProfileAppearance` (`Features/Bots/BotProfileAppearance.swift`): the static Bot
 * Mode presentation shared with Hermes Desktop. The raw `hermes-bots` look object remains the
 * source of truth; this projection only edits the keys Hermex owns on the Profile screen.
 */
data class BotProfileAppearance(
    val title: String,
    val shape: String? = null,
    val color: String? = null,
    val custom: Boolean = false,
    val imageKind: String? = null,
    /** A [BotAvatarExpression] raw value. Hermex-owned: Desktop keeps the key but renders its own eyes. */
    val expression: String? = null,
) {
    /**
     * `merging(into:)` — applies only the compatible static appearance fields while retaining
     * Desktop-owned organization, groups, timestamps and future fields.
     */
    fun merging(received: Map<String, BotJSON>): Map<String, BotJSON> {
        val result = LinkedHashMap(received)
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) result.remove("title") else result["title"] = BotJSON.string(trimmedTitle)
        if (expression != null) result["expression"] = BotJSON.string(expression) else result.remove("expression")
        if (custom) {
            result["custom"] = BotJSON.bool(true)
            if (shape != null) result["shape"] = BotJSON.string(shape) else result.remove("shape")
            if (color != null) result["color"] = BotJSON.string(color) else result.remove("color")
            if (imageKind != null) result["imageKind"] = BotJSON.string(imageKind) else result.remove("imageKind")
        } else {
            for (key in listOf("custom", "shape", "color", "imageKind")) result.remove(key)
        }
        return result
    }

    companion object {
        /** `init(profile:)` */
        fun of(profile: BotProfile): BotProfileAppearance = fromLook(profile.look, profile.name)

        /**
         * `init(look:fallbackTitle:)` — reads a look object directly, so the editor can rebuild its
         * appearance from the look it last saved instead of the roster row it was opened with.
         */
        fun fromLook(look: Map<String, BotJSON>, fallbackTitle: String): BotProfileAppearance {
            val storedTitle = look["title"]?.text?.trim().orEmpty()
            val shape = look["shape"]?.text
            val color = look["color"]?.text
            return BotProfileAppearance(
                title = storedTitle.ifEmpty { fallbackTitle },
                shape = shape,
                color = color,
                custom = look["custom"]?.flag == true || shape != null || color != null,
                imageKind = look["imageKind"]?.text,
                expression = look["expression"]?.text,
            )
        }
    }
}

/**
 * Port of `BotAvatarColor`: the swatches the editor and the create sheet offer for a drawn
 * face. The first, stored as Desktop's `#ffffff`, is the adaptive one: white on a dark screen
 * and black on a light one, so it never disappears into the background.
 */
data class BotAvatarColor(val hex: String, val name: String) {
    companion object {
        const val ADAPTIVE_HEX = "#ffffff"

        val palette: List<BotAvatarColor> = listOf(
            BotAvatarColor(ADAPTIVE_HEX, "Auto"), BotAvatarColor("#a9703d", "Brown"),
            BotAvatarColor("#ef4444", "Red"), BotAvatarColor("#f97316", "Orange"),
            BotAvatarColor("#f59e0b", "Amber"), BotAvatarColor("#22c55e", "Green"),
            BotAvatarColor("#14b8a6", "Teal"), BotAvatarColor("#38bdf8", "Blue"),
            BotAvatarColor("#8b5cf6", "Purple"), BotAvatarColor("#ec4899", "Pink"),
            BotAvatarColor("#8e8e93", "Gray"),
        )
    }
}

/** Port of `BotAvatarShape`: Desktop's classic shape vocabulary. */
enum class BotAvatarShape(val rawValue: String, val localizedName: String) {
    CIRCLE("circle", "Circle"),
    BLOB("blob", "Blob"),
    SQUIRCLE("squircle", "Squircle"),
    PILL("pill", "Pill"),
    TRIANGLE("triangle", "Triangle"),
    HEXAGON("hexagon", "Hexagon"),
    CLOUD("cloud", "Cloud"),
    DROP("drop", "Drop"),
    ;

    companion object {
        fun fromRawValue(rawValue: String?): BotAvatarShape? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/**
 * Port of `BotAvatarExpression`: the rest expression of a drawn face — eye size, lean and
 * spacing, after Bloub's measured catalogue (MIT, jeremy-prt/bloub). Values are in Bloub's
 * units of body radius and degrees; the mark scales them onto itself.
 */
enum class BotAvatarExpression(val rawValue: String, val localizedName: String) {
    NEUTRAL("neutral", "Neutral"),
    ATTENTIVE("attentive", "Attentive"),
    SURPRISED("surprised", "Surprised"),
    EXCITED("excited", "Excited"),
    HAPPY("happy", "Happy"),
    LAUGHING("laughing", "Laughing"),
    ANGRY("angry", "Angry"),
    SAD("sad", "Sad"),
    SCARED("scared", "Scared"),
    WARY("wary", "Wary"),
    CONFUSED("confused", "Confused"),
    CURIOUS("curious", "Curious"),
    PROUD("proud", "Proud"),
    SHY("shy", "Shy"),
    BORED("bored", "Bored"),
    SLEEPY("sleepy", "Sleepy"),
    ;

    data class Eye(val width: Double, val height: Double, val tilt: Double)
    data class Geometry(val left: Eye, val right: Eye, val split: Double)

    val geometry: Geometry
        get() {
            fun pair(w: Double, h: Double, tilt: Double = 0.0, split: Double) =
                Geometry(Eye(w, h, tilt), Eye(w, h, -tilt), split)
            return when (this) {
                NEUTRAL -> pair(0.186, 0.412, split = 15.46)
                ATTENTIVE -> pair(0.21, 0.44, split = 16.0)
                SURPRISED -> pair(0.45, 0.47, split = 19.0)
                EXCITED -> pair(0.4, 0.56, -10.0, split = 19.5)
                HAPPY -> pair(0.27, 0.17, 14.0, split = 17.0)
                LAUGHING -> pair(0.34, 0.13, 20.0, split = 18.0)
                ANGRY -> pair(0.34, 0.15, 30.0, split = 17.0)
                SAD -> pair(0.22, 0.4, -28.0, split = 16.0)
                SCARED -> pair(0.4, 0.6, split = 20.5)
                WARY -> Geometry(Eye(0.21, 0.4, 0.0), Eye(0.22, 0.15, 0.0), 16.0)
                CONFUSED -> Geometry(Eye(0.2, 0.44, -18.0), Eye(0.28, 0.17, 14.0), 16.5)
                CURIOUS -> Geometry(Eye(0.24, 0.46, -8.0), Eye(0.2, 0.38, -8.0), 16.5)
                PROUD -> pair(0.3, 0.15, 18.0, split = 17.0)
                SHY -> pair(0.17, 0.3, split = 14.0)
                BORED -> pair(0.3, 0.12, split = 16.0)
                SLEEPY -> pair(0.2, 0.42 * 0.42, split = 16.0)
            }
        }

    companion object {
        /** `resolve(_:)` — an unknown or missing stored value reads as neutral rather than failing the row. */
        fun resolve(raw: String?): BotAvatarExpression = entries.firstOrNull { it.rawValue == raw } ?: NEUTRAL
    }
}

/**
 * Port of `BotTitleFace`: what a bot's chat title face shows for the current turn (#757).
 * Waiting and failed override the pinned expression, since a pin is only the resting face;
 * every other surface keeps the pin. Only [WORKING] moves beyond a blink.
 */
enum class BotTitleFace {
    RESTING, WORKING, WAITING, FAILED;

    /** The eyes that replace the pinned expression, or null to keep it. */
    val expression: BotAvatarExpression?
        get() = when (this) {
            WAITING -> BotAvatarExpression.CURIOUS
            FAILED -> BotAvatarExpression.SAD
            RESTING, WORKING -> null
        }

    /** What accessibility adds after the bot's name; null when the face shows no state. */
    val accessibilityValue: String?
        get() = when (this) {
            WAITING -> "Needs attention"
            FAILED -> "Turn failed"
            RESTING, WORKING -> null
        }
}

/**
 * Port of the `Color.botBody`/`botEyes`/`init?(botHex:)` helpers and `BotAvatarPresentation`
 * (`BotProfileAppearance.swift`), as pure colour arithmetic over ARGB ints so the drawing
 * layer and the tests share one rule.
 */
object BotAvatarColors {
    private val FALLBACK_BODY_HEXES = listOf("#8b5cf6", "#38bdf8", "#14b8a6", "#22c55e", "#f59e0b", "#f97316", "#ef4444", "#ec4899")
    private const val DEFAULT_BOT_HEX = "#8b5cf6"
    private val PURPLE = 0xFF800080.toInt()

    /** `Color(botHex:)` — opaque ARGB for a `#rrggbb` string, or null for anything else. */
    fun argb(botHex: String): Int? {
        val value = botHex.trim()
        if (value.length != 7 || value[0] != '#') return null
        val rgb = value.substring(1).toLongOrNull(16) ?: return null
        return (0xFF000000L or rgb).toInt()
    }

    /** `botHexIsDark` — perceived luminance under 110 of 255. */
    fun isDark(botHex: String): Boolean {
        val value = botHex.trim()
        if (value.length != 7 || value[0] != '#') return false
        val rgb = value.substring(1).toLongOrNull(16) ?: return false
        val red = ((rgb shr 16) and 0xff).toDouble()
        val green = ((rgb shr 8) and 0xff).toDouble()
        val blue = (rgb and 0xff).toDouble()
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue < 110
    }

    /** `Color.botBody(hex)` — Desktop's white is drawn adaptively: white in dark appearance, black in light. */
    fun body(hex: String, isDarkAppearance: Boolean): Int {
        if (hex.lowercase() == BotAvatarColor.ADAPTIVE_HEX) return if (isDarkAppearance) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        return argb(hex) ?: PURPLE
    }

    /** `Color.botEyes(on:)` — eyes that contrast with [body] in both appearances. */
    fun eyes(hex: String, isDarkAppearance: Boolean): Int {
        if (hex.lowercase() == BotAvatarColor.ADAPTIVE_HEX) {
            return if (isDarkAppearance) withAlpha(0xFF000000.toInt(), 0.85) else withAlpha(0xFFFFFFFF.toInt(), 0.9)
        }
        return if (isDark(hex)) withAlpha(0xFFFFFFFF.toInt(), 0.9) else withAlpha(0xFF000000.toInt(), 0.85)
    }

    /** Port of the Swift FNV-1a seed shared by `BotAvatarPresentation`, `BotBlinkSchedule` and `BotPlayfulSchedule`. */
    fun seed(name: String): ULong {
        var hash = 1_469_598_103_934_665_603UL
        for (byte in name.toByteArray(Charsets.UTF_8)) {
            hash = (hash xor (byte.toInt() and 0xff).toULong()) * 1_099_511_628_211UL
        }
        return hash
    }

    /**
     * Port of `BotAvatarPresentation`: the shape and body colour a face draws with. The default
     * Profile without a custom look is the purple squircle Desktop draws; any other bot without
     * a stored shape or colour takes one seeded by its Profile name.
     */
    fun presentation(name: String, appearance: BotProfileAppearance): Presentation {
        if (name.lowercase() == "default" && !appearance.custom) {
            return Presentation(BotAvatarShape.SQUIRCLE, DEFAULT_BOT_HEX)
        }
        val hash = seed(name)
        val shapes = BotAvatarShape.entries.filter { it != BotAvatarShape.BLOB }
        val shape = BotAvatarShape.fromRawValue(appearance.shape) ?: shapes[(hash % shapes.size.toULong()).toInt()]
        val hex = appearance.color ?: FALLBACK_BODY_HEXES[(hash % FALLBACK_BODY_HEXES.size.toULong()).toInt()]
        return Presentation(shape, hex)
    }

    /** The resolved shape and body hex of one drawn face. */
    data class Presentation(val shape: BotAvatarShape, val bodyHex: String) {
        fun body(isDarkAppearance: Boolean): Int = BotAvatarColors.body(bodyHex, isDarkAppearance)
        fun eyes(isDarkAppearance: Boolean): Int = BotAvatarColors.eyes(bodyHex, isDarkAppearance)
    }

    private fun withAlpha(argb: Int, alpha: Double): Int =
        (argb and 0x00FFFFFF) or (((alpha * 255).toInt().coerceIn(0, 255)) shl 24)
}
