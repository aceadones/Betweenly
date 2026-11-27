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
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nextlayer.betweenly.ChatMessage
import com.nextlayer.betweenly.ChatStore
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.Store
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
    quoted: ChatMessage? = null,
    quoteMissing: Boolean = false,
    onOpenQuote: () -> Unit = {},
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
    val caption = message.json.optString("text").trim()
    val hasQuote = quoted != null || quoteMissing
    val visual = type == "att" && (kind == "photo" || kind == "video" || kind == "gif")
    val plainSticker = type == "att" && kind == "sticker" && !hasQuote
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
            } else if (visual) {
                VisualBubble(
                    message = message,
                    caption = caption,
                    ready = ready,
                    downloadFailed = downloadFailed,
                    progress = progress,
                    quoted = quoted,
                    quoteMissing = quoteMissing,
                    lit = lit,
                    onLongPress = { onLongPress(rowHolder[0], bubbleHolder[0]) },
                    onOpen = { onOpenVisual(bubbleHolder[0]) },
                    onOpenQuote = onOpenQuote,
                    onRetry = onRetryDownload,
                    onPosition = { bubbleHolder[0] = it },
                )
            } else if (plainSticker) {
                BareImage(
                    message, ready, downloadFailed, progress,
                    onLongPress = { onLongPress(rowHolder[0], bubbleHolder[0]) },
                    onOpen = { onOpenVisual(bubbleHolder[0]) },
                    onRetryDownload = onRetryDownload,
                    onPosition = { bubbleHolder[0] = it },
                    lit = lit,
                )
            } else if (type == "att" && kind == "sticker") {
                QuotedSticker(
                    message, ready, downloadFailed, progress, quoted, quoteMissing, lit,
                    onLongPress = { onLongPress(rowHolder[0], bubbleHolder[0]) },
                    onOpen = { onOpenVisual(bubbleHolder[0]) },
                    onOpenQuote = onOpenQuote,
                    onRetry = onRetryDownload,
                    onPosition = { bubbleHolder[0] = it },
                )
            } else {
                val shape = ChatTheme.bubble
                Box(
                    Modifier
                        .widthIn(max = 300.dp)
                        .onGloballyPositioned { bubbleHolder[0] = it.boundsInWindow() }
                        .bubblePlate(message.outgoing, shape, lit),
                ) {
                    Column(
                        Modifier
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { onLongPress(rowHolder[0], bubbleHolder[0]) },
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        if (hasQuote) {
                            QuoteCard(
                                quoted = quoted,
                                missing = quoteMissing,
                                outgoing = message.outgoing,
                                onClick = onOpenQuote,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
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
                        Meta(message, Modifier.align(Alignment.End))
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

/**
 * Photo, video, or GIF. A caption sits under the image in the same bubble.
 * Without a caption the time and ticks sit on the image corner.
 */
@Composable
private fun VisualBubble(
    message: ChatMessage,
    caption: String,
    ready: Boolean,
    downloadFailed: Boolean,
    progress: Float?,
    quoted: ChatMessage?,
    quoteMissing: Boolean,
    lit: Boolean,
    onLongPress: () -> Unit,
    onOpen: () -> Unit,
    onOpenQuote: () -> Unit,
    onRetry: () -> Unit,
    onPosition: (Rect) -> Unit,
) {
    val kind = message.json.optString("kind")
    val hasCaption = caption.isNotBlank()
    val hasQuote = quoted != null || quoteMissing
    val wide = kind != "gif" || hasCaption || hasQuote
    val bubbleWidth = if (kind == "gif") ChatTheme.photoMax * 0.75f else if (wide) ChatTheme.photoMax else ChatTheme.stickerMax
    val maxHeight = if (kind == "video") 300.dp else if (kind == "gif") 300.dp else 440.dp
    val shape = ChatTheme.bubble
    val mime = message.json.optString("mime")
    val animate = kind == "gif" || mime == "image/gif" || mime == "image/webp"
    Column(
        Modifier
            .width(bubbleWidth)
            .onGloballyPositioned { onPosition(it.boundsInWindow()) }
            .bubblePlate(message.outgoing, shape, lit),
    ) {
        if (hasQuote) {
            QuoteCard(
                quoted = quoted,
                missing = quoteMissing,
                outgoing = message.outgoing,
                onClick = onOpenQuote,
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp),
            )
        }
        val inset = true // The bubble always shows as a frame around the picture.
        val radius = if (inset) LdRadius.inner(ChatTheme.bubbleRadius, 4.dp) else ChatTheme.bubbleRadius
        Box(Modifier.padding(if (inset) 4.dp else 0.dp)) {
            FittedStill(
                message = message,
                ready = ready,
                animate = animate,
                maxHeight = maxHeight,
                corners = RoundedCornerShape(radius),
                desc = stringResource(
                    when (kind) {
                        "video" -> R.string.chat_video_cd
                        "gif" -> R.string.chat_gif_cd
                        else -> R.string.chat_photo_cd
                    },
                ),
                onOpen = onOpen,
                onLongPress = onLongPress,
            )
            if (kind == "video" && (ready || message.json.optString("thumb").isNotEmpty())) {
                LuIcon(
                    Lu.Play,
                    contentDescription = stringResource(R.string.chat_video_cd),
                    modifier = Modifier.align(Alignment.Center),
                    tint = Color.White,
                    size = 42.dp,
                )
            }
            DownloadOverlay(ready, downloadFailed, progress, onRetry, Modifier.align(Alignment.Center))
            if (!hasCaption) {
                Meta(
                    message,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    onImage = true,
                )
            }
        }
        if (hasCaption) {
            Text(
                caption,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                color = ChatTheme.ink(message.outgoing),
                style = MaterialTheme.typography.bodyLarge,
            )
            Meta(
                message,
                Modifier.align(Alignment.End).padding(start = 12.dp, end = 10.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun FittedStill(
    message: ChatMessage,
    ready: Boolean,
    animate: Boolean,
    maxHeight: Dp,
    corners: Shape,
    desc: String,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val w = message.json.optInt("w")
    val h = message.json.optInt("h")
    val ratio = if (w > 0 && h > 0) w.toFloat() / h.toFloat() else 1f
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        val natural = maxWidth / ratio
        // Fill the width; very tall pictures are cropped top and bottom at maxHeight.
        val height = natural.coerceIn(120.dp, maxHeight)
        Box(Modifier.fillMaxWidth().height(height).clip(corners)) {
            MediaVisual(message, ready, animate, Modifier.fillMaxSize(), desc, corners, ContentScale.Crop)
        }
    }
}

/** A sticker that is also a reply. The quote sits inside the bubble, above the image. */
@Composable
private fun QuotedSticker(
    message: ChatMessage,
    ready: Boolean,
    downloadFailed: Boolean,
    progress: Float?,
    quoted: ChatMessage?,
    quoteMissing: Boolean,
    lit: Boolean,
    onLongPress: () -> Unit,
    onOpen: () -> Unit,
    onOpenQuote: () -> Unit,
    onRetry: () -> Unit,
    onPosition: (Rect) -> Unit,
) {
    val mime = message.json.optString("mime")
    val animate = mime == "image/gif" || mime == "image/webp"
    Column(
        Modifier
            .width(ChatTheme.stickerMax + 16.dp)
            .onGloballyPositioned { onPosition(it.boundsInWindow()) }
            .bubblePlate(message.outgoing, ChatTheme.bubble, lit)
            .padding(6.dp),
    ) {
        QuoteCard(
            quoted = quoted,
            missing = quoteMissing,
            outgoing = message.outgoing,
            onClick = onOpenQuote,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        )
        Box(
            Modifier.combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        ) {
            MediaVisual(
                message,
                ready,
                animate,
                Modifier.fillMaxWidth().heightIn(max = ChatTheme.stickerMax),
                stringResource(R.string.chat_sticker_cd),
                RoundedCornerShape(LdRadius.inner(ChatTheme.bubbleRadius, 6.dp)),
            )
            DownloadOverlay(ready, downloadFailed, progress, onRetry, Modifier.align(Alignment.Center))
        }
        Meta(message, Modifier.align(Alignment.End).padding(top = 2.dp))
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
                color = ChatTheme.ink(message.outgoing),
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
                    color = ChatTheme.ink(message.outgoing),
                )
                TextButton(onClick = onRetryDownload) {
                    Text(
                        stringResource(R.string.chat_download_retry),
                        color = ChatTheme.ink(message.outgoing),
                    )
                }
            } else {
                val fraction = progress
                val spin = ChatTheme.ink(message.outgoing)
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
    scale: ContentScale = ContentScale.Fit,
) {
    val framed = modifier.clip(corners)
    if (ready) {
        ChatFrame(message.id, animate, if (animate) 720 else 1080, framed, desc, scale)
    } else {
        val thumb = message.json.optString("thumb")
        if (thumb.isNotEmpty()) {
            ThumbImage(thumb, framed, desc, scale)
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
    if (animate) AnimBytes(id, data, desc, modifier, scale) else StillBytes(id, data, desc, modifier, maxEdge, scale)
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
private fun Meta(message: ChatMessage, modifier: Modifier, onImage: Boolean = false) {
    val color = if (onImage) Color.White else ChatTheme.meta(message.outgoing)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = modifier,
    ) {
        Text(chatTime(message.ts), style = MaterialTheme.typography.labelSmall, color = color)
        if (message.outgoing) {
            Spacer(Modifier.size(4.dp))
            StatusTick(message.status, outgoing = true, onImage = onImage)
        }
    }
}

@Composable
private fun StatusTick(status: String, outgoing: Boolean, onImage: Boolean = false) {
    val tint = when {
        onImage && status != ChatStore.READ -> Color.White
        else -> ChatTheme.meta(outgoing)
    }
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
            tint = if (onImage) Color.White else ChatTheme.ink(outgoing),
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
private fun ThumbImage(b64: String, modifier: Modifier, desc: String, scale: ContentScale = ContentScale.Fit) {
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
        contentScale = scale,
    )
}

@Composable
private fun AnimBytes(id: String, bytes: ByteArray, desc: String, modifier: Modifier, scale: ContentScale = ContentScale.Fit) {
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
                scaleType = if (scale == ContentScale.Crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
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

/**
 * WhatsApp-style quote. Darker on glass bubbles, lighter on a solid accent bubble.
 * A 3dp accent bar sits on the left. Media quotes show a label and a small thumb.
 */
@Composable
fun QuoteCard(
    quoted: ChatMessage?,
    missing: Boolean,
    outgoing: Boolean,
    onClick: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val look = rememberBubbleLook()
    val lighter = outgoing && !look.mineGlass
    val fill = if (lighter) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.28f)
    val bar = 3.dp
    val you = stringResource(R.string.you)
    val partner = Store.partnerName(context).ifBlank { stringResource(R.string.partner_fallback) }
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(fill)
            .drawBehind {
                drawRect(color = LdColors.primary, size = androidx.compose.ui.geometry.Size(bar.toPx(), size.height))
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(start = bar + 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (!missing && quoted != null) {
                Text(
                    if (quoted.outgoing) you else partner,
                    color = LdColors.primary,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                QuoteBody(quoted)
            } else {
                Text(
                    stringResource(R.string.chat_reply_gone),
                    color = ChatTheme.meta(outgoing),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!missing && quoted != null && quoteThumbKind(quoted) != null) {
            Spacer(Modifier.width(8.dp))
            QuoteThumb(quoted)
        }
        if (onClose != null) {
            val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = press,
                        indication = null,
                        onClick = onClose,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                LuIcon(
                    Lu.X,
                    contentDescription = stringResource(R.string.chat_reply_cancel),
                    tint = LdColors.onGlass,
                    size = 18.dp,
                )
            }
        }
    }
}

private fun quoteThumbKind(message: ChatMessage): String? {
    if (message.json.optString("t") != "att") return null
    return when (message.json.optString("kind")) {
        "photo", "video", "gif", "sticker" -> message.json.optString("kind")
        else -> null
    }
}

@Composable
private fun QuoteBody(message: ChatMessage) {
    val type = message.json.optString("t")
    val ink = ChatTheme.meta(message.outgoing)
    if (type == "gone") {
        Text(
            stringResource(R.string.chat_deleted_them),
            color = ink,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    if (type == "att") {
        when (message.json.optString("kind")) {
            "photo" -> QuoteLabel(Lu.Image, stringResource(R.string.chat_reply_photo), ink)
            "video" -> QuoteLabel(Lu.Video, stringResource(R.string.chat_reply_video), ink)
            "gif" -> QuoteLabel(Lu.Sparkles, stringResource(R.string.chat_reply_gif), ink)
            "sticker" -> QuoteLabel(Lu.Smile, stringResource(R.string.chat_reply_sticker), ink)
            "voice" -> QuoteLabel(Lu.Mic, voiceClock(message.json.optInt("dur")), ink)
            else -> {
                val name = message.json.optString("name").trim().ifBlank { stringResource(R.string.chat_reply_file) }
                QuoteLabel(Lu.FileText, name, ink)
            }
        }
        return
    }
    val text = message.json.optString("text").trim().ifBlank {
        message.json.optString("title").trim().ifBlank { message.json.optString("url").trim() }
    }.ifBlank { stringResource(R.string.chat_reply_gone) }
    Text(
        text,
        color = ink,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun QuoteLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, ink: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LuIcon(icon, contentDescription = null, tint = ink, size = 14.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            color = ink,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QuoteThumb(message: ChatMessage) {
    val b64 = message.json.optString("thumb")
    var bitmap by remember(message.id, b64) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(message.id, b64) {
        bitmap = withContext(Dispatchers.IO) {
            if (b64.isNotEmpty()) {
                try {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    ChatMedia.decodeForDisplay(bytes, 160)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(44.dp).clip(shape).background(Color.Black.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        val shown = bitmap
        if (shown != null) {
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
private fun Modifier.bubblePlate(outgoing: Boolean, shape: Shape, lit: Boolean): Modifier {
    val look = rememberBubbleLook()
    val glass = if (outgoing) look.mineGlass else look.theirsGlass
    val custom = if (outgoing) look.mine != 0 else look.theirs != 0
    val paint = if (outgoing) look.minePaint() else look.theirsPaint()
    val plate = when {
        outgoing && !glass && !custom -> this.clip(shape).background(LdColors.mineBrush())
        !outgoing && glass && !custom -> this.darkGlass(shape)
        glass -> this.darkGlass(shape, fill = paint.copy(alpha = 0.94f))
        else -> this.clip(shape).background(paint)
    }
    return if (lit) plate.border(2.dp, LdColors.white, shape) else plate
}

/** Two of these sit on the theme preview so a color change is visible immediately. */
@Composable
fun ChatSampleBubble(text: String, outgoing: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .widthIn(max = 260.dp)
            .bubblePlate(outgoing, ChatTheme.bubble, lit = false)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, color = ChatTheme.ink(outgoing), style = MaterialTheme.typography.bodyLarge)
    }
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


/** Three bouncing dots in a small incoming bubble, shown while the partner types. */
@Composable
fun TypingBubble() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    Row(
        Modifier
            .padding(top = 6.dp)
            .bubblePlate(false, ChatTheme.bubble, false)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0..2) {
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.keyframes {
                        durationMillis = 900
                        0f at (i * 150)
                        1f at (i * 150 + 220)
                        0f at (i * 150 + 440)
                    },
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        translationY = -phase * 5.dp.toPx()
                        alpha = 0.45f + 0.55f * phase
                    }
                    .background(ChatTheme.onTheirs(), CircleShape),
            )
        }
    }
}
