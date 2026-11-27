package com.nextlayer.betweenly.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.AppScope
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.Shared
import com.nextlayer.betweenly.SharedItem
import com.nextlayer.betweenly.Store
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A mood and its emoji. The key is what is stored and sent. */
private class Mood(val key: String, val emoji: String, val label: Int)

private val moods = listOf(
    Mood("happy", "😊", R.string.mood_happy),
    Mood("love", "😍", R.string.mood_love),
    Mood("cuddly", "🥰", R.string.mood_cuddly),
    Mood("flirty", "😏", R.string.mood_flirty),
    Mood("horny", "🥵", R.string.mood_horny),
    Mood("sleepy", "😴", R.string.mood_sleepy),
    Mood("bored", "😒", R.string.mood_bored),
    Mood("angry", "😡", R.string.mood_angry),
    Mood("sad", "😢", R.string.mood_sad),
    Mood("anxious", "😰", R.string.mood_anxious),
)

private fun moodOf(key: String?): Mood? = moods.firstOrNull { it.key == key }

/**
 * A mood calendar. Pick how you feel today; the month grid shows two small lines per day, yours on
 * top and your partner's below. Tap a day for the details.
 *
 * DATA-FLOW: a day and a mood word, encrypted, to the partner via the relay.
 */
@Composable
fun MoodScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val items by rememberShared("mood")
    val today = LocalDate.now()
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var picked by remember { mutableStateOf(today) }
    val me = Store.myName(context).ifBlank { stringResource(R.string.you) }
    val her = Store.partnerName(context).ifBlank { stringResource(R.string.partner_default) }

    fun moodFor(day: LocalDate, mine: Boolean): SharedItem? =
        items.filter { it.d.optString("day") == day.toString() && it.mine == mine }.maxByOrNull { it.ts }

    val myToday = moodFor(today, true)

    FeatureScaffold(stringResource(R.string.mood_title), onBack) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.mood_today_q), style = MaterialTheme.typography.titleMedium, color = LdColors.colors.ink)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                moods.forEach { m ->
                    val on = myToday?.d?.optString("m") == m.key
                    val scale by animateFloatAsState(if (on) 1.12f else 1f, Motion.spring(), label = "moodScale")
                    Column(
                        Modifier
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) Color(0x33D62448) else Color(0x14808080))
                            .border(if (on) 2.dp else 0.dp, if (on) LdColors.primary else Color.Transparent, RoundedCornerShape(16.dp))
                            .clickable {
                                val d = JSONObject().put("day", today.toString()).put("m", m.key)
                                AppScope.scope.launch { Shared.put(context, "mood", myToday?.id, d) }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(m.emoji, style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(m.label), style = MaterialTheme.typography.labelSmall, color = LdColors.colors.ink)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹",
                    modifier = Modifier.clickable { month = month.minusMonths(1) }.padding(horizontal = 14.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.headlineMedium,
                    color = LdColors.colors.ink,
                )
                Text(
                    month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    color = LdColors.colors.ink,
                )
                Text(
                    "›",
                    modifier = Modifier.clickable { month = month.plusMonths(1) }.padding(horizontal = 14.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.headlineMedium,
                    color = LdColors.colors.ink,
                )
            }
            Row(Modifier.fillMaxWidth()) {
                for (i in 0..6) {
                    val dow = java.time.DayOfWeek.of((i + 6) % 7 + 1)
                    Text(
                        dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = LdColors.colors.inkMuted,
                    )
                }
            }
            val first = month.atDay(1)
            val lead = first.dayOfWeek.value % 7 // Sunday first
            val cells = lead + month.lengthOfMonth()
            val rows = (cells + 6) / 7
            Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (r in 0 until rows) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (c in 0..6) {
                            val n = r * 7 + c - lead + 1
                            if (n < 1 || n > month.lengthOfMonth()) {
                                Box(Modifier.weight(1f).aspectRatio(0.72f))
                            } else {
                                val day = month.atDay(n)
                                val a = moodOf(moodFor(day, true)?.d?.optString("m"))
                                val b = moodOf(moodFor(day, false)?.d?.optString("m"))
                                val sel = day == picked
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .aspectRatio(0.72f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (sel) Color(0x33D62448) else Color(0x10808080))
                                        .border(if (day == today) 1.5.dp else 0.dp, LdColors.primary, RoundedCornerShape(10.dp))
                                        .clickable { picked = day }
                                        .padding(vertical = 3.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.SpaceEvenly,
                                ) {
                                    Text("$n", style = MaterialTheme.typography.labelSmall, color = LdColors.colors.inkMuted)
                                    Text(a?.emoji ?: "·", style = MaterialTheme.typography.bodyMedium)
                                    Text(b?.emoji ?: "·", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
            val pa = moodOf(moodFor(picked, true)?.d?.optString("m"))
            val pb = moodOf(moodFor(picked, false)?.d?.optString("m"))
            Column(
                Modifier.fillMaxWidth().ldGlass(RoundedCornerShape(LdRadius.card)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(picked.toString(), style = MaterialTheme.typography.labelLarge, color = LdColors.onGlassMuted)
                Text(
                    "$me   " + (pa?.let { it.emoji + " " + stringResource(it.label) } ?: "—"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = LdColors.onGlass,
                )
                Text(
                    "$her   " + (pb?.let { it.emoji + " " + stringResource(it.label) } ?: "—"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = LdColors.onGlass,
                )
            }
        }
    }
}
