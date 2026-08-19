package pro.nspanel.ha2.diag

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.net.ServerSocket
import java.net.Socket

/**
 * Minimal HTTP responder for read-only diagnostics polling on the local network.
 *
 *   GET /diag    → JSON snapshot (see [DiagSnapshot])
 *   GET /healthz → "ok"
 *
 * Deliberately hand-rolled (two routes, GET-only, connection-close) to avoid an
 * embedded-server dependency. Unauthenticated by design: the panels sit on a
 * trusted LAN and nothing sensitive is exposed.
 */
class DiagServer(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var currentPort = -1

    /** Start, restart on a new port, or stop (port <= 0). Safe to call repeatedly. */
    @Synchronized
    fun ensureRunning(port: Int) {
        if (port == currentPort) return
        stopLocked()
        currentPort = port
        if (port <= 0) {
            Log.i(TAG, "Diagnostics server disabled")
            return
        }
        val socket = try {
            ServerSocket(port)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot bind diagnostics server to port $port", e)
            return
        }
        serverSocket = socket
        acceptJob = scope.launch {
            Log.i(TAG, "Diagnostics server listening on :$port")
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (!socket.isClosed) Log.w(TAG, "accept failed", e)
                    break
                }
                launch { client.use { handle(it) } }
            }
        }
    }

    @Synchronized
    fun stop() {
        stopLocked()
        currentPort = -1
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    private fun stopLocked() {
        acceptJob?.cancel(); acceptJob = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 5_000
            val reader = client.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            drainHeaders(reader)

            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val path = parts.getOrNull(1)?.substringBefore('?') ?: ""

            val (status, contentType, body) = when {
                method != "GET" -> Triple("405 Method Not Allowed", "text/plain", "method not allowed\n")
                path == "/diag" -> Triple("200 OK", "application/json", DiagSnapshot.build(context).toString(2) + "\n")
                path == "/healthz" -> Triple("200 OK", "text/plain", "ok\n")
                else -> Triple("404 Not Found", "text/plain", "not found\n")
            }

            val bytes = body.toByteArray(Charsets.UTF_8)
            val out = client.getOutputStream()
            out.write(
                ("HTTP/1.1 $status\r\n" +
                    "Content-Type: $contentType; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n").toByteArray(Charsets.UTF_8),
            )
            out.write(bytes)
            out.flush()
        } catch (e: Exception) {
            Log.d(TAG, "request handling failed", e)
        }
    }

    private fun drainHeaders(reader: BufferedReader) {
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) return
        }
    }

    private companion object {
        const val TAG = "DiagServer"
    }
}
