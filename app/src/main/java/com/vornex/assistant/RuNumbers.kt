package com.vornex.assistant

import java.util.Locale
import kotlin.math.abs

/** Русские числа словами ("двадцать пять", "семь тридцать"), время и длительность из речи. */
object RuNumbers {
    sealed class Tok {
        data class W(val s: String) : Tok()
        data class N(val v: Double) : Tok()
    }

    private val units = mapOf(
        "один" to 1, "одна" to 1, "одну" to 1, "одно" to 1, "два" to 2, "две" to 2, "три" to 3,
        "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9
    )
    private val teens = mapOf(
        "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13,
        "четырнадцать" to 14, "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17,
        "восемнадцать" to 18, "девятнадцать" to 19
    )
    private val tens = mapOf(
        "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50,
        "шестьдесят" to 60, "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90
    )
    private val hundreds = mapOf(
        "сто" to 100, "двести" to 200, "триста" to 300, "четыреста" to 400, "пятьсот" to 500,
        "шестьсот" to 600, "семьсот" to 700, "восемьсот" to 800, "девятьсот" to 900
    )

    private fun scale(w: String): Long? = when {
        w.startsWith("тысяч") -> 1_000L
        w.startsWith("миллион") -> 1_000_000L
        else -> null
    }

    /** Разбивает фразу на слова и числа. Числа словами склеиваются: «двадцать пять» -> 25, «семь тридцать» -> 7 и 30. */
    fun tokenize(text: String): List<Tok> {
        val s = text.lowercase(Locale("ru")).replace('ё', 'е')
            .replace(Regex("(\\d),(\\d)"), "$1.$2").replace(':', ' ')
        val words = s.split(Regex("[^a-zа-я0-9.]+")).map { it.trim('.') }.filter { it.isNotEmpty() }
        val out = ArrayList<Tok>()
        var total = 0L
        var cur = 0L
        var lastClass = 99
        var active = false

        fun flush() {
            if (active) {
                out.add(Tok.N((total + cur).toDouble()))
                total = 0
                cur = 0
                lastClass = 99
                active = false
            }
        }

        fun comp(v: Long, cls: Int) {
            if (active && lastClass <= cls) flush()
            cur += v
            lastClass = cls
            active = true
        }

        for (w in words) {
            val num = w.toDoubleOrNull()
            val sc = scale(w)
            when {
                num != null -> {
                    flush()
                    out.add(Tok.N(num))
                }
                w == "ноль" || w == "нуль" -> {
                    flush()
                    out.add(Tok.N(0.0))
                }
                units.containsKey(w) -> comp(units.getValue(w).toLong(), 1)
                teens.containsKey(w) -> comp(teens.getValue(w).toLong(), 1)
                tens.containsKey(w) -> comp(tens.getValue(w).toLong(), 2)
                hundreds.containsKey(w) -> comp(hundreds.getValue(w).toLong(), 3)
                sc != null -> {
                    if (!active) {
                        cur = 1
                        active = true
                    }
                    total += cur * sc
                    cur = 0
                    lastClass = 99
                }
                else -> {
                    flush()
                    out.add(Tok.W(w))
                }
            }
        }
        flush()
        return out
    }

    // ---------- Время суток: «в семь тридцать вечера» ----------

    data class ClockAt(val hour: Int, val minute: Int, val end: Int)

    fun parseClockAt(toks: List<Tok>, from: Int = 0): ClockAt? {
        var i = (from until toks.size).firstOrNull { toks[it] is Tok.N } ?: return null
        var hour = (toks[i] as Tok.N).v.toInt()
        i++
        if (i < toks.size && (toks[i] as? Tok.W)?.s?.startsWith("час") == true) i++
        var minute = 0
        if (i < toks.size && toks[i] is Tok.N) {
            val m = (toks[i] as Tok.N).v.toInt()
            if (m in 0..59) {
                minute = m
                i++
            }
        }
        if (i < toks.size && (toks[i] as? Tok.W)?.s?.startsWith("минут") == true) i++
        if (i < toks.size && toks[i] is Tok.N && (toks[i] as Tok.N).v == 0.0) i++ // «восемь ноль ноль»
        when ((toks.getOrNull(i) as? Tok.W)?.s) {
            "вечера", "дня" -> {
                if (hour < 12) hour += 12
                i++
            }
            "утра", "ночи" -> {
                if (hour == 12) hour = 0
                i++
            }
        }
        if (hour !in 0..23) return null
        return ClockAt(hour, minute, i)
    }

    // ---------- Длительность: «пять минут», «час», «два часа тридцать минут» ----------

    data class Dur(val seconds: Long, val end: Int)

    private fun unitSeconds(w: String): Long? = when {
        w.startsWith("секунд") -> 1L
        w.startsWith("минут") -> 60L
        w == "час" || w.startsWith("часа") || w.startsWith("часов") -> 3600L
        else -> null
    }

    fun parseDuration(toks: List<Tok>, start: Int): Dur? {
        var i = start
        var total = 0L
        var found = false
        while (i < toks.size) {
            val t = toks[i]
            if (t is Tok.W && t.s == "полчаса") {
                total += 1800
                found = true
                i++
                continue
            }
            if (t is Tok.W) {
                val u = unitSeconds(t.s)
                if (u != null) { // «час» без числа = один час
                    total += u
                    found = true
                    i++
                    continue
                }
            }
            if (t is Tok.N && i + 1 < toks.size) {
                val u = (toks[i + 1] as? Tok.W)?.let { unitSeconds(it.s) }
                if (u != null) {
                    total += (t.v * u).toLong()
                    found = true
                    i += 2
                    continue
                }
            }
            break
        }
        return if (found) Dur(total, i) else null
    }

    /** Остаток фразы как текст (числа возвращаются цифрами). */
    fun textFrom(toks: List<Tok>, from: Int): String =
        toks.drop(from).joinToString(" ") {
            when (it) {
                is Tok.W -> it.s
                is Tok.N -> format(it.v)
            }
        }.trim()

    // ---------- Вывод ----------

    fun plural(n: Long, one: String, few: String, many: String): String {
        val a = n % 100
        val b = n % 10
        return when {
            a in 11L..14L -> many
            b == 1L -> one
            b in 2L..4L -> few
            else -> many
        }
    }

    fun humanDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        val parts = ArrayList<String>()
        if (h > 0) parts.add("$h ${plural(h, "час", "часа", "часов")}")
        if (m > 0) parts.add("$m ${plural(m, "минуту", "минуты", "минут")}")
        if (s > 0 || parts.isEmpty()) parts.add("$s ${plural(s, "секунду", "секунды", "секунд")}")
        return parts.joinToString(" ")
    }

    fun format(d: Double): String {
        if (d == Math.rint(d) && abs(d) < 1e15) return d.toLong().toString()
        return String.format(Locale.US, "%.4f", d).trimEnd('0').trimEnd('.').replace('.', ',')
    }
}
