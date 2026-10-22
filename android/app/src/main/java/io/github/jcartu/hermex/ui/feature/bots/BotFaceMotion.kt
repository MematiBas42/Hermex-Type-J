package io.github.jcartu.hermex.ui.feature.bots

import io.github.jcartu.hermex.model.hermes.BotAvatarColors
import java.util.UUID
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * How a drawn bot face moves. [Still] is one frozen rest frame, [Idle] blinks on a sparse
 * schedule, and [Working] sways for one bounded beat before settling to a still lean that only
 * blinks. Times are epoch seconds.
 */
sealed interface BotFaceMotion {
    data object Still : BotFaceMotion
    data object Idle : BotFaceMotion
    data class Working(val since: Double) : BotFaceMotion

    /** Android's disabled animator scale collapses every mode to a still face. */
    fun honoring(reduceMotion: Boolean): BotFaceMotion = if (reduceMotion) Still else this
}

/**
 * One frame of a face. Gaze and lift are fractions of the mark size, roll is degrees, and lid is
 * the factor applied to the expression's eye height (1 open, 0.06 shut).
 */
data class BotFacePose(
    val gazeX: Double = 0.0,
    val gazeY: Double = 0.0,
    val roll: Double = 0.0,
    val lid: Double = 1.0,
    val lift: Double = 0.0,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
) {
    companion object {
        val Rest = BotFacePose()
        val Blink = BotFacePose(lid = 0.06)

        /** Desktop's bounded work pose (`avatar.tsx` facePose), scaled onto this mark. */
        fun working(at: Double): BotFacePose {
            val turn = -11.0 + sin(at * 0.48) * 8.0
            val tilt = sin(at * 0.42) * 8.0 + sin(at * 1.1) * 1.6
            val roll = sin(at * 0.75) * 4.2
            val blink = at % 1.45 > 1.26
            return BotFacePose(
                gazeX = turn * 0.0025,
                gazeY = -tilt * 0.0025,
                roll = roll,
                lid = if (blink) Blink.lid else 1.0,
            )
        }

        /** The working lean with the continuous sway removed. */
        val SettledWorking = BotFacePose(gazeX = -11.0 * 0.0025)
    }
}

/**
 * Sparse blink timing: shut, then open 180 ms later, every 3–5 seconds. Period and phase are
 * seeded by Profile name so lists do not blink in unison. Consumers schedule only these edges.
 */
data class BotBlinkSchedule(val seed: String) {
    val period: Double
    val phase: Double

    init {
        val hash = BotAvatarColors.seed(seed)
        period = 3.0 + (hash % 2_001UL).toDouble() / 1_000.0
        phase = ((hash shr 20) % 5_000UL).toDouble() / 1_000.0
    }

    fun isShut(at: Double): Boolean {
        val position = positiveRemainder(at - phase, period)
        // The open edge is exactly SHUT_DURATION. Keep floating error from retaining a shut lid.
        return position + EDGE_EPSILON < SHUT_DURATION
    }

    /** The first shut/open edge strictly after [at]. */
    fun nextEdge(after: Double): Double {
        val position = positiveRemainder(after - phase, period)
        return if (position + EDGE_EPSILON < SHUT_DURATION) {
            after + (SHUT_DURATION - position)
        } else {
            after + (period - position)
        }
    }

    /** Finite, allocation-bounded projection used by tests and non-Compose schedulers. */
    fun entries(from: Double, count: Int): List<Double> {
        require(count >= 0) { "count must be non-negative" }
        if (count == 0) return emptyList()
        return buildList(count) {
            var entry = from
            add(entry)
            repeat(count - 1) {
                entry = nextEdge(entry)
                add(entry)
            }
        }
    }

    companion object {
        const val SHUT_DURATION = 0.18
        private const val EDGE_EPSILON = 0.0001

        private fun positiveRemainder(value: Double, modulus: Double): Double {
            val raw = (value % modulus).let { if (it < 0.0) it + modulus else it }
            return if (raw < EDGE_EPSILON || modulus - raw < EDGE_EPSILON) 0.0 else raw
        }
    }
}

/**
 * Fifteen frames per second for [BEAT_SECONDS] from [start], followed only by [blink] edges.
 * Long-running turns therefore have no ongoing frame animation after they settle.
 */
data class BotWorkingSchedule(
    val start: Double,
    val blink: BotBlinkSchedule,
) {
    val end: Double get() = start + BEAT_SECONDS

    fun pose(at: Double): BotFacePose {
        if (at < end) return BotFacePose.working(at)
        return if (blink.isShut(at)) {
            BotFacePose.SettledWorking.copy(lid = BotFacePose.Blink.lid)
        } else {
            BotFacePose.SettledWorking
        }
    }

    fun isContinuouslyAnimating(at: Double): Boolean = at < end

    /** The next required repaint strictly after [after]. */
    fun nextEntry(after: Double): Double {
        if (after >= end) return blink.nextEdge(after)
        if (after < start) return start
        val elapsedFrames = (after - start) / FRAME_INTERVAL
        val nextIndex = floor(elapsedFrames + FRAME_INDEX_EPSILON).toLong() + 1L
        return minOf(start + nextIndex * FRAME_INTERVAL, end)
    }

    /** Finite schedule projection; after the beat this contains only sparse blink edges. */
    fun entries(from: Double, count: Int): List<Double> {
        require(count >= 0) { "count must be non-negative" }
        if (count == 0) return emptyList()
        return buildList(count) {
            var entry = from
            add(entry)
            repeat(count - 1) {
                entry = nextEntry(entry)
                add(entry)
            }
        }
    }

    companion object {
        const val BEAT_SECONDS = 30.0
        const val FRAME_INTERVAL = 1.0 / 15.0
        private const val FRAME_INDEX_EPSILON = 0.000_000_1
    }
}

