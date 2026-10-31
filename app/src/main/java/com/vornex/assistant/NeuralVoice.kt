package com.vornex.assistant

import android.content.Context
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Офлайн нейро-голос (Piper через sherpa-onnx). Файлы голоса качаются один раз из релиза `voice`. */
object NeuralVoice {
    private const val URL_ZIP = "https://github.com/nikishkinmilan-cloud/vornex-assistant/releases/download/voice/voice.zip"

    @Volatile private var tts: OfflineTts? = null
    @Volatile var error: String? = null
    private val lock = Any()

    fun dir(ctx: Context) = File(ctx.filesDir, "voice")
    private fun onnx(ctx: Context) = dir(ctx).listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull()

    fun isInstalled(ctx: Context) = onnx(ctx) != null && File(dir(ctx), "tokens.txt").exists()
    val ready get() = tts != null

    /** Включён ли нейро-голос (по умолчанию да, если установлен). */
    fun enabled(ctx: Context) = ctx.getSharedPreferences("vornex", Context.MODE_PRIVATE).getBoolean("neural", true)

    fun setEnabled(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences("vornex", Context.MODE_PRIVATE).edit().putBoolean("neural", v).apply()

    fun load(ctx: Context) {
        synchronized(lock) {
            if (tts != null || !isInstalled(ctx)) return
            try {
                val d = dir(ctx)
                val cfg = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = onnx(ctx)!!.absolutePath,
                            tokens = File(d, "tokens.txt").absolutePath,
                            dataDir = File(d, "espeak-ng-data").absolutePath,
                            lengthScale = 1.0f
                        ),
                        numThreads = 3,
                        debug = false,
                        provider = "cpu"
                    ),
                    maxNumSentences = 1
                )
                tts = OfflineTts(null, cfg)
                error = null
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
                tts = null
            }
        }
    }

    fun unload() {
        synchronized(lock) { tts = null }
    }

    fun sampleRate() = tts?.sampleRate() ?: 22050

    /** Синтез одного куска текста (блокирующий). */
    fun synth(text: String, speed: Float = 1.0f): GeneratedAudio? {
        val t = tts ?: return null
        return synchronized(lock) {
            try {
                t.generate(text, 0, speed)
            } catch (e: Throwable) {
                error = e.message
                null
            }
        }
    }

    /** Скачать и распаковать голос. Блокирующий вызов, из фонового потока. */
    fun download(ctx: Context, onProgress: (Int) -> Unit) {
        val tmp = File(ctx.filesDir, "voice_tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val conn = URL(URL_ZIP).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 40000
        conn.instanceFollowRedirects = true
        if (conn.responseCode != 200) throw IllegalStateException("голос не найден (HTTP ${conn.responseCode})")
        val total = conn.contentLengthLong.coerceAtLeast(1)
        var read = 0L
        val root = tmp.canonicalPath + File.separator
        val input = object : java.io.FilterInputStream(BufferedInputStream(conn.inputStream)) {
            override fun read(): Int = super.read().also { if (it >= 0) read++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) read += it }
        }
        var lastPct = -1
        ZipInputStream(input).use { zis ->
            var e = zis.nextEntry
            val buf = ByteArray(1 shl 16)
            while (e != null) {
                val out = File(tmp, e.name)
                if (!out.canonicalPath.startsWith(root)) throw SecurityException("bad zip entry")
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            o.write(buf, 0, n)
                            val pct = (read * 100 / total).toInt().coerceAtMost(99)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
                e = zis.nextEntry
            }
        }
        unload()
        val target = dir(ctx)
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw IllegalStateException("не удалось сохранить голос")
        onProgress(100)
    }
}
