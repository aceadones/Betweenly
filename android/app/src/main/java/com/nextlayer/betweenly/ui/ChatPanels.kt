package com.nextlayer.betweenly.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.ChatMessage
import com.nextlayer.betweenly.ChatStore
import com.nextlayer.betweenly.DrawStore
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.SavedDrawing
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** One attachment waiting for the preview sheet. Not uploaded yet. */
class ChatDraft(
    val key: Long,
    val uri: Uri?,
    val bytes: ByteArray?,
    val mime: String,
    val name: String,
    val video: Boolean,
    val asFile: Boolean,
    val fromCamera: Boolean,
)

private enum class PanelTab { Gif, Kitchen, Stickers }

@Composable
fun ChatAttachPreview(
    items: List<ChatDraft>,
    selected: Int,
    caption: String,
    onCaption: (String) -> Unit,
    onSelect: (Int) -> Unit,
    onRemove: () -> Unit,
    onAdd: () -> Unit,
    onRetake: (() -> Unit)?,
    onSend: () -> Unit,
    onClose: () -> Unit,
) {
    val item = items.getOrNull(selected) ?: items.lastOrNull()
    BackHandler(onBack = onClose)
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF010090C))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(onClose, Lu.X, stringResource(R.string.chat_close), solid = true)
            Spacer(Modifier.weight(1f))
            if (onRetake != null) {
                GlassIconButton(onRetake, Lu.RefreshCw, stringResource(R.string.chat_preview_retake), solid = true)
            }
            GlassIconButton(onRemove, Lu.Trash2, stringResource(R.string.chat_preview_remove), solid = true)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (item == null) {
                Text(stringResource(R.string.chat_attach_failed), color = LdColors.onGlassMuted)
            } else if (item.asFile && !item.mime.startsWith("image/")) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LuIcon(Lu.FileText, contentDescription = null, tint = LdColors.onGlass, size = 48.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(item.name, color = LdColors.onGlass, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            } else {
                DraftPicture(item, Modifier.fillMaxSize())
            }
        }
        BasicTextField(
            value = caption,
            onValueChange = { onCaption(it.take(2_000)) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = LdColors.onGlass),
            cursorBrush = SolidColor(LdColors.onGlass),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .ldGlass(RoundedCornerShape(LdRadius.chip), solid = true)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                if (caption.isEmpty()) {
                    Text(stringResource(R.string.chat_preview_caption), color = LdColors.onGlassMuted)
                }
                inner()
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, draft ->
                    DraftThumb(
                        draft = draft,
                        selected = index == selected,
                        onClick = { onSelect(index) },
                    )
                }
                val addPress = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .size(52.dp)
                        .pressScale(addPress)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, LdColors.onGlass.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .clickable(interactionSource = addPress, indication = null, onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) {
                    LuIcon(Lu.Plus, contentDescription = stringResource(R.string.chat_preview_add), tint = LdColors.onGlass)
                }
            }
            Spacer(Modifier.width(8.dp))
            val sendPress = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(48.dp)
                    .pressScale(sendPress)
                    .clip(CircleShape)
                    .background(LdColors.primary)
                    .clickable(interactionSource = sendPress, indication = null, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                LuIcon(Lu.Send, contentDescription = stringResource(R.string.chat_send), tint = LdColors.white)
            }
        }
    }
}

