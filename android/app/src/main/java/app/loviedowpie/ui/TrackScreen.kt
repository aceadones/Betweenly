package app.loviedowpie.ui

import androidx.compose.runtime.collectAsState
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
        containerColor = MaterialTheme.colorScheme.background,
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
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shadowElevation = 0.dp,
                    tonalElevation = 0.dp,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
                if (loc?.live == true) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shadowElevation = 0.dp,
                        tonalElevation = 0.dp,
                    ) {
                        Text(
                            stringResource(R.string.live),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
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
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        shadowElevation = 0.dp,
                        tonalElevation = 0.dp,
                    ) {
                        Text(
                            stringResource(R.string.location_off_last, whenSeen),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (showCard && loc != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(
                                top = if (sharingOff) 140.dp else 64.dp,
                                start = 16.dp,
                                end = 16.dp,
                            )
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 0.dp,
                        tonalElevation = 0.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
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
                }
                if (lat != null && lon != null) {
                    Button(
                        onClick = { recenter++ },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 36.dp),
                    ) {
                        Text(stringResource(R.string.recenter))
                    }
                }
            }
            RingButton(snackbar, Modifier.fillMaxWidth().padding(16.dp))
        }
    }
}
