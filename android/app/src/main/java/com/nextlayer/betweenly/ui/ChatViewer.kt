package com.nextlayer.betweenly.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.content.FileProvider
import com.nextlayer.betweenly.ChatGallery
import com.nextlayer.betweenly.ChatMessage
import com.nextlayer.betweenly.ChatStore
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

private val thumbCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
}

fun viewerItems(messages: List<ChatMessage>): List<ChatMessage> =
    messages.filter { isViewerMedia(it) }.sortedWith(compareBy({ it.ts }, { it.id }))

/**
 * Full-screen pager for photos, videos, stickers, and GIFs.
 * Opens from [origin] (the tapped bubble) and closes back toward it.
 * Videos show a play badge and leave through [onPlay].
 */
@Composable
fun ChatMediaViewer(
    items: List<ChatMessage>,
    startId: String,
    origin: Rect?,
    onClose: () -> Unit,
    onPlay: (ChatMessage) -> Unit,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val start = items.indexOfFirst { it.id == startId }.let { if (it < 0) 0 else it }
    val pager = rememberPagerState(initialPage = start, pageCount = { items.size })
    val thumbs = rememberLazyListState()
    val progress = remember { Animatable(if (Motion.enabled) 0f else 1f) }
    val dragY = remember { Animatable(0f) }
    var zoomed by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var at by remember { mutableStateOf(Offset.Zero) }
    val page = pager.currentPage.coerceIn(0, items.lastIndex)
    val current = items[page]

    LaunchedEffect(Unit) {
        if (progress.value < 1f) progress.animateTo(1f, Motion.tween(280, Motion.easeOut))
    }
    LaunchedEffect(page) {
        zoomed = false
        thumbs.scrollToItem(page)
    }
    LaunchedEffect(items.size) {
        if (items.isNotEmpty() && pager.currentPage > items.lastIndex) {
            try {
                pager.scrollToPage(items.lastIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The pager rejects a page that is already gone. The next frame uses the last image.
            }
        }
    }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            progress.animateTo(0f, Motion.tween(180, Motion.easeOut))
            onClose()
        }
    }

    BackHandler(onBack = { close() })

    val open = progress.value
    val drag = dragY.value
    val heightPx = size.height.toFloat().coerceAtLeast(1f)
    val widthPx = size.width.toFloat().coerceAtLeast(1f)
    val dragFrac = (drag / heightPx).coerceIn(0f, 1f)
    val startScale = if (origin != null && origin.width > 1f) {
        (origin.width / widthPx).coerceIn(0.15f, 0.95f)
    } else {
        0.94f
    }
    val localCx = if (origin != null) origin.center.x - at.x else widthPx / 2f
    val localCy = if (origin != null) origin.center.y - at.y else heightPx / 2f
    val startTx = localCx - widthPx / 2f
    val startTy = localCy - heightPx / 2f
    val scale = lerp(startScale, 1f, open) * (1f - 0.25f * dragFrac)
    val tx = lerp(startTx, 0f, open)
    val ty = lerp(startTy, 0f, open) + drag
    val scrimAlpha = 0.92f * open * (1f - dragFrac)

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .onGloballyPositioned { at = it.positionInRoot() },
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha)))
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = tx
                    translationY = ty
                    alpha = open
                    transformOrigin = TransformOrigin.Center
                },
        ) {
            ViewerBar(
                whenLabel = chatStamp(current.ts),
                onClose = { close() },
                onSave = {
                    scope.launch {
                        val saved = saveCurrent(context, current)
                        snackbar.showSnackbar(
                            context.getString(if (saved) R.string.chat_saved else R.string.chat_save_failed),
                        )
                    }
                },
                onShare = {
                    scope.launch {
                        val ok = shareCurrent(context, current)
                        if (!ok) snackbar.showSnackbar(context.getString(R.string.chat_open_failed))
                    }
                },
            )
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                userScrollEnabled = !zoomed && drag < 8f,
                key = { index -> items.getOrNull(index)?.id ?: index },
            ) { index ->
                val item = items.getOrNull(index)
                if (item != null) {
                    ViewerPage(
                        message = item,
                        active = index == page,
                        onZoomed = { if (index == page) zoomed = it },
                        onPlay = { onPlay(item) },
                        onPull = { dy ->
                            scope.launch { dragY.snapTo((dragY.value + dy).coerceAtLeast(0f)) }
                        },
                        onPullEnd = {
                            scope.launch {
                                val threshold = with(density) { 140.dp.toPx() }
                                if (dragY.value > threshold) {
                                    dragY.animateTo(size.height.toFloat().coerceAtLeast(1f), Motion.tween(180, Motion.easeOut))
                                    onClose()
                                } else {
                                    dragY.animateTo(0f, Motion.spring())
                                }
                            }
                        },
                        onPullCancel = {
                            scope.launch { dragY.animateTo(0f, Motion.spring()) }
                        },
                    )
                }
            }
            LazyRow(
                state = thumbs,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    ViewerThumb(
                        message = item,
                        selected = index == page,
                        onClick = {
                            scope.launch {
                                if (Motion.enabled) pager.animateScrollToPage(index) else pager.scrollToPage(index)
                            }
                        },
                    )
                }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
    }
}

