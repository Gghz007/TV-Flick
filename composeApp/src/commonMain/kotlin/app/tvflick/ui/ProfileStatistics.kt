package app.tvflick.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import tv_flick.composeapp.generated.resources.*
import androidx.compose.ui.unit.dp

@Composable
internal fun ProfileStatistics(state: FlickState, mobile: Boolean, modifier: Modifier = Modifier) {
    val numberStyle = TextStyle(fontFamily = FontFamily(Font(Res.font.inter_bold, FontWeight.Bold)),
        fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 17.25.sp,
        letterSpacing = (-.3).sp, color = FlickDesign.foreground, textAlign = TextAlign.Center)
    val captionStyle = TextStyle(fontFamily = FontFamily(Font(Res.font.inter_extralight, FontWeight.ExtraLight)),
        fontWeight = FontWeight.ExtraLight, fontSize = 10.sp, lineHeight = 11.5.sp,
        letterSpacing = (-.3).sp, color = FlickDesign.muted, textAlign = TextAlign.Center)
    val episodes = state.episodes.values.flatten().filter { it.watchedAtEpochMs != null }
    val movies = state.library.filter { it.movieWatchedAtEpochMs != null }
    val minutes = movies.sumOf { it.content.runtimeMinutes ?: 0 } + episodes.sumOf { it.runtimeMinutes ?: 0 }
    Row(modifier.fillMaxWidth().height(77.dp), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically) {
        listOf("MONTHS" to minutes / 43200, "DAYS" to minutes / 1440 % 30,
            "HOURS" to minutes / 60 % 24, "EPISODES" to episodes.size.toLong(),
            "MOVIES" to movies.size.toLong()).forEach { (label, count) ->
            Column(Modifier.weight(1f, fill = false).width(if (mobile) 57.dp else 63.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(count.toString(), style = numberStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(appText(label), style = captionStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
