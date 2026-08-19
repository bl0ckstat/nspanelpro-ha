package pro.nspanel.ha2.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import pro.nspanel.ha2.MainViewModel
import pro.nspanel.ha2.MainViewModelFactory
import pro.nspanel.ha2.screen.ScreenStats
import kotlin.math.roundToInt

@Composable
fun PanelScreen(
    viewModel: MainViewModel = viewModel(factory = MainViewModelFactory(LocalContext.current)),
    screenStats: StateFlow<ScreenStats>? = null,
    onUserInteraction: () -> Unit = {},
) {
    val stats by (screenStats ?: MutableStateFlow(ScreenStats())).collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val panelConfig by viewModel.panelConfig.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    var draft by remember(settings) { mutableStateOf(settings) }
    var reloadTrigger by remember { mutableStateOf(0) }
    var yamlError by remember { mutableStateOf<String?>(null) }
    var yamlDownloadSuccessChars by remember { mutableStateOf<Int?>(null) }
    var yamlLoading by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        if (!settingsOpen) draft = settings
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(onUserInteraction) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onUserInteraction()
                }
            },
    ) {
        val isCompact = maxWidth < 520.dp && maxHeight < 520.dp
        val titleSize = if (isCompact) 16.sp else 20.sp
        val bodySize = if (isCompact) 13.sp else 15.sp
        val captionSize = if (isCompact) 11.sp else 13.sp
        val sheetMaxHeight = maxHeight * if (isCompact) 0.92f else 0.88f
        val topZone = 72.dp

        Box(modifier = Modifier.fillMaxSize()) {
            HaWebView(
                appSettings = settings,
                onUserInteraction = onUserInteraction,
                reloadTrigger = reloadTrigger,
                modifier = Modifier.fillMaxSize(),
            )

            // Drag zone — swipe down to open settings
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(topZone)
                    .zIndex(2f)
                    .pointerInput(settingsOpen) {
                        var totalDrag = 0f
                        detectVerticalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onVerticalDrag = { _, dragAmount ->
                                totalDrag += dragAmount
                                if (!settingsOpen && totalDrag > 48f) settingsOpen = true
                                else if (settingsOpen && totalDrag < -48f) settingsOpen = false
                            },
                        )
                    },
            )

            // Settings sheet
            AnimatedVisibility(
                visible = settingsOpen,
                enter = slideInVertically { -it },
                exit = slideOutVertically { -it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(4f),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = sheetMaxHeight)
                        .padding(top = 48.dp, start = 8.dp, end = 8.dp, bottom = 8.dp),
                    shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                    tonalElevation = 6.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                            .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                                focusManager.clearFocus()
                            },
                    ) {
                        // Title row with close button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "NSPanel HA",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = titleSize),
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close",
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable { settingsOpen = false },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Sensor status row
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "Light: ${"%.0f".format(stats.lux)} lx",
                                fontSize = captionSize,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Proximity: ${if (stats.proximityNear) "near" else "clear"}",
                                fontSize = captionSize,
                                color = if (stats.proximityNear)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Device: ${stats.generation} · ${stats.deviceModel} · API ${stats.sdkInt}" +
                                if (panelConfig.diagPort > 0) " · diag :${panelConfig.diagPort}" else "",
                            fontSize = captionSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!stats.canWriteSettings) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "⚠ WRITE_SETTINGS not granted — screen dimming may be unreliable. " +
                                    "Fix: adb shell appops set pro.nspanel.ha2 WRITE_SETTINGS allow",
                                fontSize = captionSize,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        // Diagnostics — network/relay status and the way out to Android settings
                        OutlinedButton(
                            onClick = { diagnosticsOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Diagnostics & Android settings", fontSize = bodySize)
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        // Home Assistant URL
                        OutlinedTextField(
                            value = draft.homeAssistantUrl,
                            onValueChange = { draft = draft.copy(homeAssistantUrl = it) },
                            label = { Text("Home Assistant URL", fontSize = bodySize) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = bodySize),
                            placeholder = {
                                Text(
                                    "http://homeassistant.local:8123",
                                    fontSize = bodySize,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                )
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // YAML URL
                        OutlinedTextField(
                            value = draft.panelYamlUrl,
                            onValueChange = {
                                draft = draft.copy(panelYamlUrl = it)
                                yamlError = null
                                yamlDownloadSuccessChars = null
                            },
                            label = { Text("Panel YAML URL (optional)", fontSize = bodySize) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = bodySize),
                            placeholder = {
                                Text(
                                    "http://…/panel-config.yaml",
                                    fontSize = bodySize,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                )
                            },
                            supportingText = {
                                Text(
                                    if (draft.panelYamlUrl.isBlank())
                                        "Using manual settings below."
                                    else
                                        "YAML overrides manual settings. Clear to use manual.",
                                    fontSize = captionSize,
                                )
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        )

                        if (draft.panelYamlUrl.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    yamlError = null
                                    yamlDownloadSuccessChars = null
                                    yamlLoading = true
                                    viewModel.fetchPanelYaml(draft.panelYamlUrl) { err, charCount ->
                                        yamlLoading = false
                                        yamlError = err
                                        yamlDownloadSuccessChars = if (err == null) charCount else null
                                    }
                                },
                                enabled = !yamlLoading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (yamlLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.padding(end = 8.dp).size(22.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                    Text("Download YAML", fontSize = bodySize)
                                }
                            }
                            yamlDownloadSuccessChars?.let { n ->
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "Downloaded $n characters.",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = bodySize,
                                )
                            }
                            yamlError?.let { err ->
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(err, color = MaterialTheme.colorScheme.error, fontSize = bodySize)
                            }
                        }

                        // Manual panel settings — only when no YAML URL
                        if (draft.panelYamlUrl.isBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Panel settings",
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = bodySize),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            // Brightness
                            LabeledSlider(
                                label = "Brightness",
                                value = draft.manualBrightness.toFloat(),
                                valueRange = 30f..255f,
                                displayValue = "${draft.manualBrightness}",
                                onValueChange = {
                                    draft = draft.copy(manualBrightness = it.roundToInt())
                                    viewModel.applyLive(draft)
                                },
                                onValueChangeFinished = { viewModel.saveDraft(draft) },
                                bodySize = bodySize,
                                captionSize = captionSize,
                            )

                            // Screen timeout
                            LabeledSlider(
                                label = "Screen timeout",
                                value = draft.manualTimeoutSeconds.toFloat(),
                                valueRange = 15f..600f,
                                displayValue = formatSeconds(draft.manualTimeoutSeconds),
                                onValueChange = {
                                    draft = draft.copy(manualTimeoutSeconds = it.roundToInt())
                                    viewModel.applyLive(draft)
                                },
                                onValueChangeFinished = { viewModel.saveDraft(draft) },
                                bodySize = bodySize,
                                captionSize = captionSize,
                            )

                            // Idle dim
                            LabeledSlider(
                                label = "Idle dim",
                                value = draft.manualIdleDimPercent.toFloat(),
                                valueRange = 0f..100f,
                                displayValue = "${draft.manualIdleDimPercent}%",
                                onValueChange = {
                                    draft = draft.copy(manualIdleDimPercent = it.roundToInt())
                                    viewModel.applyLive(draft)
                                },
                                onValueChangeFinished = { viewModel.saveDraft(draft) },
                                bodySize = bodySize,
                                captionSize = captionSize,
                            )

                            // Sensor report interval
                            LabeledSlider(
                                label = "Sensor report interval",
                                value = draft.manualSensorIntervalSeconds.toFloat(),
                                valueRange = 10f..300f,
                                displayValue = formatSeconds(draft.manualSensorIntervalSeconds),
                                onValueChange = {
                                    draft = draft.copy(manualSensorIntervalSeconds = it.roundToInt())
                                    viewModel.applyLive(draft)
                                },
                                onValueChangeFinished = { viewModel.saveDraft(draft) },
                                bodySize = bodySize,
                                captionSize = captionSize,
                            )

                            // Auto-brightness
                            LabeledSwitch(
                                label = "Auto-brightness (light sensor)",
                                checked = draft.manualAutoBrightness,
                                onCheckedChange = {
                                    draft = draft.copy(manualAutoBrightness = it)
                                    viewModel.saveDraft(draft)
                                },
                                bodySize = bodySize,
                            )

                            // Proximity wake
                            LabeledSwitch(
                                label = "Proximity wake",
                                checked = draft.manualProximityWake,
                                onCheckedChange = { draft = draft.copy(manualProximityWake = it) },
                                bodySize = bodySize,
                            )

                            // Show status bar
                            LabeledSwitch(
                                label = "Show status bar",
                                checked = draft.manualShowStatusBar,
                                onCheckedChange = { draft = draft.copy(manualShowStatusBar = it) },
                                bodySize = bodySize,
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { settingsOpen = false },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Cancel", fontSize = bodySize)
                            }
                            Button(
                                onClick = {
                                    reloadTrigger++
                                    settingsOpen = false
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Refresh", fontSize = bodySize)
                            }
                            Button(
                                onClick = {
                                    viewModel.saveDraft(draft)
                                    settingsOpen = false
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Save", fontSize = bodySize)
                            }
                        }
                    }
                }
            }

            if (diagnosticsOpen) {
                DiagnosticsDialog(
                    stats = stats,
                    panelConfig = panelConfig,
                    settings = settings,
                    onDismiss = { diagnosticsOpen = false },
                )
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    bodySize: androidx.compose.ui.unit.TextUnit,
    captionSize: androidx.compose.ui.unit.TextUnit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = captionSize, modifier = Modifier.weight(1f))
        Text(displayValue, fontSize = captionSize, color = MaterialTheme.colorScheme.primary)
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(2.dp))
}

@Composable
private fun LabeledSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    bodySize: androidx.compose.ui.unit.TextUnit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = bodySize, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun formatSeconds(seconds: Int): String = when {
    seconds < 60 -> "${seconds}s"
    seconds % 60 == 0 -> "${seconds / 60}m"
    else -> "${seconds / 60}m ${seconds % 60}s"
}
