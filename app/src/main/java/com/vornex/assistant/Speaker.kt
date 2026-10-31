package com.vornex.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * Озвучка: нейро-голос (если скачан и включён), иначе системный синтезатор.
 * onDone(id) вызывается, когда фраза договорена или сброшена.
 */
class Speaker(
    private val ctx: Context,
    private val alarmStream: Boolean = false,
    private val onDone: (String) -> Unit
) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voiceName: String? = null

    private class Item(val id: String, val text: String)

    private val queue = LinkedBlockingQueue<Item>()
    @Volatile private var generation = 0
    @Volatile private var alive = true
    @Volatile private var track: AudioTrack? = null
    private var worker: Thread? = null

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                tts?.setPitch(0.9f)
                if (alarmStream) {
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                    )
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { utteranceId?.let(onDone) }
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(utteranceId: String?) { utteranceId?.let(onDone) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let(onDone) }
                })
                ttsReady = true
            }
        }
        if (NeuralVoice.isInstalled(ctx) && NeuralVoice.enabled(ctx)) {
            worker = Thread { workLoop() }.also { it.isDaemon = true; it.start() }
        }
    }

    private fun neuralOn() = NeuralVoice.ready && NeuralVoice.enabled(ctx)

    /** Что сейчас используется — для экрана. */
    fun engineName() = if (neuralOn()) "нейро-голос" else "системный голос"

    fun speak(text: String, flush: Boolean, id: String) {
        if (neuralOn()) {
            if (flush) flushNeural()
            queue.put(Item(id, text))
        } else {
            if (!ttsReady) {
                onDone(id)
                return
            }
            applyVoice()
            val r = tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            if (r != TextToSpeech.SUCCESS) onDone(id)
        }
    }

    fun stop() {
        flushNeural()
        tts?.stop()
    }

    private fun flushNeural() {
        generation++
        val dropped = ArrayList<Item>()
        queue.drainTo(dropped)
        dropped.forEach { onDone(it.id) }
        try { track?.pause(); track?.flush() } catch (_: Exception) {}
    }

    private fun applyVoice() {
        val want = ctx.getSharedPreferences("vornex", Context.MODE_PRIVATE).getString("voice", null)
        if (want == voiceName) return
        val v = tts?.voices?.firstOrNull { it.name == want }
        if (v != null) {
            tts?.voice = v
            voiceName = want
        }
    }

    private fun workLoop() {
        NeuralVoice.load(ctx)
        while (alive) {
            val item = try { queue.take() } catch (_: InterruptedException) { break }
            val gen = generation
            try {
                for (part in split(item.text)) {
                    if (gen != generation || !alive) break
                    val audio = NeuralVoice.synth(part) ?: break
                    if (gen != generation) break
                    play(audio.samples, audio.sampleRate, gen)
                }
            } catch (_: Throwable) {
            }
            onDone(item.id)
        }
    }

    private fun split(t: String): List<String> =
        t.split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun play(samples: FloatArray, rate: Int, gen: Int) {
        if (samples.isEmpty()) return
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val attrs = AudioAttributes.Builder()
            .setUsage(if (alarmStream) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, samples.size * 4))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = t
        try {
            t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            t.play()
            val ms = samples.size * 1000L / rate
            val end = System.currentTimeMillis() + ms + 150
            while (System.currentTimeMillis() < end && gen == generation && alive) Thread.sleep(40)
            // небольшая пауза между предложениями
        } finally {
            try { t.stop() } catch (_: Exception) {}
            t.release()
            track = null
        }
    }

    fun shutdown() {
        alive = false
        worker?.interrupt()
        flushNeural()
        tts?.shutdown()
    }
}
