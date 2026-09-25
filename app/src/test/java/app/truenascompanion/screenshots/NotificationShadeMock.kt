package app.truenascompanion.screenshots

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.truenascompanion.R

/**
 * Approximation of how the alert notifications look in an Android 14-16 notification shade (for previews only;
 * the real rendering is done by the system from AlertNotifier's NotificationCompat builders).
 */
@Composable
fun NotificationShadeMock(dark: Boolean) {
    val shade = if (dark) Color(0xFF101418) else Color(0xFFE9EDF4)
    val card = if (dark) Color(0xFF232A33) else Color(0xFFFFFFFF)
    val onCard = if (dark) Color(0xFFE6E9EF) else Color(0xFF1B1F26)
    val sub = if (dark) Color(0xFFAAB2BF) else Color(0xFF5A6270)
    val brand = Color(0xFF2F5BEA)
    val action = if (dark) Color(0xFF9DB4FF) else brand

    Column(Modifier.fillMaxWidth().background(shade).padding(horizontal = 12.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("12:41", color = onCard, fontSize = 40.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 8.dp))
        Text("Fri, Sep 25", color = sub, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, bottom = 16.dp))

        @Composable
        fun Notif(top: Boolean, bottom: Boolean, meta: String, title: String, text: String, actions: List<String>, maxText: Int = 3) {
            val shape = RoundedCornerShape(
                topStart = if (top) 24.dp else 6.dp, topEnd = if (top) 24.dp else 6.dp,
                bottomStart = if (bottom) 24.dp else 6.dp, bottomEnd = if (bottom) 24.dp else 6.dp,
            )
            Column(Modifier.fillMaxWidth().clip(shape).background(card).padding(16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(brand), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_stat_notify), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color.White))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(meta, color = sub, fontSize = 12.sp, maxLines = 1)
                        Text(title, color = onCard, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(text, color = sub, fontSize = 14.sp, maxLines = maxText, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                if (actions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        actions.forEach { Text(it, color = action, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                    }
                }
            }
        }

        Notif(true, false, "TrueNAS Companion · homenas · Critical · now", "Pool Status Is Not Healthy",
            "Pool tank state is DEGRADED: One or more devices are faulted in response to persistent errors.", listOf("Dismiss", "Open"))
        Notif(false, false, "TrueNAS Companion · homenas · Warning · 2m", "Pool Space Usage Is Above 80%",
            "Space usage for pool \"fast\" is 88%. Optimal pool performance requires used space remain below 80%.", listOf("Dismiss", "Open"), maxText = 2)
        Notif(false, true, "TrueNAS Companion · homenas · 5m", "Sign in to keep receiving alerts",
            "Your session with homenas expired. Open the app and sign in to keep receiving alerts.", listOf("Sign in"), maxText = 2)
        Spacer(Modifier.height(16.dp))
        Text("Silent", color = sub, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
        Notif(true, true, "TrueNAS Companion", "Instant alerts on", "homenas · connected", listOf("Turn off"))
        Spacer(Modifier.height(8.dp))
        Text("Preview mock: the system draws the real notifications.", color = sub.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
    }
}
