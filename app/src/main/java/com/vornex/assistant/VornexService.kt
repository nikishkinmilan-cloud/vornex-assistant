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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.Locale

/** Фоновый сервис: слушает микрофон, ждёт слово «Боря», выполняет команду или отвечает на вопрос. */
class VornexService : Service(), RecognitionListener {

    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voiceName: String? = null
    private val speaking = HashSet<String>() // только из главного потока
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlay: View? = null
    private var awaitingUntil = 0L
    private lateinit var router: CommandRouter

    // Слово активации «Боря». Смотри строку «Услышал:» на экране, если не срабатывает.
    private val wakeRegex = Regex("(?<![а-я])бо\\s?р[яьюи](?![а-я])")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val brain = Brain(applicationContext, { say(it) }, { sayQueued(it) })
        router = CommandRouter(applicationContext, { say(it) }, brain)
        startAsForeground()

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vornex:listen")
            .apply { acquire() }

        addOverlay()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                tts?.setPitch(0.9f)
                tts?.setSpeechRate(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        finished(utteranceId)
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(utteranceId: String?) {
                        finished(utteranceId)
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        finished(utteranceId)
                    }
                })
                ttsReady = true
            }
        }

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
            val rec = Recognizer(model, 16000f)
            speech = SpeechService(rec, 16000f)
            speech?.startListening(this)
            VornexState.status = "Слушаю. Скажи: «Боря, …»"
            say(router.hello())
        } catch (e: Exception) {
            VornexState.status = "Ошибка модели: ${e.message}"
        }
        // нейросеть грузится дольше всего, поэтому отдельно и после запуска слуха
        LlmEngine.load(applicationContext)
    }

    // ---- Распознавание ----

    override fun onResult(hypothesis: String?) = process(hypothesis)
    override fun onFinalResult(hypothesis: String?) = process(hypothesis)
    override fun onPartialResult(hypothesis: String?) {}
    override fun onTimeout() {}
    override fun onError(exception: Exception?) {
        VornexState.status = "Ошибка: ${exception?.message}"
    }

    private fun process(json: String?) {
        val text = try {
            JSONObject(json ?: return).optString("text")
        } catch (_: Exception) {
            return
        }
        if (text.isBlank()) return
        VornexState.lastHeard = text

        val now = SystemClock.elapsedRealtime()
        val m = wakeRegex.find(text)
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
            if (!ttsReady) return@post
            applyVoice()
            speech?.setPause(true)
            val id = "v" + System.nanoTime()
            speaking.add(id)
            val r = tts?.speak(
                text,
                if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                id
            )
            if (r != TextToSpeech.SUCCESS) finished(id)
        }
    }

    private fun finished(id: String?) {
        handler.post {
            speaking.remove(id)
            if (speaking.isEmpty()) speech?.setPause(false)
        }
    }

    // Голос выбирается в приложении кнопкой «Сменить голос».
    private fun applyVoice() {
        val want = getSharedPreferences("vornex", MODE_PRIVATE).getString("voice", null)
        if (want == voiceName) return
        val v = tts?.voices?.firstOrNull { it.name == want }
        if (v != null) {
            tts?.voice = v
            voiceName = want
        }
    }

    override fun onDestroy() {
        speech?.stop()
        speech?.shutdown()
        model?.close()
        tts?.shutdown()
        WikiDb.close()
        PlacesDb.close()
        LlmEngine.unload()
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
