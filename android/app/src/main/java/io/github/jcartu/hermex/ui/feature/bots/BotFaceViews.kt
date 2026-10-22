package io.github.jcartu.hermex.ui.feature.bots

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jcartu.hermex.model.hermes.BotAvatarColors
import io.github.jcartu.hermex.model.hermes.BotAvatarExpression
import io.github.jcartu.hermex.model.hermes.BotAvatarShape
import io.github.jcartu.hermex.model.hermes.BotProfile
import io.github.jcartu.hermex.model.hermes.BotProfileAppearance
import io.github.jcartu.hermex.model.hermes.BotTitleFace
import io.github.jcartu.hermex.ui.feature.chat.rememberReduceMotion
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A bitmap asset wins over the compatible drawn fallback. Bitmaps are supplied by the caller;
 * this composable performs no fetch and photos never animate.
 */
@Composable
fun BotAvatarView(
    profile: BotProfile,
    avatar: Bitmap? = null,
    size: Dp = 44.dp,
    motion: BotFaceMotion = BotFaceMotion.Idle,
    expression: BotAvatarExpression? = null,
    modifier: Modifier = Modifier,
) {
    if (avatar != null) {
        val image = remember(avatar) { avatar.asImageBitmap() }
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size),
        )
    } else {
        val appearance = remember(profile.look, profile.name, expression) {
            BotProfileAppearance.of(profile).let { value ->
                expression?.let { value.copy(expression = it.rawValue) } ?: value
            }
        }
        BotAnimatedFaceView(
            name = profile.id,
            appearance = appearance,
            size = size,
            motion = motion,
            modifier = modifier,
        )
    }
}

/**
 * Draws a face according to [motion]. Idle and settled work schedule only blink edges; only the
 * first thirty seconds of a working beat request frame-rate updates. Inactive and Reduce Motion
 * paths are a plain rest frame with no scheduling coroutine.
 */
@Composable
fun BotAnimatedFaceView(
    name: String,
    appearance: BotProfileAppearance,
    size: Dp,
    motion: BotFaceMotion = BotFaceMotion.Idle,
    modifier: Modifier = Modifier,
) {
    val active = rememberFaceActive()
    val reduceMotion = rememberReduceMotion()
    AnimatedFace(
        name = name,
        appearance = appearance,
        size = size,
        motion = motion,
        gazeX = 0.0,
        gazeY = 0.0,
        active = active,
        reduceMotion = reduceMotion,
        modifier = modifier,
    )
}

/** One clock-free frame of the compatible Desktop face vocabulary. */
@Composable
fun BotAvatarMarkView(
    name: String,
    appearance: BotProfileAppearance,
    size: Dp,
    pose: BotFacePose = BotFacePose.Rest,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val renderer = remember(name, appearance, dark) { BotAvatarRenderer(name, appearance, dark) }
    var canvasModifier = modifier
    if (pose.scaleX != 1.0 || pose.scaleY != 1.0 || pose.lift != 0.0) {
        val sizePixels = with(LocalDensity.current) { size.toPx() }
        canvasModifier = canvasModifier.graphicsLayer {
            scaleX = pose.scaleX.toFloat()
            scaleY = pose.scaleY.toFloat()
            translationY = (-sizePixels * pose.lift).toFloat()
            transformOrigin = TransformOrigin(0.5f, 1f)
        }
    }
    if (pose.roll != 0.0) {
        canvasModifier = canvasModifier.graphicsLayer {
            rotationZ = pose.roll.toFloat()
            transformOrigin = TransformOrigin(0.5f, 0.5f)
        }
    }
    Canvas(modifier = canvasModifier.size(size)) {
        drawIntoCanvas { canvas -> renderer.draw(canvas.nativeCanvas, this.size.width, this.size.height, pose) }
    }
}

/**
 * The create/edit hero. Its eyes follow a drag, a tap briefly uses Surprised eyes and squishes,
 * and seeded short bits play between sparse blinks. A cue already playing is ignored. All
 * autonomous work is cancelled while inactive; Reduce Motion leaves only the tap expression.
 */
