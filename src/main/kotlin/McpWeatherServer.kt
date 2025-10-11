package org.example

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.streams.*
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.OutputStream

// Run the MCP server using arbitrary InputStream / OutputStream (so we can bridge to HTTP)
fun `run mcp server`(stdin: InputStream, stdout: OutputStream) {
    // Create an HTTP client with default config and JSON serialization
    val httpClient = HttpClient {
        defaultRequest {
            url("https://api.weather.gov")
            headers {
                append("Accept", "application/geo+json")
                append("User-Agent", "WeatherApiClient/1.0")
            }
            contentType(ContentType.Application.Json)
        }
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    // Create the MCP Server instance with a basic implementation
    val server = Server(
        Implementation(
            name = "weather",
            version = "1.0.0"
        ),
        ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))
        )
    )

    // Register tools
    server.addTool(
        name = "get_alerts",
        description = """
            Get weather alerts for a US state. Input is Two-letter US state code (e.g. CA, NY)
        """.trimIndent(),
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("state") {
                    put("type", "string")
                    put("description", "Two-letter US state code (e.g. CA, NY)")
                }
            },
            required = listOf("state")
        )
    ) { request ->
        val state = request.arguments["state"]?.jsonPrimitive?.content
        if (state == null) {
            return@addTool CallToolResult(
                content = listOf(TextContent("The 'state' parameter is required."))
            )
        }

        val alerts = httpClient.getAlerts(state)
        CallToolResult(content = alerts.map { TextContent(it) })
    }

    server.addTool(
        name = "get_forecast",
        description = "Get weather forecast for a specific latitude/longitude",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("latitude") { put("type", "number") }
                putJsonObject("longitude") { put("type", "number") }
            },
            required = listOf("latitude", "longitude")
        )
    ) { request ->
        val latitude = request.arguments["latitude"]?.jsonPrimitive?.doubleOrNull
        val longitude = request.arguments["longitude"]?.jsonPrimitive?.doubleOrNull
        if (latitude == null || longitude == null) {
            return@addTool CallToolResult(
                content = listOf(TextContent("The 'latitude' and 'longitude' parameters are required."))
            )
        }

        val forecast = httpClient.getForecast(latitude, longitude)
        CallToolResult(content = forecast.map { TextContent(it) })
    }

    // Use provided streams (convert to kotlinx.io sinks/inputs like your original code)
    val transport = StdioServerTransport(
        stdin.asInput(),
        stdout.asSink().buffered()
    )

    runBlocking {
        server.connect(transport)
        // ensure anything written by server is flushed to stdout stream so bridge reader sees it
        try {
            stdout.flush()
        } catch (e: Throwable) {
            // ignore flush errors
        }

        val done = Job()
        server.onClose {
            done.complete()
        }
        done.join()
    }
}

// Backwards-compatible convenience: original behavior using System.in/out
fun `run mcp server`() {
    `run mcp server`(System.`in`, System.out)
}
