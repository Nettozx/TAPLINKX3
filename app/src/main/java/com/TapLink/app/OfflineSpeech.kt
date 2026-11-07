package com.TapLinkX3.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Offline voice typing with Vosk. The small English model (~40 MB) is downloaded once into the
 * app's files on first use; recognition then runs on the glasses without a connection. One
 * utterance per [toggle]: listening stops at the first pause and the text is delivered.
 * Callbacks arrive on the main thread.
 */
class OfflineSpeech(private val context: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        fun onStatus(text: String)

        fun onListening(active: Boolean)

        fun onText(text: String)

        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var model: Model? = null
    private var service: SpeechService? = null
    private var preparing = false

    val isBusy: Boolean
        get() = service != null || preparing

    private val modelDir: File
        get() = File(context.filesDir, MODEL_NAME)

    /** Starts listening, or stops and delivers what was heard so far. */
    fun toggle() {
        if (service != null) {
            service?.stop() // onFinalResult delivers the text
            return
        }
        if (preparing) return
        val loaded = model
        if (loaded != null) {
            start(loaded)
            return
        }
        preparing = true
        worker.execute {
            try {
                if (!File(modelDir, READY_MARKER).exists()) download()
                main.post { callbacks.onStatus("Loading voice model…") }
                val newModel = Model(modelDir.absolutePath)
                main.post {
                    preparing = false
                    model = newModel
                    start(newModel)
                }
            } catch (e: Exception) {
                Log.e(TAG, "model setup failed", e)
                main.post {
                    preparing = false
                    callbacks.onError("Voice model unavailable: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun start(model: Model) {
        try {
            val recognizer = Recognizer(model, SAMPLE_RATE)
            val newService = SpeechService(recognizer, SAMPLE_RATE)
            service = newService
            newService.startListening(listener, LISTEN_TIMEOUT_MS)
            callbacks.onListening(true)
            callbacks.onStatus("Listening…")
        } catch (e: IOException) {
            Log.e(TAG, "start failed", e)
            finish()
            callbacks.onError("Couldn't start the microphone")
        }
    }

    // Stop Vosk's recording thread before releasing the microphone: releasing it while the thread
    // still reads makes Vosk throw "error reading audio buffer" on that thread, crashing the app
    private fun finish() {
        val current = service ?: return
        service = null
        current.cancel()
        current.shutdown()
        callbacks.onListening(false)
    }

    private fun deliver(json: String?) {
        val text = try {
            JSONObject(json ?: "{}").optString("text").trim()
        } catch (e: Exception) {
            ""
        }
        finish()
        if (text.isEmpty()) callbacks.onError("Didn't catch that") else callbacks.onText(text)
    }

    // Vosk calls these on the main thread
    private val listener =
            object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {}

                // End of an utterance (a pause): that's the dictation
                override fun onResult(hypothesis: String?) {
                    if (service == null) return
                    val text = try {
                        JSONObject(hypothesis ?: "{}").optString("text").trim()
                    } catch (e: Exception) {
                        ""
                    }
                    if (text.isNotEmpty()) deliver(hypothesis)
                }

                override fun onFinalResult(hypothesis: String?) {
                    if (service != null) deliver(hypothesis)
                }

                override fun onError(exception: Exception?) {
                    Log.e(TAG, "recognition error", exception)
                    finish()
                    callbacks.onError("Voice typing error")
                }

                override fun onTimeout() {
                    finish()
                    callbacks.onError("Didn't catch that")
                }
            }

    private fun download() {
        val partial = File(context.cacheDir, "$MODEL_NAME.zip")
        main.post { callbacks.onStatus("Downloading voice model (40 MB)…") }
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("download failed (HTTP ${connection.responseCode})")
            }
            val total = connection.contentLengthLong
            var lastShown = -1
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent / 10 != lastShown) {
                                lastShown = percent / 10
                                main.post { callbacks.onStatus("Downloading voice model $percent%") }
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        main.post { callbacks.onStatus("Unpacking voice model…") }
        modelDir.deleteRecursively() // a partial earlier attempt
        unzip(partial, context.filesDir)
        partial.delete()
        if (!File(modelDir, "conf").exists()) throw IOException("model files missing after unpacking")
        File(modelDir, READY_MARKER).writeText("ok")
    }

    // The archive holds one top-level folder, MODEL_NAME
    private fun unzip(zip: File, target: File) {
        val root = target.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                val out = File(target, entry.name)
                if (!out.canonicalPath.startsWith(root)) throw IOException("bad entry ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { stream.copyTo(it) }
                }
            }
        }
    }

    fun release() {
        service?.cancel()
        service?.shutdown()
        service = null
        model?.close()
        model = null
        worker.shutdown()
    }

    companion object {
        private const val TAG = "OfflineSpeech"
        private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
        private const val SAMPLE_RATE = 16000f
        private const val READY_MARKER = ".taplink-ready"

        // Overall cap per dictation
        private const val LISTEN_TIMEOUT_MS = 15000
    }
}
