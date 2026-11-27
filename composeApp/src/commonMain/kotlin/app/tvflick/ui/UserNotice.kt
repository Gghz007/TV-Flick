package app.tvflick.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** Only curated user-facing messages belong here, never exception messages or server responses. */
enum class UserProblem(val message: String) {
    NewReleases("New releases in your Watch List"),
    UpdateAvailable("Доступна новая версия TV Flick · Открыть"),
    LinkUnavailable("Не удалось открыть ссылку"),
    SourceUnavailable("Источник недоступен"),
    SourceUnsupported("Формат источника пока не поддерживается"),
    VideoNotFound("Видео не найдено"),
    PlaybackFailed("Не удалось воспроизвести видео"),
    TrailerUnavailable("Трейлер недоступен"),
    PictureInPictureUnavailable("Мини-окно недоступно на этом устройстве"),
    PlaybackSelectionChanged("Данные изменились. Выберите видео ещё раз"),
    DownloadFailed("Не удалось загрузить видео"),
    DownloadUnsupported("Загрузка этого формата пока недоступна"),
    LibraryUnavailable("Не удалось загрузить библиотеку"),
    LibraryUpdateFailed("Не удалось сохранить изменения"),
    MetadataUnavailable("Не удалось обновить данные фильма или сериала"),
    CatalogUnavailable("Не удалось загрузить рекомендации"),
    SearchUnavailable("Не удалось выполнить поиск"),
    SourceSettingsFailed("Не удалось сохранить настройки источника"),
    InvalidSourceAddress("Проверьте адрес источника"),
    DuplicateSource("Этот источник уже добавлен"),
    BackupFailed("Не удалось выполнить операцию с резервной копией"),
    GoogleSetupRequired("Вход в Google пока недоступен"),
    GoogleSignInRequired("Войдите в Google снова"),
    TimedOut("Нет ответа. Попробуйте ещё раз"),
}

data class UserNotice(val id: Long, val problem: UserProblem, val releaseUrl: String? = null,
                      val releaseCount: Int? = null)

@Stable
class UserNotices {
    private var nextId = 0L
    var current by mutableStateOf<UserNotice?>(null)
        private set

    fun show(problem: UserProblem) {
        // Concurrent background requests must not keep an identical banner alive indefinitely.
        if (current?.problem == problem) return
        current = UserNotice(++nextId, problem)
    }

    fun dismiss(id: Long) {
        if (current?.id == id) current = null
    }

    internal fun showUpdate(url: String) {
        if (current == null) current = UserNotice(++nextId, UserProblem.UpdateAvailable, url)
    }

    internal fun showReleases(count: Int): Boolean {
        if (current != null) return false
        current = UserNotice(++nextId, UserProblem.NewReleases, releaseCount = count)
        return true
    }
}

@Composable
fun UserNoticeHost(notices: UserNotices, hasDesktopTitleBar: Boolean, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val current = notices.current
    var displayed by remember { mutableStateOf<UserNotice?>(null) }
    LaunchedEffect(current) {
        current ?: return@LaunchedEffect
        displayed = current
        delay(10_000)
        notices.dismiss(current.id)
    }
    Box(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .padding(top = if (hasDesktopTitleBar) 48.dp else 12.dp, start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(visible = current != null,
            enter = slideInVertically(tween(220)) { -it * 2 } + fadeIn(tween(180)),
            exit = slideOutVertically(tween(220)) { -it * 2 } + fadeOut(tween(180))) {
            displayed?.let { notice ->
                var drag by remember(notice.id) { mutableStateOf(Offset.Zero) }
                var dragging by remember(notice.id) { mutableStateOf(false) }
                val offset by animateOffsetAsState(drag, tween(if (dragging) 0 else 150))
                val threshold = with(LocalDensity.current) { 40.dp.toPx() }
                Surface(
                    modifier = Modifier.widthIn(max = 560.dp)
                        .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                        .pointerInput(notice.id, threshold) {
                            detectDragGestures(
                                onDragStart = { dragging = true },
                                onDragCancel = { dragging = false; drag = Offset.Zero },
                                onDragEnd = {
                                    dragging = false
                                    if (abs(drag.x) >= threshold || drag.y <= -threshold) notices.dismiss(notice.id)
                                    else drag = Offset.Zero
                                },
                            ) { change, amount -> change.consume(); drag += amount }
                        }
                        .then(if (notice.releaseUrl != null) Modifier.clickable {
                            notices.dismiss(notice.id)
                            runCatching { uriHandler.openUri(notice.releaseUrl) }
                                .onFailure { notices.show(UserProblem.LinkUnavailable) }
                        } else Modifier)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    shape = RoundedCornerShape(14.dp),
                    color = FlickDesign.neutral(Color(0xFF252525)),
                    border = BorderStroke(1.dp, FlickDesign.neutral(Color(0x26FFFFFF))),
                    shadowElevation = 8.dp,
                ) {
                    val message = appText(notice.problem.message)
                    Text(notice.releaseCount?.let { "$message · $it" } ?: message, Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge, fontSize = 14.sp, color = FlickDesign.foreground,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