/**
 * Stateful beat gate for a chat-title face. Unknown reconnect states deliberately preserve the
 * current arming state; they can neither start nor rearm a settled same-turn beat.
 */
class BotWorkingBeat {
    var start: Double = Double.NEGATIVE_INFINITY
        private set

    private var armed = true

    /** Returning to the foreground allows the current running turn to begin a fresh beat. */
    fun rearm() {
        armed = true
    }

    fun observe(turn: BotTurnState, at: Double) {
        when (turn) {
            BotTurnState.RUNNING -> if (armed) {
                start = at
                armed = false
            }
            BotTurnState.UNKNOWN -> Unit
            BotTurnState.IDLE,
            BotTurnState.NEEDS_ATTENTION,
            BotTurnState.INTERRUPTED,
            -> armed = true
        }
    }
}

/** The title face's connection/turn state. UNKNOWN never changes [BotWorkingBeat]'s gate. */
enum class BotTurnState { UNKNOWN, IDLE, RUNNING, NEEDS_ATTENTION, INTERRUPTED }

/** A short, self-ending hero-face action whose first and final poses are exactly [BotFacePose.Rest]. */
enum class BotFaceBit(val duration: Double) {
    GLANCE_LEFT(0.9),
    GLANCE_RIGHT(0.9),
    GLANCE_DOWN(1.6),
    DOUBLE_BLINK(0.5),
    WOBBLE(0.7),
    HOP(0.55),
    SPIN(0.9),
    ;

    fun pose(at: Double): BotFacePose {
        if (at <= 0.0 || at >= 1.0) return BotFacePose.Rest
        val hold = when {
            at < 0.3 -> easeOut(at / 0.3)
            at > 0.7 -> 1.0 - easeOut((at - 0.7) / 0.3)
            else -> 1.0
        }
        return when (this) {
            GLANCE_LEFT -> BotFacePose(gazeX = -0.07 * hold)
            GLANCE_RIGHT -> BotFacePose(gazeX = 0.07 * hold)
            GLANCE_DOWN -> BotFacePose(gazeY = 0.06 * hold)
            DOUBLE_BLINK -> {
                val shut = (at >= 0.05 && at < 0.3) || (at >= 0.45 && at < 0.75)
                BotFacePose(lid = if (shut) BotFacePose.Blink.lid else 1.0)
            }
            WOBBLE -> BotFacePose(roll = 9.0 * sin(at * 2.0 * PI) * (1.0 - at))
            HOP -> {
                val arc = sin(at * PI)
                val squash = when {
                    at < 0.15 -> 1.0 - 0.12 * sin(at / 0.15 * PI)
                    at > 0.85 -> 1.0 - 0.12 * sin((at - 0.85) / 0.15 * PI)
                    else -> 1.0
                }
                BotFacePose(
                    lift = 0.16 * arc,
                    scaleX = 2.0 - squash,
                    scaleY = squash,
                )
            }
            SPIN -> {
                val turn = if (at < 0.5) 2.0 * at * at else 1.0 - (-2.0 * at + 2.0).pow(2.0) / 2.0
                BotFacePose(roll = 360.0 * turn)
            }
        }
    }

    private fun easeOut(value: Double): Double = 1.0 - (1.0 - value).pow(3.0)
}

/**
 * Seeded autonomous hero-face slots. A slot waits 4–9 seconds. GLANCE_DOWN is cue-only and a
 * SPIN occurs only at each tenth non-zero slot, keeping spins at least forty seconds apart.
 */
data class BotPlayfulSchedule(val seed: String) {
    private val value = BotAvatarColors.seed(seed)

    data class Entry(val delay: Double, val bit: BotFaceBit)

    fun entry(index: Int): Entry {
        require(index >= 0) { "index must be non-negative" }
        var state = value xor (index.toULong() * 0x9E37_79B9_7F4A_7C15UL)
        state = state xor (state shr 30)
        state *= 0xBF58_476D_1CE4_E5B9UL
        state = state xor (state shr 27)
        state *= 0x94D0_49BB_1331_11EBUL
        state = state xor (state shr 31)
        val delay = 4.0 + (state % 5_001UL).toDouble() / 1_000.0
        val bit = if (index > 0 && index % 10 == 0) {
            BotFaceBit.SPIN
        } else {
            COMMON_BITS[((state shr 16) % COMMON_BITS.size.toULong()).toInt()]
        }
        return Entry(delay, bit)
    }

    companion object {
        private val COMMON_BITS = arrayOf(
            BotFaceBit.GLANCE_LEFT,
            BotFaceBit.GLANCE_RIGHT,
            BotFaceBit.DOUBLE_BLINK,
            BotFaceBit.WOBBLE,
            BotFaceBit.HOP,
        )
    }
}

/** A one-shot screen cue. A new [id] can replay the same bit after its previous run ended. */
data class BotFaceCue(
    val bit: BotFaceBit,
    val id: UUID = UUID.randomUUID(),
)
