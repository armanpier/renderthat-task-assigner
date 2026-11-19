package com.renderthat.taskassigner

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LiveLogger {

    private const val MAX_LOGS = 150
    private val logs = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val mainHandler: Handler? by lazy {
        try {
            Handler(Looper.getMainLooper())
        } catch (_: Throwable) {
            null
        }
    }

    var onLogListener: ((String) -> Unit)? = null

    @Synchronized
    fun log(tag: String, message: String) {
        val time = dateFormat.format(Date())
        val formatted = "[$time][$tag] $message"
        logs.add(formatted)
        if (logs.size > MAX_LOGS) {
            logs.removeAt(0)
        }
        val handler = mainHandler
        if (handler != null) {
            handler.post {
                onLogListener?.invoke(formatted)
            }
        } else {
            onLogListener?.invoke(formatted)
        }
    }

    @Synchronized
    fun getAllLogs(): String {
        return logs.joinToString("\n")
    }

    @Synchronized
    fun clear() {
        logs.clear()
        val handler = mainHandler
        if (handler != null) {
            handler.post {
                onLogListener?.invoke("")
            }
        } else {
            onLogListener?.invoke("")
        }
    }
}
