package app.tvflick.plugin.video

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.compose.rememberAsyncImagePainter
import coil3.svg.SvgDecoder
import org.jetbrains.compose.resources.*

@OptIn(ExperimentalResourceApi::class)
@Composable
internal actual fun playerAssetPainter(asset: DrawableResource): Painter {
    val environment = rememberResourceEnvironment()
    val bytes by produceState<ByteArray?>(null, asset, environment) {
        value = getDrawableResourceBytes(environment, asset)
    }
    return rememberAsyncImagePainter(bytes, imageLoader = PlayerSvgLoader.get(LocalContext.current))
}

@OptIn(coil3.annotation.ExperimentalCoilApi::class)
private object PlayerSvgLoader {
    private var loader: ImageLoader? = null
    fun get(context: Context): ImageLoader = loader ?: ImageLoader.Builder(context.applicationContext)
        .components { add(SvgDecoder.Factory(density = SvgDecoder.PLATFORM_DENSITY)) }
        .build().also { loader = it }
}
