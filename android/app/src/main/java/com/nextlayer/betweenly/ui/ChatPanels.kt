package com.nextlayer.betweenly.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.runtime.snapshotFlow
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
import com.nextlayer.betweenly.DrawStore
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.SavedDrawing
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val kind: String? = null,
)

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
    onEdit: ((ChatDraft) -> Unit)? = null,
) {
    val item = items.getOrNull(selected) ?: items.lastOrNull()
    BackHandler(onBack = onClose)
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF10090C))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(onClose, Lu.X, stringResource(R.string.chat_close), solid = true)
            Spacer(Modifier.weight(1f))
            if (onRetake != null) {
                GlassIconButton(onRetake, Lu.RefreshCw, stringResource(R.string.chat_preview_retake), solid = true)
            }
            if (onEdit != null && item != null && !item.video && !item.asFile &&
                item.mime.startsWith("image/") && item.mime != "image/gif" && item.kind != "sticker" && item.kind != "gif"
            ) {
                GlassIconButton({ onEdit(item) }, Lu.Pencil, stringResource(R.string.photo_edit), solid = true)
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

/** GIF search only. Gboard stickers arrive through the message field, not this panel. */
@Composable
fun ChatStickerPanel(
    onGif: (ByteArray) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Column(
        Modifier
            .fillMaxWidth()
            .height(340.dp)
            .ldGlass(RoundedCornerShape(topStart = LdRadius.card, topEnd = LdRadius.card), solid = true),
    ) {
        GifTab(onGif, Modifier.weight(1f).fillMaxWidth())
    }
}

private class GifList(val hits: List<GifHit>, val page: Int, val hasNext: Boolean)

@Composable
private fun GifTab(onGif: (ByteArray) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val provider = ChatRemote.gifs
    val ready = provider.ready()
    var query by remember { mutableStateOf("") }
    var list by remember { mutableStateOf(GifList(emptyList(), 1, false)) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    LaunchedEffect(query, ready) {
        if (!ready) return@LaunchedEffect
        delay(if (query.isBlank()) 0 else 350)
        busy = true
        failed = false
        val loaded = try {
            provider.load(context, query, 1)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        busy = false
        if (loaded == null) {
            failed = true
            list = GifList(emptyList(), 1, false)
        } else {
            list = GifList(loaded.hits, 1, loaded.hasNext)
        }
    }
    LaunchedEffect(grid, list.hasNext, list.hits.size, ready) {
        if (!ready || !list.hasNext) return@LaunchedEffect
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                val shown = list
                if (!shown.hasNext || busy || last < shown.hits.size - 8) return@collect
                busy = true
                val loaded = try {
                    provider.load(context, query, shown.page + 1)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                busy = false
                if (loaded == null || loaded.hits.isEmpty()) {
                    list = GifList(shown.hits, shown.page, false)
                } else {
                    val seen = shown.hits.map { it.id }.toHashSet()
                    list = GifList(
                        shown.hits + loaded.hits.filter { it.id !in seen },
                        shown.page + 1,
                        loaded.hasNext,
                    )
                }
            }
    }
    val hits = list.hits
    Column(modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
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
                    state = grid,
                    contentPadding = PaddingValues(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(hits, key = { it.id + it.previewUrl }) { hit ->
                        GifCell(hit) {
                            scope.launch {
                                val bytes = try {
                                    provider.bytes(hit.sendUrl, ChatMedia.MAX_BYTES)
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
        Text(
            stringResource(R.string.chat_gif_credit),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
            color = LdColors.onGlassMuted,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun GifCell(hit: GifHit, onClick: () -> Unit) {
    var bitmap by remember(hit.previewUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(hit.previewUrl) {
        val bytes = try {
            ChatRemote.gifs.bytes(hit.previewUrl, 400 * 1024)
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

internal fun sampleUri(context: android.content.Context, uri: Uri, maxEdge: Int): Bitmap? {
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
