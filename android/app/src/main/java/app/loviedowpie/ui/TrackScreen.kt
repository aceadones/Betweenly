package app.loviedowpie.ui

import androidx.compose.runtime.collectAsState
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.foundation.layout.safeDrawing
import app.loviedowpie.R
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

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing,
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
                GlassIconButton(
                    onClick = onBack,
                    icon = Icons.Filled.ArrowBack,
                    description = stringResource(R.string.back),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
                if (loc?.live == true) {
                    Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
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
                            .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                            .fillMaxWidth()
                            .ldGlass(RoundedCornerShape(24.dp))
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LdColors.colors.ink,
                    )
                }
                if (showCard && loc != null) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(
                                top = if (sharingOff) 140.dp else 64.dp,
                                start = 16.dp,
                                end = 16.dp,
                            )
                            .fillMaxWidth()
                            .ldGlass(RoundedCornerShape(24.dp))
                            .padding(12.dp),
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
                            if (sharing) {
                                Spacer(Modifier.width(6.dp))
                                VerifiedDot()
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
