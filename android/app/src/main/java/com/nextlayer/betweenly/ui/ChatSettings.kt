package com.nextlayer.betweenly.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.ChatStore
import com.nextlayer.betweenly.ChatWallpaperSync
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.SharedWall
import com.nextlayer.betweenly.Store
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Auto-save lives here. The shared background has its own screen. */
@Composable
fun ChatSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val revision by Store.changes.collectAsState()
    val autoSave = remember(revision) { Store.chatAutoSaveMedia(context) }
    val receipts = remember(revision) { Store.chatReadReceipts(context) }
    val hideActivity = remember(revision) { Store.chatHideActivity(context) }
    BackHandler(onBack = onBack)
    // Opaque page: the chat underneath must not show through the settings.
    Box(Modifier.fillMaxSize()) {
    AppBackground(Modifier.fillMaxSize())
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader(stringResource(R.string.chat_settings), onBack)
            ToggleRow(
                title = stringResource(R.string.chat_autosave),
                help = stringResource(R.string.chat_autosave_help),
                checked = autoSave,
                enabled = true,
                onCheckedChange = { Store.setChatAutoSaveMedia(context, it) },
            )
            ToggleRow(
                title = stringResource(R.string.chat_receipts),
                help = stringResource(R.string.chat_receipts_help),
                checked = receipts,
                enabled = true,
                onCheckedChange = { Store.setChatReadReceipts(context, it) },
            )
            ToggleRow(
                title = stringResource(R.string.chat_hide_activity),
                help = stringResource(R.string.chat_hide_activity_help),
                checked = hideActivity,
                enabled = true,
                onCheckedChange = { Store.setChatHideActivity(context, it) },
            )
        }
    }
    }
}

/**
 * Picks the background both people see. A choice is sent by [ChatWallpaperSync].
 */
