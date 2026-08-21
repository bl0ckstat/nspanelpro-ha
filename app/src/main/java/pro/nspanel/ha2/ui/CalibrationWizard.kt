package pro.nspanel.ha2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import pro.nspanel.ha2.data.AppSettings
import pro.nspanel.ha2.device.PanelGeneration
import pro.nspanel.ha2.screen.ScreenStats
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Guided calibration for the things no fixed number can get right: where the
 * proximity trigger sits on THIS panel's sensor, and what "bright room" and
 * "dark room" mean in THIS room's lux.
 *
 * Rules that shape it, each learned on hardware:
 *  - every measuring step is entered by an explicit button press — a wizard
 *    that advances itself measures a distracted person's absence;
 *  - measuring starts on a visible countdown, because the finger that pressed
 *    the button belongs to a body standing at the panel;
 *  - the palette is dark (Brick & Brass, like the dashboards) because the
 *    screen's own glow feeds the lux sensor it is calibrating — and during
 *    the dark-room sample the screen goes nearly black for the same reason;
 *  - the sensors read out live on every screen, so a hand waved at the panel
 *    visibly moves the number it is about to calibrate.
 */
private enum class Step {
    INTRO,
    PROX_CLEAR,
    PROX_NEAR_READY,
    PROX_NEAR,
    PROX_RESULT,
    LUX_BRIGHT_READY,
    LUX_BRIGHT,
    LUX_DARK_READY,
    LUX_DARK,
    DIMMING,
    DONE,
}

// Brick & Brass, as deployed on the dashboards.
private val Bg = Color(0xFF171210)
private val Card = Color(0xFF221A15)
private val Raised = Color(0xFF2A211A)
private val Border = Color(0xFF4C3C2D)
private val Cream = Color(0xFFF5EDE0)
private val Muted = Color(0xFFB5A58D)
private val Dim = Color(0xFF8C7A63)
private val Action = Color(0xFFB75A33)
private val Brass = Color(0xFFC89A4B)

// Distance-readable type: the instructions are followed from across the room.
private val TITLE = 30.sp
private val BODY = 21.sp
private val DETAIL = 17.sp

