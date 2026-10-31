package com.vornex.assistant

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.abs

/**
 * Собственный захват микрофона с автоусилением: Боря слышит и тихую речь,
 * не нужно кричать. Результаты распознавания отдаёт в onText.
 */
class MicEngine(
    private val model: Model,
    private val spk: org.vosk.SpeakerModel?,
    private val onText: (String, FloatArray?) -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var mutedUntil = 0L
    private var thread: Thread? = null

    fun setPause(p: Boolean) {
        paused = p
        // хвост эха после окончания речи не слушаем
        if (!p) mutedUntil = System.currentTimeMillis() + 350
    }

    fun start() {
        running = true
        thread = Thread { loop() }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
    }

    private fun loop() {
        val rate = 16000
        val chunk = rate / 10 // 100 мс
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, chunk * 2 * 4)
            )
        } catch (e: Exception) {
            onError("Микрофон: ${e.message}")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            onError("Микрофон не запустился")
            return
        }
        val id = rec.audioSessionId
        try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(id)?.enabled = true } catch (_: Throwable) {}
        try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(id)?.enabled = true } catch (_: Throwable) {}
        // системный AGC не включаем: свой работает мягче и сильнее

        val recognizer = if (spk != null) Recognizer(model, rate.toFloat(), spk) else Recognizer(model, rate.toFloat())
        val buf = ShortArray(chunk)
        var smooth = 300f
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (paused || System.currentTimeMillis() < mutedUntil) {
                    continue
                }
                var peak = 0
                for (i in 0 until n) {
                    val a = abs(buf[i].toInt())
                    if (a > peak) peak = a
                }
                smooth = maxOf(peak.toFloat(), smooth * 0.97f, 250f)
                // цель — пики около 14000; усиление от 2x до 14x
                val gain = (14000f / smooth).coerceIn(2f, 14f)
                for (i in 0 until n) {
                    val v = (buf[i] * gain).toInt()
                    buf[i] = v.coerceIn(-32768, 32767).toShort()
                }
                if (recognizer.acceptWaveForm(buf, n)) {
                    val j = org.json.JSONObject(recognizer.result)
                    val text = j.optString("text")
                    val arr = j.optJSONArray("spk")
                    val vec = if (arr != null) FloatArray(arr.length()) { arr.getDouble(it).toFloat() } else null
                    if (text.isNotBlank()) onText(text, vec)
                }
            }
            val last = org.json.JSONObject(recognizer.finalResult).optString("text")
            if (last.isNotBlank()) onText(last, null)
        } catch (e: Exception) {
            onError("Ошибка микрофона: ${e.message}")
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            recognizer.close()
        }
    }
}