@Composable
fun ChatBackground(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wall by ChatWallpaperSync.wall.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var previewKind by remember { mutableStateOf<String?>(null) }
    var previewColor by remember { mutableIntStateOf(0) }
    var previewJpeg by remember { mutableStateOf<ByteArray?>(null) }
    val shown = if (previewKind != null) {
        SharedWall(previewKind!!, previewColor, wall.ts, wall.revision + 1)
    } else {
        wall
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        busy = true
        failed = false
        scope.launch {
            val jpeg = try {
                withContext(Dispatchers.IO) { ChatMedia.shareWallpaperJpeg(context, uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (jpeg == null) {
                failed = true
                busy = false
                return@launch
            }
            previewKind = "image"
            previewColor = 0
            previewJpeg = jpeg
            val ok = try {
                ChatWallpaperSync.publishImage(context, jpeg)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                failed = true
                previewKind = null
                previewJpeg = null
            } else {
                previewKind = null
                previewJpeg = null
            }
            busy = false
        }
    }
    fun publish(kind: String, color: Int, block: suspend () -> Boolean) {
        if (busy) return
        busy = true
        failed = false
        previewKind = kind
        previewColor = color
        previewJpeg = null
        scope.launch {
            val ok = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                failed = true
                previewKind = null
            } else {
                previewKind = null
            }
            busy = false
        }
    }
    var wheel by remember { mutableStateOf<Boolean?>(null) }
    BackHandler(onBack = onBack)
    val swatch = if (shown.kind == "color") ChatTheme.wallpapers.getOrNull(shown.color) else null
    val dim = wallScrim(shown.kind, swatch)
    val look = rememberBubbleLook()
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // Preview only. Wallpaper is the back layer here. Controls live in the column below,
        // so no glass card or page fill is painted over the photo.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(0.42f)
                .clip(RoundedCornerShape(bottomStart = LdRadius.card, bottomEnd = LdRadius.card)),
        ) {
            AppBackground(Modifier.fillMaxSize())
            ChatWallBackdrop(
                wall = shown,
                modifier = Modifier.fillMaxSize(),
                previewJpeg = previewJpeg,
            )
            if (dim > 0f) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
            }
            Column(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChatSampleBubble(
                    stringResource(R.string.chat_theme_sample_theirs),
                    outgoing = false,
                    Modifier.align(Alignment.Start),
                )
                ChatSampleBubble(
                    stringResource(R.string.chat_theme_sample_mine),
                    outgoing = true,
                    Modifier.align(Alignment.End),
                )
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(40.dp).darkGlass(CircleShape))
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .darkGlass(RoundedCornerShape(20.dp)),
                )
                Box(Modifier.size(40.dp).darkGlass(CircleShape))
            }
        }
        Box(Modifier.weight(0.58f).fillMaxWidth()) {
            AppBackground(Modifier.fillMaxSize())
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ScreenHeader(stringResource(R.string.chat_theme), onBack)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .darkGlass(RoundedCornerShape(LdRadius.card))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CompositionLocalProvider(LocalOnGlass provides true) {
                        Text(
                            stringResource(R.string.chat_theme_background),
                            style = MaterialTheme.typography.titleMedium,
                            color = LdColors.onGlass,
                        )
                        WallRow(
                            label = stringResource(R.string.chat_wallpaper_default),
                            selected = shown.kind == "default",
                            enabled = !busy,
                            onClick = { publish("default", 0) { ChatWallpaperSync.publishDefault(context) } },
                        )
                        Text(
                            stringResource(R.string.chat_wallpaper),
                            style = MaterialTheme.typography.titleMedium,
                            color = LdColors.onGlass,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChatTheme.wallpapers.forEachIndexed { index, color ->
                                val selected = shown.kind == "color" && shown.color == index
                                val press = remember(index) { MutableInteractionSource() }
                                Box(
                                    modifier = Modifier
                                        .pressScale(press)
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            width = if (selected) 2.dp else 0.dp,
                                            color = if (selected) LdColors.white else LdColors.clear,
                                            shape = CircleShape,
                                        )
                                        .clickable(
                                            enabled = !busy,
                                            interactionSource = press,
                                            indication = null,
                                            onClick = {
                                                publish("color", index) { ChatWallpaperSync.publishColor(context, index) }
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) {
                                        LuIcon(
                                            Lu.Check,
                                            contentDescription = stringResource(R.string.chat_wallpaper_color, index + 1),
                                            tint = ChatTheme.onWallpaper(color),
                                            size = 20.dp,
                                        )
                                    }
                                }
                            }
                        }
                        WallRow(
                            label = stringResource(R.string.chat_wallpaper_photo),
                            selected = shown.kind == "image",
                            enabled = !busy,
                            onClick = {
                                failed = false
                                try {
                                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                } catch (_: Exception) {
                                    failed = true
                                }
                            },
                        )
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = LdColors.onGlass,
                            )
                        }
                        if (failed) {
                            Text(
                                stringResource(R.string.chat_wall_failed),
                                color = Color(0xFFFFB4AB),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .darkGlass(RoundedCornerShape(LdRadius.card))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CompositionLocalProvider(LocalOnGlass provides true) {
                        Text(
                            stringResource(R.string.chat_theme_colors),
                            style = MaterialTheme.typography.titleMedium,
                            color = LdColors.onGlass,
                        )
                        BubbleColorRow(
                            title = stringResource(R.string.chat_theme_mine),
                            builtin = LdColors.bubbleMineStart,
                            selected = look.mine,
                            glass = look.mineGlass,
                            onColor = { Store.setChatBubbleMine(context, it) },
                            onGlass = { Store.setChatBubbleMineGlass(context, it) },
                            onCustom = { wheel = true },
                        )
                        BubbleColorRow(
                            title = stringResource(R.string.chat_theme_theirs),
                            builtin = Color(0xFF2A1218),
                            selected = look.theirs,
                            glass = look.theirsGlass,
                            onColor = { Store.setChatBubbleTheirs(context, it) },
                            onGlass = { Store.setChatBubbleTheirsGlass(context, it) },
                            onCustom = { wheel = false },
                        )
                    }
                }
            }
        }
    }
    val picking = wheel
    if (picking != null) {
        val argb = if (picking) {
            if (look.mine == 0) look.minePaint().toArgb() else look.mine
        } else {
            if (look.theirs == 0) look.theirsPaint().toArgb() else look.theirs
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { wheel = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            CompositionLocalProvider(LocalOnGlass provides true) {
                Column(
                    Modifier
                        .padding(24.dp)
                        .fillMaxWidth()
                        .darkGlass(RoundedCornerShape(LdRadius.card)),
                ) {
                    DrawWheelBody(
                        colorArgb = argb,
                        onColor = { value ->
                            if (picking) Store.setChatBubbleMine(context, value) else Store.setChatBubbleTheirs(context, value)
                        },
                        onDone = { wheel = null },
                    )
                }
            }
        }
    }
}

/**
 * How much black sits on a wallpaper so controls stay readable without hiding it.
 * Default (the sky) needs none. Photos get a very light dim.
 */
