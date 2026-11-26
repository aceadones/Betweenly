@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nextlayer.betweenly.ui

import android.view.HapticFeedbackConstants
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.util.Base64
import android.util.LruCache
import android.widget.ImageView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nextlayer.betweenly.ChatMessage
import com.nextlayer.betweenly.ChatStore
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val stampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")

private val stillCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
}

fun chatDay(ts: Long): LocalDate =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()

fun chatTime(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(timeFormat)

fun chatStamp(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(stampFormat)

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

fun isViewerMedia(message: ChatMessage): Boolean {
    if (message.json.optString("t") != "att") return false
    return when (message.json.optString("kind")) {
        "photo", "video", "sticker", "gif" -> true
        else -> false
    }
}

@Composable
fun ChatDateLabel(label: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Box(
            Modifier
                .darkGlass(ChatTheme.chip)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = LdColors.onGlass,
            )
        }
    }
}

@Composable
fun ChatSystemNote(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Box(
            Modifier
                .darkGlass(ChatTheme.chip)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = LdColors.onGlassMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Short label for a quoted message: the text, or the kind of attachment. */
@Composable
fun replySnippet(message: ChatMessage): String {
    val type = message.json.optString("t")
    if (type == "gone") {
        return stringResource(R.string.chat_deleted_them)
    }
    if (type == "note") return message.json.optString("text").ifBlank { stringResource(R.string.chat_reply_gone) }
    val text = message.json.optString("text").trim()
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
            "voice" -> stringResource(R.string.chat_reply_voice)
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
    onLongPress: (row: Rect, bubble: Rect) -> Unit,
    onOpenVisual: (Rect) -> Unit,
    onOpenExternal: () -> Unit,
    onOpenLink: (String) -> Unit,
    onRetryDownload: () -> Unit,
    onToggleReaction: (String) -> Unit,
    modifier: Modifier = Modifier,
    quote: String? = null,
    onReply: () -> Unit = {},
    lit: Boolean = false,
) {
    val context = LocalContext.current
    val fid = message.json.optString("fid")
    val type = message.json.optString("t")
    LaunchedEffect(message.id, fid, type) {
        if (type != "att" || fid.length != 32) return@LaunchedEffect
        val has = withContext(Dispatchers.IO) { ChatStore.hasAttachment(context, message.id) }
        if (!has) ChatStore.fetch(context, message.id)
    }
    val kind = message.json.optString("kind")
    val bare = type == "att" && (kind == "sticker" || kind == "gif")
    val badges = if (type == "gone" || type == "note") emptyList() else ChatReactions.badges(message.json)
    val arrange = if (message.outgoing) Arrangement.End else Arrangement.Start
    val rowHolder = remember(message.id) { arrayOf(Rect.Zero) }
    val bubbleHolder = remember(message.id) { arrayOf(Rect.Zero) }
    val shift = remember(message.id) { Animatable(0f) }
    val side = if (message.outgoing) {
        Modifier.padding(start = 48.dp)
    } else {
        Modifier.padding(end = 48.dp)
    }
    Row(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { rowHolder[0] = it.boundsInWindow() }
            .graphicsLayer { translationX = shift.value }
            .then(
                if (lit || type == "gone" || type == "note") {
                    Modifier
                } else {
                    Modifier.swipeReply(shift, onReply)
                },
            )
            .then(side),
        horizontalArrangement = arrange,
    ) {
        Column(
            Modifier.padding(bottom = if (badges.isNotEmpty()) 16.dp else 0.dp),
            horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start,
        ) {
            Box {
                Column(horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
            if (type == "gone") {
                Tombstone(message, bubbleHolder) {
                    onLongPress(rowHolder[0], bubbleHolder[0])
                }
            } else if (bare) {
                if (!quote.isNullOrBlank()) QuoteChip(quote, message.outgoing)
                BareImage(
                    message, ready, downloadFailed, progress,
                    onLongPress = { onLongPress(rowHolder[0], bubbleHolder[0]) },
                    onOpen = { onOpenVisual(bubbleHolder[0]) },
                    onRetryDownload = onRetryDownload,
                    onPosition = { bubbleHolder[0] = it },
                    lit = lit,
                )
            } else {
                val shape = ChatTheme.bubble
                Box(
                    Modifier
                        .widthIn(max = 300.dp)
                        .onGloballyPositioned { bubbleHolder[0] = it.boundsInWindow() }
                        .bubblePlate(message.outgoing, shape, lit),
                ) {
                    val media = type == "att" && (kind == "photo" || kind == "video")
                    if (media) {
                        Column {
                            if (!quote.isNullOrBlank()) {
                                QuoteLine(
                                    quote,
                                    message.outgoing,
                                    Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                                )
                            }
                            Box(Modifier.padding(4.dp)) {
                                AttBody(
                                    message, ready, downloadFailed, progress,
                                    onLongPress = { onLongPress(rowHolder[0], bubbleHolder[0]) },
                                    onOpenPhoto = { onOpenVisual(bubbleHolder[0]) },
                                    onOpenExternal = onOpenExternal,
                                    onRetryDownload = onRetryDownload,
                                    insetMedia = true,
                                )
                            }
                            val caption = message.json.optString("text")
                            if (caption.isNotBlank()) {
                                Text(
                                    caption,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = ChatTheme.ink(message.outgoing),
                                )
                            }
                            Meta(message, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                        }
                    } else {
                        Column(
                            Modifier
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = { onLongPress(rowHolder[0], bubbleHolder[0]) },
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            if (!quote.isNullOrBlank()) QuoteLine(quote, message.outgoing, Modifier)
                            when (type) {
                                "link" -> LinkBody(message, { onLongPress(rowHolder[0], bubbleHolder[0]) }, onOpenLink)
                                "att" -> AttBody(
                                    message, ready, downloadFailed, progress,
                                    onLongPress = { onLongPress(rowHolder[0], bubbleHolder[0]) },
                                    onOpenPhoto = { onOpenVisual(bubbleHolder[0]) },
                                    onOpenExternal = onOpenExternal,
                                    onRetryDownload = onRetryDownload,
                                    insetMedia = false,
                                )
                                else -> TextBody(message)
                            }
                            Meta(message, Modifier)
                        }
                    }
                }
            }
                }
                if (badges.isNotEmpty() && type != "gone") {
                    ReactionBadges(
                        badges = badges,
                        outgoing = message.outgoing,
                        onToggle = onToggleReaction,
                        modifier = Modifier
                            .align(if (message.outgoing) Alignment.BottomEnd else Alignment.BottomStart)
                            .offset(y = 14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Tombstone(message: ChatMessage, holder: Array<Rect>, onLongPress: () -> Unit) {
    val label = stringResource(R.string.chat_deleted_them)
    Column(
        Modifier
            .widthIn(max = 280.dp)
            .onGloballyPositioned { holder[0] = it.boundsInWindow() }
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .darkGlass(ChatTheme.bubble)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = LdColors.onGlassMuted,
        )
        Text(
            chatTime(message.ts),
            style = MaterialTheme.typography.labelSmall,
            color = LdColors.onGlassMuted,
        )
    }
}

@Composable
private fun ReactionBadges(
    badges: List<String>,
    outgoing: Boolean,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (badges.isEmpty()) return
    val same = badges.size == 2 && badges[0] == badges[1]
    val step = if (same) 14.dp else 26.dp
    val badge = 26.dp
    Box(modifier.width(badge + step * (badges.size - 1)).height(badge)) {
        badges.forEachIndexed { index, emoji ->
            val press = remember(emoji, index) { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val outline = if (outgoing) Color.White.copy(alpha = 0.9f) else LdColors.onGlass
            Box(
                Modifier
                    .offset(x = step * index)
                    .shadow(3.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.28f))
                    .size(badge)
                    .pressScale(press)
                    .border(1.dp, outline, CircleShape)
                    .background(Color(0xF0140A10), CircleShape)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = press,
                        indication = null,
                        onClick = { onToggle(emoji) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TextBody(message: ChatMessage) {
    Text(
        message.json.optString("text"),
        color = if (message.outgoing) ChatTheme.onMine() else ChatTheme.onTheirs(),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun LinkBody(message: ChatMessage, onLongPress: () -> Unit, onOpenLink: (String) -> Unit) {
    val url = message.json.optString("url")
    val title = message.json.optString("title")
    val desc = message.json.optString("desc")
    val text = message.json.optString("text")
    val host = hostOf(url)
    val mediaShape = concentricShape(ChatTheme.bubbleRadius, 14.dp)
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
            ThumbImage(
                img,
                Modifier.fillMaxWidth().heightIn(max = 160.dp).clip(mediaShape),
                stringResource(R.string.chat_photo_cd),
            )
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
                color = if (message.outgoing) LdColors.white else LdColors.onGlass,
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
    insetMedia: Boolean,
) {
    val kind = message.json.optString("kind")
    val mediaShape = if (insetMedia) {
        RoundedCornerShape(LdRadius.inner(ChatTheme.bubbleRadius, 4.dp))
    } else {
        concentricShape(ChatTheme.bubbleRadius, 14.dp)
    }
    when (kind) {
        "photo" -> Box(
            modifier = Modifier.combinedClickable(
                onClick = { onOpenPhoto() },
                onLongClick = onLongPress,
            ),
        ) {
            MediaVisual(
                message, ready, animate = false,
                Modifier.widthIn(max = ChatTheme.photoMax).heightIn(max = 320.dp),
                stringResource(R.string.chat_photo_cd),
                mediaShape,
            )
            DownloadOverlay(ready, downloadFailed, progress, onRetryDownload, Modifier.align(Alignment.Center))
        }
        "voice" -> ChatVoiceBubble(
            message, ready, downloadFailed, progress, onLongPress, onRetryDownload,
        )
        "video" -> Box(
            modifier = Modifier.combinedClickable(
                onClick = { onOpenPhoto() },
                onLongClick = onLongPress,
            ),
        ) {
            MediaVisual(
                message, ready, animate = false,
                Modifier.widthIn(max = ChatTheme.photoMax).heightIn(max = 220.dp),
                stringResource(R.string.chat_video_cd),
                mediaShape,
            )
            if (ready || message.json.optString("thumb").isNotEmpty()) {
                LuIcon(
                    Lu.Play,
                    contentDescription = stringResource(R.string.chat_video_cd),
                    modifier = Modifier.align(Alignment.Center),
                    tint = ChatTheme.viewerContent,
                    size = 42.dp,
                )
            }
            DownloadOverlay(ready, downloadFailed, progress, onRetryDownload, Modifier.align(Alignment.Center))
        }
        else -> FileBody(message, ready, downloadFailed, progress, onLongPress, onOpenExternal, onRetryDownload)
    }
    if (!insetMedia) {
        val caption = message.json.optString("text")
        if (caption.isNotBlank()) {
            Text(
                caption,
                modifier = Modifier.padding(top = 4.dp),
                color = if (message.outgoing) ChatTheme.onMine() else ChatTheme.onTheirs(),
            )
        }
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
                    color = if (message.outgoing) LdColors.white else Color(0xFFFFB4AB),
                )
                TextButton(onClick = onRetryDownload) {
                    Text(
                        stringResource(R.string.chat_download_retry),
                        color = if (message.outgoing) LdColors.white else LdColors.onGlass,
                    )
                }
            } else {
                val fraction = progress
                val spin = if (message.outgoing) LdColors.white else LdColors.onGlass
                if (fraction != null && fraction > 0f) {
                    CircularProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = spin,
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = spin)
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
    onOpen: () -> Unit,
    onRetryDownload: () -> Unit,
    onPosition: (Rect) -> Unit,
    lit: Boolean,
) {
    val mime = message.json.optString("mime")
    val animate = mime == "image/gif" || mime == "image/webp"
    val desc = stringResource(if (message.json.optString("kind") == "gif") R.string.chat_gif_cd else R.string.chat_sticker_cd)
    Column(horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
        Box(
            modifier = Modifier
                .onGloballyPositioned { onPosition(it.boundsInWindow()) }
                .then(if (lit) Modifier.border(2.dp, LdColors.white, RoundedCornerShape(2.dp)) else Modifier)
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        ) {
            MediaVisual(
                message,
                ready,
                animate,
                Modifier.widthIn(max = ChatTheme.stickerMax).heightIn(max = ChatTheme.stickerMax),
                desc,
                corners = RoundedCornerShape(0.dp),
            )
            DownloadOverlay(ready, downloadFailed, progress, onRetryDownload, Modifier.align(Alignment.Center))
        }
        Meta(message, Modifier)
    }
}

@Composable
private fun MediaVisual(
    message: ChatMessage,
    ready: Boolean,
    animate: Boolean,
    modifier: Modifier,
    desc: String,
    corners: Shape = ChatTheme.bubble,
) {
    val framed = modifier.clip(corners)
    if (ready) {
        ChatFrame(message.id, animate, if (animate) 720 else 1080, framed, desc, ContentScale.Fit)
    } else {
        val thumb = message.json.optString("thumb")
        if (thumb.isNotEmpty()) {
            ThumbImage(thumb, framed, desc)
        } else {
            Box(framed.heightIn(min = 72.dp).widthIn(min = 96.dp))
        }
    }
}

/** Still or animated attachment. Large stills are downsampled and cached. */
@Composable
fun ChatFrame(
    id: String,
    animate: Boolean,
    maxEdge: Int,
    modifier: Modifier,
    desc: String,
    scale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current
    var bytes by remember(id) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(id) {
        bytes = withContext(Dispatchers.IO) { ChatStore.readAttachment(context, id) }
    }
    val data = bytes ?: return
    if (animate) AnimBytes(id, data, desc, modifier) else StillBytes(id, data, desc, modifier, maxEdge, scale)
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
            Text(
                stringResource(R.string.chat_download_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
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
private fun Meta(message: ChatMessage, modifier: Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = modifier.padding(top = 2.dp),
    ) {
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
        ChatStore.SENDING -> Text(
            stringResource(R.string.chat_status_sending),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
        ChatStore.FAILED -> LuIcon(
            Lu.TriangleAlert,
            contentDescription = stringResource(R.string.chat_status_failed),
            size = 14.dp,
            tint = if (outgoing) LdColors.white else Color(0xFFFFB4AB),
        )
        ChatStore.READ -> LuIcon(
            Lu.CheckCheck,
            contentDescription = stringResource(R.string.chat_status_read),
            size = 14.dp,
            tint = Color(0xFF7EC8FF),
        )
        ChatStore.DELIVERED -> LuIcon(
            Lu.CheckCheck,
            contentDescription = stringResource(R.string.chat_status_delivered),
            size = 14.dp,
            tint = tint,
        )
        ChatStore.SENT -> LuIcon(
            Lu.Check,
            contentDescription = stringResource(R.string.chat_status_sent),
            size = 14.dp,
            tint = tint,
        )
        else -> Unit
    }
}

@Composable
private fun StillBytes(
    id: String,
    bytes: ByteArray,
    desc: String,
    modifier: Modifier,
    maxEdge: Int,
    scale: ContentScale,
) {
    val key = "$id@$maxEdge"
    var bitmap by remember(key) { mutableStateOf(stillCache.get(key)) }
    LaunchedEffect(key) {
        if (bitmap != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) { ChatMedia.decodeForDisplay(bytes, maxEdge) }
        if (decoded != null) {
            stillCache.put(key, decoded)
            bitmap = decoded
        }
    }
    val shown = bitmap ?: return
    Image(
        bitmap = shown.asImageBitmap(),
        contentDescription = desc,
        modifier = modifier,
        contentScale = scale,
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
private fun QuoteLine(text: String, outgoing: Boolean, modifier: Modifier) {
    Text(
        text,
        modifier = modifier.padding(bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = ChatTheme.meta(outgoing),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun QuoteChip(text: String, outgoing: Boolean) {
    Box(
        Modifier
            .padding(bottom = 4.dp)
            .widthIn(max = 300.dp)
            .bubblePlate(outgoing, ChatTheme.chip, lit = false),
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

@Composable
private fun Modifier.bubblePlate(outgoing: Boolean, shape: Shape, lit: Boolean): Modifier {
    if (!outgoing) {
        val glass = this.darkGlass(shape)
        return if (lit) glass.border(2.dp, LdColors.white, shape) else glass
    }
    val fill = this.clip(shape).background(LdColors.mineBrush())
    return if (lit) fill.border(2.dp, LdColors.white, shape) else fill
}

/**
 * Swipe starts a reply. The row always springs back to 0 on release, including
 * when the gesture is cancelled, so it cannot stay offset. Reply fires once,
 * on release past the threshold, with a haptic tick. A vertical move is left
 * for the message list.
 */
@Composable
private fun Modifier.swipeReply(
    shift: Animatable<Float, AnimationVector1D>,
    onReply: () -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    return pointerInput(onReply) {
        val threshold = 72.dp.toPx()
        val maxShift = 88.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var total = 0f
            var dx = 0f
            var dy = 0f
            var horizontal: Boolean? = null
            var released = false
            var track: Job? = null
            scope.launch { shift.snapTo(0f) }
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        released = change.changedToUp() && !change.isConsumed
                        break
                    }
                    val mx = change.position.x - change.previousPosition.x
                    val my = change.position.y - change.previousPosition.y
                    if (horizontal == null) {
                        dx += mx
                        dy += my
                        val slop = viewConfiguration.touchSlop
                        if (abs(dx) < slop && abs(dy) < slop) continue
                        horizontal = abs(dx) > abs(dy)
                        if (horizontal != true) break
                        total = dx.coerceIn(-maxShift, maxShift)
                    } else {
                        total = (total + mx).coerceIn(-maxShift, maxShift)
                        change.consume()
                    }
                    val sample = total
                    track?.cancel()
                    track = scope.launch { shift.snapTo(sample) }
                }
            } finally {
                track?.cancel()
                val trigger = released && horizontal == true && abs(total) >= threshold
                scope.launch {
                    if (Motion.enabled) {
                        shift.animateTo(0f, Motion.spring(dampingRatio = 0.72f, stiffness = 520f))
                    } else {
                        shift.snapTo(0f)
                    }
                }
                if (trigger) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onReply()
                }
            }
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
