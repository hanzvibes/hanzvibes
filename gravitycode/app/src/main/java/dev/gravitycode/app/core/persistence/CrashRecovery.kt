package dev.gravitycode.app.core.persistence

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class CrashRecovery(private val context: Context) {
    private val file = File(context.filesDir, "last-crash.txt")

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val writer = StringWriter()
                throwable.printStackTrace(PrintWriter(writer))
                file.writeText(
                    buildString {
                        appendLine("${System.currentTimeMillis()} | ${thread.name}")
                        append(writer.toString().take(40_000))
                    },
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun read(): String? = runCatching { file.takeIf { it.isFile }?.readText()?.takeIf(String::isNotBlank) }.getOrNull()
    fun clear() { file.delete() }
}
