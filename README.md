# weather-mcp-server-java

An MCP (Model Context Protocol) server that exposes US weather data from the [National Weather Service API](https://www.weather.gov/documentation/services-web-api) as tools, built with Kotlin and Ktor. It also includes a bridge that exposes the same MCP server over plain HTTP, so it can run as a standard web service.

## Tools

- **`get_alerts`** — Get active weather alerts for a US state.
  - `state` (string, required): Two-letter US state code (e.g. `CA`, `NY`)
- **`get_forecast`** — Get the weather forecast for a location.
  - `latitude` (number, required)
  - `longitude` (number, required)

Both tools call the NWS API (`api.weather.gov`), which requires no API key but only covers US locations.

## Project structure

```
src/main/kotlin/
├── Main.kt              # HTTP bridge (Ktor server) that exposes the MCP server over HTTP
├── McpWeatherServer.kt  # MCP server definition and tool registration
└── WeatherApi.kt        # NWS API client and response models
```

`McpWeatherServer.kt` implements the MCP server using stdio transport, as normal MCP servers do. `Main.kt` wraps it in a Ktor HTTP server so it can be run over HTTP instead of stdio, translating incoming HTTP requests into JSON-RPC messages piped to the MCP server and streaming responses back.

## Requirements

- JDK 21+ (the project targets JVM toolchain 21)
- No local Gradle install needed — the Gradle wrapper (`gradlew`) is included

## Running the server

### Build a fat JAR

```bash
./gradlew shadowJar
```

This produces a runnable JAR at `build/libs/weather-mcp-server-java.jar`.

### Run over stdio (standard MCP client integration)

Point your MCP client (e.g. Claude Desktop, Claude Code) at the built JAR. Example `mcp.json` / client config:

```json
{
  "mcpServers": {
    "weather": {
      "command": "java",
      "args": ["-jar", "/path/to/weather-mcp-server-java.jar"]
    }
  }
}
```

### Run over HTTP

```bash
./gradlew run
```

By default the server listens on port `8000` (override with the `PORT` environment variable).

Endpoints:

| Method | Path      | Description                                  |
|--------|-----------|-----------------------------------------------|
| GET    | `/`       | Health check, returns `ok`                    |
| GET    | `/health` | Health check, returns `{"status": "ok"}`      |
| GET    | `/mcp`    | SSE endpoint (`Accept: text/event-stream`) for keep-alive streaming |
| POST   | `/mcp`    | Send a JSON-RPC request, receive the MCP response |

Example request:

```bash
curl -X POST http://localhost:8000/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## Development

Run tests:

```bash
./gradlew test
```

## Tech stack

- [Kotlin](https://kotlinlang.org/) / JVM 21
- [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk)
- [Ktor](https://ktor.io/) (HTTP client and server)
- [Gradle Shadow plugin](https://github.com/johnrengelman/shadow) for fat-JAR packaging
