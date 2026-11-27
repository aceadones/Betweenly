package com.nextlayer.betweenly.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.AppScope
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.Shared
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Shared to-do and wish lists. You make the lists (places to go, things to buy, anything you like);
 * there are no presets. Either of you can add, tick off or remove an entry.
 *
 * DATA-FLOW: list names and entries, encrypted, to the partner via the relay.
 */
@Composable
fun TodoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val cats by rememberShared("todo_cat")
    val entries by rememberShared("todo")
    val ordered = cats.sortedBy { it.ts }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val current = ordered.firstOrNull { it.id == selectedId } ?: ordered.firstOrNull()
    var naming by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<com.nextlayer.betweenly.SharedItem?>(null) }
    var draft by remember { mutableStateOf("") }

    FeatureScaffold(stringResource(R.string.todo_title), onBack) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ordered.forEach { c ->
                    val on = c.id == current?.id
                    val bg by animateColorAsState(
                        if (on) LdColors.primary else Color(0x22808080),
                        Motion.tween(160),
                        label = "chip",
                    )
                    Text(
                        c.d.optString("name"),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(bg)
                            .clickable { selectedId = c.id }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) Color.White else LdColors.colors.ink,
                    )
                }
                Text(
                    stringResource(R.string.todo_new_list),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, LdColors.primary, RoundedCornerShape(50))
                        .clickable { draft = ""; naming = true }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = LdColors.primary,
                )
            }
            if (current == null) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.todo_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = LdColors.colors.inkMuted,
                    )
                }
            } else {
                val mine = entries.filter { it.d.optString("cat") == current.id }.sortedWith(
                    compareBy({ it.d.optInt("done") }, { it.ts }),
                )
                var newText by remember(current.id) { mutableStateOf("") }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "head") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                current.d.optString("name"),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleLarge,
                                color = LdColors.colors.ink,
                            )
                            Text(
                                stringResource(R.string.todo_manage),
                                modifier = Modifier.clickable { editing = current }.padding(8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = LdColors.primary,
                            )
                        }
                    }
                    items(mine, key = { it.id }) { item ->
                        val done = item.d.optInt("done") == 1
                        val alpha by animateFloatAsState(if (done) 0.55f else 1f, Motion.tween(180), label = "done")
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .ldGlass(RoundedCornerShape(LdRadius.card))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val fill by animateColorAsState(if (done) LdColors.primary else Color.Transparent, Motion.tween(160), label = "tick")
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(fill)
                                    .border(2.dp, LdColors.primary, CircleShape)
                                    .clickable {
                                        val d = JSONObject(item.d.toString()).put("done", if (done) 0 else 1)
                                        AppScope.scope.launch { Shared.put(context, "todo", item.id, d) }
                                    },
                                contentAlignment = Alignment.Center,
                            ) { if (done) LuIcon(Lu.Check, null, tint = Color.White, size = 16.dp) }
                            Text(
                                item.d.optString("text"),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                                ),
                                color = LdColors.onGlass.copy(alpha = alpha),
                            )
                            Text(
                                "✕",
                                modifier = Modifier
                                    .clickable { AppScope.scope.launch { Shared.remove(context, "todo", item.id) } }
                                    .padding(6.dp),
                                color = LdColors.onGlassMuted,
                            )
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .ldGlass(RoundedCornerShape(50))
                        .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    fun add() {
                        val t = newText.trim()
                        if (t.isEmpty()) return
                        val d = JSONObject().put("cat", current.id).put("text", t.take(200)).put("done", 0)
                        AppScope.scope.launch { Shared.put(context, "todo", null, d) }
                        newText = ""
                    }
                    BasicTextField(
                        value = newText,
                        onValueChange = { newText = it.take(200) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = LdColors.onGlass),
                        cursorBrush = SolidColor(LdColors.onGlass),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add() }),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (newText.isEmpty()) Text(stringResource(R.string.todo_add), color = LdColors.onGlassMuted)
                            inner()
                        },
                    )
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (newText.isBlank()) Color(0x33808080) else LdColors.primary)
                            .clickable(enabled = newText.isNotBlank()) { add() },
                        contentAlignment = Alignment.Center,
                    ) { LuIcon(Lu.Plus, null, tint = Color.White, size = 20.dp) }
                }
            }
        }
    }
    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.todo_name_title)) },
            text = {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it.take(30) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = LdColors.colors.ink),
                    cursorBrush = SolidColor(LdColors.primary),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (draft.isEmpty()) Text(stringResource(R.string.todo_name_hint), color = LdColors.colors.inkMuted)
                        inner()
                    },
                )
            },
            confirmButton = {
                Text(
                    stringResource(R.string.photo_edit_done),
                    modifier = Modifier
                        .clickable {
                            val name = draft.trim()
                            if (name.isNotEmpty()) {
                                AppScope.scope.launch {
                                    val id = Shared.put(context, "todo_cat", null, JSONObject().put("name", name))
                                    selectedId = id
                                }
                            }
                            naming = false
                        }
                        .padding(12.dp),
                    color = LdColors.primary,
                )
            },
        )
    }
    editing?.let { cat ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(cat.d.optString("name")) },
            text = { Text(stringResource(R.string.todo_delete_help)) },
            confirmButton = {
                Text(
                    stringResource(R.string.todo_delete_list),
                    modifier = Modifier
                        .clickable {
                            val id = cat.id
                            editing = null
                            selectedId = null
                            AppScope.scope.launch {
                                entries.filter { it.d.optString("cat") == id }.forEach { Shared.remove(context, "todo", it.id) }
                                Shared.remove(context, "todo_cat", id)
                            }
                        }
                        .padding(12.dp),
                    color = LdColors.primary,
                )
            },
            dismissButton = {
                Text(
                    stringResource(R.string.frame_cancel),
                    modifier = Modifier.clickable { editing = null }.padding(12.dp),
                    color = LdColors.colors.ink,
                )
            },
        )
    }
}
