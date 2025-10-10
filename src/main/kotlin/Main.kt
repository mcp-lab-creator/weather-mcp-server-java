package org.example

 fun main() {
    System.err.println("[weather] starting MCP server...")
    try {
        `run mcp server`()
    } catch (e: Throwable) {
        e.printStackTrace(System.err)
        throw e
    }
}