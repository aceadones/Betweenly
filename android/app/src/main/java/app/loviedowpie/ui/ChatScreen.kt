@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.loviedowpie.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.FileProvider
import app.loviedowpie.AppState
import app.loviedowpie.ChatGallery
import app.loviedowpie.ChatMessage
import app.loviedowpie.ChatNotifications
import app.loviedowpie.ChatStore
import app.loviedowpie.ChatWallpaperSync
import app.loviedowpie.FileTransfer
import app.loviedowpie.Inbox
import app.loviedowpie.R
import app.loviedowpie.Store
import app.loviedowpie.ui.icons.Lu
import app.loviedowpie.ui.icons.LuIcon
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
 * The paired chat. [onOpenCamera] opens the in-app camera; its result comes
 * back through [ChatController.sendMedia].
 */
@Composable
fun ChatScreen(onOpenCamera: () -> Unit) {
    val context = LocalContext.current
    SideEffect { ChatController.install(context) }
    DisposableEffect(Unit) {
        AppState.chatOpen = true
        ChatStore.markRead(context)
        ChatNotifications.cancel(context)
        onDispose { AppState.chatOpen = false }
    }
    var settings by rememberSaveable { mutableStateOf(false) }
    var background by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        ChatBody(
            onOpenCamera = onOpenCamera,
            onOpenSettings = { settings = true },
            onOpenBackground = { background = true },
        )
        if (background) ChatBackground(onBack = { background = false })
        if (settings) ChatSettings(onBack = { settings = false })
    }
}

