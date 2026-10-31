package com.vornex.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Нажимает системные кнопки за тебя: домой, назад, недавние, блокировка, скриншот, свайпы и нажатия по тексту. */
class VornexAccessibility : AccessibilityService() {

    companion object {
        @Volatile var instance: VornexAccessibility? = null

        private val closeWords = listOf(
            "закрыть все", "закрыть всё", "очистить все", "очистить всё", "удалить все", "убрать все",
            "очистить", "close all", "clear all", "dismiss all"
        )
        private val closeIds = listOf("clear_all", "clearall", "close_all", "dismiss_all", "clean_all", "clear")

        private val sendWords = listOf("Отправить", "Send")
    }

    private val h = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun lockScreen() = Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    fun screenshot() = Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)

    // ---------- Свайпы ----------

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 250) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        dispatchGesture(g, null, null)
    }

    private fun size(): Pair<Float, Float> {
        val m = resources.displayMetrics
        return m.widthPixels.toFloat() to m.heightPixels.toFloat()
    }

    /** dir: "вниз" — прокрутить контент вниз (палец идёт вверх). */
    fun scroll(down: Boolean) {
        val (w, hh) = size()
        if (down) swipe(w / 2, hh * 0.72f, w / 2, hh * 0.28f, 300) else swipe(w / 2, hh * 0.28f, w / 2, hh * 0.72f, 300)
    }

    // ---------- Закрыть все приложения ----------

    /** Открывает недавние, жмёт «Закрыть все». Если кнопки нет, смахивает карточки вверх. */
    fun closeAll(done: (Boolean) -> Unit) {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        h.postDelayed({
            var ok = clickClose()
            if (!ok) {
                h.postDelayed({ ok = clickClose(); if (ok) finishClose(done, true) else swipeAway(done) }, 600)
            } else {
                finishClose(done, true)
            }
        }, 1200)
    }

    private fun finishClose(done: (Boolean) -> Unit, ok: Boolean) {
        done(ok)
        h.postDelayed({ home() }, 800)
    }

    private fun swipeAway(done: (Boolean) -> Unit) {
        val (w, hh) = size()
        var n = 0
        val step = object : Runnable {
            override fun run() {
                swipe(w / 2, hh * 0.62f, w / 2, hh * 0.08f, 220)
                n++
                if (n < 8) h.postDelayed(this, 450) else finishClose(done, true)
            }
        }
        step.run()
    }

    private fun clickClose(): Boolean {
        val roots = allRoots()
        for (r in roots) {
            // по тексту и описанию
            val node = findNode(r) { n ->
                val t = (n.text?.toString() ?: "").lowercase()
                val d = (n.contentDescription?.toString() ?: "").lowercase()
                val id = (n.viewIdResourceName ?: "").lowercase()
                closeWords.any { w -> t == w || d == w || d.contains(w) && w.length > 6 || t.contains(w) && w.length > 6 } ||
                    closeIds.any { id.endsWith(it) || id.contains("clear_all") || id.contains("close_all") }
            }
            if (node != null && clickUp(node)) return true
        }
        return false
    }

    private fun allRoots(): List<AccessibilityNodeInfo> {
        val list = ArrayList<AccessibilityNodeInfo>()
        try {
            windows?.forEach { w -> w.root?.let { list.add(it) } }
        } catch (_: Exception) {
        }
        rootInActiveWindow?.let { if (list.none { r -> r == it }) list.add(it) }
        return list
    }

    private fun findNode(n: AccessibilityNodeInfo?, depth: Int = 0, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null || depth > 25) return null
        if (pred(n)) return n
        for (i in 0 until n.childCount) {
            val r = findNode(n.getChild(i), depth + 1, pred)
            if (r != null) return r
        }
        return null
    }

    private fun clickUp(node: AccessibilityNodeInfo): Boolean {
        var x: AccessibilityNodeInfo? = node
        while (x != null && !x.isClickable) x = x.parent
        return x?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    // ---------- Отправка сообщения ----------

    fun clickSend(done: (Boolean) -> Unit) {
        var tries = 0
        val attempt = object : Runnable {
            override fun run() {
                tries++
                if (clickByTexts(sendWords)) {
                    done(true)
                } else if (tries < 8) {
                    h.postDelayed(this, 1000)
                } else {
                    done(false)
                }
            }
        }
        h.postDelayed(attempt, 2500)
    }

    /** Нажать элемент на экране, в тексте или описании которого есть слово. */
    fun clickByTexts(words: List<String>): Boolean {
        for (r in allRoots()) {
            for (w in words) {
                val lw = w.lowercase()
                val node = findNode(r) { n ->
                    (n.text?.toString() ?: "").lowercase().contains(lw) ||
                        (n.contentDescription?.toString() ?: "").lowercase().contains(lw)
                }
                if (node != null && clickUp(node)) return true
            }
        }
        return false
    }

    /** Тап по точке экрана (доли ширины и высоты) через delayMs. */
    fun tapFraction(fx: Float, fy: Float, delayMs: Long) {
        h.postDelayed({
            val (w, hh) = size()
            val p = Path().apply { moveTo(w * fx, hh * fy) }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
            dispatchGesture(g, null, null)
        }, delayMs)
    }

    /** Повторяет попытку нажатия через delay, пока не получится (до tries раз). */
    fun clickWhenReady(words: List<String>, firstDelayMs: Long, tries: Int, done: (Boolean) -> Unit) {
        var n = 0
        val r = object : Runnable {
            override fun run() {
                n++
                if (clickByTexts(words)) done(true)
                else if (n < tries) h.postDelayed(this, 1200) else done(false)
            }
        }
        h.postDelayed(r, firstDelayMs)
    }
}
