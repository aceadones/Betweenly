package app.loviedowpie.ui

import android.content.Context
import android.location.LocationManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.loviedowpie.BuildConfig
import app.loviedowpie.LocationSharing
import app.loviedowpie.Msg
import app.loviedowpie.Outbox
import app.loviedowpie.R
import app.loviedowpie.Store
import app.loviedowpie.UpdateCheck
import app.loviedowpie.UpdateInfo
import app.loviedowpie.Updater
import app.loviedowpie.ui.icons.Lu
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.launch

/**
 * Location tab. Partner hero, a bento of status, and the two sharing switches.
 * The shell already applies the status-bar and dock insets.
 *
 * DATA-FLOW: distance uses a last-known fix already on this phone. Nothing is sent.
 * Ring and the sharing switches send encrypted messages. See [Outbox] and [LocationSharing].
 */
@Composable
fun HomeScreen(onTrack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val revision by Store.changes.collectAsState()
    val now = rememberNow()
    var versionCode by rememberSaveable { mutableStateOf(0) }
    var versionName by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var ringing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val result = try {
            // DATA-FLOW: update metadata, from the relay, not encrypted, no user data.
            Updater.check(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            UpdateCheck.Error
        }
        if (result is UpdateCheck.Available) {
            versionCode = result.info.versionCode
            versionName = result.info.versionName
            notes = result.info.notes
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
    val seenValue = if (loc == null || loc.lastSeen <= 0L) {
        stringResource(R.string.no_update_yet)
    } else {
        lastSeenPhrase(context, loc.lastSeen, now)
    }
    val accuracyValue = loc?.acc?.takeIf { it > 0f }?.let { accuracyPhrase(context, it) }
        ?: stringResource(R.string.stat_none)
    val batteryText = if (loc == null || loc.bat !in 0..100) {
        stringResource(R.string.battery_unknown_short)
    } else {
        batteryPhrase(context, loc.bat, loc.chg)
    }
    val status = when {
        sharing && loc?.live == true -> stringResource(R.string.home_sharing_live)
        sharing -> stringResource(R.string.sharing_on)
        heard -> stringResource(R.string.sharing_paused)
        else -> stringResource(R.string.no_location_yet)
    }
    val seenLine = if (loc != null && loc.lastSeen > 0L) {
        stringResource(R.string.last_seen, seenValue)
    } else {
        stringResource(R.string.no_update_yet)
    }
    val distance = remember(revision, loc?.lat, loc?.lon) {
        val lat = loc?.lat
        val lon = loc?.lon
        if (lat == null || lon == null) null else quietDistance(context, lat, lon)
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
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
            update?.let { UpdateBanner(it, Modifier.appear(0)) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 36.dp)
                    .appear(0),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 42.dp)
                        .ldGlass()
                        .padding(start = 20.dp, end = 20.dp, top = 52.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name,
                            style = LdType.PageTitle,
                            color = LdColors.colors.ink,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (sharing) VerifiedDot()
                    }
                    Text(
                        stringResource(R.string.home_status_line, status, seenLine),
                        style = MaterialTheme.typography.bodyLarge,
                        color = LdColors.colors.inkMuted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RadarStrip(
                        hasPoint = loc?.lat != null && loc?.lon != null,
                        modifier = Modifier.fillMaxWidth().height(84.dp),
                    )
                }
                RoundAvatar(
                    revision,
                    { Store.partnerAvatar(context) },
                    name,
                    84.dp,
                    Modifier
                        .align(Alignment.TopCenter)
                        .shadow(10.dp, CircleShape),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BentoStat(
                    label = stringResource(R.string.stat_battery),
                    value = batteryText,
                    modifier = Modifier.weight(1f).height(168.dp).appear(1),
                    visual = { BatteryRing(if (loc == null) -1 else loc.bat) },
                )
                BentoStat(
                    label = stringResource(R.string.stat_seen),
                    value = seenValue,
                    modifier = Modifier.weight(1f).height(168.dp).appear(2),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BentoStat(
                    label = stringResource(R.string.stat_accuracy),
                    value = accuracyValue,
                    modifier = Modifier.weight(1f).height(120.dp).appear(3),
                )
                BentoStat(
                    label = stringResource(R.string.home_distance),
                    value = distance ?: stringResource(R.string.stat_none),
                    modifier = Modifier.weight(1f).height(120.dp).appear(4),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BentoAction(
                    title = stringResource(R.string.track_map),
                    icon = Lu.Navigation,
                    onClick = onTrack,
                    modifier = Modifier.weight(1f).height(132.dp).appear(5),
                )
                BentoAction(
                    title = stringResource(R.string.ring_partner),
                    icon = Lu.BellRing,
                    enabled = !ringing,
                    onClick = {
                        if (ringing) return@BentoAction
                        ringing = true
                        scope.launch {
                            val ok = try {
                                // DATA-FLOW: a ring command, encrypted, to my partner, plus an empty wake ping. No location in this message.
                                Outbox.send(context, Msg.ring(), wake = true)
                            } catch (e: CancellationException) {
                                ringing = false
                                throw e
                            } catch (_: Exception) {
                                false
                            }
                            ringing = false
                            snackbar.showSnackbar(
                                context.getString(if (ok) R.string.ring_sent else R.string.ring_failed),
                            )
                        }
                    },
                    modifier = Modifier.weight(1f).height(132.dp).appear(6),
                )
            }
            GlassGroup(Modifier.appear(7)) {
                ShareToggles(snackbar)
            }
        }
    }
}

/** On-device only. A missing permission or a missing fix hides the number. */
private fun quietDistance(context: Context, lat: Double, lon: Double): String? {
    if (!LocationSharing.hasLocationPermission(context)) return null
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val fix = try {
        listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { provider ->
            try {
                manager.getLastKnownLocation(provider)
            } catch (_: Exception) {
                null
            }
        }.maxByOrNull { it.time }
    } catch (_: Exception) {
        null
    } ?: return null
    val meters = haversine(fix.latitude, fix.longitude, lat, lon)
    if (meters.isNaN() || meters.isInfinite()) return null
    return if (meters < 1000.0) {
        context.getString(R.string.home_m, meters.toInt().coerceAtLeast(0))
    } else {
        context.getString(R.string.home_km, meters / 1000.0)
    }
}

private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1.0 - a))
}
