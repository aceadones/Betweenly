package com.nextlayer.betweenly.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    BackHandler(onBack = onBack)
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
    BackHandler(onBack = onBack)
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
            ScreenHeader(stringResource(R.string.chat_background), onBack)
            Text(stringResource(R.string.chat_wall_preview), style = MaterialTheme.typography.titleMedium)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(LdRadius.card)),
            ) {
                AppBackground(Modifier.fillMaxSize())
                ChatWallBackdrop(
                    wall = shown,
                    modifier = Modifier.fillMaxSize(),
                    previewJpeg = previewJpeg,
                )
            }
            WallRow(
                label = stringResource(R.string.chat_wallpaper_default),
                selected = shown.kind == "default",
                enabled = !busy,
                onClick = { publish("default", 0) { ChatWallpaperSync.publishDefault(context) } },
            )
            Text(stringResource(R.string.chat_wallpaper), style = MaterialTheme.typography.titleMedium)
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
                                color = LdColors.primary,
                                shape = CircleShape,
                            )
                            .clickable(
                                enabled = !busy,
                                interactionSource = press,
                                indication = null,
                                onClick = { publish("color", index) { ChatWallpaperSync.publishColor(context, index) } },
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
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            if (failed) {
                Text(
                    stringResource(R.string.chat_wall_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * The shared background behind the messages. Default is transparent so the
 * sky shows through. A photo gets a light scrim so bubbles stay readable.
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
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.12f)))
        }
    }
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
            color = LdColors.colors.ink,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (selected) {
            LuIcon(Lu.Check, contentDescription = null, tint = LdColors.primary, size = 20.dp)
        }
    }
}
