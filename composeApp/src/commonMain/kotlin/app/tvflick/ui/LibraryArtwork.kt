package app.tvflick.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import app.tvflick.core.backup.libraryImageModel
import coil3.compose.AsyncImage

@Composable
internal fun LibraryArtwork(url: String, description: String?, modifier: Modifier) {
    var loaded by remember(url) { mutableStateOf(false) }
    val opacity by animateFloatAsState(if (loaded) 1f else 0f, tween(170), label = "Artwork reveal")
    AsyncImage(
        model = libraryImageModel(url),
        contentDescription = description,
        modifier = modifier.graphicsLayer { alpha = opacity },
        contentScale = ContentScale.Crop,
        onSuccess = { loaded = true },
        onError = { loaded = false },
    )
}
