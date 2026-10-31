package com.vornex.assistant

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Мозг: отвечает на всё, что не команда. Всё работает без интернета.
 * 1) «где я» -> GPS + офлайн-база мест
 * 2) вопрос -> поиск в офлайн-Википедии -> нейросеть формулирует ответ (если установлена)
 * 3) нет нейросети -> читает вступление статьи из Википедии
 */
class Brain(
    private val ctx: Context,
    private val say: (String) -> Unit,        // сказать сразу (прерывает текущую речь)
    private val sayQueued: (String) -> Unit   // добавить в очередь речи
) {
    private val ru = Locale("ru")
    private val busy = AtomicBoolean(false)

    fun answer(raw: String) {
        if (!busy.compareAndSet(false, true)) {
            say("Минуту, сэр, я ещё занят прошлым вопросом")
            return
        }
        Thread {
            try {
                if (isWhere(WikiDb.norm(raw))) whereAmI() else ask(raw)
            } catch (e: Throwable) {
                say("Что-то пошло не так, сэр")
            } finally {
                busy.set(false)
            }
        }.start()
    }

    private fun isWhere(t: String) = listOf(
        "где я", "где мы", "где нахожусь", "где находимся", "мое местоположение", "мои координаты",
        "какой это город", "что это за место", "что за место", "какое это место",
        "в каком я городе", "в каком городе я"
    ).any { t.contains(it) }

    // ---------- Где я ----------

    private fun whereAmI() {
        if (!LocationHelper.hasPermission(ctx)) {
            say("У меня нет доступа к геолокации, сэр. Разреши её в приложении")
            return
        }
        say("Определяю, сэр. Это может занять до полуминуты")
        val loc = LocationHelper.fresh(ctx)
        if (loc == null) {
            sayQueued("Спутники не отвечают, сэр. Выйди на открытое место и спроси ещё раз")
            return
        }
        val places = PlacesDb.nearest(ctx, loc.latitude, loc.longitude, 3)
        if (places.isEmpty()) {
            sayQueued(
                "Ваши координаты: широта %.3f, долгота %.3f. Базы мест у меня нет, поэтому название не назову"
                    .format(ru, loc.latitude, loc.longitude)
            )
            return
        }
        val p = places[0]
        val sb = StringBuilder()
        if (p.distKm < 1.5) {
            sb.append("Вы находитесь в населённом пункте ${p.name}")
        } else {
            val km = p.distKm.roundToInt().coerceAtLeast(1)
            sb.append("Ближайший населённый пункт — ${p.name}, примерно $km км ${Geo.direction(p.bearingDeg)}")
        }
        PlacesDb.countryName(ctx, p.cc)?.let { sb.append(", $it") }
        sb.append(". ")
        places.getOrNull(1)?.let { sb.append("Рядом также ${it.name}.") }
        sayQueued(sb.toString())
    }

    private fun placeContext(): String? {
        val loc = LocationHelper.lastKnown(ctx) ?: return null
        val p = PlacesDb.nearest(ctx, loc.latitude, loc.longitude, 1).firstOrNull()
            ?: return "координаты %.3f, %.3f".format(Locale.US, loc.latitude, loc.longitude)
        val country = PlacesDb.countryName(ctx, p.cc)
        return "рядом с населённым пунктом ${p.name}" + (country?.let { ", $it" } ?: "")
    }

    // ---------- Вопросы ----------

    private fun ask(q: String) {
        val hits = WikiDb.search(ctx, q)
        when {
            LlmEngine.ready -> {
                say(listOf("Секунду, сэр, думаю", "Дайте подумать, хозяин", "Так, сейчас соображу").random())
                var spoke = false
                LlmEngine.generate(buildPrompt(q, hits)) {
                    spoke = true
                    sayQueued(it)
                }
                if (!spoke) say("Ответа не вышло, сэр. Спросите иначе")
            }
            hits.isNotEmpty() ->
                say("Вот что я знаю, сэр. " + firstSentences(hits[0].lead, 2))
            LlmEngine.isInstalled(ctx) ->
                say("Нейросеть ещё просыпается, сэр. Дайте ей минуту")
            !WikiDb.available(ctx) ->
                say("У меня пока нет базы знаний, сэр. Загрузите Википедию и нейросеть в приложении")
            else ->
                say("В моих записях этого нет, сэр. Скажите: найди в интернете, и тему")
        }
    }

    private fun buildPrompt(q: String, hits: List<WikiDb.Hit>): String {
        val now = SimpleDateFormat("d MMMM yyyy, EEEE, HH:mm", ru).format(Date())
        val sb = StringBuilder()
        sb.append(
            "Ты Боря, голосовой помощник-дворецкий в стиле Джарвиса: вежливый, чуть ироничный. " +
                "Обращайся к собеседнику «сэр» или «хозяин». Отвечай только по-русски, коротко, " +
                "одним-тремя предложениями, без списков, без markdown и без смайликов, " +
                "потому что ответ будет озвучен голосом.\n"
        )
        sb.append("Сейчас: $now.\n")
        placeContext()?.let { sb.append("Местоположение пользователя: $it.\n") }
        if (hits.isNotEmpty()) {
            sb.append("Справка из Википедии (используй, если она относится к вопросу):\n")
            hits.take(2).forEach { sb.append("${it.title}: ${it.lead.take(500)}\n") }
        }
        sb.append("Если не знаешь ответа, так и скажи, ничего не выдумывай.\n")
        sb.append("Вопрос: $q")
        return sb.toString()
    }

    private fun firstSentences(text: String, n: Int): String {
        val parts = text.split(Regex("(?<=[.!?])\\s+"))
        return parts.take(n).joinToString(" ").take(400)
    }
}
