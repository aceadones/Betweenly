package com.nextlayer.betweenly.ui

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The seven quick-reaction emoji, plus a short recent list for the picker.
 * Both live in the app prefs (the same file [com.nextlayer.betweenly.Store] uses).
 * DATA-FLOW: this phone only. Nothing here is sent until a reaction is tapped.
 */
object ChatReactions {
    const val COUNT = 7
    val defaults: List<String> = listOf("❤️", "😂", "😮", "😢", "🙏", "👍", "🔥")

    private const val PREFS = "app"
    private const val SLOTS = "chat_react_slots"
    private const val RECENT = "chat_react_recent"
    private val lock = Any()
    private var ready = false
    private val slotsMut = MutableStateFlow(defaults)

    val slots: StateFlow<List<String>> = slotsMut

    fun refresh(context: Context) {
        synchronized(lock) {
            slotsMut.value = readSlots(context)
            ready = true
        }
    }

    fun current(context: Context): List<String> {
        if (!ready) refresh(context)
        return slotsMut.value
    }

    fun save(context: Context, next: List<String>) {
        val clean = MutableList(COUNT) { index ->
            val raw = next.getOrNull(index)?.trim().orEmpty()
            val emoji = raw.filter { it >= ' ' }.take(16)
            emoji.ifEmpty { defaults[index] }
        }
        synchronized(lock) {
            try {
                val array = JSONArray()
                clean.forEach { array.put(it) }
                prefs(context).edit().putString(SLOTS, array.toString()).commit()
            } catch (_: Exception) {
                // The in-memory row still updates for this process.
            }
            slotsMut.value = clean
            ready = true
        }
    }

    fun recent(context: Context): List<String> {
        return try {
            val raw = prefs(context).getString(RECENT, "").orEmpty()
            if (raw.isEmpty()) return emptyList()
            val array = JSONArray(raw)
            val out = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val emoji = array.optString(i).trim().filter { it >= ' ' }.take(16)
                if (emoji.isNotEmpty() && emoji !in out) out.add(emoji)
            }
            out.take(16)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun pushRecent(context: Context, emoji: String) {
        val clean = emoji.trim().filter { it >= ' ' }.take(16)
        if (clean.isEmpty()) return
        val next = ArrayList<String>(17)
        next.add(clean)
        for (item in recent(context)) {
            if (item != clean && next.size < 16) next.add(item)
        }
        try {
            val array = JSONArray()
            next.forEach { array.put(it) }
            prefs(context).edit().putString(RECENT, array.toString()).commit()
        } catch (_: Exception) {
            // The pick still applies to the slot.
        }
    }

    /** Partner emoji first, then this phone's, with a count when both match. */
    fun chips(json: JSONObject): List<Pair<String, Int>> {
        val theirs = json.optString("rx")
        val mine = json.optString("mx")
        if (theirs.isEmpty() && mine.isEmpty()) return emptyList()
        val order = LinkedHashMap<String, Int>()
        if (theirs.isNotEmpty()) order[theirs] = 1
        if (mine.isNotEmpty()) order[mine] = (order[mine] ?: 0) + 1
        return order.map { it.key to it.value }
    }

    private fun readSlots(context: Context): List<String> {
        return try {
            val raw = prefs(context).getString(SLOTS, "").orEmpty()
            if (raw.isEmpty()) return defaults
            val array = JSONArray(raw)
            val out = MutableList(COUNT) { defaults[it] }
            for (i in 0 until minOf(COUNT, array.length())) {
                val emoji = array.optString(i).trim().filter { it >= ' ' }.take(16)
                if (emoji.isNotEmpty()) out[i] = emoji
            }
            out
        } catch (_: Exception) {
            defaults
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Quick-reaction pill. The last slot edits the seven emoji. */
@Composable
fun ReactionPill(
    emojis: List<String>,
    editing: Boolean,
    onEmoji: (index: Int) -> Unit,
    onEdit: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .ldGlass(RoundedCornerShape(LdRadius.chip), solid = true)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        emojis.take(ChatReactions.COUNT).forEachIndexed { index, emoji ->
            SlotPop(index) {
                val press = remember { MutableInteractionSource() }
                val outline = if (editing) {
                    Modifier.border(1.5.dp, LdColors.primary, CircleShape)
                } else {
                    Modifier
                }
                Row(
                    modifier = outline
                        .pressScale(press)
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = press,
                            indication = LocalIndication.current,
                            onClick = { onEmoji(index) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Text(emoji, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        SlotPop(ChatReactions.COUNT) {
            val press = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .pressScale(press)
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = press,
                        indication = LocalIndication.current,
                        onClick = if (editing) onDone else onEdit,
                    )
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (editing) {
                    Text(
                        stringResource(R.string.chat_react_done),
                        style = MaterialTheme.typography.labelLarge,
                        color = LdColors.colors.ink,
                    )
                } else {
                    LuIcon(
                        Lu.SmilePlus,
                        contentDescription = stringResource(R.string.chat_react_edit),
                        tint = LdColors.colors.ink,
                    )
                }
            }
        }
    }
}

/** Reply, copy, delete, and unsend. Sits under the pressed bubble. */
@Composable
fun MessageActionCard(
    canReply: Boolean,
    canCopy: Boolean,
    canRetry: Boolean,
    canUnsend: Boolean,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onUnsend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(min = 200.dp, max = 280.dp)
            .ldGlass(RoundedCornerShape(LdRadius.card), solid = true)
            .padding(vertical = 8.dp),
    ) {
        if (canReply) {
            ActionRow(Lu.Reply, stringResource(R.string.chat_reply), onReply)
        }
        if (canCopy) {
            ActionRow(Lu.Copy, stringResource(R.string.chat_copy), onCopy)
        }
        if (canRetry) {
            ActionRow(Lu.RefreshCw, stringResource(R.string.chat_retry), onRetry)
        }
        ActionRow(Lu.Trash2, stringResource(R.string.chat_delete), onDelete)
        if (canUnsend) {
            ActionRow(Lu.Undo2, stringResource(R.string.chat_unsend), onUnsend)
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(press)
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = press,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LuIcon(icon, contentDescription = null, size = 20.dp, tint = LdColors.colors.ink)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = LdColors.colors.ink)
    }
}

/** Fade and a 0.94 scale. The first eight slots stagger 35ms. Snaps when motion is off. */
@Composable
private fun SlotPop(index: Int, content: @Composable () -> Unit) {
    val on = Motion.enabled
    var shown by remember { mutableStateOf(!on) }
    LaunchedEffect(Unit) { shown = true }
    if (!on) {
        content()
        return
    }
    val delayMs = index.coerceIn(0, 7) * 35
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = Motion.tween(160, Motion.easeOut, delayMs = delayMs),
        label = "slotA",
    )
    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.94f,
        animationSpec = Motion.tween(160, Motion.easeOut, delayMs = delayMs),
        label = "slotS",
    )
    androidx.compose.foundation.layout.Box(
        Modifier.graphicsLayer {
            this.alpha = alpha
            scaleX = scale
            scaleY = scale
        },
    ) {
        content()
    }
}