fun wallScrim(kind: String, swatch: Color?): Float = when (kind) {
    "image" -> 0.08f
    "color" -> {
        val tone = swatch?.let { it.red * 0.2126f + it.green * 0.7152f + it.blue * 0.0722f } ?: 0.5f
        if (tone > 0.72f) 0.06f else 0f
    }
    else -> 0f
}

/**
 * The shared background behind the messages. Default is transparent so the
 * sky shows through. The chat and the picker add [wallScrim] themselves.
 */
@Composable
fun ChatWallBackdrop(
    wall: SharedWall,
    modifier: Modifier = Modifier,
    previewJpeg: ByteArray? = null,
) {
    val token = remember(wall.kind, wall.color, wall.revision, previewJpeg) {
        WallToken(wall.kind, wall.color, wall.revision, previewJpeg)
    }
    Crossfade(
        targetState = token,
        animationSpec = Motion.tween(220, Motion.easeInOut),
        modifier = modifier,
        label = "wall",
    ) { frame ->
        WallPaint(frame)
    }
}

private class WallToken(
    val kind: String,
    val color: Int,
    val revision: Int,
    val preview: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WallToken) return false
        return kind == other.kind && color == other.color && revision == other.revision && preview === other.preview
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + color
        result = 31 * result + revision
        result = 31 * result + System.identityHashCode(preview)
        return result
    }
}

@Composable
private fun WallPaint(token: WallToken) {
    val context = LocalContext.current
    var bitmap by remember(token.kind, token.revision, token.preview) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(token.kind, token.revision, token.preview) {
        if (token.kind != "image") {
            bitmap = null
            return@LaunchedEffect
        }
        val bytes = token.preview ?: withContext(Dispatchers.IO) {
            try {
                ChatStore.readWallpaper(context)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        bitmap = if (bytes == null || bytes.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                try {
                    ChatMedia.decodeForDisplay(bytes, 1080)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } catch (_: OutOfMemoryError) {
                    null
                }
            }
        }
    }
    val swatch = ChatTheme.wallpapers.getOrNull(token.color) ?: Color.Transparent
    Box(
        Modifier
            .fillMaxSize()
            .background(if (token.kind == "color") swatch else Color.Transparent),
    ) {
        val shown = bitmap
        if (token.kind == "image" && shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun BubbleColorRow(
    title: String,
    builtin: Color,
    selected: Int,
    glass: Boolean,
    onColor: (Int) -> Unit,
    onGlass: (Boolean) -> Unit,
    onCustom: () -> Unit,
) {
    val presets = listOf(
        0,
        LdColors.bubbleMineStart.toArgb(),
        LdColors.inkBlue.toArgb(),
        LdColors.inkPurple.toArgb(),
        LdColors.inkGreen.toArgb(),
        LdColors.navy.toArgb(),
        Color.White.toArgb(),
    )
    val customOn = selected != 0 && selected !in presets
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = LdColors.onGlass)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            presets.forEach { argb ->
                ColorDot(
                    color = if (argb == 0) builtin else Color(argb),
                    selected = selected == argb,
                    label = title,
                    onClick = { onColor(argb) },
                )
            }
            ColorDot(
                color = if (customOn) Color(selected) else LdColors.onGlass.copy(alpha = 0.2f),
                selected = customOn,
                label = stringResource(R.string.chat_theme_custom),
                onClick = onCustom,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StyleChip(
                label = stringResource(R.string.chat_bubble_glass),
                selected = glass,
                onClick = { onGlass(true) },
            )
            StyleChip(
                label = stringResource(R.string.chat_bubble_solid),
                selected = !glass,
                onClick = { onGlass(false) },
            )
        }
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(press)
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .border(if (selected) 2.dp else 1.dp, if (selected) LdColors.white else Color.White.copy(alpha = 0.35f), CircleShape)
            .clickable(interactionSource = press, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            LuIcon(Lu.Check, contentDescription = label, tint = contrastInk(color), size = 16.dp)
        }
    }
}

@Composable
private fun StyleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(LdRadius.chip)
    Text(
        label,
        color = if (selected) LdColors.navy else LdColors.onGlass,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .pressScale(press)
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(interactionSource = press, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun WallRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(press)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(LdRadius.tile))
            .clickable(
                enabled = enabled,
                interactionSource = press,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = surfaceInk(),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (selected) {
            LuIcon(Lu.Check, contentDescription = null, tint = LdColors.onGlass, size = 20.dp)
        }
    }
}
