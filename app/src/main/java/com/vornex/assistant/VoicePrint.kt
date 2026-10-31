package com.vornex.assistant

import android.content.Context
import java.io.File
import kotlin.math.sqrt

/** Запоминает голос хозяина (вектор говорящего из модели Vosk) и сравнивает с ним новые фразы. */
object VoicePrint {
    const val NEED = 6
    const val THRESHOLD = 0.60f

    @Volatile var enrolling = false
    @Volatile var collected = 0
    @Volatile var lastDist = -1f
    private val vecs = ArrayList<FloatArray>()
    private var cached: FloatArray? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "voiceprint.txt")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vornex", Context.MODE_PRIVATE)

    fun hasProfile(ctx: Context) = file(ctx).exists()
    fun active(ctx: Context) = hasProfile(ctx) && prefs(ctx).getBoolean("vp_on", true)
    fun setActive(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("vp_on", v).apply()

    fun startEnroll() {
        synchronized(vecs) { vecs.clear() }
        collected = 0
        enrolling = true
    }

    fun cancelEnroll() {
        enrolling = false
    }

    private fun norm(v: FloatArray): FloatArray {
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-6f)
        return FloatArray(v.size) { v[it] / n }
    }

    /** Добавить образец. Возвращает true, когда образцов достаточно и профиль сохранён. */
    fun addSample(ctx: Context, v: FloatArray): Boolean {
        synchronized(vecs) {
            vecs.add(norm(v))
            collected = vecs.size
            if (vecs.size < NEED) return false
            val avg = FloatArray(vecs[0].size)
            vecs.forEach { x -> for (i in avg.indices) avg[i] += x[i] }
            val res = norm(avg)
            file(ctx).writeText(res.joinToString(","))
            cached = res
            setActive(ctx, true)
            enrolling = false
            return true
        }
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
        cached = null
    }

    private fun profile(ctx: Context): FloatArray? {
        cached?.let { return it }
        val f = file(ctx)
        if (!f.exists()) return null
        return try {
            f.readText().split(",").map { it.toFloat() }.toFloatArray().also { cached = it }
        } catch (_: Exception) { null }
    }

    /** Косинусное расстояние: 0 — тот же голос, больше — чужой. */
    fun distance(ctx: Context, v: FloatArray): Float {
        val p = profile(ctx) ?: return 0f
        val x = norm(v)
        if (x.size != p.size) return 0f
        var dot = 0f
        for (i in p.indices) dot += p[i] * x[i]
        return 1f - dot
    }
}
