package com.vornex.assistant

import android.content.Context
import java.io.File

/** Простые текстовые списки на телефоне: заметки и память о хозяине. */
object Store {
    const val NOTES = "notes.txt"
    const val MEMORY = "memory.txt"

    private fun f(ctx: Context, name: String) = File(ctx.filesDir, name)

    @Synchronized
    fun read(ctx: Context, name: String): List<String> {
        val file = f(ctx, name)
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }
    }

    @Synchronized
    fun add(ctx: Context, name: String, line: String, cap: Int = 200) {
        val all = read(ctx, name) + line.trim().replace('\n', ' ')
        f(ctx, name).writeText(all.takeLast(cap).joinToString("\n") + "\n")
    }

    @Synchronized
    fun clear(ctx: Context, name: String) {
        f(ctx, name).delete()
    }
}
