package com.TapLinkX3.app

import android.content.Context
import android.content.Intent
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * Experimental GeckoView page engine (the system WebView on the X3 Pro is Chromium 95).
 *
 * Off by default. Toggle with:
 *   adb shell am start -n com.TapLinkX3.app/.MainActivity --ez gecko_engine true|false
 * The setting is persisted and takes effect from the next app start.
 */
object GeckoEngine {
    const val EXTRA_ENABLE = "gecko_engine"
    const val EXTRA_URL = "gecko_url"
    private const val KEY_ENABLED = "gecko_engine_enabled"

    private var runtime: GeckoRuntime? = null

    fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED, false)

    /** Debug: URL to open once the session is up, from `--es gecko_url <url>` on the launch intent. */
    var pendingUrl: String? = null

    /** Persists the toggle from the launch intent; returns true if it changed. */
    fun applyIntentToggle(context: Context, intent: Intent?): Boolean {
        pendingUrl = intent?.getStringExtra(EXTRA_URL)
        if (intent == null || !intent.hasExtra(EXTRA_ENABLE)) return false
        val enable = intent.getBooleanExtra(EXTRA_ENABLE, false)
        if (enable == isEnabled(context)) return false
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enable)
                .commit()
        return true
    }

    /** One runtime per process; must be created on the main thread. */
    fun runtime(context: Context): GeckoRuntime =
            runtime
                    ?: GeckoRuntime.create(
                                    context.applicationContext,
                                    // Page console messages go to logcat (tag GeckoConsole) while we port
                                    GeckoRuntimeSettings.Builder().consoleOutput(true).build()
                            )
                            .also { runtime = it }

    /** Gecko serves app assets from resource://android/assets/ instead of file:///android_asset/. */
    fun toGeckoUri(url: String): String =
            if (url.startsWith("file:///android_asset/")) {
                "resource://android/assets/" + url.removePrefix("file:///android_asset/")
            } else {
                url
            }
}
