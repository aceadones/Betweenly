package com.nextlayer.betweenly.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import com.nextlayer.betweenly.LocationSharing
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.RingController
import com.nextlayer.betweenly.RingState
import com.nextlayer.betweenly.Store
import com.nextlayer.betweenly.UpdatePrompt
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Location tab. A map preview, three status cards, three actions, then sharing.
 * The shell already applies the status-bar and dock insets.
 *
 * DATA-FLOW: distance reads this phone's last fused fix and sends nothing.
 * Ping, ring, and the sharing switches send encrypted messages.
 */
@Composable
fun HomeScreen(onTrack: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val revision by Store.changes.collectAsState()
    val ring by RingController.state.collectAsState()
    val now = rememberNow()
    val offer by UpdatePrompt.info.collectAsState()
    var pingToken by remember { mutableIntStateOf(0) }
    var pingOn by remember { mutableStateOf(false) }
    var marker by remember { mutableStateOf<Bitmap?>(null) }
    var distanceText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        RingController.ensure(context)
    }
    LaunchedEffect(pingToken) {
        if (pingToken == 0) return@LaunchedEffect
        pingOn = true
        delay(1100)
        pingOn = false
    }
    LaunchedEffect(revision) {
        marker = withContext(Dispatchers.IO) {
            val bytes = Store.partnerAvatar(context)
            if (bytes == null || bytes.isEmpty()) null
            else try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) {
                null
            }
        }
    }
    val loc = remember(revision) { Store.partnerLoc(context) }
    val name = remember(revision) {
        Store.partnerName(context).ifBlank { context.getString(R.string.partner_fallback) }
    }
    val sharing = remember(revision) { Store.partnerSharing(context) }
    val seenValue = if (loc == null || loc.lastSeen <= 0L) {
        stringResource(R.string.no_update_yet)
    } else {
        lastSeenPhrase(context, loc.lastSeen, now)
    }
    val bat = loc?.bat
    val batteryKnown = bat != null && bat in 0..100
    val batterySeen = if (loc == null) 0L else batterySeenAt(loc.bat, loc.batSeen, loc.lastSeen)
    val batteryStale = batteryIsStale(batterySeen, now)
    val batteryText = if (bat != null && bat in 0..100) {
        stringResource(R.string.battery_pct, bat)
    } else {
        stringResource(R.string.home_dash)
    }
    val batteryAge = if (batterySeen > 0L) {
        stringResource(R.string.battery_age, lastSeenPhrase(context, batterySeen, now))
    } else {
        null
    }
    val status = when {
        sharing && loc?.live == true -> stringResource(R.string.home_sharing_live)
        sharing -> stringResource(R.string.sharing_on)
        loc != null -> stringResource(R.string.sharing_paused)
        else -> stringResource(R.string.no_location_yet)
    }
    val lat = loc?.lat
    val lon = loc?.lon
    LaunchedEffect(lat, lon) {
        distanceText = if (lat == null || lon == null) {
            null
        } else {
            withContext(Dispatchers.IO) { quietDistance(context, lat, lon) }
        }
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            offer?.let { UpdateBanner(it, Modifier.appear(0)) }
            MapPreview(
                name = name,
                status = status,
                seen = seenValue,
                sharing = sharing,
                live = loc?.live == true,
                latitude = lat,
                longitude = lon,
                marker = marker,
                onOpen = onTrack,
                modifier = Modifier.appear(0),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.appear(1).height(IntrinsicSize.Max),
            ) {
                MiniStat(
                    label = stringResource(R.string.stat_battery),
                    value = batteryText,
                    detail = batteryAge,
                    valueColor = if (batteryStale) LdColors.onGlassMuted else LdColors.onGlass,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    leading = {
                        BatteryRing(
                            percent = if (batteryKnown) loc?.bat ?: -1 else -1,
                            diameter = 22.dp,
                        )
                        if (loc?.chg == true) {
                            LuIcon(
                                Lu.Zap,
                                contentDescription = null,
                                tint = LdColors.primary,
                                size = 14.dp,
                            )
                        }
                    },
                )
                MiniStat(
                    label = stringResource(R.string.stat_seen),
                    value = seenValue,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                MiniStat(
                    label = stringResource(R.string.home_distance),
                    value = distanceText ?: stringResource(R.string.home_dash),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            val ringing = ring is RingState.Ringing
            val stopping = ring is RingState.Stopping
            val starting = ring is RingState.Starting
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.appear(2)) {
                ActionTile(
                    label = stringResource(R.string.home_ping),
                    icon = if (pingOn) Lu.Check else Lu.Bell,
                    tint = if (pingOn) LdColors.success else LdColors.onGlass,
                    onClick = {
                        if (!Store.isPaired(context)) return@ActionTile
                        RingController.sendPing(context)
                        pingToken++
                    },
                    modifier = Modifier.weight(1f),
                )
                ActionTile(
                    label = stringResource(if (ringing || stopping) R.string.home_stop_ring else R.string.home_ring),
                    icon = Lu.BellRing,
                    filled = ringing || stopping,
                    spinning = starting || stopping,
                    glow = ringing,
                    onClick = {
                        when (ring) {
                            is RingState.Idle -> RingController.startCall(context)
                            is RingState.Ringing -> RingController.stopCall(context)
                            else -> Unit
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                ActionTile(
                    label = stringResource(R.string.home_track),
                    icon = Lu.Navigation,
                    onClick = onTrack,
                    modifier = Modifier.weight(1f),
                )
            }
            GlassGroup(Modifier.appear(3)) {
                ShareToggles(snackbar)
            }
        }
    }
}

@Composable
private fun MapPreview(
    name: String,
    status: String,
    seen: String,
    sharing: Boolean,
    live: Boolean,
    latitude: Double?,
    longitude: Double?,
    marker: Bitmap?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(LdRadius.card)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(shape),
    ) {
        if (latitude != null && longitude != null) {
            TileMap(
                latitude = latitude,
                longitude = longitude,
                marker = marker,
                markerLetter = initialOf(name),
                recenterToken = 0,
                onMarkerClick = {},
                onMapClick = {},
                modifier = Modifier.fillMaxSize(),
                gestures = false,
                startZoom = 14.5f,
            )
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .ldGlass(RoundedCornerShape(LdRadius.tile), strong = true)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        color = LdColors.onGlass,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (sharing) {
                        Text(
                            stringResource(if (live) R.string.live else R.string.home_chip_sharing),
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clip(RoundedCornerShape(LdRadius.chip))
                                .background(LdColors.primary)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            color = LdColors.white,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    stringResource(R.string.home_status_line, status, seen),
                    style = MaterialTheme.typography.bodySmall,
                    color = LdColors.onGlassMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        role = Role.Button,
                        onClick = onOpen,
                    )
                    .semantics { contentDescription = name },
            )
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .ldGlass(shape)
                    .clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        role = Role.Button,
                        onClick = onOpen,
                    )
                    .semantics { contentDescription = name }
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LuIcon(Lu.MapPin, contentDescription = null, tint = LdColors.onGlass, size = 36.dp)
                Text(
                    stringResource(R.string.home_waiting, name),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = LdColors.onGlass,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MiniStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    valueColor: Color = LdColors.onGlass,
    leading: @Composable () -> Unit = {},
) {
    CompositionLocalProvider(LocalOnGlass provides true) {
    Column(
        modifier
            .fillMaxHeight()
            .heightIn(min = 76.dp)
            .ldGlass(RoundedCornerShape(LdRadius.tile))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = LdColors.onGlassMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leading()
            Text(
                value,
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                color = valueColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = LdColors.onGlassMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    }
}

@Composable
private fun ActionTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LdColors.onGlass,
    filled: Boolean = false,
    spinning: Boolean = false,
    glow: Boolean = false,
) {
    val shape = RoundedCornerShape(LdRadius.tile)
    val interaction = remember(label) { MutableInteractionSource() }
    val ink = if (filled) LdColors.white else tint
    Box(modifier.height(80.dp)) {
        if (glow) {
            val pulse = if (Motion.enabled) ringPulse() else 0.7f
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = 0.22f + 0.38f * pulse
                        scaleX = 1.04f
                        scaleY = 1.08f
                    }
                    .background(LdColors.glow, shape),
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .pressScale(interaction)
                .then(
                    if (filled) {
                        Modifier.background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(LdColors.primary, LdColors.deep),
                            ),
                            shape,
                        )
                    } else {
                        Modifier.ldGlass(shape, strong = true)
                    },
                )
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    enabled = !spinning,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (spinning) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = ink, strokeWidth = 2.dp)
            } else {
                LuIcon(icon, contentDescription = null, tint = ink, size = 22.dp)
            }
            Text(
                label,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ringPulse(): Float {
    val transition = rememberInfiniteTransition(label = "ringGlow")
    val value by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = Motion.easeInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringGlow",
    )
    return value
}

/**
 * On-device only. Uses the fused provider's last fix and does not request a new one.
 * A missing permission or a missing fix returns null.
 *
 * DATA-FLOW: Play services lastLocation, already on this phone. Nothing is sent.
 */
private fun quietDistance(context: Context, lat: Double, lon: Double): String? {
    if (!LocationSharing.hasLocationPermission(context)) return null
    val fix = try {
        val client = LocationServices.getFusedLocationProviderClient(context)
        Tasks.await(client.lastLocation, 2, TimeUnit.SECONDS)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    } ?: return null
    val meters = haversine(fix.latitude, fix.longitude, lat, lon)
    if (!meters.isFinite() || meters < 0.0) return null
    val miles = Locale.getDefault().country in setOf("US", "GB", "LR", "MM")
    return if (miles) {
        val shown = (meters / 1609.344).coerceAtLeast(0.1)
        context.getString(R.string.home_mi, shown)
    } else if (meters < 1000.0) {
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
