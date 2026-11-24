package app.loviedowpie.ui

import androidx.compose.runtime.collectAsState
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.loviedowpie.LocationSharing
import app.loviedowpie.Msg
import app.loviedowpie.Outbox
import app.loviedowpie.R
import app.loviedowpie.Store
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Title row used by settings and the privacy page. */
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        }
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

/** A small status pill: "Live" or "Sharing paused". */
@Composable
fun StatusChip(text: String, emphasized: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = if (emphasized) colors.primaryContainer else colors.secondaryContainer,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (emphasized) colors.onPrimaryContainer else colors.onSecondaryContainer,
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
            modifier = modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        BoxInitial(initialOf(name), size, modifier)
    }
}

@Composable
private fun BoxInitial(letter: String, size: Dp, modifier: Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
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
private fun ToggleRow(
    title: String,
    help: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
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
    Button(
        onClick = {
            if (sending) return@Button
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
    ) {
        Icon(Icons.Filled.Notifications, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.ring_partner))
    }
}
