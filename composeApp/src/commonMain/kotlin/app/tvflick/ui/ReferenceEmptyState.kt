package app.tvflick.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Lunacy jzfPckouyUKBic4q5R-Dmw / AhZys4C1PkiYysmtXxYVdw.
 * Keep the text frame fixed; only its centered position follows the window.
 */
@Composable
fun ReferenceEmptyState(mobile: Boolean, text: String = "EMPTY LIST", addLabel: String? = null, onAdd: () -> Unit = {}) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.offset(y = if (addLabel == null) 0.dp else if (mobile) 0.dp else 18.5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = appText(text),
            modifier = Modifier.offset(y = if (addLabel != null) 0.dp else if (mobile) (-9.5).dp else .5.dp)
                .size(if (mobile) 375.dp else 599.dp, if (mobile) 47.dp else 66.dp),
            color = FlickDesign.foreground.copy(alpha = .02f),
            fontSize = if (mobile) 40.sp else 60.sp,
            lineHeight = if (mobile) 47.sp else 66.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-.3).sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (addLabel != null) {
            Spacer(Modifier.height(9.dp))
            Box(Modifier.size(if (mobile) 140.dp else 170.dp, if (mobile) 42.dp else 51.dp)
                .background(FlickDesign.panel, RoundedCornerShape(100.dp))
                .referenceClick(RoundedCornerShape(100.dp), action = onAdd), contentAlignment = Alignment.Center) {
                Label(addLabel, Modifier.padding(horizontal = 8.dp), size = if (mobile) 14 else 17,
                    weight = if (mobile) FontWeight.Medium else FontWeight.Normal, color = FlickDesign.muted, align = TextAlign.Center)
            }
        }
        }
    }
}
