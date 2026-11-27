package app.tvflick.plugin.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
internal actual fun playerAssetPainter(asset: DrawableResource): Painter = painterResource(asset)
