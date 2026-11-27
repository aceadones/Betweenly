package com.nextlayer.betweenly.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.key
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.PetStore
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.Store
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The shared pet screen: two chubby pets that breathe, blink, and wave, and a
 * daily streak. Everything is drawn from two small bitmaps with transforms,
 * so there is no video and almost no size or battery cost.
 *
 * DATA-FLOW: none. Reads [PetStore] and the profile names on this phone.
 */
@Composable
fun PetScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    val rev by PetStore.changes.collectAsState()
    val storeRev by Store.changes.collectAsState()
    val state = remember(rev) { PetStore.state(context) }
    val partner = remember(storeRev) { Store.partnerName(context).trim() }
    val me = remember(storeRev) { Store.myName(context).trim() }
    Box(Modifier.fillMaxSize()) {
        AppBackground(Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader(stringResource(R.string.pet_title), onBack)
            Spacer(Modifier.height(8.dp))
            val skin = remember(rev) { PetStore.skin(context) }
            val scope = rememberCoroutineScope()
            Box(Modifier.fillMaxWidth().padding(horizontal = 60.dp)) {
                StreakBadge(
                    state.streak,
                    Modifier.align(Alignment.TopEnd).padding(top = 4.dp),
                )
                key(skin) {
                    Pet(
                        res = if (skin == 0) R.drawable.pet_bunny else R.drawable.pet_kitten,
                        eyes = if (skin == 0) {
                            listOf(Offset(0.339f, 0.434f), Offset(0.690f, 0.434f))
                        } else {
                            listOf(Offset(0.325f, 0.327f), Offset(0.689f, 0.328f))
                        },
                        eyeRadius = 0.085f,
                        label = stringResource(R.string.pet_name),
                        phase = 0f,
                    )
                }
            }
            Text(
                stringResource(R.string.pet_choose),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                color = LdColors.colors.inkMuted,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            ) {
                listOf(R.drawable.pet_bunny, R.drawable.pet_kitten).forEachIndexed { i, res ->
                    val chosen = i == skin
                    Box(
                        Modifier
                            .size(84.dp)
                            .ldGlass(RoundedCornerShape(LdRadius.card))
                            .border(
                                if (chosen) 3.dp else 0.dp,
                                if (chosen) LdColors.primary else Color.Transparent,
                                RoundedCornerShape(LdRadius.card),
                            )
                            .clickable {
                                val ts = PetStore.chooseSkin(context, i)
                                if (ts > 0L) {
                                    scope.launch {
                                        // DATA-FLOW: the chosen look, encrypted, to the partner via the relay.
                                        com.nextlayer.betweenly.Outbox.send(context, com.nextlayer.betweenly.Msg.pet(i, ts))
                                    }
                                }
                            }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(res),
                            contentDescription = null,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            StreakCard(state, partner)
            Text(
                stringResource(R.string.pet_hint),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = LdColors.colors.inkMuted,
            )
        }
    }
}

@Composable
private fun StreakCard(state: PetStore.State, partner: String) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(LdRadius.card)
    val name = partner.ifBlank { stringResource(R.string.partner_default) }
    val line = when {
        state.mineToday && state.theirsToday -> stringResource(R.string.pet_both_today)
        state.mineToday -> stringResource(R.string.pet_wait_partner, name)
        state.theirsToday -> stringResource(R.string.pet_wait_you, name)
        else -> stringResource(R.string.pet_none_today)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .ldGlass(shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Flame(lit = state.streak > 0, modifier = Modifier.size(56.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (state.restorable > 0) {
                Text(
                    stringResource(R.string.pet_restore, state.restorable),
                    modifier = Modifier
                        .clickable { PetStore.restore(context) }
                        .ldGlass(RoundedCornerShape(50), strong = true)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    color = LdColors.primary,
                )
                Text(
                    stringResource(R.string.pet_restore_help),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = LdColors.onGlassMuted,
                )
            }
            Text(
                if (state.streak == 1) {
                    stringResource(R.string.pet_streak_one)
                } else {
                    stringResource(R.string.pet_streak_n, state.streak)
                },
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = LdColors.onGlass,
            )
            Text(
                line,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = LdColors.onGlassMuted,
            )
        }
    }
}

/** A flame drawn with gradients. Lit when a streak is alive, grey when it is not. */
@Composable
fun Flame(lit: Boolean, modifier: Modifier = Modifier) {
    val flicker = if (lit && Motion.enabled) {
        val t = rememberInfiniteTransition(label = "flame")
        val v by t.animateFloat(
            initialValue = 0.97f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(tween(520, easing = Motion.easeInOut), RepeatMode.Reverse),
            label = "flameScale",
        )
        v
    } else {
        1f
    }
    Canvas(modifier.graphicsLayer {
        scaleX = 1f + (flicker - 1f) * 0.6f
        scaleY = flicker
        transformOrigin = TransformOrigin(0.5f, 1f)
    }) {
        val w = size.width
        val h = size.height
        fun DrawScope.flame(path: Path, brush: Brush) = drawPath(path, brush)
        if (lit) {
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0x66FF8A00), Color.Transparent),
                    center = Offset(w / 2f, h * 0.66f),
                    radius = w * 0.7f,
                ),
                radius = w * 0.7f,
                center = Offset(w / 2f, h * 0.66f),
            )
        }
        val outer = Path().apply {
            moveTo(w * 0.52f, h * 0.02f)
            cubicTo(w * 0.60f, h * 0.22f, w * 0.90f, h * 0.38f, w * 0.88f, h * 0.65f)
            cubicTo(w * 0.87f, h * 0.86f, w * 0.70f, h * 0.99f, w * 0.50f, h * 0.99f)
            cubicTo(w * 0.30f, h * 0.99f, w * 0.13f, h * 0.86f, w * 0.12f, h * 0.65f)
            cubicTo(w * 0.11f, h * 0.50f, w * 0.20f, h * 0.40f, w * 0.29f, h * 0.31f)
            cubicTo(w * 0.30f, h * 0.44f, w * 0.38f, h * 0.50f, w * 0.44f, h * 0.48f)
            cubicTo(w * 0.40f, h * 0.30f, w * 0.43f, h * 0.14f, w * 0.52f, h * 0.02f)
            close()
        }
        val mid = Path().apply {
            moveTo(w * 0.50f, h * 0.30f)
            cubicTo(w * 0.58f, h * 0.46f, w * 0.78f, h * 0.56f, w * 0.76f, h * 0.74f)
            cubicTo(w * 0.75f, h * 0.90f, w * 0.63f, h * 0.98f, w * 0.50f, h * 0.98f)
            cubicTo(w * 0.37f, h * 0.98f, w * 0.25f, h * 0.90f, w * 0.24f, h * 0.74f)
            cubicTo(w * 0.23f, h * 0.62f, w * 0.34f, h * 0.55f, w * 0.40f, h * 0.48f)
            cubicTo(w * 0.42f, h * 0.58f, w * 0.46f, h * 0.60f, w * 0.48f, h * 0.58f)
            cubicTo(w * 0.46f, h * 0.46f, w * 0.47f, h * 0.38f, w * 0.50f, h * 0.30f)
            close()
        }
        val core = Path().apply {
            moveTo(w * 0.50f, h * 0.58f)
            cubicTo(w * 0.57f, h * 0.68f, w * 0.66f, h * 0.76f, w * 0.64f, h * 0.88f)
            cubicTo(w * 0.62f, h * 0.97f, w * 0.56f, h * 0.99f, w * 0.50f, h * 0.99f)
            cubicTo(w * 0.44f, h * 0.99f, w * 0.38f, h * 0.97f, w * 0.36f, h * 0.88f)
            cubicTo(w * 0.34f, h * 0.76f, w * 0.43f, h * 0.68f, w * 0.50f, h * 0.58f)
            close()
        }
        if (lit) {
            flame(outer, Brush.verticalGradient(listOf(Color(0xFFE5282A), Color(0xFFFF6A00), Color(0xFFFF9A00))))
            flame(mid, Brush.verticalGradient(listOf(Color(0xFFFF8A00), Color(0xFFFFC21F), Color(0xFFFFD84D))))
            flame(core, Brush.verticalGradient(listOf(Color(0xFFFFE27A), Color(0xFFFFF7D6))))
        } else {
            flame(outer, Brush.verticalGradient(listOf(Color(0xFF9B8F95), Color(0xFFB9AEB3))))
            flame(mid, Brush.verticalGradient(listOf(Color(0xFFB9AEB3), Color(0xFFCFC6CA))))
            flame(core, Brush.verticalGradient(listOf(Color(0xFFD9D2D5), Color(0xFFEDE8EA))))
        }
    }
}

