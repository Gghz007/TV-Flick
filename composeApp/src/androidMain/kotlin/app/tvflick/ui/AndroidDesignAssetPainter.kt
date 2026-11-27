package app.tvflick.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import coil3.ImageLoader
import coil3.compose.rememberAsyncImagePainter
import coil3.svg.SvgDecoder
import org.jetbrains.compose.resources.*

@OptIn(ExperimentalResourceApi::class)
@Composable
actual fun designAssetPainter(asset: DrawableResource): Painter {
    val environment = rememberResourceEnvironment()
    val bytes by produceState<ByteArray?>(null, asset, environment) {
        value = getDrawableResourceBytes(environment, asset)
    }
    return rememberAsyncImagePainter(bytes, imageLoader = SvgAssetLoader.get(LocalContext.current))
}

@OptIn(coil3.annotation.ExperimentalCoilApi::class)
private object SvgAssetLoader {
    private var loader: ImageLoader? = null
    fun get(context: Context): ImageLoader = loader ?: ImageLoader.Builder(context.applicationContext)
        .components { add(SvgDecoder.Factory(density = SvgDecoder.PLATFORM_DENSITY)) }
        .build().also { loader = it }
}