@Composable
private fun ViewerBar(
    whenLabel: String,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewerIcon(Lu.X, stringResource(R.string.chat_close), onClose)
        Text(
            whenLabel,
            modifier = Modifier.weight(1f),
            color = ChatTheme.viewerContent,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
        ViewerIcon(Lu.Download, stringResource(R.string.chat_save_gallery), onSave)
        ViewerIcon(Lu.Share2, stringResource(R.string.chat_share), onShare)
    }
}

@Composable
private fun ViewerIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressScale(press)
            .size(48.dp)
            .clickable(
                interactionSource = press,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        LuIcon(icon, contentDescription = description, tint = ChatTheme.viewerContent)
    }
}

@Composable
private fun ViewerPage(
    message: ChatMessage,
    active: Boolean,
    onZoomed: (Boolean) -> Unit,
    onPlay: () -> Unit,
    onPull: (Float) -> Unit,
    onPullEnd: () -> Unit,
    onPullCancel: () -> Unit,
) {
    val kind = message.json.optString("kind")
    val video = kind == "video"
    var scale by remember(message.id) { mutableFloatStateOf(1f) }
    var pan by remember(message.id) { mutableStateOf(Offset.Zero) }
    val scaleNow = rememberUpdatedState(scale)
    val pullNow = rememberUpdatedState(onPull)
    val endNow = rememberUpdatedState(onPullEnd)
    val cancelNow = rememberUpdatedState(onPullCancel)
    val transform = rememberTransformableState { zoom, panChange, _ ->
        val next = (scale * zoom).coerceIn(1f, 4f)
        scale = next
        if (next > 1.01f) pan += panChange else pan = Offset.Zero
        onZoomed(next > 1.01f)
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(message.id, active) {
                if (!active) return@pointerInput
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dy ->
                        if (scaleNow.value <= 1.05f) pullNow.value(dy)
                    },
                    onDragEnd = { if (scaleNow.value <= 1.05f) endNow.value() },
                    onDragCancel = { cancelNow.value() },
                )
            },
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .transformable(state = transform, canPan = { scaleNow.value > 1.01f })
            .pointerInput(message.id) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scaleNow.value > 1.2f) {
                            scale = 1f
                            pan = Offset.Zero
                            onZoomed(false)
                        } else {
                            scale = 2.5f
                            onZoomed(true)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (video) {
            VideoFrame(message, Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = pan.x
                translationY = pan.y
            })
            val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .pressScale(press)
                    .size(72.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                    .clickable(
                        interactionSource = press,
                        indication = null,
                        onClick = onPlay,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                LuIcon(
                    Lu.Play,
                    contentDescription = stringResource(R.string.chat_video_cd),
                    tint = ChatTheme.viewerContent,
                    size = 36.dp,
                )
            }
        } else {
            val mime = message.json.optString("mime")
            val animate = mime == "image/gif" || mime == "image/webp" || kind == "gif"
            val zoom = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = pan.x
                translationY = pan.y
            }
            Box(Modifier.fillMaxSize().then(zoom), contentAlignment = Alignment.Center) {
                ThumbBits(message, Modifier.fillMaxSize(), ContentScale.Fit)
                ChatFrame(
                    id = message.id,
                    animate = animate && active,
                    maxEdge = 1280,
                    modifier = Modifier.fillMaxSize(),
                    desc = stringResource(
                        when (kind) {
                            "gif" -> R.string.chat_gif_cd
                            "sticker" -> R.string.chat_sticker_cd
                            else -> R.string.chat_photo_cd
                        },
                    ),
                    scale = ContentScale.Fit,
                )
            }
        }
    }
    }
}

