#!/usr/bin/env python3
"""
Делает базу населённых пунктов places.db для Vornex (ответ на «где я» без интернета).
Источник: GeoNames (города и посёлки от 500 жителей, русские названия).

Запуск:  python build_places_db.py places.db
Файлы GeoNames скачаются сами в папку geonames_cache (около 200 МБ один раз).
Если файлы уже лежат в этой папке, повторно не качаются.
"""
import argparse
import csv
import io
import os
import re
import sqlite3
import sys
import urllib.request
import zipfile

BASE = "https://download.geonames.org/export/dump/"
FILES = ["cities500.zip", "alternateNamesV2.zip", "countryInfo.txt"]


def download(name: str, cache: str) -> str:
    path = os.path.join(cache, name)
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return path
    print(f"Скачиваю {name} …")
    tmp = path + ".part"
    with urllib.request.urlopen(BASE + name, timeout=60) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if total:
                print(f"\r  {done * 100 // total}%", end="", flush=True)
    print()
    os.replace(tmp, path)
    return path


def open_text(path: str, inner: str | None = None):
    if path.endswith(".zip"):
        z = zipfile.ZipFile(path)
        name = inner or z.namelist()[0]
        return io.TextIOWrapper(z.open(name), encoding="utf-8", newline="")
    return open(path, encoding="utf-8", newline="")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("out", nargs="?", default="places.db")
    ap.add_argument("--cache", default="geonames_cache")
    ap.add_argument("--min-pop", type=int, default=0, help="минимальное население (0 = все из cities500)")
    args = ap.parse_args()
    os.makedirs(args.cache, exist_ok=True)

    cities = download("cities500.zip", args.cache)
    alt = download("alternateNamesV2.zip", args.cache)
    cinfo = download("countryInfo.txt", args.cache)

    # 1) города
    places = {}  # geonameid -> [name, lat, lon, pop, cc]
    with open_text(cities, "cities500.txt") as f:
        for row in csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
            if len(row) < 15:
                continue
            gid = int(row[0])
            pop = int(row[14] or 0)
            if pop < args.min_pop:
                continue
            places[gid] = [row[1], float(row[4]), float(row[5]), pop, row[8]]
    print(f"Населённых пунктов: {len(places)}")

    # 2) страны (geonameid страны нужен, чтобы найти её русское название)
    countries = {}  # cc -> [geonameid, english name]
    with open(cinfo, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            c = line.rstrip("\n").split("\t")
            if len(c) > 16 and c[16].isdigit():
                countries[c[0]] = [int(c[16]), c[4]]
    country_gid = {v[0]: k for k, v in countries.items()}

    # 3) русские названия из alternateNamesV2
    wanted = set(places) | set(country_gid)
    best = {}  # gid -> (rank, name)
    print("Ищу русские названия (1-2 минуты)…")
    with open_text(alt, "alternateNamesV2.txt") as f:
        for row in csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
            if len(row) < 4 or row[2] != "ru":
                continue
            try:
                gid = int(row[1])
            except ValueError:
                continue
            if gid not in wanted:
                continue
            if len(row) > 7 and (row[6] == "1" or row[7] == "1"):  # разговорное / историческое
                continue
            name = row[3].strip()
            if not name:
                continue
            preferred = len(row) > 4 and row[4] == "1"
            short = len(row) > 5 and row[5] == "1"
            rank = (0 if preferred else 1, 1 if short else 0, len(name))
            if gid not in best or rank < best[gid][0]:
                best[gid] = (rank, name)

    db = sqlite3.connect(args.out)
    db.executescript(
        """
        PRAGMA journal_mode=OFF;
        DROP TABLE IF EXISTS places;
        DROP TABLE IF EXISTS countries;
        CREATE TABLE places(id INTEGER PRIMARY KEY, name TEXT NOT NULL, lat REAL NOT NULL,
                            lon REAL NOT NULL, pop INTEGER, cc TEXT);
        CREATE TABLE countries(cc TEXT PRIMARY KEY, name TEXT NOT NULL);
        """
    )
    no_ru = 0
    rows = []
    for gid, (name, lat, lon, pop, cc) in places.items():
        ru = best.get(gid, (None, None))[1]
        if ru is None:
            no_ru += 1
            ru = name
        rows.append((gid, ru, lat, lon, pop, cc))
    db.executemany("INSERT INTO places VALUES (?,?,?,?,?,?)", rows)
    db.executemany(
        "INSERT INTO countries VALUES (?,?)",
        [(cc, best.get(gid, (None, eng))[1] or eng) for cc, (gid, eng) in countries.items()],
    )
    db.execute("CREATE INDEX idx_places_lat ON places(lat)")
    db.execute("PRAGMA user_version=1")
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"Готово: {len(rows)} мест ({no_ru} без русского названия) -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
