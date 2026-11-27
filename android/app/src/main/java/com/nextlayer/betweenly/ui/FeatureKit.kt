package com.nextlayer.betweenly.ui

import android.app.DatePickerDialog
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.ChatStore
import com.nextlayer.betweenly.Shared
import com.nextlayer.betweenly.SharedItem
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Common frame for the activity screens: page background, a header with a back button and
 * optional trailing actions, and the same entrance motion everywhere.
 *
 * DATA-FLOW: none by itself.
 */
@Composable
fun FeatureScaffold(
    title: String,
    onBack: () -> Unit,
    trailing: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize()) {
        AppBackground(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { ScreenHeader(title, onBack) }
                trailing()
            }
            content()
        }
    }
}

/** The shared records of one feature. Reloads when the chat store changes. */
@Composable
fun rememberShared(feature: String): State<List<SharedItem>> {
    val context = LocalContext.current
    val rev by ChatStore.changes.collectAsState()
    val state = remember { mutableStateOf<List<SharedItem>>(emptyList()) }
    LaunchedEffect(rev) {
        state.value = withContext(Dispatchers.IO) { Shared.items(context, feature) }
    }
    return state
}

/** A glass card with the app's press feel. */
@Composable
fun GlassCardButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction)
            .ldGlass(RoundedCornerShape(LdRadius.card))
            .clip(RoundedCornerShape(LdRadius.card))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) { content() }
}

/** Pill button in the accent colour. */
@Composable
fun AccentPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Text(
        label,
        modifier = modifier
            .pressScale(interaction)
            .clip(RoundedCornerShape(50))
            .background(LdColors.primary)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        style = MaterialTheme.typography.titleSmall,
        color = androidx.compose.ui.graphics.Color.White,
    )
}

/** System date picker. [onPick] gets a [LocalDate]. */
fun pickDate(context: Context, start: LocalDate, onPick: (LocalDate) -> Unit) {
    DatePickerDialog(
        context,
        { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) },
        start.year,
        start.monthValue - 1,
        start.dayOfMonth,
    ).show()
}
