@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.loviedowpie.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.util.Base64
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.loviedowpie.ChatMessage
import app.loviedowpie.ChatStore
import app.loviedowpie.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun chatDay(ts: Long): LocalDate =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()

fun chatTime(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(timeFormat)

@Composable
fun chatDayLabel(day: LocalDate, today: LocalDate): String {
    return when (day) {
        today -> stringResource(R.string.chat_today)
        today.minusDays(1) -> stringResource(R.string.chat_yesterday)
        else -> day.format(dayFormat.withLocale(Locale.getDefault()))
    }
}

fun formatBytes(size: Int): String {
    if (size < 0) return ""
    if (size < 1024) return "$size B"
    val kb = size / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.0f KB", kb)
    return String.format(Locale.getDefault(), "%.1f MB", kb / 1024.0)
}

@Composable
fun ChatDateLabel(label: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(
            shape = ChatTheme.chip,
            color = LdColors.colors.glassStrong,
            border = BorderStroke(1.dp, LdColors.colors.glassBorder),
            shadowElevation = 0.dp,
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = LdColors.colors.ink,
            )
        }
    }
}

/** Short label for a quoted message: the text, or the kind of attachment. */
@Composable
fun replySnippet(message: ChatMessage): String {
    val text = message.json.optString("text").trim()
    val type = message.json.optString("t")
    if (type == "chat" || type == "link") {
        if (text.isNotEmpty()) return text
        val title = message.json.optString("title").trim()
        if (title.isNotEmpty()) return title
        val url = message.json.optString("url").trim()
        if (url.isNotEmpty()) return url
    }
    if (type == "att") {
        if (text.isNotEmpty()) return text
        return when (message.json.optString("kind")) {
            "photo" -> stringResource(R.string.chat_reply_photo)
            "video" -> stringResource(R.string.chat_reply_video)
            "sticker" -> stringResource(R.string.chat_reply_sticker)
            "gif" -> stringResource(R.string.chat_reply_gif)
            else -> message.json.optString("name").trim().ifBlank { stringResource(R.string.chat_reply_file) }
        }
    }
    return text.ifBlank { stringResource(R.string.chat_reply_gone) }
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    ready: Boolean,
    downloadFailed: Boolean,
    progress: Float?,
    onLongPress: () -> Unit,
    onOpenPhoto: () -> Unit,
    onOpenExternal: () -> Unit,
    onOpenLink: (String) -> Unit,
    onRetryDownload: () -> Unit,
    quote: String? = null,
    onReply: () -> Unit = {},
) {
    val context = LocalContext.current
    val fid = message.json.optString("fid")
    LaunchedEffect(message.id, fid) {
        if (message.json.optString("t") != "att" || fid.length != 32) return@LaunchedEffect
        val has = withContext(Dispatchers.IO) { ChatStore.hasAttachment(context, message.id) }
        if (!has) ChatStore.fetch(context, message.id)
    }
    val type = message.json.optString("t")
    val kind = message.json.optString("kind")
    val bare = type == "att" && (kind == "sticker" || kind == "gif")
    val arrange = if (message.outgoing) Arrangement.End else Arrangement.Start
    var drag by remember(message.id) { mutableFloatStateOf(0f) }
    Row(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(drag.roundToInt().coerceIn(-140, 140), 0) }
            .swipeReply(onReply = onReply, onDrag = { drag = it }),
        horizontalArrangement = arrange,
    ) {
        Column(horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
            if (bare) {
                if (!quote.isNullOrBlank()) QuoteChip(quote, message.outgoing)
                BareImage(message, ready, downloadFailed, progress, onLongPress, onRetryDownload)
            } else {
                Surface(
                    shape = ChatTheme.bubble,
                    color = if (message.outgoing) ChatTheme.mine() else ChatTheme.theirs(),
                    border = if (message.outgoing) {
                        null
                    } else {
                        BorderStroke(1.dp, LdColors.colors.glassBorder)
                    },
                    shadowElevation = 0.dp,
                    modifier = Modifier.widthIn(max = 300.dp),
                ) {
                    Column(Modifier.padding(if (type == "att" && kind == "photo") 4.dp else 10.dp)) {
                        if (!quote.isNullOrBlank()) QuoteLine(quote, message.outgoing)
                        when (type) {
                            "link" -> LinkBody(message, onLongPress, onOpenLink)
                            "att" -> AttBody(
                                message, ready, downloadFailed, progress,
                                onLongPress, onOpenPhoto, onOpenExternal, onRetryDownload,
                            )
                            else -> TextBody(message, onLongPress)
                        }
                        Meta(message)
                    }
                }
            }
            val emoji = message.json.optString("rx")
            if (emoji.isNotEmpty()) {
                Surface(
                    shape = ChatTheme.chip,
                    color = LdColors.colors.glassStrong,
                    shadowElevation = 0.dp,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(
                        emoji,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun TextBody(message: ChatMessage, onLongPress: () -> Unit) {
    Text(
        message.json.optString("text"),
        color = if (message.outgoing) ChatTheme.onMine() else ChatTheme.onTheirs(),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress),
    )
}

@Composable
private fun LinkBody(message: ChatMessage, onLongPress: () -> Unit, onOpenLink: (String) -> Unit) {
    val url = message.json.optString("url")
    val title = message.json.optString("title")
    val desc = message.json.optString("desc")
    val text = message.json.optString("text")
    val host = hostOf(url)
    Column(
        modifier = Modifier.combinedClickable(
            onClick = { if (url.isNotEmpty()) onOpenLink(url) },
            onLongClick = onLongPress,
        ),
    ) {
        if (text.isNotBlank() && text != url) {
            Text(
                text,
                color = if (message.outgoing) ChatTheme.onMine() else ChatTheme.onTheirs(),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.size(6.dp))
        }
        val img = message.json.optString("img")
        if (img.isNotEmpty()) {
            ThumbImage(img, Modifier.fillMaxWidth().heightIn(max = 160.dp).clip(ChatTheme.bubble), stringResource(R.string.chat_photo_cd))
            Spacer(Modifier.size(6.dp))
        }
        val ink = ChatTheme.ink(message.outgoing)
        if (title.isNotBlank()) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (desc.isNotBlank()) {
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = ChatTheme.meta(message.outgoing),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (host.isNotBlank()) {
            Text(
                host,
                style = MaterialTheme.typography.labelMedium,
                color = if (message.outgoing) LdColors.white else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AttBody(
    message: ChatMessage,
    ready: Boolean,
    downloadFailed: Boolean,
    progress: Float?,
    onLongPress: () -> Unit,
    onOpenPhoto: () -> Unit,
    onOpenExternal: () -> Unit,
    onRetryDownload: () -> Unit,
) {
    val kind = message.json.optString("kind")
    when (kind) {
        "photo" -> Box(
            modifier = Modifier.combinedClickable(
                onClick = { if (ready) onOpenPhoto() },
                onLongClick = onLongPress,
            ),
        ) {
            MediaVisual(message, ready, animate = false, Modifier.widthIn(max = ChatTheme.photoMax).heightIn(max = 320.dp), stringResource(R.string.chat_photo_cd))
            DownloadOverlay(ready, downloadFailed, progress, onRetryDownload, Modifier.align(Alignment.Center))
        }
        "video" -> Box(
            modifier = Modifier.combinedClickable(
                onClick = { if (ready) onOpenExternal() },
                onLongClick = onLongPress,
            ),
        ) {
            MediaVisual(message, ready, animate = false, Modifier.widthIn(max = ChatTheme.photoMax).heightIn(max = 220.dp), stringResource(R.string.chat_video_cd))
            if (ready) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.chat_video_cd),
                    modifier = Modifier.align(Alignment.Center).size(42.dp),
                    tint = ChatTheme.viewerContent,
                )
            }
            DownloadOverlay(ready, downloadFailed, progress, onRetryDownload, Modifier.align(Alignment.Center))
        }
        else -> FileBody(message, ready, downloadFailed, progress, onLongPress, onOpenExternal, onRetryDownload)
    }
    val caption = message.json.optString("text")
    if (caption.isNotBlank()) {
        Text(
            caption,
            modifier = Modifier.padding(top = 4.dp),
            color = if (message.outgoing) ChatTheme.onMine() else ChatTheme.onTheirs(),
        )
    }
}

@Composable
private fun FileBody(
    message: ChatMessage,
    ready: Boolean,
    downloadFailed: Boolean,
    progress: Float?,
    onLongPress: () -> Unit,
    onOpenExternal: () -> Unit,
    onRetryDownload: () -> Unit,
) {
    val name = message.json.optString("name").ifBlank { stringResource(R.string.chat_file) }
    val size = message.json.optInt("size", -1)
    Column(
        modifier = Modifier.combinedClickable(
            onClick = { if (ready) onOpenExternal() else if (downloadFailed) onRetryDownload() },
            onLongClick = onLongPress,
        ),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            color = ChatTheme.ink(message.outgoing),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            formatBytes(size),
            style = MaterialTheme.typography.bodySmall,
            color = ChatTheme.meta(message.outgoing),
        )
        if (!ready) {
            if (downloadFailed) {
                Text(
                    stringResource(R.string.chat_download_failed),
                    color = if (message.outgoing) LdColors.white else MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetryDownload) { Text(stringResource(R.string.chat_download_retry)) }
            } else {
                val fraction = progress
                if (fraction != null && fraction > 0f) {
                    CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun BareImage(
    message: ChatMessage,
    ready: Boolean,
    downloadFailed: Boolean,
    progress: Float?,
    onLongPress: () -> Unit,
    onRetryDownload: () -> Unit,
) {
    val mime = message.json.optString("mime")
    val animate = mime == "image/gif" || mime == "image/webp"
    val desc = stringResource(if (message.json.optString("kind") == "gif") R.string.chat_gif_cd else R.string.chat_sticker_cd)
    Column(horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
        Box(
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress),
        ) {
            MediaVisual(
                message,
                ready,
                animate,
                Modifier.widthIn(max = ChatTheme.stickerMax).heightIn(max = ChatTheme.stickerMax),
                desc,
            )
            DownloadOverlay(ready, downloadFailed, progress, onRetryDownload, Modifier.align(Alignment.Center))
        }
        Meta(message)
    }
}

@Composable
private fun MediaVisual(
    message: ChatMessage,
    ready: Boolean,
    animate: Boolean,
    modifier: Modifier,
    desc: String,
) {
    if (ready) {
        AttachmentVisual(message.id, animate, modifier, desc)
    } else {
        val thumb = message.json.optString("thumb")
        if (thumb.isNotEmpty()) {
            ThumbImage(thumb, modifier, desc)
        } else {
            Box(modifier.heightIn(min = 72.dp).widthIn(min = 96.dp))
        }
    }
}

@Composable
private fun DownloadOverlay(
    ready: Boolean,
    failed: Boolean,
    progress: Float?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (ready) return
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.padding(6.dp)) {
        if (failed) {
            Text(stringResource(R.string.chat_download_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.chat_download_retry)) }
        } else {
            val fraction = progress
            if (fraction != null && fraction > 0f) {
                CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            } else {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun Meta(message: ChatMessage) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.padding(top = 2.dp)) {
        Text(chatTime(message.ts), style = MaterialTheme.typography.labelSmall, color = ChatTheme.meta(message.outgoing))
        if (message.outgoing) {
            Spacer(Modifier.size(4.dp))
            StatusTick(message.status, outgoing = true)
        }
    }
}

@Composable
private fun StatusTick(status: String, outgoing: Boolean) {
    val tint = ChatTheme.meta(outgoing)
    when (status) {
        ChatStore.SENT -> Icon(
            Icons.Filled.Check,
            contentDescription = stringResource(R.string.chat_status_sent),
            modifier = Modifier.size(14.dp),
            tint = tint,
        )
        ChatStore.FAILED -> Icon(
            Icons.Filled.Warning,
            contentDescription = stringResource(R.string.chat_status_failed),
            modifier = Modifier.size(14.dp),
            tint = if (outgoing) LdColors.white else MaterialTheme.colorScheme.error,
        )
        else -> Text(
            stringResource(R.string.chat_status_sending),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

@Composable
private fun AttachmentVisual(id: String, animate: Boolean, modifier: Modifier, desc: String) {
    val context = LocalContext.current
    var bytes by remember(id) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(id) {
        bytes = withContext(Dispatchers.IO) { ChatStore.readAttachment(context, id) }
    }
    val data = bytes ?: return
    if (animate) AnimBytes(id, data, desc, modifier) else StillBytes(id, data, desc, modifier)
}

@Composable
private fun StillBytes(id: String, bytes: ByteArray, desc: String, modifier: Modifier) {
    var bitmap by remember(id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(id) {
        bitmap = withContext(Dispatchers.IO) { ChatMedia.decodeForDisplay(bytes, 1080) }
    }
    val shown = bitmap ?: return
    Image(
        bitmap = shown.asImageBitmap(),
        contentDescription = desc,
        modifier = modifier.clip(ChatTheme.bubble),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun ThumbImage(b64: String, modifier: Modifier, desc: String) {
    var bitmap by remember(b64) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(b64) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                ChatMedia.decodeForDisplay(bytes, 480)
            } catch (_: Exception) {
                null
            }
        }
    }
    val shown = bitmap ?: return
    Image(
        bitmap = shown.asImageBitmap(),
        contentDescription = desc,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun AnimBytes(id: String, bytes: ByteArray, desc: String, modifier: Modifier) {
    val context = LocalContext.current
    var drawable by remember(id) { mutableStateOf<android.graphics.drawable.Drawable?>(null) }
    LaunchedEffect(id) {
        drawable = withContext(Dispatchers.IO) {
            try {
                // ImageDecoder.createSource(ByteBuffer) is API 31. A cache file works from API 28.
                val dir = File(context.cacheDir, "chat-anim")
                if (!dir.exists() && !dir.mkdirs()) return@withContext null
                val file = File(dir, "$id.img")
                if (!file.isFile || file.length() != bytes.size.toLong()) file.writeBytes(bytes)
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        }
        val decoded = drawable
        if (decoded is AnimatedImageDrawable) {
            decoded.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            decoded.start()
        }
    }
    DisposableEffect(drawable) {
        onDispose {
            (drawable as? AnimatedImageDrawable)?.stop()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            ImageView(viewContext).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            }
        },
        update = { view ->
            view.contentDescription = desc
            view.setImageDrawable(drawable)
            (drawable as? AnimatedImageDrawable)?.start()
        },
        onRelease = { view ->
            (view.drawable as? AnimatedImageDrawable)?.stop()
            view.setImageDrawable(null)
        },
    )
}

@Composable
private fun QuoteLine(text: String, outgoing: Boolean) {
    Text(
        text,
        modifier = Modifier.padding(bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = ChatTheme.meta(outgoing),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun QuoteChip(text: String, outgoing: Boolean) {
    Surface(
        shape = ChatTheme.chip,
        color = if (outgoing) ChatTheme.mine() else ChatTheme.theirs(),
        shadowElevation = 0.dp,
        modifier = Modifier.padding(bottom = 4.dp).widthIn(max = 300.dp),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = ChatTheme.meta(outgoing),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Horizontal drag starts a reply. The gesture watches the initial pass and
 * does not consume the pointer, so a long-press and vertical scrolling still win.
 */
private fun Modifier.swipeReply(onReply: () -> Unit, onDrag: (Float) -> Unit): Modifier {
    return pointerInput(Unit) {
        val threshold = 72.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var total = 0f
            var fired = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                total += change.position.x - change.previousPosition.x
                onDrag(total.coerceIn(-140f, 140f))
                if (!fired && abs(total) >= threshold) {
                    fired = true
                    onReply()
                }
            }
            onDrag(0f)
        }
    }
}

private fun hostOf(url: String): String {
    return try {
        java.net.URL(url).host
    } catch (_: Exception) {
        ""
    }
}
