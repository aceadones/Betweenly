package com.nextlayer.betweenly.ui

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
import androidx.compose.foundation.layout.heightIn
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
import com.nextlayer.betweenly.PartnerLoc
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.Store
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
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val partnerStatus = when {
                        sharing && loc?.live == true -> stringResource(R.string.live)
                        sharing -> stringResource(R.string.sharing_on)
                        loc != null -> stringResource(R.string.sharing_paused)
                        else -> stringResource(R.string.no_location_yet)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GlassIconButton(
                            onClick = onBack,
                            icon = Lu.ArrowLeft,
                            description = stringResource(R.string.back),
                            solid = true,
                        )
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .ldGlass(RoundedCornerShape(LdRadius.chip), solid = true)
                                .padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RoundAvatar(revision, { Store.partnerAvatar(context) }, name, 36.dp)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = LdColors.onGlass,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    partnerStatus,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = LdColors.onGlassMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (lat != null && lon != null) {
                            GlassIconButton(
                                onClick = { recenter++ },
                                icon = Lu.LocateFixed,
                                description = stringResource(R.string.recenter),
                                solid = true,
                            )
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
                                .fillMaxWidth()
                                .ldGlass(RoundedCornerShape(LdRadius.card), solid = true)
                                .padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LdColors.onGlass,
                        )
                    }
                    if (showCard && loc != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .ldGlass(RoundedCornerShape(LdRadius.card), solid = true)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(name, style = MaterialTheme.typography.titleMedium, color = LdColors.onGlass)
                            PartnerBattery(loc, now)
                            if (loc.lastSeen > 0L) {
                                Text(
                                    stringResource(R.string.last_seen, lastSeenPhrase(context, loc.lastSeen, now)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LdColors.onGlass,
                                )
                            } else {
                                Text(
                                    stringResource(R.string.no_update_yet),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LdColors.onGlass,
                                )
                            }
                        }
                    }
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
                                color = LdColors.onGlass,
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
                                    color = LdColors.onGlass,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (loc != null) {
                            PartnerBattery(loc, now)
                            Text(
                                if (loc.lastSeen > 0L) {
                                    stringResource(R.string.last_seen, lastSeenPhrase(context, loc.lastSeen, now))
                                } else {
                                    stringResource(R.string.no_update_yet)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = LdColors.onGlassMuted,
                            )
                        }
                    }
                }
                if (lat != null && lon != null) {
                    PartnerMapActions(
                        latitude = lat,
                        longitude = lon,
                        name = name,
                        modifier = Modifier.fillMaxWidth(),
                        hintColor = LdColors.onGlassMuted,
                    )
                }
                RingButton(snackbar, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PartnerBattery(loc: PartnerLoc, now: Long) {
    val context = LocalContext.current
    val seenAt = batterySeenAt(loc.bat, loc.batSeen, loc.lastSeen)
    val fresh = loc.bat in 0..100 && !batteryIsStale(seenAt, now)
    Text(
        batteryPhrase(context, loc.bat, loc.chg),
        style = MaterialTheme.typography.bodyMedium,
        color = if (fresh) LdColors.onGlass else LdColors.onGlassMuted,
    )
    if (seenAt > 0L) {
        Text(
            stringResource(R.string.battery_age, lastSeenPhrase(context, seenAt, now)),
            style = MaterialTheme.typography.bodyMedium,
            color = LdColors.onGlassMuted,
        )
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
