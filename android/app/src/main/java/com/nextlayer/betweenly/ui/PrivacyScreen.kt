package com.nextlayer.betweenly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import com.nextlayer.betweenly.R

/**
 * The in-app copy of PRIVACY-DATA-FLOW.md. The markdown file is not in the APK,
 * so the same facts are shown here when the person taps the link.
 *
 * DATA-FLOW: none. This screen does not load anything from the network.
 */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader(stringResource(R.string.privacy_title), onBack)
            Text(stringResource(R.string.privacy_intro), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.privacy_location), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.privacy_code), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.privacy_keys), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.privacy_fcm), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.privacy_tiles), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.privacy_update), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.privacy_not), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.privacy_keep), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
