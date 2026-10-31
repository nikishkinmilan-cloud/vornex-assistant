#!/usr/bin/env python3
"""geojsonseq из `osmium export` -> pois.db (магазины, аптеки, банкоматы, остановки...).

    python build_pois_db.py export.geojsonseq pois.db
"""
import json
import sqlite3
import sys

KEEP_UNNAMED = {"atm", "bus_stop", "toilets", "pharmacy", "fuel", "hospital", "police", "post_office", "marketplace"}
KEY_ORDER = ["shop", "amenity", "tourism", "railway", "highway", "public_transport", "leisure"]
SKIP_KIND = {"bench", "waste_basket", "parking", "parking_space", "bicycle_parking", "shelter",
             "vending_machine", "recycling", "post_box", "street_lamp", "fountain", "drinking_water",
             "parking_entrance", "motorcycle_parking", "grave_yard", "hunting_stand", "bbq", "clock",
             "waste_disposal", "give_box", "charging_station", "taxi", "atm_booth"}


def centre(geom):
    t = geom.get("type")
    c = geom.get("coordinates")
    if t == "Point":
        return c[1], c[0]
    ring = None
    if t == "Polygon":
        ring = c[0]
    elif t == "MultiPolygon":
        ring = c[0][0]
    if not ring:
        return None
    lon = sum(p[0] for p in ring) / len(ring)
    lat = sum(p[1] for p in ring) / len(ring)
    return lat, lon


def main():
    src, out = sys.argv[1], sys.argv[2]
    db = sqlite3.connect(out)
    db.executescript(
        """
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        DROP TABLE IF EXISTS pois;
        CREATE TABLE pois(id INTEGER PRIMARY KEY, name TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, kind TEXT NOT NULL);
        """
    )
    n = 0
    with open(src, encoding="utf-8") as f:
        for line in f:
            line = line.strip().lstrip("\x1e")
            if not line:
                continue
            try:
                ft = json.loads(line)
            except Exception:
                continue
            p = ft.get("properties") or {}
            kind = None
            for k in KEY_ORDER:
                v = p.get(k)
                if v and v != "yes":
                    kind = v
                    break
                if v == "yes" and k == "shop":
                    kind = "shop"
                    break
            if not kind or kind in SKIP_KIND:
                continue
            name = p.get("name:ru") or p.get("name") or p.get("brand") or p.get("operator") or ""
            if not name and kind not in KEEP_UNNAMED:
                continue
            if not name:
                name = kind
            c = centre(ft.get("geometry") or {})
            if not c:
                continue
            n += 1
            db.execute("INSERT INTO pois(name, lat, lon, kind) VALUES (?,?,?,?)", (name.strip(), c[0], c[1], kind))
    db.execute("CREATE INDEX idx_lat ON pois(lat)")
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"Готово: {n} объектов -> {out}")


if __name__ == "__main__":
    main()
