package com.nextlayer.betweenly.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.R

/**
 * Hands the partner's last point to Google Maps. Betweenly does not upload it.
 *
 * DATA-FLOW: none. The user's tap gives lat/lon to the Maps app, or to the
 * browser if Maps is not installed. This process does not send the coordinates.
 */
internal object PartnerMaps {
    private const val PACKAGE = "com.google.android.apps.maps"

    fun open(context: Context, lat: Double, lon: Double, label: String) {
        val geo = geoUri(lat, lon, label)
        val web = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lon")
        if (!start(context, Intent(Intent.ACTION_VIEW, geo).setPackage(PACKAGE))) {
            start(context, Intent(Intent.ACTION_VIEW, web))
        }
    }

    fun directions(context: Context, lat: Double, lon: Double, label: String) {
        val dir = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")
        val maps = Intent(Intent.ACTION_VIEW, dir).setPackage(PACKAGE)
        if (start(context, maps)) return
        val geo = Intent(Intent.ACTION_VIEW, geoUri(lat, lon, label)).setPackage(PACKAGE)
        if (start(context, geo)) return
        start(context, Intent(Intent.ACTION_VIEW, dir))
    }

    private fun geoUri(lat: Double, lon: Double, label: String): Uri {
        val name = label.ifBlank { "Location" }.replace("(", " ").replace(")", " ")
        return Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(name)})")
    }

    private fun start(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }
}

@Composable
fun PartnerMapActions(
    latitude: Double,
    longitude: Double,
    name: String,
    modifier: Modifier = Modifier,
    hintColor: Color = LdColors.colors.inkMuted,
) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryPill(
            text = stringResource(R.string.map_open_google),
            onClick = { PartnerMaps.open(context, latitude, longitude, name) },
            modifier = Modifier.fillMaxWidth(),
        )
        SecondaryPill(
            text = stringResource(R.string.map_directions),
            onClick = { PartnerMaps.directions(context, latitude, longitude, name) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.map_google_hint),
            style = MaterialTheme.typography.labelSmall,
            color = hintColor,
        )
    }
}