@Composable
private fun DraftThumb(draft: ChatDraft, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(52.dp)
            .clip(shape)
            .then(if (selected) Modifier.border(2.dp, LdColors.white, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (draft.asFile && !draft.mime.startsWith("image/")) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                LuIcon(Lu.FileText, contentDescription = draft.name, tint = LdColors.onGlass, size = 22.dp)
            }
        } else {
            DraftPicture(draft, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun DraftPicture(draft: ChatDraft, modifier: Modifier) {
    val context = LocalContext.current
    var bitmap by remember(draft.key) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(draft.key) {
        bitmap = withContext(Dispatchers.IO) { draftBitmap(context, draft) }
    }
    val shown = bitmap
    if (shown != null) {
        Image(
            bitmap = shown.asImageBitmap(),
            contentDescription = draft.name,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    } else if (draft.video) {
        Box(modifier, contentAlignment = Alignment.Center) {
            LuIcon(Lu.Play, contentDescription = stringResource(R.string.chat_video_cd), tint = LdColors.white, size = 42.dp)
        }
    }
}

@Composable
fun ChatStickerPanel(
    messages: List<ChatMessage>,
    onGif: (ByteArray) -> Unit,
    onSticker: (ByteArray, String) -> Unit,
    onClose: () -> Unit,
) {
    var tab by remember { mutableStateOf(PanelTab.Gif) }
    BackHandler(onBack = onClose)
    Column(
        Modifier
            .fillMaxWidth()
            .height(320.dp)
            .ldGlass(RoundedCornerShape(topStart = LdRadius.card, topEnd = LdRadius.card), solid = true),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PanelChip(stringResource(R.string.chat_tab_gif), tab == PanelTab.Gif) { tab = PanelTab.Gif }
            PanelChip(stringResource(R.string.chat_tab_kitchen), tab == PanelTab.Kitchen) { tab = PanelTab.Kitchen }
            PanelChip(stringResource(R.string.chat_tab_stickers), tab == PanelTab.Stickers) { tab = PanelTab.Stickers }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                PanelTab.Gif -> GifTab(onGif)
                PanelTab.Kitchen -> KitchenTab(onSticker)
                PanelTab.Stickers -> StickerTab(messages, onSticker)
            }
        }
    }
}

@Composable
private fun PanelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val press = remember(label) { MutableInteractionSource() }
    Text(
        label,
        modifier = Modifier
            .pressScale(press)
            .clip(RoundedCornerShape(LdRadius.chip))
            .background(if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(interactionSource = press, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = if (selected) LdColors.onGlass else LdColors.onGlassMuted,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun GifTab(onGif: (ByteArray) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<TenorGif>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val ready = ChatRemote.tenorReady()
    LaunchedEffect(query, ready) {
        if (!ready) return@LaunchedEffect
        delay(if (query.isBlank()) 0 else 320)
        busy = true
        failed = false
        val loaded = try {
            if (query.isBlank()) ChatRemote.featured() else ChatRemote.search(query)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        busy = false
        if (loaded == null) failed = true else hits = loaded
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        BasicTextField(
            value = query,
            onValueChange = { query = it.take(40) },
            enabled = ready,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = LdColors.onGlass),
            cursorBrush = SolidColor(LdColors.onGlass),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(LdRadius.chip))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(stringResource(R.string.chat_gif_search), color = LdColors.onGlassMuted)
                }
                inner()
            },
        )
        when {
            !ready -> Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.chat_gif_unset),
                    color = LdColors.onGlass,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.chat_gif_keyboard),
                    color = LdColors.onGlassMuted,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            failed -> Text(
                stringResource(R.string.chat_gif_failed),
                modifier = Modifier.padding(top = 24.dp).fillMaxWidth(),
                color = LdColors.onGlassMuted,
                textAlign = TextAlign.Center,
            )
            !busy && hits.isEmpty() -> Text(
                stringResource(R.string.chat_gif_empty),
                modifier = Modifier.padding(top = 24.dp).fillMaxWidth(),
                color = LdColors.onGlassMuted,
                textAlign = TextAlign.Center,
            )
            else -> Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(hits, key = { it.id + it.previewUrl }) { hit ->
                        GifCell(hit) {
                            scope.launch {
                                val bytes = try {
                                    ChatRemote.bytes(hit.sendUrl, ChatMedia.MAX_BYTES)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    null
                                }
                                if (bytes == null) failed = true else onGif(bytes)
                            }
                        }
                    }
                }
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).size(22.dp),
                        strokeWidth = 2.dp,
                        color = LdColors.onGlass,
                    )
                }
            }
        }
    }
}

