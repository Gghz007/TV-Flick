package app.tvflick.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import tv_flick.composeapp.generated.resources.*
import app.tvflick.core.library.AppLanguage

private val LocalLightTheme = staticCompositionLocalOf { false }
internal const val THEME_SELECTION_AVAILABLE = false
internal val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.English }
internal val LocalTouchFeedback = staticCompositionLocalOf { false }
object FlickDesign {
    val isLight @Composable get() = LocalLightTheme.current
    val background @Composable get() = neutral(Color(0xFF101010))
    val panel @Composable get() = neutral(Color(0xFF141414))
    val foreground @Composable get() = neutral(Color.White)
    val settingsRow @Composable get() = neutral(Color(0xFF1D1D1D))
    val yellow = Color(0xFFFFE626)
    val accent = Color(0xFFFF0238)
    val muted @Composable get() = neutral(Color(0x8CFFFFFF))
    val androidContentInset = 8.dp
    val cardHeight = 118.dp
    val cardWidth = 359.dp
    val desktopPanelWidth = 516.dp

    @Composable
    fun neutral(dark: Color): Color = if (isLight && dark != Color.Unspecified &&
        dark.red == dark.green && dark.green == dark.blue) {
        dark.copy(red = 1f - dark.red, green = 1f - dark.green, blue = 1f - dark.blue)
    } else dark
}

@Composable
fun FlickTheme(
    light: Boolean = false,
    language: AppLanguage = AppLanguage.English,
    touchFeedback: Boolean = false,
    content: @Composable () -> Unit,
) {
    val inter = FontFamily(
        Font(Res.font.inter_extralight, FontWeight.ExtraLight),
        Font(Res.font.inter_light, FontWeight.Light),
        Font(Res.font.inter_regular, FontWeight.Normal),
        Font(Res.font.inter_medium, FontWeight.Medium),
        Font(Res.font.inter_semibold, FontWeight.SemiBold),
        Font(Res.font.inter_bold, FontWeight.Bold),
        Font(Res.font.inter_extrabold, FontWeight.ExtraBold),
        Font(Res.font.inter_black, FontWeight.Black),
    )
    // No Material line height: each reference component owns its text geometry.
    val ink = if (light) Color.Black else Color.White
    val colors = if (light) lightColorScheme(primary = FlickDesign.accent, onBackground = ink, onSurface = ink,
        background = Color(0xFFEFEFEF), surface = Color(0xFFEBEBEB)) else
        darkColorScheme(primary = FlickDesign.accent, onBackground = ink, onSurface = ink,
            background = Color(0xFF101010), surface = Color(0xFF141414))
    CompositionLocalProvider(LocalLightTheme provides light, LocalAppLanguage provides language,
        LocalTouchFeedback provides touchFeedback) {
        MaterialTheme(colorScheme = colors, typography = Typography(bodyLarge = TextStyle(
            fontFamily = inter, fontSize = 13.sp, color = ink, letterSpacing = (-.3).sp,
        )), content = content)
    }
}


