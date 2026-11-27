package com.nextlayer.betweenly.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch
import org.json.JSONObject

private val lines = listOf(
    intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
    intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
    intArrayOf(0, 4, 8), intArrayOf(2, 4, 6),
)

private fun winnerLine(board: String): IntArray? =
    lines.firstOrNull { l -> board[l[0]] != ' ' && board[l[0]] == board[l[1]] && board[l[1]] == board[l[2]] }

/**
 * X and O with your partner. The person who starts a game is X. Moves sync as small encrypted
 * messages; a move pings the other phone so they know it is their turn.
 *
 * DATA-FLOW: the board (nine characters) and whose turn it is, encrypted, to the partner via the relay.
 */
@Composable
fun GameScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val games by rememberShared("xo")
    val game = games.maxByOrNull { it.ts }
    val me = Store.myName(context).ifBlank { stringResource(R.string.you) }
    val her = Store.partnerName(context).ifBlank { stringResource(R.string.partner_default) }

    fun newGame() {
        val d = JSONObject().put("b", "         ").put("t", "X")
        AppScope.scope.launch { Shared.put(context, "xo", null, d, wake = true) }
    }

    FeatureScaffold(stringResource(R.string.xo_title), onBack) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (game == null) {
                Text(stringResource(R.string.xo_empty), style = MaterialTheme.typography.bodyLarge, color = LdColors.colors.inkMuted)
                AccentPill(stringResource(R.string.xo_start), { newGame() })
            } else {
                val board = game.d.optString("b", "         ").padEnd(9, ' ').take(9)
                val turn = game.d.optString("t", "X")
                val myMark = if (game.mine) "X" else "O"
                val win = winnerLine(board)
                val full = board.none { it == ' ' }
                val myTurn = win == null && !full && turn == myMark
                val status = when {
                    win != null -> if (board[win[0]].toString() == myMark) stringResource(R.string.xo_you_won) else stringResource(R.string.xo_they_won, her)
                    full -> stringResource(R.string.xo_draw)
                    myTurn -> stringResource(R.string.xo_your_turn, myMark)
                    else -> stringResource(R.string.xo_their_turn, her)
                }
                Text(
                    status,
                    modifier = Modifier.fillMaxWidth().appear(0),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge,
                    color = LdColors.colors.ink,
                )
                Column(
                    Modifier.fillMaxWidth().aspectRatio(1f).ldGlass(RoundedCornerShape(LdRadius.card)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (r in 0..2) {
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (c in 0..2) {
                                val i = r * 3 + c
                                val mark = board[i]
                                val inWin = win?.contains(i) == true
                                val pop by animateFloatAsState(
                                    if (mark == ' ') 0.4f else 1f,
                                    Motion.spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
                                    label = "mark$i",
                                )
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (inWin) Color(0x66D62448) else if (LdColors.glassLight) Color(0x14000000) else Color(0x1AFFFFFF))
                                        .clickable(enabled = myTurn && mark == ' ') {
                                            val nb = StringBuilder(board).also { it.setCharAt(i, myMark[0]) }.toString()
                                            val d = JSONObject(game.d.toString()).put("b", nb).put("t", if (myMark == "X") "O" else "X")
                                            AppScope.scope.launch { Shared.put(context, "xo", game.id, d, wake = true) }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (mark != ' ') {
                                        Text(
                                            mark.toString(),
                                            modifier = Modifier.graphicsLayer { scaleX = pop; scaleY = pop; alpha = pop.coerceIn(0f, 1f) },
                                            style = MaterialTheme.typography.displayMedium,
                                            color = if (mark == 'X') LdColors.primary else LdColors.onGlass,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                val scoreMe = games.count { g -> winnerLine(g.d.optString("b").padEnd(9, ' '))?.let { l -> g.d.optString("b")[l[0]] == (if (g.mine) 'X' else 'O') } == true }
                val scoreHer = games.count { g -> winnerLine(g.d.optString("b").padEnd(9, ' '))?.let { l -> g.d.optString("b")[l[0]] == (if (g.mine) 'O' else 'X') } == true }
                Text(
                    "$me $scoreMe  ·  $scoreHer $her",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    color = LdColors.colors.inkMuted,
                )
                if (win != null || full) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        AccentPill(stringResource(R.string.xo_again), { newGame() })
                    }
                }
            }
        }
    }
}
