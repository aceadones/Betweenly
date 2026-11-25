package app.loviedowpie.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Concentric corners. A rounded child inside a rounded parent uses
 * inner = outer − the gap between them, so both curves share one center.
 * A 28dp card with 8dp padding holds a 20dp tile. A 20dp bubble with a 4dp
 * media inset holds 16dp media. Do not copy the parent's radius onto the child.
 * [chip] is a capsule (pills, switches, dock-adjacent controls) and stays one.
 */
object LdRadius {
    val card = 28.dp
    val tile = 20.dp
    val chip = 999.dp

    fun inner(outer: Dp, inset: Dp): Dp = (outer - inset).coerceAtLeast(2.dp)
}

fun concentricShape(outer: Dp, inset: Dp): RoundedCornerShape =
    RoundedCornerShape(LdRadius.inner(outer, inset))