@Composable
fun CalibrationWizard(
    statsFlow: StateFlow<ScreenStats>,
    settings: AppSettings,
    onApply: (AppSettings) -> Unit,
    onClose: () -> Unit,
) {
    var step by remember { mutableStateOf(Step.INTRO) }
    var draft by remember { mutableStateOf(settings) }

    var restPeak by remember { mutableStateOf(Float.NaN) }
    var nearTypical by remember { mutableStateOf(Float.NaN) }
    var proxThreshold by remember { mutableStateOf(Float.NaN) }
    var proxFailed by remember { mutableStateOf(false) }
    var luxBright by remember { mutableStateOf(Float.NaN) }
    var luxDark by remember { mutableStateOf(Float.NaN) }
    var progress by remember { mutableStateOf(0f) }
    var countdown by remember { mutableStateOf(0) }

    // Live readings, recomposing as the sensors report. Watching the number
    // jump when a hand approaches is what makes the calibration believable —
    // and it shows a dead sensor as a frozen figure instead of a mystery.
    val stats by statsFlow.collectAsStateWithLifecycle()
    val radar = stats.generation == PanelGeneration.GEN2

    suspend fun sample(grace: Int, seconds: Int, pick: (ScreenStats) -> Float): List<Float> {
        for (t in grace downTo 1) {
            countdown = t
            delay(1000)
        }
        countdown = 0
        val out = mutableListOf<Float>()
        val ticks = seconds * 4
        repeat(ticks) { i ->
            out += pick(statsFlow.value)
            progress = (i + 1f) / ticks
            delay(250)
        }
        return out.filter { !it.isNaN() && it >= 0f }
    }

    fun median(values: List<Float>): Float =
        values.sorted().let { if (it.isEmpty()) Float.NaN else it[it.size / 2] }

    LaunchedEffect(step) {
        progress = 0f
        when (step) {
            Step.PROX_CLEAR -> {
                restPeak = sample(8, 10) { it.proximityRaw }.maxOrNull() ?: Float.NaN
                step = Step.PROX_NEAR_READY
            }
            Step.PROX_NEAR -> {
                // The typical reading at wake distance, not the peak: the peak
                // is whatever moment the hand drifted closest, and a trigger
                // set off that wakes only on touch.
                nearTypical = median(sample(5, 8) { it.proximityRaw })
                proxFailed = when {
                    radar -> false
                    nearTypical.isNaN() || restPeak.isNaN() -> true
                    nearTypical < restPeak * 1.5f + 10f -> true
                    else -> false
                }
                if (!proxFailed && !radar) {
                    // Geometric midpoint: reflectance is multiplicative, so
                    // halfway in log-space splits the two states evenly.
                    proxThreshold = sqrt(restPeak * nearTypical)
                    draft = draft.copy(
                        manualProximityThreshold = proxThreshold.roundToInt().toFloat(),
                    )
                }
                step = Step.PROX_RESULT
            }
            Step.LUX_BRIGHT -> {
                luxBright = median(sample(3, 6) { it.lux })
                step = Step.LUX_DARK_READY
            }
            Step.LUX_DARK -> {
                luxDark = median(sample(3, 6) { it.lux })
                if (!luxDark.isNaN() && !luxBright.isNaN() && luxBright > luxDark * 1.2f) {
                    draft = draft.copy(
                        manualLuxDark = luxDark.roundToInt().toFloat(),
                        manualLuxBright = luxBright.roundToInt().toFloat(),
                        manualAutoBrightness = true,
                    )
                }
                step = Step.DIMMING
            }
            else -> Unit
        }
    }

    // The dark-room sample runs on a near-black screen: the display is the
    // one light source the user cannot switch off, so the wizard removes
    // itself from its own measurement.
    val blackout = step == Step.LUX_DARK && countdown == 0

    Surface(modifier = Modifier.fillMaxSize(), color = if (blackout) Color.Black else Bg) {
        if (blackout) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("measuring dark…", fontSize = DETAIL, color = Color(0xFF4A4239))
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(0.5f),
                    color = Color(0xFF4A4239),
                    trackColor = Color(0xFF201B17),
                )
            }
            return@Surface
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Calibration", fontSize = TITLE, color = Cream)
                Text(stepBadge(step), fontSize = DETAIL, color = Dim)
            }

            LiveReadings(
                stats = stats,
                emphasis = when (step) {
                    Step.PROX_CLEAR, Step.PROX_NEAR_READY, Step.PROX_NEAR,
                    Step.PROX_RESULT -> Emphasis.PRESENCE
                    Step.LUX_BRIGHT_READY, Step.LUX_BRIGHT,
                    Step.LUX_DARK_READY, Step.LUX_DARK -> Emphasis.LIGHT
                    else -> Emphasis.NONE
                },
            )

            when (step) {
                Step.INTRO -> {
                    Body(
                        "This tunes the wake sensor and auto-brightness for " +
                            "this room. You'll step away, stand at wake " +
                            "distance, and change the lighting. Each step " +
                            "waits for a button press first."
                    )
                    BigButton("Start — then step away") { step = Step.PROX_CLEAR }
                }

                Step.PROX_CLEAR -> Sampling(
                    "Step well away from the panel",
                    "Measuring the empty room. Keep clear until the bar fills.",
                    countdown, progress,
                )

                Step.PROX_NEAR_READY -> {
                    Body("Empty room measured.")
                    Body(
                        "Next: stand where the panel should wake you — arm's " +
                            "length is typical. Watch the presence number move " +
                            "as you approach."
                    )
                    BigButton("I'm ready — measure me") { step = Step.PROX_NEAR }
                }

                Step.PROX_NEAR -> Sampling(
                    "Stand at your wake distance",
                    "Hold that position until the bar fills.",
                    countdown, progress,
                )

                Step.PROX_RESULT -> {
                    if (radar) {
                        Body(
                            "This panel has a presence radar: it reports " +
                                "someone-there or empty on its own. Nothing to " +
                                "tune here."
                        )
                    } else if (proxFailed) {
                        Body(
                            "Couldn't tell you apart from the empty room " +
                                "(empty peaked at ${restPeak.roundToInt()}, you read " +
                                "${if (nearTypical.isNaN()) "nothing" else nearTypical.roundToInt().toString()}). " +
                                "Try again, standing closer.",
                            color = Brass,
                        )
                        BigButton("Retry — step away again") { step = Step.PROX_CLEAR }
                    } else {
                        ResultCard(
                            "Empty room" to "${restPeak.roundToInt()}",
                            "You, at wake distance" to "${nearTypical.roundToInt()}",
                            "Trigger set to" to "${proxThreshold.roundToInt()}",
                        )
                    }
                    if (!proxFailed) {
                        BigButton("Next: room brightness") { step = Step.LUX_BRIGHT_READY }
                    }
                }

                Step.LUX_BRIGHT_READY -> {
                    Body(
                        "Set the room to its normal daytime brightness — " +
                            "blinds and lights as usual. Press when it's set."
                    )
                    BigButton("Room is bright — measure") { step = Step.LUX_BRIGHT }
                }

                Step.LUX_BRIGHT -> Sampling(
                    "Measuring bright level",
                    "Hold the lighting steady.",
                    countdown, progress,
                )

                Step.LUX_DARK_READY -> {
                    Body(
                        "Now make the room as dark as it gets at night — " +
                            "lights off, blinds closed. The screen will go " +
                            "almost black while it measures, so its own glow " +
                            "doesn't pollute the reading."
                    )
                    BigButton("Room is dark — measure") { step = Step.LUX_DARK }
                }

                Step.LUX_DARK -> Sampling(
                    "Measuring dark level",
                    "Going dark…",
                    countdown, progress,
                )

                Step.DIMMING -> {
                    if (luxBright.isNaN() || luxDark.isNaN() || luxBright <= luxDark * 1.2f) {
                        Body(
                            "Bright and dark read too close together " +
                                "(${if (luxBright.isNaN()) "?" else luxBright.roundToInt().toString()} vs " +
                                "${if (luxDark.isNaN()) "?" else luxDark.roundToInt().toString()} lux) — " +
                                "brightness anchors left unchanged.",
                            color = Brass,
                        )
                        OutlinedButton(onClick = { step = Step.LUX_BRIGHT_READY }) {
                            Text("Retry lighting", fontSize = DETAIL, color = Muted)
                        }
                    } else {
                        ResultCard(
                            "Bright room" to "${luxBright.roundToInt()} lx",
                            "Dark room" to "${luxDark.roundToInt()} lx",
                        )
                    }
                    Body("Idle timeout: ${draft.manualTimeoutSeconds}s")
                    BrassSlider(
                        value = draft.manualTimeoutSeconds.toFloat(),
                        onChange = { draft = draft.copy(manualTimeoutSeconds = it.roundToInt()) },
                        range = 15f..600f,
                    )
                    Body("Dim to ${draft.manualIdleDimPercent}% before switching off")
                    BrassSlider(
                        value = draft.manualIdleDimPercent.toFloat(),
                        onChange = { draft = draft.copy(manualIdleDimPercent = it.roundToInt()) },
                        range = 0f..100f,
                    )
                    BigButton("Finish") { step = Step.DONE }
                }

                Step.DONE -> {
                    val entries = mutableListOf<Pair<String, String>>()
                    if (!radar && !proxThreshold.isNaN()) {
                        entries += "Wake trigger" to "${proxThreshold.roundToInt()}"
                    }
                    if (!luxDark.isNaN() && !luxBright.isNaN() && luxBright > luxDark * 1.2f) {
                        entries += "Brightness anchors" to
                            "${luxDark.roundToInt()}–${luxBright.roundToInt()} lx"
                    }
                    entries += "Idle" to
                        "dim to ${draft.manualIdleDimPercent}% after ${draft.manualTimeoutSeconds}s"
                    ResultCard(*entries.toTypedArray())
                    Body(
                        "All of this is editable in the settings sheet, and the " +
                            "wizard can be re-run any time.",
                        size = DETAIL,
                        color = Dim,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BigButton("Apply", modifier = Modifier.fillMaxWidth(0.6f)) {
                            onApply(draft); onClose()
                        }
                        OutlinedButton(onClick = onClose) {
                            Text("Discard", fontSize = DETAIL, color = Muted)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))
            if (step != Step.DONE) {
                OutlinedButton(onClick = onClose) { Text("Cancel", fontSize = DETAIL, color = Muted) }
            }
        }
    }
}