/**
 * One pet. It breathes forever, blinks every few seconds, hops and wiggles
 * when tapped (a wave), and waves by itself now and then. Eyelids are drawn
 * over the eyes in the colour of the fur above them.
 */
@Composable
private fun Pet(
    res: Int,
    eyes: List<Offset>,
    eyeRadius: Float,
    label: String,
    phase: Float,
) {
    val image = ImageBitmap.imageResource(res)
    val scope = rememberCoroutineScope()
    val aspect = image.height.toFloat() / image.width.toFloat()
    val breathe = if (Motion.enabled) {
        val t = rememberInfiniteTransition(label = "breathe")
        val v by t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(3200, easing = Motion.easeInOut), RepeatMode.Reverse),
            label = "breatheValue",
        )
        ((v + phase) % 1f).let { x -> if (x < 0.5f) x * 2f else (1f - x) * 2f }
    } else {
        0f
    }
    var blink by remember { mutableFloatStateOf(0f) }
    val hop = remember { Animatable(0f) }
    val tilt = remember { Animatable(0f) }
    var hearts by remember { mutableStateOf(0) }
    val heartRise = remember { Animatable(0f) }
    suspend fun wave() {
        hearts += 1
        scope.launch {
            heartRise.snapTo(0f)
            heartRise.animateTo(1f, tween(900, easing = Motion.easeOut))
        }
        scope.launch { hop.animateTo(1f, spring(0.45f, Spring.StiffnessMedium)); hop.animateTo(0f, spring(0.5f, Spring.StiffnessLow)) }
        for (a in listOf(9f, -9f, 7f, -7f, 0f)) tilt.animateTo(a, tween(110))
    }
    LaunchedEffect(Motion.enabled) {
        if (!Motion.enabled) return@LaunchedEffect
        var n = 0
        while (true) {
            delay(Random.nextLong(2400, 4800))
            blink = 1f; delay(110); blink = 0f
            n++
            if (n % 3 == 0) wave()
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val colors = remember(image) {
                val bmp = image.asAndroidBitmap()
                eyes.map { e ->
                    val x = (e.x * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                    val y = ((e.y - eyeRadius * 1.5f) * bmp.width).toInt().coerceIn(0, bmp.height - 1)
                    Color(bmp.getPixel(x, y))
                }
            }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .graphicsLayer {
                        val b = 0.025f * breathe
                        scaleX = 1f - b * 0.5f
                        scaleY = 1f + b
                        translationY = -hop.value * 26.dp.toPx()
                        rotationZ = tilt.value
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { scope.launch { wave() } },
            ) {
                val iw = size.height / aspect
                val ih = size.height
                val left = (size.width - iw) / 2f
                drawImage(
                    image,
                    dstOffset = IntOffset(left.toInt(), 0),
                    dstSize = IntSize(iw.toInt(), ih.toInt()),
                )
                if (blink > 0f) {
                    eyes.forEachIndexed { i, e ->
                        val cx = left + e.x * iw
                        val cy = e.y * iw
                        val r = eyeRadius * iw
                        drawOval(
                            color = colors[i],
                            topLeft = Offset(cx - r, cy - r * 0.8f),
                            size = Size(r * 2f, r * 1.6f),
                        )
                        drawLine(
                            Color(0xFF7A3B2E),
                            start = Offset(cx - r * 0.7f, cy),
                            end = Offset(cx + r * 0.7f, cy),
                            strokeWidth = 3.dp.toPx(),
                        )
                    }
                }
            }
            if (hearts > 0) {
                val p = heartRise.value
                if (p in 0.001f..0.999f) {
                    Text(
                        "❤",
                        color = LdColors.primary.copy(alpha = 1f - p),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .graphicsLayer {
                                translationY = -p * 40.dp.toPx() + 30.dp.toPx()
                                translationX = sin(p * PI.toFloat() * 2f) * 8.dp.toPx()
                            },
                        style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                    )
                }
            }
        }
        Text(
            label,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            color = LdColors.colors.ink,
        )
    }
}

