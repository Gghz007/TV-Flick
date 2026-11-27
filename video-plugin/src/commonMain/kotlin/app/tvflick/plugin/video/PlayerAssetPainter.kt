package app.tvflick.plugin.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.DrawableResource

@Composable
internal expect fun playerAssetPainter(asset: DrawableResource): Painter
