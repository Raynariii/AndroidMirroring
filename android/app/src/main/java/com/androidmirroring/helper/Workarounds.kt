package com.androidmirroring.helper

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build

/**
 * Makes the helper look enough like the shell app for framework classes that expect an app
 * process (AudioRecord checks the caller's package and attribution). Same approach as scrcpy.
 * Call once from the main thread after Looper.prepareMainLooper().
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object Workarounds {
    const val SHELL_UID = 2000

    private val activityThreadClass by lazy { Class.forName("android.app.ActivityThread") }
    private var activityThread: Any? = null

    fun apply() {
        try {
            val constructor = activityThreadClass.getDeclaredConstructor().apply { isAccessible = true }
            val thread = constructor.newInstance()
            activityThreadClass.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
            activityThreadClass.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
            activityThread = thread

            val bindDataClass = Class.forName("android.app.ActivityThread\$AppBindData")
            val bindData = bindDataClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val appInfo = ApplicationInfo().apply { packageName = SystemServices.SHELL_PACKAGE }
            bindDataClass.getDeclaredField("appInfo").apply { isAccessible = true }.set(bindData, appInfo)
            activityThreadClass.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(thread, bindData)

            val app = Instrumentation.newApplication(Application::class.java, ShellContext.get())
            activityThreadClass.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(thread, app)
        } catch (e: Throwable) {
            Ln.i("workarounds: not applied ($e); audio may be unavailable")
        }
    }

    fun systemContext(): Context =
        activityThreadClass.getDeclaredMethod("getSystemContext").invoke(activityThread) as Context
}

/** A Context that identifies as the shell package, for APIs that check who is calling. */
@SuppressLint("NewApi") // getAttributionSource is only called by the framework on Android 12+.
class ShellContext private constructor(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = SystemServices.SHELL_PACKAGE
    override fun getOpPackageName(): String = SystemServices.SHELL_PACKAGE
    override fun getApplicationContext(): Context = this

    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(Workarounds.SHELL_UID).setPackageName(SystemServices.SHELL_PACKAGE).build()

    companion object {
        private val instance by lazy { ShellContext(Workarounds.systemContext()) }
        fun get(): ShellContext = instance
    }
}
