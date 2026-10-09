package app.truenascompanion.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 1.8.0 design tokens (UI review P1-1..P1-3). Spacing is a 4 dp scale; radii depend on the role of the surface, so a
 * 60 dp row doesn't turn into a pill while a big card keeps soft corners.
 */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** Bottom padding of lists with a floating action button. */
    val fabClearance = 96.dp
}

object Radius {
    /** Content cards (Home widgets, sections). */
    val card = 24.dp
    /** List rows, hub tiles, compact cards. */
    val row = 16.dp
    /** Tags and small chips. */
    val tag = 8.dp
    val cardShape = RoundedCornerShape(card)
    val rowShape = RoundedCornerShape(row)
    val tagShape = RoundedCornerShape(tag)
}

/** Card padding: comfortable for content cards, compact for rows and tiles. */
object CardPadding {
    val comfortable = 16.dp
    val compact = 12.dp
}
