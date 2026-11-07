package com.TapLinkX3.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Firefox add-ons for the GeckoView engine: installs from addons.mozilla.org (Gecko checks
 * Mozilla's signature), keeps track of each add-on's toolbar button so its popup can be opened, and
 * routes the tabs and options pages add-ons open to the app. All calls on the main thread.
 */
class GeckoExtensions(private val runtime: GeckoRuntime, private val host: Host) {

    interface Host {
        /** Show an add-on popup's session in place of the page until [closePopup]. */
        fun showExtensionPopup(session: GeckoSession)

        /** Load a URL an add-on opened (a new tab, its options page) in the current page. */
        fun openExtensionUrl(url: String)

        fun extensionsChanged()

        fun extensionMessage(text: String)
    }

    private val controller: WebExtensionController = runtime.webExtensionController
    private val actions = HashMap<String, WebExtension.Action>()
    private var extensions: List<WebExtension> = emptyList()

    private val actionDelegate =
            object : WebExtension.ActionDelegate {
                override fun onBrowserAction(
                        extension: WebExtension,
                        session: GeckoSession?,
                        action: WebExtension.Action
                ) {
                    actions[extension.id] = action
                }

                override fun onTogglePopup(
                        extension: WebExtension,
                        action: WebExtension.Action
                ): GeckoResult<GeckoSession>? = popupSession(extension)

                override fun onOpenPopup(
                        extension: WebExtension,
                        action: WebExtension.Action
                ): GeckoResult<GeckoSession>? = popupSession(extension)
            }

    // No multi-tab support yet: add-on tabs and options pages open in the current page
    private val tabDelegate =
            object : WebExtension.TabDelegate {
                override fun onNewTab(
                        source: WebExtension,
                        createDetails: WebExtension.CreateTabDetails
                ): GeckoResult<GeckoSession>? {
                    createDetails.url?.let { host.openExtensionUrl(it) }
                    return null
                }

                override fun onOpenOptionsPage(source: WebExtension) {
                    source.metaData.optionsPageUrl?.let { host.openExtensionUrl(it) }
                }
            }

    init {
        // The user picks what to install from TapLink's Extensions page, so accept the
        // permissions an add-on asks for (Firefox would show them in a dialog).
        controller.setPromptDelegate(
                object : WebExtensionController.PromptDelegate {
                    override fun onInstallPromptRequest(
                            extension: WebExtension,
                            permissions: Array<String>,
                            origins: Array<String>,
                            dataCollectionPermissions: Array<String>
                    ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                        Log.d(TAG, "install ${extension.id}: ${permissions.joinToString()} ${origins.joinToString()}")
                        return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, false, false))
                    }

                    override fun onUpdatePrompt(
                            extension: WebExtension,
                            newPermissions: Array<String>,
                            newOrigins: Array<String>,
                            newDataCollectionPermissions: Array<String>
                    ): GeckoResult<AllowOrDeny>? = GeckoResult.allow()

                    override fun onOptionalPrompt(
                            extension: WebExtension,
                            permissions: Array<String>,
                            origins: Array<String>,
                            dataCollectionPermissions: Array<String>
                    ): GeckoResult<AllowOrDeny>? = GeckoResult.allow()
                }
        )
        refresh()
    }

    private fun popupSession(extension: WebExtension): GeckoResult<GeckoSession> {
        Log.d(TAG, "popup for ${extension.id}")
        val session = GeckoSession()
        if (!session.isOpen) session.open(runtime)
        host.showExtensionPopup(session)
        return GeckoResult.fromValue(session)
    }

    private fun attach(extension: WebExtension) {
        if (extension.isBuiltIn) return
        extension.setActionDelegate(actionDelegate)
        extension.setTabDelegate(tabDelegate)
    }

    fun refresh() {
        controller.list().accept(
                { list ->
                    extensions = list.orEmpty().filter { !it.isBuiltIn }
                    extensions.forEach(::attach)
                    host.extensionsChanged()
                },
                { e -> Log.e(TAG, "list failed", e) }
        )
    }

    fun install(url: String) {
        host.extensionMessage("Installing…")
        controller.install(url).accept(
                { extension ->
                    if (extension != null) {
                        attach(extension)
                        host.extensionMessage("Installed ${extension.metaData.name ?: extension.id}")
                    }
                    refresh()
                },
                { e ->
                    val code = (e as? WebExtension.InstallException)?.code
                    Log.e(TAG, "install $url failed (code $code)", e)
                    host.extensionMessage("Install failed${if (code != null) " (error $code)" else ""}")
                }
        )
    }

    private fun find(id: String): WebExtension? = extensions.firstOrNull { it.id == id }

    /** Opens the add-on's toolbar popup; false if it has none. */
    fun openPopup(id: String): Boolean {
        val action = actions[id] ?: return false
        action.click()
        return true
    }

    fun openOptions(id: String): Boolean {
        val url = find(id)?.metaData?.optionsPageUrl ?: return false
        host.openExtensionUrl(url)
        return true
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val extension = find(id) ?: return
        val result =
                if (enabled) controller.enable(extension, WebExtensionController.EnableSource.USER)
                else controller.disable(extension, WebExtensionController.EnableSource.USER)
        result.accept({ refresh() }, { e -> Log.e(TAG, "enable $id failed", e) })
    }

    fun uninstall(id: String) {
        val extension = find(id) ?: return
        controller.uninstall(extension).accept(
                {
                    actions.remove(id)
                    host.extensionMessage("Removed ${extension.metaData.name ?: id}")
                    refresh()
                },
                { e -> Log.e(TAG, "uninstall $id failed", e) }
        )
    }

    fun toJson(): String {
        val array = JSONArray()
        for (extension in extensions) {
            val meta = extension.metaData
            array.put(
                    JSONObject()
                            .put("id", extension.id)
                            .put("name", meta.name ?: extension.id)
                            .put("version", meta.version)
                            .put("enabled", meta.enabled)
                            .put("hasPopup", actions.containsKey(extension.id))
                            .put("hasOptions", meta.optionsPageUrl != null)
            )
        }
        return array.toString()
    }

    companion object {
        private const val TAG = "GeckoExtensions"
    }
}
