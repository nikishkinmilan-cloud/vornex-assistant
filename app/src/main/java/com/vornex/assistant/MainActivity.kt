package com.vornex.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var heardView: TextView
    private lateinit var infoView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var voiceBtn: Button
    private val ui = Handler(Looper.getMainLooper())

    // Что-то качается или копируется прямо сейчас (null, если ничего).
    @Volatile private var busyText: String? = null
    private var pendingTarget: String? = null
    private var tts: TextToSpeech? = null

    private val tick = object : Runnable {
        override fun run() {
            statusView.text = busyText ?: VornexState.status
            heardView.text = if (VornexState.lastHeard.isEmpty()) "" else "Услышал: ${VornexState.lastHeard}"
            toggleBtn.text = if (VornexState.running) "■ Остановить" else "▶ Запустить"
            infoView.text =
                "Википедия: ${if (WikiDb.available(this@MainActivity)) "есть" else "нет"}\n" +
                    "Места: ${if (PlacesDb.available(this@MainActivity)) "есть" else "нет"}\n" +
                    "Нейросеть: ${if (LlmEngine.isInstalled(this@MainActivity)) LlmEngine.status else "нет файла"}"
            ui.postDelayed(this, 500)
        }
    }

    private fun btn(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Vornex · Боря"
            textSize = 30f
        })
        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 4)
        }
        heardView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, pad / 4)
        }
        infoView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, pad / 2)
        }
        toggleBtn = btn("") { toggle() }
        voiceBtn = btn("Сменить голос") { cycleVoice() }

        root.addView(statusView)
        root.addView(heardView)
        root.addView(infoView)
        root.addView(toggleBtn)
        root.addView(btn("1. Разрешить поверх других окон") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("2. Не ограничивать батарею") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        root.addView(btn("3. Включить управление (Vornex в спец. возможностях)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("4. Википедия: выбрать wiki.db") { pick("wiki.db") })
        root.addView(btn("5. Места: выбрать places.db") { pick("places.db") })
        root.addView(btn("6. Нейросеть: выбрать файл .litertlm") { pick("model.litertlm") })
        root.addView(voiceBtn)

        setContentView(ScrollView(this).apply { addView(root) })

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ru", "RU")
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- Запуск и разрешения ----------

    private fun toggle() {
        if (VornexState.running) {
            stopService(Intent(this, VornexService::class.java))
            return
        }
        val need = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else launch()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            launch()
        } else {
            VornexState.status = "Нужен доступ к микрофону"
        }
    }

    private fun launch() {
        if (ModelStore.isReady(this)) {
            startVornex()
            return
        }
        if (busyText != null) return
        busyText = "Скачиваю голосовую модель (около 45 МБ): 0%"
        Thread {
            try {
                ModelStore.download(applicationContext) { busyText = "Скачиваю голосовую модель (около 45 МБ): $it%" }
                busyText = null
                runOnUiThread { startVornex() }
            } catch (e: Exception) {
                busyText = null
                VornexState.status = "Ошибка загрузки модели: ${e.message}"
            }
        }.start()
    }

    private fun startVornex() {
        try {
            startForegroundService(Intent(this, VornexService::class.java))
        } catch (e: Exception) {
            VornexState.status = "Не удалось запустить: ${e.message}. Нажми «Запустить» ещё раз."
        }
    }

    // ---------- Выбор файлов (Википедия, места, нейросеть) ----------

    private fun pick(target: String) {
        if (busyText != null) return
        pendingTarget = target
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            },
            REQ_PICK
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val target = pendingTarget ?: return
            importFile(uri, target)
        }
    }

    private fun importFile(uri: Uri, targetName: String) {
        busyText = "Копирую $targetName: 0%"
        Thread {
            try {
                val tmp = File(filesDir, "$targetName.part")
                val total = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
                contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var done = 0L
                        var lastPct = -1L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) done * 100 / total else done / (1 shl 20)
                            if (pct != lastPct) {
                                lastPct = pct
                                busyText = if (total > 0) "Копирую $targetName: $pct%" else "Копирую $targetName: $pct МБ"
                            }
                        }
                    }
                }
                when (targetName) {
                    "wiki.db" -> WikiDb.close()
                    "places.db" -> PlacesDb.close()
                    else -> LlmEngine.unload()
                }
                val dst = File(filesDir, targetName)
                dst.delete()
                if (!tmp.renameTo(dst)) throw IllegalStateException("не удалось сохранить файл")
                busyText = null
                VornexState.status = "$targetName загружен. Остановите и снова запустите Vornex"
            } catch (e: Exception) {
                busyText = null
                VornexState.status = "Ошибка копирования: ${e.message}"
            }
        }.start()
    }

    // ---------- Голос ----------

    private fun cycleVoice() {
        val t = tts ?: return
        val ru = t.voices?.filter { it.locale.language == "ru" }?.sortedBy { it.name } ?: emptyList()
        if (ru.isEmpty()) {
            VornexState.status = "Русских голосов не нашёл. Установите «Службу распознавания и синтеза речи» от Google"
            return
        }
        val prefs = getSharedPreferences("vornex", MODE_PRIVATE)
        val idx = ru.indexOfFirst { it.name == prefs.getString("voice", null) }
        val next = ru[(idx + 1) % ru.size]
        prefs.edit().putString("voice", next.name).apply()
        t.voice = next
        t.setPitch(0.9f)
        t.speak("Есть, сэр. Вот так я теперь звучу.", TextToSpeech.QUEUE_FLUSH, null, "demo")
        voiceBtn.text = "Голос: ${next.name} (нажми, чтобы сменить)"
    }

    private companion object {
        const val REQ_PICK = 7
    }
}
