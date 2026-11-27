package app.tvflick.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.DrawableResource

/** Retain the Lunacy vector; Android needs an SVG decoder instead of BitmapFactory. */
@Composable
expect fun designAssetPainter(asset: DrawableResource): Painter
