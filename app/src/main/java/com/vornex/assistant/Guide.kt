package com.vornex.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock

/** Ведёт к цели голосом по GPS, без интернета: «чуть правее, осталось 150 метров». Дорог не знает. */
class Guide(private val ctx: Context, private val say: (String) -> Unit) {
    private var lm: LocationManager? = null
    private var target: PoiDb.Poi? = null
    private var lastSpeak = 0L
    private var lastKey = ""
    private var heading = -1.0
    @Volatile var active = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) = update(l)
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun start(p: PoiDb.Poi) {
        stop()
        target = p
        lastSpeak = 0
        lastKey = ""
        heading = -1.0
        val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = m
        try {
            val prov = if (m.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            m.requestLocationUpdates(prov, 2000L, 0f, listener, Looper.getMainLooper())
            active = true
        } catch (_: Exception) {
            say("Не могу включить GPS, сэр")
        }
    }

    fun stop() {
        if (active) say("Провожать закончил, сэр")
        quiet()
    }

    private fun quiet() {
        active = false
        try { lm?.removeUpdates(listener) } catch (_: Exception) {}
        target = null
    }

    private fun meters(m: Double): String {
        val v = if (m < 100) (m / 10).toInt() * 10 else (m / 50).toInt() * 50
        val n = v.coerceAtLeast(10)
        return "$n " + RuNumbers.plural(n.toLong(), "метр", "метра", "метров")
    }

    private fun update(l: Location) {
        val t = target ?: return
        val dist = Geo.distKm(l.latitude, l.longitude, t.lat, t.lon) * 1000
        if (dist < 25) {
            say("Вы пришли, сэр. Это ${t.name}")
            quiet()
            return
        }
        val brg = Geo.bearing(l.latitude, l.longitude, t.lat, t.lon)
        if (l.hasBearing() && l.speed > 0.7f) heading = l.bearing.toDouble()

        val phrase: String
        val key: String
        if (heading >= 0) {
            var rel = ((brg - heading + 540) % 360) - 180 // -180..180, плюс — вправо
            phrase = when {
                kotlin.math.abs(rel) < 20 -> "Идите прямо"
                rel in 20.0..60.0 -> "Чуть правее"
                rel in -60.0..-20.0 -> "Чуть левее"
                rel in 60.0..135.0 -> "Поверните направо"
                rel in -135.0..-60.0 -> "Поверните налево"
                else -> "Развернитесь"
            }
            key = phrase
        } else {
            phrase = "Идите ${Geo.direction(brg)}. Пройдите немного, и я пойму, куда вы смотрите"
            key = Geo.direction(brg)
        }
        val now = SystemClock.elapsedRealtime()
        val changed = key != lastKey && now - lastSpeak > 6000
        if (lastSpeak == 0L || changed || now - lastSpeak > 15000) {
            lastSpeak = now
            lastKey = key
            say("$phrase. Осталось ${meters(dist)}")
        }
    }
}