@Composable
fun BotInteractiveFaceView(
    name: String,
    appearance: BotProfileAppearance,
    size: Dp = 120.dp,
    cue: BotFaceCue? = null,
    modifier: Modifier = Modifier,
) {
    val active = rememberFaceActive()
    val reduceMotion = rememberReduceMotion()
    val gazeX = remember { Animatable(0f) }
    val gazeY = remember { Animatable(0f) }
    val interactionScope = rememberCoroutineScope()
    var reaction by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf<PlayingBit?>(null) }
    var bitSample by remember { mutableDoubleStateOf(nowSeconds()) }
    var playfulIndex by remember(name) { mutableIntStateOf(0) }

    LaunchedEffect(active, reduceMotion) {
        if (!active || reduceMotion) {
            playing = null
            gazeX.snapTo(0f)
            gazeY.snapTo(0f)
            if (!active) reaction = 0
        }
    }

    LaunchedEffect(reaction) {
        if (reaction <= 0) return@LaunchedEffect
        val token = reaction
        delay(REACTION_MILLIS)
        if (reaction == token) reaction = 0
    }

    LaunchedEffect(cue?.id) {
        val requested = cue ?: return@LaunchedEffect
        if (active && !reduceMotion && playing?.bit != requested.bit) {
            val start = nowSeconds()
            bitSample = start
            playing = PlayingBit(requested.bit, start)
        }
    }

    LaunchedEffect(name, active, reduceMotion) {
        if (!active || reduceMotion) return@LaunchedEffect
        val schedule = BotPlayfulSchedule(name)
        while (coroutineContext.isActive) {
            val entry = schedule.entry(playfulIndex)
            delaySeconds(entry.delay)
            if (playing == null && reaction == 0) {
                val start = nowSeconds()
                bitSample = start
                playing = PlayingBit(entry.bit, start)
            }
            playfulIndex += 1
        }
    }

    LaunchedEffect(playing, active, reduceMotion) {
        val run = playing ?: return@LaunchedEffect
        if (!active || reduceMotion) return@LaunchedEffect
        while (coroutineContext.isActive) {
            val sample = nowSeconds()
            bitSample = sample
            if (sample - run.start >= run.bit.duration) {
                if (playing == run) playing = null
                return@LaunchedEffect
            }
            delaySeconds(BIT_FRAME_INTERVAL)
        }
    }

    val shown = if (reaction > 0) {
        appearance.copy(expression = BotAvatarExpression.SURPRISED.rawValue)
    } else {
        appearance
    }
    val scaleX by animateFloatAsState(
        targetValue = if (reaction > 0 && active && !reduceMotion) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.58f, stiffness = 650f),
        label = "bot face squish x",
    )
    val scaleY by animateFloatAsState(
        targetValue = if (reaction > 0 && active && !reduceMotion) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.58f, stiffness = 650f),
        label = "bot face squish y",
    )

    var gestureModifier = modifier
    if (scaleX != 1f || scaleY != 1f) {
        gestureModifier = gestureModifier.graphicsLayer {
            this.scaleX = scaleX
            this.scaleY = scaleY
        }
    }
    gestureModifier = gestureModifier.pointerInput(name, active, reduceMotion, size) {
            if (!active) return@pointerInput
            val width = this.size.width.toFloat().coerceAtLeast(1f)
            val height = this.size.height.toFloat().coerceAtLeast(1f)
            val tapDistance = 10.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val origin = down.position
                val acceptsTouch = hypot(
                    (origin.x - width / 2f).toDouble(),
                    (origin.y - height / 2f).toDouble(),
                ) <= min(width, height) / 2.0
                var furthest = 0f
                var pressed = true
                while (pressed) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val dxFromOrigin = change.position.x - origin.x
                    val dyFromOrigin = change.position.y - origin.y
                    furthest = maxOf(furthest, hypot(dxFromOrigin.toDouble(), dyFromOrigin.toDouble()).toFloat())
                    if (acceptsTouch && !reduceMotion && change.pressed) {
                        val dx = (change.position.x - width / 2f) / width
                        val dy = (change.position.y - height / 2f) / height
                        val length = hypot(dx.toDouble(), dy.toDouble()).coerceAtLeast(0.001)
                        val clamp = min(length, 0.5) / length
                        gazeX.snapTo((dx * clamp * GAZE_REACH * 2.0).toFloat())
                        gazeY.snapTo((dy * clamp * GAZE_REACH * 2.0).toFloat())
                        change.consume()
                    }
                    pressed = change.pressed
                }
                if (acceptsTouch) {
                    if (reduceMotion) {
                        gazeX.snapTo(0f)
                        gazeY.snapTo(0f)
                    } else {
                        interactionScope.launch {
                            gazeX.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 500f))
                        }
                        interactionScope.launch {
                            gazeY.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 500f))
                        }
                    }
                    if (furthest < tapDistance) reaction += 1
                }
            }
        }

    val run = playing
    if (run != null && active && !reduceMotion) {
        val progress = (bitSample - run.start) / run.bit.duration
        val basePose = run.bit.pose(progress)
        val x = gazeX.value.toDouble()
        val y = gazeY.value.toDouble()
        val bitPose = if (x == 0.0 && y == 0.0) basePose else basePose.withGaze(x, y)
        BotAvatarMarkView(name, shown, size, bitPose, gestureModifier)
    } else {
        AnimatedFace(
            name = name,
            appearance = shown,
            size = size,
            motion = BotFaceMotion.Idle,
            gazeX = gazeX.value.toDouble(),
            gazeY = gazeY.value.toDouble(),
            active = active,
            reduceMotion = reduceMotion,
            modifier = gestureModifier,
        )
    }
}

