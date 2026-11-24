@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.loviedowpie.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.loviedowpie.AppState
import app.loviedowpie.ChatGallery
import app.loviedowpie.ChatMessage
import app.loviedowpie.ChatNotifications
import app.loviedowpie.ChatStore
import app.loviedowpie.FileTransfer
import app.loviedowpie.Inbox
import app.loviedowpie.R
import app.loviedowpie.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

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
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        ChatBody(onOpenCamera = onOpenCamera, onOpenSettings = { settings = true }, onOpenPhoto = { viewing = it })
        if (settings) {
            ChatSettings(onBack = { settings = false })
        }
        val photo = viewing
        if (photo != null) {
            PhotoViewer(id = photo, onClose = { viewing = null })
        }
    }
}

@Composable
private fun ChatBody(
    onOpenCamera: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPhoto: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val storeRev by Store.changes.collectAsState()
    val chatRev by ChatStore.changes.collectAsState()
    val failedDownloads by ChatStore.failedDownloads.collectAsState()
    val fileProgress by FileTransfer.progress.collectAsState()
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var ready by remember { mutableStateOf<Set<String>>(emptySet()) }
    var text by rememberSaveable { mutableStateOf("") }
    var menuId by remember { mutableStateOf<String?>(null) }
    var replyId by remember { mutableStateOf(ChatController.peekReply()) }
    var attachOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    val name = remember(storeRev) {
        Store.partnerName(context).ifBlank { context.getString(R.string.partner_fallback) }
    }
    val wallpaper = remember(storeRev) { Store.chatWallpaper(context) }

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

    val today = LocalDate.now()
    val rows = remember(messages, today) { buildRows(messages) }
    val selected = messages.firstOrNull { it.id == menuId }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            ChatWallpaper(wallpaper)
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .ldGlass(RoundedCornerShape(32.dp), strong = true)
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
                    )
                    Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.chat_menu))
                            }
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_settings)) },
                                    onClick = {
                                        overflowOpen = false
                                        onOpenSettings()
                                    },
                                )
                            }
                        }
                    }
                if (rows.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.chat_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(
                            items = rows,
                            key = { row ->
                                when (row) {
                                    is ChatRow.Msg -> row.message.id
                                    is ChatRow.Day -> "day-${row.day}"
                                }
                            },
                        ) { row ->
                            when (row) {
                                is ChatRow.Day -> ChatDateLabel(chatDayLabel(row.day, today))
                                is ChatRow.Msg -> {
                                    val message = row.message
                                    val fid = message.json.optString("fid").trim().lowercase()
                                    ChatBubble(
                                        message = message,
                                        ready = message.id in ready,
                                        downloadFailed = message.id in failedDownloads,
                                        progress = fileProgress[fid],
                                        onLongPress = { menuId = message.id },
                                        onOpenPhoto = { onOpenPhoto(message.id) },
                                        onOpenExternal = { openExternal(context, scope, snackbar, message) },
                                        onOpenLink = { url -> openLink(context, scope, snackbar, url) },
                                        onRetryDownload = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) { ChatStore.fetch(context, message.id) }
                                            }
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
                Column(
                    Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .ldGlass(RoundedCornerShape(28.dp), strong = true),
                ) {
                    val quoting = replyId
                    if (quoting != null) {
                        val quoted = messages.firstOrNull { it.id == quoting }
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
                                    if (quoted == null) {
                                        stringResource(R.string.chat_reply_gone)
                                    } else {
                                        replySnippet(quoted)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = LdColors.colors.ink,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            IconButton(onClick = {
                                replyId = null
                                ChatController.armReply(null)
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.chat_reply_cancel))
                            }
                        }
                    }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                        Box {
                            IconButton(onClick = { attachOpen = true }) {
                                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.chat_attach))
                            }
                            DropdownMenu(expanded = attachOpen, onDismissRequest = { attachOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_gallery)) },
                                    onClick = {
                                        attachOpen = false
                                        try {
                                            gallery.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                                            )
                                        } catch (_: Exception) {
                                            scope.launch { snackbar.showSnackbar(context.getString(R.string.chat_attach_failed)) }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_file)) },
                                    onClick = {
                                        attachOpen = false
                                        try {
                                            documents.launch(arrayOf("*/*"))
                                        } catch (_: Exception) {
                                            scope.launch { snackbar.showSnackbar(context.getString(R.string.chat_attach_failed)) }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_camera)) },
                                    onClick = {
                                        attachOpen = false
                                        onOpenCamera()
                                    },
                                )
                            }
                        }
                        TextField(
                            value = text,
                            onValueChange = { text = it.take(10_000) },
                            modifier = Modifier.weight(1f).contentReceiver(object : ReceiveContentListener {
                              override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                                val content = transferableContent
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
                                return if (consumed) null else content
                              }
                            }),
                            placeholder = { Text(stringResource(R.string.chat_hint)) },
                            maxLines = 5,
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
                        IconButton(
                            onClick = {
                                val current = text
                                if (current.isBlank()) return@IconButton
                                val id = replyId
                                text = ""
                                replyId = null
                                ChatController.sendText(context, current, id)
                            },
                            enabled = text.isNotBlank(),
                        ) {
                            Icon(Icons.Filled.Send, contentDescription = stringResource(R.string.chat_send))
                        }
                    }
                }
            }
        }
    }

    if (selected != null) {
        MessageActions(
            message = selected,
            onDismiss = { menuId = null },
            onCopy = { value ->
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("text", value))
                menuId = null
                scope.launch { snackbar.showSnackbar(context.getString(R.string.chat_copied)) }
            },
            onRetry = {
                menuId = null
                ChatController.retry(context, selected.id)
            },
            onDelete = {
                menuId = null
                ChatController.delete(context, selected.id)
            },
            onReact = { emoji ->
                menuId = null
                ChatController.react(context, selected.id, emoji)
            },
            onReply = {
                menuId = null
                replyId = selected.id
                ChatController.armReply(selected.id)
            },
        )
    }
}

@Composable
private fun ChatWallpaper(spec: String) {
    val context = LocalContext.current
    var bitmap by remember(spec) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(spec) {
        bitmap = if (spec == "image") {
            withContext(Dispatchers.IO) {
                val bytes = ChatStore.readWallpaper(context) ?: return@withContext null
                ChatMedia.decodeForDisplay(bytes, 1440)
            }
        } else {
            null
        }
    }
    val index = spec.removePrefix("color:").toIntOrNull()
    val color = if (spec.startsWith("color:") && index != null && index in ChatTheme.wallpapers.indices) {
        ChatTheme.wallpapers[index]
    } else {
        Color.Transparent
    }
    Box(Modifier.fillMaxSize().background(color)) {
        val shown = bitmap
        if (spec == "image" && shown != null) {
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
private fun MessageActions(
    message: ChatMessage,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
) {
    val copy = copyable(message)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_actions)) },
        text = {
            Column {
                if (copy != null) {
                    TextButton(onClick = { onCopy(copy) }) { Text(stringResource(R.string.chat_copy)) }
                }
                if (message.outgoing && message.status == ChatStore.FAILED) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.chat_retry)) }
                }
                TextButton(onClick = onReply) { Text(stringResource(R.string.chat_reply)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.chat_delete)) }
                Text(stringResource(R.string.chat_react), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("❤", "😂", "👍", "😮", "😢", "🙏").forEach { emoji ->
                        TextButton(onClick = { onReact(emoji) }) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_close)) }
        },
    )
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
