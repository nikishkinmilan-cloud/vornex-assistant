package com.vornex.assistant

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Скачивает и хранит офлайн-модель русской речи Vosk (около 45 МБ). */
object ModelStore {
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    private const val DIR_NAME = "vosk-model-small-ru-0.22"

    fun modelDir(ctx: Context) = File(ctx.filesDir, "models/$DIR_NAME")

    fun isReady(ctx: Context) = File(modelDir(ctx), "am").isDirectory

    private class Counting(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }
    }

    /** Блокирующая загрузка, вызывать из фонового потока. */
    fun download(ctx: Context, onProgress: (Int) -> Unit) {
        val tmp = File(ctx.filesDir, "models_tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()

        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        val total = conn.contentLengthLong.coerceAtLeast(1)
        val counting = Counting(BufferedInputStream(conn.inputStream))
        val root = tmp.canonicalPath + File.separator

        ZipInputStream(counting).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(tmp, entry.name)
                if (!out.canonicalPath.startsWith(root)) throw SecurityException("bad zip entry")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                onProgress((counting.count * 100 / total).toInt().coerceAtMost(99))
                entry = zis.nextEntry
            }
        }

        val target = File(ctx.filesDir, "models")
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw IllegalStateException("Не удалось сохранить модель")
        onProgress(100)
    }
}
