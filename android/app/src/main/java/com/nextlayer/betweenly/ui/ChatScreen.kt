@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nextlayer.betweenly.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.OpenableColumns
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.FileProvider
import com.nextlayer.betweenly.AppState
import com.nextlayer.betweenly.ChatGallery
import com.nextlayer.betweenly.ChatMessage
import com.nextlayer.betweenly.ChatNotifications
import com.nextlayer.betweenly.ChatStore
import com.nextlayer.betweenly.ChatWallpaperSync
import com.nextlayer.betweenly.FileTransfer
import com.nextlayer.betweenly.Inbox
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.Store
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * The paired chat. The list stays on the tab. Opening the thread hides the
 * dock. [onOpenCamera] opens the in-app camera; the shot waits in
 * [ChatController.stageCapture] until this screen shows the preview.
 */
@Composable
fun ChatScreen(
    opened: Boolean,
    onOpen: () -> Unit,
    onBack: () -> Unit,
    onOpenCamera: () -> Unit,
) {
    val context = LocalContext.current
    SideEffect { ChatController.install(context) }
    if (!opened) {
        ChatInbox(onOpen)
        return
    }
    DisposableEffect(Unit) {
        AppState.chatOpen = true
        ChatStore.markRead(context)
        ChatNotifications.cancel(context)
        onDispose {
            AppState.chatOpen = false
            VoicePlay.stop()
        }
    }
    var settings by rememberSaveable { mutableStateOf(false) }
    var background by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        ChatBody(
            onOpenCamera = onOpenCamera,
            onOpenSettings = { settings = true },
            onOpenBackground = { background = true },
            onBack = onBack,
        )
        if (background) ChatBackground(onBack = { background = false })
        if (settings) ChatSettings(onBack = { settings = false })
    }
}

