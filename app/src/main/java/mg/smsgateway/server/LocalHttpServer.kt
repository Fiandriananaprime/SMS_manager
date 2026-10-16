package mg.smsgateway.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mg.smsgateway.Config
import mg.smsgateway.util.AppLogger
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: String,
)

class HttpResponse(val status: Int, val body: String)

private class BadRequest(message: String) : Exception(message)

/**
 * Mini serveur HTTP/1.1 sans dépendance, lié UNIQUEMENT à 127.0.0.1 (jamais 0.0.0.0) :
 * aucune machine du réseau Wi-Fi ne peut l'atteindre. Le PC y accède via `adb forward`.
 */
class LocalHttpServer(
    private val port: Int,
    private val scope: CoroutineScope,
    private val logger: AppLogger,
    private val handler: suspend (HttpRequest) -> HttpResponse,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val permits = Semaphore(8)

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
        serverSocket = ss
        logger.i("HttpServer", "Écoute sur 127.0.0.1:$port")
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val socket = try {
                    ss.accept()
                } catch (e: SocketException) {
                    break // socket fermée par stop()
                }
                launch { permits.withPermit { handle(socket) } }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        acceptJob?.cancel()
        serverSocket = null
    }

    private suspend fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 15_000
                val input = s.getInputStream().buffered()
                val response = try {
                    handler(parse(input))
                } catch (e: BadRequest) {
                    HttpResponse(400, """{"error":{"code":"BAD_REQUEST","message":"${e.message}"}}""")
                } catch (e: Exception) {
                    logger.e("HttpServer", "Erreur de traitement", e)
                    HttpResponse(500, """{"error":{"code":"INTERNAL_ERROR","message":"Erreur interne"}}""")
                }
                write(s, response)
            } catch (e: Exception) {
                logger.w("HttpServer", "Connexion interrompue : ${e.javaClass.simpleName}")
            }
        }
    }

    private fun parse(input: InputStream): HttpRequest {
        val requestLine = readLine(input) ?: throw BadRequest("empty request")
        val parts = requestLine.split(" ")
        if (parts.size < 3) throw BadRequest("bad request line")
        val method = parts[0].uppercase()
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = HashMap<String, String>()
        if (target.contains('?')) {
            target.substringAfter('?').split('&').filter { it.isNotEmpty() }.forEach {
                val k = URLDecoder.decode(it.substringBefore('='), "UTF-8")
                val v = URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                query[k] = v
            }
        }
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: throw BadRequest("unexpected eof")
            if (line.isEmpty()) break
            if (headers.size >= 64) throw BadRequest("too many headers")
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            throw BadRequest("chunked encoding not supported, send Content-Length")
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > Config.MAX_BODY_BYTES) throw BadRequest("body too large")
        val body = if (length > 0) {
            val buf = ByteArray(length)
            var off = 0
            while (off < length) {
                val n = input.read(buf, off, length - off)
                if (n < 0) throw BadRequest("truncated body")
                off += n
            }
            String(buf, Charsets.UTF_8)
        } else ""
        return HttpRequest(method, path, query, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val sb = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.size() == 0) null else throw BadRequest("unexpected eof")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.write(b)
            if (sb.size() > 8192) throw BadRequest("line too long")
        }
        return sb.toString("UTF-8")
    }

    private fun write(socket: Socket, r: HttpResponse) {
        val body = r.body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 ${r.status} ${statusText(r.status)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"; else -> "Error"
    }
}
