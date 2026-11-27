package com.nextlayer.betweenly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.AppScope
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.Shared
import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Two counters both of you can edit: how long since you met online, and how long until you meet
 * in person (or how long since you last did). Dates sync to your partner.
 *
 * DATA-FLOW: two dates, encrypted, to the partner via the relay.
 */
@Composable
fun CountdownScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val items by rememberShared("cd")
    val record = items.firstOrNull { it.id == Shared.COUNTDOWN_ID }
    val met = record?.d?.optString("met").orEmpty().let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val irl = record?.d?.optString("irl").orEmpty().let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val today = LocalDate.now()

    fun save(newMet: LocalDate?, newIrl: LocalDate?) {
        val d = JSONObject().put("met", newMet?.toString() ?: "").put("irl", newIrl?.toString() ?: "")
        AppScope.scope.launch { Shared.put(context, "cd", Shared.COUNTDOWN_ID, d) }
    }

    FeatureScaffold(stringResource(R.string.cd_title), onBack) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CountCard(
                modifier = Modifier.appear(0),
                label = stringResource(R.string.cd_since_met),
                date = met,
                today = today,
                futureLabel = stringResource(R.string.cd_not_yet),
                emptyLabel = stringResource(R.string.cd_set_met),
                onEdit = { pickDate(context, met ?: today) { save(it, irl) } },
            )
            CountCard(
                modifier = Modifier.appear(1),
                label = stringResource(R.string.cd_until_meet),
                date = irl,
                today = today,
                futureLabel = stringResource(R.string.cd_until_suffix),
                pastLabel = stringResource(R.string.cd_since_last_meet),
                emptyLabel = stringResource(R.string.cd_set_irl),
                onEdit = { pickDate(context, irl ?: today.plusDays(30)) { save(met, it) } },
            )
        }
    }
}

@Composable
private fun CountCard(
    modifier: Modifier,
    label: String,
    date: LocalDate?,
    today: LocalDate,
    futureLabel: String,
    emptyLabel: String,
    pastLabel: String = "",
    onEdit: () -> Unit,
) {
    GlassCardButton(onEdit, modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = LdColors.onGlassMuted)
            if (date == null) {
                Text(emptyLabel, style = MaterialTheme.typography.titleLarge, color = LdColors.onGlass)
            } else {
                val future = date.isAfter(today)
                val a = if (future) today else date
                val b = if (future) date else today
                val p = Period.between(a, b)
                val total = ChronoUnit.DAYS.between(a, b)
                Text(
                    spanText(p),
                    style = MaterialTheme.typography.headlineMedium,
                    color = LdColors.onGlass,
                )
                Text(
                    stringResource(R.string.cd_total_days, total) + when {
                        future && pastLabel.isNotEmpty() -> "  ·  $futureLabel"
                        !future && pastLabel.isNotEmpty() -> "  ·  $pastLabel"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = LdColors.onGlassMuted,
                )
                Text(
                    date.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = LdColors.onGlassMuted,
                )
            }
        }
    }
}

@Composable
private fun spanText(p: Period): String {
    val parts = ArrayList<String>()
    if (p.years > 0) parts.add(pluralStringResource(R.plurals.cd_years, p.years, p.years))
    if (p.months > 0) parts.add(pluralStringResource(R.plurals.cd_months, p.months, p.months))
    if (p.days > 0 || parts.isEmpty()) parts.add(pluralStringResource(R.plurals.cd_days, p.days, p.days))
    return parts.joinToString(", ")
}

@Composable
private fun pluralStringResource(id: Int, count: Int, vararg args: Any): String =
    androidx.compose.ui.res.pluralStringResource(id, count, *args)
