package app.loviedowpie.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.loviedowpie.R
import kotlinx.coroutines.delay

private class EmojiCategory(val title: Int, val emojis: List<String>)

/**
 * In-app emoji sheet. [visible] drives the scale-in from the bottom edge.
 * Picking calls [onPick] and does not dismiss; the caller closes it.
 */
@Composable
fun EmojiPicker(
    visible: Boolean,
    recent: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var render by remember { mutableStateOf(visible) }
    var category by remember { mutableIntStateOf(0) }
    SideEffect {
        if (visible) render = true
    }
    LaunchedEffect(visible) {
        if (!visible && render) {
            delay(if (Motion.enabled) 140 else 0)
            if (!visible) render = false
        }
    }
    if (!render) return
    val scrim by animateFloatAsState(
        targetValue = if (visible) 0.30f else 0f,
        animationSpec = Motion.tween(if (visible) 160 else 110),
        label = "emojiScrim",
    )
    BackHandler(enabled = visible, onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrim))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        BoxWithConstraints(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            val sheet = maxHeight * 0.72f
            PopupScale(visible = visible, origin = TransformOrigin(0.5f, 1f)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(sheet)
                        .ldGlass(
                            RoundedCornerShape(topStart = LdRadius.card, topEnd = LdRadius.card),
                            solid = true,
                        )
                        .navigationBarsPadding()
                        .padding(top = 12.dp),
                ) {
                    Text(
                        stringResource(R.string.chat_emoji_title),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = LdColors.colors.ink,
                    )
                    if (recent.isNotEmpty()) {
                        Text(
                            stringResource(R.string.chat_emoji_recent),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = LdColors.colors.inkMuted,
                        )
                        EmojiStrip(recent, onPick)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        emojiCategories.forEachIndexed { index, item ->
                            val selected = index == category
                            val press = remember(item.title) { MutableInteractionSource() }
                            Box(
                                modifier = Modifier
                                    .pressScale(press)
                                    .heightIn(min = 48.dp)
                                    .clickable(
                                        interactionSource = press,
                                        indication = null,
                                        onClick = { category = index },
                                    )
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(item.title),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) LdColors.primary else LdColors.colors.inkMuted,
                                )
                            }
                        }
                    }
                    val page = emojiCategories[category.coerceIn(emojiCategories.indices)]
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(48.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    ) {
                        items(page.emojis) { emoji ->
                            val press = remember(emoji) { MutableInteractionSource() }
                            Box(
                                modifier = Modifier
                                    .pressScale(press)
                                    .size(48.dp)
                                    .clickable(
                                        interactionSource = press,
                                        indication = null,
                                        onClick = { onPick(emoji) },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    emoji,
                                    style = MaterialTheme.typography.titleLarge,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmojiStrip(emojis: List<String>, onPick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        emojis.forEach { emoji ->
            val press = remember(emoji) { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .pressScale(press)
                    .size(48.dp)
                    .clickable(
                        interactionSource = press,
                        indication = null,
                        onClick = { onPick(emoji) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

private val smileys = listOf(
    "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃",
    "😉", "😊", "😇", "🥰", "😍", "🤩", "😘", "😗", "☺️", "😚",
    "😙", "🥲", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭",
    "🫢", "🫣", "🤫", "🤔", "🫡", "🤐", "🤨", "😐", "😑", "😶",
    "🫥", "😏", "😒", "🙄", "😬", "🤥", "😌", "😔", "😪", "🤤",
    "😴", "😷", "🤒", "🤕", "🥵", "🥶", "🥴", "😵", "🤯", "🤠",
    "🥳", "😎", "🤓", "😕", "😟", "😮", "😯", "😲", "😳", "🥺",
    "😢", "😭", "😱", "😤", "😡", "😠", "🤬", "😈", "💀", "🤡",
)

private val hearts = listOf(
    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔",
    "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "♥️",
    "💌", "💋", "😻", "😽", "💑", "👩‍❤️‍👨", "👩‍❤️‍👩", "👨‍❤️‍👨", "💏", "👩‍❤️‍💋‍👨",
    "❤️‍🔥", "❤️‍🩹", "🫀", "💐", "🌹", "🥀", "🌷", "🌺", "🌸", "💮",
    "🏵️", "🌼", "🌻", "💍", "💎", "🎁", "🎀", "💒", "😍", "😘",
)

private val gestures = listOf(
    "👋", "🤚", "🖐️", "✋", "🖖", "🫱", "🫲", "🫳", "🫴", "👌",
    "🤌", "🤏", "✌️", "🤞", "🫰", "🤟", "🤘", "🤙", "👈", "👉",
    "👆", "👇", "☝️", "👍", "👎", "✊", "👊", "🤛", "🤜", "👏",
    "🙌", "🫶", "👐", "🤲", "🤝", "🙏", "✍️", "💅", "🤳", "💪",
    "🦾", "👂", "👃", "👀", "👁️", "👅", "👄", "🦵", "🦶", "🦴",
)

private val people = listOf(
    "👶", "🧒", "👦", "👧", "🧑", "👱", "👨", "👩", "🧔", "👴",
    "👵", "🧓", "👮", "👷", "💂", "🕵️", "🧑‍⚕️", "🧑‍🌾", "🧑‍🍳", "🧑‍🎓",
    "🧑‍🎤", "🧑‍🏫", "🧑‍💻", "🧑‍💼", "🧑‍🔧", "🧑‍🔬", "🧑‍🎨", "🧑‍🚒", "🧑‍✈️", "🧑‍🚀",
    "🧑‍⚖️", "👰", "🤵", "👸", "🤴", "🥷", "🦸", "🦹", "🧙", "🧚",
    "🧛", "🧜", "🧝", "🧞", "🧟", "🎅", "🤶", "🧑‍🎄", "👼", "🧕",
)

private val animals = listOf(
    "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯",
    "🦁", "🐮", "🐷", "🐸", "🐵", "🙈", "🙉", "🙊", "🐔", "🐧",
    "🐦", "🐤", "🐣", "🐥", "🦆", "🦅", "🦉", "🦇", "🐺", "🐴",
    "🦄", "🐝", "🐛", "🦋", "🐌", "🐞", "🐜", "🐢", "🐍", "🦎",
    "🐙", "🦑", "🦐", "🦞", "🦀", "🐡", "🐠", "🐟", "🐬", "🐳",
    "🦈", "🐊", "🐅", "🐆", "🦓", "🦍", "🐘", "🦒", "🦘", "🐄",
)

private val food = listOf(
    "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐", "🍈",
    "🍒", "🍑", "🥭", "🍍", "🥥", "🥝", "🍅", "🍆", "🥑", "🥦",
    "🥬", "🥒", "🌶️", "🌽", "🥕", "🧄", "🧅", "🥔", "🍠", "🥐",
    "🥯", "🍞", "🥖", "🥨", "🧀", "🥚", "🍳", "🧈", "🥞", "🧇",
    "🥓", "🥩", "🍗", "🍖", "🌭", "🍔", "🍟", "🍕", "🥪", "🌮",
    "🌯", "🥗", "🍝", "🍜", "🍣", "🍱", "🥟", "🍦", "🍰", "🎂",
)

private val activities = listOf(
    "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🥏", "🎱",
    "🪀", "🏓", "🏸", "🏒", "🏑", "🥍", "🏏", "🪃", "🥅", "⛳",
    "🪁", "🏹", "🎣", "🤿", "🥊", "🥋", "🎽", "🛹", "🛼", "🛷",
    "⛸️", "🥌", "🎿", "⛷️", "🏂", "🪂", "🏋️", "🤼", "🤸", "⛹️",
    "🤾", "🏌️", "🏇", "🧘", "🏄", "🏊", "🚣", "🧗", "🚴", "🚵",
    "🏆", "🥇", "🥈", "🥉", "🎖️", "🏅", "🎪", "🎭", "🎬", "🎤",
)

private val travel = listOf(
    "🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑", "🚒", "🚐",
    "🛻", "🚚", "🚛", "🚜", "🏍️", "🛵", "🚲", "🛴", "🚨", "🚔",
    "✈️", "🛫", "🛬", "🛩️", "💺", "🚀", "🛸", "🚁", "🛶", "⛵",
    "🚤", "🛥️", "🛳️", "⛴️", "🚢", "⚓", "🚧", "⛽", "🚏", "🚦",
    "🚥", "🗺️", "🗽", "🗼", "🏰", "🏯", "🏟️", "🎡", "🎢", "🎠",
    "⛲", "🏖️", "🏝️", "🏜️", "🌋", "⛰️", "🏔️", "🏕️", "⛺", "🏠",
)

private val objects = listOf(
    "⌚", "📱", "💻", "⌨️", "🖥️", "🖨️", "🖱️", "💽", "💾", "💿",
    "📀", "📷", "📸", "📹", "🎥", "📞", "☎️", "📟", "📠", "📺",
    "📻", "🎙️", "⏱️", "⏲️", "⏰", "🕰️", "⌛", "⏳", "📡", "🔋",
    "🔌", "💡", "🔦", "🕯️", "🧯", "💸", "💵", "💴", "💶", "💷",
    "💰", "💳", "🧰", "🔧", "🔨", "⚒️", "🛠️", "⛏️", "🔩", "⚙️",
    "🧱", "🧲", "🔮", "🧿", "📿", "💈", "⚗️", "🔭", "🔬", "💊",
    "💉", "🩹", "🩺", "🔑", "🗝️", "🚪", "🪑", "🛋️", "🛏️", "🧸",
    "🎁", "🎈", "✉️", "📦", "📫", "📮", "📜", "📚", "✏️", "🔒",
)

private val symbols = listOf(
    "💯", "🔥", "✨", "⭐", "🌟", "💫", "⚡", "💥", "💦", "💨",
    "💤", "🎵", "🎶", "🔔", "🔕", "♻️", "✅", "❌", "❓", "❗",
    "➕", "➖", "➗", "©️", "®️", "™️", "🔴", "🟠", "🟡", "🟢",
    "🔵", "🟣", "⚫", "⚪", "🟤", "🔺", "🔻", "🔸", "🔹", "🔶",
    "🔷", "💠", "🔘", "🏁", "🚩", "♠️", "♥️", "♦️", "♣️", "🃏",
    "🔇", "🔈", "🔉", "🔊", "📣", "✳️", "❇️", "✴️", "🔰", "🔱",
)

private val emojiCategories = listOf(
    EmojiCategory(R.string.chat_emoji_smileys, smileys),
    EmojiCategory(R.string.chat_emoji_hearts, hearts),
    EmojiCategory(R.string.chat_emoji_gestures, gestures),
    EmojiCategory(R.string.chat_emoji_people, people),
    EmojiCategory(R.string.chat_emoji_animals, animals),
    EmojiCategory(R.string.chat_emoji_food, food),
    EmojiCategory(R.string.chat_emoji_activities, activities),
    EmojiCategory(R.string.chat_emoji_travel, travel),
    EmojiCategory(R.string.chat_emoji_objects, objects),
    EmojiCategory(R.string.chat_emoji_symbols, symbols),
)
