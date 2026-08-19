package pro.nspanel.ha2.mqtt

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import pro.nspanel.ha2.sound.SoundPlayer

private const val TAG = "MqttManager"

class MqttManager(private val soundPlayer: SoundPlayer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: Job? = null

    @Volatile private var currentBroker = ""
    @Volatile private var currentTopic = ""
    @Volatile private var currentUser = ""
    @Volatile private var currentPass = ""
    @Volatile private var client: MqttClient? = null

    fun applyConfig(broker: String, topic: String, username: String, password: String) {
        val b = broker.trim(); val t = topic.trim()
        val u = username.trim(); val p = password.trim()
        if (b == currentBroker && t == currentTopic && u == currentUser && p == currentPass) return

        currentBroker = b; currentTopic = t; currentUser = u; currentPass = p
        stopClient()

        if (b.isNotEmpty() && t.isNotEmpty()) {
            startClient(b, t, u, p)
        }
    }

    fun destroy() {
        scope.cancel()
        stopClient()
    }

    private fun startClient(broker: String, topic: String, user: String, pass: String) {
        connectJob = scope.launch {
            var backoff = 5_000L
            while (isActive) {
                try {
                    val c = MqttClient(broker, MqttClient.generateClientId(), MemoryPersistence())
                    c.setCallback(object : MqttCallback {
                        override fun connectionLost(cause: Throwable?) {
                            Log.w(TAG, "Lost connection: ${cause?.message}")
                            client = null
                            if (broker == currentBroker && topic == currentTopic) {
                                startClient(broker, topic, user, pass)
                            }
                        }
                        override fun messageArrived(t: String, msg: MqttMessage) =
                            handleMessage(msg.toString().trim())
                        override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
                    })
                    c.connect(MqttConnectOptions().apply {
                        isCleanSession = true
                        connectionTimeout = 10
                        keepAliveInterval = 30
                        isAutomaticReconnect = false
                        if (user.isNotEmpty()) { userName = user; password = pass.toCharArray() }
                    })
                    val topics = topic.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    c.subscribe(topics.toTypedArray(), IntArray(topics.size) { 1 })
                    client = c
                    Log.i(TAG, "Connected $broker → ${topics.joinToString()}")
                    return@launch
                } catch (e: Exception) {
                    Log.w(TAG, "Connect failed (${e.message}) — retry in ${backoff / 1000}s")
                    delay(backoff)
                    backoff = minOf(backoff * 2, 60_000L)
                }
            }
        }
    }

    private fun stopClient() {
        connectJob?.cancel(); connectJob = null
        try { client?.disconnect(); client?.close() } catch (_: Exception) {}
        client = null
    }

    private fun handleMessage(payload: String) {
        val sound = if (payload.startsWith("{")) {
            Regex(""""sound"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.get(1) ?: return
        } else {
            payload
        }
        soundPlayer.play(sound)
    }
}
