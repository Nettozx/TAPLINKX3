package com.TapLinkX3.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import java.lang.reflect.Method
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

/**
 * App side of the taplink_bridge WebExtension: evaluateJavascript and addJavascriptInterface
 * for a GeckoSession. The content script in the current page connects a native port ("taplink");
 * scripts are sent over it and @JavascriptInterface calls come back.
 * Used on the main thread, like WebView's equivalents.
 */
class GeckoBridge : WebExtension.MessageDelegate, WebExtension.PortDelegate {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var port: WebExtension.Port? = null
    private val pending = ArrayDeque<JSONObject>() // sent once the next page connects
    private val callbacks = HashMap<Int, ValueCallback<String>>()
    private var nextId = 1
    private val interfaces = LinkedHashMap<String, Any>()

    // WebView calls @JavascriptInterface methods off the main thread too
    private val callExecutor = Executors.newSingleThreadExecutor()

    fun evaluate(script: String, callback: ValueCallback<String>?) {
        val message = JSONObject().put("type", "eval").put("code", script)
        if (callback != null) {
            val id = nextId++
            callbacks[id] = callback
            message.put("id", id)
            // WebView always calls back (null on failure); callers rely on it, e.g. the tap's UP
            // event is sent from the callback, so a script that never answers would wedge input.
            mainHandler.postDelayed(
                    {
                        callbacks.remove(id)?.let {
                            Log.w(TAG, "Script $id timed out (port=${port != null}): ${script.take(80)}")
                            it.onReceiveValue("null")
                        }
                    },
                    RESULT_TIMEOUT_MS
            )
        }
        val current = port
        if (current != null) {
            current.postMessage(message)
        } else {
            pending.addLast(message)
            while (pending.size > MAX_PENDING) pending.removeFirst()
        }
    }

    fun addInterface(obj: Any, name: String) {
        interfaces[name] = obj
        port?.postMessage(interfacesMessage())
    }

    fun removeInterface(name: String) {
        interfaces.remove(name)
    }

    override fun onConnect(port: WebExtension.Port) {
        Log.d(TAG, "Page connected (${pending.size} pending)")
        this.port = port
        loggedCalls.clear()
        port.setDelegate(this)
        port.postMessage(interfacesMessage())
        while (pending.isNotEmpty()) port.postMessage(pending.removeFirst())
    }

    override fun onDisconnect(port: WebExtension.Port) {
        Log.d(TAG, "Page disconnected (current=${this.port === port})")
        if (this.port === port) this.port = null
    }

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        val json = message as? JSONObject ?: return
        when (json.optString("type")) {
            "log" -> Log.d(TAG, "page: ${json.optString("text")}")
            "result" -> callbacks.remove(json.optInt("id"))?.onReceiveValue(json.optString("value", "null"))
            // Args come as a JSON string: GeckoView's message conversion rejects mixed-type arrays
            // (e.g. ["id", false]) and drops the whole message
            "call" -> {
                val args = json.optString("argsJson", "").takeIf { it.isNotEmpty() }?.let { JSONArray(it) }
                        ?: json.optJSONArray("args")
                invoke(json.optString("iface"), json.optString("method"), args)
            }
        }
    }

    private fun interfacesMessage(): JSONObject {
        val defs = JSONObject()
        for ((name, obj) in interfaces) {
            defs.put(name, JSONArray(bridgeMethods(obj).map { it.name }.distinct()))
        }
        return JSONObject().put("type", "interfaces").put("defs", defs)
    }

    private fun bridgeMethods(obj: Any): List<Method> =
            obj.javaClass.methods.filter { it.isAnnotationPresent(JavascriptInterface::class.java) }

    // First call of each method per page, to see which page hooks reach the app
    private val loggedCalls = HashSet<String>()

    private fun invoke(iface: String, methodName: String, args: JSONArray?) {
        val obj = interfaces[iface] ?: return
        val argCount = args?.length() ?: 0
        val method =
                bridgeMethods(obj).firstOrNull { it.name == methodName && it.parameterTypes.size == argCount }
                        ?: run {
                            Log.w(TAG, "No bridge method $iface.$methodName/$argCount")
                            return
                        }
        val params = Array(argCount) { i -> convert(args?.opt(i), method.parameterTypes[i]) }
        callExecutor.execute {
            try {
                method.isAccessible = true // anonymous/private interface classes
                method.invoke(obj, *params)
            } catch (e: Exception) {
                Log.e(TAG, "Bridge call $iface.$methodName failed", e)
            }
        }
    }

    private fun convert(value: Any?, type: Class<*>): Any? {
        val v = if (value == JSONObject.NULL) null else value
        val number = v as? Number ?: v?.toString()?.toDoubleOrNull()
        return when (type) {
            String::class.java -> v?.toString()
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> number?.toInt() ?: 0
            Long::class.javaPrimitiveType, Long::class.javaObjectType -> number?.toLong() ?: 0L
            Double::class.javaPrimitiveType, Double::class.javaObjectType -> number?.toDouble() ?: 0.0
            Float::class.javaPrimitiveType, Float::class.javaObjectType -> number?.toFloat() ?: 0f
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType ->
                    v as? Boolean ?: (v?.toString() == "true")
            else -> v
        }
    }

    companion object {
        private const val TAG = "GeckoBridge"
        private const val MAX_PENDING = 50
        private const val RESULT_TIMEOUT_MS = 1000L
    }
}
