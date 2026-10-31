package com.vornex.assistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.Locale
import kotlin.math.cos

/** Офлайн-база магазинов, аптек, банкоматов, остановок (pois.db из OpenStreetMap). */
object PoiDb {
    data class Poi(val name: String, val lat: Double, val lon: Double, val kind: String, val distM: Double)

    private var db: SQLiteDatabase? = null
    fun file(ctx: Context) = File(ctx.filesDir, "pois.db")
    fun available(ctx: Context) = file(ctx).length() > 0

    @Synchronized
    private fun open(ctx: Context): SQLiteDatabase? {
        db?.let { if (it.isOpen) return it }
        val f = file(ctx)
        if (f.length() == 0L) return null
        return try {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
        } catch (e: Exception) { null }
    }

    @Synchronized
    fun close() { db?.close(); db = null }

    // слово пользователя -> виды объектов в базе
    private val categories = listOf(
        "аптек" to listOf("pharmacy"),
        "банкомат" to listOf("atm"),
        "остановк" to listOf("bus_stop"),
        "кафе" to listOf("cafe", "fast_food"),
        "ресторан" to listOf("restaurant"),
        "заправк" to listOf("fuel"),
        "азс" to listOf("fuel"),
        "больниц" to listOf("hospital", "clinic"),
        "поликлиник" to listOf("clinic", "doctors", "hospital"),
        "полици" to listOf("police"),
        "рын" to listOf("marketplace"),
        "базар" to listOf("marketplace"),
        "почт" to listOf("post_office"),
        "туалет" to listOf("toilets"),
        "банк" to listOf("bank"),
        "магазин" to listOf("supermarket", "convenience", "general", "department_store", "mall", "grocery", "greengrocer", "kiosk", "variety_store", "butcher", "bakery")
    )

    private val stop = setOf(
        "ближайший", "ближайшая", "ближайшую", "ближайшего", "ближайшей", "ближайшее", "ближайшие", "ближайшим", "ближайшему",
        "меня", "мне", "до", "к", "в", "на", "рядом", "поближе", "пожалуйста", "путь", "дорогу", "магазин", "магазина", "магазину"
    )

    private val tr = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sh",
        'ы' to "i", 'э' to "e", 'ю' to "u", 'я' to "a", 'ә' to "a", 'ң' to "n", 'ү' to "u", 'ұ' to "u", 'қ' to "k", 'ө' to "o", 'һ' to "h", 'і' to "i", 'ғ' to "g"
    )

    /** Грубая «основа» слова без гласных: эконом маркета ~ Econom Market. */
    fun skeleton(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase(Locale("ru"))) sb.append(tr[ch] ?: if (ch.isLetterOrDigit()) ch.toString() else "")
        var x = sb.toString().replace("w", "v").replace("ph", "f").replace("ck", "k").replace("c", "k")
            .replace("x", "ks").replace("q", "k").replace("h", "").replace("y", "i")
        x = x.replace(Regex("[aeiou]"), "")
        return x.replace(Regex("(.)\\1+"), "$1")
    }

    private fun boxes() = listOf(0.02, 0.06, 0.2, 0.6, 2.0)

    /** Ближайший объект по запросу вроде «ближайшего эконом маркета» или «аптеку». */
    fun find(ctx: Context, phrase: String, lat: Double, lon: Double): Poi? {
        val d = open(ctx) ?: return null
        val words = phrase.lowercase(Locale("ru")).replace('ё', 'е').split(Regex("\\s+")).filter { it.isNotBlank() }
        val catHit = categories.firstOrNull { (k, _) -> words.any { it.startsWith(k) } }
        val rest = words.filter { it !in stop && !(catHit != null && it.startsWith(catHit.first)) }
        val q = skeleton(rest.joinToString(""))
        val kinds = if (rest.isEmpty() && catHit != null) catHit.second else null
        if (kinds == null && q.length < 2) return null

        for (r in boxes()) {
            val dLon = r / cos(Math.toRadians(lat)).coerceAtLeast(0.2)
            var best: Poi? = null
            val sql = "SELECT name, lat, lon, kind FROM pois WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?" +
                (if (kinds != null) " AND kind IN (${kinds.joinToString(",") { "'$it'" }})" else "") + " LIMIT 80000"
            try {
                d.rawQuery(sql, arrayOf((lat - r).toString(), (lat + r).toString(), (lon - dLon).toString(), (lon + dLon).toString())).use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(0)
                        if (kinds == null && !skeleton(name).contains(q)) continue
                        val plat = c.getDouble(1)
                        val plon = c.getDouble(2)
                        val dist = Geo.distKm(lat, lon, plat, plon) * 1000
                        if (best == null || dist < best!!.distM) best = Poi(name, plat, plon, c.getString(3), dist)
                    }
                }
            } catch (_: Exception) {}
            if (best != null) return best
        }
        return null
    }
}
