package pro.nspanel.ha2.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Two rules shape the flow, both learned from watching people use it:
 * every measuring step is entered by an explicit button press — a wizard that
 * silently moves itself along measures a distracted person's absence — and
 * measuring starts on a visible countdown, because the finger that pressed
 * the button belongs to a body that is standing at the panel and needs time
 * to get where the step wants it. The type is sized to be read from across
 * the room, since that is where the instructions send you.
 *
 * Everything it writes lands in the same settings the sheet edits by hand,
 * so results are tunable afterwards and the wizard re-runnable any time.
 */
private enum class Step {
    INTRO,
    PROX_CLEAR,        // countdown + sample the empty room
    PROX_NEAR_READY,   // gate: confirm before the stand-here step
    PROX_NEAR,         // countdown + sample at wake distance
    PROX_RESULT,
    LUX_BRIGHT_READY,  // gate: set the room bright first
    LUX_BRIGHT,
    LUX_DARK_READY,    // gate: now make it dark
    LUX_DARK,
    DIMMING,
    DONE,
}

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

    val radar = statsFlow.value.generation == PanelGeneration.GEN2

    /** A visible get-into-position countdown, then [seconds] of readings. */
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

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Calibration", fontSize = TITLE, style = MaterialTheme.typography.titleLarge)

            when (step) {
                Step.INTRO -> {
                    Text(
                        "This tunes the wake sensor and auto-brightness for " +
                            "this room. You'll step away, stand at wake " +
                            "distance, and change the lighting. Each step " +
                            "waits for you to press a button first.",
                        fontSize = BODY,
                    )
                    BigButton("Start — then step away") { step = Step.PROX_CLEAR }
                }

                Step.PROX_CLEAR -> Sampling(
                    "Step well away from the panel",
                    "Measuring the empty room. Keep clear until the bar fills.",
                    countdown, progress,
                )

                Step.PROX_NEAR_READY -> {
                    Text("Empty room measured.", fontSize = BODY)
                    Text(
                        "Next: stand where the panel should wake you — arm's " +
                            "length is typical. Press when you're ready to " +
                            "take position.",
                        fontSize = BODY,
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
                        Text(
                            "This panel has a presence radar: it reports " +
                                "someone-there or empty on its own. Nothing " +
                                "to tune here.",
                            fontSize = BODY,
                        )
                    } else if (proxFailed) {
                        Text(
                            "Couldn't tell you apart from the empty room " +
                                "(empty peaked at ${restPeak.roundToInt()}, you read " +
                                "${if (nearTypical.isNaN()) "nothing" else nearTypical.roundToInt().toString()}). " +
                                "Try again, standing closer.",
                            fontSize = BODY,
                            color = MaterialTheme.colorScheme.error,
                        )
                        BigButton("Retry — step away again") { step = Step.PROX_CLEAR }
                    } else {
                        Text(
                            "Empty room: ${restPeak.roundToInt()}. You at wake " +
                                "distance: ${nearTypical.roundToInt()}. Trigger set " +
                                "to ${proxThreshold.roundToInt()}.",
                            fontSize = BODY,
                        )
                    }
                    if (!proxFailed) {
                        BigButton("Next: room brightness") { step = Step.LUX_BRIGHT_READY }
                    }
                }

                Step.LUX_BRIGHT_READY -> {
                    Text(
                        "Set the room to its normal daytime brightness — " +
                            "blinds and lights as usual. Press when it's set.",
                        fontSize = BODY,
                    )
                    BigButton("Room is bright — measure") { step = Step.LUX_BRIGHT }
                }

                Step.LUX_BRIGHT -> Sampling(
                    "Measuring bright level",
                    "Hold the lighting steady.",
                    countdown, progress,
                )

                Step.LUX_DARK_READY -> {
                    Text(
                        "Now make the room as dark as it gets at night — " +
                            "lights off, blinds closed. Press when it's dark.",
                        fontSize = BODY,
                    )
                    BigButton("Room is dark — measure") { step = Step.LUX_DARK }
                }

                Step.LUX_DARK -> Sampling(
                    "Measuring dark level",
                    "Hold the lighting steady.",
                    countdown, progress,
                )

                Step.DIMMING -> {
                    if (luxBright.isNaN() || luxDark.isNaN() || luxBright <= luxDark * 1.2f) {
                        Text(
                            "Bright and dark read too close together " +
                                "(${if (luxBright.isNaN()) "?" else luxBright.roundToInt().toString()} vs " +
                                "${if (luxDark.isNaN()) "?" else luxDark.roundToInt().toString()} lux) — " +
                                "brightness anchors left unchanged.",
                            fontSize = BODY,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(onClick = { step = Step.LUX_BRIGHT_READY }) {
                            Text("Retry lighting", fontSize = DETAIL)
                        }
                    } else {
                        Text(
                            "Bright ${luxBright.roundToInt()} lux, dark " +
                                "${luxDark.roundToInt()} lux — brightness will " +
                                "span between those.",
                            fontSize = BODY,
                        )
                    }
                    Text("Idle timeout: ${draft.manualTimeoutSeconds}s", fontSize = BODY)
                    Slider(
                        value = draft.manualTimeoutSeconds.toFloat(),
                        onValueChange = { draft = draft.copy(manualTimeoutSeconds = it.roundToInt()) },
                        valueRange = 15f..600f,
                    )
                    Text("Dim to ${draft.manualIdleDimPercent}% before switching off", fontSize = BODY)
                    Slider(
                        value = draft.manualIdleDimPercent.toFloat(),
                        onValueChange = { draft = draft.copy(manualIdleDimPercent = it.roundToInt()) },
                        valueRange = 0f..100f,
                    )
                    BigButton("Finish") { step = Step.DONE }
                }

                Step.DONE -> {
                    Text("Summary", fontSize = BODY)
                    if (!radar && !proxThreshold.isNaN()) {
                        Text("• Wake trigger: ${proxThreshold.roundToInt()}", fontSize = DETAIL)
                    }
                    if (!luxDark.isNaN() && !luxBright.isNaN() && luxBright > luxDark * 1.2f) {
                        Text(
                            "• Brightness anchors: ${luxDark.roundToInt()}–${luxBright.roundToInt()} lux",
                            fontSize = DETAIL,
                        )
                    }
                    Text(
                        "• Idle: dim to ${draft.manualIdleDimPercent}% after ${draft.manualTimeoutSeconds}s",
                        fontSize = DETAIL,
                    )
                    Text(
                        "All of this is editable in the settings sheet, and the " +
                            "wizard can be re-run any time.",
                        fontSize = DETAIL,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BigButton("Apply") { onApply(draft); onClose() }
                        OutlinedButton(onClick = onClose) { Text("Discard", fontSize = DETAIL) }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            if (step != Step.DONE) {
                OutlinedButton(onClick = onClose) { Text("Cancel", fontSize = DETAIL) }
            }
        }
    }
}

@Composable
private fun BigButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(64.dp)) {
        Text(label, fontSize = BODY)
    }
}

@Composable
private fun Sampling(title: String, body: String, countdown: Int, progress: Float) {
    Text(title, fontSize = BODY)
    Text(body, fontSize = DETAIL)
    if (countdown > 0) {
        // The countdown is the biggest thing on screen: it is read mid-stride.
        Text("$countdown", fontSize = 64.sp)
        Text("Get into position…", fontSize = DETAIL)
    } else {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}
