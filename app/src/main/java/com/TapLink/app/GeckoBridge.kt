package com.TapLinkX3.app

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
        this.port = port
        port.setDelegate(this)
        port.postMessage(interfacesMessage())
        while (pending.isNotEmpty()) port.postMessage(pending.removeFirst())
    }

    override fun onDisconnect(port: WebExtension.Port) {
        if (this.port === port) this.port = null
    }

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        val json = message as? JSONObject ?: return
        when (json.optString("type")) {
            "result" -> callbacks.remove(json.optInt("id"))?.onReceiveValue(json.optString("value", "null"))
            "call" -> invoke(json.optString("iface"), json.optString("method"), json.optJSONArray("args"))
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
    }
}
