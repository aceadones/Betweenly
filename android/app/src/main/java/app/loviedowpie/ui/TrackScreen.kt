package app.loviedowpie.ui

import androidx.compose.runtime.collectAsState
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import app.loviedowpie.R
import app.loviedowpie.ui.icons.Lu
import app.loviedowpie.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen map of the partner's last known place, with ring underneath.
 * If they turned sharing off, the map stays on the last point and says so.
 */
@Composable
fun TrackScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val revision by Store.changes.collectAsState()
    val now = rememberNow()
    var recenter by remember { mutableIntStateOf(0) }
    var showCard by remember { mutableStateOf(false) }
    var marker by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(revision) {
        marker = withContext(Dispatchers.IO) {
            val bytes = Store.partnerAvatar(context)
            if (bytes == null || bytes.isEmpty()) {
                null
            } else {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
    }
    val loc = remember(revision) { Store.partnerLoc(context) }
    val sharing = remember(revision) { Store.partnerSharing(context) }
    val name = remember(revision) {
        Store.partnerName(context).ifBlank { context.getString(R.string.partner_fallback) }
    }
    val lat = loc?.lat
    val lon = loc?.lon
    val sharingOff = lat != null && lon != null && !sharing
    MapStatusIcons()

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.navigationBars,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (lat != null && lon != null) {
                    TileMap(
                        latitude = lat,
                        longitude = lon,
                        marker = marker,
                        markerLetter = initialOf(name),
                        recenterToken = recenter,
                        onMarkerClick = { showCard = !showCard },
                        onMapClick = { showCard = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (sharing) {
                                stringResource(R.string.no_location_yet)
                            } else {
                                stringResource(R.string.location_off)
                            },
                            modifier = Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                MapTopScrim()
                GlassIconButton(
                    onClick = onBack,
                    icon = Lu.ArrowLeft,
                    description = stringResource(R.string.back),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(8.dp),
                )
                if (loc?.live == true) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(12.dp),
                    ) {
                        StatusChip(stringResource(R.string.live), emphasized = true)
                    }
                }
                if (sharingOff) {
                    val seenAt = loc?.lastSeen ?: 0L
                    val whenSeen = if (seenAt > 0L) {
                        lastSeenPhrase(context, seenAt, now)
                    } else {
                        stringResource(R.string.no_update_yet)
                    }
                    Text(
                        stringResource(R.string.location_off_last, whenSeen),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                            .fillMaxWidth()
                            .ldGlass(RoundedCornerShape(LdRadius.card))
                            .padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LdColors.colors.ink,
                    )
                }
                if (showCard && loc != null) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(
                                top = if (sharingOff) 140.dp else 64.dp,
                                start = 16.dp,
                                end = 16.dp,
                            )
                            .fillMaxWidth()
                            .ldGlass(RoundedCornerShape(LdRadius.card))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(name, style = MaterialTheme.typography.titleMedium, color = LdColors.colors.ink)
                        Text(
                            batteryPhrase(context, loc.bat, loc.chg),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (loc.lastSeen > 0L) {
                            Text(
                                stringResource(R.string.last_seen, lastSeenPhrase(context, loc.lastSeen, now)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text(stringResource(R.string.no_update_yet), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (lat != null && lon != null) {
                    SecondaryPill(
                        text = stringResource(R.string.recenter),
                        onClick = { recenter++ },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 12.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .ldGlass(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundAvatar(revision, { Store.partnerAvatar(context) }, name, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                name,
                                style = MaterialTheme.typography.titleLarge,
                                color = LdColors.colors.ink,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val partnerStatus = when {
                                sharing && loc?.live == true -> stringResource(R.string.live)
                                sharing -> stringResource(R.string.sharing_on)
                                loc != null -> stringResource(R.string.sharing_paused)
                                else -> null
                            }
                            if (sharing) {
                                Spacer(Modifier.width(6.dp))
                                VerifiedDot()
                            }
                            if (partnerStatus != null) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    partnerStatus,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = LdColors.colors.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (loc != null) {
                            Text(
                                batteryPhrase(context, loc.bat, loc.chg),
                                style = MaterialTheme.typography.bodyMedium,
                                color = LdColors.colors.inkMuted,
                            )
                            Text(
                                if (loc.lastSeen > 0L) {
                                    stringResource(R.string.last_seen, lastSeenPhrase(context, loc.lastSeen, now))
                                } else {
                                    stringResource(R.string.no_update_yet)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = LdColors.colors.inkMuted,
                            )
                        }
                    }
                }
                RingButton(snackbar, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Dark icons on the light scrim, light icons on the navy scrim. */
@Composable
private fun MapStatusIcons() {
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    DisposableEffect(view, dark) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = !dark
        onDispose {
            if (previous != null) controller?.isAppearanceLightStatusBars = previous
        }
    }
}

/** Soft fade under the status bar so clock and battery stay readable on map tiles. */
@Composable
private fun BoxScope.MapTopScrim() {
    val top = if (isSystemInDarkTheme()) LdColors.navy else LdColors.white
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(96.dp)
            .background(
                Brush.verticalGradient(
                    listOf(top.copy(alpha = 0.94f), top.copy(alpha = 0f)),
                ),
            ),
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
