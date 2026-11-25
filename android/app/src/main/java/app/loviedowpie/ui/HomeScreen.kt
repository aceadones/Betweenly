package app.loviedowpie.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import app.loviedowpie.ui.icons.Lu
import app.loviedowpie.BuildConfig
import app.loviedowpie.R
import app.loviedowpie.Store
import app.loviedowpie.UpdateCheck
import app.loviedowpie.UpdateInfo
import app.loviedowpie.Updater
import kotlin.coroutines.cancellation.CancellationException

/**
 * Location tab: partner card, the two sharing switches, the map button, and
 * ring. The shell draws the status bar inset and the bottom bar, so this
 * scaffold adds none of its own. An update banner shows when [Updater.check] finds one.
 */
@Composable
fun HomeScreen(onTrack: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val revision by Store.changes.collectAsState()
    val now = rememberNow()
    // Kept across rotation. A quiet result does not clear a banner already found
    // in this process. A newer installed build hides it. Errors stay silent.
    var versionCode by rememberSaveable { mutableStateOf(0) }
    var versionName by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
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
    val myName = remember(revision) {
        Store.myName(context).ifBlank { context.getString(R.string.partner_fallback) }
    }
    val seenValue = if (loc == null || loc.lastSeen <= 0L) {
        stringResource(R.string.no_update_yet)
    } else {
        lastSeenPhrase(context, loc.lastSeen, now)
    }
    val accuracyValue = loc?.acc?.takeIf { it > 0f }?.let { accuracyPhrase(context, it) }
        ?: stringResource(R.string.stat_none)

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
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GreetingHeader(
                name = myName,
                revision = revision,
                loadAvatar = { Store.myAvatar(context) },
            ) {
                GlassIconButton(
                    onClick = onTrack,
                    icon = Lu.MapPin,
                    description = stringResource(R.string.track_map),
                )
            }
            update?.let { UpdateBanner(it, Modifier.appear(1)) }
            Column(
                modifier = Modifier.fillMaxWidth().ldGlass().padding(8.dp).appear(2),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundAvatar(revision, { Store.partnerAvatar(context) }, name, 72.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
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
                                heard -> stringResource(R.string.sharing_paused)
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
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val batteryText = if (loc == null || loc.bat !in 0..100) {
                        stringResource(R.string.battery_unknown_short)
                    } else {
                        batteryPhrase(context, loc.bat, loc.chg)
                    }
                    StatTile(
                        stringResource(R.string.stat_battery),
                        batteryText,
                        Modifier.weight(1f).appear(0),
                    )
                    StatTile(stringResource(R.string.stat_seen), seenValue, Modifier.weight(1f).appear(1))
                    StatTile(stringResource(R.string.stat_accuracy), accuracyValue, Modifier.weight(1f).appear(2))
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().ldGlass().padding(16.dp).appear(3),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.you), style = MaterialTheme.typography.titleMedium)
                ShareToggles(snackbar)
            }
            PrimaryPill(
                text = stringResource(R.string.track_map),
                onClick = onTrack,
                modifier = Modifier.fillMaxWidth(),
            )
            RingButton(snackbar, Modifier.fillMaxWidth())
        }
    }
}
