package com.vornex.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Calendar

/**
 * Свои будильники и напоминания: в нужное время Боря сам заговорит.
 * Хранятся в SharedPreferences, ставятся через AlarmManager.setAlarmClock (работает и в спящем режиме).
 */
object Alarms {
    // repeat: 0 — один раз, 1 — каждый день, 2 — по будням
    data class A(val id: Int, val at: Long, val hour: Int, val minute: Int, val repeat: Int, val kind: String, val msg: String)

    private const val PREF = "alarms"
    private const val KEY = "list"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<A> =
        (prefs(ctx).getString(KEY, "") ?: "").split("\n").mapNotNull { line ->
            val p = line.split("|", limit = 7)
            if (p.size < 7) null else try {
                A(p[0].toInt(), p[1].toLong(), p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5], p[6])
            } catch (_: Exception) { null }
        }

    private fun save(ctx: Context, list: List<A>) {
        prefs(ctx).edit().putString(KEY, list.joinToString("\n") {
            "${it.id}|${it.at}|${it.hour}|${it.minute}|${it.repeat}|${it.kind}|${it.msg.replace("\n", " ").replace("|", " ")}"
        }).apply()
    }

    /** Ближайшее срабатывание после now для времени hour:minute с учётом повтора. */
    private fun nextAt(hour: Int, minute: Int, repeat: Int, after: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = after
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= after) c.add(Calendar.DAY_OF_YEAR, 1)
        if (repeat == 2) {
            while (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                c.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return c.timeInMillis
    }

    private fun newId(list: List<A>) = (list.maxOfOrNull { it.id } ?: 0) + 1

    fun addClock(ctx: Context, hour: Int, minute: Int, repeat: Int, kind: String, msg: String): A {
        val list = all(ctx)
        val a = A(newId(list), nextAt(hour, minute, repeat, System.currentTimeMillis()), hour, minute, repeat, kind, msg)
        save(ctx, list + a)
        schedule(ctx, a)
        return a
    }

    fun addAfter(ctx: Context, seconds: Long, kind: String, msg: String): A {
        val list = all(ctx)
        val at = System.currentTimeMillis() + seconds * 1000
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val a = A(newId(list), at, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), 0, kind, msg)
        save(ctx, list + a)
        schedule(ctx, a)
        return a
    }

    private fun pi(ctx: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, AlarmReceiver::class.java).setData(Uri.parse("vornex://alarm/$id")).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun schedule(ctx: Context, a: A) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(a.at, show), pi(ctx, a.id))
    }

    fun cancelAll(ctx: Context): Int {
        val list = all(ctx)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        list.forEach { am.cancel(pi(ctx, it.id)) }
        save(ctx, emptyList())
        return list.size
    }

    /** После перезагрузки телефона. */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val fixed = all(ctx).mapNotNull { a ->
            when {
                a.at > now -> a
                a.repeat != 0 -> a.copy(at = nextAt(a.hour, a.minute, a.repeat, now))
                else -> null // разовый, время прошло
            }
        }
        save(ctx, fixed)
        fixed.forEach { schedule(ctx, it) }
    }

    fun fire(ctx: Context, id: Int) {
        val list = all(ctx)
        val a = list.firstOrNull { it.id == id } ?: return
        val rest = list.filter { it.id != id }
        if (a.repeat != 0) {
            val n = a.copy(at = nextAt(a.hour, a.minute, a.repeat, System.currentTimeMillis() + 60_000))
            save(ctx, rest + n)
            schedule(ctx, n)
        } else {
            save(ctx, rest)
        }
        WakeService.start(ctx, a.kind, a.msg)
    }

    fun describe(a: A): String {
        val t = "%d:%02d".format(a.hour, a.minute)
        val r = when (a.repeat) { 1 -> ", каждый день"; 2 -> ", по будням"; else -> "" }
        return "$t$r" + if (a.kind == "alarm" || a.msg.isBlank()) "" else ": ${a.msg}"
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Alarms.fire(context.applicationContext, intent.getIntExtra("id", -1))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Alarms.rescheduleAll(context.applicationContext)
    }
}