@Composable
private fun ChatBody(
    onOpenCamera: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBackground: () -> Unit,
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
    var armed by remember { mutableStateOf("") }
    val draft = rememberUpdatedState(text)
    var replyId by remember { mutableStateOf(ChatController.peekReply()) }
    var attachOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var menuId by remember { mutableStateOf<String?>(null) }
    var menuOn by remember { mutableStateOf(false) }
    var renderMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var slots by remember { mutableStateOf(ChatReactions.defaults) }
    var pickerSlot by remember { mutableStateOf<Int?>(null) }
    var rowRect by remember { mutableStateOf(Rect.Zero) }
    var bubbleRect by remember { mutableStateOf(Rect.Zero) }
    var rootAt by remember { mutableStateOf(Offset.Zero) }
    var viewingId by remember { mutableStateOf<String?>(null) }
    var viewingRect by remember { mutableStateOf<Rect?>(null) }
    var composerPx by remember { mutableIntStateOf(0) }
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

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val id = replyId
            replyId = null
            ChatController.sendPicked(context, uri, id)
        }
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val id = replyId
            replyId = null
            ChatController.sendDocument(context, uri, id)
        }
    }

    fun sendDraft(fromKeyboard: Boolean = false) {
        val current = if (fromKeyboard) text.trim() else text.trim().ifEmpty { armed.trim() }
        if (current.isEmpty()) {
            armed = ""
            return
        }
        val id = replyId
        val queued = ChatController.sendText(context, current, id)
        if (!queued) return
        armed = ""
        text = ""
        replyId = null
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

    BackHandler(enabled = menuOn && pickerSlot == null, onBack = { closeMenu() })

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .onGloballyPositioned { rootAt = it.positionInRoot() },
        ) {
            ChatWallBackdrop(wall, Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .ldGlass(RoundedCornerShape(LdRadius.card), solid = true)
                        .padding(start = 6.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundAvatar(storeRev, { Store.partnerAvatar(context) }, name, 36.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        name,
                        style = MaterialTheme.typography.titleLarge,
                        color = LdColors.colors.ink,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box {
                        MotionIconButton(
                            onClick = {
                                overflowOpen = !overflowOpen
                                if (overflowOpen) attachOpen = false
                            },
                            icon = Lu.EllipsisVertical,
                            description = stringResource(R.string.chat_menu),
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
                                MenuLine(Lu.Trash2, stringResource(R.string.chat_clear)) {
                                    overflowOpen = false
                                    confirmClear = true
                                }
                            }
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (rows.isEmpty()) {
                        Box(
                            Modifier.fillMaxSize().padding(bottom = composerHeight),
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
                                top = 8.dp,
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
                    Composer(
                        text = text,
                        armed = armed,
                        quoting = replyId?.let { id -> messages.firstOrNull { it.id == id } },
                        turn = turn,
                        attachOpen = attachOpen,
                        onText = { incoming ->
                            val next = incoming.take(10_000)
                            val previous = text
                            text = next
                            if (next.isEmpty() && armed.trim() != previous.trim()) armed = ""
                        },
                        onArm = { armed = draft.value },
                        onSend = { sendDraft() },
                        onKeyboardSend = { sendDraft(fromKeyboard = true) },
                        onToggleAttach = {
                            attachOpen = !attachOpen
                            if (attachOpen) overflowOpen = false
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
                        onCamera = {
                            attachOpen = false
                            onOpenCamera()
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
                        receiveImage = { content ->
                            var consumed = false
                            try {
                                val clip = content.clipEntry.clipData
                                for (i in 0 until clip.itemCount) {
                                    val uri = clip.getItemAt(i).uri ?: continue
                                    val mime = context.contentResolver.getType(uri)
                                        ?: clip.description?.getMimeType(i)
                                        ?: ""
                                    if (mime.isNotEmpty() && !mime.startsWith("image/")) continue
                                    when (val read = ChatMedia.readNow(context, uri, ChatMedia.MAX_BYTES)) {
                                        is ReadBytes.Ok -> {
                                            consumed = true
                                            val id = replyId
                                            replyId = null
                                            ChatController.sendRich(context, read.data, mime, id)
                                        }
                                        is ReadBytes.TooBig -> {
                                            consumed = true
                                            ChatController.note(R.string.chat_file_too_large)
                                        }
                                        is ReadBytes.Failed -> Unit
                                    }
                                }
                            } catch (_: Exception) {
                                // The keyboard image could not be read. Typed text still works.
                            }
                            if (consumed) null else content
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .onSizeChanged { composerPx = it.height },
                    )
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
                    root = rootAt,
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
            EmojiPicker(
                visible = pickerSlot != null,
                recent = if (pickerSlot == null) emptyList() else ChatReactions.recent(context),
                onPick = { emoji ->
                    val slot = pickerSlot ?: return@EmojiPicker
                    val next = slots.toMutableList()
                    if (slot in next.indices) next[slot] = emoji
                    slots = next
                    ChatReactions.pushRecent(context, emoji)
                    pickerSlot = null
                },
                onDismiss = { pickerSlot = null },
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            modifier = Modifier.ldPopupFrame(),
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = LdColors.colors.menu,
            tonalElevation = 0.dp,
            titleContentColor = LdColors.colors.ink,
            textContentColor = LdColors.colors.ink,
            title = { Text(stringResource(R.string.chat_clear_title)) },
            text = { Text(stringResource(R.string.chat_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            try {
                                ChatStore.clearHistory(context)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                false
                            }
                        }
                        if (!ok) snackbar.showSnackbar(context.getString(R.string.chat_clear_failed))
                    }
                }) { Text(stringResource(R.string.chat_clear_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.chat_close))
                }
            },
        )
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
    root: Offset,
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
    val density = LocalDensity.current
    val type = message?.json?.optString("t").orEmpty()
    val gone = type == "gone"
    val canReply = message != null && !gone
    val canCopy = message?.let { copyable(it) } != null && !gone
    val canRetry = message != null && message.outgoing && message.status == ChatStore.FAILED && !gone
    val canUnsend = message != null && message.outgoing && !gone
    val gap = with(density) { 8.dp.roundToPx() }
    Box(Modifier.fillMaxSize()) {
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
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (rowRect.left - root.x).roundToInt(),
                            (rowRect.top - root.y).roundToInt(),
                        )
                    }
                    .width(with(density) { rowRect.width.toDp() }),
                quote = quoteFor(message, messages),
                lit = true,
            )
        }
        if (message != null) {
            val left = bubbleRect.left - root.x
            val right = bubbleRect.right - root.x
            val top = bubbleRect.top - root.y
            val bottom = bubbleRect.bottom - root.y
            val center = (left + right) / 2f
            val outgoing = message.outgoing
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    PopupScale(visible = menuOn, origin = TransformOrigin(0.5f, 1f)) {
                        ReactionPill(
                            emojis = slots,
                            editing = editing,
                            onEmoji = onEmoji,
                            onEdit = onEdit,
                            onDone = onDone,
                        )
                    }
                },
            ) { measurables, constraints ->
                val placeable = measurables.firstOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    if (placeable != null) {
                        val maxX = (constraints.maxWidth - placeable.width - gap).coerceAtLeast(0)
                        val x = (center - placeable.width / 2f).roundToInt().coerceIn(0, maxX)
                        val y = (top - gap - placeable.height).roundToInt().coerceAtLeast(0)
                        placeable.place(x, y)
                    }
                }
            }
            val origin = if (outgoing) TransformOrigin(1f, 0f) else TransformOrigin(0f, 0f)
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    PopupScale(visible = menuOn, origin = origin) {
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
                val placeable = measurables.firstOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    if (placeable != null) {
                        val maxX = (constraints.maxWidth - placeable.width - gap).coerceAtLeast(0)
                        val maxY = (constraints.maxHeight - placeable.height - gap).coerceAtLeast(0)
                        val preferred = if (outgoing) right - placeable.width else left
                        val x = preferred.roundToInt().coerceIn(0, maxX)
                        val y = (bottom + gap).roundToInt().coerceIn(0, maxY)
                        placeable.place(x, y)
                    }
                }
            }
        }
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
            LuIcon(Lu.ChevronDown, contentDescription = desc, size = 20.dp, tint = LdColors.colors.ink)
            if (count > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    count.coerceAtMost(99).toString(),
                    color = LdColors.colors.ink,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun Composer(
    text: String,
    armed: String,
    quoting: ChatMessage?,
    turn: Float,
    attachOpen: Boolean,
    onText: (String) -> Unit,
    onArm: () -> Unit,
    onSend: () -> Unit,
    onKeyboardSend: () -> Unit,
    onToggleAttach: () -> Unit,
    onDismissAttach: () -> Unit,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onFile: () -> Unit,
    onCancelReply: () -> Unit,
        receiveImage: (TransferableContent) -> TransferableContent?,
    modifier: Modifier = Modifier,
) {
    val showSend = text.isNotBlank() || armed.isNotBlank()
    val outer = ChatTheme.composerRadius
    Column(
        modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .ldGlass(RoundedCornerShape(outer), solid = true)
            .padding(4.dp),
    ) {
        if (quoting != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.chat_replying),
                        style = MaterialTheme.typography.labelMedium,
                        color = LdColors.colors.inkMuted,
                    )
                    Text(
                        replySnippet(quoting),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = LdColors.colors.ink,
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
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
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
                    origin = TransformOrigin(0f, 1f),
                    onDismiss = onDismissAttach,
                ) {
                    Column(
                        Modifier
                            .widthIn(min = 180.dp)
                            .ldGlass(RoundedCornerShape(LdRadius.card), solid = true)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        AttachTile(0, Lu.Image, stringResource(R.string.chat_gallery), onGallery)
                        AttachTile(1, Lu.Camera, stringResource(R.string.chat_camera), onCamera)
                        AttachTile(2, Lu.FileText, stringResource(R.string.chat_file), onFile)
                    }
                }
            }
            TextField(
                value = text,
                onValueChange = onText,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onKeyboardSend() }),
                modifier = Modifier.weight(1f).contentReceiver(object : ReceiveContentListener {
                    override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                        return receiveImage(transferableContent)
                    }
                }),
                placeholder = { Text(stringResource(R.string.chat_hint)) },
                maxLines = 5,
                shape = RoundedCornerShape(LdRadius.inner(outer, 4.dp)),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )
            AnimatedVisibility(
                visible = showSend,
                enter = fadeIn(Motion.tween(160)) + scaleIn(
                    animationSpec = Motion.tween(160),
                    initialScale = 0.94f,
                    transformOrigin = TransformOrigin(1f, 0.5f),
                ),
                exit = fadeOut(Motion.tween(110)) + scaleOut(
                    animationSpec = Motion.tween(110),
                    targetScale = 0.94f,
                    transformOrigin = TransformOrigin(1f, 0.5f),
                ),
            ) {
                MotionIconButton(
                    onClick = onSend,
                    icon = Lu.Send,
                    description = stringResource(R.string.chat_send),
                    modifier = Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.any { it.changedToDown() }) onArm()
                            }
                        }
                    },
                )
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
            LuIcon(icon, contentDescription = null, tint = LdColors.colors.ink, size = 24.dp)
            Spacer(Modifier.width(12.dp))
            Text(label, color = LdColors.colors.ink, style = MaterialTheme.typography.bodyLarge)
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
        LuIcon(icon, contentDescription = null, tint = LdColors.colors.ink, size = 20.dp)
        Spacer(Modifier.width(12.dp))
        Text(label, color = LdColors.colors.ink, style = MaterialTheme.typography.bodyLarge)
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
        LuIcon(icon, contentDescription = description, tint = LdColors.colors.ink)
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
