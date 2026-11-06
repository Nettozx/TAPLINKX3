package com.TapLinkX3.app

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Serves the app's assets on http://127.0.0.1 for the GeckoView engine. Gecko serves assets from
 * resource://, and WebExtension content scripts (the page bridge) don't run on resource:// or
 * file:// pages; on a plain http origin they do. Loopback only, read-only, GET/HEAD.
 */
class LocalAssetServer(context: Context) {
    private val assets = context.applicationContext.assets
    private val server: ServerSocket = bind()
    private val workers = Executors.newFixedThreadPool(2)

    /** e.g. http://127.0.0.1:37813/ */
    val baseUrl: String = "http://127.0.0.1:${server.localPort}/"

    init {
        Thread({ acceptLoop() }, "LocalAssetServer").apply { isDaemon = true }.start()
    }

    // A fixed port keeps the pages' origin, and so their localStorage, stable across launches
    private fun bind(): ServerSocket {
        val loopback = InetAddress.getByName("127.0.0.1")
        return try {
            ServerSocket(PREFERRED_PORT, 16, loopback)
        } catch (e: Exception) {
            Log.w(TAG, "Port $PREFERRED_PORT unavailable, using a random port", e)
            ServerSocket(0, 16, loopback)
        }
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            try {
                val socket = server.accept()
                workers.execute { serve(socket) }
            } catch (e: Exception) {
                Log.e(TAG, "accept failed", e)
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                while (!reader.readLine().isNullOrEmpty()) {
                    // skip headers
                }
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: ""
                val path = parts.getOrNull(1)?.substringBefore('?')?.substringBefore('#')?.removePrefix("/") ?: ""
                val out = s.getOutputStream()

                if ((method != "GET" && method != "HEAD") || path.isEmpty() || path.contains("..")) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                val body =
                        try {
                            assets.open(java.net.URLDecoder.decode(path, "UTF-8")).use { it.readBytes() }
                        } catch (e: Exception) {
                            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            return
                        }
                out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: ${contentType(path)}\r\n" +
                                        "Content-Length: ${body.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n")
                                .toByteArray()
                )
                if (method == "GET") out.write(body)
                out.flush()
            } catch (e: Exception) {
                Log.w(TAG, "request failed", e)
            }
        }
    }

    private fun contentType(path: String): String =
            when (path.substringAfterLast('.', "").lowercase()) {
                "html", "htm" -> "text/html; charset=utf-8"
                "js" -> "text/javascript; charset=utf-8"
                "css" -> "text/css; charset=utf-8"
                "json" -> "application/json"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "svg" -> "image/svg+xml"
                "webp" -> "image/webp"
                else -> "application/octet-stream"
            }

    companion object {
        private const val TAG = "LocalAssetServer"
        private const val PREFERRED_PORT = 37813
    }
}
