package app.loviedowpie.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import app.loviedowpie.BuildConfig
import app.loviedowpie.R
import app.loviedowpie.Store
import app.loviedowpie.UpdateInfo
import app.loviedowpie.Updater
import kotlin.coroutines.cancellation.CancellationException

/**
 * The paired home screen: partner card, the two sharing switches, the map
 * button, ring, and settings. An update banner shows when [Updater.check] finds one.
 */
@Composable
fun HomeScreen(onTrack: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val revision by Store.changes.collectAsState()
    val now = rememberNow()
    // Kept across rotation. check() is null for a day after a real answer, so the
    // banner would otherwise vanish. A newer installed build hides it.
    var versionCode by rememberSaveable { mutableStateOf(0) }
    var versionName by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val info = try {
            // DATA-FLOW: update metadata, from the relay, not encrypted, no user data.
            Updater.check(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (info != null) {
            versionCode = info.versionCode
            versionName = info.versionName
            notes = info.notes
        }
    }
    val update = if (versionCode > BuildConfig.VERSION_CODE) {
        UpdateInfo(versionCode, versionName, "", notes)
    } else {
        null
    }
    val loc = remember(revision) { Store.partnerLoc(context) }
    val name = remember(revision) {
        Store.partnerName(context).ifBlank { context.getString(R.string.partner_fallback) }
    }
    val sharing = remember(revision) { Store.partnerSharing(context) }
    val profileKnown = remember(revision) { Store.partnerProfileKnown(context) }
    val heard = loc != null || profileKnown

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                }
            }
            update?.let { UpdateBanner(it) }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 0.dp,
                tonalElevation = 0.dp,
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundAvatar(revision, { Store.partnerAvatar(context) }, name, 72.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(name, style = MaterialTheme.typography.titleLarge)
                        if (loc == null || loc.lastSeen <= 0L) {
                            Text(stringResource(R.string.no_update_yet), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(
                                stringResource(R.string.last_seen, lastSeenPhrase(context, loc.lastSeen, now)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            batteryPhrase(context, loc?.bat ?: -1, loc?.chg == true),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (loc?.live == true) {
                                StatusChip(stringResource(R.string.live), emphasized = true)
                            } else if (heard && !sharing) {
                                StatusChip(stringResource(R.string.sharing_paused), emphasized = false)
                            }
                        }
                        val acc = loc?.acc
                        if (acc != null && acc > 0f) {
                            Text(
                                accuracyPhrase(context, acc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Text(stringResource(R.string.you), style = MaterialTheme.typography.titleMedium)
            ShareToggles(snackbar)
            Button(onClick = onTrack, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.LocationOn, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.track_map))
            }
            RingButton(snackbar, Modifier.fillMaxWidth())
        }
    }
}
