package com.akay.pdfscanner

import android.app.Application
import android.content.Context

class MyApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val prefs = base.getSharedPreferences("crash", Context.MODE_PRIVATE)
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            prefs.edit().putString("log", android.util.Log.getStackTraceString(e)).commit()
            old?.uncaughtException(t, e)
        }
    }
}
