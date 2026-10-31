#!/usr/bin/env python3
"""
Делает из ZIM-файла Википедии (Kiwix) компактную базу wiki.db для Vornex:
заголовок статьи + первые абзацы (вступление) + быстрый поиск по заголовку.

Установка:   pip install libzim beautifulsoup4 lxml
Запуск:      python build_wiki_db.py wikipedia_ru_all_mini.zim wiki.db
Тест:        python build_wiki_db.py wikipedia_ru_all_mini.zim wiki.db --limit 5000
"""
import argparse
import re
import sqlite3
import sys
import time

from bs4 import BeautifulSoup
from libzim.reader import Archive

MAX_HTML = 80_000      # вступление всегда в начале страницы, остальное не читаем
MAX_LEAD = 800         # максимум символов вступления
MIN_PARAGRAPH = 40     # короче этого абзац считаем мусором

SKIP_TITLE = re.compile(r"^(Служебная|Обсуждение|Категория|Шаблон|Файл|Портал|Википедия|Проект):", re.I)
CITATION = re.compile(r"\[\s*(?:\d+|[a-zа-я]|прим\.[^\]]*|источник не указан[^\]]*)\s*\]", re.I)
SPACES = re.compile(r"\s+")


def norm(s: str) -> str:
    return s.lower().replace("ё", "е")


def extract_lead(raw_html: bytes) -> str:
    soup = BeautifulSoup(raw_html[:MAX_HTML], "lxml")
    for t in soup(["style", "script", "table", "sup", "figure", "math", "audio", "video"]):
        t.decompose()
    for t in soup.select(".hatnote, .mw-editsection, .navbox, .infobox, .thumb, .noprint, .mw-empty-elt"):
        t.decompose()

    root = soup.select_one('section[data-mw-section-id="0"]') or soup.body or soup
    parts = []
    total = 0
    for p in root.find_all("p"):
        text = SPACES.sub(" ", p.get_text(" ", strip=True)).strip()
        text = CITATION.sub("", text)
        text = re.sub(r"\s+([,.;:!?)])", r"\1", text)
        text = re.sub(r"\(\s+", "(", text)
        text = re.sub(r"\(\s*[;,]?\s*\)", "", text)   # пустые скобки после удаления транскрипций
        if len(text) < MIN_PARAGRAPH:
            continue
        parts.append(text)
        total += len(text)
        if total >= MAX_LEAD:
            break
    lead = " ".join(parts).strip()
    if len(lead) > MAX_LEAD:
        cut = lead[:MAX_LEAD]
        end = max(cut.rfind(". "), cut.rfind("! "), cut.rfind("? "))
        lead = cut[: end + 1] if end > 200 else cut.rstrip() + "…"
    return lead


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("zim")
    ap.add_argument("out", nargs="?", default="wiki.db")
    ap.add_argument("--limit", type=int, default=0, help="обработать только N статей (для теста)")
    args = ap.parse_args()

    zim = Archive(args.zim)
    get_by_id = getattr(zim, "get_entry_by_id", None) or getattr(zim, "_get_entry_by_id")
    total = zim.all_entry_count
    print(f"Записей в ZIM: {total}")

    db = sqlite3.connect(args.out)
    db.executescript(
        """
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        DROP TABLE IF EXISTS articles;
        DROP TABLE IF EXISTS fts;
        CREATE TABLE articles(id INTEGER PRIMARY KEY, title TEXT NOT NULL, lead TEXT NOT NULL);
        CREATE VIRTUAL TABLE fts USING fts4(title_lc, tokenize=unicode61);
        """
    )

    t0 = time.time()
    kept = 0
    seen = 0
    for i in range(total):
        try:
            entry = get_by_id(i)
            if entry.is_redirect:
                continue
            title = (entry.title or "").strip()
            if not title or SKIP_TITLE.match(title):
                continue
            item = entry.get_item()
            if not item.mimetype.startswith("text/html"):
                continue
            seen += 1
            lead = extract_lead(bytes(item.content))
        except Exception as e:  # битая запись не должна ронять весь процесс
            print(f"  пропуск записи {i}: {e}", file=sys.stderr)
            continue
        if not lead:
            continue
        kept += 1
        db.execute("INSERT INTO articles(id, title, lead) VALUES (?,?,?)", (kept, title, lead))
        db.execute("INSERT INTO fts(docid, title_lc) VALUES (?,?)", (kept, norm(title)))
        if kept % 20000 == 0:
            db.commit()
            speed = seen / max(time.time() - t0, 1e-6)
            print(f"  статей: {kept}  (просмотрено {seen}, {speed:.0f}/с)")
        if args.limit and kept >= args.limit:
            break

    db.commit()
    print("Оптимизирую поиск…")
    db.execute("INSERT INTO fts(fts) VALUES('optimize')")
    db.execute("PRAGMA user_version=1")
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"Готово: {kept} статей -> {args.out} за {time.time() - t0:.0f} с")
    return 0


if __name__ == "__main__":
    sys.exit(main())
