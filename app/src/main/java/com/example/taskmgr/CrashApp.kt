package com.example.taskmgr

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File

class CrashApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val f = File(base.filesDir, "crash.txt")
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { f.writeText(Log.getStackTraceString(e)) } catch (_: Throwable) {}
            old?.uncaughtException(t, e)
        }
    }
}