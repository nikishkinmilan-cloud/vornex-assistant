package com.vornex.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import org.vosk.Model

/** Фоновый сервис: слушает микрофон, ждёт слово «Боря», выполняет команду или отвечает на вопрос. */
class VornexService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var mic: MicEngine? = null
    private var speaker: Speaker? = null
    private val speaking = HashSet<String>() // только из главного потока
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlay: View? = null
    private var awaitingUntil = 0L
    private var followUpPending = false
    private lateinit var router: CommandRouter

    private companion object {
        const val FOLLOW_UP_MS = 8000L
    }

    // Слово активации «Боря». Смотри строку «Услышал:» на экране, если не срабатывает.
    private val wakeRegex = Regex("(?<![а-я])б[оа]\\s?р[яьюие](?![а-я])")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val brain = Brain(applicationContext, { say(it) }, { sayQueued(it) }, { requestFollowUp() })
        router = CommandRouter(applicationContext, { say(it) }, brain, { requestFollowUp() })
        startAsForeground()

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vornex:listen")
            .apply { acquire() }

        addOverlay()

        speaker = Speaker(this) { id -> finished(id) }

        VornexState.running = true
        VornexState.status = "Загружаю модель…"
        Thread { loadModelAndListen() }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("vornex", "Vornex", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "vornex")
            .setContentTitle("Боря слушает")
            .setContentText("Скажи: «Боря, открой тикток»")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (LocationHelper.hasPermission(this)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(1, n, type)
        } else {
            startForeground(1, n)
        }
    }

    // Невидимое окно 1x1 пиксель. Без него новые Android не дают запускать приложения из фона.
    private fun addOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        try {
            val v = View(this)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp)
            overlay = v
        } catch (_: Exception) {
        }
    }

    private fun loadModelAndListen() {
        try {
            model = Model(ModelStore.modelDir(this).absolutePath)
            val spkModel = try {
                SpkStore.modelDir(this)?.let { org.vosk.SpeakerModel(it.absolutePath) }
            } catch (_: Throwable) { null }
            mic = MicEngine(model!!, spkModel, { t, v -> process(t, v) }, { VornexState.status = it })
            mic?.start()
            VornexState.status = "Слушаю. Скажи: «Боря, …»"
            say(router.hello())
        } catch (e: Exception) {
            VornexState.status = "Ошибка модели: ${e.message}"
        }
        // нейросеть грузится дольше всего, поэтому отдельно и после запуска слуха
        LlmEngine.load(applicationContext)
    }

    // ---- Распознавание ----

    private fun process(text: String, spk: FloatArray?) {
        if (text.isBlank()) return
        VornexState.lastHeard = text

        // Запись голоса хозяина: берём фразы от трёх слов
        if (VoicePrint.enrolling) {
            if (spk != null && text.trim().split(" ").size >= 3) {
                val done = VoicePrint.addSample(this, spk)
                if (done) say("Запомнил ваш голос, хозяин. Теперь я слушаюсь только вас")
                else say("Принял. Ещё ${VoicePrint.NEED - VoicePrint.collected}")
            }
            return
        }

        val now = SystemClock.elapsedRealtime()
        val m = wakeRegex.find(text)
        if ((m != null || now < awaitingUntil) && spk != null && VoicePrint.active(this) &&
            text.trim().split(" ").size >= 2
        ) {
            val d = VoicePrint.distance(this, spk)
            VoicePrint.lastDist = d
            if (d > VoicePrint.THRESHOLD) {
                VornexState.lastHeard = "$text  [чужой голос: %.2f]".format(d)
                return
            }
            VornexState.lastHeard = "$text  [голос %.2f]".format(d)
        }
        when {
            m != null -> {
                val rest = text.substring(m.range.last + 1).trim().trimStart(',', ' ')
                if (rest.isEmpty()) {
                    awaitingUntil = now + 8000 // сказал только имя: ждём команду 8 секунд
                    say(router.listening())
                } else {
                    router.handle(rest)
                }
            }
            now < awaitingUntil -> {
                awaitingUntil = 0
                router.handle(text)
            }
            // Без слова «Боря» ничего не обрабатывается и нигде не сохраняется.
        }
    }

    // ---- Озвучка (на время речи микрофон на паузе, чтобы не слышать самого себя) ----

    private fun say(t: String) = speak(t, true)
    private fun sayQueued(t: String) = speak(t, false)

    private fun speak(t: String, flush: Boolean) {
        val text = t.trim()
        if (text.isEmpty()) return
        handler.post {
            mic?.setPause(true)
            val id = "v" + System.nanoTime()
            speaking.add(id)
            speaker?.speak(text, flush, id)
        }
    }

    private fun finished(id: String?) {
        handler.post {
            speaking.remove(id)
            if (speaking.isEmpty()) {
                mic?.setPause(false)
                if (followUpPending) {
                    followUpPending = false
                    awaitingUntil = SystemClock.elapsedRealtime() + FOLLOW_UP_MS
                }
            }
        }
    }

    // Режим диалога: после ответа 8 секунд можно говорить без слова «Боря».
    // Окно начинается, когда Боря договорил.
    private fun requestFollowUp() {
        handler.post {
            if (speaking.isEmpty()) {
                awaitingUntil = SystemClock.elapsedRealtime() + FOLLOW_UP_MS
            } else {
                followUpPending = true
            }
        }
    }

    override fun onDestroy() {
        mic?.stop()
        speaker?.shutdown()
        model?.close()
        WikiDb.close()
        PlacesDb.close()
        LlmEngine.unload()
        NeuralVoice.unload()
        overlay?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {
            }
        }
        wakeLock?.release()
        VornexState.running = false
        VornexState.status = "Остановлен"
        super.onDestroy()
    }
}
