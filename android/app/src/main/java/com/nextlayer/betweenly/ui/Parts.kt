package com.nextlayer.betweenly.ui

import androidx.compose.runtime.collectAsState
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import com.nextlayer.betweenly.LocationSharing
import com.nextlayer.betweenly.Msg
import com.nextlayer.betweenly.Outbox
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.Store
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Dark glass in both app themes. No blur. Fill is near-black wine at 78% so white
 * at 70% stays above 4.5:1 on the light blush. A short white sheen sits at the top.
 * The rim fades from 35% white at the top-left to 4% at the bottom-right.
 */
@Composable
fun Modifier.darkGlass(
    shape: Shape = RoundedCornerShape(LdRadius.card),
): Modifier {
    val sheen = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.16f),
        0.24f to Color.White.copy(alpha = 0.04f),
        0.55f to Color.Transparent,
    )
    val rim = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.35f),
        1f to Color.White.copy(alpha = 0.04f),
    )
    return this
        .shadow(
            elevation = 8.dp,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.22f),
            spotColor = Color.Black.copy(alpha = 0.16f),
        )
        .clip(shape)
        .drawWithCache {
            val strokePx = 1.dp.toPx()
            val inset = strokePx / 2f
            val inner = Size(
                (size.width - strokePx).coerceAtLeast(1f),
                (size.height - strokePx).coerceAtLeast(1f),
            )
            val outline = shape.createOutline(inner, layoutDirection, this)
            onDrawBehind {
                drawRect(LdColors.glassFill)
                drawRect(sheen)
                translate(left = inset, top = inset) {
                    drawOutline(outline, rim, style = Stroke(width = strokePx))
                }
            }
        }
}

/** Title row used by settings and the privacy page. */
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            GlassIconButton(
                onClick = onBack,
                icon = Lu.ArrowLeft,
                description = stringResource(R.string.back),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, color = surfaceInk())
    }
}

/** A small status pill: "Live" or "Sharing paused". */
@Composable
fun StatusChip(text: String, emphasized: Boolean) {
    val shape = RoundedCornerShape(LdRadius.chip)
    val plate = if (emphasized) {
        Modifier.clip(shape).background(Color.White.copy(alpha = 0.92f))
    } else {
        Modifier.darkGlass(shape)
    }
    Box(plate) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (emphasized) LdColors.navy else LdColors.onGlass,
        )
    }
}

/**
 * Round photo, or the first letter of [name] when there is no photo.
 * [load] runs only when [revision] changes, so a map pan does not decode the JPEG again.
 */
