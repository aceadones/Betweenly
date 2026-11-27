package com.nextlayer.betweenly.ui

import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.ChatMessage
import com.nextlayer.betweenly.ChatStore
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.Store
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The shared photo frame: photos you both post, one per row at full width, each with comments.
 * A comment can be replied to and any comment (or photo) can get an emoji reaction.
 * Photos are ordinary end-to-end encrypted attachments flagged `fr`; comments are small `fcom`
 * messages. They live in the chat store but never show in the chat.
 *
 * DATA-FLOW: the same encrypted paths as chat (file upload plus a message pointer). Nothing else leaves the phone.
 */
private val quickEmoji = listOf("\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDC4D", "\uD83D\uDD25", "\uD83D\uDE4F")

@Composable
fun FrameScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val rev by ChatStore.changes.collectAsState()
    var posts by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var comments by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    LaunchedEffect(rev) {
        val all = withContext(Dispatchers.IO) { ChatStore.list(context) }
        posts = all.filter { it.json.optInt("fr") == 1 && it.json.optString("t") == "att" }
        comments = all.filter { it.json.optString("t") == "fcom" }.sortedBy { it.ts }
    }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pending = uri
    }
    BackHandler { if (openId != null) openId = null else onBack() }
    val open = posts.firstOrNull { it.id == openId }
    Box(Modifier.fillMaxSize()) {
        AppBackground(Modifier.fillMaxSize())
        if (open != null) {
            PostDetail(open, comments.filter { it.json.optString("p") == open.id }, onBack = { openId = null })
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { ScreenHeader(stringResource(R.string.frame_title), onBack) }
                    GlassIconButton(
                        {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        Lu.ImagePlus,
                        stringResource(R.string.frame_add),
                    )
                }
                if (posts.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.frame_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = LdColors.colors.inkMuted,
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(posts, key = { it.id }) { post ->
                            PostCard(
                                post,
                                comments.count { it.json.optString("p") == post.id },
                                onOpen = { openId = post.id },
                            )
                        }
                    }
                }
            }
        }
        val uri = pending
        if (uri != null) {
            var caption by remember(uri) { mutableStateOf("") }
            val preview by produceBitmap(uri)
            Box(
                Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { pending = null },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .padding(24.dp)
                        .clip(RoundedCornerShape(LdRadius.card))
                        .background(Color(0xFF1B1015))
                        .clickable(enabled = false) {}
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    preview?.let {
                        Image(
                            it.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    BasicTextField(
                        value = caption,
                        onValueChange = { caption = it.take(500) },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x22FFFFFF))
                            .padding(12.dp),
                        decorationBox = { inner ->
                            if (caption.isEmpty()) Text(stringResource(R.string.frame_caption), color = Color(0x99FFFFFF))
                            inner()
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.frame_cancel),
                            modifier = Modifier.clickable { pending = null }.padding(8.dp),
                            color = Color.White,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            stringResource(R.string.frame_post),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(LdColors.primary)
                                .clickable {
                                    com.nextlayer.betweenly.ui.ChatController.sendFramePhoto(context, uri, caption)
                                    pending = null
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

/** Decodes a small preview of [uri] off the main thread. */
@Composable
private fun produceBitmap(uri: Uri): androidx.compose.runtime.State<Bitmap?> {
    val context = LocalContext.current
    return androidx.compose.runtime.produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                sampleUri(context, uri, 720)
            } catch (_: Exception) {
                null
            }
        }
    }
}

@Composable
private fun PostCard(post: ChatMessage, commentCount: Int, onOpen: () -> Unit) {
    val context = LocalContext.current
    val name = authorName(context, post.outgoing)
    val shape = RoundedCornerShape(LdRadius.card)
    Column(
        Modifier
            .fillMaxWidth()
            .ldGlass(shape)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Avatar(name)
            Column {
                Text(name, style = MaterialTheme.typography.titleSmall, color = LdColors.onGlass)
                Text(lastSeenPhrase(context, post.ts), style = MaterialTheme.typography.labelSmall, color = LdColors.onGlassMuted)
            }
        }
        FramePhoto(post, Modifier.fillMaxWidth().clickable(onClick = onOpen))
        val caption = post.json.optString("text")
        if (caption.isNotBlank()) {
            Text(
                caption,
                modifier = Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = LdColors.onGlass,
            )
        }
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ReactionRow(post)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clickable(onClick = onOpen).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LuIcon(Lu.MessageCircle, null, tint = LdColors.onGlass, size = 20.dp)
                Text(
                    if (commentCount == 0) stringResource(R.string.frame_comment) else commentCount.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = LdColors.onGlass,
                )
            }
        }
    }
}

