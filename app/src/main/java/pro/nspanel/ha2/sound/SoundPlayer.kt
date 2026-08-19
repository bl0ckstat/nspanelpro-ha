package pro.nspanel.ha2.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

class SoundPlayer(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun play(soundName: String) {
        when (soundName.lowercase().trim()) {
            "doorbell" -> scheduleChime(DOORBELL_NOTES)
            else -> scheduleChime(DOORBELL_NOTES)
        }
    }

    private fun scheduleChime(notes: List<Note>) {
        Thread {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener {}
                .build()
            audioManager.requestAudioFocus(focusRequest)
            try {
                notes.forEachIndexed { i, note ->
                    if (i > 0) Thread.sleep(note.gapMs)
                    playTone(note.freq, note.durationMs, attrs)
                }
            } finally {
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun playTone(freq: Float, durationMs: Int, attrs: AudioAttributes) {
        val numSamples = SAMPLE_RATE * durationMs / 1000
        val samples = ShortArray(numSamples) { i ->
            val envelope = when {
                i < numSamples * 0.04 -> i / (numSamples * 0.04)
                i > numSamples * 0.75 -> (numSamples - i) / (numSamples * 0.25)
                else -> 1.0
            }
            (sin(2.0 * PI * freq * i / SAMPLE_RATE) * Short.MAX_VALUE * envelope).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(
                maxOf(samples.size * 2, AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, samples.size)
        track.play()
        Thread.sleep(durationMs.toLong() + 20)
        track.stop()
        track.release()
    }

    private data class Note(val freq: Float, val durationMs: Int, val gapMs: Long = 0)

    private companion object {
        const val SAMPLE_RATE = 44100
        val DOORBELL_NOTES = listOf(
            Note(freq = 659f, durationMs = 280),          // E5 — ding
            Note(freq = 523f, durationMs = 480, gapMs = 60), // C5 — dong
        )
    }
}
