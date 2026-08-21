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
import androidx.compose.ui.Alignment
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
 * "dark room" mean in THIS room's lux. Each step tells the user what to do
 * with their body or their lights, samples while they do it, and derives the
 * setting from the measurements. Everything it writes lands in the same
 * settings the sheet edits by hand, so results are tunable afterwards and the
 * wizard can be re-run whenever the room or the furniture changes.
 */
private enum class Step {
    INTRO,
    PROX_CLEAR,
    PROX_NEAR,
    PROX_RESULT,
    LUX_BRIGHT,
    LUX_DARK,
    DIMMING,
    DONE,
}

@Composable
fun CalibrationWizard(
    statsFlow: StateFlow<ScreenStats>,
    settings: AppSettings,
    onApply: (AppSettings) -> Unit,
    onClose: () -> Unit,
) {
    var step by remember { mutableStateOf(Step.INTRO) }
    var draft by remember { mutableStateOf(settings) }

    // Measurements, carried between steps.
    var restPeak by remember { mutableStateOf(Float.NaN) }
    var nearTypical by remember { mutableStateOf(Float.NaN) }
    var proxThreshold by remember { mutableStateOf(Float.NaN) }
    var proxFailed by remember { mutableStateOf(false) }
    var luxBright by remember { mutableStateOf(Float.NaN) }
    var luxDark by remember { mutableStateOf(Float.NaN) }
    var progress by remember { mutableStateOf(0f) }

    val generation = statsFlow.value.generation
    val radar = generation == PanelGeneration.GEN2

    /** Sample [seconds] of readings, reporting progress, then hand back the list. */
    suspend fun sample(seconds: Int, pick: (ScreenStats) -> Float): List<Float> {
        val out = mutableListOf<Float>()
        val ticks = seconds * 4
        repeat(ticks) { i ->
            out += pick(statsFlow.value)
            progress = (i + 1f) / ticks
            delay(250)
        }
        return out.filter { !it.isNaN() && it >= 0f }
    }

    LaunchedEffect(step) {
        progress = 0f
        when (step) {
            Step.PROX_CLEAR -> {
                val rest = sample(10) { it.proximityRaw }
                restPeak = rest.maxOrNull() ?: Float.NaN
                step = Step.PROX_NEAR
            }
            Step.PROX_NEAR -> {
                val near = sample(8) { it.proximityRaw }
                // The typical reading at wake distance, not the peak: the
                // peak is whatever moment the hand drifted closest, and a
                // trigger set off that wakes only on touch.
                nearTypical = near.sorted().let {
                    if (it.isEmpty()) Float.NaN else it[it.size / 2]
                }
                proxFailed = when {
                    radar -> false
                    nearTypical.isNaN() || restPeak.isNaN() -> true
                    // Indistinguishable from resting noise: standing at wake
                    // distance must read clearly above an empty room.
                    nearTypical < restPeak * 1.5f + 10f -> true
                    else -> false
                }
                if (!proxFailed && !radar) {
                    // Geometric midpoint: reflectance is multiplicative, so
                    // halfway in log-space splits the two states evenly.
                    proxThreshold = sqrt(restPeak * nearTypical)
                    draft = draft.copy(manualProximityThreshold = proxThreshold.roundToInt().toFloat())
                }
                step = Step.PROX_RESULT
            }
            Step.LUX_BRIGHT -> {
                val v = sample(6) { it.lux }
                luxBright = v.sorted().let { if (it.isEmpty()) Float.NaN else it[it.size / 2] }
                step = Step.LUX_DARK
            }
            Step.LUX_DARK -> {
                val v = sample(6) { it.lux }
                luxDark = v.sorted().let { if (it.isEmpty()) Float.NaN else it[it.size / 2] }
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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Calibration", fontSize = 22.sp, style = MaterialTheme.typography.titleLarge)

            when (step) {
                Step.INTRO -> {
                    Text(
                        "This walks through tuning the wake sensor and the " +
                            "auto-brightness for this room. You'll be asked to " +
                            "step away, stand at wake distance, and change the " +
                            "room lighting. About a minute in total.",
                        fontSize = 15.sp,
                    )
                    Button(onClick = { step = Step.PROX_CLEAR }) { Text("Start") }
                }

                Step.PROX_CLEAR -> Sampling(
                    "Step well away from the panel",
                    "Measuring the empty-room reading for 10 seconds — keep clear.",
                    progress,
                )

                Step.PROX_NEAR -> Sampling(
                    "Now stand where the panel should wake",
                    "Stay at that distance — arm's length is typical. Measuring for 8 seconds.",
                    progress,
                )

                Step.PROX_RESULT -> {
                    if (radar) {
                        Text(
                            "This panel has a presence radar: it reports " +
                                "someone-there or empty on its own, with no " +
                                "distance to tune. Nothing to set here.",
                            fontSize = 15.sp,
                        )
                    } else if (proxFailed) {
                        Text(
                            "Couldn't tell you apart from the empty room " +
                                "(empty peaked at ${restPeak.roundToInt()}, you " +
                                "read ${if (nearTypical.isNaN()) "nothing" else nearTypical.roundToInt().toString()}). " +
                                "Try again standing closer.",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(onClick = { step = Step.PROX_CLEAR }) { Text("Retry") }
                    } else {
                        Text(
                            "Empty room peaks at ${restPeak.roundToInt()}; at your " +
                                "chosen distance it reads ${nearTypical.roundToInt()}. " +
                                "Trigger set to ${proxThreshold.roundToInt()}.",
                            fontSize = 15.sp,
                        )
                    }
                    Button(onClick = { step = Step.LUX_BRIGHT }) { Text("Next: room brightness") }
                }

                Step.LUX_BRIGHT -> Sampling(
                    "Set the room to its normal daytime brightness",
                    "Open blinds or turn lights on as usual, then hold still — measuring.",
                    progress,
                )

                Step.LUX_DARK -> Sampling(
                    "Now make the room as dark as it gets at night",
                    "Lights off, blinds closed — measuring the dark level.",
                    progress,
                )

                Step.DIMMING -> {
                    if (luxBright.isNaN() || luxDark.isNaN() || luxBright <= luxDark * 1.2f) {
                        Text(
                            "The bright and dark readings were too close " +
                                "(${if (luxBright.isNaN()) "?" else luxBright.roundToInt().toString()} vs " +
                                "${if (luxDark.isNaN()) "?" else luxDark.roundToInt().toString()} lux) — " +
                                "brightness anchors left unchanged.",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(onClick = { step = Step.LUX_BRIGHT }) { Text("Retry lighting") }
                    } else {
                        Text(
                            "Bright ${luxBright.roundToInt()} lux, dark ${luxDark.roundToInt()} lux — " +
                                "the screen will span its brightness range between those.",
                            fontSize = 15.sp,
                        )
                    }
                    Text("Idle timeout: ${draft.manualTimeoutSeconds}s", fontSize = 15.sp)
                    Slider(
                        value = draft.manualTimeoutSeconds.toFloat(),
                        onValueChange = { draft = draft.copy(manualTimeoutSeconds = it.roundToInt()) },
                        valueRange = 15f..600f,
                    )
                    Text("Dim to ${draft.manualIdleDimPercent}% before switching off", fontSize = 15.sp)
                    Slider(
                        value = draft.manualIdleDimPercent.toFloat(),
                        onValueChange = { draft = draft.copy(manualIdleDimPercent = it.roundToInt()) },
                        valueRange = 0f..100f,
                    )
                    Button(onClick = { step = Step.DONE }) { Text("Finish") }
                }

                Step.DONE -> {
                    Text("Calibration summary", fontSize = 16.sp)
                    if (!radar && !proxThreshold.isNaN()) {
                        Text("• Wake trigger: ${proxThreshold.roundToInt()}", fontSize = 14.sp)
                    }
                    if (!luxDark.isNaN() && !luxBright.isNaN() && luxBright > luxDark * 1.2f) {
                        Text(
                            "• Brightness anchors: ${luxDark.roundToInt()}–${luxBright.roundToInt()} lux",
                            fontSize = 14.sp,
                        )
                    }
                    Text(
                        "• Idle: dim to ${draft.manualIdleDimPercent}% after " +
                            "${draft.manualTimeoutSeconds}s",
                        fontSize = 14.sp,
                    )
                    Text(
                        "Everything here is editable later in the settings sheet, " +
                            "and the wizard can be re-run any time.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onApply(draft); onClose() }) { Text("Apply") }
                        OutlinedButton(onClick = onClose) { Text("Discard") }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            if (step != Step.DONE) {
                OutlinedButton(onClick = onClose) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun Sampling(title: String, body: String, progress: Float) {
    Text(title, fontSize = 16.sp)
    Text(body, fontSize = 14.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}