private fun stepBadge(step: Step): String = when (step) {
    Step.INTRO -> ""
    Step.PROX_CLEAR, Step.PROX_NEAR_READY, Step.PROX_NEAR, Step.PROX_RESULT -> "1 / 3 · presence"
    Step.LUX_BRIGHT_READY, Step.LUX_BRIGHT, Step.LUX_DARK_READY, Step.LUX_DARK -> "2 / 3 · light"
    Step.DIMMING -> "3 / 3 · dimming"
    Step.DONE -> "done"
}

private enum class Emphasis { PRESENCE, LIGHT, NONE }

/** The sensors, live, in a bordered strip. The metric being measured runs big and brass. */
@Composable
private fun LiveReadings(stats: ScreenStats, emphasis: Emphasis) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Reading(
            "presence",
            if (stats.proximityRaw < 0f) "—" else stats.proximityRaw.roundToInt().toString(),
            emphasis == Emphasis.PRESENCE,
        )
        Reading("light", "${stats.lux.roundToInt()} lx", emphasis == Emphasis.LIGHT)
    }
}

@Composable
private fun Reading(label: String, value: String, big: Boolean) {
    Column {
        Text(label, fontSize = 13.sp, color = Dim)
        Text(
            value,
            fontSize = if (big) 36.sp else 19.sp,
            color = if (big) Brass else Muted,
        )
    }
}

@Composable
private fun Body(text: String, size: androidx.compose.ui.unit.TextUnit = BODY, color: Color = Cream) {
    Text(text, fontSize = size, color = color)
}

/** Label/value rows in a card, the way the dashboards present readouts. */
@Composable
private fun ResultCard(vararg rows: Pair<String, String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { (label, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, fontSize = DETAIL, color = Muted)
                Text(value, fontSize = DETAIL, color = Cream)
            }
        }
    }
}

@Composable
private fun BigButton(label: String, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Action, contentColor = Cream),
    ) {
        Text(label, fontSize = BODY)
    }
}

@Composable
private fun BrassSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>) {
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        colors = SliderDefaults.colors(
            thumbColor = Cream,
            activeTrackColor = Action,
            inactiveTrackColor = Raised,
        ),
    )
}

@Composable
private fun Sampling(title: String, body: String, countdown: Int, progress: Float) {
    Body(title)
    Body(body, size = DETAIL, color = Muted)
    if (countdown > 0) {
        // The countdown is the biggest thing on screen: it is read mid-stride.
        Text("$countdown", fontSize = 72.sp, color = Cream)
        Body("Get into position…", size = DETAIL, color = Dim)
    } else {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = Brass,
            trackColor = Raised,
        )
    }
}