/**
 * A small pet that floats over the main screens. Drag it anywhere; when you
 * let go it slides to the nearest side and stays there. Tap it to open the
 * pet screen. The place it was left is remembered.
 *
 * DATA-FLOW: none. The side and height are stored on this phone.
 */
@Composable
fun PetOverlay(onOpen: () -> Unit) {
    if (PetOverlayGate.hidden) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val petRevO by PetStore.changes.collectAsState()
    val skinO = remember(petRevO) { PetStore.skin(context) }
    val image = ImageBitmap.imageResource(if (skinO == 0) R.drawable.pet_bunny else R.drawable.pet_kitten)
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val petW = 64.dp
        val petH = 64.dp * (image.height.toFloat() / image.width.toFloat())
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val pw = with(density) { petW.toPx() }
        val ph = with(density) { petH.toPx() }
        val margin = with(density) { 4.dp.toPx() }
        val top = with(density) { 120.dp.toPx() }
        val imePx = WindowInsets.ime.getBottom(density).toFloat()
        val bottom = with(density) { 200.dp.toPx() } + imePx
        val (side0, frac0) = remember { PetStore.overlayPos(context) }
        val x = remember { Animatable(if (side0 == 0) margin else wPx - pw - margin) }
        val y = remember {
            Animatable((frac0 * hPx).coerceIn(top, (hPx - bottom - ph).coerceAtLeast(top)))
        }
        // Keep the pet above the keyboard and the message bar.
        LaunchedEffect(imePx) {
            val limit = (hPx - bottom - ph).coerceAtLeast(top)
            if (y.value > limit) y.animateTo(limit, tween(200))
        }
        val breathe = if (Motion.enabled) {
            val t = rememberInfiniteTransition(label = "overlayBreathe")
            val v by t.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(3000, easing = Motion.easeInOut), RepeatMode.Reverse),
                label = "overlayBreatheValue",
            )
            v
        } else {
            0f
        }
        Box(
            Modifier
                .offset { IntOffset(x.value.toInt(), y.value.toInt()) }
                .size(petW, petH)
                .graphicsLayer {
                    scaleY = 1f + 0.03f * breathe
                    scaleX = 1f - 0.015f * breathe
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    shadowElevation = 0f
                }
                .pointerInput(wPx, hPx) {
                    detectDragGestures(
                        onDragEnd = {
                            val toRight = x.value + pw / 2f > wPx / 2f
                            val targetX = if (toRight) wPx - pw - margin else margin
                            scope.launch {
                                x.animateTo(targetX, spring(0.7f, Spring.StiffnessMedium))
                            }
                            PetStore.saveOverlayPos(context, if (toRight) 1 else 0, y.value / hPx)
                        },
                    ) { change, drag ->
                        change.consume()
                        scope.launch {
                            x.snapTo((x.value + drag.x).coerceIn(0f, wPx - pw))
                            y.snapTo((y.value + drag.y).coerceIn(top, (hPx - bottom - ph).coerceAtLeast(top)))
                        }
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpen,
                ),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            }
            val streakNow = PetStore.state(context).streak
            StreakBadge(
                streakNow,
                Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = (-8).dp),
                small = true,
            )
        }
    }
}

/** Flame and the day count, shown on the pet when a streak is alive. Hidden at zero. */
@Composable
fun StreakBadge(streak: Int, modifier: Modifier = Modifier, small: Boolean = false) {
    if (streak <= 0) return
    Row(
        modifier
            .ldGlass(RoundedCornerShape(50))
            .padding(horizontal = if (small) 5.dp else 8.dp, vertical = if (small) 2.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Flame(lit = true, modifier = Modifier.size(if (small) 16.dp else 24.dp))
        Text(
            "$streak",
            style = if (small) {
                androidx.compose.material3.MaterialTheme.typography.labelMedium
            } else {
                androidx.compose.material3.MaterialTheme.typography.titleMedium
            },
            color = LdColors.onGlass,
        )
    }
}

/** Lets a full-screen sheet (photo preview, editor) hide the floating pet while it is open. */
object PetOverlayGate {
    var hidden by mutableStateOf(false)
}
