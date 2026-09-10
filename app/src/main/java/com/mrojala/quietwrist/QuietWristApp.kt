package com.mrojala.quietwrist

import android.app.Application
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exists only to record crashes into the on-device log.
 *
 * There is no local Android SDK for this project and no `adb logcat` when the
 * phone is in a pocket, so a crash that leaves no trace cannot be investigated at
 * all. The handler writes the exception where the app's own diagnostics screen
 * will show it, then hands off to the default handler so the crash still happens
 * normally — this suppresses nothing.
 */
class QuietWristApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(threadName: String, error: Throwable) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(System.currentTimeMillis()))
        val cause = generateSequence(error) { it.cause }.last()
        val frame = cause.stackTrace.firstOrNull { it.className.startsWith(PACKAGE) }
            ?: cause.stackTrace.firstOrNull()

        Prefs.logBlocking(
            this,
            "$stamp  CRASH   ${cause.javaClass.simpleName}: ${cause.message}" +
                "  —  at ${frame?.methodName}:${frame?.lineNumber} on $threadName",
        )
    }

    private companion object {
        const val PACKAGE = "com.mrojala.quietwrist"
    }
}
