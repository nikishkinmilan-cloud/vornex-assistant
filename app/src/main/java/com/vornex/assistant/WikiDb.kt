package com.vornex.assistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.Locale

/** Офлайн-Википедия: файл wiki.db, собранный скриптом tools/build_wiki_db.py. */
object WikiDb {
    data class Hit(val title: String, val lead: String)

    private var db: SQLiteDatabase? = null

    fun file(ctx: Context) = File(ctx.filesDir, "wiki.db")
    fun available(ctx: Context) = file(ctx).length() > 0

    @Synchronized
    private fun open(ctx: Context): SQLiteDatabase? {
        db?.let { if (it.isOpen) return it }
        val f = file(ctx)
        if (f.length() == 0L) return null
        return try {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun close() {
        db?.close()
        db = null
    }

    fun norm(s: String) = s.lowercase(Locale("ru")).replace('ё', 'е')

    // Слова вопроса, которые не несут темы.
    private val stop = setOf(
        "что", "такое", "кто", "такой", "такая", "такие", "как", "где", "почему", "зачем", "когда",
        "сколько", "какой", "какая", "какое", "какие", "расскажи", "рассказать", "про", "о", "об",
        "мне", "нам", "это", "есть", "ли", "а", "и", "в", "во", "на", "с", "со", "по", "для", "ты",
        "мы", "я", "мой", "моя", "скажи", "объясни", "значит", "значение", "находится", "работает",
        "можно", "можешь", "пожалуйста", "боря", "чем", "чего", "кого", "откуда", "куда", "был",
        "была", "были", "будет", "у", "от", "из", "за", "к", "не", "да", "нет", "или", "ну", "вот",
        "же", "бы", "то", "все", "еще", "такого", "ответь", "подскажи", "знаешь", "знаешь"
    )

    // «зеландии» -> «зеланд», чтобы находить «зеландия» при любом падеже.
    private fun stem(w: String) = when {
        w.length >= 6 -> w.dropLast(2)
        w.length >= 4 -> w.dropLast(1)
        else -> w
    }

    fun search(ctx: Context, question: String): List<Hit> {
        val d = open(ctx) ?: return emptyList()
        val words = Regex("[a-zа-я0-9]+").findAll(norm(question)).map { it.value }
            .filter { it !in stop && it.length >= 2 }.toList()
        if (words.isEmpty()) return emptyList()
        var hits = query(d, words)
        if (hits.isEmpty() && words.size > 1) {
            hits = query(d, listOf(words.maxByOrNull { it.length }!!))
        }
        return hits
    }

    private fun query(d: SQLiteDatabase, words: List<String>): List<Hit> {
        val match = words.joinToString(" ") { stem(it) + "*" }
        val scored = ArrayList<Pair<Double, Hit>>()
        try {
            d.rawQuery(
                "SELECT a.title, a.lead FROM fts JOIN articles a ON a.id = fts.docid " +
                    "WHERE fts MATCH ? LIMIT 60",
                arrayOf(match)
            ).use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0)
                    val lead = c.getString(1)
                    val t = norm(title)
                    var s = 0.0
                    if (t == words.joinToString(" ")) s += 100
                    if (t.startsWith(stem(words[0]))) s += 20
                    s -= t.split(' ').size * 3
                    s -= t.length * 0.1
                    if (t.contains("(")) s -= 15
                    if (t.startsWith("список")) s -= 40
                    scored.add(s to Hit(title, lead))
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return scored.sortedByDescending { it.first }.take(3).map { it.second }
    }
}
