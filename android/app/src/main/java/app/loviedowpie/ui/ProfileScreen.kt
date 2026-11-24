package app.loviedowpie.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.loviedowpie.BuildConfig
import app.loviedowpie.ChatStore
import app.loviedowpie.DrawSend
import app.loviedowpie.DrawStore
import app.loviedowpie.Outbox
import app.loviedowpie.R
import app.loviedowpie.SavedDrawing
import app.loviedowpie.Store
import app.loviedowpie.UpdateCheck
import app.loviedowpie.UpdateInfo
import app.loviedowpie.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException

/**
 * Profile tab. Name and photo use the existing encrypted profile message.
 * Saved drawings stay on this phone until Send again, which uses [DrawSend].
 *
 * Replaces the old settings screen. Sharing switches stay on the Location tab.
 */
@Composable
fun ProfileScreen(onPrivacy: () -> Unit, onUnpaired: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val revision by Store.changes.collectAsState()
    val drawRev by DrawStore.changes.collectAsState()
    var name by rememberSaveable { mutableStateOf(Store.myName(context)) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var viewingTs by rememberSaveable { mutableStateOf(-1L) }
    val safety = remember(revision) { Store.safetyNumber(context) }
    val drawings = remember(drawRev) { DrawStore.list(context) }
    val notifyMessages = remember(revision) { Store.notifyMessages(context) }
    val notifyDrawings = remember(revision) { Store.notifyDrawings(context) }

    fun sendProfile() {
        scope.launch {
            val ok = try {
                // DATA-FLOW: my name and photo, encrypted, to my partner, with an empty wake ping.
                Outbox.sendProfile(context, wake = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            snackbar.showSnackbar(
                context.getString(if (ok) R.string.profile_sent else R.string.profile_failed),
            )
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = LdColors.colors.menu,
            titleContentColor = LdColors.colors.ink,
            textContentColor = LdColors.colors.ink,
            title = { Text(stringResource(R.string.unpair_title)) },
            text = { Text(stringResource(R.string.unpair_body_v2)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        val app = context.applicationContext
                        try {
                            ChatStore.wipe(app)
                        } catch (_: Exception) {
                            // Chat data should be gone. Unpair still runs.
                        }
                        try {
                            DrawStore.wipe(app)
                            DrawingWidget.updateAll(app)
                        } catch (_: Exception) {
                            // Drawings are app-private. A failed delete must not skip unpair.
                        }
                        Store.unpair(app)
                        PairSession.reset()
                        LoviedowpieWidget.updateAll(app)
                        onUnpaired()
                    },
                ) { Text(stringResource(R.string.unpair_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.pair_cancel)) }
            },
        )
    }

    if (viewingTs > 0L) {
        DrawingViewer(
            ts = viewingTs,
            onClose = { viewingTs = -1L },
            onDeleted = {
                viewingTs = -1L
                scope.launch { snackbar.showSnackbar(context.getString(R.string.draw_deleted)) }
            },
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.headlineLarge)
                    MyPhoto(name.ifBlank { stringResource(R.string.partner_fallback) }) { jpeg ->
                        val clean = name.trim()
                        if (clean.isNotEmpty()) Store.setMyName(context, clean)
                        Store.setMyAvatar(context, jpeg)
                        sendProfile()
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { value ->
                            if (value.length <= 80) name = value.replace("\n", " ")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.name_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
                    PrimaryPill(
                        text = stringResource(R.string.save_name),
                        onClick = {
                            val clean = name.trim()
                            if (clean.isEmpty()) return@PrimaryPill
                            Store.setMyName(context, clean)
                            sendProfile()
                        },
                        enabled = name.trim().isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.draw_saved_title), style = MaterialTheme.typography.titleMedium)
                    if (drawings.isEmpty()) {
                        Text(
                            stringResource(R.string.draw_none_saved),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(drawings, key = { it.ts }) { drawing ->
                SavedThumb(drawing) { viewingTs = drawing.ts }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.notif_section), style = MaterialTheme.typography.titleMedium)
                    ToggleRow(
                        title = stringResource(R.string.notif_messages),
                        help = stringResource(R.string.notif_messages_help),
                        checked = notifyMessages,
                        enabled = true,
                        onCheckedChange = { Store.setNotifyMessages(context, it) },
                    )
                    ToggleRow(
                        title = stringResource(R.string.notif_drawings),
                        help = stringResource(R.string.notif_drawings_help),
                        checked = notifyDrawings,
                        enabled = true,
                        onCheckedChange = { Store.setNotifyDrawings(context, it) },
                    )
                    SecondaryPill(
                        text = stringResource(R.string.notif_system),
                        onClick = {
                            if (!openNotificationSettings(context)) {
                                scope.launch {
                                    snackbar.showSnackbar(context.getString(R.string.notif_system_failed))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecondaryPill(
                        text = stringResource(R.string.check_updates),
                        onClick = {
                            if (checking) return@SecondaryPill
                            checking = true
                            scope.launch {
                                val result = try {
                                    // DATA-FLOW: update metadata, from the relay, not encrypted, no user data.
                                    Updater.check(context, force = true)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    UpdateCheck.Error
                                }
                                when (result) {
                                    is UpdateCheck.Available -> {
                                        update = result.info
                                        status = null
                                        statusError = false
                                    }
                                    UpdateCheck.UpToDate -> {
                                        update = null
                                        status = context.getString(R.string.update_latest)
                                        statusError = false
                                    }
                                    UpdateCheck.Error -> {
                                        update = null
                                        status = context.getString(R.string.update_unreachable)
                                        statusError = true
                                    }
                                }
                                checking = false
                            }
                        },
                        enabled = !checking,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (checking) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    status?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (statusError) MaterialTheme.colorScheme.error else LdColors.colors.ink,
                        )
                    }
                    update?.let { UpdateBanner(it) }
                    Text(stringResource(R.string.pair_safety_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        safety.ifBlank { stringResource(R.string.safety_missing) },
                        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                    Text(
                        stringResource(R.string.safety_help),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { confirm = true }) {
                        Text(stringResource(R.string.unpair), color = MaterialTheme.colorScheme.error)
                    }
                    Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.version_line, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.privacy_see),
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.clickable(onClick = onPrivacy),
                    )
                }
            }
        }
    }
}

@Composable
private fun MyPhoto(name: String, onPicked: (ByteArray) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by Store.changes.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        failed = false
        scope.launch {
            val jpeg = AvatarJpeg.fromUri(context, uri)
            busy = false
            if (jpeg == null) failed = true else onPicked(jpeg)
        }
    }
    fun launch() {
        try {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: Exception) {
            failed = true
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RoundAvatar(
            revision = revision,
            load = { Store.myAvatar(context) },
            name = name,
            size = 96.dp,
            modifier = Modifier.clickable(enabled = !busy, onClick = ::launch),
        )
        TextButton(enabled = !busy, onClick = ::launch) {
            Text(stringResource(R.string.photo_choose))
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        if (failed) {
            Text(
                stringResource(R.string.photo_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SavedThumb(drawing: SavedDrawing, onClick: () -> Unit) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(drawing.ts) {
        bitmap = withContext(Dispatchers.IO) { DrawStore.decode(drawing.file, 360) }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        val shown = bitmap
        if (shown != null && !shown.isRecycled) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = stringResource(R.string.draw_open),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 0.dp,
                tonalElevation = 0.dp,
            ) {}
        }
    }
}

@Composable
private fun DrawingViewer(
    ts: Long,
    onClose: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var missing by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val title = remember(ts) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts))
    }
    LaunchedEffect(ts) {
        val decoded = withContext(Dispatchers.IO) {
            val file = DrawStore.list(context).firstOrNull { it.ts == ts }?.file
            if (file == null) null else DrawStore.decode(file, 1600)
        }
        if (decoded == null) missing = true else bitmap = decoded
    }
    LaunchedEffect(missing) {
        if (missing) onClose()
    }
    if (missing) return
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = LdColors.colors.menu,
            contentColor = LdColors.colors.ink,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ScreenHeader(title, onClose)
                val shown = bitmap
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (shown != null && !shown.isRecycled) {
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = stringResource(R.string.draw_open),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                }
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val removed = withContext(Dispatchers.IO) {
                                    val ok = DrawStore.delete(context, ts)
                                    if (ok) DrawingWidget.updateAll(context)
                                    ok
                                }
                                if (removed) onDeleted()
                            }
                        },
                        enabled = !sending,
                    ) {
                        Text(stringResource(R.string.draw_delete), color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        onClick = {
                            if (sending) return@Button
                            sending = true
                            note = null
                            scope.launch {
                                val ok = try {
                                    withContext(Dispatchers.IO) {
                                        val file = DrawStore.list(context).firstOrNull { it.ts == ts }?.file
                                        if (file == null || !file.isFile || file.length() <= 0L || file.length() > 24L * 1024 * 1024) {
                                            false
                                        } else {
                                            // DATA-FLOW: saved PNG, encrypted inside DrawSend, ciphertext to the relay.
                                            DrawSend.send(context, file.readBytes())
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    sending = false
                                    throw e
                                } catch (_: Exception) {
                                    false
                                }
                                sending = false
                                note = context.getString(if (ok) R.string.draw_sent else R.string.draw_send_failed)
                            }
                        },
                        enabled = !sending,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.draw_send_again))
                    }
                }
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp).align(Alignment.CenterHorizontally),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

/** Opens this app's system notification settings. False when neither screen can open. */
private fun openNotificationSettings(context: Context): Boolean {
    val pkg = context.packageName
    val primary = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(primary)
        return true
    } catch (_: Exception) {
        // This Android build has no notification-settings screen. The app page is next.
    }
    return try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: Exception) {
        false
    }
}
