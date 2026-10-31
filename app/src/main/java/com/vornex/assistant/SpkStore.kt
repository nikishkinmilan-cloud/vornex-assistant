package com.vornex.assistant

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Модель распознавания говорящего Vosk (около 13 МБ). */
object SpkStore {
    private const val URL_ZIP = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"

    private fun root(ctx: Context) = File(ctx.filesDir, "spk")

    fun modelDir(ctx: Context): File? =
        root(ctx).listFiles { f -> f.isDirectory && File(f, "mean.vec").exists() }?.firstOrNull()

    fun isInstalled(ctx: Context) = modelDir(ctx) != null

    fun download(ctx: Context, onProgress: (Int) -> Unit) {
        val tmp = File(ctx.filesDir, "spk_tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val c = URL(URL_ZIP).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 40000
        val total = c.contentLengthLong.coerceAtLeast(1)
        var read = 0L
        val input = object : java.io.FilterInputStream(BufferedInputStream(c.inputStream)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) read += it }
            override fun read(): Int = super.read().also { if (it >= 0) read++ }
        }
        val rootPath = tmp.canonicalPath + File.separator
        ZipInputStream(input).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val out = File(tmp, e.name)
                if (!out.canonicalPath.startsWith(rootPath)) throw SecurityException("bad zip entry")
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                onProgress((read * 100 / total).toInt().coerceAtMost(99))
                e = zis.nextEntry
            }
        }
        val target = root(ctx)
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw IllegalStateException("не удалось сохранить модель голоса")
        onProgress(100)
    }
}
