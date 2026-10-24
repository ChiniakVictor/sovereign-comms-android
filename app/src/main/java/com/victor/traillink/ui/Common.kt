package com.victor.traillink.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val TLColors = darkColorScheme(
    primary = Color(0xFFFFB74D), onPrimary = Color(0xFF231300),
    secondary = Color(0xFF80CBC4), background = Color(0xFF0E1114), surface = Color(0xFF161A1F),
    surfaceVariant = Color(0xFF232931), onSurface = Color(0xFFECEFF1), onSurfaceVariant = Color(0xFFB0BEC5),
    error = Color(0xFFFF5252),
)

@Composable
fun TrailTheme(content: @Composable () -> Unit) {
    val t = Typography()
    MaterialTheme(colorScheme = TLColors, typography = t.copy(
        bodyLarge = t.bodyLarge.copy(fontSize = 19.sp), bodyMedium = t.bodyMedium.copy(fontSize = 17.sp),
        labelLarge = t.labelLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontSize = 25.sp, fontWeight = FontWeight.Bold),
    ), content = content)
}

/** Red/amber bar when this phone can't know its own position. Shows nothing when all is well. */
@Composable
fun LocationBanner(vm: AppViewModel, compact: Boolean = false) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st by vm.locStatus.collectAsState()
    val (text, button, action) = when (st) {
        LocStatus.NO_PERMISSION -> Triple("Location permission is off for Sovereign Comms — partners can't see you.", "Allow") {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName)))
        }
        LocStatus.SYSTEM_OFF -> Triple("Phone Location is OFF — your position isn't shown or sent.", "Turn on") {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        LocStatus.WAITING -> Triple("Waiting for a GPS fix… (go near a window or outside)", null) {}
        LocStatus.OK -> return
    }
    Row(Modifier.fillMaxWidth().background(if (st == LocStatus.WAITING) Color(0xE6795548) else Color(0xF2B71C1C))
        .let { m -> if (button != null) m.clickable { action() } else m }.padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (compact) 13.sp else 16.sp)
        if (button != null) {
            Spacer(Modifier.width(8.dp))
            Button({ action() }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB71C1C)),
                contentPadding = if (compact) PaddingValues(horizontal = 10.dp, vertical = 2.dp) else ButtonDefaults.ContentPadding,
                modifier = if (compact) Modifier.height(32.dp) else Modifier) {
                Text(button, fontWeight = FontWeight.Bold, fontSize = if (compact) 13.sp else 16.sp)
            }
        }
    }
}

@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = true, onClick: () -> Unit) {
    if (primary) Button(onClick, modifier.heightIn(min = 60.dp), enabled = enabled, shape = RoundedCornerShape(14.dp)) { Text(text, fontSize = 19.sp) }
    else OutlinedButton(onClick, modifier.heightIn(min = 60.dp), enabled = enabled, shape = RoundedCornerShape(14.dp)) { Text(text, fontSize = 19.sp) }
}

@Composable
fun ColorDot(color: Int, size: Dp = 22.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color(color)))
}

/** Arrow pointing toward the target, relative to where the top of the phone points (or north if no compass). */
@Composable
fun BearingArrow(rotationDeg: Float, color: Color, size: Dp) {
    Canvas(Modifier.size(size).rotate(rotationDeg)) {
        val w = this.size.width; val h = this.size.height
        drawCircle(Color(0x33FFFFFF), radius = w / 2, style = Stroke(width = w * 0.04f))
        val p = Path().apply {
            moveTo(w / 2, h * 0.06f); lineTo(w * 0.80f, h * 0.84f); lineTo(w / 2, h * 0.66f); lineTo(w * 0.20f, h * 0.84f); close()
        }
        drawPath(p, color)
        drawCircle(Color.Black, radius = w * 0.05f, center = Offset(w / 2, h / 2))
    }
}

fun ageColor(ageMs: Long, lostMin: Int): Color = when {
    ageMs < 5 * 60_000L -> Color(0xFF69F0AE)
    ageMs < lostMin * 60_000L -> Color(0xFFFFD740)
    else -> Color(0xFFFF5252)
}

fun batteryText(b: Int?) = when { b == null -> "—"; b > 100 -> "USB"; else -> "$b%" }

@Composable
fun SourceBadge(src: String?, small: Boolean = false) {
    if (src == null) return
    val (t, c) = when (src) {
        "net" -> "NET" to Color(0xFF1565C0)
        "gw" -> "NET·RADIO" to Color(0xFF00838F)
        else -> "LoRa" to Color(0xFF6A1B9A)
    }
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(c).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(t, color = Color.White, fontSize = if (small) 10.sp else 13.sp, fontWeight = FontWeight.Bold)
    }
}

fun sourceEmoji(src: String?) = when (src) { "net" -> "🌐"; "gw" -> "🌐📡"; null -> ""; else -> "📡" }
