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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.graphics.drawscope.rotate
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
            val look = remember(rev) { PetStore.look(context) }
            val skin = look.skin
            val scope = rememberCoroutineScope()
            Box(Modifier.fillMaxWidth().padding(horizontal = 60.dp)) {
                StreakBadge(
                    state.streak,
                    Modifier.align(Alignment.TopEnd).padding(top = 4.dp),
                )
                key(skin) {
                    Pet(
                        sad = petIsSad(state),
                        res = petRes(skin, petIsSad(state)),
                        eyes = if (skin == 0) {
                            listOf(Offset(0.339f, 0.434f), Offset(0.690f, 0.434f))
                        } else {
                            listOf(Offset(0.325f, 0.327f), Offset(0.689f, 0.328f))
                        },
                        eyeRadius = 0.085f,
                        label = look.name.ifBlank { stringResource(R.string.pet_name) },
                        phase = 0f,
                        look = look,
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
                                        com.nextlayer.betweenly.Outbox.send(context, PetStore.message(context, ts))
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
            var renaming by remember { mutableStateOf(false) }
            var draftName by remember { mutableStateOf("") }
            Text(
                stringResource(R.string.pet_rename),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(50))
                    .clickable { draftName = look.name; renaming = true }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                color = LdColors.primary,
            )
            if (renaming) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { renaming = false },
                    title = { Text(stringResource(R.string.pet_rename_title)) },
                    text = {
                        androidx.compose.foundation.text.BasicTextField(
                            value = draftName,
                            onValueChange = { draftName = it.take(20) },
                            singleLine = true,
                            textStyle = androidx.compose.material3.MaterialTheme.typography.titleLarge.copy(
                                color = LdColors.colors.ink,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(LdColors.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    confirmButton = {
                        Text(
                            stringResource(R.string.photo_edit_done),
                            modifier = Modifier
                                .clickable {
                                    renaming = false
                                    val ts = PetStore.rename(context, draftName)
                                    if (ts > 0L) {
                                        scope.launch {
                                            // DATA-FLOW: the pet's name and look, encrypted, to the partner via the relay.
                                            com.nextlayer.betweenly.Outbox.send(context, PetStore.message(context, ts))
                                        }
                                    }
                                }
                                .padding(12.dp),
                            color = LdColors.primary,
                        )
                    },
                )
            }
            Wardrobe(look) { item ->
                val ts = PetStore.toggleItem(context, item.slot, item.id)
                scope.launch {
                    // DATA-FLOW: the chosen look, encrypted, to the partner via the relay.
                    com.nextlayer.betweenly.Outbox.send(context, PetStore.message(context, ts))
                }
            }
            Spacer(Modifier.height(4.dp))
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
            Text(
                stringResource(R.string.pet_day_note),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
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
    sad: Boolean,
    res: Int,
    eyes: List<Offset>,
    eyeRadius: Float,
    label: String,
    phase: Float,
    look: PetStore.Look,
) {
    val image = ImageBitmap.imageResource(res)
    val worn = wornItems(look)
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
    val shiver = if (sad && Motion.enabled) {
        val t = rememberInfiniteTransition(label = "shiver")
        val v by t.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(90), RepeatMode.Reverse),
            label = "shiverValue",
        )
        v
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
    LaunchedEffect(Motion.enabled, sad) {
        if (!Motion.enabled || sad) return@LaunchedEffect
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
                        translationX = shiver * 2.dp.toPx()
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
                if (!sad) drawOutfit(worn, look.skin, left, iw, ih)
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
    val sadO = petIsSad(PetStore.state(context))
    val image = ImageBitmap.imageResource(petRes(skinO, sadO))
    val wornO = wornItems(PetStore.look(context))
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
                if (!sadO) drawOutfit(wornO, skinO, 0f, size.width, size.height)
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

/** The drawable for the pet's look. Sad and frosty until both of you have messaged today. */
fun petRes(skin: Int, sad: Boolean): Int = when {
    skin == 0 && sad -> R.drawable.pet_bunny_sad
    skin == 0 -> R.drawable.pet_bunny
    sad -> R.drawable.pet_kitten_sad
    else -> R.drawable.pet_kitten
}

/** True when the pet should look cold and sad: not both of you have messaged today. */
fun petIsSad(state: PetStore.State): Boolean = !(state.mineToday && state.theirsToday)

/** One wearable. [slot] 0 head, 1 face, 2 neck, 3 hand. */
class PetItem(val id: Int, val slot: Int, val res: Int, val label: Int)

val petItems = listOf(
    PetItem(0, 0, R.drawable.acc_party, R.string.acc_party),
    PetItem(1, 0, R.drawable.acc_crown, R.string.acc_crown),
    PetItem(2, 0, R.drawable.acc_tophat, R.string.acc_tophat),
    PetItem(3, 0, R.drawable.acc_cap, R.string.acc_cap),
    PetItem(4, 1, R.drawable.acc_sunglasses, R.string.acc_sunglasses),
    PetItem(5, 1, R.drawable.acc_heartglasses, R.string.acc_heartglasses),
    PetItem(6, 2, R.drawable.acc_bowtie, R.string.acc_bowtie),
    PetItem(7, 2, R.drawable.acc_scarf, R.string.acc_scarf),
    PetItem(8, 3, R.drawable.acc_guitar, R.string.acc_guitar),
)

/** Where one accessory sits on a pet, as fractions of the pet picture. Head items anchor at their bottom edge. */
private class Spot(val cx: Float, val cy: Float, val width: Float, val rotate: Float = 0f)

private fun spotFor(item: PetItem, skin: Int): Spot {
    val bunny = skin == 0
    return when (item.id) {
        0 -> Spot(0.50f, if (bunny) 0.27f else 0.15f, 0.30f)
        1 -> Spot(0.50f, if (bunny) 0.27f else 0.15f, 0.36f)
        2 -> Spot(0.50f, if (bunny) 0.27f else 0.15f, 0.40f)
        3 -> Spot(0.52f, if (bunny) 0.30f else 0.18f, 0.46f)
        4, 5 -> Spot(0.515f, if (bunny) 0.434f else 0.327f, 0.58f)
        6 -> Spot(0.50f, if (bunny) 0.64f else 0.61f, 0.26f)
        7 -> Spot(0.50f, if (bunny) 0.66f else 0.63f, 0.52f)
        else -> Spot(0.74f, if (bunny) 0.78f else 0.72f, 0.52f, -18f)
    }
}

/** Loads the worn accessories once per look. Order is the slot order, so the scarf sits under the guitar. */
@Composable
private fun wornItems(look: PetStore.Look): List<Pair<PetItem, ImageBitmap>> {
    val out = ArrayList<Pair<PetItem, ImageBitmap>>(4)
    for (slot in 0..3) {
        val id = look.outfit.getOrNull(slot) ?: -1
        val item = petItems.firstOrNull { it.id == id } ?: continue
        out.add(item to ImageBitmap.imageResource(item.res))
    }
    return out
}

/** Draws the worn accessories on a pet picture of size [iw] x [ih] whose left edge is at [left]. */
private fun DrawScope.drawOutfit(worn: List<Pair<PetItem, ImageBitmap>>, skin: Int, left: Float, iw: Float, ih: Float) {
    for ((item, bmp) in worn) {
        val spot = spotFor(item, skin)
        val w = spot.width * iw
        val h = w * bmp.height / bmp.width
        val cx = left + spot.cx * iw
        val cy = spot.cy * ih
        val top = if (item.slot == 0) cy - h else cy - h / 2f
        rotate(spot.rotate, Offset(cx, cy)) {
            drawImage(
                bmp,
                dstOffset = IntOffset((cx - w / 2f).toInt(), top.toInt()),
                dstSize = IntSize(w.toInt(), h.toInt()),
            )
        }
    }
}

/** The wardrobe row. Tap an item to wear it, tap again to take it off. */
@Composable
private fun Wardrobe(look: PetStore.Look, onToggle: (PetItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.pet_dress),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            color = LdColors.colors.inkMuted,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            petItems.forEach { item ->
                val worn = look.outfit.getOrNull(item.slot) == item.id
                Box(
                    Modifier
                        .size(64.dp)
                        .ldGlass(RoundedCornerShape(LdRadius.card))
                        .border(
                            if (worn) 3.dp else 0.dp,
                            if (worn) LdColors.primary else Color.Transparent,
                            RoundedCornerShape(LdRadius.card),
                        )
                        .clickable { onToggle(item) }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(item.res),
                        contentDescription = stringResource(item.label),
                    )
                }
            }
        }
    }
}
