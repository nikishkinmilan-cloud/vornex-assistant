package com.vornex.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import java.util.Locale

/** Поиск контакта по имени из речи («маме», «Ивану Петрову»). */
object Contacts {
    data class Contact(val name: String, val number: String)

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun norm(s: String) = s.lowercase(Locale("ru")).replace('ё', 'е')

    fun find(ctx: Context, phrase: String): Contact? {
        if (!hasPermission(ctx)) return null
        val q = norm(phrase).trim()
        if (q.isEmpty()) return null
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
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                if (norm(name).contains(stem)) return Contact(name, number)
            }
        }
        return null
    }

    /** Номер в формате для wa.me: только цифры, российский/казахстанский 8... -> 7... */
    fun waNumber(raw: String): String {
        var d = raw.filter { it.isDigit() }
        if (d.length == 11 && d.startsWith("8")) d = "7" + d.drop(1)
        return d
    }
}
