package com.tvtime.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * Starts GeckoView's runtime when the app starts (it must exist before the first screen
 * resumes). GeckoView runs extra processes of this app (content, crash helper); those must
 * not create a runtime, so this only happens in the main process.
 */
class TvTimeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (isMainProcess()) runtime(this)
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        return name == null || name == packageName
    }

    companion object {
        private var instance: GeckoRuntime? = null

        fun runtime(context: Context): GeckoRuntime =
            instance ?: GeckoRuntime.create(
                context.applicationContext,
                GeckoRuntimeSettings.Builder()
                    .consoleOutput(true)
                    .build(),
            ).also { instance = it }
    }
}
