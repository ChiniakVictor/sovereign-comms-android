package com.victor.traillink.call

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victor.traillink.ui.TrailTheme
import kotlinx.coroutines.delay

/** Full-screen call UI: shows over the lock screen and turns the screen on for an incoming call. */
class CallActivity : ComponentActivity() {
    companion object { const val ACTION_ANSWER = "com.victor.traillink.call.ANSWER" }

    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) CallManager.accept(this)
        else { Toast.makeText(this, "Microphone permission is needed to talk", Toast.LENGTH_LONG).show(); CallManager.decline() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallManager.init(this)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handle(intent)
        setContent {
            TrailTheme {
                val u by CallManager.ui.collectAsState()
                LaunchedEffect(u.phase) { if (u.phase == CallUi.Phase.IDLE) finish() }
                CallScreen(u, onAnswer = { answer() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
    override fun onResume() { super.onResume(); AppVisible.resumed++ }
    override fun onPause() { AppVisible.resumed--; super.onPause() }

    private fun handle(i: Intent?) { if (i?.action == ACTION_ANSWER) answer() }

    private fun answer() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) mic.launch(Manifest.permission.RECORD_AUDIO)
        else CallManager.accept(this)
    }
}

@Composable
private fun CallScreen(u: CallUi, onAnswer: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(u.phase) { while (u.phase == CallUi.Phase.ACTIVE) { now = System.currentTimeMillis(); delay(500) } }
    val status = when (u.phase) {
        CallUi.Phase.OUTGOING -> "Calling…"
        CallUi.Phase.INCOMING -> "Incoming call"
        CallUi.Phase.CONNECTING -> "Connecting…"
        CallUi.Phase.ACTIVE -> when (u.net) {
            CallMedia.Net.RECONNECTING -> "Reconnecting…"
            else -> ((now - u.connectedAt).coerceAtLeast(0) / 1000).let { "%d:%02d".format(it / 60, it % 60) }
        }
        CallUi.Phase.ENDED -> u.detail ?: "Call ended"
        CallUi.Phase.IDLE -> ""
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF0E1114)).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Box(Modifier.size(110.dp).clip(CircleShape).background(Color(0xFF1E4F8F)), contentAlignment = Alignment.Center) {
            Text(u.peerName.take(1).uppercase().ifBlank { "?" }, fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(18.dp))
        Text(u.peerName, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
        Text("Sovereign Comms call · encrypted", fontSize = 14.sp, color = Color(0xFF90A4AE))
        Spacer(Modifier.height(16.dp))
        Text(status, fontSize = if (u.phase == CallUi.Phase.ENDED) 18.sp else 24.sp, textAlign = TextAlign.Center,
            color = if (u.phase == CallUi.Phase.ENDED) Color(0xFFFFB74D) else Color.White)
        if (u.phase == CallUi.Phase.ACTIVE && u.route != null) Text(u.route, fontSize = 13.sp, color = Color(0xFF90A4AE))
        Spacer(Modifier.weight(1f))
        when (u.phase) {
            CallUi.Phase.INCOMING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundButton("Decline", "✕", Color(0xFFD32F2F)) { CallManager.decline() }
                RoundButton("Answer", "📞", Color(0xFF2E7D32)) { onAnswer() }
            }
            CallUi.Phase.OUTGOING, CallUi.Phase.CONNECTING, CallUi.Phase.ACTIVE -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(if (u.muted) "Unmute" else "Mute", if (u.muted) "🔇" else "🎙", if (u.muted) Color(0xFFFFFFFF) else Color(0xFF2B3440),
                        fg = if (u.muted) Color.Black else Color.White) { CallManager.toggleMute() }
                    RoundButton(if (u.speaker) "Speaker on" else "Speaker", "🔊", if (u.speaker) Color(0xFFFFFFFF) else Color(0xFF2B3440),
                        fg = if (u.speaker) Color.Black else Color.White) { CallManager.toggleSpeaker() }
                }
                Spacer(Modifier.height(28.dp))
                RoundButton("Hang up", "✕", Color(0xFFD32F2F)) { CallManager.hangup() }
            }
            CallUi.Phase.ENDED -> RoundButton("Close", "✓", Color(0xFF2B3440)) { CallManager.dismissEnded() }
            else -> {}
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun RoundButton(label: String, icon: String, bg: Color, fg: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(76.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 30.sp, color = fg)
        }
        Text(label, fontSize = 14.sp, color = Color.White, modifier = Modifier.padding(top = 6.dp))
    }
}