@Composable
private fun GifCell(hit: TenorGif, onClick: () -> Unit) {
    var bitmap by remember(hit.previewUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(hit.previewUrl) {
        val bytes = try {
            ChatRemote.bytes(hit.previewUrl, 400 * 1024)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        bitmap = bytes?.let { stillGif(it) }
    }
    val shown = bitmap
    Box(
        Modifier
            .height(88.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = stringResource(R.string.chat_gif_cd),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun KitchenTab(onSticker: (ByteArray, String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var second by remember { mutableStateOf<String?>(null) }
    var combo by remember { mutableStateOf<Bitmap?>(null) }
    var comboBytes by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf(false) }
    LaunchedEffect(first, second) {
        val left = first
        val right = second
        combo = null
        comboBytes = null
        missing = false
        if (left == null || right == null) return@LaunchedEffect
        busy = true
        val bytes = try {
            ChatRemote.kitchen(left, right)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        busy = false
        if (bytes == null) {
            missing = true
        } else {
            comboBytes = bytes
            combo = stillGif(bytes)
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(first ?: "·", style = MaterialTheme.typography.headlineMedium)
            Text("  +  ", color = LdColors.onGlassMuted)
            Text(second ?: "·", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = LdColors.onGlass)
            }
            val png = combo
            val raw = comboBytes
            if (png != null && raw != null) {
                Image(
                    bitmap = png.asImageBitmap(),
                    contentDescription = stringResource(R.string.chat_kitchen_send),
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSticker(raw, "image/png") },
                )
            }
        }
        if (first == null || second == null) {
            Text(stringResource(R.string.chat_kitchen_pick), color = LdColors.onGlassMuted, style = MaterialTheme.typography.bodySmall)
        } else if (missing) {
            Text(stringResource(R.string.chat_kitchen_none), color = LdColors.onGlassMuted, style = MaterialTheme.typography.bodySmall)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(48.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
        ) {
            items(chatEmoji) { emoji ->
                Box(
                    Modifier
                        .size(48.dp)
                        .clickable {
                            if (first == null || (first != null && second != null)) {
                                first = emoji
                                second = null
                            } else {
                                second = emoji
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(emoji, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
private fun StickerTab(messages: List<ChatMessage>, onSticker: (ByteArray, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recent = remember(messages) {
        messages.filter { it.json.optString("t") == "att" && it.json.optString("kind") == "sticker" }.take(24)
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(72.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (recent.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.chat_stickers_empty),
                    color = LdColors.onGlassMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        items(recent, key = { it.id }) { message ->
            Box(
                Modifier
                    .height(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        scope.launch {
                            val bytes = withContext(Dispatchers.IO) { ChatStore.readAttachment(context, message.id) }
                            if (bytes != null) {
                                val mime = message.json.optString("mime").ifBlank { "image/png" }
                                onSticker(bytes, mime)
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                ChatFrame(
                    message.id,
                    animate = message.json.optString("mime") == "image/gif" || message.json.optString("mime") == "image/webp",
                    maxEdge = 240,
                    modifier = Modifier.fillMaxSize(),
                    desc = stringResource(R.string.chat_sticker_cd),
                    scale = ContentScale.Fit,
                )
            }
        }
        items(chatEmoji) { emoji ->
            Box(
                Modifier.size(56.dp).clickable {
                    val png = emojiPng(emoji) ?: return@clickable
                    onSticker(png, "image/png")
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}

@Composable
fun ChatActivitiesSheet(onPick: (File) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val rev by DrawStore.changes.collectAsState()
    var files by remember { mutableStateOf<List<SavedDrawing>>(emptyList()) }
    LaunchedEffect(rev) {
        files = withContext(Dispatchers.IO) { DrawStore.list(context) }
    }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.30f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .ldGlass(RoundedCornerShape(topStart = LdRadius.card, topEnd = LdRadius.card), solid = true)
                .padding(16.dp),
        ) {
            Text(stringResource(R.string.chat_activities), color = LdColors.onGlass, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (files.isEmpty()) {
                Text(stringResource(R.string.chat_activities_empty), color = LdColors.onGlassMuted)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 380.dp),
                ) {
                    items(files, key = { it.ts }) { drawing ->
                        ActivityCell(drawing.file) { onPick(drawing.file) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityCell(file: File, onClick: () -> Unit) {
    var bitmap by remember(file.path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.path) {
        bitmap = withContext(Dispatchers.IO) { sampleFile(file, 360) }
    }
    val shown = bitmap
    Box(
        Modifier
            .height(108.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = stringResource(R.string.chat_activities),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private fun draftBitmap(context: android.content.Context, draft: ChatDraft): Bitmap? {
    return try {
        when {
            draft.video && draft.uri != null -> videoFrame(context, draft.uri)
            draft.bytes != null -> ChatMedia.decodeForDisplay(draft.bytes, 1080)
            draft.uri != null -> sampleUri(context, draft.uri, 1080)
            else -> null
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

private fun sampleUri(context: android.content.Context, uri: Uri, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val edge = maxOf(bounds.outWidth, bounds.outHeight)
    while (edge / (sample * 2) > maxEdge) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}

private fun sampleFile(file: File, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    val edge = maxOf(bounds.outWidth, bounds.outHeight)
    while (edge / (sample * 2) > maxEdge) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun videoFrame(context: android.content.Context, uri: Uri): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (_: Exception) {
        null
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
            // The frame is already taken.
        }
    }
}

private fun stillGif(bytes: ByteArray): Bitmap? {
    return try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

/** Draws one emoji onto a transparent PNG so it can travel as a sticker. */
internal fun emojiPng(emoji: String): ByteArray? {
    val size = 256
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    return try {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 180f
        }
        val y = size / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(emoji, size / 2f, y, paint)
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
        out.toByteArray()
    } catch (_: Exception) {
        null
    } finally {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}
