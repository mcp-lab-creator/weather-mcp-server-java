package org.example

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.http.ContentType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.InputStream
import java.io.EOFException
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

fun main() {
    System.err.println("[bridge] starting MCP->HTTP bridge...")

    // Create piped streams:
    val adapterToServer = PipedOutputStream()
    val serverInput = PipedInputStream(adapterToServer)
    val serverOutput = PipedOutputStream()
    val adapterFromServer = PipedInputStream(serverOutput)

    // Start the MCP server in IO dispatcher
    val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    serverScope.launch {
        try {
            System.err.println("[bridge] launching MCP server (connected to piped streams)...")
            `run mcp server`(serverInput, serverOutput)
            System.err.println("[bridge] MCP server finished.")
        } catch (t: Throwable) {
            System.err.println("[bridge] MCP server crashed:")
            t.printStackTrace(System.err)
        }
    }

    // Map to track pending requests: requestId -> response channel
    val pendingRequests = ConcurrentHashMap<String, Channel<String>>()

    // Launch a coroutine that reads JSON responses and routes them to pending requests
    val readerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    readerScope.launch {
        try {
            while (isActive) {
                val json = try {
                    readNextJsonObject(adapterFromServer)
                } catch (e: EOFException) {
                    System.err.println("[bridge] adapterFromServer closed (EOF)")
                    break
                } catch (t: Throwable) {
                    System.err.println("[bridge] error reading from adapterFromServer:")
                    t.printStackTrace(System.err)
                    break
                }

                System.err.println("[bridge] received response: ${json.take(100)}...")

                // Extract the request ID from the response
                val requestId = extractRequestId(json)
                if (requestId != null) {
                    val channel = pendingRequests[requestId]
                    if (channel != null) {
                        channel.trySend(json)
                        System.err.println("[bridge] routed response to request $requestId")
                    } else {
                        System.err.println("[bridge] no pending request for id $requestId")
                    }
                } else {
                    System.err.println("[bridge] could not extract request ID from response")
                }
            }
        } finally {
            // Close all pending request channels
            pendingRequests.values.forEach { it.close() }
            pendingRequests.clear()
        }
    }

    // Start Ktor HTTP server
    val httpPort = System.getenv("PORT")?.toIntOrNull() ?: 8000
    embeddedServer(Netty, port = httpPort) {
        install(ContentNegotiation) { json() }

        routing {
            get("/") {
                call.respondText("ok", ContentType.Text.Plain)
            }

            get("/health") {
                call.respond(mapOf("status" to "ok"))
            }

            // GET /mcp - for SSE (backward compatibility)
            get("/mcp") {
                val accept = call.request.headers["Accept"] ?: ""
                System.err.println("[bridge] GET /mcp Accept: $accept")

                if (accept.contains("text/event-stream")) {
                    call.response.headers.append("Cache-Control", "no-cache")
                    call.response.headers.append("Connection", "keep-alive")
                    call.response.headers.append("X-Accel-Buffering", "no")

                    call.respondTextWriter(contentType = ContentType.parse("text/event-stream; charset=UTF-8")) {
                        write(": connected\n\n")
                        flush()

                        // Keep connection alive
                        while (true) {
                            delay(30_000)
                            write(": keep-alive\n\n")
                            flush()
                        }
                    }
                } else {
                    call.respond(mapOf("status" to "ready"))
                }
            }

            // POST /mcp - request-response pattern
            post("/mcp") {
                val payload = call.receiveText()
                System.err.println("[bridge] POST /mcp payload: $payload")

                // Extract request ID to match response
                val requestId = extractRequestId(payload)
                if (requestId == null) {
                    System.err.println("[bridge] could not extract request ID from payload")
                    call.respondText(
                        """{"error": "invalid request format"}""",
                        ContentType.Application.Json
                    )
                    return@post
                }

                // Create a channel to receive the response
                val responseChannel = Channel<String>(1)
                pendingRequests[requestId] = responseChannel

                try {
                    // Send request to MCP server
                    synchronized(adapterToServer) {
                        val bytes = payload.toByteArray(Charset.forName("UTF-8"))
                        adapterToServer.write(bytes)
                        adapterToServer.write('\n'.code)
                        adapterToServer.flush()
                    }

                    // Wait for response (with timeout)
                    val response = withTimeoutOrNull(30_000) {
                        responseChannel.receive()
                    }

                    if (response != null) {
                        System.err.println("[bridge] sending response for request $requestId")
                        call.respondText(response, ContentType.Application.Json)
                    } else {
                        System.err.println("[bridge] timeout waiting for response to request $requestId")
                        call.respondText(
                            """{"error": "timeout waiting for response"}""",
                            ContentType.Application.Json,
                            io.ktor.http.HttpStatusCode.GatewayTimeout
                        )
                    }
                } finally {
                    pendingRequests.remove(requestId)
                    responseChannel.close()
                }
            }
        }
    }.start(wait = true)
}

/**
 * Extract the request/response ID from a JSON-RPC message.
 * Looks for "id" field in the JSON.
 */
fun extractRequestId(json: String): String? {
    return try {
        // Simple regex to extract "id" field value
        val idPattern = """"id"\s*:\s*(?:(\d+)|"([^"]+)")""".toRegex()
        val match = idPattern.find(json)
        match?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            ?: match?.groupValues?.get(2)
    } catch (e: Exception) {
        System.err.println("[bridge] error extracting request ID: ${e.message}")
        null
    }
}

/**
 * Read the next top-level JSON object from an InputStream.
 * Brace-counting parser that ignores braces inside strings and honors backslash escapes.
 */
fun readNextJsonObject(stream: InputStream): String {
    val sb = StringBuilder()
    var foundStart = false
    var inString = false
    var escape = false
    var depth = 0

    while (true) {
        val b = stream.read()
        if (b == -1) throw EOFException("Stream closed while waiting for response")
        val ch = b.toChar()
        if (!foundStart) {
            if (ch.isWhitespace()) continue
            if (ch == '{') {
                foundStart = true
                depth = 1
                sb.append(ch)
                continue
            } else {
                // ignore any prefix (logs, etc.)
                continue
            }
        } else {
            sb.append(ch)
            if (escape) { escape = false; continue }
            if (ch == '\\') { escape = true; continue }
            if (ch == '"') { inString = !inString; continue }
            if (inString) continue
            if (ch == '{') depth++
            else if (ch == '}') {
                depth--
                if (depth == 0) return sb.toString()
            }
        }
    }
}