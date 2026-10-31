package com.vornex.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** Срабатывание будильника: короткий звонок, затем Боря говорит сообщение вслух несколько раз. */
class WakeService : Service() {

    companion object {
        private const val STOP = "vornex.STOP"

        fun start(ctx: Context, kind: String, msg: String) {
            val i = Intent(ctx, WakeService::class.java).putExtra("kind", kind).putExtra("msg", msg)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, WakeService::class.java).setAction(STOP))
        }
    }

    private val h = Handler(Looper.getMainLooper())
    private var speaker: Speaker? = null
    private var wl: PowerManager.WakeLock? = null
    private var ringtone: android.media.Ringtone? = null
    private var repeats = 0
    private var text = ""
    private var kind = "alarm"
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            finish()
            return START_NOT_STICKY
        }
        kind = intent?.getStringExtra("kind") ?: "alarm"
        val msg = intent?.getStringExtra("msg") ?: ""
        text = compose(kind, msg)
        goForeground()
        wl = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vornex:wake").apply { acquire(5 * 60_000L) }
        if (NeuralVoice.isInstalled(this)) NeuralVoice.load(this)
        speaker = Speaker(this, alarmStream = true) { }
        if (kind == "alarm") ring()
        // дать синтезатору прогреться, затем говорить
        h.postDelayed({ say() }, if (kind == "alarm") 6000 else 1500)
        return START_NOT_STICKY
    }

    private fun compose(kind: String, msg: String): String = when {
        msg.lowercase().contains("хозяин") -> msg
        kind == "alarm" -> if (msg.isBlank()) "Хозяин, вставайте! Новый день уже начался." else "Хозяин, вставайте! $msg"
        kind == "timer" -> "Хозяин, таймер сработал!"
        else -> "Хозяин, напоминаю: $msg"
    }

    private fun ring() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                play()
            }
            h.postDelayed({ ringtone?.stop() }, 5000)
        } catch (_: Exception) {
        }
    }

    private fun say() {
        if (stopped) return
        speaker?.speak(text, true, "wake${repeats}")
        repeats++
        val max = if (kind == "alarm") 6 else 3
        if (repeats >= max) h.postDelayed({ finish() }, 20_000) else h.postDelayed({ say() }, 25_000)
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("vornex_wake", "Будильник Бори", NotificationManager.IMPORTANCE_HIGH))
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, WakeService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "vornex_wake")
            .setContentTitle("Боря")
            .setContentText(text.ifBlank { "Будильник" })
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Стоп", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(2, n)
        }
    }

    private fun finish() {
        stopped = true
        h.removeCallbacksAndMessages(null)
        try { ringtone?.stop() } catch (_: Exception) {}
        speaker?.shutdown()
        speaker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        h.removeCallbacksAndMessages(null)
        speaker?.shutdown()
        try { wl?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
