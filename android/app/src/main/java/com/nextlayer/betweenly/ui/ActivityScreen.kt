package com.nextlayer.betweenly.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nextlayer.betweenly.DrawStore
import com.nextlayer.betweenly.DuetBridge
import com.nextlayer.betweenly.DuetState
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.Store
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Activity is a hub. Drawing opens in this app. Duet opens the other app, or
 * its install page. The card reads Duet when this screen resumes, and when
 * Duet pushes a new line. It does not poll.
 *
 * DATA-FLOW: none. A Duet tap is a local intent. Install hands a URL to the browser.
 */
@Composable
fun ActivityScreen(onOpenDrawing: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val storeRev by Store.changes.collectAsState()
    val duetRev by DuetState.changes.collectAsState()
    val duetNow = remember(resumeTick, duetRev) { DuetState.fresh(context) }
    val duetInstalled = remember(resumeTick) { DuetBridge.installed(context) }
    val drawRev by DrawStore.changes.collectAsState()
    val partnerFile = remember(drawRev, storeRev) { DrawStore.latestFromPartner(context) }
    val showPreview = remember(drawRev, storeRev) { DrawStore.hasPreview(context) }
    val name = remember(storeRev) { Store.partnerName(context).trim() }
    val slot = remember { arrayOfNulls<Bitmap>(1) }
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(drawRev, storeRev) {
        val decoded = if (!showPreview) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val bmp = try {
                    // Shared page (paper + both layers), or the older single-file fallback.
                    DrawStore.loadPreview(context, 720)
                } catch (_: Exception) {
                    null
                }
                if (!isActive && bmp != null) {
                    if (!bmp.isRecycled) bmp.recycle()
                    null
                } else {
                    bmp
                }
            }
        }
        val previous = slot[0]
        slot[0] = decoded
        thumb = decoded
        if (previous != null && previous !== decoded) recycleLater(view, previous)
    }
    DisposableEffect(Unit) {
        onDispose { slot[0]?.let { recycleLater(view, it) } }
    }
    val drawSubtitle = when {
        partnerFile != null && name.isNotEmpty() -> stringResource(R.string.draw_from, name)
        partnerFile != null -> stringResource(R.string.draw_partner)
        else -> stringResource(R.string.activity_draw_help)
    }
    val cards = HubCard.entries
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.tab_activity),
                style = LdType.Display,
                color = LdColors.colors.ink,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                cards.forEachIndexed { index, card ->
                    Box(Modifier.weight(1f)) {
                        when (card) {
                            HubCard.Draw -> HubTile(
                                title = stringResource(R.string.activity_draw),
                                subtitle = drawSubtitle,
                                index = index,
                                onClick = onOpenDrawing,
                                chipText = stringResource(R.string.activity_open),
                            ) {
                                DrawMedia(thumb = thumb, waiting = showPreview && thumb == null)
                            }
                            HubCard.Duet -> HubTile(
                                title = stringResource(R.string.duet_title),
                                subtitle = stringResource(R.string.duet_card_sub),
                                index = index,
                                onClick = { DuetBridge.open(context) },
                                chipText = duetChip(duetInstalled, duetNow),
                            ) {
                                DuetMedia(playing = duetInstalled && duetNow?.playing == true)
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class HubCard { Draw, Duet }

@Composable
private fun duetChip(installed: Boolean, now: DuetState.Now?): String {
    if (!installed) return stringResource(R.string.duet_install)
    if (now == null || (now.title.isBlank() && !now.playing)) {
        return stringResource(R.string.activity_open)
    }
    return if (now.playing) {
        stringResource(R.string.duet_chip_playing)
    } else {
        stringResource(R.string.duet_chip_paused)
    }
}

@Composable
private fun HubTile(
    title: String,
    subtitle: String,
    index: Int,
    onClick: () -> Unit,
    chipText: String,
    media: @Composable () -> Unit,
) {
    val interaction = remember(title) { MutableInteractionSource() }
    val shape = RoundedCornerShape(LdRadius.card)
    val mediaShape = RoundedCornerShape(LdRadius.inner(LdRadius.card, 8.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .appear(index)
            .pressScale(interaction)
            .ldGlass(shape, strong = true)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(mediaShape),
        ) {
            media()
            Text(
                chipText,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(LdRadius.chip))
                    .background(Color.White.copy(alpha = 0.92f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = LdType.Caption,
                color = LdColors.navy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = LdType.Title,
                color = LdColors.onGlass,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = LdType.Caption,
                color = LdColors.onGlassMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DrawMedia(thumb: Bitmap?, waiting: Boolean) {
    val sky = LdColors.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(sky.skyTop, sky.skyBottom))),
    ) {
        when {
            thumb != null && !thumb.isRecycled -> Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = stringResource(R.string.draw_partner),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            waiting -> Box(Modifier.fillMaxSize().background(LdColors.paper.copy(alpha = 0.72f)))
            else -> EmptyDrawing()
        }
    }
}

@Composable
private fun EmptyDrawing() {
    Box(Modifier.fillMaxSize()) {
        EmptyDrawingArt()
        LuIcon(
            Lu.Pencil,
            contentDescription = null,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            tint = LdColors.primary,
        )
    }
}

@Composable
private fun EmptyDrawingArt() {
    Canvas(Modifier.fillMaxSize()) {
        val inset = 16.dp.toPx()
        val corner = 4.dp.toPx()
        drawRoundRect(
            color = Color.White.copy(alpha = 0.92f),
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2f, size.height - inset * 2f),
            cornerRadius = CornerRadius(corner, corner),
        )
        val left = inset + 28.dp.toPx()
        val right = size.width - inset - 28.dp.toPx()
        val mid = size.height / 2f
        val path = Path().apply {
            moveTo(left, mid + 16.dp.toPx())
            quadraticBezierTo(left + (right - left) * 0.35f, mid - 36.dp.toPx(), (left + right) / 2f, mid)
            quadraticBezierTo(left + (right - left) * 0.75f, mid + 28.dp.toPx(), right, mid - 8.dp.toPx())
        }
        drawPath(
            path,
            color = LdColors.inkBlue,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun DuetMedia(playing: Boolean) {
    Canvas(Modifier.fillMaxSize().background(LdColors.navy)) {
        drawRect(
            Brush.linearGradient(
                listOf(Color(0xFF3A1220), LdColors.deep, LdColors.primary),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
        )
        val disc = Offset(size.width * 0.42f, size.height * 0.42f)
        val radius = size.minDimension * 0.30f
        drawCircle(LdColors.glow.copy(alpha = 0.34f), radius * 1.45f, disc)
        drawCircle(Color(0xFF12060C), radius, disc)
        drawCircle(
            Color.White.copy(alpha = 0.22f),
            radius * 0.72f,
            disc,
            style = Stroke(width = 1.4.dp.toPx()),
        )
        drawCircle(
            Color.White.copy(alpha = 0.14f),
            radius * 0.46f,
            disc,
            style = Stroke(width = 1.dp.toPx()),
        )
        drawCircle(LdColors.glow, radius * 0.16f, disc)
        drawCircle(Color(0xFF12060C), radius * 0.045f, disc)
        val note = size.minDimension * 0.20f
        val head = Offset(size.width * 0.66f, size.height * 0.58f)
        drawOval(
            color = Color.White.copy(alpha = 0.92f),
            topLeft = Offset(head.x - note * 0.22f, head.y - note * 0.12f),
            size = Size(note * 0.46f, note * 0.28f),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.92f),
            start = Offset(head.x + note * 0.22f, head.y),
            end = Offset(head.x + note * 0.22f, head.y - note * 0.85f),
            strokeWidth = note * 0.08f,
            cap = StrokeCap.Round,
        )
        val flag = Path().apply {
            moveTo(head.x + note * 0.22f, head.y - note * 0.85f)
            quadraticBezierTo(
                head.x + note * 0.85f,
                head.y - note * 0.62f,
                head.x + note * 0.28f,
                head.y - note * 0.28f,
            )
        }
        drawPath(
            flag,
            Color.White.copy(alpha = 0.92f),
            style = Stroke(width = note * 0.07f, cap = StrokeCap.Round),
        )
        val levels = if (playing) {
            floatArrayOf(0.55f, 0.95f, 0.42f, 0.78f, 0.5f)
        } else {
            floatArrayOf(0.28f, 0.46f, 0.22f, 0.36f, 0.24f)
        }
        val barW = size.width * 0.055f
        val gap = size.width * 0.03f
        val total = levels.size * barW + (levels.size - 1) * gap
        var x = (size.width - total) / 2f
        val base = size.height * 0.90f
        levels.forEach { level ->
            val barH = size.height * 0.20f * level
            drawRoundRect(
                color = Color.White.copy(alpha = 0.90f),
                topLeft = Offset(x, base - barH),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
            x += barW + gap
        }
    }
}
