package com.projectorreceiver

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.UUID

class ControlServer(
    private val context: Context,
    private val playbackController: PlaybackController
) {
    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val controlToken: String by lazy { loadOrCreateToken() }

    fun start() {
        synchronized(this) {
            if (running || serverThread?.isAlive == true) return
            serverThread = Thread(::runServer, "ProjectorReceiver-ControlServer").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        synchronized(this) {
            running = false
            try {
                serverSocket?.close()
            } catch (_: Exception) {
                // The accept loop is already being stopped.
            }
            serverSocket = null
            serverThread = null
        }
    }

    fun isRunning(): Boolean = running

    fun controlUrl(): String? {
        val ip = NetworkUtils.localIpv4Address() ?: return null
        return "http://$ip:$PORT"
    }

    private fun runServer() {
        try {
            ServerSocket(PORT, 16, InetAddress.getByName("0.0.0.0")).use { socket ->
                serverSocket = socket
                running = true
                Log.i(TAG, "Control server listening on 0.0.0.0:$PORT")
                while (running) {
                    try {
                        val client = socket.accept()
                        Thread({ handleClient(client) }, "ProjectorReceiver-HttpRequest").apply {
                            isDaemon = true
                            start()
                        }
                    } catch (_: SocketException) {
                        if (running) Log.e(TAG, "Control server accept failed")
                    }
                }
            }
        } catch (error: Exception) {
            running = false
            Log.e(TAG, "Control server could not start on port $PORT", error)
        } finally {
            serverSocket = null
            running = false
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 10_000
                val request = readRequest(client) ?: return
                if (request.method == "OPTIONS") {
                    respond(client, 204, "text/plain; charset=utf-8", "")
                    return
                }
                if (CONTROL_TOKEN_ENABLED && request.path != "/" && !isAuthorized(request)) {
                    respondJson(client, 401, JSONObject().put("ok", false).put("error", "Unauthorized"))
                    return
                }

                when {
                    request.method == "GET" && request.path == "/" -> respondHtml(client)
                    request.method == "GET" && request.path == "/status" -> respondJson(client, 200, statusJson())
                    request.method == "POST" && request.path == "/play" -> handlePlay(client, request)
                    request.method == "POST" && request.path == "/pause" -> {
                        playbackController.pause()
                        respondOk(client, "pause")
                    }
                    request.method == "POST" && request.path == "/resume" -> {
                        playbackController.resume()
                        respondOk(client, "resume")
                    }
                    request.method == "POST" && request.path == "/stop" -> {
                        playbackController.stopAndReturnToConnection(context)
                        respondOk(client, "stop")
                    }
                    request.method == "POST" && request.path == "/seek" -> handleSeek(client, request)
                    request.method == "POST" && request.path == "/volume" -> handleVolume(client, request)
                    request.method == "POST" -> respondJson(client, 404, errorJson("Unknown endpoint"))
                    else -> respondJson(client, 405, errorJson("Method not allowed"))
                }
            } catch (error: Exception) {
                Log.w(TAG, "HTTP request failed", error)
                runCatching { respondJson(client, 400, errorJson("Invalid request")) }
            }
        }
    }

    private fun handlePlay(socket: Socket, request: HttpRequest) {
        val json = runCatching { JSONObject(request.body.toString(StandardCharsets.UTF_8)) }.getOrNull()
        if (json == null || !json.has("url")) {
            respondJson(socket, 400, errorJson("JSON body must include url"))
            return
        }

        val url = json.optString("url", "")
        if (url.isBlank()) {
            respondJson(socket, 400, errorJson("url must not be empty"))
            return
        }
        val type = PlaybackController.normalizeType(json.optString("type", "auto"))
        val headers = parseHeaders(json.optJSONObject("headers"))
        if (headers == null) {
            respondJson(socket, 400, errorJson("headers must be an object of string values"))
            return
        }

        playbackController.play(context, PlayRequest(url = url, headers = headers, type = type))
        respondJson(
            socket,
            202,
            JSONObject().put("ok", true).put("state", "connecting").put("url", url)
        )
    }

    private fun handleSeek(socket: Socket, request: HttpRequest) {
        val json = parseJson(request) ?: run {
            respondJson(socket, 400, errorJson("JSON body must include positionMs"))
            return
        }
        if (!json.has("positionMs")) {
            respondJson(socket, 400, errorJson("JSON body must include positionMs"))
            return
        }
        val position = json.optLong("positionMs", Long.MIN_VALUE)
        if (position < 0) {
            respondJson(socket, 400, errorJson("positionMs must be zero or greater"))
            return
        }
        playbackController.seek(position)
        respondOk(socket, "seek")
    }

    private fun handleVolume(socket: Socket, request: HttpRequest) {
        val json = parseJson(request) ?: run {
            respondJson(socket, 400, errorJson("JSON body must include volume"))
            return
        }
        if (!json.has("volume")) {
            respondJson(socket, 400, errorJson("JSON body must include volume"))
            return
        }
        val volume = json.optDouble("volume", Double.NaN)
        if (volume.isNaN() || volume < 0.0 || volume > 1.0) {
            respondJson(socket, 400, errorJson("volume must be between 0.0 and 1.0"))
            return
        }
        playbackController.setVolume(volume.toFloat())
        respondOk(socket, "volume")
    }

    private fun parseJson(request: HttpRequest): JSONObject? =
        runCatching { JSONObject(request.body.toString(StandardCharsets.UTF_8)) }.getOrNull()

    private fun parseHeaders(json: JSONObject?): Map<String, String>? {
        if (json == null) return emptyMap()
        val headers = linkedMapOf<String, String>()
        val names = json.keys()
        while (names.hasNext()) {
            val name = names.next()
            val value = json.opt(name)
            if (value !is String || name.isBlank() || name.any { it <= ' ' || it == ':' }) return null
            if (name.equals("Host", true) || name.equals("Content-Length", true) ||
                name.equals("Connection", true) || name.equals("Transfer-Encoding", true)
            ) continue
            headers[name] = value
        }
        return headers
    }

    private fun statusJson(): JSONObject {
        val status = playbackController.status()
        return JSONObject()
            .put("state", status.state.name.lowercase(java.util.Locale.US))
            .put("url", status.url ?: JSONObject.NULL)
            .put("positionMs", status.positionMs)
            .put("durationMs", status.durationMs)
            .put("bufferedMs", status.bufferedMs)
            .put("decoder", status.decoder ?: JSONObject.NULL)
            .put("error", status.error ?: JSONObject.NULL)
            .put("videoWidth", status.videoWidth)
            .put("videoHeight", status.videoHeight)
    }

    private fun respondHtml(socket: Socket) {
        val page = runCatching {
            context.assets.open("index.html").bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }.getOrElse {
            respondJson(socket, 500, errorJson("Control page unavailable"))
            return
        }
        respond(socket, 200, "text/html; charset=utf-8", page)
    }

    private fun respondOk(socket: Socket, command: String) {
        respondJson(socket, 200, JSONObject().put("ok", true).put("command", command))
    }

    private fun errorJson(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    private fun respondJson(socket: Socket, code: Int, json: JSONObject) {
        respond(socket, code, "application/json; charset=utf-8", json.toString())
    }

    private fun respond(socket: Socket, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val output: OutputStream = socket.getOutputStream()
        output.write(
            ("HTTP/1.1 $code $reason\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Headers: Content-Type, X-Projector-Token\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1)
        )
        output.write(bytes)
        output.flush()
    }

    private fun isAuthorized(request: HttpRequest): Boolean =
        request.headers["x-projector-token"].orEmpty() == controlToken

    private fun loadOrCreateToken(): String {
        val preferences = context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
        val existing = preferences.getString(TOKEN_KEY, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString().replace("-", "")
        preferences.edit { putString(TOKEN_KEY, generated) }
        Log.i(TAG, "Control token generated: $generated")
        return generated
    }

    private fun readRequest(socket: Socket): HttpRequest? {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 2) return null
        val headers = linkedMapOf<String, String>()
        for (index in 0 until MAX_HEADERS) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY_BYTES) {
            respond(socket, 413, "text/plain; charset=utf-8", "Payload too large")
            return null
        }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) return null
            offset += count
        }
        val target = parts[1]
        val path = if (target.startsWith("http://") || target.startsWith("https://")) {
            target.substringAfter("://").substringAfter('/', "/")
        } else {
            target
        }.substringBefore('?').ifEmpty { "/" }.let { if (it.startsWith('/')) it else "/$it" }
        return HttpRequest(parts[0].uppercase(), path, headers, body)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < MAX_LINE_BYTES) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString(StandardCharsets.ISO_8859_1.name())
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.write(value)
        }
        return bytes.toString(StandardCharsets.ISO_8859_1.name())
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    companion object {
        const val PORT = 8080
        private const val TAG = "ProjectorReceiver"
        private const val CONTROL_TOKEN_ENABLED = false
        private const val MAX_HEADERS = 64
        private const val MAX_LINE_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 1024 * 1024
        private const val TOKEN_PREFS = "projector_receiver_control"
        private const val TOKEN_KEY = "control_token"
    }
}