/** Heart button plus the reactions already on this photo or comment. Tap the heart to toggle. */
@Composable
private fun ReactionRow(message: ChatMessage) {
    val context = LocalContext.current
    val mine = message.json.optString("mx")
    val theirs = message.json.optString("rx")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (mine == "\u2764\uFE0F") "\u2764\uFE0F" else "\uD83E\uDD0D",
            modifier = Modifier.clickable { ChatController.react(context, message.id, "\u2764\uFE0F") }.padding(4.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        for (e in listOf(mine, theirs).filter { it.isNotEmpty() && it != "\u2764\uFE0F" || it == theirs }.distinct()) {
            if (e == mine && e == "\u2764\uFE0F") continue
            Text(e, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun FramePhoto(post: ChatMessage, modifier: Modifier, fixed: Boolean = false) {
    val context = LocalContext.current
    val w = post.json.optInt("w")
    val h = post.json.optInt("h")
    val ratio = if (w > 0 && h > 0) (w.toFloat() / h.toFloat()).coerceIn(0.75f, 1.5f) else 1f
    LaunchedEffect(post.id) {
        val has = withContext(Dispatchers.IO) { ChatStore.hasAttachment(context, post.id) }
        if (!has && !post.outgoing) ChatStore.fetch(context, post.id)
    }
    var thumb by remember(post.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(post.id) {
        val b64 = post.json.optString("thumb")
        thumb = if (b64.isEmpty()) null else withContext(Dispatchers.IO) {
            try {
                ChatMedia.decodeForDisplay(Base64.decode(b64, Base64.DEFAULT), 480)
            } catch (_: Exception) {
                null
            }
        }
    }
    Box((if (fixed) modifier else modifier.aspectRatio(ratio)).background(Color(0x22000000))) {
        thumb?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        ChatFrame(post.id, false, 1080, Modifier.fillMaxSize(), stringResource(R.string.frame_photo_cd), ContentScale.Crop)
    }
}

@Composable
private fun Avatar(name: String) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(LdColors.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.titleSmall)
    }
}

private fun authorName(context: android.content.Context, mine: Boolean): String =
    if (mine) Store.myName(context).ifBlank { context.getString(R.string.you) }
    else Store.partnerName(context).ifBlank { context.getString(R.string.partner_default) }

@Composable
private fun PostDetail(post: ChatMessage, comments: List<ChatMessage>, onBack: () -> Unit) {
    val context = LocalContext.current
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    var draft by remember { mutableStateOf("") }
    var reacting by remember { mutableStateOf<String?>(null) }
    // Replies of replies join their root comment, so the thread stays one level deep.
    val byId = comments.associateBy { it.id }
    fun root(c: ChatMessage): String {
        var cur = c
        var guard = 0
        while (guard++ < 20) {
            val parent = byId[cur.json.optString("r")] ?: break
            cur = parent
        }
        return cur.id
    }
    val tops = comments.filter { byId[it.json.optString("r")] == null }
    val replies = comments.filter { byId[it.json.optString("r")] != null }.groupBy { root(it) }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            ScreenHeader(authorName(context, post.outgoing), onBack)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "photo") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FramePhoto(post, Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(LdRadius.card)), fixed = true)
                    val caption = post.json.optString("text")
                    if (caption.isNotBlank()) {
                        Text(caption, style = MaterialTheme.typography.bodyLarge, color = LdColors.colors.ink)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        quickEmoji.forEach { e ->
                            Text(
                                e,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (post.json.optString("mx") == e) Color(0x33D62448) else Color.Transparent)
                                    .clickable { ChatController.react(context, post.id, e) }
                                    .padding(6.dp),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    val seen = listOf(post.json.optString("mx"), post.json.optString("rx")).filter { it.isNotEmpty() }
                    if (seen.isNotEmpty()) {
                        Text(seen.joinToString(" "), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        stringResource(R.string.frame_comments_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = LdColors.colors.inkMuted,
                    )
                }
            }
            tops.forEach { c ->
                item(key = c.id) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CommentRow(c, 0, reacting == c.id, { reacting = if (reacting == c.id) null else c.id }, { replyTo = c })
                        replies[c.id].orEmpty().forEach { r ->
                            CommentRow(r, 1, reacting == r.id, { reacting = if (reacting == r.id) null else r.id }, { replyTo = r })
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            replyTo?.let { target ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x22000000))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.frame_replying, authorName(context, target.outgoing)),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = LdColors.colors.ink,
                    )
                    Text("\u2715", modifier = Modifier.clickable { replyTo = null }.padding(4.dp), color = LdColors.colors.ink)
                }
            }
            Row(
                Modifier.fillMaxWidth().ldGlass(RoundedCornerShape(50)).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it.take(500) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = LdColors.onGlass),
                    cursorBrush = SolidColor(LdColors.onGlass),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                        if (draft.isNotBlank()) {
                            ChatController.sendComment(context, post.id, replyTo?.id, draft)
                            draft = ""
                            replyTo = null
                        }
                    }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (draft.isEmpty()) Text(stringResource(R.string.frame_write), color = LdColors.onGlassMuted)
                        inner()
                    },
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (draft.isBlank()) Color(0x33808080) else LdColors.primary)
                        .clickable(enabled = draft.isNotBlank()) {
                            ChatController.sendComment(context, post.id, replyTo?.id, draft)
                            draft = ""
                            replyTo = null
                        },
                    contentAlignment = Alignment.Center,
                ) { LuIcon(Lu.Send, stringResource(R.string.chat_send), tint = Color.White, size = 20.dp) }
            }
        }
    }
}

