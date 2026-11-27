package app.tvflick

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class GridLayoutTest {
    @Test
    fun `cast and similar strips fit whole cells across phone and desktop widths`() {
        for (width in listOf(304.dp, 359.dp, 476.1.dp, 720.dp)) {
            for ((reference, gap) in listOf(83.488.dp to 14.dp, 110.512.dp to 8.dp)) {
                val columns = calculateGridColumns(width, reference, gap, 0.dp)
                val cell = fittedStripCellWidth(width, reference, gap)
                assertEquals(width.value, (cell * columns + gap * (columns - 1)).value, .001f)
                kotlin.test.assertTrue(cell >= reference)
            }
        }
    }

    @Test
    fun `desktop panel content grows proportionally up to six columns and mobile remains unscaled`() {
        for (window in listOf(1137.dp, 1920.dp)) {
            val panel = collectionPanelWidth(window, false)
            val scale = panelContentScale(panel, false)
            assertEquals(760f, (panel / scale).value, .001f)
            assertEquals(6, collectionPosterColumns(panel / scale, false))
        }
        assertEquals(1000f / 760f, panelContentScale(1000.dp, false), .0001f)
        val narrow = collectionPanelWidth(720.dp, false)
        assertEquals(6, collectionPosterColumns(narrow / panelContentScale(narrow, false), false))
        assertEquals(1f, panelContentScale(375.dp, true))
    }
    @Test
    fun `desktop overlays shrink by width or height and retain their six column layout`() {
        val maximum = collectionPanelWidth(1137.dp, false, 747.dp)
        assertEquals(1000.dp, maximum)
        assertEquals(maximum, collectionPanelWidth(2560.dp, false, 1440.dp))
        for ((width, height) in listOf(800.dp to 600.dp, 1137.dp to 600.dp)) {
            val panel = collectionPanelWidth(width, false, height)
            val scale = panelContentScale(panel, false)
            kotlin.test.assertTrue(panel < maximum)
            kotlin.test.assertTrue(scale < panelContentScale(maximum, false))
            assertEquals(760f, (panel / scale).value, .001f)
            assertEquals(6, collectionPosterColumns(panel / scale, false))
        }
        assertEquals(375.dp, collectionPanelWidth(412.dp, true, 600.dp))
    }
    @Test
    fun `uses one column when two full cards do not fit`() {
        assertEquals(1, calculateGridColumns(767.dp, 359.dp, 10.dp, 20.dp))
    }

    @Test
    fun `uses two columns when both full cards and spacing fit`() {
        assertEquals(2, calculateGridColumns(856.dp, 359.dp, 10.dp, 20.dp))
    }

    @Test
    fun `does not add a column for one extra pixel shortfall`() {
        assertEquals(2, calculateGridColumns(1136.dp, 359.dp, 10.dp, 20.dp))
        assertEquals(3, calculateGridColumns(1137.dp, 359.dp, 10.dp, 20.dp))
    }

    @Test
    fun `caps desktop card width without stretching the cards`() {
        val grid = calculateAdaptiveCardGrid(1136.dp, 359.dp, 440.dp, 10.dp, 20.dp)

        assertEquals(2, grid.columns)
        assertEquals(440.dp, grid.cardWidth)
        assertEquals(890.dp, grid.width)
    }

    @Test
    fun `wide collections gain columns without scaling posters`() {
        assertEquals(1000.dp, collectionPanelWidth(1137.dp, false))
        assertEquals(8, collectionPosterColumns(1000.dp, false))
        assertEquals(5, collectionPosterColumns(collectionPanelWidth(720.dp, false), false))
        assertEquals(375.dp, collectionPanelWidth(412.dp, true))
        assertEquals(3, collectionPosterColumns(375.dp, true))
        assertEquals(2, collectionPosterColumns(320.dp, true))
    }

    @Test
    fun `uses all available space for a narrow mobile card without cropping it`() {
        assertEquals(304.dp, calculateSingleCardWidth(320.dp, 8.dp, 440.dp))
        assertEquals(440.dp, calculateSingleCardWidth(600.dp, 8.dp, 440.dp))
    }
}
