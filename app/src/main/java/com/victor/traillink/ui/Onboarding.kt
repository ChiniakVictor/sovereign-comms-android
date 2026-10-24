package com.victor.traillink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.victor.traillink.data.PoiPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One step of the guided tour. [target] is a key set with Modifier.tourTarget(); null = centred card. [enter] runs first (e.g. switch tab). */
class TourStep(val target: String?, val title: String, val body: String, val enter: (() -> Unit)? = null)

/** First run: privacy note -> welcome/tour offer -> (tour). [busy] stays true until that is finished, so the launch update dialog waits. */
object Onboarding {
    private val _busy = MutableStateFlow(true)
    val busy: StateFlow<Boolean> get() = _busy
    internal val stage = MutableStateFlow(Stage.INIT)
    internal val targets = mutableStateMapOf<String, Rect>()
    enum class Stage { INIT, PRIVACY, WELCOME, TOUR, PRIVACY_AGAIN, DONE }
    internal fun set(s: Stage) { stage.value = s; _busy.value = s != Stage.DONE }
    /** Settings → Tutorial. */
    fun startTour() = set(Stage.TOUR)
    /** Settings → Privacy. */
    fun openPrivacy() = set(Stage.PRIVACY_AGAIN)
}

fun Modifier.tourTarget(key: String): Modifier = composed {
    DisposableEffect(key) { onDispose { Onboarding.targets.remove(key) } }
    onGloballyPositioned { Onboarding.targets[key] = it.boundsInRoot() }
}

@Composable
fun OnboardingHost(appName: String, privacy: List<String>, steps: List<TourStep>) {
    val ctx = LocalContext.current
    val stage by Onboarding.stage.collectAsState()
    LaunchedEffect(Unit) {
        if (Onboarding.stage.value == Onboarding.Stage.INIT) {
            val p = PoiPrefs.load(ctx)
            Onboarding.set(when { !p.privacyOk -> Onboarding.Stage.PRIVACY; !p.tourNever -> Onboarding.Stage.WELCOME; else -> Onboarding.Stage.DONE })
        }
    }
    when (stage) {
        Onboarding.Stage.PRIVACY, Onboarding.Stage.PRIVACY_AGAIN -> PrivacyDialog(appName, privacy) {
            val first = stage == Onboarding.Stage.PRIVACY
            PoiPrefs.update(ctx) { it.copy(privacyOk = true) }
            Onboarding.set(if (first && !PoiPrefs.state.value.tourNever) Onboarding.Stage.WELCOME else Onboarding.Stage.DONE)
        }
        Onboarding.Stage.WELCOME -> WelcomeDialog(appName,
            onTour = { Onboarding.set(Onboarding.Stage.TOUR) },
            onLater = { Onboarding.set(Onboarding.Stage.DONE) },
            onNever = { PoiPrefs.update(ctx) { it.copy(tourNever = true) }; Onboarding.set(Onboarding.Stage.DONE) })
        Onboarding.Stage.TOUR -> TourOverlay(steps) { Onboarding.set(Onboarding.Stage.DONE) }
        else -> {}
    }
}

@Composable
fun PrivacyDialog(appName: String, lines: List<String>, onOk: () -> Unit) {
    AlertDialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Your privacy") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                lines.forEach { Text(it, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp)) }
            }
        },
        confirmButton = { Button(onOk) { Text("I understand") } })
}

@Composable
fun WelcomeDialog(appName: String, onTour: () -> Unit, onLater: () -> Unit, onNever: () -> Unit) {
    AlertDialog(onDismissRequest = onLater,
        title = { Text("Welcome to $appName") },
        text = { Text("Want a quick tour? It takes about a minute. You can replay it any time from Settings → Tutorial.", fontSize = 15.sp) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Button(onTour) { Text("Take the tour") }
                Row {
                    TextButton(onNever) { Text("Don't show again") }
                    TextButton(onLater) { Text("Not now") }
                }
            }
        })
}

@Composable
fun TourOverlay(steps: List<TourStep>, onDone: () -> Unit) {
    var i by remember { mutableIntStateOf(0) }
    val step = steps.getOrNull(i) ?: run { LaunchedEffect(Unit) { onDone() }; return }
    LaunchedEffect(i) { step.enter?.invoke() }
    val rect = step.target?.let { Onboarding.targets[it] }?.takeIf { it.width > 0f && it.height > 0f }
    val d = LocalDensity.current
    val pad = with(d) { 6.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary
    BoxWithConstraints(Modifier.fillMaxSize()
        .pointerInput(Unit) { detectTapGestures { } }) {   // swallow taps on the map while touring
        val W = constraints.maxWidth.toFloat(); val H = constraints.maxHeight.toFloat()
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(Color(0xB3000000))
            rect?.let { r ->
                val tl = Offset(r.left - pad, r.top - pad); val sz = Size(r.width + 2 * pad, r.height + 2 * pad)
                drawRoundRect(Color.Transparent, tl, sz, CornerRadius(pad * 2), blendMode = BlendMode.Clear)
                drawRoundRect(accent, tl, sz, CornerRadius(pad * 2), style = Stroke(with(d) { 3.dp.toPx() }))
            }
        }
        val cardW = minOf(maxWidth * 0.92f, 420.dp)
        // card below the highlight if the highlight is in the top half, otherwise above it; centred when no target
        var cardH by remember(i) { mutableIntStateOf(0) }
        val gap = with(d) { 14.dp.toPx() }
        val y: Float = when {
            rect == null -> (H - cardH) / 2f
            rect.center.y < H / 2f -> minOf(rect.bottom + pad + gap, H - cardH - gap)
            else -> maxOf(rect.top - pad - gap - cardH, gap)
        }
        val cardWpx = with(d) { cardW.toPx() }
        val x = ((rect?.center?.x ?: (W / 2f)) - cardWpx / 2f).coerceIn(gap.coerceAtMost((W - cardWpx) / 2f), maxOf(W - cardWpx - gap, (W - cardWpx) / 2f))
        Card(Modifier.width(cardW).heightIn(max = maxHeight * 0.6f).offset { IntOffset(x.toInt(), y.toInt()) }
            .onGloballyPositioned { cardH = it.size.height },
            shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("${i + 1} / ${steps.size}", fontSize = 12.sp, color = accent)
                Text(step.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text(step.body, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onDone) { Text("Skip") }
                    Spacer(Modifier.weight(1f))
                    if (i > 0) TextButton({ i-- }) { Text("Back") }
                    Button({ if (i < steps.lastIndex) i++ else onDone() }) { Text(if (i < steps.lastIndex) "Next" else "Done") }
                }
            }
        }
    }
}
