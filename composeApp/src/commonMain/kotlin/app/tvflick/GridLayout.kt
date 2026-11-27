package app.tvflick

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Desktop overlays shrink with either window dimension, never beyond the approved maximum. */
internal fun collectionPanelWidth(windowWidth: Dp, mobile: Boolean, windowHeight: Dp = Dp.Infinity): Dp =
    if (mobile) minOf(375.dp, windowWidth)
    else minOf(1000.dp, windowWidth - 80.dp, 1000.dp * (windowHeight / 747.dp))

/** Keep the six-column composition while shrinking all desktop panel content together. */
internal fun panelContentScale(panelWidth: Dp, mobile: Boolean): Float =
    if (mobile) 1f else minOf(panelWidth / 760.dp, 1000f / 760f)

internal fun collectionPosterColumns(panelWidth: Dp, mobile: Boolean): Int =
    calculateGridColumns(panelWidth, 110.512.dp, if (mobile) 8.73.dp else 8.dp, if (mobile) 13.dp else 25.dp)

internal fun calculateGridColumns(availableWidth: Dp, cardWidth: Dp, gap: Dp, sidePadding: Dp): Int {
    val usable = availableWidth - sidePadding * 2
    if (usable < cardWidth) return 1
    return ((usable + gap) / (cardWidth + gap)).toInt().coerceAtLeast(1)
}

/** Fill a horizontal strip with whole reference cells, scaling their contents together. */
internal fun fittedStripCellWidth(width: Dp, referenceWidth: Dp, gap: Dp): Dp {
    val columns = calculateGridColumns(width, referenceWidth, gap, 0.dp)
    return (width - gap * (columns - 1)) / columns
}

internal data class AdaptiveCardGrid(val columns: Int, val cardWidth: Dp, val width: Dp)

internal fun calculateAdaptiveCardGrid(availableWidth: Dp, minCardWidth: Dp, maxCardWidth: Dp,
    gap: Dp, sidePadding: Dp): AdaptiveCardGrid {
    val columns = calculateGridColumns(availableWidth, minCardWidth, gap, sidePadding)
    val available = (availableWidth - sidePadding * 2 - gap * (columns - 1)) / columns
    val cardWidth = available.coerceAtMost(maxCardWidth)
    return AdaptiveCardGrid(columns, cardWidth, cardWidth * columns + gap * (columns - 1))
}

internal fun calculateSingleCardWidth(availableWidth: Dp, sidePadding: Dp, maxCardWidth: Dp): Dp =
    minOf(availableWidth - sidePadding * 2, maxCardWidth)