/**
 * Chat title face/name pill. [turn] owns the bounded working-beat gate: UNKNOWN reconnects do not
 * rearm, attention/interruption do, and returning from inactivity explicitly starts a fresh beat
 * if work is still live. Waiting and failed expressions override the pinned rest expression.
 */
@Composable
fun BotChatTitlePill(
    profile: BotProfile,
    avatar: Bitmap? = null,
    face: BotTitleFace = BotTitleFace.RESTING,
    turn: BotTurnState = BotTurnState.IDLE,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = rememberFaceActive()
    val reduceMotion = rememberReduceMotion()
    val beat = remember(profile.id) { BotWorkingBeat() }
    var beatStart by remember(profile.id) { mutableDoubleStateOf(beat.start) }
    val previousActive = remember(profile.id) { booleanArrayOf(active) }

    LaunchedEffect(turn, active, profile.id) {
        if (active) {
            if (!previousActive[0]) beat.rearm()
            beat.observe(turn, nowSeconds())
        } else if (turn != BotTurnState.RUNNING && turn != BotTurnState.UNKNOWN) {
            beat.observe(turn, nowSeconds())
        }
        previousActive[0] = active
        beatStart = beat.start
    }

    val shownFace = if (turn == BotTurnState.NEEDS_ATTENTION) BotTitleFace.WAITING else face
    val motion = when {
        !active || reduceMotion -> BotFaceMotion.Still
        shownFace == BotTitleFace.WORKING -> BotFaceMotion.Working(beatStart)
        else -> BotFaceMotion.Idle
    }
    val state = shownFace.accessibilityValue
    Surface(
        onClick = onOpen,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = profile.name
            if (state != null) stateDescription = state
        },
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotAvatarView(
                profile = profile,
                avatar = avatar,
                size = 30.dp,
                motion = motion,
                expression = shownFace.expression,
            )
            Text(
                text = profile.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * Renders the same REST geometry as [BotAvatarMarkView] into a transparent bitmap. This is the
 * allocation boundary for roster memoization and notification icons; it never fetches an asset.
 */
fun renderBotAvatar(
    profile: BotProfile,
    sizePixels: Int = 48,
    dark: Boolean = true,
): Bitmap {
    require(sizePixels > 0) { "sizePixels must be positive" }
    val bitmap = Bitmap.createBitmap(sizePixels, sizePixels, Bitmap.Config.ARGB_8888)
    BotAvatarRenderer(profile.id, BotProfileAppearance.of(profile), dark).draw(
        canvas = AndroidCanvas(bitmap),
        width = sizePixels.toFloat(),
        height = sizePixels.toFloat(),
        pose = BotFacePose.Rest,
    )
    return bitmap
}

@Composable
private fun AnimatedFace(
    name: String,
    appearance: BotProfileAppearance,
    size: Dp,
    motion: BotFaceMotion,
    gazeX: Double,
    gazeY: Double,
    active: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier,
) {
    val resolved = if (active) motion.honoring(reduceMotion) else BotFaceMotion.Still
    val blink = remember(name) { BotBlinkSchedule(name) }
    val working = remember(resolved, blink) {
        (resolved as? BotFaceMotion.Working)?.let { BotWorkingSchedule(it.since, blink) }
    }
    var sample by remember(name, resolved) { mutableDoubleStateOf(nowSeconds()) }

    LaunchedEffect(name, resolved, active) {
        if (!active || resolved == BotFaceMotion.Still) return@LaunchedEffect
        when (resolved) {
            BotFaceMotion.Still -> Unit
            BotFaceMotion.Idle -> {
                while (coroutineContext.isActive) {
                    val now = nowSeconds()
                    sample = now
                    delayUntil(blink.nextEdge(now))
                }
            }
            is BotFaceMotion.Working -> {
                val schedule = checkNotNull(working)
                while (coroutineContext.isActive) {
                    val now = nowSeconds()
                    sample = now
                    delayUntil(schedule.nextEntry(now))
                }
            }
        }
    }

    val pose = when (resolved) {
        BotFaceMotion.Still -> BotFacePose.Rest
        BotFaceMotion.Idle -> if (blink.isShut(sample)) BotFacePose.Blink else BotFacePose.Rest
        is BotFaceMotion.Working -> checkNotNull(working).pose(sample)
    }
    val shownPose = if (resolved == BotFaceMotion.Still || (gazeX == 0.0 && gazeY == 0.0)) {
        pose
    } else {
        pose.withGaze(gazeX, gazeY)
    }
    BotAvatarMarkView(name, appearance, size, shownPose, modifier)
}

@Composable
private fun rememberFaceActive(): Boolean {
    val owner = LocalLifecycleOwner.current
    var active by remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            active = when (event) {
                Lifecycle.Event.ON_RESUME -> true
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY,
                -> false
                else -> owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
        }
        owner.lifecycle.addObserver(observer)
        active = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return active
}

private data class PlayingBit(val bit: BotFaceBit, val start: Double)

private fun BotFacePose.withGaze(x: Double, y: Double): BotFacePose = copy(
    gazeX = (gazeX + x).coerceIn(-GAZE_REACH, GAZE_REACH),
    gazeY = (gazeY + y).coerceIn(-GAZE_REACH, GAZE_REACH),
)

private suspend fun delayUntil(epochSeconds: Double) {
    val remaining = epochSeconds - nowSeconds()
    if (remaining > 0.0) delay(maxOf(1L, ceil(remaining * 1_000.0).toLong()))
}

private suspend fun delaySeconds(seconds: Double) {
    delay(maxOf(1L, ceil(seconds * 1_000.0).toLong()))
}

private fun nowSeconds(): Double = System.currentTimeMillis() / 1_000.0

/** One renderer backs Compose Canvas and the bitmap API, preventing fallback geometry drift. */
private class BotAvatarRenderer(
    name: String,
    appearance: BotProfileAppearance,
    dark: Boolean,
) {
    private val presentation = BotAvatarColors.presentation(name, appearance)
    private val geometry = BotAvatarExpression.resolve(appearance.expression).geometry
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = presentation.body(dark)
    }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = presentation.eyes(dark)
    }
    private val path = Path()

    fun draw(canvas: AndroidCanvas, width: Float, height: Float, pose: BotFacePose) {
        if (width <= 0f || height <= 0f) return
        val mark = min(width, height)
        val left = (width - mark) / 2f
        val top = (height - mark) / 2f
        drawBody(canvas, presentation.shape, left, top, mark)
        drawEyes(canvas, left, top, mark, pose)
    }

    private fun drawBody(canvas: AndroidCanvas, shape: BotAvatarShape, left: Float, top: Float, mark: Float) {
        val inset = mark * 0.08f
        val minX = left + inset
        val minY = top + inset
        val maxX = left + mark - inset
        val maxY = top + mark - inset
        val midX = left + mark / 2f
        when (shape) {
            BotAvatarShape.CIRCLE -> canvas.drawOval(minX, minY, maxX, maxY, bodyPaint)
            BotAvatarShape.SQUIRCLE -> {
                val radius = mark * 0.28f
                canvas.drawRoundRect(minX, minY, maxX, maxY, radius, radius, bodyPaint)
            }
            BotAvatarShape.PILL -> {
                val pillTop = top + mark * 0.23f
                val pillBottom = top + mark * 0.77f
                val radius = (pillBottom - pillTop) / 2f
                canvas.drawRoundRect(minX, pillTop, maxX, pillBottom, radius, radius, bodyPaint)
            }
            BotAvatarShape.TRIANGLE -> {
                path.reset()
                path.moveTo(midX, minY)
                path.lineTo(maxX, maxY)
                path.lineTo(minX, maxY)
                path.close()
                canvas.drawPath(path, bodyPaint)
            }
            BotAvatarShape.HEXAGON -> {
                path.reset()
                path.moveTo(midX, minY)
                path.lineTo(maxX, top + mark * 0.3f)
                path.lineTo(maxX, top + mark * 0.7f)
                path.lineTo(midX, maxY)
                path.lineTo(minX, top + mark * 0.7f)
                path.lineTo(minX, top + mark * 0.3f)
                path.close()
                canvas.drawPath(path, bodyPaint)
            }
            BotAvatarShape.CLOUD -> {
                canvas.drawOval(left + mark * 0.08f, top + mark * 0.38f, left + mark * 0.56f, top + mark * 0.86f, bodyPaint)
                canvas.drawOval(left + mark * 0.28f, top + mark * 0.12f, left + mark * 0.78f, top + mark * 0.76f, bodyPaint)
                canvas.drawOval(left + mark * 0.55f, top + mark * 0.35f, left + mark * 0.93f, top + mark * 0.8f, bodyPaint)
            }
            BotAvatarShape.DROP -> {
                path.reset()
                path.moveTo(midX, minY)
                path.cubicTo(
                    midX + mark * 0.22f,
                    top + mark * 0.2f,
                    maxX,
                    top + mark * 0.42f,
                    maxX,
                    top + mark * 0.62f,
                )
                path.cubicTo(maxX, maxY, minX, maxY, minX, top + mark * 0.62f)
                path.cubicTo(
                    minX,
                    top + mark * 0.42f,
                    midX - mark * 0.22f,
                    top + mark * 0.2f,
                    midX,
                    minY,
                )
                path.close()
                canvas.drawPath(path, bodyPaint)
            }
            BotAvatarShape.BLOB -> {
                path.reset()
                path.moveTo(left + mark * 0.2f, top + mark * 0.17f)
                path.cubicTo(
                    left + mark * 0.4f,
                    top,
                    left + mark * 0.78f,
                    top + mark * 0.02f,
                    left + mark * 0.86f,
                    top + mark * 0.2f,
                )
                path.cubicTo(
                    left + mark,
                    top + mark * 0.4f,
                    left + mark,
                    top + mark * 0.72f,
                    left + mark * 0.78f,
                    top + mark * 0.86f,
                )
                path.cubicTo(
                    left + mark * 0.55f,
                    top + mark,
                    left + mark * 0.2f,
                    top + mark,
                    left + mark * 0.12f,
                    top + mark * 0.78f,
                )
                path.cubicTo(
                    left,
                    top + mark * 0.6f,
                    left,
                    top + mark * 0.3f,
                    left + mark * 0.2f,
                    top + mark * 0.17f,
                )
                path.close()
                canvas.drawPath(path, bodyPaint)
            }
        }
    }

    private fun drawEyes(canvas: AndroidCanvas, left: Float, top: Float, mark: Float, pose: BotFacePose) {
        val leftWidth = mark * 0.075f * (geometry.left.width / 0.186).toFloat()
        val rightWidth = mark * 0.075f * (geometry.right.width / 0.186).toFloat()
        val spacing = mark * 0.1f * (geometry.split / 15.46).toFloat()
        val groupCenter = left + mark * (0.62f + pose.gazeX.toFloat())
        val eyeCenterY = top + mark * (0.42f + pose.gazeY.toFloat())
        val leftCenter = groupCenter - (spacing + rightWidth) / 2f
        val rightCenter = groupCenter + (spacing + leftWidth) / 2f
        drawEye(
            canvas,
            leftCenter,
            eyeCenterY,
            leftWidth,
            mark * 0.22f * (geometry.left.height / 0.412).toFloat() * pose.lid.toFloat(),
            -18f + geometry.left.tilt.toFloat(),
        )
        drawEye(
            canvas,
            rightCenter,
            eyeCenterY,
            rightWidth,
            mark * 0.22f * (geometry.right.height / 0.412).toFloat() * pose.lid.toFloat(),
            -18f + geometry.right.tilt.toFloat(),
        )
    }

    private fun drawEye(
        canvas: AndroidCanvas,
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float,
        degrees: Float,
    ) {
        val radius = min(width, height) / 2f
        canvas.save()
        canvas.rotate(degrees, centerX, centerY)
        canvas.drawRoundRect(
            centerX - width / 2f,
            centerY - height / 2f,
            centerX + width / 2f,
            centerY + height / 2f,
            radius,
            radius,
            eyePaint,
        )
        canvas.restore()
    }
}

private const val GAZE_REACH = 0.07
private const val REACTION_MILLIS = 650L
private const val BIT_FRAME_INTERVAL = 1.0 / 60.0
