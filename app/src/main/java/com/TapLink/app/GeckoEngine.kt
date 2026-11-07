package com.TapLinkX3.app

import android.content.Context
import android.content.Intent
import android.util.Log
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension

/**
 * TapLink's page engine: GeckoView (Firefox), replacing the system WebView (Chromium 95 on the
 * X3 Pro, not updatable). TapLink's WebViews remain as the shell its UI logic drives; pages load
 * in GeckoView. Debug: `--es gecko_url <url>` on the launch intent opens that URL.
 */
object GeckoEngine {
    const val EXTRA_URL = "gecko_url"
    private const val TAG = "GeckoEngine"

    private const val ANDROID_ASSET_PREFIX = "file:///android_asset/"
    private const val BRIDGE_LOCATION = "resource://android/assets/taplink_bridge/"
    private const val BRIDGE_ID = "taplink-bridge@taplinkx3.app"
    const val BRIDGE_NATIVE_APP = "taplink"

    private var runtime: GeckoRuntime? = null
    private var bridgeExtension: GeckoResult<WebExtension>? = null

    // Serves the app's assets over http://127.0.0.1; see toGeckoUri
    private var assetServer: LocalAssetServer? = null

    /** Debug: URL to open once the session is up, from `--es gecko_url <url>` on the launch intent. */
    var pendingUrl: String? = null

    @Suppress("UNUSED_PARAMETER")
    fun isEnabled(context: Context): Boolean = true

    /** Reads the debug URL from the launch intent. */
    @Suppress("UNUSED_PARAMETER")
    fun applyIntentToggle(context: Context, intent: Intent?): Boolean {
        pendingUrl = intent?.getStringExtra(EXTRA_URL)
        return false
    }

    /** One runtime per process; must be created on the main thread. */
    fun runtime(context: Context): GeckoRuntime =
            runtime
                    ?: GeckoRuntime.create(
                                    context.applicationContext,
                                    GeckoRuntimeSettings.Builder()
                                            // Page console messages go to logcat (tag GeckoConsole) while we port
                                            .consoleOutput(true)
                                            // Match the WebView's textZoom = 80 (MainActivity)
                                            .automaticFontSizeAdjustment(false)
                                            .fontSizeFactor(0.8f)
                                            // One eye; the panel is 1280x480 for both, and page
                                            // fullscreen otherwise sizes to both eyes. App pages use
                                            // width=640 viewports, which this would shrink as
                                            // device-width.
                                            .screenSizeOverride(640, 480)
                                            .build()
                            )
                            .also {
                                runtime = it
                                assetServer =
                                        try {
                                            LocalAssetServer(context)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to start the asset server", e)
                                            null
                                        }
                            }

    /** The bundled taplink_bridge extension (see GeckoBridge), installed once per runtime. */
    fun bridgeExtension(context: Context): GeckoResult<WebExtension> =
            bridgeExtension
                    ?: runtime(context)
                            .webExtensionController
                            .ensureBuiltIn(BRIDGE_LOCATION, BRIDGE_ID)
                            .also { bridgeExtension = it }

    /**
     * The dashboard and other app pages are file:///android_asset/ URLs for WebView. Extension
     * content scripts (the bridge) don't run on Gecko's resource:// or file:// pages, so load them
     * from the local asset server instead.
     */
    fun toGeckoUri(url: String): String {
        if (!url.startsWith(ANDROID_ASSET_PREFIX)) return url
        val path = url.removePrefix(ANDROID_ASSET_PREFIX)
        return (assetServer?.baseUrl ?: "resource://android/assets/") + path
    }

    /** Inverse of toGeckoUri, so the app's URL checks keep seeing file:///android_asset/. */
    fun fromGeckoUri(url: String?): String? {
        val prefix = assetServer?.baseUrl ?: return url
        return if (url != null && url.startsWith(prefix)) {
            ANDROID_ASSET_PREFIX + url.removePrefix(prefix)
        } else {
            url
        }
    }
}
