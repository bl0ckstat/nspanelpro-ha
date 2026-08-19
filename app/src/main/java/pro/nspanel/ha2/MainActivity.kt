package pro.nspanel.ha2

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import pro.nspanel.ha2.diag.DiagServer
import pro.nspanel.ha2.diag.DiagState
import pro.nspanel.ha2.mqtt.MqttManager
import pro.nspanel.ha2.screen.ScreenManager
import pro.nspanel.ha2.sound.SoundPlayer
import pro.nspanel.ha2.ui.PanelScreen
import pro.nspanel.ha2.ui.theme.NSPanelHATheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModelFactory(this) }
    private lateinit var screenManager: ScreenManager
    private lateinit var diagServer: DiagServer
    private lateinit var mqttManager: MqttManager
    private var showStatusBar = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        applySystemBars(showStatusBar = false)

        screenManager = ScreenManager(this)
        mqttManager = MqttManager(SoundPlayer(this))
        diagServer = DiagServer(applicationContext)
        DiagState.activityAlive = true

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.panelConfig.collect { config ->
                        screenManager.applyConfig(config)
                        mqttManager.applyConfig(
                            config.mqttBroker, config.mqttTopic,
                            config.mqttUsername, config.mqttPassword,
                        )
                        DiagState.panelConfig = config
                        diagServer.ensureRunning(config.diagPort)
                        if (config.showStatusBar != showStatusBar) {
                            showStatusBar = config.showStatusBar
                            applySystemBars(showStatusBar)
                        }
                    }
                }
                launch { viewModel.settings.collect { DiagState.appSettings = it } }
                launch { screenManager.stats.collect { DiagState.stats = it } }
            }
        }

        setContent {
            NSPanelHATheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PanelScreen(
                        viewModel = viewModel,
                        screenStats = screenManager.stats,
                        onUserInteraction = screenManager::onUserInteraction,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        screenManager.resume()
    }

    override fun onPause() {
        super.onPause()
        screenManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        DiagState.activityAlive = false
        diagServer.destroy()
        screenManager.destroy()
        mqttManager.destroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars(showStatusBar)
    }

    private fun applySystemBars(showStatusBar: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (showStatusBar) {
            controller.show(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
