package com.victor.traillink.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victor.traillink.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Update check against kodiakking.com: one check when the app opens, then only when the button is tapped. No background work. */
object UpdateCheck {
    const val HOME_URL = "https://www.kodiakking.com/"
    private val RE = Regex("""href="(https?://[^"]*?/sovereign-comms-(\d+(?:[.-]\d+)*)[^"]*)"""", RegexOption.IGNORE_CASE)
    fun latestFrom(html: String): Pair<String, String>? = RE.findAll(html)
        .map { it.groupValues[2].replace('-', '.') to it.groupValues[1].replace("&amp;", "&") }
        .maxWithOrNull { a, b -> compare(a.first, b.first) }
    fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d }
        return 0
    }
    fun fetch(): Pair<String, String>? = runCatching {
        val c = java.net.URL(HOME_URL).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000; c.setRequestProperty("User-Agent", "SovereignComms/${BuildConfig.VERSION_NAME}")
        c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }.getOrNull()?.let { latestFrom(it) }
}

private var checkedThisLaunch = false

@Composable
fun UpdateButton(modifier: Modifier) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(true) }
    var label by remember { mutableStateOf("Check for update") }
    var dialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun check(silent: Boolean) {
        label = "Checking…"
        scope.launch {
            val l = withContext(Dispatchers.IO) { UpdateCheck.fetch() }
            val cur = BuildConfig.VERSION_NAME
            when {
                l == null -> { label = "Check for update"; if (!silent) android.widget.Toast.makeText(ctx, "Couldn't check for updates. Try again later.", android.widget.Toast.LENGTH_SHORT).show() }
                UpdateCheck.compare(l.first, cur) > 0 -> { label = "Update available ⬇"; dialog = l }
                else -> label = "Up to date ✓"
            }
        }
    }
    LaunchedEffect(Unit) { if (!checkedThisLaunch) { checkedThisLaunch = true; check(true) } }
    if (shown) Row(modifier.tourTarget("update").clip(RoundedCornerShape(14.dp)).background(Color(0xCC161A1F)), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(enabled = label != "Checking…") { check(false) }.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp))
        Text("✕", fontSize = 12.sp, color = Color(0xFFB0BEC5), modifier = Modifier.clickable { shown = false }.padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp))
    }
    val onboarding by Onboarding.busy.collectAsState()
    if (!onboarding) dialog?.let { (v, url) ->
        AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Update available") },
            text = { Text("Sovereign Comms $v is available.\nYou have ${BuildConfig.VERSION_NAME}.") },
            confirmButton = { TextButton({ dialog = null; runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) { Text("Download") } },
            dismissButton = { TextButton({ dialog = null }) { Text("Later") } })
    }
}

/** Settings row: manual update check (works even after the map button was closed). */
@Composable
fun UpdateCheckRow() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true; msg = "Checking…"
            scope.launch {
                val l = withContext(Dispatchers.IO) { UpdateCheck.fetch() }
                busy = false
                msg = when {
                    l == null -> "Couldn't check. Try again later."
                    UpdateCheck.compare(l.first, BuildConfig.VERSION_NAME) > 0 -> { dialog = l; "Update available: ${l.first}" }
                    else -> "Up to date ✓"
                }
            }
        }) { Text("Check for update") }
        msg?.let { Text("  $it", fontSize = 13.sp) }
    }
    dialog?.let { (v, url) ->
        AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Update available") },
            text = { Text("Version $v is available.\nYou have ${BuildConfig.VERSION_NAME}.") },
            confirmButton = { TextButton({ dialog = null; runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) { Text("Download") } },
            dismissButton = { TextButton({ dialog = null }) { Text("Later") } })
    }
}
