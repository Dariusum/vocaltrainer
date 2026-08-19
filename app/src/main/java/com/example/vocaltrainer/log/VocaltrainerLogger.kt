package com.example.vocaltrainer.log

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Einfacher datei-basierter Logger + globaler Crash-Handler (gleiches Muster wie in
 * der Vault-App). Speichert unter filesDir/logs/vocaltrainer.log, damit sich
 * Abstürze auch ohne funktionierendes USB-Debugging (adb) diagnostizieren lassen —
 * abrufbar über die Hilfe-Seite in der App.
 */
object VocaltrainerLogger {

    private const val MAX_FILE_SIZE_BYTES = 200_000L
    private const val LOG_FILE_NAME = "vocaltrainer.log"

    @Volatile private var logFile: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        logFile = File(dir, LOG_FILE_NAME)
        installUncaughtExceptionHandler()
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        i("VocaltrainerApp", "=== App gestartet (Version $versionName) ===")
    }

    fun d(tag: String, message: String) = write("D", tag, message)
    fun i(tag: String, message: String) = write("I", tag, message)
    fun w(tag: String, message: String, throwable: Throwable? = null) = write("W", tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = write("E", tag, message, throwable)

    fun readLog(): String = logFile?.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
        ?: "Noch kein Log vorhanden."

    fun clear() {
        runCatching { logFile?.writeText("") }
    }

    private fun installUncaughtExceptionHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            e("Crash", "Unbehandelte Ausnahme in Thread ${thread.name}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    @Synchronized
    private fun write(level: String, tag: String, message: String, throwable: Throwable? = null) {
        when (level) {
            "E" -> Log.e(tag, message, throwable)
            "W" -> Log.w(tag, message, throwable)
            "I" -> Log.i(tag, message)
            else -> Log.d(tag, message)
        }

        val file = logFile ?: return
        try {
            rotateIfNeeded(file)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.GERMANY).format(Date())
            val line = buildString {
                append(timestamp).append(' ').append(level).append('/').append(tag).append(": ").append(message).append('\n')
                if (throwable != null) {
                    append(Log.getStackTraceString(throwable)).append('\n')
                }
            }
            file.appendText(line)
        } catch (_: Exception) {
            // Logging darf selbst niemals zum Absturz führen.
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (file.exists() && file.length() > MAX_FILE_SIZE_BYTES) {
            val keep = file.readText().takeLast((MAX_FILE_SIZE_BYTES / 2).toInt())
            file.writeText("--- Log gekürzt ---\n$keep")
        }
    }
}