@Composable
private fun VideoFrame(message: ChatMessage, modifier: Modifier) {
    val thumb = message.json.optString("thumb")
    if (thumb.isEmpty()) {
        Box(modifier.background(Color.Black))
        return
    }
    var bitmap by remember(message.id) { mutableStateOf(thumbCache.get("v-${message.id}")) }
    LaunchedEffect(message.id) {
        if (bitmap != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            try {
                val bytes = Base64.decode(thumb, Base64.DEFAULT)
                ChatMedia.decodeForDisplay(bytes, 1280)
            } catch (_: Exception) {
                null
            }
        }
        if (decoded != null) {
            thumbCache.put("v-${message.id}", decoded)
            bitmap = decoded
        }
    }
    val shown = bitmap
    if (shown == null) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = ChatTheme.viewerContent)
        }
    } else {
        Image(
            bitmap = shown.asImageBitmap(),
            contentDescription = stringResource(R.string.chat_video_cd),
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun ViewerThumb(message: ChatMessage, selected: Boolean, onClick: () -> Unit) {
    val outer = RoundedCornerShape(12.dp)
    val inner = RoundedCornerShape(LdRadius.inner(12.dp, 2.dp))
    val press = remember(message.id) { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressScale(press)
            .size(56.dp)
            .border(2.dp, if (selected) Color.White else Color.Transparent, outer)
            .padding(2.dp)
            .clip(inner)
            .clickable(interactionSource = press, indication = null, onClick = onClick),
    ) {
        ThumbBits(message, Modifier.fillMaxSize())
        if (message.json.optString("kind") == "video") {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                LuIcon(Lu.Play, contentDescription = null, tint = Color.White, size = 16.dp)
            }
        }
    }
}

@Composable
private fun ThumbBits(
    message: ChatMessage,
    modifier: Modifier,
    scale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val key = "t-${message.id}"
    var bitmap by remember(message.id) { mutableStateOf(thumbCache.get(key)) }
    LaunchedEffect(message.id) {
        if (bitmap != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            try {
                val thumb = message.json.optString("thumb")
                val bytes = if (thumb.isNotEmpty()) {
                    Base64.decode(thumb, Base64.DEFAULT)
                } else if (message.json.optString("kind") == "video") {
                    null
                } else {
                    ChatStore.readAttachment(context, message.id)
                }
                if (bytes == null || bytes.isEmpty()) null else ChatMedia.decodeForDisplay(bytes, 240)
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        }
        if (decoded != null) {
            thumbCache.put(key, decoded)
            bitmap = decoded
        }
    }
    val shown = bitmap
    if (shown == null) {
        Box(modifier.background(Color.White.copy(alpha = 0.12f)))
    } else {
        Image(
            bitmap = shown.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = scale,
        )
    }
}

private suspend fun saveCurrent(context: android.content.Context, message: ChatMessage): Boolean {
    return try {
        withContext(Dispatchers.IO) {
            val bytes = ChatStore.readAttachment(context, message.id) ?: return@withContext false
            val kind = message.json.optString("kind")
            val video = kind == "video"
            val mime = message.json.optString("mime").ifBlank {
                if (video) "video/mp4" else "image/jpeg"
            }
            val name = message.json.optString("name").ifBlank { if (video) "video.mp4" else "photo.jpg" }
            ChatGallery.saveToGallery(context, name, mime, bytes, video)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}

private suspend fun shareCurrent(context: android.content.Context, message: ChatMessage): Boolean {
    val name = message.json.optString("name").ifBlank { "file" }
    val mime = message.json.optString("mime").ifBlank {
        if (message.json.optString("kind") == "video") "video/*" else "image/*"
    }
    val file = try {
        withContext(Dispatchers.IO) { ChatStore.exportOpenFile(context, message.id, name) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    } ?: return false
    val uri = try {
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    } catch (_: Exception) {
        return false
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri("file", uri)
    }
    return try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.chat_share)))
        true
    } catch (_: Exception) {
        false
    }
}
