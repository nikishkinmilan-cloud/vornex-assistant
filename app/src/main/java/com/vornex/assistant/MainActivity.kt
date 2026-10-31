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
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var heardView: TextView
    private lateinit var toggleBtn: Button
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var downloading = false
    @Volatile private var dlText = ""

    private val tick = object : Runnable {
        override fun run() {
            statusView.text = if (downloading) dlText else VornexState.status
            heardView.text = if (VornexState.lastHeard.isEmpty()) "" else "Услышал: ${VornexState.lastHeard}"
            toggleBtn.text = if (VornexState.running) "■ Остановить" else "▶ Запустить"
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Vornex"
            textSize = 32f
        })
        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 4)
        }
        heardView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, pad / 2)
        }
        toggleBtn = Button(this).apply { setOnClickListener { toggle() } }
        val overlayBtn = Button(this).apply {
            text = "1. Разрешить поверх других окон"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
        val batteryBtn = Button(this).apply {
            text = "2. Не ограничивать батарею"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        }
        val a11yBtn = Button(this).apply {
            text = "3. Включить управление (Vornex в спец. возможностях)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(statusView)
        root.addView(heardView)
        root.addView(overlayBtn)
        root.addView(batteryBtn)
        root.addView(a11yBtn)
        root.addView(toggleBtn)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    private fun toggle() {
        if (VornexState.running) {
            stopService(Intent(this, VornexService::class.java))
            return
        }
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)
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
        if (downloading) return
        downloading = true
        dlText = "Скачиваю голосовую модель (около 45 МБ): 0%"
        Thread {
            try {
                ModelStore.download(applicationContext) { dlText = "Скачиваю голосовую модель (около 45 МБ): $it%" }
                runOnUiThread {
                    downloading = false
                    startVornex()
                }
            } catch (e: Exception) {
                downloading = false
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
}
