package com.vornex.assistant

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Нейросеть на телефоне (LiteRT-LM). Файл модели: model.litertlm. */
object LlmEngine {
    @Volatile var status: String = "не загружена"
    private var engine: Engine? = null

    fun modelFile(ctx: Context) = File(ctx.filesDir, "model.litertlm")
    fun isInstalled(ctx: Context) = modelFile(ctx).length() > 0
    val ready: Boolean get() = engine != null

    @Synchronized
    fun load(ctx: Context) {
        if (engine != null || !isInstalled(ctx)) return
        status = "просыпается…"
        try {
            val e = Engine(
                EngineConfig(
                    modelPath = modelFile(ctx).path,
                    backend = Backend.CPU(),
                    cacheDir = ctx.cacheDir.path
                )
            )
            e.initialize()
            engine = e
            status = "готова"
        } catch (t: Throwable) {
            status = "ошибка: ${t.message}"
        }
    }

    @Synchronized
    fun unload() {
        try {
            engine?.close()
        } catch (_: Throwable) {
        }
        engine = null
        status = "не загружена"
    }

    /** Блокирующая генерация. Готовые предложения сразу отдаёт в onSentence (чтобы озвучивать по ходу). */
    fun generate(prompt: String, onSentence: (String) -> Unit) {
        val e = engine ?: return
        val buf = StringBuilder()
        e.createConversation(ConversationConfig()).use { conv ->
            runBlocking {
                withTimeoutOrNull(90_000L) {
                    conv.sendMessageAsync(prompt).collect { chunk ->
                        buf.append(chunk.toString())
                        flush(buf, onSentence, false)
                    }
                }
            }
        }
        flush(buf, onSentence, true)
    }

    private val sentenceEnd = Regex("[.!?…]+\\s")

    private fun clean(s: String) = s.replace(Regex("[*#_`>\\n]+"), " ").replace(Regex("\\s+"), " ").trim()

    private fun flush(buf: StringBuilder, out: (String) -> Unit, force: Boolean) {
        while (true) {
            val m = sentenceEnd.find(buf) ?: break
            val end = m.range.last + 1
            val s = clean(buf.substring(0, end))
            buf.delete(0, end)
            if (s.length > 1) out(s)
        }
        if (force) {
            val s = clean(buf.toString())
            buf.clear()
            if (s.isNotEmpty()) out(s)
        }
    }
}