/** One row for the only conversation. The dock stays visible on this page. */
@Composable
private fun ChatInbox(onOpen: () -> Unit) {
    val context = LocalContext.current
    val storeRev by Store.changes.collectAsState()
    val chatRev by ChatStore.changes.collectAsState()
    val unread by ChatStore.unreadCount.collectAsState()
    var latest by remember { mutableStateOf<ChatMessage?>(null) }
    LaunchedEffect(chatRev) {
        latest = withContext(Dispatchers.IO) { ChatStore.list(context).firstOrNull() }
    }
    val name = remember(storeRev) {
        Store.partnerName(context).ifBlank { context.getString(R.string.partner_fallback) }
    }
    val openLabel = stringResource(R.string.chat_open_thread)
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            stringResource(R.string.tab_chat),
            style = MaterialTheme.typography.headlineMedium,
            color = LdColors.onGlass,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .ldGlass(RoundedCornerShape(LdRadius.card), solid = true)
                .clip(RoundedCornerShape(LdRadius.card))
                .clickable(onClick = onOpen)
                .semantics { contentDescription = openLabel }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundAvatar(storeRev, { Store.partnerAvatar(context) }, name, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = LdColors.onGlass,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val preview = latest
                Text(
                    if (preview == null) stringResource(R.string.chat_empty) else replySnippet(preview),
                    color = LdColors.onGlassMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val shown = latest
                if (shown != null) {
                    Text(
                        chatTime(shown.ts),
                        color = LdColors.onGlassMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (unread > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        unread.coerceAtMost(99).toString(),
                        color = LdColors.white,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .background(LdColors.primary, CircleShape)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBody(
    onOpenCamera: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBackground: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val storeRev by Store.changes.collectAsState()
    val chatRev by ChatStore.changes.collectAsState()
    val failedDownloads by ChatStore.failedDownloads.collectAsState()
    val fileProgress by FileTransfer.progress.collectAsState()
    val wall by ChatWallpaperSync.wall.collectAsState()
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var ready by remember { mutableStateOf<Set<String>>(emptySet()) }
    var text by rememberSaveable { mutableStateOf("") }
    var replyId by remember { mutableStateOf(ChatController.peekReply()) }
    var attachOpen by remember { mutableStateOf(false) }
    var stickersOn by remember { mutableStateOf(false) }
    var activitiesOn by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var drafts by remember { mutableStateOf<List<ChatDraft>>(emptyList()) }
    var previewOn by remember { mutableStateOf(false) }
    var caption by remember { mutableStateOf("") }
    var draftIndex by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var recordLocked by remember { mutableStateOf(false) }
    var recordCancel by remember { mutableStateOf(false) }
    var recordMs by remember { mutableIntStateOf(0) }
    var levels by remember { mutableStateOf<List<Int>>(emptyList()) }
    var voiceDraft by remember { mutableStateOf<VoiceClip?>(null) }
    val voice = remember { VoiceSession(context.applicationContext) }
    var menuId by remember { mutableStateOf<String?>(null) }
    var menuOn by remember { mutableStateOf(false) }
    var renderMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var slots by remember { mutableStateOf(ChatReactions.defaults) }
    var pickerSlot by remember { mutableStateOf<Int?>(null) }
    var rowRect by remember { mutableStateOf(Rect.Zero) }
    var bubbleRect by remember { mutableStateOf(Rect.Zero) }
    var viewingId by remember { mutableStateOf<String?>(null) }
    var viewingRect by remember { mutableStateOf<Rect?>(null) }
    var composerPx by remember { mutableIntStateOf(0) }
    var headerPx by remember { mutableIntStateOf(0) }
    var newAway by remember { mutableIntStateOf(0) }
    var showJump by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(true) }
    var primed by remember { mutableStateOf(false) }
    val seen = remember { HashSet<String>() }
    val name = remember(storeRev) {
        Store.partnerName(context).ifBlank { context.getString(R.string.partner_fallback) }
    }
    val density = LocalDensity.current
    val composerHeight = with(density) { composerPx.toDp() }
    val headerHeight = with(density) { headerPx.toDp() }.coerceAtLeast(72.dp)
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        ChatWallpaperSync.refresh(context)
        ChatReactions.refresh(context)
        try {
            ChatController.flushUnsends(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A missed unsend stays pending for the next open.
        }
        try {
            ChatWallpaperSync.retryPending(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The wallpaper photo stays pending for the next open.
        }
    }
    LaunchedEffect(Unit) {
        ChatController.notes.collect { id ->
            snackbar.showSnackbar(context.getString(id))
        }
    }
    LaunchedEffect(chatRev) {
        val loaded = withContext(Dispatchers.IO) { ChatStore.list(context) }
        messages = loaded
        ready = withContext(Dispatchers.IO) {
            loaded.mapNotNull { message ->
                if (ChatStore.hasAttachment(context, message.id)) message.id else null
            }.toSet()
        }
        if (AppState.chatOpen) ChatStore.markRead(context)
        ChatNotifications.cancel(context)
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            try {
                // DATA-FLOW: ciphertext blobs from my relay inbox, encrypted. The relay deletes them on read. See Inbox.process.
                Inbox.process(context)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // No network. The next pass tries again.
            }
            delay(5_000)
        }
    }

    fun appendDrafts(more: List<ChatDraft>) {
        if (more.isEmpty()) return
        val merged = (drafts + more).takeLast(10)
        drafts = merged
        draftIndex = merged.lastIndex
        previewOn = true
        attachOpen = false
        stickersOn = false
        activitiesOn = false
    }

    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        appendDrafts(uris.map { uri -> draftFromUri(context, uri, fromCamera = false) })
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri).orEmpty()
        appendDrafts(
            listOf(
                ChatDraft(
                    key = System.nanoTime(),
                    uri = uri,
                    bytes = null,
                    mime = mime.ifBlank { "application/octet-stream" },
                    name = displayName(context, uri),
                    video = false,
                    asFile = true,
                    fromCamera = false,
                ),
            ),
        )
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.chat_mic_denied)) }
            return@rememberLauncherForActivityResult
        }
        if (voice.start()) {
            recording = true
            recordLocked = true
            recordCancel = false
            recordMs = 0
            levels = emptyList()
            voiceDraft = null
            stickersOn = false
            attachOpen = false
        } else {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.chat_voice_failed)) }
        }
    }

    fun sendDraft() {
        val current = text.trim()
        if (current.isEmpty()) return
        val id = replyId
        val queued = ChatController.sendText(context, current, id)
        if (!queued) return
        text = ""
        replyId = null
    }

    fun openCamera() {
        ChatController.holdPreview(drafts, caption, draftIndex)
        attachOpen = false
        stickersOn = false
        onOpenCamera()
    }

    fun stageIncoming(uri: Uri, mime: String) {
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                ChatMedia.readNow(context, uri, ChatMedia.MAX_BYTES)
            }
            when (read) {
                is ReadBytes.Ok -> {
                    val image = mime.startsWith("image/") || mime.startsWith("video/") || mime.isBlank()
                    appendDrafts(
                        listOf(
                            ChatDraft(
                                key = System.nanoTime(),
                                uri = null,
                                bytes = read.data,
                                mime = mime.ifBlank { "image/*" },
                                name = if (image) "" else "file",
                                video = mime.startsWith("video/"),
                                asFile = !image,
                                fromCamera = false,
                            ),
                        ),
                    )
                }
                is ReadBytes.TooBig -> ChatController.note(R.string.chat_file_too_large)
                is ReadBytes.Failed -> ChatController.note(R.string.chat_attach_failed)
            }
        }
    }

    fun startVoice() {
        if (recording) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPerm.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!voice.start()) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.chat_voice_failed)) }
            return
        }
        recording = true
        recordLocked = false
        recordCancel = false
        recordMs = 0
        levels = emptyList()
        voiceDraft = null
        stickersOn = false
        attachOpen = false
        activitiesOn = false
    }

    fun finishVoice() {
        val clip = voice.finish()
        recording = false
        recordLocked = false
        recordCancel = false
        levels = emptyList()
        if (clip != null && clip.durationMs >= 400) voiceDraft = clip
    }

    fun cancelVoice() {
        voice.cancel()
        recording = false
        recordLocked = false
        recordCancel = false
        levels = emptyList()
    }

    fun sendVoiceNote() {
        val clip = voiceDraft ?: return
        ChatController.sendVoice(context, clip.bytes, clip.durationMs, clip.wave, replyId)
        voiceDraft = null
        replyId = null
    }

    fun sendPreview() {
        val items = drafts
        if (items.isEmpty()) return
        ChatController.sendDrafts(
            context,
            items.map { item ->
                OutgoingDraft(item.uri, item.bytes, item.mime, item.name, item.video, item.asFile)
            },
            caption.trim(),
            replyId,
        )
        drafts = emptyList()
        caption = ""
        previewOn = false
        replyId = null
        ChatController.holdPreview(emptyList(), "", 0)
    }

    LaunchedEffect(Unit) {
        val (held, heldCaption, heldIndex) = ChatController.takePreview()
        val staged = ChatController.consumeCaptures().map { cap ->
            draftFromUri(context, cap.uri, fromCamera = true, video = cap.video)
        }
        val merged = (held + staged).takeLast(10)
        if (merged.isNotEmpty()) {
            drafts = merged
            caption = heldCaption
            draftIndex = heldIndex.coerceIn(0, merged.lastIndex)
            previewOn = true
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                voice.cancel()
                recording = false
                recordLocked = false
                recordCancel = false
                VoicePlay.stop()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            voice.cancel()
        }
    }
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        while (isActive && recording) {
            levels = voice.sample()
            recordMs = voice.elapsed()
            if (recordMs >= 5 * 60 * 1000) {
                val vibrator = context.getSystemService(Vibrator::class.java)
                vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
                finishVoice()
                break
            }
            delay(80)
        }
    }

    fun closeMenu() {
        menuOn = false
        editing = false
        pickerSlot = null
    }

    fun openMenu(id: String, row: Rect, bubble: Rect) {
        if (row.width < 1f) return
        menuId = id
        rowRect = row
        bubbleRect = if (bubble.width < 1f) row else bubble
        slots = ChatReactions.current(context)
        editing = false
        pickerSlot = null
        attachOpen = false
        stickersOn = false
        activitiesOn = false
        overflowOpen = false
        renderMenu = true
        menuOn = true
    }

    LaunchedEffect(menuOn) {
        if (!menuOn && renderMenu) {
            delay(if (Motion.enabled) 140 else 0)
            if (!menuOn) {
                renderMenu = false
                menuId = null
            }
        }
    }

    val today = LocalDate.now()
    val rows = remember(messages, today) { buildRows(messages) }
    val idKey = remember(messages) { messages.joinToString(separator = "\n") { it.id } }
    val selected = messages.firstOrNull { it.id == menuId }
    LaunchedEffect(idKey) {
        ChatController.syncDelivery(context, messages)
    }
    LaunchedEffect(listState, idKey) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }.collect { indices ->
            val ids = ArrayList<String>()
            for (index in indices) {
                val row = rows.getOrNull(index) as? ChatRow.Msg ?: continue
                val message = row.message
                if (message.outgoing) continue
                val type = message.json.optString("t")
                if (type != "chat" && type != "att" && type != "link") continue
                if (message.json.optInt("sv") == 1) continue
                ids.add(message.id)
            }
            if (ids.isNotEmpty()) ChatController.noteSeen(context, ids)
        }
    }
    val now = rememberNow()
    val hideActivity = remember(storeRev) { Store.chatHideActivity(context) }
    val heardAt = remember(chatRev) { ChatStore.partnerHeardAt }
    val seenAt = maxOf(Store.partnerLoc(context)?.lastSeen ?: 0L, heardAt)
    val presence = when {
        hideActivity || seenAt <= 0L -> null
        now - seenAt in 0..90_000L -> stringResource(R.string.chat_online)
        else -> stringResource(R.string.last_seen, lastSeenPhrase(context, seenAt, now))
    }
    val view = LocalView.current

    SideEffect {
        if (!renderMenu) {
            pinned = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 24
        }
        val watching = viewingId
        if (watching != null && messages.none { it.id == watching }) {
            viewingId = null
            viewingRect = null
        }
    }
    LaunchedEffect(idKey, renderMenu) {
        val current = messages
        if (renderMenu) return@LaunchedEffect
        if (!primed) {
            if (current.isNotEmpty()) {
                listState.scrollToItem(0)
                primed = true
                seen.clear()
                current.forEach { seen.add(it.id) }
            }
            return@LaunchedEffect
        }
        val added = current.filter { it.id !in seen }
        seen.clear()
        current.forEach { seen.add(it.id) }
        if (added.isEmpty()) return@LaunchedEffect
        val sentByMe = added.any { it.outgoing && it.json.optString("t") != "note" }
        if (sentByMe || pinned) {
            listState.scrollToItem(0)
            newAway = 0
        } else {
            newAway += added.count { message ->
                !message.outgoing &&
                    message.json.optString("t") != "note" &&
                    message.json.optString("t") != "gone"
            }
        }
    }
    LaunchedEffect(composerPx) {
        if (primed && pinned && composerPx > 0 && !renderMenu) {
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
            val index = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            val newestVisible = info.visibleItemsInfo.any { it.index == 0 }
            val avg = info.visibleItemsInfo.let { visible ->
                if (visible.isEmpty()) viewport else visible.sumOf { it.size } / visible.size
            }.coerceAtLeast(1)
            val distance = if (index == 0) offset else index * avg + offset
            val bottom = index == 0 && offset <= 24
            val show = primed && !bottom && (distance > viewport * 3 / 2 || !newestVisible)
            bottom to show
        }.distinctUntilChanged().collect { (bottom, show) ->
            showJump = show
            if (bottom) newAway = 0
        }
    }

    val turn by animateFloatAsState(
        targetValue = if (attachOpen) 45f else 0f,
        animationSpec = Motion.spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "plus",
    )
    val scrim by animateFloatAsState(
        targetValue = if (menuOn) 0.30f else 0f,
        animationSpec = Motion.tween(if (menuOn) 160 else 110),
        label = "menuScrim",
    )

    BackHandler {
        when {
            renderMenu -> closeMenu()
            previewOn -> {
                previewOn = false
                drafts = emptyList()
                caption = ""
            }
            activitiesOn -> activitiesOn = false
            stickersOn -> stickersOn = false
            recording -> cancelVoice()
            else -> onBack()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ChatWallBackdrop(wall, Modifier.fillMaxSize())
            val wallDim = wallScrim(
                wall.kind,
                if (wall.kind == "color") ChatTheme.wallpapers.getOrNull(wall.color) else null,
            )
            if (wallDim > 0f) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = wallDim)))
            }
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(1f)
                        .onSizeChanged { headerPx = it.height }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .ldGlass(RoundedCornerShape(LdRadius.chip), solid = true)
                            .padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MotionIconButton(
                            onClick = onBack,
                            icon = Lu.ArrowLeft,
                            description = stringResource(R.string.back),
                        )
                        RoundAvatar(storeRev, { Store.partnerAvatar(context) }, name, 36.dp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                name,
                                style = MaterialTheme.typography.titleMedium,
                                color = LdColors.onGlass,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (presence != null) {
                                Text(
                                    presence,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LdColors.onGlassMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box {
                        GlassIconButton(
                            onClick = {
                                overflowOpen = !overflowOpen
                                if (overflowOpen) {
                                    attachOpen = false
                                    stickersOn = false
                                }
                            },
                            icon = Lu.EllipsisVertical,
                            description = stringResource(R.string.chat_menu),
                            solid = true,
                        )
                        AnchoredPopup(
                            visible = overflowOpen,
                            above = false,
                            origin = TransformOrigin(1f, 0f),
                            onDismiss = { overflowOpen = false },
                        ) {
                            Column(
                                Modifier
                                    .widthIn(min = 220.dp, max = 300.dp)
                                    .ldGlass(RoundedCornerShape(LdRadius.card), solid = true)
                                    .padding(vertical = 8.dp),
                            ) {
                                MenuLine(Lu.Image, stringResource(R.string.chat_background)) {
                                    overflowOpen = false
                                    onOpenBackground()
                                }
                                MenuLine(Lu.Settings, stringResource(R.string.chat_settings)) {
                                    overflowOpen = false
                                    onOpenSettings()
                                }
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    if (rows.isEmpty()) {
                        Box(
                            Modifier.fillMaxSize().padding(top = headerHeight, bottom = composerHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                            ChatSystemNote(stringResource(R.string.chat_empty))
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            reverseLayout = true,
                            userScrollEnabled = !renderMenu,
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                top = headerHeight + 8.dp,
                                end = 16.dp,
                                bottom = composerHeight + 8.dp,
                            ),
                        ) {
                            itemsIndexed(
                                items = rows,
                                key = { _, row ->
                                    when (row) {
                                        is ChatRow.Msg -> row.message.id
                                        is ChatRow.Day -> "day-${row.day}"
                                    }
                                },
                            ) { index, row ->
                                Box(Modifier.padding(top = rowGap(rows, index))) {
                                    when (row) {
                                        is ChatRow.Day -> ChatDateLabel(chatDayLabel(row.day, today))
                                        is ChatRow.Msg -> {
                                            val message = row.message
                                            if (message.json.optString("t") == "note") {
                                                ChatSystemNote(message.json.optString("text"))
                                            } else {
                                                val fid = message.json.optString("fid").trim().lowercase()
                                                ChatBubble(
                                                    message = message,
                                                    ready = message.id in ready,
                                                    downloadFailed = message.id in failedDownloads,
                                                    progress = fileProgress[fid],
                                                    onLongPress = { rowBounds, bubbleBounds ->
                                                        openMenu(message.id, rowBounds, bubbleBounds)
                                                    },
                                                    onOpenVisual = { rect ->
                                                        closeMenu()
                                                        viewingId = message.id
                                                        viewingRect = rect.takeIf { it.width > 1f && it.height > 1f }
                                                    },
                                                    onOpenExternal = {
                                                        openExternal(context, scope, snackbar, message)
                                                    },
                                                    onOpenLink = { url -> openLink(context, scope, snackbar, url) },
                                                    onRetryDownload = {
                                                        scope.launch {
                                                            withContext(Dispatchers.IO) {
                                                                ChatStore.fetch(context, message.id)
                                                            }
                                                        }
                                                    },
                                                    onToggleReaction = { emoji ->
                                                        ChatController.react(context, message.id, emoji)
                                                    },
                                                    quote = quoteFor(message, messages),
                                                    onReply = {
                                                        replyId = message.id
                                                        ChatController.armReply(message.id)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    JumpButton(
                        visible = showJump && !renderMenu,
                        count = newAway,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = composerHeight + 12.dp),
                        onClick = {
                            scope.launch {
                                listState.scrollToItem(0)
                                newAway = 0
                            }
                        },
                    )
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .onSizeChanged { composerPx = it.height },
                    ) {
                        if (stickersOn && !recording && voiceDraft == null) {
                            ChatStickerPanel(
                                messages = messages,
                                onGif = { bytes ->
                                    ChatController.sendKind(context, bytes, "image/gif", "gif", "gif.gif", replyId)
                                    replyId = null
                                },
                                onSticker = { bytes, mime ->
                                    ChatController.sendKind(context, bytes, mime, "sticker", "sticker.png", replyId)
                                    replyId = null
                                },
                                onClose = { stickersOn = false },
                            )
                        }
                        Composer(
                            text = text,
                            quoting = replyId?.let { id -> messages.firstOrNull { it.id == id } },
                            turn = turn,
                            attachOpen = attachOpen,
                            recording = recording,
                            recordLocked = recordLocked,
                            recordCancel = recordCancel,
                            recordMs = recordMs,
                            levels = levels,
                            voiceDraft = voiceDraft,
                            onText = { text = it.take(10_000) },
                            onSend = { sendDraft() },
                            onKeyboardSend = { sendDraft() },
                            onToggleAttach = {
                                attachOpen = !attachOpen
                                if (attachOpen) {
                                    overflowOpen = false
                                    stickersOn = false
                                }
                            },
                            onDismissAttach = { attachOpen = false },
                            onGallery = {
                                attachOpen = false
                                try {
                                    gallery.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                                    )
                                } catch (_: Exception) {
                                    scope.launch { snackbar.showSnackbar(context.getString(R.string.chat_attach_failed)) }
                                }
                            },
                            onCamera = { openCamera() },
                            onStickers = {
                                val next = !stickersOn
                                stickersOn = next
                                if (next) {
                                    attachOpen = false
                                    overflowOpen = false
                                    val imm = context.getSystemService(InputMethodManager::class.java)
                                    imm?.hideSoftInputFromWindow(view.windowToken, 0)
                                    view.clearFocus()
                                }
                            },
                            onActivities = {
                                attachOpen = false
                                activitiesOn = true
                            },
                            onFile = {
                                attachOpen = false
                                try {
                                    documents.launch(arrayOf("*/*"))
                                } catch (_: Exception) {
                                    scope.launch { snackbar.showSnackbar(context.getString(R.string.chat_attach_failed)) }
                                }
                            },
                            onCancelReply = {
                                replyId = null
                                ChatController.armReply(null)
                            },
                            onImage = { uri, mime -> stageIncoming(uri, mime) },
                            onMicDown = { startVoice() },
                            onMicDrag = { recordCancel = it },
                            onMicUp = { tap, cancelled ->
                                if (recording) {
                                    when {
                                        cancelled -> cancelVoice()
                                        recordLocked && tap -> finishVoice()
                                        tap -> recordLocked = true
                                        else -> finishVoice()
                                    }
                                }
                            },
                            onSendVoice = { sendVoiceNote() },
                            onDeleteVoice = { voiceDraft = null },
                        )
                    }
                }
            }
            if (renderMenu) {
                MessageMenu(
                    message = selected,
                    messages = messages,
                    ready = selected?.let { it.id in ready } == true,
                    downloadFailed = selected?.let { it.id in failedDownloads } == true,
                    progress = selected?.let { fileProgress[it.json.optString("fid").trim().lowercase()] },
                    menuOn = menuOn,
                    scrim = scrim,
                    editing = editing,
                    slots = slots,
                    rowRect = rowRect,
                    bubbleRect = bubbleRect,
                    pickerOpen = pickerSlot != null,
                    recent = if (pickerSlot == null) emptyList() else ChatReactions.recent(context),
                    onPickEmoji = { emoji ->
                        val slot = pickerSlot ?: return@MessageMenu
                        val next = slots.toMutableList()
                        if (slot in next.indices) next[slot] = emoji
                        slots = next
                        ChatReactions.pushRecent(context, emoji)
                        pickerSlot = null
                    },
                    onDismissPicker = { pickerSlot = null },
                    onDismiss = { closeMenu() },
                    onEdit = {
                        editing = true
                        slots = ChatReactions.current(context)
                    },
                    onDone = {
                        ChatReactions.save(context, slots)
                        editing = false
                    },
                    onEmoji = { index ->
                        if (editing) {
                            pickerSlot = index
                        } else {
                            val emoji = slots.getOrNull(index) ?: return@MessageMenu
                            val id = menuId ?: return@MessageMenu
                            closeMenu()
                            ChatController.react(context, id, emoji)
                        }
                    },
                    onReply = {
                        val id = selected?.id ?: return@MessageMenu
                        replyId = id
                        ChatController.armReply(id)
                        closeMenu()
                    },
                    onCopy = {
                        val value = selected?.let { copyable(it) } ?: return@MessageMenu
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("text", value))
                        closeMenu()
                        scope.launch { snackbar.showSnackbar(context.getString(R.string.chat_copied)) }
                    },
                    onRetry = {
                        val id = selected?.id ?: return@MessageMenu
                        closeMenu()
                        ChatController.retry(context, id)
                    },
                    onDelete = {
                        val id = selected?.id ?: return@MessageMenu
                        closeMenu()
                        ChatController.delete(context, id)
                    },
                    onUnsend = {
                        val id = selected?.id ?: return@MessageMenu
                        closeMenu()
                        ChatController.unsend(context, id)
                    },
                )
            }
            val watch = viewingId
            if (watch != null) {
                ChatMediaViewer(
                    items = viewerItems(messages),
                    startId = watch,
                    origin = viewingRect,
                    onClose = {
                        viewingId = null
                        viewingRect = null
                    },
                    onPlay = { item -> openExternal(context, scope, snackbar, item) },
                )
            }
            if (activitiesOn) {
                ChatActivitiesSheet(
                    onPick = { file ->
                        activitiesOn = false
                        scope.launch {
                            val bytes = withContext(Dispatchers.IO) {
                                try {
                                    file.readBytes()
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            if (bytes == null || bytes.isEmpty()) {
                                snackbar.showSnackbar(context.getString(R.string.chat_attach_failed))
                            } else {
                                ChatController.sendLocalImage(context, bytes, "image/png", replyId)
                                replyId = null
                            }
                        }
                    },
                    onClose = { activitiesOn = false },
                )
            }
            if (previewOn && drafts.isNotEmpty()) {
                val current = drafts.getOrNull(draftIndex)
                ChatAttachPreview(
                    items = drafts,
                    selected = draftIndex.coerceIn(0, drafts.lastIndex),
                    caption = caption,
                    onCaption = { caption = it },
                    onSelect = { draftIndex = it },
                    onRemove = {
                        val next = drafts.toMutableList()
                        val index = draftIndex.coerceIn(0, next.lastIndex)
                        if (index in next.indices) next.removeAt(index)
                        drafts = next
                        if (next.isEmpty()) {
                            previewOn = false
                            caption = ""
                        } else {
                            draftIndex = index.coerceAtMost(next.lastIndex)
                        }
                    },
                    onAdd = {
                        try {
                            gallery.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                            )
                        } catch (_: Exception) {
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.chat_attach_failed)) }
                        }
                    },
                    onRetake = if (current?.fromCamera == true) {
                        {
                            val key = current.key
                            drafts = drafts.filter { it.key != key }
                            if (drafts.isEmpty()) previewOn = false
                            openCamera()
                        }
                    } else {
                        null
                    },
                    onSend = { sendPreview() },
                    onClose = {
                        previewOn = false
                        drafts = emptyList()
                        caption = ""
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageMenu(
    message: ChatMessage?,
    messages: List<ChatMessage>,
    ready: Boolean,
    downloadFailed: Boolean,
    progress: Float?,
    menuOn: Boolean,
    scrim: Float,
    editing: Boolean,
    slots: List<String>,
    rowRect: Rect,
    bubbleRect: Rect,
    pickerOpen: Boolean,
    recent: List<String>,
    onPickEmoji: (String) -> Unit,
    onDismissPicker: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDone: () -> Unit,
    onEmoji: (Int) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onUnsend: () -> Unit,
) {
    Dialog(
        onDismissRequest = { if (pickerOpen) onDismissPicker() else onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
    val dialogView = LocalView.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    SideEffect {
        (dialogView.parent as? DialogWindowProvider)?.window?.apply {
            setDimAmount(0f)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
        }
    }
    val root = origin
    val type = message?.json?.optString("t").orEmpty()
    val gone = type == "gone"
    val canReply = message != null && !gone
    val canCopy = message?.let { copyable(it) } != null && !gone
    val canRetry = message != null && message.outgoing && message.status == ChatStore.FAILED && !gone
    val canUnsend = message != null && message.outgoing && !gone
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrim))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        if (message != null && rowRect.width > 1f) {
            LiftedMessage(
                message = message,
                messages = messages,
                ready = ready,
                downloadFailed = downloadFailed,
                progress = progress,
                menuOn = menuOn,
                editing = editing,
                slots = slots,
                rowRect = rowRect,
                bubbleRect = bubbleRect,
                root = root,
                canReply = canReply,
                canCopy = canCopy,
                canRetry = canRetry,
                canUnsend = canUnsend,
                onEdit = onEdit,
                onDone = onDone,
                onEmoji = onEmoji,
                onReply = onReply,
                onCopy = onCopy,
                onRetry = onRetry,
                onDelete = onDelete,
                onUnsend = onUnsend,
            )
        }
        EmojiPicker(
            visible = pickerOpen,
            recent = recent,
            onPick = onPickEmoji,
            onDismiss = onDismissPicker,
        )
    }
    }
}

/**
 * Lifted copy of the pressed bubble. The reaction pill stays above it and the
 * actions stay below it. If the screen is short, the copy slides until both fit.
 * A bubble taller than that free area is cropped with a fade, never covered.
 */
@Composable
private fun LiftedMessage(
    message: ChatMessage,
    messages: List<ChatMessage>,
    ready: Boolean,
    downloadFailed: Boolean,
    progress: Float?,
    menuOn: Boolean,
    editing: Boolean,
    slots: List<String>,
    rowRect: Rect,
    bubbleRect: Rect,
    root: Offset,
    canReply: Boolean,
    canCopy: Boolean,
    canRetry: Boolean,
    canUnsend: Boolean,
    onEdit: () -> Unit,
    onDone: () -> Unit,
    onEmoji: (Int) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onUnsend: () -> Unit,
) {
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.toPx() }
    val margin = gap
    val rowTop = rowRect.top - root.y
    val rowH = rowRect.height.coerceAtLeast(1f)
    val rowX = rowRect.left - root.x
    var topGoal by remember(rowTop) { mutableFloatStateOf(rowTop) }
    var visGoal by remember(rowH) { mutableFloatStateOf(rowH) }
    val top = remember(rowTop) { Animatable(rowTop) }
    val vis = remember(rowH) { Animatable(rowH) }
    LaunchedEffect(topGoal) {
        top.animateTo(topGoal, Motion.spring(dampingRatio = 0.8f, stiffness = 400f))
    }
    LaunchedEffect(visGoal) {
        vis.animateTo(visGoal, Motion.spring(dampingRatio = 0.8f, stiffness = 400f))
    }
    val holder = remember { floatArrayOf(rowTop, rowH) }
    val outgoing = message.outgoing
    val cardOrigin = if (outgoing) TransformOrigin(1f, 0f) else TransformOrigin(0f, 0f)
    val cropped = vis.value < rowH - 1f
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            Box(
                Modifier
                    .width(with(density) { rowRect.width.toDp() })
                    .height(with(density) { vis.value.toDp().coerceAtLeast(1.dp) })
                    .graphicsLayer {
                        scaleX = 1.03f
                        scaleY = 1.03f
                        shadowElevation = 18.dp.toPx()
                        shape = RoundedCornerShape(LdRadius.tile)
                        clip = false
                        ambientShadowColor = Color.Black.copy(alpha = 0.28f)
                        spotShadowColor = Color.Black.copy(alpha = 0.22f)
                    }
                    .clipToBounds()
                    .drawWithContent {
                        drawContent()
                        if (cropped) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Black,
                                    0.72f to Color.Black,
                                    1f to Color.Transparent,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                    },
            ) {
                Box(Modifier.wrapContentHeight(unbounded = true, align = Alignment.Top)) {
                    ChatBubble(
                        message = message,
                        ready = ready,
                        downloadFailed = downloadFailed,
                        progress = progress,
                        onLongPress = { _, _ -> },
                        onOpenVisual = {},
                        onOpenExternal = {},
                        onOpenLink = {},
                        onRetryDownload = {},
                        onToggleReaction = {},
                        modifier = Modifier.fillMaxWidth(),
                        quote = quoteFor(message, messages),
                        lit = true,
                    )
                }
            }
            PopupScale(visible = menuOn, origin = TransformOrigin(0.5f, 1f)) {
                ReactionPill(
                    emojis = slots,
                    editing = editing,
                    onEmoji = onEmoji,
                    onEdit = onEdit,
                    onDone = onDone,
                )
            }
            PopupScale(visible = menuOn, origin = cardOrigin) {
                MessageActionCard(
                    canReply = canReply,
                    canCopy = canCopy,
                    canRetry = canRetry,
                    canUnsend = canUnsend,
                    onReply = onReply,
                    onCopy = onCopy,
                    onRetry = onRetry,
                    onDelete = onDelete,
                    onUnsend = onUnsend,
                )
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val bubble = measurables[0].measure(loose)
        val pill = measurables.getOrNull(1)?.measure(loose)
        val card = measurables.getOrNull(2)?.measure(loose)
        val pillH = (pill?.height ?: 0).toFloat()
        val cardH = (card?.height ?: 0).toFloat()
        val free = (constraints.maxHeight - pillH - cardH - gap * 2f - margin * 2f).coerceAtLeast(1f)
        val visTarget = rowH.coerceAtMost(free)
        val minTop = margin + pillH + gap
        val maxTop = (constraints.maxHeight - margin - cardH - gap - visTarget).coerceAtLeast(minTop)
        holder[0] = rowTop.coerceIn(minTop, maxTop)
        holder[1] = visTarget
        layout(constraints.maxWidth, constraints.maxHeight) {
            val y = top.value
            bubble.place(rowX.roundToInt(), y.roundToInt())
            if (pill != null) {
                val center = (bubbleRect.left + bubbleRect.right) / 2f - root.x
                val maxX = (constraints.maxWidth - pill.width - gap).toInt().coerceAtLeast(0)
                val x = (center - pill.width / 2f).roundToInt().coerceIn(0, maxX)
                pill.place(x, (y - gap - pill.height).roundToInt())
            }
            if (card != null) {
                val left = bubbleRect.left - root.x
                val right = bubbleRect.right - root.x
                val maxX = (constraints.maxWidth - card.width - gap).toInt().coerceAtLeast(0)
                val preferred = if (outgoing) right - card.width else left
                val x = preferred.roundToInt().coerceIn(0, maxX)
                card.place(x, (y + vis.value + gap).roundToInt())
            }
        }
    }
    SideEffect {
        if (topGoal != holder[0]) topGoal = holder[0]
        if (visGoal != holder[1]) visGoal = holder[1]
    }
}

@Composable
private fun JumpButton(
    visible: Boolean,
    count: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val desc = if (count > 0) {
        stringResource(R.string.chat_scroll_new, count)
    } else {
        stringResource(R.string.chat_scroll_down)
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(Motion.tween(160)) + scaleIn(
            animationSpec = Motion.tween(160),
            initialScale = 0.94f,
            transformOrigin = TransformOrigin(1f, 1f),
        ),
        exit = fadeOut(Motion.tween(110)) + scaleOut(
            animationSpec = Motion.tween(110),
            targetScale = 0.94f,
            transformOrigin = TransformOrigin(1f, 1f),
        ),
    ) {
        val press = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .pressScale(press)
                .heightIn(min = 48.dp)
                .widthIn(min = 48.dp)
                .ldGlass(CircleShape, solid = true)
                .clip(CircleShape)
                .clickable(
                    interactionSource = press,
                    indication = LocalIndication.current,
                    onClick = onClick,
                )
                .padding(horizontal = if (count > 0) 12.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            LuIcon(Lu.ChevronDown, contentDescription = desc, size = 20.dp, tint = LdColors.onGlass)
            if (count > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    count.coerceAtMost(99).toString(),
                    color = LdColors.onGlass,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun Composer(
    text: String,
    quoting: ChatMessage?,
    turn: Float,
    attachOpen: Boolean,
    recording: Boolean,
    recordLocked: Boolean,
    recordCancel: Boolean,
    recordMs: Int,
    levels: List<Int>,
    voiceDraft: VoiceClip?,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    onKeyboardSend: () -> Unit,
    onToggleAttach: () -> Unit,
    onDismissAttach: () -> Unit,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onStickers: () -> Unit,
    onActivities: () -> Unit,
    onFile: () -> Unit,
    onCancelReply: () -> Unit,
    onImage: (Uri, String) -> Unit,
    onMicDown: () -> Unit,
    onMicDrag: (Boolean) -> Unit,
    onMicUp: (Boolean, Boolean) -> Unit,
    onSendVoice: () -> Unit,
    onDeleteVoice: () -> Unit,
) {
    val down = rememberUpdatedState(onMicDown)
    val drag = rememberUpdatedState(onMicDrag)
    val up = rememberUpdatedState(onMicUp)
    val tools = !recording && voiceDraft == null
    val showSend = text.isNotBlank() || voiceDraft != null
    Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            if (tools) {
                GlassIconButton(
                    onClick = onCamera,
                    icon = Lu.Camera,
                    description = stringResource(R.string.chat_camera),
                    solid = true,
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .ldGlass(RoundedCornerShape(ChatTheme.composerRadius), solid = true),
            ) {
                if (quoting != null && tools) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.chat_replying),
                                style = MaterialTheme.typography.labelMedium,
                                color = LdColors.onGlassMuted,
                            )
                            Text(
                                replySnippet(quoting),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = LdColors.onGlass,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        MotionIconButton(
                            onClick = onCancelReply,
                            icon = Lu.X,
                            description = stringResource(R.string.chat_reply_cancel),
                        )
                    }
                }
                when {
                    recording -> VoiceRecordBar(recordMs, levels, recordCancel, recordLocked)
                    voiceDraft != null -> VoiceDraftBar(voiceDraft)
                    else -> ChatInputField(
                        text = text,
                        hint = stringResource(R.string.chat_hint),
                        onText = onText,
                        onKeyboardSend = onKeyboardSend,
                        onImage = onImage,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    )
                }
            }
            if (tools) {
                MotionIconButton(onGallery, Lu.Image, stringResource(R.string.chat_gallery))
                MotionIconButton(onStickers, Lu.Smile, stringResource(R.string.chat_tab_stickers))
            }
            if (voiceDraft != null) {
                MotionIconButton(onDeleteVoice, Lu.Trash2, stringResource(R.string.chat_voice_delete))
            }
            if (showSend) {
                MotionIconButton(
                    onClick = if (voiceDraft != null) onSendVoice else onSend,
                    icon = Lu.Send,
                    description = stringResource(R.string.chat_send),
                )
            } else {
                val micLabel = stringResource(
                    if (recordLocked) R.string.chat_voice_stop else R.string.chat_mic,
                )
                Box(
                    Modifier
                        .size(48.dp)
                        .semantics { contentDescription = micLabel }
                        .pointerInput(Unit) {
                            val cancelPx = 72.dp.toPx()
                            awaitEachGesture {
                                val press = awaitFirstDown()
                                val originX = press.position.x
                                val started = System.currentTimeMillis()
                                down.value()
                                var cancelled = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == press.id } ?: break
                                    if (!change.pressed) break
                                    cancelled = change.position.x - originX < -cancelPx
                                    drag.value(cancelled)
                                    change.consume()
                                }
                                val tap = System.currentTimeMillis() - started < 280L
                                up.value(tap, cancelled)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    LuIcon(
                        if (recordLocked) Lu.Pause else Lu.Mic,
                        contentDescription = micLabel,
                        tint = LdColors.onGlass,
                    )
                }
            }
            if (tools) {
                Box {
                    MotionIconButton(
                        onClick = onToggleAttach,
                        icon = Lu.Plus,
                        description = stringResource(R.string.chat_attach),
                        modifier = Modifier.graphicsLayer { rotationZ = turn },
                    )
                    AnchoredPopup(
                        visible = attachOpen,
                        above = true,
                        origin = TransformOrigin(1f, 1f),
                        onDismiss = onDismissAttach,
                    ) {
                        Column(
                            Modifier
                                .widthIn(min = 180.dp)
                                .ldGlass(RoundedCornerShape(LdRadius.card), solid = true)
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            AttachTile(0, Lu.Pencil, stringResource(R.string.chat_activities), onActivities)
                            AttachTile(1, Lu.FileText, stringResource(R.string.chat_file), onFile)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachTile(index: Int, icon: ImageVector, label: String, onClick: () -> Unit) {
    Stagger(index) {
        val press = remember(label) { MutableInteractionSource() }
        Row(
            Modifier
                .fillMaxWidth()
                .pressScale(press)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(LdRadius.inner(LdRadius.card, 8.dp)))
                .clickable(
                    interactionSource = press,
                    indication = LocalIndication.current,
                    onClick = onClick,
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LuIcon(icon, contentDescription = null, tint = LdColors.onGlass, size = 24.dp)
            Spacer(Modifier.width(12.dp))
            Text(label, color = LdColors.onGlass, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun MenuLine(icon: ImageVector, label: String, onClick: () -> Unit) {
    val press = remember(label) { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(press)
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = press,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LuIcon(icon, contentDescription = null, tint = LdColors.onGlass, size = 20.dp)
        Spacer(Modifier.width(12.dp))
        Text(label, color = LdColors.onGlass, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Stagger(index: Int, content: @Composable () -> Unit) {
    val on = Motion.enabled
    var shown by remember { mutableStateOf(!on) }
    LaunchedEffect(Unit) { shown = true }
    val delayMs = index.coerceIn(0, 7) * 30
    val alpha by animateFloatAsState(
        targetValue = if (!on || shown) 1f else 0f,
        animationSpec = Motion.tween(160, Motion.easeOut, delayMs = if (on) delayMs else 0),
        label = "tileA",
    )
    val dy by animateDpAsState(
        targetValue = if (!on || shown) 0.dp else 6.dp,
        animationSpec = Motion.tween(160, Motion.easeOut, delayMs = if (on) delayMs else 0),
        label = "tileY",
    )
    Box(
        Modifier.graphicsLayer {
            this.alpha = alpha
            translationY = dy.toPx()
        },
    ) { content() }
}

@Composable
private fun AnchoredPopup(
    visible: Boolean,
    above: Boolean,
    origin: TransformOrigin,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    var render by remember { mutableStateOf(false) }
    SideEffect {
        if (visible) render = true
    }
    LaunchedEffect(visible) {
        if (!visible && render) {
            delay(if (Motion.enabled) 130 else 0)
            if (!visible) render = false
        }
    }
    if (!render) return
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Popup(
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = if (above) anchorBounds.left else anchorBounds.right - popupContentSize.width
                val y = if (above) {
                    anchorBounds.top - popupContentSize.height - gap
                } else {
                    anchorBounds.bottom + gap
                }
                val maxX = (windowSize.width - popupContentSize.width - gap).coerceAtLeast(0)
                val maxY = (windowSize.height - popupContentSize.height - gap).coerceAtLeast(0)
                return IntOffset(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
            }
        },
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            clippingEnabled = false,
        ),
    ) {
        PopupScale(visible = visible, origin = origin) { content() }
    }
}

@Composable
private fun MotionIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val press = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = press,
        modifier = modifier.pressScale(press),
    ) {
        LuIcon(icon, contentDescription = description, tint = LdColors.onGlass)
    }
}

private sealed class ChatRow {
    class Msg(val message: ChatMessage) : ChatRow()
    class Day(val day: LocalDate) : ChatRow()
}

private fun buildRows(messages: List<ChatMessage>): List<ChatRow> {
    val sorted = messages.sortedWith { left, right ->
        val byTime = right.ts.compareTo(left.ts)
        if (byTime != 0) byTime else right.id.compareTo(left.id)
    }
    val rows = ArrayList<ChatRow>(sorted.size + 8)
    for (index in sorted.indices) {
        rows.add(ChatRow.Msg(sorted[index]))
        val day = chatDay(sorted[index].ts)
        val next = sorted.getOrNull(index + 1)?.let { chatDay(it.ts) }
        if (next != day) rows.add(ChatRow.Day(day))
    }
    return rows
}

private fun rowGap(rows: List<ChatRow>, index: Int): Dp {
    val older = rows.getOrNull(index + 1) ?: return 0.dp
    val here = rows[index]
    if (here !is ChatRow.Msg || older !is ChatRow.Msg) return 10.dp
    val left = here.message
    val right = older.message
    if (left.json.optString("t") == "note" || right.json.optString("t") == "note") return 10.dp
    return if (left.outgoing == right.outgoing) 2.dp else 10.dp
}

@Composable
private fun quoteFor(message: ChatMessage, all: List<ChatMessage>): String? {
    val id = ChatStore.normalizeId(message.json.optString("re")) ?: return null
    val found = all.firstOrNull { it.id == id }
    return if (found == null) stringResource(R.string.chat_reply_gone) else replySnippet(found)
}

private fun copyable(message: ChatMessage): String? {
    val text = message.json.optString("text")
    if (text.isNotBlank()) return text
    if (message.json.optString("t") == "link") {
        val url = message.json.optString("url")
        if (url.isNotBlank()) return url
    }
    return null
}

private fun openLink(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    url: String,
) {
    val uri = try {
        android.net.Uri.parse(url)
    } catch (_: Exception) {
        return
    }
    val scheme = uri.scheme?.lowercase() ?: return
    if (scheme != "https" && scheme != "http") return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: Exception) {
        scope.launch { snackbar.showSnackbar(context.getString(R.string.chat_open_failed)) }
    }
}

private fun openExternal(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    message: ChatMessage,
) {
    scope.launch {
        val name = message.json.optString("name").ifBlank { "file" }
        val mime = message.json.optString("mime").ifBlank {
            if (message.json.optString("kind") == "video") "video/*" else "*/*"
        }
        val file = try {
            withContext(Dispatchers.IO) { ChatStore.exportOpenFile(context, message.id, name) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (file == null) {
            snackbar.showSnackbar(context.getString(R.string.chat_open_failed))
            return@launch
        }
        val uri = try {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        } catch (_: Exception) {
            snackbar.showSnackbar(context.getString(R.string.chat_open_failed))
            return@launch
        }
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("file", uri)
        }
        try {
            context.startActivity(view)
        } catch (_: Exception) {
            val saved = try {
                withContext(Dispatchers.IO) {
                    val bytes = ChatStore.readAttachment(context, message.id) ?: return@withContext false
                    ChatGallery.saveDownload(context, name, mime, bytes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            snackbar.showSnackbar(
                context.getString(if (saved) R.string.chat_saved else R.string.chat_open_failed),
            )
        }
    }
}

private fun draftFromUri(
    context: android.content.Context,
    uri: Uri,
    fromCamera: Boolean,
    video: Boolean? = null,
): ChatDraft {
    val mime = context.contentResolver.getType(uri).orEmpty()
    val isVideo = video ?: mime.startsWith("video/")
    return ChatDraft(
        key = System.nanoTime(),
        uri = uri,
        bytes = null,
        mime = mime.ifBlank { if (isVideo) "video/mp4" else "image/jpeg" },
        name = "",
        video = isVideo,
        asFile = false,
        fromCamera = fromCamera,
    )
}

private fun displayName(context: android.content.Context, uri: Uri): String {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) null else cursor.getString(index)
        } ?: "file"
    } catch (_: Exception) {
        "file"
    }
}
