package app.tvflick.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlin.math.roundToInt

/** Touch drag, trackpad/shift-wheel and ordinary mouse wheel. Arrows hidden by request. */
@Composable
fun HorizontalStrip(
    mobile: Boolean,
    modifier: Modifier,
    spacing: Dp,
    itemWidth: Dp,
    content: @Composable RowScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var wheelJob by remember { mutableStateOf<Job?>(null) }
    var wheelTarget by remember { mutableIntStateOf(0) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().pointerInput(scroll, itemWidth, spacing) {
            val stride = (itemWidth.roundToPx() + spacing.roundToPx()).coerceAtLeast(1)
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Scroll && event.changes.any { !it.isConsumed }) {
                        val delta = event.changes.sumOf { change ->
                            val value = change.scrollDelta
                            (if (value.x != 0f) value.x else value.y).toDouble()
                        }.toFloat()
                        if ((delta > 0 && scroll.canScrollForward) || (delta < 0 && scroll.canScrollBackward)) {
                            event.changes.forEach { it.consume() }
                            // Finish wheel scrolling on whole cells, including at the end of the strip.
                            val start = if (wheelJob?.isActive == true) wheelTarget else scroll.value
                            wheelTarget = (((start.toFloat() / stride).roundToInt() + if (delta > 0) 1 else -1) * stride)
                                .coerceIn(0, scroll.maxValue)
                            wheelJob?.cancel()
                            wheelJob = scope.launch { scroll.animateScrollTo(wheelTarget) }
                        }
                    }
                }
            }
        }.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}
