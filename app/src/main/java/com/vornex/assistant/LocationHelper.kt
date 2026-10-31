package com.vornex.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Координаты телефона по GPS. Интернет не нужен. */
object LocationHelper {

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun lastKnown(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.getProviders(true).mapNotNull {
            try {
                lm.getLastKnownLocation(it)
            } catch (e: Exception) {
                null
            }
        }.maxByOrNull { it.time }
    }

    /** Блокирующий вызов: свежая точка (до таймаута) или последняя известная. Не из главного потока. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun fresh(ctx: Context, timeoutMs: Long = 25_000): Location? {
        if (!hasPermission(ctx)) return null
        val last = lastKnown(ctx)
        if (last != null && System.currentTimeMillis() - last.time < 120_000) return last

        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return last
        }
        val latch = CountDownLatch(1)
        val result = AtomicReference<Location?>(null)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                result.set(location)
                latch.countDown()
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        return try {
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            lm.removeUpdates(listener)
            result.get() ?: last
        } catch (e: Exception) {
            last
        }
    }
}
