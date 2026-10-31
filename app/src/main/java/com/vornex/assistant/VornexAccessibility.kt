package com.vornex.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Нажимает системные кнопки за тебя: домой, назад, недавние, блокировка, скриншот. */
class VornexAccessibility : AccessibilityService() {

    companion object {
        @Volatile var instance: VornexAccessibility? = null

        // Подписи кнопки «закрыть все» в окне недавних приложений. На разных прошивках разные.
        private val closeWords = listOf(
            "закрыть все", "закрыть всё", "очистить все", "очистить всё", "удалить все",
            "close all", "clear all"
        )
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

    /** Открывает недавние приложения и пытается нажать «Закрыть все». */
    fun closeAll(done: (Boolean) -> Unit) {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        h.postDelayed({
            val ok = clickCloseAll(rootInActiveWindow)
            done(ok)
            h.postDelayed({ home() }, 700)
        }, 1000)
    }

    private fun clickCloseAll(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        for (w in closeWords) {
            for (n in root.findAccessibilityNodeInfosByText(w)) {
                var x: AccessibilityNodeInfo? = n
                while (x != null && !x.isClickable) x = x.parent
                if (x != null && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }
        return false
    }
}