@Composable
fun RoundAvatar(
    revision: Int,
    load: () -> ByteArray?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(revision) {
        bitmap = withContext(Dispatchers.IO) {
            val bytes = load()
            if (bytes == null || bytes.isEmpty()) {
                null
            } else {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
    }
    val shown = bitmap
    if (shown != null && !shown.isRecycled) {
        Image(
            bitmap = shown.asImageBitmap(),
            contentDescription = null,
            modifier = modifier
                .size(size)
                .border(2.dp, LdColors.colors.chipOn, CircleShape)
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        BoxInitial(initialOf(name), size, modifier)
    }
}

@Composable
private fun BoxInitial(letter: String, size: Dp, modifier: Modifier) {
    val dark = isSystemInDarkTheme()
    val wash = if (dark) {
        Brush.linearGradient(listOf(Color(0xFF4A182B), Color(0xFFD62448)))
    } else {
        Brush.linearGradient(listOf(Color(0xFFFFE3E7), Color(0xFFFF8A9A)))
    }
    val ink = if (dark) LdColors.white else LdColors.navy
    val style = when {
        size >= 100.dp -> LdType.PageTitle
        size >= 64.dp -> MaterialTheme.typography.headlineMedium
        else -> MaterialTheme.typography.titleLarge
    }
    Box(
        modifier = modifier
            .size(size)
            .border(2.dp, LdColors.colors.chipOn, CircleShape)
            .clip(CircleShape)
            .background(wash),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = style, color = ink)
    }
}

/**
 * Lets the person pick a gallery photo and stores the small JPEG via [onPicked].
 * DATA-FLOW: the picker reads a local image. Uploading happens in [onPicked].
 */
@Composable
fun PhotoPicker(name: String, onPicked: (ByteArray) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by Store.changes.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        failed = false
        scope.launch {
            val jpeg = AvatarJpeg.fromUri(context, uri)
            busy = false
            if (jpeg == null) failed = true else onPicked(jpeg)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundAvatar(revision, { Store.myAvatar(context) }, name, 96.dp)
        Spacer(Modifier.height(8.dp))
        TextButton(
            enabled = !busy,
            onClick = {
                try {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                } catch (_: Exception) {
                    failed = true
                }
            },
        ) {
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

/**
 * The two sharing switches. Live is enabled only while sharing is on.
 * [LocationSharing.setShare] and [LocationSharing.setLive] do the sending.
 */
@Composable
fun ShareToggles(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by Store.changes.collectAsState()
    val share = remember(revision) { Store.shareOn(context) }
    val live = remember(revision) { Store.liveOn(context) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleRow(
            title = stringResource(R.string.share_switch),
            help = stringResource(R.string.share_help),
            checked = share,
            enabled = true,
            icon = Lu.MapPin,
            onCheckedChange = { on ->
                if (on && !LocationSharing.hasLocationPermission(context)) {
                    scope.launch {
                        snackbar.showSnackbar(context.getString(R.string.location_permission_hint))
                    }
                }
                // DATA-FLOW: sharing on or off, inside an encrypted "share" message, to my partner. See LocationSharing.setShare.
                LocationSharing.setShare(context, on)
            },
        )
        ToggleRow(
            title = stringResource(R.string.live_switch),
            help = stringResource(R.string.live_help),
            checked = live && share,
            enabled = share,
            icon = Lu.Radio,
            onCheckedChange = { on ->
                if (on && !LocationSharing.hasLocationPermission(context)) {
                    scope.launch {
                        snackbar.showSnackbar(context.getString(R.string.location_permission_hint))
                    }
                }
                // DATA-FLOW: live on or off. Turning it on also shares location. Encrypted, to my partner. See LocationSharing.setLive.
                LocationSharing.setLive(context, on)
            },
        )
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    help: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
) {
    val onChange = rememberUpdatedState(onCheckedChange)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { onChange.value(it) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            LuIcon(icon, contentDescription = null, tint = surfaceInk(), size = 22.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = surfaceInk(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        onClick(label = title) {
                            if (enabled) onChange.value(!checked)
                            true
                        }
                    },
            )
            Text(
                help,
                style = MaterialTheme.typography.bodySmall,
                color = surfaceInk(muted = true),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // The row owns the toggle. The thumb does not take the click.
        LdSwitch(checked = checked, enabled = enabled)
    }
}

/** Track and thumb. The thumb slides with [Motion] and snaps when motion is off. */
@Composable
private fun LdSwitch(checked: Boolean, enabled: Boolean) {
    val travel by animateDpAsState(
        targetValue = if (checked) 20.dp else 0.dp,
        animationSpec = Motion.spring<Dp>(),
        label = "switch",
    )
    val onGlass = LocalOnGlass.current
    val track = when {
        !enabled -> if (onGlass) Color.White.copy(alpha = 0.16f) else LdColors.colors.inkMuted.copy(alpha = 0.28f)
        checked -> LdColors.primary
        else -> if (onGlass) Color.White.copy(alpha = 0.28f) else LdColors.colors.glassBorder
    }
    Box(
        Modifier
            .clearAndSetSemantics { }
            .size(width = 52.dp, height = 32.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(LdRadius.chip))
            .background(track)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .graphicsLayer { translationX = travel.toPx() }
                .size(24.dp)
                .clip(CircleShape)
                .background(LdColors.white),
        )
    }
}

/**
 * "Ring their phone". The snackbar says whether the encrypted message left the phone.
 */
@Composable
fun RingButton(snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    PrimaryPill(
        text = stringResource(R.string.ring_partner),
        onClick = {
            if (sending) return@PrimaryPill
            sending = true
            scope.launch {
                val ok = try {
                    // DATA-FLOW: a ring command, encrypted, to my partner, plus an empty wake ping. No location in this message.
                    Outbox.send(context, Msg.ring(), wake = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                sending = false
                snackbar.showSnackbar(
                    context.getString(if (ok) R.string.ring_sent else R.string.ring_failed),
                )
            }
        },
        enabled = !sending,
        modifier = modifier,
    )
}
