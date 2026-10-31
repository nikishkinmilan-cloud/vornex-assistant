package com.vornex.assistant

/** Общее состояние между сервисом и экраном. */
object VornexState {
    @Volatile var status: String = "Остановлен"
    @Volatile var lastHeard: String = ""
    @Volatile var running: Boolean = false
}
