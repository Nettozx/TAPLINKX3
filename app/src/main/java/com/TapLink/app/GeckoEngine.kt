package com.TapLinkX3.app

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension

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
    private const val TAG = "GeckoEngine"

    private const val ANDROID_ASSET_PREFIX = "file:///android_asset/"
    private const val BRIDGE_LOCATION = "resource://android/assets/taplink_bridge/"
    private const val BRIDGE_ID = "taplink-bridge@taplinkx3.app"
    const val BRIDGE_NATIVE_APP = "taplink"

    private var runtime: GeckoRuntime? = null
    private var bridgeExtension: GeckoResult<WebExtension>? = null

    // file:// copy of the app's HTML assets; see toGeckoUri
    private var assetsCopyPrefix: String? = null

    /** Debug: URL to open once the session is up, from `--es gecko_url <url>` on the launch intent. */
    var pendingUrl: String? = null

    fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED, false)

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
                            .also {
                                runtime = it
                                copyHtmlAssets(context.applicationContext)
                            }

    /** The bundled taplink_bridge extension (see GeckoBridge), installed once per runtime. */
    fun bridgeExtension(context: Context): GeckoResult<WebExtension> =
            bridgeExtension
                    ?: runtime(context)
                            .webExtensionController
                            .ensureBuiltIn(BRIDGE_LOCATION, BRIDGE_ID)
                            .also { bridgeExtension = it }

    /**
     * The dashboard and other app pages are file:///android_asset/ URLs for WebView. Gecko serves
     * assets from resource://, where extension content scripts (the bridge) can't run, so load a
     * file:// copy instead.
     */
    fun toGeckoUri(url: String): String {
        if (!url.startsWith(ANDROID_ASSET_PREFIX)) return url
        val path = url.removePrefix(ANDROID_ASSET_PREFIX)
        return (assetsCopyPrefix ?: "resource://android/assets/") + path
    }

    /** Inverse of toGeckoUri, so the app's URL checks keep seeing file:///android_asset/. */
    fun fromGeckoUri(url: String?): String? {
        val prefix = assetsCopyPrefix ?: return url
        return if (url != null && url.startsWith(prefix)) {
            ANDROID_ASSET_PREFIX + url.removePrefix(prefix)
        } else {
            url
        }
    }

    private fun copyHtmlAssets(context: Context) {
        try {
            val dir = File(context.filesDir, "gecko_assets").apply { mkdirs() }
            context.assets.list("")?.filter { it.endsWith(".html") }?.forEach { name ->
                context.assets.open(name).use { input ->
                    File(dir, name).outputStream().use { input.copyTo(it) }
                }
            }
            assetsCopyPrefix = "file://" + dir.absolutePath + "/"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy HTML assets", e)
        }
    }
}