@Composable
private fun CommentRow(c: ChatMessage, depth: Int, reactOpen: Boolean, onToggleReact: () -> Unit, onReply: () -> Unit) {
    val context = LocalContext.current
    val name = authorName(context, c.outgoing)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = (depth * 28).dp)
            .ldGlass(RoundedCornerShape(LdRadius.card))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Avatar(name)
            Text(name, style = MaterialTheme.typography.titleSmall, color = LdColors.onGlass)
            Text(lastSeenPhrase(context, c.ts), style = MaterialTheme.typography.labelSmall, color = LdColors.onGlassMuted)
        }
        Text(c.json.optString("text"), style = MaterialTheme.typography.bodyLarge, color = LdColors.onGlass)
        val chips = listOf(c.json.optString("mx"), c.json.optString("rx")).filter { it.isNotEmpty() }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                stringResource(R.string.frame_react),
                modifier = Modifier.clickable(onClick = onToggleReact),
                style = MaterialTheme.typography.labelLarge,
                color = LdColors.primary,
            )
            Text(
                stringResource(R.string.frame_reply),
                modifier = Modifier.clickable(onClick = onReply),
                style = MaterialTheme.typography.labelLarge,
                color = LdColors.primary,
            )
            if (chips.isNotEmpty()) Text(chips.joinToString(" "), style = MaterialTheme.typography.titleMedium)
        }
        if (reactOpen) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                quickEmoji.forEach { e ->
                    Text(
                        e,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (c.json.optString("mx") == e) Color(0x33D62448) else Color.Transparent)
                            .clickable {
                                ChatController.react(context, c.id, e)
                                onToggleReact()
                            }
                            .padding(6.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}
