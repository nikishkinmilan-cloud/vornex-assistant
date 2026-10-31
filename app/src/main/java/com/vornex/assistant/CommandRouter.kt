package com.vornex.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.AlarmClock
import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Разбирает фразу после слова «Боря», выполняет команду или передаёт вопрос мозгу. */
class CommandRouter(
    private val ctx: Context,
    private val say: (String) -> Unit,
    private val brain: Brain,
    private val followUp: () -> Unit      // открыть окно «можно отвечать без слова Боря»
) {

    private val ru = Locale("ru")
    private var torchOn = false

    // Ожидание подтверждения («Отправить? Скажите да»)
    private var pending: (() -> Unit)? = null
    private var pendingUntil = 0L

    // ---------- Характер Боря. Меняй и добавляй свои фразы ----------

    private fun ack() = listOf(
        "Есть, сэр", "Так точно, сэр", "Слушаюсь, сэр", "Хорошо, хозяин", "Будет сделано, хозяин",
        "Сию секунду, сэр", "Уже делаю, сэр", "Как прикажете, сэр"
    ).random()

    fun hello() = listOf(
        "Боря на связи, сэр",
        "Я проснулся, хозяин. Чем займёмся?",
        "К вашим услугам, сэр. Я весь внимание"
    ).random()

    fun listening() = listOf("Слушаю, сэр", "Да, хозяин?", "К вашим услугам", "Я весь внимание, сэр").random()

    private val jokes = listOf(
        "Почему программисты путают Хэллоуин и Рождество? Потому что тридцать первое октября равно двадцать пятому декабря.",
        "Сколько нужно программистов, чтобы вкрутить лампочку? Ни одного. Это аппаратная проблема.",
        "Говорят, искусственный интеллект скоро захватит мир. Но пока я даже не могу закрыть вкладки в Хроме.",
        "Я не ленивый, хозяин. Я просто работаю в режиме энергосбережения.",
        "Почему телефон не пошёл на вечеринку? У него сел заряд общения.",
        "Заходит бесконечное число программистов в бар. Первый просит пиво, второй полпива, третий четверть. Бармен говорит: вот вам два пива, и разбирайтесь сами."
    )

    // Как человек говорит -> что искать в названии или пакете приложения. Дополняй сам.
    private val aliases = mapOf(
        "тикток" to listOf("tiktok"),
        "ютуб" to listOf("youtube"),
        "ютьюб" to listOf("youtube"),
        "ватсап" to listOf("whatsapp"),
        "вотсап" to listOf("whatsapp"),
        "телеграм" to listOf("telegram"),
        "телега" to listOf("telegram"),
        "инстаграм" to listOf("instagram"),
        "хром" to listOf("chrome"),
        "браузер" to listOf("chrome", "browser", "браузер"),
        "камера" to listOf("camera", "камера"),
        "настройки" to listOf("settings", "настройки"),
        "карты" to listOf("maps", "карты"),
        "галерея" to listOf("gallery", "галерея", "photos", "фото"),
        "калькулятор" to listOf("calculator", "калькулятор"),
        "контакты" to listOf("contacts", "контакты"),
        "часы" to listOf("clock", "часы"),
        "почта" to listOf("gmail", "mail", "почта"),
        "плеймаркет" to listOf("play store", "vending"),
        "майнкрафт" to listOf("minecraft"),
        "роблокс" to listOf("roblox"),
    )

    // ---------- Разбор команд ----------

    fun handle(raw: String) {
        val t = raw.lowercase(ru).replace('ё', 'е').trim()
        if (checkPending(t)) return

        val open = Regex("^(открой|открыть|запусти|запустить)\\s+(.+)$").find(t)
        val call = Regex("^(позвони|набери|вызови)\\s+(.+)$").find(t)
        val web = Regex("^(найди|загугли|поищи)\\s+(?:в интернете\\s+|в гугле\\s+|в google\\s+)?(.+)$").find(t)
        val msg = if (Regex("^(напиши|отправь|пошли|скинь)\\s").containsMatchIn(t)) parseMessage(t) else null
        val note = Regex("^(запиши|создай|добавь)\\s+(?:мне\\s+)?заметк[уиа]\\s+(.+)$").find(t)
        val remember = Regex("^(запомни|запиши в память)\\s+(?:что\\s+)?(.+)$").find(t)

        when {
            t.isEmpty() -> say(listening())
            open != null -> openApp(open.groupValues[2])
            msg != null -> whatsapp(msg.first, msg.second)
            call != null -> callContact(call.groupValues[2])
            web != null -> webSearch(web.groupValues[2])

            t.contains("будильник") || t.startsWith("разбуди") -> alarm(t)
            t.contains("таймер") -> timer(t)
            t.startsWith("напомни") -> remind(t)

            remember != null -> {
                Store.add(ctx, Store.MEMORY, remember.groupValues[2])
                say(ack() + ". Запомнил")
            }
            t.contains("что ты") && (t.contains("помнишь") || t.contains("знаешь обо мне")) -> readMemory()
            t.contains("забудь все") || t.contains("очисти память") ->
                confirm("Стереть всю память о вас? Скажите да") {
                    Store.clear(ctx, Store.MEMORY)
                    say("Память очищена, сэр")
                }
            note != null -> {
                Store.add(ctx, Store.NOTES, SimpleDateFormat("d.MM HH:mm", ru).format(Date()) + " " + note.groupValues[2])
                say(ack() + ". Записал")
            }
            t.contains("заметк") && (t.contains("удали") || t.contains("очисти")) ->
                confirm("Удалить все заметки? Скажите да") {
                    Store.clear(ctx, Store.NOTES)
                    say("Заметки удалены, сэр")
                }
            t.contains("заметк") && (t.contains("прочита") || t.contains("зачита") || t.contains("какие") || t.contains("покажи")) ->
                readNotes()

            Regex("закро[а-я]*\\s+вс[её]").containsMatchIn(t) -> closeAll(t.contains("вкладк"))
            t == "домой" || t.contains("главный экран") -> withA11y { it.home(); say(ack()) }
            t == "назад" || t == "вернись" -> withA11y { it.back() }
            t.contains("заблокируй") || t.contains("выключи экран") -> withA11y { say(ack()); it.lockScreen() }
            t.contains("скриншот") || t.contains("снимок экрана") -> withA11y { say(ack()); it.screenshot() }

            t.contains("пауза") || t.contains("на паузу") || t.contains("останови музыку") ->
                media(KeyEvent.KEYCODE_MEDIA_PAUSE)
            t.contains("включи музыку") || t.contains("продолжи") || t.contains("играй") ->
                media(KeyEvent.KEYCODE_MEDIA_PLAY)
            (t.contains("следующ") && (t.contains("трек") || t.contains("песн") || t.contains("музык") || t.contains("композиц"))) ||
                t == "дальше" || t == "следующий" || t == "следующая" ->
                media(KeyEvent.KEYCODE_MEDIA_NEXT)
            t.contains("предыдущ") || t.contains("прошлый трек") || t.contains("прошлая песня") ->
                media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

            t.contains("фонар") -> torch(t)
            t.contains("сколько времени") || t.contains("который час") || t == "время" -> sayTime()
            t.contains("какое число") || t.contains("какая дата") || t.contains("какой сегодня день") -> sayDate()
            t.contains("громче") -> volume(AudioManager.ADJUST_RAISE)
            t.contains("тише") -> volume(AudioManager.ADJUST_LOWER)

            t.contains("анекдот") || t.contains("шутк") -> say(jokes.random())
            t.contains("как дела") -> say(
                listOf(
                    "Дела идут как по маслу, сэр. Заряд бодрости сто процентов",
                    "Отлично, хозяин. Жду приказов и никуда не тороплюсь",
                    "Работаю без выходных и без жалоб, сэр"
                ).random()
            )
            t.contains("спасибо") -> say(listOf("Всегда пожалуйста, сэр", "Обращайтесь, хозяин", "Рад стараться").random())
            t.startsWith("привет") -> say(listOf("Приветствую, сэр!", "Доброго времени суток, хозяин", "О, хозяин! Рад слышать").random())
            t.contains("кто ты") || t.contains("как тебя зовут") ->
                say("Я Боря, ваш личный помощник. Не для продажи, только для хозяина")

            else -> {
                val r = Calc.tryEvaluate(t)
                if (r != null) say("Получается " + RuNumbers.format(r)) else brain.answer(t)
            }
        }
    }

    // ---------- Подтверждение ----------

    private fun confirm(prompt: String, action: () -> Unit) {
        pending = action
        pendingUntil = SystemClock.elapsedRealtime() + 20_000
        say(prompt)
        followUp()
    }

    /** true, если фраза была ответом на вопрос подтверждения. */
    private fun checkPending(t: String): Boolean {
        val p = pending ?: return false
        pending = null
        if (SystemClock.elapsedRealtime() > pendingUntil) return false
        if (Regex("^(да|давай|отправляй|отправь|конечно|угу|ага|подтверждаю|валяй|так точно)(\\s|$)").containsMatchIn(t)) {
            p()
            return true
        }
        if (Regex("^(нет|не надо|не нужно|отмена|отмени|стоп|не отправляй)(\\s|$)").containsMatchIn(t)) {
            say("Отменил, сэр")
            return true
        }
        return false
    }

    private fun start(i: Intent) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            say("Не получилось, сэр")
        }
    }

    // ---------- Будильник, таймер, напоминания (через системные «Часы», работают без интернета) ----------

    private fun setAlarm(hour: Int, minute: Int, label: String?) {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!label.isNullOrBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        start(i)
    }

    private fun setTimer(seconds: Long, label: String?) {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!label.isNullOrBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        start(i)
    }

    private fun alarm(t: String) {
        val toks = RuNumbers.tokenize(t)
        val c = RuNumbers.parseClockAt(toks)
        if (c == null) {
            say("На какое время поставить, сэр?")
            return
        }
        setAlarm(c.hour, c.minute, null)
        say(ack() + ". Будильник на %d:%02d".format(c.hour, c.minute))
    }

    private fun timer(t: String) {
        val toks = RuNumbers.tokenize(t)
        val d = RuNumbers.parseDuration(toks, 0)
        if (d == null || d.seconds <= 0) {
            say("На сколько поставить таймер, сэр?")
            return
        }
        setTimer(d.seconds, null)
        say(ack() + ". Таймер на " + RuNumbers.humanDuration(d.seconds))
    }

    private fun cleanLabel(s: String) =
        s.replace(Regex("^(мне\\s+)?(о том\\s+)?(что\\s+|чтобы\\s+|про\\s+)?"), "").trim()

    private fun remind(t: String) {
        val toks = RuNumbers.tokenize(t)
        val through = toks.indexOfFirst { it is RuNumbers.Tok.W && it.s == "через" }
        if (through >= 0) {
            val d = RuNumbers.parseDuration(toks, through + 1)
            if (d != null && d.seconds > 0) {
                val label = cleanLabel(RuNumbers.textFrom(toks, d.end))
                setTimer(d.seconds, label.ifBlank { "Напоминание" })
                say(ack() + ". Напомню через " + RuNumbers.humanDuration(d.seconds))
                return
            }
        }
        val c = RuNumbers.parseClockAt(toks)
        if (c != null) {
            val label = cleanLabel(RuNumbers.textFrom(toks, c.end))
            setAlarm(c.hour, c.minute, label.ifBlank { "Напоминание" })
            say(ack() + ". Напомню в %d:%02d".format(c.hour, c.minute))
            return
        }
        say("Когда напомнить, сэр? Скажите, например: напомни через десять минут позвонить маме")
    }

    // ---------- Заметки и память ----------

    private fun readNotes() {
        val notes = Store.read(ctx, Store.NOTES)
        if (notes.isEmpty()) {
            say("Заметок нет, сэр")
            return
        }
        val text = notes.takeLast(5).joinToString(". ") { it.replace(Regex("^\\d+\\.\\d+ \\d+:\\d+ "), "") }
        say("Заметок: ${notes.size}. Последние: $text")
    }

    private fun readMemory() {
        val facts = Store.read(ctx, Store.MEMORY)
        if (facts.isEmpty()) {
            say("Пока ничего не помню, сэр. Скажите: запомни, и что именно")
            return
        }
        say("Я помню: " + facts.takeLast(8).joinToString(". "))
    }

    // ---------- Музыка ----------

    private fun media(code: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        say(ack())
    }

    // ---------- Системные действия (нужны «специальные возможности») ----------

    private fun withA11y(block: (VornexAccessibility) -> Unit) {
        val s = VornexAccessibility.instance
        if (s == null) {
            say("Мне нужны специальные возможности, сэр. Включите их в приложении, кнопка номер три")
            return
        }
        block(s)
    }

    private fun closeAll(tabs: Boolean) = withA11y { s ->
        say(
            if (tabs) "Вкладки внутри Хрома мне не подчиняются, сэр. Зато закрою все приложения"
            else ack() + ". Зачищаю всё"
        )
        s.closeAll { ok -> if (!ok) say("Кнопку не нашёл, сэр. Просто ушёл на главный экран") }
    }

    // ---------- Приложения, звонки, сообщения, фонарик ----------

    private fun openApp(nameRaw: String) {
        val name = nameRaw.trim()
        val key = name.replace(" ", "")
        val candidates = (listOf(name, key) + (aliases[key] ?: emptyList())).filter { it.isNotBlank() }
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)

        fun label(ri: ResolveInfo) = ri.loadLabel(pm).toString().lowercase(ru)

        val hit = apps.firstOrNull { ri -> candidates.any { label(ri) == it } }
            ?: apps.firstOrNull { ri ->
                val pkg = ri.activityInfo.packageName.lowercase()
                candidates.any { label(ri).contains(it) || pkg.contains(it) }
            }

        val intent = hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (intent == null) {
            say("Не нашёл приложение $name, сэр. Может, оно просто скромное и прячется?")
            return
        }
        say(ack() + ". Открываю $name")
        start(intent)
    }

    private fun callContact(nameRaw: String) {
        if (!Contacts.hasPermission(ctx)) {
            say("Нет доступа к контактам, сэр")
            return
        }
        val c = Contacts.find(ctx, nameRaw)
        if (c == null) {
            say("Не нашёл контакт ${nameRaw.trim()}, сэр")
            return
        }
        say(ack() + ". Звоню: ${c.name}")
        val canCall = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        start(Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(c.number))))
    }

    /** «напиши маме в ватсап привет» -> (контакт, "привет"). null, если контакт не найден. */
    private fun parseMessage(t: String): Pair<Contacts.Contact, String>? {
        val s = t
            .replace(Regex("\\bв\\s+(ватсапе|ватсап|вотсапе|вотсап|вацапе|вацап|whatsapp)\\b"), " ")
            .replace(Regex("\\b(сообщение|смс)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val m = Regex("^(напиши|отправь|пошли|скинь)\\s+(.+)$").find(s) ?: return null
        val words = m.groupValues[2].split(" ")
        for (k in 1..minOf(3, words.size - 1)) {
            val c = Contacts.find(ctx, words.take(k).joinToString(" "))
            if (c != null) return c to words.drop(k).joinToString(" ")
        }
        return null
    }

    private fun whatsapp(c: Contacts.Contact, text: String) {
        confirm("Пишу: ${c.name}. Текст: $text. Отправить? Скажите да") {
            val uri = Uri.parse("https://wa.me/${Contacts.waNumber(c.number)}?text=" + Uri.encode(text))
            say(ack() + ". Отправляю")
            if (!openWhatsApp(uri)) {
                say("Не получилось открыть WhatsApp, сэр")
                return@confirm
            }
            val a = VornexAccessibility.instance
            if (a != null) {
                a.clickSend { ok ->
                    say(if (ok) "Отправлено, сэр" else "Чат открыл, но кнопку отправки не нашёл. Нажмите сами")
                }
            } else {
                say("Чат открыт с текстом. Нажмите отправить сами. Чтобы я нажимал сам, включите управление, кнопка три")
            }
        }
    }

    private fun openWhatsApp(uri: Uri): Boolean {
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun torch(t: String) {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (id == null) {
                say("Вспышки нет, сэр")
                return
            }
            torchOn = when {
                t.contains("выключ") || t.contains("погаси") -> false
                t.contains("включ") || t.contains("зажги") || t.contains("вруби") -> true
                else -> !torchOn
            }
            cm.setTorchMode(id, torchOn)
            say(if (torchOn) "Да будет свет, сэр" else "Свет погашен")
        } catch (e: Exception) {
            say("Фонарик недоступен")
        }
    }

    // ---------- Время, громкость, поиск ----------

    private fun sayTime() {
        val c = Calendar.getInstance()
        val tail = listOf("", "", " Время летит, сэр", " Самое время что-нибудь сделать")
        say("Сейчас %d:%02d.".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) + tail.random())
    }

    private fun sayDate() {
        say("Сегодня " + SimpleDateFormat("d MMMM, EEEE", ru).format(Date()))
    }

    private fun volume(dir: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
    }

    private fun webSearch(query: String) {
        say(ack() + ". Ищу в интернете: $query")
        start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
    }
}
