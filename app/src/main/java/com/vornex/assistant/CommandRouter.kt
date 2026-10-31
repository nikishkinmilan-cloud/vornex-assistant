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
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
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
        "вацап" to listOf("whatsapp"),
        "ватцап" to listOf("whatsapp"),
        "вотцап" to listOf("whatsapp"),
        "уотсап" to listOf("whatsapp"),
        "вацапп" to listOf("whatsapp"),
        "вк" to listOf("vkontakte", "com.vk", "vk"),
        "вконтакте" to listOf("vkontakte", "com.vk", "vk"),
        "вкмузыка" to listOf("musicvk", "vk music", "vk"),
        "яндекс" to listOf("yandex"),
        "яндексмузыка" to listOf("ru.yandex.music"),
        "спотифай" to listOf("spotify"),
        "вайлдберриз" to listOf("wildberries", "wb"),
        "озон" to listOf("ozon"),
        "диск" to listOf("drive", "диск"),
        "файлы" to listOf("files", "файлы", "documents"),
        "диктофон" to listOf("recorder", "диктофон"),
        "музыка" to listOf("music", "музыка"),
        "магазин" to listOf("play store", "vending"),
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
            web != null && !t.contains("ближайш") -> webSearch(web.groupValues[2])

            t == "стоп" || t.startsWith("стоп ") || t.contains("хватит") || t.contains("отстань") ||
                t.contains("выключи будильник") || t.contains("я встал") || t.contains("проснулся") || t.contains("замолчи") -> {
                WakeService.stop(ctx)
                guide?.let { if (it.active) it.stop() }
                say(listOf("Молчу, сэр", "Доброе утро, хозяин", "Как скажете, сэр").random())
            }
            (t.contains("какие") || t.contains("покажи") || t.contains("список")) && (t.contains("будильник") || t.contains("напоминани")) -> listAlarms()
            (t.contains("отмени") || t.contains("удали") || t.contains("сними") || t.contains("отключи")) &&
                (t.contains("будильник") || t.contains("напоминани") || t.contains("таймер")) -> {
                val n = Alarms.cancelAll(ctx)
                say(if (n == 0) "Отменять нечего, сэр" else "Отменил, сэр. Всего $n")
            }
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
            t == "включи музыку" || t == "продолжи" || t == "играй" || t == "включи" ->
                media(KeyEvent.KEYCODE_MEDIA_PLAY)
            (t.contains("следующ") && (t.contains("трек") || t.contains("песн") || t.contains("музык") || t.contains("композиц"))) ||
                t == "дальше" || t == "следующий" || t == "следующая" ->
                media(KeyEvent.KEYCODE_MEDIA_NEXT)
            t.contains("предыдущ") || t.contains("прошлый трек") || t.contains("прошлая песня") ->
                media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

            t.contains("фонар") -> torch(t)

            Regex("^(включи|поставь|запусти|сыграй|найди и включи|найди песню|найди музыку|включи песню|хочу послушать|поищи песню)\\s+.+").containsMatchIn(t) &&
                !t.contains("вайфай") && !t.contains("блютуз") && !t.contains("экран") -> music(t)

            t.contains("заряд") || t.contains("батаре") -> battery()
            t.contains("вайфай") || t.contains("вай фай") || t.contains("wifi") || t.contains("интернет включи") ->
                settingsPanel("wifi")
            t.contains("блютуз") || t.contains("bluetooth") -> settingsPanel("bt")
            t.contains("режим полета") || t.contains("в самолете") -> settingsPanel("air")
            t.contains("яркост") -> settingsPanel("display")
            Regex("^(проведи|веди|отведи|доведи|покажи дорогу|найди дорогу)\\s+(?:меня\\s+|путь\\s+|дорогу\\s+)*(?:до|к|в|на)\\s+(.+)$").find(t) != null ->
                guideTo(Regex("^(проведи|веди|отведи|доведи|покажи дорогу|найди дорогу)\\s+(?:меня\\s+|путь\\s+|дорогу\\s+)*(?:до|к|в|на)\\s+(.+)$").find(t)!!.groupValues[2])
            Regex("^(где|найди)\\s+ближайш[а-я]*\\s+(.+)$").find(t) != null ->
                guideTo(Regex("^(где|найди)\\s+ближайш[а-я]*\\s+(.+)$").find(t)!!.groupValues[2], onlyTell = true)
            Regex("^(проложи маршрут|построй маршрут|проложи путь|проведи путь|построй путь|как доехать|как добраться|как пройти|веди меня|отвези меня|навигация|маршрут|путь)\\s+(?:до\\s+|в\\s+|к\\s+|на\\s+)?(.+)$").find(t) != null -> {
                val dest = Regex("^(проложи маршрут|построй маршрут|проложи путь|проведи путь|построй путь|как доехать|как добраться|как пройти|веди меня|отвези меня|навигация|маршрут|путь)\\s+(?:до\\s+|в\\s+|к\\s+|на\\s+)?(.+)$").find(t)!!.groupValues[2]
                say(ack() + ". Строю маршрут: $dest")
                start(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest))))
            }
            Regex("^(открой|зайди на|перейди на)\\s+(?:сайт|страницу)\\s+(.+)$").find(t) != null -> {
                val site = Regex("^(открой|зайди на|перейди на)\\s+(?:сайт|страницу)\\s+(.+)$").find(t)!!.groupValues[2]
                    .replace(" точка ", ".")
                say(ack() + ". Открываю сайт $site")
                val url = if (site.contains(".") && !site.contains(" ")) {
                    if (site.startsWith("http")) site else "https://$site"
                } else {
                    // «мне повезёт»: DuckDuckGo сразу переходит на первый найденный сайт
                    "https://duckduckgo.com/?q=" + Uri.encode("\\" + site + " сайт")
                }
                start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            t.contains("сфотографируй") || t.contains("сделай фото") || t.contains("сделай селфи") -> {
                say(ack())
                start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            }
            Regex("^(нажми|тапни|кликни)\\s+(?:на\\s+)?(.+)$").find(t) != null -> {
                val w = Regex("^(нажми|тапни|кликни)\\s+(?:на\\s+)?(.+)$").find(t)!!.groupValues[2]
                withA11y { a ->
                    if (a.clickByTexts(listOf(w))) say(ack()) else say("Не вижу на экране «$w», сэр")
                }
            }
            t.contains("прокрути вниз") || t.contains("листай вниз") || t == "вниз" || t.contains("пролистни вниз") ->
                withA11y { it.scroll(true) }
            t.contains("прокрути вверх") || t.contains("листай вверх") || t == "вверх" ->
                withA11y { it.scroll(false) }
            t.contains("что ты умеешь") || t.contains("что умеешь") || t == "помощь" || t.contains("твои команды") -> say(
                "Я умею: открывать приложения, звонить, писать в ватсап, включать музыку в ютубе и ВК, " +
                    "ставить будильники и напоминания, при которых я сам вас бужу голосом, заметки, " +
                    "запоминать факты о вас, считать, показывать заряд батареи, строить маршруты, вести вас пешком до ближайшего магазина без интернета, " +
                    "закрывать приложения, делать скриншоты, листать экран и нажимать кнопки по названию. " +
                    "А если подключены знания, отвечаю на вопросы и говорю, где вы находитесь"
            )
            t.contains("сколько времени") || t.contains("который час") || t == "время" -> sayTime()
            t.contains("какое число") || t.contains("какая дата") || t.contains("какой сегодня день") -> sayDate()
            t.contains("громче") -> volume(AudioManager.ADJUST_RAISE)
            t.contains("тише") -> volume(AudioManager.ADJUST_LOWER)

            t.contains("запомни мой голос") || t.contains("запомни голос") -> startEnroll()
            t.contains("забудь мой голос") || t.contains("отключи проверку голоса") || t.contains("выключи проверку голоса") -> {
                VoicePrint.setActive(ctx, false)
                say("Хорошо, сэр. Теперь я слушаюсь всех")
            }
            t.contains("включи проверку голоса") -> {
                if (VoicePrint.hasProfile(ctx)) {
                    VoicePrint.setActive(ctx, true)
                    say("Есть, сэр. Теперь отвечаю только вам")
                } else say("Сначала скажите: Боря, запомни мой голос")
            }
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

    // ---------- Будильник, таймер, напоминания: Боря сам говорит вслух ----------

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    /** Из остатка фразы достаёт, что сказать: «и скажи, что пора в школу». */
    private fun spokenPart(rest: String): String {
        var r = rest.trim().replace(Regex("^(утра|вечера|дня|ночи)\\s*"), "")
        val m = Regex("(?:скажи|говори|сообщи|напомни|объяви)\\s+(?:мне\\s+)?(?:что\\s+|чтобы\\s+)?(.+)$").find(r)
        r = m?.groupValues?.get(1) ?: r.replace(Regex("^(и|а|чтобы|что|мне)\\s+"), "")
        return r.trim()
    }

    private fun repeatOf(t: String) = when {
        t.contains("каждый день") || t.contains("ежедневно") || t.contains("каждое утро") || t.contains("каждый вечер") -> 1
        t.contains("по будням") || t.contains("в будни") || t.contains("рабочие дни") -> 2
        else -> 0
    }

    private fun repText(r: Int) = when (r) { 1 -> " Повторяю каждый день."; 2 -> " Повторяю по будням."; else -> "" }

    private fun alarm(t: String) {
        val toks = RuNumbers.tokenize(t)
        val c = RuNumbers.parseClockAt(toks)
        if (c == null) {
            say("На какое время поставить, сэр?")
            return
        }
        val msg = spokenPart(RuNumbers.textFrom(toks, c.end))
        val r = repeatOf(t)
        Alarms.addClock(ctx, c.hour, c.minute, r, "alarm", msg)
        say(ack() + ". Разбужу в %d:%02d.".format(c.hour, c.minute) + repText(r))
    }

    private fun timer(t: String) {
        val toks = RuNumbers.tokenize(t)
        val d = RuNumbers.parseDuration(toks, 0)
        if (d == null || d.seconds <= 0) {
            say("На сколько поставить таймер, сэр?")
            return
        }
        Alarms.addAfter(ctx, d.seconds, "timer", "")
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
                Alarms.addAfter(ctx, d.seconds, "remind", label.ifBlank { "время вышло" })
                say(ack() + ". Напомню через " + RuNumbers.humanDuration(d.seconds))
                return
            }
        }
        val c = RuNumbers.parseClockAt(toks)
        if (c != null) {
            val label = cleanLabel(RuNumbers.textFrom(toks, c.end))
            Alarms.addClock(ctx, c.hour, c.minute, repeatOf(t), "remind", label.ifBlank { "время пришло" })
            say(ack() + ". Напомню в %d:%02d".format(c.hour, c.minute))
            return
        }
        say("Когда напомнить, сэр? Скажите, например: напомни через десять минут позвонить маме")
    }

    private fun listAlarms() {
        val l = Alarms.all(ctx).sortedBy { it.at }
        if (l.isEmpty()) {
            say("Будильников и напоминаний нет, сэр")
            return
        }
        say("Всего ${l.size}. " + l.take(5).joinToString(". ") { Alarms.describe(it) })
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

    // ---------- Проводник без интернета ----------

    private var guide: Guide? = null

    private fun guideTo(what: String, onlyTell: Boolean = false) {
        if (!LocationHelper.hasPermission(ctx)) {
            say("Нет доступа к геолокации, сэр")
            return
        }
        if (!PoiDb.available(ctx)) {
            say("Офлайн-карты магазинов нет, сэр. Нажмите в приложении «Установить всё». А пока открою карты")
            start(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:mode=w&q=" + Uri.encode(what))))
            return
        }
        say("Ищу, сэр. Сначала определю, где вы")
        Thread {
            val loc = LocationHelper.fresh(ctx)
            if (loc == null) {
                say("Не поймал спутники, сэр. Выйдите на открытое место")
                return@Thread
            }
            val p = PoiDb.find(ctx, what, loc.latitude, loc.longitude)
            if (p == null) {
                say("Рядом ничего похожего не нашёл, сэр")
                return@Thread
            }
            val dist = p.distM
            val far = if (dist >= 1000) "%.1f километра".format(Locale("ru"), dist / 1000) else "${(dist / 10).toInt() * 10} метров"
            if (onlyTell) {
                say("Ближайший: ${p.name}, примерно $far ${Geo.direction(Geo.bearing(loc.latitude, loc.longitude, p.lat, p.lon))}")
                return@Thread
            }
            say("Нашёл: ${p.name}, это $far. Веду, сэр")
            val g = guide ?: Guide(ctx) { say(it) }.also { guide = it }
            g.start(p)
        }.start()
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
            ?: run {
                // на слух: сравниваем «скелеты» слов (ватсап ~ whatsapp, тикток ~ TikTok)
                val want = skeleton(key)
                if (want.length < 3) null
                else apps.firstOrNull { ri ->
                    val sk = skeleton(label(ri).replace(" ", ""))
                    sk == want || (sk.length >= 4 && lev(sk, want) <= 1)
                }
            }

        val intent = hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (intent == null) {
            say("Не нашёл приложение $name, сэр. Может, оно просто скромное и прячется?")
            return
        }
        say(ack() + ". Открываю $name")
        start(intent)
    }

    private val tr = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sh",
        'ы' to "i", 'э' to "e", 'ю' to "u", 'я' to "a"
    )

    /** Грубая фонетическая «основа»: без гласных и повторов, w=v, c=k. */
    private fun skeleton(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase(ru)) sb.append(tr[ch] ?: if (ch.isLetterOrDigit()) ch.toString() else "")
        var x = sb.toString().replace("w", "v").replace("ph", "f").replace("ck", "k").replace("c", "k")
            .replace("x", "ks").replace("q", "k").replace("h", "").replace("y", "i")
        x = x.replace(Regex("[aeiouy]"), "")
        return x.replace(Regex("(.)\\1+"), "$1")
    }

    private fun lev(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
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

    // ---------- Музыка ----------

    private fun installed(pkg: String) = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }

    private fun launchPkg(vararg pkgs: String): Boolean {
        for (p in pkgs) {
            val i = ctx.packageManager.getLaunchIntentForPackage(p) ?: continue
            start(i)
            return true
        }
        return false
    }

    private fun music(t: String) {
        val vk = Regex("(^|\\s)(в\\s+)?(вк|вконтакте|контакте)(\\s|$)").containsMatchIn(t)
        val yt = t.contains("ютуб") || t.contains("youtube") || t.contains("ютьюб")
        val ym = t.contains("яндекс музык")
        var q = t
            .replace(Regex("^(включи|поставь|запусти|сыграй|найди и включи|найди песню|найди музыку|включи песню|хочу послушать|поищи песню)\\s+"), "")
            .replace(Regex("\\s+(в|во|на)\\s+(вк|вконтакте|контакте|ютубе|ютуб|ютьюбе|youtube|яндекс музыке)\\b"), "")
            .replace(Regex("\\b(мою|мой|моё)\\s+"), "")
            .replace(Regex("^(пожалуйста)\\s+"), "")
            .trim()
        val generic = q.isEmpty() || q in listOf("музыку", "музыка", "песню", "песни", "плейлист", "плейлиста", "музыку из плейлиста", "любимую музыку", "любимые песни", "треки")
        if (vk && generic || vk && q.contains("плейлист")) {
            say(ack() + ". Включаю ваш плейлист в ВК")
            if (!launchPkg("com.uma.musicvk", "com.vkontakte.android", "com.vk.vkclient")) {
                say("ВК не нашёл, сэр")
                return
            }
            VornexAccessibility.instance?.clickWhenReady(
                listOf("Перемешать", "Слушать", "Воспроизвести", "Shuffle", "Play"), 4000, 6
            ) { ok -> if (!ok) say("ВК открыл, но кнопку воспроизведения не нашёл. Нажмите сами") }
            return
        }
        if (generic) {
            media(KeyEvent.KEYCODE_MEDIA_PLAY)
            return
        }
        q = q.replace(Regex("^(песню|трек|музыку|песни)\\s+"), "").trim()
        say(ack() + ". Ищу и включаю: $q")
        val pkg = when {
            ym && installed("ru.yandex.music") -> "ru.yandex.music"
            installed("com.google.android.youtube") -> "com.google.android.youtube"
            else -> null
        }
        try {
            val i = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH")
                .putExtra(android.app.SearchManager.QUERY, q)
                .putExtra("android.intent.extra.focus", "vnd.android.cursor.item/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg != null) i.setPackage(pkg)
            ctx.startActivity(i)
        } catch (_: Exception) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
        }
        // На экране результатов тапаем по первому видео
        VornexAccessibility.instance?.tapFraction(0.5f, 0.30f, 6500)
    }

    private fun startEnroll() {
        if (!SpkStore.isInstalled(ctx)) {
            say("Нужна модель голоса, сэр. Нажмите в приложении кнопку: Установить всё")
            return
        }
        VoicePrint.startEnroll()
        say("Хорошо, хозяин. Говорите любые фразы, не короче трёх слов. Мне нужно ${VoicePrint.NEED} штук")
    }

    private fun battery() {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        say("Заряд $pct " + RuNumbers.plural(pct.toLong(), "процент", "процента", "процентов") +
            if (charging) ", телефон на зарядке" else "")
    }

    private fun settingsPanel(kind: String) {
        val i = when (kind) {
            "wifi" -> Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            "bt" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "air" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            else -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        }
        say("Открываю нужные настройки, сэр. Сам переключатель трогать не умею")
        start(i)
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
