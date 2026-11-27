package com.nextlayer.betweenly.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nextlayer.betweenly.BuildConfig
import com.nextlayer.betweenly.ChatStore
import com.nextlayer.betweenly.DrawSend
import com.nextlayer.betweenly.DrawStore
import com.nextlayer.betweenly.Outbox
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.SavedDrawing
import com.nextlayer.betweenly.Store
import com.nextlayer.betweenly.UpdateCheck
import com.nextlayer.betweenly.UpdatePrompt
import com.nextlayer.betweenly.Updater
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
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
fun ProfileScreen(
    onPrivacy: () -> Unit,
    onUnpaired: () -> Unit,
    onSavedDrawings: () -> Unit,
) {
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
    val offer by UpdatePrompt.info.collectAsState()
    val safety = remember(revision) { Store.safetyNumber(context) }
    val drawings = remember(drawRev) { DrawStore.list(context) }
    val animationsOn by Store.animationsFlow(context).collectAsState()

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
            modifier = Modifier.ldPopupFrame(),
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = LdColors.glassFill,
            tonalElevation = 0.dp,
            titleContentColor = LdColors.onGlass,
            textContentColor = LdColors.onGlassMuted,
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
                        BetweenlyWidget.updateAll(app)
                        onUnpaired()
                    },
                ) { Text(stringResource(R.string.unpair_confirm), color = Color(0xFFFFB4AB)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text(stringResource(R.string.pair_cancel), color = LdColors.onGlass)
                }
            },
        )
    }

    val gridState = rememberLazyGridState()
    val collapse by remember {
        derivedStateOf {
            if (gridState.firstVisibleItemIndex > 0) 1f
            else (gridState.firstVisibleItemScrollOffset / 160f).coerceIn(0f, 1f)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.appear(0), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.profile_title), style = LdType.Lead, color = LdColors.colors.ink)
                    MyPhoto(name.ifBlank { stringResource(R.string.partner_fallback) }) { jpeg ->
                        val clean = name.trim()
                        if (clean.isNotEmpty()) Store.setMyName(context, clean)
                        Store.setMyAvatar(context, jpeg)
                        sendProfile()
                    }
                    Text(
                        stringResource(R.string.photo_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = LdColors.colors.inkMuted,
                    )
                    UnderlineNameField(
                        value = name,
                        onValue = { value ->
                            if (value.length <= 80) name = value.replace("\n", " ")
                        },
                        label = stringResource(R.string.name_label),
                        onDone = {
                            val clean = name.trim()
                            if (clean.isEmpty()) return@UnderlineNameField
                            Store.setMyName(context, clean)
                            sendProfile()
                        },
                    )
                    Text(
                        stringResource(R.string.name_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = LdColors.colors.inkMuted,
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
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                GlassGroup(
                    Modifier
                        .appear(1)
                        .clickable(role = Role.Button, onClick = onSavedDrawings),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        LuIcon(Lu.Image, contentDescription = null, tint = surfaceInk(), size = 20.dp)
                        Text(
                            stringResource(R.string.draw_saved_title),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = surfaceInk(),
                            maxLines = 1,
                        )
                        Text(
                            stringResource(R.string.draw_saved_count, drawings.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = surfaceInk(muted = true),
                        )
                        LuIcon(Lu.ChevronRight, contentDescription = null, tint = surfaceInk(), size = 18.dp)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                GlassGroup(Modifier.appear(1)) {
                    SectionLabel(Lu.Sparkles, stringResource(R.string.appearance_section))
                    ToggleRow(
                        title = stringResource(R.string.reduce_animations),
                        help = stringResource(R.string.reduce_animations_help),
                        checked = !animationsOn,
                        enabled = true,
                        icon = Lu.Sparkles,
                        onCheckedChange = { reduce -> Store.setAnimationsEnabled(context, !reduce) },
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                NotificationSettings(snackbar, Modifier.appear(2))
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                GlassGroup(Modifier.appear(3)) {
                    SectionLabel(Lu.ShieldCheck, stringResource(R.string.pair_safety_title))
                    Text(
                        safety.ifBlank { stringResource(R.string.safety_missing) },
                        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                        color = surfaceInk(),
                    )
                    Text(
                        stringResource(R.string.safety_help),
                        style = MaterialTheme.typography.bodyMedium,
                        color = surfaceInk(muted = true),
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                GlassGroup(Modifier.appear(4)) {
                    SectionLabel(Lu.RefreshCw, stringResource(R.string.check_updates))
                    SecondaryPill(
                        text = stringResource(R.string.check_updates),
                        onClick = {
                            if (checking) return@SecondaryPill
                            checking = true
                            scope.launch {
                                val result = try {
                                    // DATA-FLOW: update metadata from the relay, and from GitHub only when that repo is public. Not encrypted. No pair data.
                                    Updater.check(context, force = true)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    UpdateCheck.Error
                                }
                                when (result) {
                                    is UpdateCheck.Available -> {
                                        UpdatePrompt.publish(result.info, dialog = true)
                                        status = null
                                        statusError = false
                                    }
                                    UpdateCheck.UpToDate -> {
                                        UpdatePrompt.clear()
                                        status = context.getString(R.string.update_latest)
                                        statusError = false
                                    }
                                    UpdateCheck.Error -> {
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
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = LdColors.onGlass,
                        )
                    }
                    status?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (statusError) Color(0xFFFFB4AB) else surfaceInk(),
                        )
                    }
                    offer?.let { UpdateBanner(it) }
                    BrandMark(Modifier.padding(top = 8.dp), size = 56.dp)
                    Text(
                        stringResource(R.string.about_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = surfaceInk(),
                    )
                    Text(
                        stringResource(R.string.about_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = surfaceInk(muted = true),
                    )
                    Text(
                        stringResource(R.string.version_line, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = surfaceInk(muted = true),
                    )
                    Text(
                        stringResource(R.string.privacy_see),
                        color = LdColors.onGlass,
                        textDecoration = TextDecoration.Underline,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button, onClick = onPrivacy)
                            .padding(vertical = 12.dp),
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                DestructiveBlock(
                    title = stringResource(R.string.unpair),
                    icon = Lu.Trash2,
                    onClick = { confirm = true },
                    modifier = Modifier.appear(5),
                )
            }
        }
        val barClicks = remember { MutableInteractionSource() }
        if (collapse > 0.02f) {
            Text(
                stringResource(R.string.profile_title),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = collapse
                        val scale = 0.94f + 0.06f * collapse
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .background(LdColors.colors.skyMid.copy(alpha = 0.94f))
                    .clickable(
                        interactionSource = barClicks,
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                style = LdType.PageTitle,
                color = LdColors.colors.ink,
            )
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
        Box(
            modifier = Modifier
                .size(132.dp)
                .clickable(
                    enabled = !busy,
                    onClickLabel = stringResource(R.string.profile_edit_photo),
                    role = Role.Button,
                    onClick = ::launch,
                ),
            contentAlignment = Alignment.Center,
        ) {
            RoundAvatar(
                revision = revision,
                load = { Store.myAvatar(context) },
                name = name,
                size = 120.dp,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(40.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(LdColors.colors.pill),
                contentAlignment = Alignment.Center,
            ) {
                LuIcon(
                    Lu.Camera,
                    contentDescription = null,
                    tint = LdColors.colors.onPill,
                    size = 20.dp,
                )
            }
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp).size(18.dp), strokeWidth = 2.dp)
        }
        if (failed) {
            Text(
                stringResource(R.string.photo_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

