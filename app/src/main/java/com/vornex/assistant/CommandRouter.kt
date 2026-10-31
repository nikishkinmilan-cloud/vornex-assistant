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
import android.provider.ContactsContract
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Разбирает фразу после слова «Боря», выполняет команду или передаёт вопрос мозгу. */
class CommandRouter(
    private val ctx: Context,
    private val say: (String) -> Unit,
    private val brain: Brain
) {

    private val ru = Locale("ru")
    private var torchOn = false

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
        val open = Regex("^(открой|открыть|запусти|запустить)\\s+(.+)$").find(t)
        val call = Regex("^(позвони|набери|вызови)\\s+(.+)$").find(t)
        val web = Regex("^(найди|загугли|поищи)\\s+(?:в интернете\\s+|в гугле\\s+|в google\\s+)?(.+)$").find(t)
        when {
            t.isEmpty() -> say(listening())
            open != null -> openApp(open.groupValues[2])
            call != null -> callContact(call.groupValues[2])
            web != null -> webSearch(web.groupValues[2])

            Regex("закро[а-я]*\\s+вс[её]").containsMatchIn(t) -> closeAll(t.contains("вкладк"))
            t == "домой" || t.contains("главный экран") -> withA11y { it.home(); say(ack()) }
            t == "назад" || t == "вернись" -> withA11y { it.back() }
            t.contains("заблокируй") || t.contains("выключи экран") -> withA11y { say(ack()); it.lockScreen() }
            t.contains("скриншот") || t.contains("снимок экрана") -> withA11y { say(ack()); it.screenshot() }

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

            else -> brain.answer(t) // вопросы: Википедия, местоположение, нейросеть
        }
    }

    private fun start(i: Intent) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            say("Не получилось, сэр")
        }
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

    // ---------- Приложения, звонки, фонарик ----------

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
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            say("Нет доступа к контактам, сэр")
            return
        }
        val q = nameRaw.trim()
        val stem = if (q.length >= 4) q.dropLast(1) else q // «маме» -> «мам»
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                if (n.lowercase(ru).replace('ё', 'е').contains(stem)) {
                    say(ack() + ". Набираю $n")
                    start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num))))
                    return
                }
            }
        }
        say("Не нашёл контакт $q, сэр")
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
