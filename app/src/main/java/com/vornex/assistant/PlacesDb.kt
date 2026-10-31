package com.vornex.assistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlin.math.cos

/** Офлайн-база населённых пунктов: файл places.db, собранный tools/build_places_db.py. */
object PlacesDb {
    data class Place(
        val name: String,
        val distKm: Double,
        val bearingDeg: Double,
        val cc: String?
    )

    private var db: SQLiteDatabase? = null

    fun file(ctx: Context) = File(ctx.filesDir, "places.db")
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

    fun countryName(ctx: Context, cc: String?): String? {
        if (cc == null) return null
        val d = open(ctx) ?: return null
        return try {
            d.rawQuery("SELECT name FROM countries WHERE cc = ?", arrayOf(cc)).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Ближайшие населённые пункты к точке. Ищет в расширяющейся рамке (в градусах). */
    fun nearest(ctx: Context, lat: Double, lon: Double, n: Int = 3): List<Place> {
        val d = open(ctx) ?: return emptyList()
        for (r in listOf(0.3, 1.0, 3.0, 10.0)) {
            val dLon = r / cos(Math.toRadians(lat)).coerceAtLeast(0.2)
            val list = ArrayList<Place>()
            try {
                d.rawQuery(
                    "SELECT name, lat, lon, cc FROM places " +
                        "WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
                    arrayOf(
                        (lat - r).toString(), (lat + r).toString(),
                        (lon - dLon).toString(), (lon + dLon).toString()
                    )
                ).use { c ->
                    while (c.moveToNext()) {
                        val plat = c.getDouble(1)
                        val plon = c.getDouble(2)
                        list.add(
                            Place(
                                c.getString(0),
                                Geo.distKm(lat, lon, plat, plon),
                                Geo.bearing(lat, lon, plat, plon),
                                c.getString(3)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                return emptyList()
            }
            if (list.isNotEmpty()) return list.sortedBy { it.distKm }.take(n)
        }
        return emptyList()
    }
}
