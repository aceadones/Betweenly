package com.nextlayer.betweenly.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.nextlayer.betweenly.AppScope
import com.nextlayer.betweenly.R
import com.nextlayer.betweenly.Shared
import com.nextlayer.betweenly.SharedItem
import com.nextlayer.betweenly.Store
import com.nextlayer.betweenly.ui.icons.Lu
import com.nextlayer.betweenly.ui.icons.LuIcon
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Letters to the future. Write a letter, choose the day it opens, and it stays sealed for both of
 * you until then. The lock is kept by this app on each phone; the letter itself travels encrypted.
 *
 * DATA-FLOW: the title, text and open date of a letter, encrypted, to the partner via the relay.
 */
@Composable
fun LettersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val letters by rememberShared("letter")
    val today = LocalDate.now()
    var writing by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val opened = letters.firstOrNull { it.id == openId }
    val partner = Store.partnerName(context).ifBlank { stringResource(R.string.partner_default) }
    val me = Store.myName(context).ifBlank { stringResource(R.string.you) }

    FeatureScaffold(
        stringResource(R.string.letters_title),
        onBack = { if (opened != null) openId = null else if (writing) writing = false else onBack() },
        trailing = {
            if (!writing && opened == null) {
                GlassIconButton({ writing = true }, Lu.Plus, stringResource(R.string.letters_write))
            }
        },
    ) {
        when {
            writing -> Writer(onDone = { writing = false })
            opened != null -> Reader(opened, if (opened.mine) me else partner)
            letters.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.letters_empty), style = MaterialTheme.typography.bodyLarge, color = LdColors.colors.inkMuted)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(letters.sortedBy { it.d.optString("on") }, key = { it.id }) { letter ->
                    val on = runCatching { LocalDate.parse(letter.d.optString("on")) }.getOrNull() ?: today
                    val locked = on.isAfter(today)
                    val days = ChronoUnit.DAYS.between(today, on)
                    GlassCardButton(
                        onClick = { if (!locked) openId = letter.id },
                        modifier = Modifier.fillMaxWidth().animateItem(),
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            LuIcon(if (locked) Lu.Lock else Lu.Heart, null, tint = LdColors.primary, size = 30.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (locked) stringResource(R.string.letters_sealed) else letter.d.optString("title").ifBlank { stringResource(R.string.letters_untitled) },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = LdColors.onGlass,
                                )
                                Text(
                                    (if (letter.mine) me else partner) + " · " + if (locked) stringResource(R.string.letters_opens_in, on.toString(), days) else stringResource(R.string.letters_tap_open),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LdColors.onGlassMuted,
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
private fun Writer(onDone: () -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var on by remember { mutableStateOf(LocalDate.now().plusDays(30)) }
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicTextField(
            value = title,
            onValueChange = { title = it.take(60) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(color = LdColors.onGlass),
            cursorBrush = SolidColor(LdColors.onGlass),
            modifier = Modifier.fillMaxWidth().ldGlass(RoundedCornerShape(LdRadius.card)).padding(16.dp),
            decorationBox = { inner ->
                if (title.isEmpty()) Text(stringResource(R.string.letters_title_hint), color = LdColors.onGlassMuted, style = MaterialTheme.typography.titleLarge)
                inner()
            },
        )
        BasicTextField(
            value = body,
            onValueChange = { body = it.take(4000) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = LdColors.onGlass),
            cursorBrush = SolidColor(LdColors.onGlass),
            modifier = Modifier.fillMaxWidth().size(width = 400.dp, height = 260.dp).ldGlass(RoundedCornerShape(LdRadius.card)).padding(16.dp),
            decorationBox = { inner ->
                if (body.isEmpty()) Text(stringResource(R.string.letters_body_hint), color = LdColors.onGlassMuted)
                inner()
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.letters_opens_on), style = MaterialTheme.typography.bodyLarge, color = LdColors.colors.ink)
            Text(
                on.toString(),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x22808080))
                    .clickable { pickDate(context, on) { on = it } }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                color = LdColors.primary,
            )
        }
        Text(stringResource(R.string.letters_note), style = MaterialTheme.typography.bodySmall, color = LdColors.colors.inkMuted)
        AccentPill(
            stringResource(R.string.letters_seal),
            {
                if (body.isNotBlank()) {
                    val d = JSONObject().put("title", title.trim()).put("body", body.trim()).put("on", on.toString())
                    AppScope.scope.launch { Shared.put(context, "letter", null, d, wake = true) }
                    onDone()
                }
            },
        )
    }
}

@Composable
private fun Reader(letter: SharedItem, from: String) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(letter.d.optString("title").ifBlank { stringResource(R.string.letters_untitled) }, style = MaterialTheme.typography.headlineSmall, color = LdColors.colors.ink, modifier = Modifier.appear(0))
        Text(stringResource(R.string.letters_from, from), style = MaterialTheme.typography.labelLarge, color = LdColors.colors.inkMuted, modifier = Modifier.appear(1))
        Text(
            letter.d.optString("body"),
            modifier = Modifier.fillMaxWidth().appear(2).ldGlass(RoundedCornerShape(LdRadius.card)).padding(18.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = LdColors.onGlass,
        )
    }
}
