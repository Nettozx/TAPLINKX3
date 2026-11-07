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
        /** Show an add-on popup or tab session in place of the page until Back or it closes. */
        fun showExtensionPopup(session: GeckoSession)

        /** The add-on closed its tab or popup (e.g. Proton VPN after signing in). */
        fun closeExtensionSession(session: GeckoSession)

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
                    if (!actions.containsKey(extension.id)) Log.d(TAG, "toolbar button for ${extension.id}")
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

    // Tabs an add-on opens (sign-in, onboarding) and its options page show like popups: in place of
    // the page until Back, or until the add-on closes them. Refusing a tab fails the add-on's
    // tabs.create() ("Cannot create new tab"), which left Proton VPN's login button dead.
    private val tabDelegate =
            object : WebExtension.TabDelegate {
                override fun onNewTab(
                        source: WebExtension,
                        createDetails: WebExtension.CreateTabDetails
                ): GeckoResult<GeckoSession>? {
                    Log.d(TAG, "${source.id} opens a tab: ${createDetails.url}")
                    val session = newSession(source)
                    host.showExtensionPopup(session)
                    return GeckoResult.fromValue(session)
                }

                override fun onOpenOptionsPage(source: WebExtension) {
                    openOptionsPage(source)
                }
            }

    private val sessionTabDelegate =
            object : WebExtension.SessionTabDelegate {
                override fun onCloseTab(
                        source: WebExtension?,
                        session: GeckoSession
                ): GeckoResult<AllowOrDeny> {
                    Log.d(TAG, "${source?.id} closes its tab")
                    host.closeExtensionSession(session)
                    return GeckoResult.allow()
                }
            }

    private fun newSession(extension: WebExtension): GeckoSession {
        val session = GeckoSession()
        if (!session.isOpen) session.open(runtime)
        session.webExtensionController.setTabDelegate(extension, sessionTabDelegate)
        return session
    }

    private fun openOptionsPage(extension: WebExtension): Boolean {
        val url = extension.metaData.optionsPageUrl ?: return false
        val session = newSession(extension)
        session.loadUri(url)
        host.showExtensionPopup(session)
        return true
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
        val session = newSession(extension)
        host.showExtensionPopup(session)
        return GeckoResult.fromValue(session)
    }

    // The windows' page sessions; the active one is Gecko's active tab
    private val pageSessions = mutableListOf<GeckoSession>()
    private var activeSession: GeckoSession? = null

    fun addPageSession(session: GeckoSession) {
        if (pageSessions.contains(session)) return
        pageSessions.add(session)
        extensions.forEach { attachToSession(it, session) }
    }

    fun removePageSession(session: GeckoSession) {
        pageSessions.remove(session)
        if (activeSession === session) activeSession = null
    }

    /** Add-ons act on the active tab; toolbar popups don't open without one. */
    fun setActiveSession(session: GeckoSession) {
        if (activeSession === session) return
        activeSession?.let { if (it.isOpen) controller.setTabActive(it, false) }
        activeSession = session
        controller.setTabActive(session, true)
    }

    private fun attach(extension: WebExtension) {
        if (extension.isBuiltIn) return
        extension.setActionDelegate(actionDelegate)
        extension.setTabDelegate(tabDelegate)
        pageSessions.forEach { attachToSession(extension, it) }
    }

    // Popups open for the page's tab, as on Firefox for Android
    private fun attachToSession(extension: WebExtension, session: GeckoSession) {
        if (extension.isBuiltIn) return
        session.webExtensionController.setActionDelegate(extension, actionDelegate)
        session.webExtensionController.setTabDelegate(extension, sessionTabDelegate)
    }

    fun refresh() {
        controller.list().accept(
                { list ->
                    Log.d(TAG, "list: " + list.orEmpty().joinToString { "${it.id}${if (it.isBuiltIn) "(built-in)" else ""}/${it.metaData.enabled}" })
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
        val action = actions[id]
        Log.d(TAG, "open popup $id: ${if (action != null) "clicking its button" else "no toolbar button"}")
        action ?: return false
        action.click()
        return true
    }

    fun openOptions(id: String): Boolean = find(id)?.let { openOptionsPage(it) } ?: false

    fun setEnabled(id: String, enabled: Boolean) {
        Log.d(TAG, "set $id enabled=$enabled (known: ${find(id) != null})")
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
