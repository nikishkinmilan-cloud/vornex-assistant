# Базы знаний для Vornex (делаются один раз на компе)

Всё это нужно, чтобы Боря знал ответы и свою геопозицию **без интернета**.

## 1. Википедия → wiki.db
```
pip install libzim beautifulsoup4 lxml
```
1. Скачай ZIM русской Википедии со страницы https://download.kiwix.org/zim/wikipedia/
   Файл `wikipedia_ru_all_mini_*.zim` (только вступления статей, самый лёгкий).
   Если mini для русского нет, бери `wikipedia_ru_all_nopic_*.zim` (больше и обрабатывается дольше).
2. Собери базу:
```
python tools/build_wiki_db.py wikipedia_ru_all_mini_XXXX-XX.zim wiki.db
```
Для проверки на маленьком куске: добавь `--limit 5000`.

## 2. Места → places.db
```
python tools/build_places_db.py places.db
```
Сам скачает данные GeoNames (около 200 МБ один раз).

## 3. Нейросеть (.litertlm)
Скачай с https://huggingface.co/litert-community файл формата `.litertlm`:
- `litert-community/Qwen2.5-1.5B-Instruct` (быстрее, меньше)
- `litert-community/gemma-3n-E2B-it-litert-lm` (умнее, но больше и медленнее; нужно принять лицензию Google на странице)

## 4. На телефон
Скинь три файла на телефон (USB-кабель или Telegram «Избранное»), потом в приложении Vornex:
кнопки **4**, **5**, **6** → выбери файл. Потом «Остановить» и «Запустить».
