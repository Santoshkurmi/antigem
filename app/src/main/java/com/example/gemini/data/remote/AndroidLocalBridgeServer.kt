package com.example.gemini.data.remote

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import com.example.gemini.data.local.LocalTerminalBridge
import com.example.gemini.ui.browser.BrowserSessionManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Embedded Local Bridge Server providing JSON-RPC 2.0 MCP and REST APIs
 * for Browser Automation (Screenshots, DOM Inspection, Interactions, Logs)
 * and Live Terminal Control (Commands, Keystrokes, Output inspection).
 */
class AndroidLocalBridgeServer private constructor() {

    enum class McpEndpoint(val serverName: String) {
        BROWSER("browser_mcp"),
        TERMINAL("terminal_mcp"),
        GOOGLE_FLOW("google_flow_mcp"),
        EXTENSION_DEVELOPER("custom_plugin_developer_mcp")
    }

    companion object {
        /** The plugin developer MCP's preset name; older installs added it as [LEGACY_DEVELOPER_MCP_NAME] (same URL, still works). */
        const val DEVELOPER_MCP_NAME = "custom_plugin_developer_mcp"
        const val LEGACY_DEVELOPER_MCP_NAME = "webview_extension_developer_mcp"
        fun isDeveloperMcpName(name: String) = name == DEVELOPER_MCP_NAME || name == LEGACY_DEVELOPER_MCP_NAME
        private const val TAG = "AndroidBridgeServer"
        private const val MAX_HEADER_BYTES = 64 * 1024
        /** Uploads arrive as base64 in JSON: allow large bodies, but not unbounded ones. */
        private const val MAX_BODY_BYTES = 512L * 1024 * 1024
        /** Per app (build flavor) so com.antigem and com.termux do not collide. */
        val DEFAULT_PORT = com.example.gemini.BuildConfig.BROWSER_MCP_PORT
        val instance: AndroidLocalBridgeServer by lazy { AndroidLocalBridgeServer() }
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // one thread per open request: Flow calls wait up to ~50 s each, so a fixed pool would make parallel calls queue
    private val clientThreadPool = Executors.newCachedThreadPool()
    private var appContext: Context? = null
    @Volatile
    private var cachedDeviceId: String = ""
    var boundPort: Int = DEFAULT_PORT
        private set

    fun start(context: Context, port: Int = DEFAULT_PORT) {
        if (isRunning) return
        appContext = context.applicationContext
        cachedDeviceId = try {
            android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
        } catch (_: Exception) { "" }
        BrowserSessionManager.instance.init(context)
        LocalTerminalBridge.instance.init(context)
        com.example.gemini.ui.browser.BrowserExtensionManager.instance.init(context)
        com.example.gemini.data.remote.CustomSlashCommands.init(context)
        com.example.gemini.ui.browser.PluginRepositories.init(context)

        serverScope.launch {
            try {
                // Bind to localhost only for security
                val bindAddr = InetAddress.getByName("127.0.0.1")
                var currentPort = port
                var socket: ServerSocket? = null
                for (attempt in 0..5) {
                    try {
                        socket = ServerSocket(currentPort, 50, bindAddr)
                        boundPort = currentPort
                        break
                    } catch (e: Exception) {
                        currentPort++
                    }
                }
                serverSocket = socket ?: ServerSocket(0, 50, bindAddr).also { boundPort = it.localPort }
                isRunning = true
                Log.i(TAG, "Bridge MCP / REST Server running on http://127.0.0.1:$boundPort")

                while (isRunning && !serverSocket!!.isClosed) {
                    val client = serverSocket!!.accept()
                    clientThreadPool.execute {
                        handleClient(client)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Server accept error", e)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        Log.i(TAG, "Bridge Server stopped")
    }

    /** One HTTP/1.1 request; [body] is the raw body bytes decoded as UTF-8. */
    private class HttpRequest(val method: String, val rawPath: String, val headers: Map<String, String>, val body: String)

    /** Remembers whether anything was written, so a failure after a response never writes a second one. */
    private class TrackingOutputStream(private val out: OutputStream) : OutputStream() {
        @Volatile var used = false
            private set
        override fun write(b: Int) { used = true; out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { used = true; out.write(b, off, len) }
        override fun flush() = out.flush()
    }

    /**
     * Reads one request from the raw byte stream: the header block up to the blank line, then exactly
     * Content-Length bytes (or a chunked body). Content-Length counts bytes, so the body must never be read as
     * characters: a prompt with any non-ASCII character would otherwise wait for bytes that never come.
     */
    private fun readRequest(input: InputStream): HttpRequest? {
        val head = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return null
            head.write(b)
            if (head.size() > MAX_HEADER_BYTES) throw java.io.IOException("request headers too large")
            val bytes = head.size()
            if (b == '\n'.code) {
                val arr = head.toByteArray()
                val endsCrlf = bytes >= 4 && arr[bytes - 2] == '\r'.code.toByte() && arr[bytes - 3] == '\n'.code.toByte() && arr[bytes - 4] == '\r'.code.toByte()
                val endsLf = bytes >= 2 && arr[bytes - 2] == '\n'.code.toByte()
                if (endsCrlf || endsLf) break
                // a request may start with stray blank lines
                if (bytes <= 2 && arr.all { it == '\r'.code.toByte() || it == '\n'.code.toByte() }) head.reset()
            }
        }
        val lines = String(head.toByteArray(), Charsets.UTF_8).split('\n').map { it.trimEnd('\r') }
        val requestLine = lines.firstOrNull()?.split(" ")?.takeIf { it.size >= 2 } ?: return null
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val bodyBytes = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
            else -> {
                val length = headers["content-length"]?.toLongOrNull() ?: 0L
                if (length > MAX_BODY_BYTES) throw java.io.IOException("request body too large ($length bytes)")
                readExactly(input, length.toInt())
            }
        }
        return HttpRequest(requestLine[0].uppercase(), requestLine[1], headers, String(bodyBytes, Charsets.UTF_8))
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val r = input.read(buf, read, length - read)
            if (r == -1) throw java.io.EOFException("request body ended after $read of $length bytes")
            read += r
        }
        return buf
    }

    private fun readAsciiLine(input: InputStream): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) line.write(b)
            if (line.size() > MAX_HEADER_BYTES) throw java.io.IOException("chunk header too large")
        }
        return line.toString(Charsets.US_ASCII.name())
    }

    private fun readChunked(input: InputStream): ByteArray {
        val body = ByteArrayOutputStream()
        while (true) {
            val size = readAsciiLine(input).substringBefore(';').trim().toLongOrNull(16)
                ?: throw java.io.IOException("invalid chunk size")
            if (size == 0L) {
                // optional trailer headers up to the blank line
                while (readAsciiLine(input).isNotEmpty()) Unit
                break
            }
            if (body.size() + size > MAX_BODY_BYTES) throw java.io.IOException("request body too large")
            body.write(readExactly(input, size.toInt()))
            readAsciiLine(input) // CRLF after the chunk
        }
        return body.toByteArray()
    }

    private fun handleClient(socket: Socket) {
        var request: HttpRequest? = null
        var output: TrackingOutputStream? = null
        try {
            socket.soTimeout = 30000
            val input = java.io.BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val out = TrackingOutputStream(java.io.BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
            output = out
            val req = readRequest(input) ?: return
            request = req

            val method = req.method
            val rawPath = req.rawPath
            val path = rawPath.substringBefore("?")
            val queryString = if (rawPath.contains("?")) rawPath.substringAfter("?") else ""
            val headers = req.headers
            val body = req.body

            // Handle CORS preflight
            if (method == "OPTIONS") {
                sendResponse(out, 200, "application/json", "{}")
                return
            }

            // Verify device ID security header for all non-OPTIONS requests
            if (cachedDeviceId.length <= 5) {
                Log.e(TAG, "Bridge server security error: cachedDeviceId is not initialized or invalid")
                sendResponse(out, 500, "application/json", "{\"error\": \"Server security error: Device ID not initialized or invalid\"}")
                return
            }

            val clientDeviceId = headers["x-device-id"]
            if (clientDeviceId.isNullOrBlank() || clientDeviceId != cachedDeviceId) {
                Log.w(TAG, "Blocked unauthorized request to $path (missing or invalid X-Device-Id header)")
                sendResponse(out, 401, "application/json", "{\"error\": \"Unauthorized: Invalid or missing X-Device-Id header\"}")
                return
            }

            // Route request
            runBlocking {
                when {
                    path == "/browser" -> handleMcpHttp(McpEndpoint.BROWSER, method, body, out)
                    path == "/terminal" -> handleMcpHttp(McpEndpoint.TERMINAL, method, body, out)
                    path == "/extension_developer" -> handleMcpHttp(McpEndpoint.EXTENSION_DEVELOPER, method, body, out)
                    path == "/google_flow" -> handleMcpHttp(McpEndpoint.GOOGLE_FLOW, method, body, out)
                    path == "/api/health" -> {
                        val res = JSONObject().apply {
                            put("status", "ok")
                            put("port", boundPort)
                            put("tabsCount", BrowserSessionManager.instance.tabs.size)
                            put("terminalsCount", LocalTerminalBridge.instance.listSessions().size)
                        }
                        sendResponse(out, 200, "application/json", res.toString())
                    }
                    path.startsWith("/api/browser/") -> {
                        handleBrowserRest(path.removePrefix("/api/browser/"), method, body, queryString, out)
                    }
                    path.startsWith("/api/terminal/") -> {
                        handleTerminalRest(path.removePrefix("/api/terminal/"), method, body, queryString, out)
                    }
                    else -> {
                        sendResponse(out, 404, "application/json", "{\"error\": \"Not Found\"}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error handling client request ${request?.method} ${request?.rawPath}", t)
            // never close a request without an answer: the client would only see "EOF"
            val out = output
            val req = request
            if (out != null && req != null && !out.used) {
                runCatching {
                    val path = req.rawPath.substringBefore("?")
                    if (path == "/browser" || path == "/terminal" || path == "/google_flow" || path == "/extension_developer") {
                        val id = runCatching { JSONObject(req.body).opt("id") }.getOrNull()
                        sendResponse(out, 200, "application/json", jsonRpcError(id, -32603, "Internal error: ${t.message ?: t.javaClass.simpleName}").toString())
                    } else {
                        sendResponse(out, 500, "application/json", JSONObject().put("error", t.message ?: t.javaClass.simpleName).toString())
                    }
                }
            }
        } finally {
            try {
                output?.flush()
            } catch (_: Exception) {}
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * MCP over HTTP: a POSTed JSON-RPC request gets its response, notifications (no id) get 202 with no body, a
     * batch gets an array, and GET (the optional server-sent event stream) is not offered (405).
     */
    private suspend fun handleMcpHttp(endpoint: McpEndpoint, method: String, body: String, out: OutputStream) {
        if (method != "POST") {
            sendResponse(out, 405, "application/json", jsonRpcError(null, -32600, "Use POST for MCP requests").toString(), allow = "POST, OPTIONS")
            return
        }
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) {
            val batch = runCatching { JSONArray(trimmed) }.getOrNull()
            if (batch == null) {
                sendResponse(out, 200, "application/json", jsonRpcError(null, -32700, "Parse error").toString())
                return
            }
            val responses = JSONArray()
            for (i in 0 until batch.length()) {
                val item = batch.optJSONObject(i)
                if (item == null) {
                    responses.put(jsonRpcError(null, -32600, "Invalid request"))
                    continue
                }
                if (!item.has("id")) continue
                responses.put(runMcpRequest(item, endpoint))
            }
            if (responses.length() == 0) sendResponse(out, 202, "application/json", "")
            else sendResponse(out, 200, "application/json", responses.toString())
            return
        }
        val req = runCatching { JSONObject(trimmed) }.getOrNull()
        when {
            req == null -> sendResponse(out, 200, "application/json", jsonRpcError(null, -32700, "Parse error").toString())
            // a notification (e.g. notifications/initialized) has no id and gets no response
            !req.has("id") -> sendResponse(out, 202, "application/json", "")
            else -> sendResponse(out, 200, "application/json", runMcpRequest(req, endpoint).toString())
        }
    }

    /** One JSON-RPC request; a failure becomes a JSON-RPC error instead of a dropped connection. */
    private suspend fun runMcpRequest(req: JSONObject, endpoint: McpEndpoint): JSONObject = try {
        handleMcpJsonRpc(req.toString(), endpoint)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.w(TAG, "MCP request ${req.optString("method")} failed", t)
        jsonRpcError(req.opt("id"), -32603, "Internal error: ${t.message ?: t.javaClass.simpleName}")
    }

    private fun jsonRpcError(id: Any?, code: Int, message: String): JSONObject = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", id ?: JSONObject.NULL)
        put("error", JSONObject().apply {
            put("code", code)
            put("message", message)
        })
    }

    private fun sendResponse(
        out: OutputStream,
        statusCode: Int,
        contentType: String,
        body: String,
        allow: String? = null
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val statusText = when (statusCode) {
            200 -> "OK"
            202 -> "Accepted"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                (allow?.let { "Allow: $it\r\n" } ?: "") +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type, X-Device-Id\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    // ==========================================
    // MCP JSON-RPC 2.0 PROTOCOL HANDLER
    // ==========================================

    private suspend fun handleMcpJsonRpc(body: String, endpoint: McpEndpoint): JSONObject {
        val req = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        val id = req.opt("id")
        val jsonrpc = req.optString("jsonrpc", "2.0")
        val method = req.optString("method", "")

        val res = JSONObject().apply {
            put("jsonrpc", jsonrpc)
            if (id != null) put("id", id)
        }

        when (method) {
            "initialize" -> {
                val result = JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    if (endpoint == McpEndpoint.EXTENSION_DEVELOPER) put("instructions", "Start with webview_extension_docs. Read developer-mcp.md and the required API references, create a draft, write files, install or update, discover tools with webview_extension_test, and inspect logs to fix failures. No app source or manual ZIP handling is needed.")
                    put("capabilities", JSONObject().apply {
                        put("tools", JSONObject())
                    })
                    put("serverInfo", JSONObject().apply {
                        put("name", endpoint.serverName)
                        put("version", "1.0.0")
                    })
                }
                res.put("result", result)
            }
            "ping" -> {
                res.put("result", JSONObject())
            }
            "tools/list" -> {
                res.put("result", JSONObject().apply {
                    put("tools", getMcpToolsList(endpoint))
                })
            }
            "tools/call" -> {
                val params = req.optJSONObject("params") ?: JSONObject()
                val toolName = params.optString("name", "")
                val availableTools = getMcpToolsList(endpoint)
                val toolExists = (0 until availableTools.length()).any {
                    availableTools.getJSONObject(it).getString("name") == toolName
                }
                if (!toolExists) {
                    res.put("error", JSONObject().apply {
                        put("code", -32601)
                        put("message", "Tool '$toolName' is not available on ${endpoint.serverName}")
                    })
                } else {
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    res.put("result", executeMcpToolCall(toolName, args))
                }
            }
            else -> {
                res.put("error", JSONObject().apply {
                    put("code", -32601)
                    put("message", "Method not found: $method")
                })
            }
        }
        return res
    }

    private fun isBrowserAutomationEnabled(): Boolean {
        val ctx = appContext ?: return true
        return com.example.gemini.data.preferences.AuthPreferences(ctx).isBrowserAutomationEnabledSync()
    }

    private fun isTerminalAutomationEnabled(): Boolean {
        val ctx = appContext ?: return true
        return com.example.gemini.data.preferences.AuthPreferences(ctx).isTerminalAutomationEnabledSync()
    }

    private fun isFlowAutomationEnabled(): Boolean {
        val ctx = appContext ?: return false
        return com.example.gemini.data.preferences.AuthPreferences(ctx).isFlowAutomationEnabledSync()
    }

    private suspend fun getMcpToolsList(endpoint: McpEndpoint): JSONArray {
        if (endpoint == McpEndpoint.EXTENSION_DEVELOPER) return com.example.gemini.ui.browser.WebViewExtensionDeveloper.tools(appContext!!)
        val tools = JSONArray()
        val browserEnabled = isBrowserAutomationEnabled() && endpoint == McpEndpoint.BROWSER
        val terminalEnabled = isTerminalAutomationEnabled() && endpoint == McpEndpoint.TERMINAL
        val flowEnabled = isFlowAutomationEnabled() && endpoint == McpEndpoint.GOOGLE_FLOW

        if (browserEnabled) {
            // 1. Browser Open URL
            tools.put(JSONObject().apply {
                put("name", "browser_open_url")
                put("description", "Open a URL in the in-app WebView browser (supports localhost:port, http, https). Supports specifying desktop/web mode vs mobile mode directly. Runs in background without closing chat.")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("url", JSONObject().apply {
                            put("type", "string")
                            put("description", "URL to load, e.g. http://localhost:5173 or https://example.com")
                        })
                        put("new_tab", JSONObject().apply {
                            put("type", "boolean")
                            put("description", "Whether to open in a new tab (default false)")
                        })
                        put("desktop_mode", JSONObject().apply {
                            put("type", "boolean")
                            put("description", "Open in desktop/web view mode if true, or mobile mode if false. (Optional)")
                        })
                        put("mode", JSONObject().apply {
                            put("type", "string")
                            put("description", "View mode: 'desktop' (or 'web') vs 'mobile'. (Optional)")
                        })
                    })
                    put("required", JSONArray().apply { put("url") })
                })
            })

            // 2. Browser Set View Mode (Desktop vs Mobile)
            tools.put(JSONObject().apply {
                put("name", "browser_set_view_mode")
                put("description", "Toggle or set mobile vs desktop/web view mode for a browser tab. Configures desktop User-Agent, screen resolution spoofing, and wide viewport.")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("tab_id", JSONObject().apply {
                            put("type", "string")
                            put("description", "Optional tab ID to change view mode for (defaults to active tab)")
                        })
                        put("desktop_mode", JSONObject().apply {
                            put("type", "boolean")
                            put("description", "Set to true for desktop/web view mode, false for mobile mode. If neither mode nor desktop_mode is provided, toggles current mode.")
                        })
                        put("mode", JSONObject().apply {
                            put("type", "string")
                            put("description", "Explicit view mode string: 'desktop' (or 'web') vs 'mobile'. If omitted and desktop_mode not provided, toggles current mode.")
                        })
                    })
                })
            })

        // 2. Browser List Tabs
        tools.put(JSONObject().apply {
            put("name", "browser_list_tabs")
            put("description", "List all open browser tabs in the app with their IDs, URLs, titles, loading progress, and error counts.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        })

        // 3. Browser Switch Tab
        tools.put(JSONObject().apply {
            put("name", "browser_switch_tab")
            put("description", "Switch active browser tab by tab_id.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("tab_id", JSONObject().apply {
                        put("type", "string")
                        put("description", "ID of the tab to make active")
                    })
                })
                put("required", JSONArray().apply { put("tab_id") })
            })
        })

        // 4. Browser Close Tab
        tools.put(JSONObject().apply {
            put("name", "browser_close_tab")
            put("description", "Close a browser tab by tab_id.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("tab_id", JSONObject().apply {
                        put("type", "string")
                        put("description", "ID of the tab to close")
                    })
                })
                put("required", JSONArray().apply { put("tab_id") })
            })
        })

        // 5. Browser Screenshot
        tools.put(JSONObject().apply {
            put("name", "browser_screenshot")
            put("description", "Capture a high-res screenshot (Base64 JPEG) of the live WebView viewport or full page, even while user remains on chat screen.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("tab_id", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional tab ID (defaults to active tab)")
                    })
                    put("full_page", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "If true, captures the entire scrollable height")
                    })
                    put("quality", JSONObject().apply {
                        put("type", "integer")
                        put("description", "JPEG quality 10-100 (default 80)")
                    })
                })
            })
        })

        // 6. Browser Inspect DOM
        tools.put(JSONObject().apply {
            put("name", "browser_inspect_dom")
            put("description", "Extract structured layout tree and interactive elements (buttons, inputs, links, headings) with bounding rects and CSS selectors.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("tab_id", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional tab ID (defaults to active tab)")
                    })
                })
            })
        })

        // 7. Browser Interact
        tools.put(JSONObject().apply {
            put("name", "browser_interact")
            put("description", "Perform UI actions on the browser page: click an element, type text into inputs, or scroll.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("tab_id", JSONObject().apply { put("type", "string") })
                    put("action", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply {
                            put("click")
                            put("type")
                            put("scroll")
                        })
                    })
                    put("selector", JSONObject().apply {
                        put("type", "string")
                        put("description", "CSS selector of target element")
                    })
                    put("x", JSONObject().apply { put("type", "number"); put("description", "X coordinate for click") })
                    put("y", JSONObject().apply { put("type", "number"); put("description", "Y coordinate for click") })
                    put("text", JSONObject().apply { put("type", "string"); put("description", "Text to type") })
                    put("clear_first", JSONObject().apply { put("type", "boolean"); put("description", "Clear input before typing") })
                    put("dx", JSONObject().apply { put("type", "integer"); put("description", "Horizontal scroll offset") })
                    put("dy", JSONObject().apply { put("type", "integer"); put("description", "Vertical scroll offset") })
                })
                put("required", JSONArray().apply { put("action") })
            })
        })

        // 7b. Browser Upload File
        tools.put(JSONObject().apply {
            put("name", "browser_upload_file")
            put("description", "Upload local files to the page like a person picking them in a file dialog: taps the page's upload button or file input with a real touch and hands the files to the site's own file chooser, with no picker shown. Omit selector/x/y when the page has a single file input. For drag-and-drop-only areas it falls back to dropping the files (up to 25 MiB). Check the page afterwards (e.g. browser_screenshot) to confirm the site accepted them.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("tab_id", JSONObject().apply { put("type", "string") })
                    put("file_paths", JSONObject().apply {
                        put("type", "array"); put("items", JSONObject().put("type", "string"))
                        put("description", "1–20 local files: absolute, ~/... or relative to the terminal home; inside the terminal home or /sdcard")
                    })
                    put("selector", JSONObject().apply { put("type", "string"); put("description", "CSS selector of the upload button, its <label> or the <input type=file> (hidden inputs work)") })
                    put("x", JSONObject().apply { put("type", "number"); put("description", "Instead of selector: X of the upload button in CSS pixels (as browser_inspect_dom reports)") })
                    put("y", JSONObject().apply { put("type", "number"); put("description", "Y of the upload button in CSS pixels") })
                    put("method", JSONObject().apply {
                        put("type", "string"); put("enum", JSONArray().put("auto").put("chooser").put("drop"))
                        put("description", "auto (default): file chooser, else in-page fallback; chooser: only the real file chooser; drop: only build the files in the page (drag-and-drop areas)")
                    })
                    put("timeout_seconds", JSONObject().apply { put("type", "integer"); put("description", "How long to wait for the page to open its file chooser (1–30, default 10)") })
                })
                put("required", JSONArray().apply { put("file_paths") })
            })
        })

        // 8. Browser Evaluate JS
        tools.put(JSONObject().apply {
            put("name", "browser_eval_js")
            put("description", "Execute arbitrary JavaScript inside the WebView and return the result.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("tab_id", JSONObject().apply { put("type", "string") })
                    put("script", JSONObject().apply { put("type", "string"); put("description", "JavaScript to execute") })
                })
                put("required", JSONArray().apply { put("script") })
            })
        })

        // 9. Browser Logs
        tools.put(JSONObject().apply {
            put("name", "browser_get_console_logs")
            put("description", "Get real-time console.log, console.error, and network failure diagnostics from the webview.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("tab_id", JSONObject().apply { put("type", "string") })
                    put("level", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply { put("ALL"); put("ERROR"); put("WARN"); put("LOG"); put("INFO") })
                    })
                    put("limit", JSONObject().apply { put("type", "integer") })
                })
            })
        })
    }

    if (terminalEnabled) {
            // 10. Terminal List Sessions
            tools.put(JSONObject().apply {
                put("name", "terminal_list_sessions")
                put("description", "List all active in-app terminal sessions (tmux windows & local PTYs) with their paths and status.")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                })
            })

            // 11. Terminal Send Command
            tools.put(JSONObject().apply {
                put("name", "terminal_send_command")
                put("description", "Send a command (e.g. npm run dev, git status) or control key (Ctrl+C) to a live in-app terminal session.")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("session_id", JSONObject().apply { put("type", "string"); put("description", "Session ID or name") })
                        put("command", JSONObject().apply { put("type", "string"); put("description", "Command line to execute") })
                        put("control_key", JSONObject().apply { put("type", "string"); put("description", "Optional control key (e.g. CTRL+C, CTRL+D, ENTER)") })
                        put("append_enter", JSONObject().apply { put("type", "boolean"); put("description", "Append Enter key (default true)") })
                    })
                })
            })

            // 12. Terminal Read Output
            tools.put(JSONObject().apply {
                put("name", "terminal_read_output")
                put("description", "Read terminal output from a live in-app terminal session. Supports structured 'last_command' mode (returns last executed command, running status, and its direct output) or 'raw' mode (returns full raw transcript).")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("session_id", JSONObject().apply { put("type", "string"); put("description", "Optional session ID (defaults to active session)") })
                        put("mode", JSONObject().apply {
                            put("type", "string")
                            put("enum", JSONArray().apply { put("last_command"); put("raw") })
                            put("description", "Output mode: 'last_command' (default, returns structured JSON of last command and its output) or 'raw' (returns unparsed transcript buffer).")
                        })
                        put("lines_count", JSONObject().apply { put("type", "integer"); put("description", "Max lines to return (default 100)") })
                    })
                })
            })

            // 13. Terminal Create Session
            tools.put(JSONObject().apply {
                put("name", "terminal_create_session")
                put("description", "Create a new named terminal tab in the app.")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("working_dir", JSONObject().apply { put("type", "string") })
                        put("force_shell", JSONObject().apply { put("type", "string") })
                    })
                })
            })

            // 14. Terminal Kill Process
            tools.put(JSONObject().apply {
                put("name", "terminal_kill_process")
                put("description", "Send a termination signal (SIGINT/Ctrl+C, SIGTERM, or SIGKILL) to stop a running process in the terminal (e.g. npm run dev, python, long tasks).")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("session_id", JSONObject().apply { put("type", "string"); put("description", "Session ID or name") })
                        put("signal", JSONObject().apply {
                            put("type", "string")
                            put("enum", JSONArray().apply { put("SIGINT"); put("SIGTERM"); put("SIGKILL"); put("CTRL+C") })
                            put("description", "Signal to send (default SIGINT / Ctrl+C)")
                        })
                    })
                })
            })

            // 15. Terminal Close Session
            tools.put(JSONObject().apply {
                put("name", "terminal_close_session")
                put("description", "Close and destroy an entire terminal session tab.")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("session_id", JSONObject().apply { put("type", "string"); put("description", "Session ID to close") })
                    })
                    put("required", JSONArray().apply { put("session_id") })
                })
            })
        }

        if (flowEnabled) {
            val extensionTools = com.example.gemini.ui.browser.BrowserExtensionManager.instance.tools("google-flow")
            for (i in 0 until extensionTools.length()) {
                val tool = extensionTools.getJSONObject(i)
                if (tool.optString("name").startsWith("flow_")) tools.put(tool)
            }
        }

        return tools
    }

    private fun flowError(message: String): JSONObject = JSONObject().apply {
        put("isError", true)
        put("content", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", message)
            })
        })
    }

    private suspend fun executeMcpToolCall(toolName: String, args: JSONObject): JSONObject {
        if (toolName.startsWith("webview_extension_")) return com.example.gemini.ui.browser.WebViewExtensionDeveloper.call(appContext!!, toolName, args)
        if (toolName.startsWith("browser_") && !isBrowserAutomationEnabled()) {
            return JSONObject().apply {
                put("isError", true)
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", "Error: Browser automation is currently disabled in Settings > Automation.")
                    })
                })
            }
        }
        if (toolName.startsWith("terminal_") && !isTerminalAutomationEnabled()) {
            return JSONObject().apply {
                put("isError", true)
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", "Error: Terminal automation is currently disabled in Settings > Automation.")
                    })
                })
            }
        }
        if (toolName.startsWith("flow_") && !isFlowAutomationEnabled()) {
            return flowError("Error: Flow automation is currently disabled in Settings > Automation.")
        }

        if (toolName.startsWith("flow_")) {
            return com.example.gemini.ui.browser.BrowserExtensionManager.instance.callTool("google-flow", toolName, args)
        }

        val bManager = BrowserSessionManager.instance
        val tBridge = LocalTerminalBridge.instance
        val content = JSONArray()

        try {
            when (toolName) {
                "browser_open_url" -> {
                    val url = args.optString("url", "")
                    val newTab = args.optBoolean("new_tab", false)
                    val desktopMode = when {
                        args.has("desktop_mode") -> args.optBoolean("desktop_mode")
                        args.has("mode") -> {
                            val m = args.optString("mode", "").lowercase()
                            m == "desktop" || m == "web"
                        }
                        else -> null
                    }
                    val tab = bManager.openUrl(url, newTab = newTab, desktopMode = desktopMode)
                    bManager.waitForPageLoad(tab.id, 5000)
                    val modeStr = if (tab.isDesktopMode) "desktop" else "mobile"
                    content.put(JSONObject().apply {
                        put("type", "text")
                        put("text", "Opened $url in tab ${tab.id} ('${tab.title}') [viewMode: $modeStr]")
                    })
                }
                "browser_set_view_mode" -> {
                    val tabId = args.optString("tab_id", "").ifBlank { null }
                    val tab = bManager.getTab(tabId)
                    if (tab == null) {
                        content.put(JSONObject().apply {
                            put("type", "text")
                            put("text", "No active browser tab found")
                        })
                    } else {
                        val enableDesktop = when {
                            args.has("desktop_mode") -> args.optBoolean("desktop_mode")
                            args.has("mode") -> {
                                val m = args.optString("mode", "").lowercase()
                                m == "desktop" || m == "web"
                            }
                            else -> !tab.isDesktopMode
                        }
                        bManager.setDesktopMode(tab, enableDesktop)
                        val modeStr = if (enableDesktop) "desktop" else "mobile"
                        content.put(JSONObject().apply {
                            put("type", "text")
                            put("text", "Switched tab ${tab.id} to $modeStr mode (URL: ${tab.url})")
                        })
                    }
                }
                "browser_list_tabs" -> {
                    val tabs = bManager.listTabsJson()
                    content.put(JSONObject().apply {
                        put("type", "text")
                        put("text", tabs.toString(2))
                    })
                }
                "browser_switch_tab" -> {
                    val tabId = args.optString("tab_id", "")
                    val ok = bManager.switchTab(tabId)
                    content.put(JSONObject().apply {
                        put("type", "text")
                        put("text", if (ok) "Switched to tab $tabId" else "Tab $tabId not found")
                    })
                }
                "browser_close_tab" -> {
                    val tabId = args.optString("tab_id", "")
                    val ok = bManager.closeTab(tabId)
                    content.put(JSONObject().apply {
                        put("type", "text")
                        put("text", if (ok) "Closed tab $tabId" else "Tab $tabId not found")
                    })
                }
                "browser_screenshot" -> {
                    val tabId = args.optString("tab_id").ifBlank { null }
                    val fullPage = args.optBoolean("full_page", false)
                    val quality = args.optInt("quality", 80)
                    val res = bManager.captureScreenshotBase64(tabId, fullPage, quality)
                    if (res.isSuccess) {
                        val base64Data = res.getOrNull()!!.removePrefix("data:image/jpeg;base64,")
                        content.put(JSONObject().apply {
                            put("type", "image")
                            put("data", base64Data)
                            put("mimeType", "image/jpeg")
                        })
                    } else {
                        return JSONObject().apply {
                            put("isError", true)
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", "Screenshot failed: ${res.exceptionOrNull()?.message}")
                                })
                            })
                        }
                    }
                }
                "browser_inspect_dom" -> {
                    val tabId = args.optString("tab_id").ifBlank { null }
                    val res = bManager.inspectDom(tabId)
                    if (res.isSuccess) {
                        content.put(JSONObject().apply {
                            put("type", "text")
                            put("text", res.getOrNull()!!.toString(2))
                        })
                    } else {
                        return JSONObject().apply {
                            put("isError", true)
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", "Inspect DOM failed: ${res.exceptionOrNull()?.message}")
                                })
                            })
                        }
                    }
                }
                "browser_interact" -> {
                    val tabId = args.optString("tab_id").ifBlank { null }
                    val action = args.optString("action", "")
                    val selector = args.optString("selector").ifBlank { null }
                    when (action) {
                        "click" -> {
                            val x = if (args.has("x")) args.optDouble("x").toFloat() else null
                            val y = if (args.has("y")) args.optDouble("y").toFloat() else null
                            val res = bManager.clickElement(tabId, selector, x, y)
                            content.put(JSONObject().apply {
                                put("type", "text")
                                put("text", if (res.isSuccess) "Clicked successfully" else "Click failed: ${res.exceptionOrNull()?.message}")
                            })
                        }
                        "type" -> {
                            val text = args.optString("text", "")
                            val clearFirst = args.optBoolean("clear_first", false)
                            val res = bManager.typeText(tabId, selector ?: "input", text, clearFirst)
                            content.put(JSONObject().apply {
                                put("type", "text")
                                put("text", if (res.isSuccess) "Typed text successfully" else "Type failed: ${res.exceptionOrNull()?.message}")
                            })
                        }
                        "scroll" -> {
                            val dx = args.optInt("dx", 0)
                            val dy = args.optInt("dy", 0)
                            val res = bManager.scrollPage(tabId, dx, dy, selector)
                            content.put(JSONObject().apply {
                                put("type", "text")
                                put("text", if (res.isSuccess) "Scrolled successfully" else "Scroll failed: ${res.exceptionOrNull()?.message}")
                            })
                        }
                    }
                }
                "browser_upload_file" -> {
                    val paths = args.optJSONArray("file_paths")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                    val res = bManager.uploadFiles(
                        args.optString("tab_id").ifBlank { null }, paths, args.optString("selector").ifBlank { null },
                        if (args.has("x")) args.optDouble("x").toFloat() else null, if (args.has("y")) args.optDouble("y").toFloat() else null,
                        method = args.optString("method").ifBlank { "auto" },
                        timeoutMs = args.optInt("timeout_seconds", 10).coerceIn(1, 30) * 1000L
                    )
                    if (res.isFailure) return JSONObject().apply {
                        put("isError", true)
                        put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Upload failed: ${res.exceptionOrNull()?.message}")))
                    }
                    content.put(JSONObject().put("type", "text").put("text", res.getOrThrow().toString(2)))
                }
                "browser_eval_js" -> {
                    val tabId = args.optString("tab_id").ifBlank { null }
                    val script = args.optString("script", "")
                    val res = bManager.evaluateJs(tabId, script)
                    content.put(JSONObject().apply {
                        put("type", "text")
                        put("text", if (res.isSuccess) res.getOrNull() ?: "null" else "Error: ${res.exceptionOrNull()?.message}")
                    })
                }
                "browser_get_console_logs" -> {
                    val tabId = args.optString("tab_id").ifBlank { null }
                    val level = args.optString("level").ifBlank { null }
                    val limit = args.optInt("limit", 100)
                    val logs = bManager.getConsoleLogs(tabId, level, limit)
                    val netErrors = bManager.getNetworkErrors(tabId, limit)
                    val out = JSONObject().apply {
                        put("consoleLogs", JSONArray().apply { logs.forEach { put(it.toJsonObject()) } })
                        put("networkErrors", JSONArray().apply { netErrors.forEach { put(it.toJsonObject()) } })
                    }
                    content.put(JSONObject().apply {
                        put("type", "text")
                        put("text", out.toString(2))
                    })
                }
                "terminal_list_sessions" -> {
                    val sessions = tBridge.listSessionsJson()
                    content.put(JSONObject().apply {
                        put("type", "text")
                        put("text", sessions.toString(2))
                    })
                }
                "terminal_send_command" -> {
                    val sessionId = args.optString("session_id").ifBlank { null }
                    val command = args.optString("command", "")
                    val ctrlKey = args.optString("control_key").ifBlank { null }
                    val appendEnter = args.optBoolean("append_enter", true)

                    if (!ctrlKey.isNullOrBlank()) {
                        val res = tBridge.sendControlKey(sessionId, ctrlKey)
                        content.put(JSONObject().apply {
                            put("type", "text")
                            put("text", if (res.isSuccess) "Sent control key $ctrlKey" else "Failed: ${res.exceptionOrNull()?.message}")
                        })
                    } else {
                        val res = tBridge.sendCommand(sessionId, command, appendEnter)
                        content.put(JSONObject().apply {
                            put("type", "text")
                            put("text", if (res.isSuccess) "Sent command to terminal" else "Failed: ${res.exceptionOrNull()?.message}")
                        })
                    }
                }
                "terminal_kill_process" -> {
                    val sessionId = args.optString("session_id").ifBlank { null }
                    val signal = args.optString("signal", "SIGINT")
                    val res = tBridge.killProcess(sessionId, signal)
                    content.put(JSONObject().apply {
                        put("type", "text")
                        put("text", if (res.isSuccess) "Sent $signal to terminate running process" else "Failed to kill process: ${res.exceptionOrNull()?.message}")
                    })
                }
                "terminal_close_session" -> {
                    val sessionId = args.optString("session_id", "")
                    val res = tBridge.closeSession(sessionId)
                    content.put(JSONObject().apply {
                        put("type", "text")
                        put("text", if (res.isSuccess) "Closed terminal session $sessionId" else "Failed to close session: ${res.exceptionOrNull()?.message}")
                    })
                }
                "terminal_read_output" -> {
                    val sessionId = args.optString("session_id").ifBlank { null }
                    val linesCount = args.optInt("lines_count", 100)
                    val mode = args.optString("mode", "last_command")
                    if (mode == "raw") {
                        val res = tBridge.readTranscript(sessionId, linesCount)
                        content.put(JSONObject().apply {
                            put("type", "text")
                            put("text", if (res.isSuccess) res.getOrNull() ?: "" else "Error: ${res.exceptionOrNull()?.message}")
                        })
                    } else {
                        val res = tBridge.getLastExecution(sessionId, linesCount)
                        content.put(JSONObject().apply {
                            put("type", "text")
                            put("text", if (res.isSuccess) res.getOrNull()!!.toString(2) else "Error: ${res.exceptionOrNull()?.message}")
                        })
                    }
                }
                "terminal_create_session" -> {
                    val workingDir = args.optString("working_dir").ifBlank { null }
                    val forceShell = args.optString("force_shell").ifBlank { null }
                    val res = tBridge.createSession(workingDir, forceShell)
                    content.put(JSONObject().apply {
                        put("type", "text")
                        put("text", if (res.isSuccess) res.getOrNull()!!.toJsonObject().toString(2) else "Error: ${res.exceptionOrNull()?.message}")
                    })
                }
                else -> {
                    return JSONObject().apply {
                        put("isError", true)
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", "Unknown tool: $toolName")
                            })
                        })
                    }
                }
            }
        } catch (e: Exception) {
            return JSONObject().apply {
                put("isError", true)
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", "Tool execution error: ${e.message}")
                    })
                })
            }
        }

        return JSONObject().apply {
            put("content", content)
        }
    }

    // ==========================================
    // REST API HANDLERS
    // ==========================================

    private suspend fun handleBrowserRest(
        subPath: String,
        httpMethod: String,
        body: String,
        query: String,
        out: OutputStream
    ) {
        val bManager = BrowserSessionManager.instance
        val bodyJson = try { JSONObject(body) } catch (_: Exception) { JSONObject() }

        when (subPath) {
            "tabs" -> {
                sendResponse(out, 200, "application/json", bManager.listTabsJson().toString())
            }
            "open" -> {
                val url = bodyJson.optString("url", "")
                val newTab = bodyJson.optBoolean("new_tab", false)
                val tab = withContext(Dispatchers.Main) {
                    val previous = bManager.getActiveTab()?.id
                    val target = bodyJson.optString("tab_id").ifBlank { null }
                    if (target != null && bManager.tabs.none { it.id == target }) throw IllegalArgumentException("Tab not found: $target")
                    bManager.openUrl(url, tabId = target, newTab = newTab, desktopMode = if (bodyJson.has("desktop")) bodyJson.optBoolean("desktop") else null).also {
                        if (!bodyJson.optBoolean("active", true) && previous != null) bManager.switchTab(previous)
                    }
                }
                sendResponse(out, 200, "application/json", tab.toSummaryJson().toString())
            }
            "update" -> {
                val response = withContext(Dispatchers.Main) {
                    val tab = bManager.tabs.firstOrNull { it.id == bodyJson.optString("tab_id") } ?: throw IllegalArgumentException("Tab not found")
                    if (bodyJson.has("url")) bManager.openUrl(bodyJson.getString("url"), tabId = tab.id)
                    if (bodyJson.optBoolean("active")) bManager.switchTab(tab.id)
                    tab.toSummaryJson()
                }
                sendResponse(out, 200, "application/json", response.toString())
            }
            "devtools" -> {
                val tab = withContext(Dispatchers.Main) {
                    val found = bManager.tabs.firstOrNull { it.id == bodyJson.optString("tab_id") } ?: throw IllegalArgumentException("Tab not found")
                    android.webkit.WebView.setWebContentsDebuggingEnabled(true)
                    if (found.webView == null) bManager.ensureWebViewAttached(appContext!!, found, false)
                    found
                }
                bManager.evaluateJs(tab.id, "window.__antigemTabId=${JSONObject.quote(tab.id)}").getOrThrow()
                sendResponse(out, 200, "application/json", JSONObject().put("socket", "webview_devtools_remote_${android.os.Process.myPid()}").toString())
            }
            "zoom" -> {
                withContext(Dispatchers.Main) {
                    val tab = bManager.tabs.firstOrNull { it.id == bodyJson.optString("tab_id") } ?: throw IllegalArgumentException("Tab not found")
                    val view = tab.webView ?: throw IllegalStateException("Tab has no WebView")
                    val current = runCatching { JSONObject(bManager.evaluateJs(tab.id, "JSON.stringify({scale:window.visualViewport?.scale||1})").getOrThrow()).getDouble("scale") }.getOrDefault(1.0)
                    view.zoomBy((bodyJson.optDouble("factor", 1.0) / current).toFloat().coerceIn(0.01f, 100f))
                }
                sendResponse(out, 200, "application/json", "{\"success\":true}")
            }
            "automation" -> {
                val tab = withContext(Dispatchers.Main) {
                    val found = bManager.tabs.firstOrNull { it.id == bodyJson.optString("tab_id") } ?: throw IllegalArgumentException("Tab not found")
                    com.example.gemini.ui.browser.BrowserAutomationController.update(found, bodyJson.optString("owner"),
                        if (bodyJson.has("busy")) bodyJson.optBoolean("busy") else null,
                        if (bodyJson.has("locked")) bodyJson.optBoolean("locked") else null)
                    found
                }
                bodyJson.optJSONObject("tap")?.let { tap ->
                    bManager.dispatchTap(tab.id, tap.getDouble("fx").toFloat(), tap.getDouble("fy").toFloat()).getOrThrow()
                }
                sendResponse(out, 200, "application/json", "{\"success\":true}")
            }
            "media/frame", "media/info" -> {
                val file = File(bodyJson.getString("path"))
                require(file.isFile) { "Media file not found" }
                val retriever = android.media.MediaMetadataRetriever()
                val result = try {
                    retriever.setDataSource(file.absolutePath)
                    val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    if (subPath == "media/info") JSONObject().put("duration", duration / 1000.0)
                    else {
                        val atMs = (if (bodyJson.has("at_seconds")) (bodyJson.getDouble("at_seconds") * 1000).toLong() else duration - 50).coerceIn(0, maxOf(0, duration - 1))
                        val bitmap = retriever.getFrameAtTime(atMs * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST) ?: throw IllegalStateException("Could not extract video frame")
                        val target = File(file.parentFile, "frame_${file.nameWithoutExtension}_${atMs}.png")
                        try { target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
                        JSONObject().put("path", target.absolutePath)
                    }
                } finally { retriever.release() }
                sendResponse(out, 200, "application/json", result.toString())
            }
            "screenshot" -> {
                val tabId = bodyJson.optString("tab_id").ifBlank { null }
                val fullPage = bodyJson.optBoolean("full_page", false)
                val quality = bodyJson.optInt("quality", 80)
                val res = bManager.captureScreenshotBase64(tabId, fullPage, quality)
                if (res.isSuccess) {
                    val json = JSONObject().apply {
                        put("success", true)
                        put("image", res.getOrNull())
                    }
                    sendResponse(out, 200, "application/json", json.toString())
                } else {
                    sendResponse(out, 500, "application/json", "{\"error\": \"${res.exceptionOrNull()?.message}\"}")
                }
            }
            "inspect" -> {
                val tabId = bodyJson.optString("tab_id").ifBlank { null }
                val res = bManager.inspectDom(tabId)
                if (res.isSuccess) {
                    sendResponse(out, 200, "application/json", res.getOrNull()!!.toString())
                } else {
                    sendResponse(out, 500, "application/json", "{\"error\": \"${res.exceptionOrNull()?.message}\"}")
                }
            }
            "interact" -> {
                val tabId = bodyJson.optString("tab_id").ifBlank { null }
                val action = bodyJson.optString("action", "")
                val selector = bodyJson.optString("selector").ifBlank { null }
                when (action) {
                    "click" -> {
                        val x = if (bodyJson.has("x")) bodyJson.optDouble("x").toFloat() else null
                        val y = if (bodyJson.has("y")) bodyJson.optDouble("y").toFloat() else null
                        val res = bManager.clickElement(tabId, selector, x, y)
                        sendResponse(out, if (res.isSuccess) 200 else 500, "application/json", "{\"success\": ${res.isSuccess}}")
                    }
                    "type" -> {
                        val text = bodyJson.optString("text", "")
                        val clear = bodyJson.optBoolean("clear_first", false)
                        val res = bManager.typeText(tabId, selector ?: "input", text, clear)
                        sendResponse(out, if (res.isSuccess) 200 else 500, "application/json", "{\"success\": ${res.isSuccess}}")
                    }
                    "scroll" -> {
                        val dx = bodyJson.optInt("dx", 0)
                        val dy = bodyJson.optInt("dy", 0)
                        val res = bManager.scrollPage(tabId, dx, dy, selector)
                        sendResponse(out, if (res.isSuccess) 200 else 500, "application/json", "{\"success\": ${res.isSuccess}}")
                    }
                    else -> {
                        sendResponse(out, 400, "application/json", "{\"error\": \"Invalid action\"}")
                    }
                }
            }
            "logs" -> {
                val tabId = bodyJson.optString("tab_id").ifBlank { null }
                val level = bodyJson.optString("level").ifBlank { null }
                val logs = bManager.getConsoleLogs(tabId, level)
                val netErrors = bManager.getNetworkErrors(tabId)
                val res = JSONObject().apply {
                    put("consoleLogs", JSONArray().apply { logs.forEach { put(it.toJsonObject()) } })
                    put("networkErrors", JSONArray().apply { netErrors.forEach { put(it.toJsonObject()) } })
                }
                sendResponse(out, 200, "application/json", res.toString())
            }
            "eval" -> {
                val tabId = bodyJson.optString("tab_id").ifBlank { null }
                val script = bodyJson.optString("script", "")
                val res = bManager.evaluateJs(tabId, script)
                if (res.isSuccess) {
                    val raw = res.getOrNull().orEmpty()
                    val responseJson = JSONObject().apply {
                        put("success", true)
                        put("result", raw)
                    }
                    sendResponse(out, 200, "application/json", responseJson.toString())
                } else {
                    sendResponse(out, 500, "application/json", JSONObject().apply {
                        put("success", false)
                        put("error", res.exceptionOrNull()?.message ?: "evaluateJs failed")
                    }.toString())
                }
            }
            "tap" -> {
                val tabId = bodyJson.optString("tab_id").ifBlank { null }
                val x = bodyJson.optDouble("x", 0.0).toFloat()
                val y = bodyJson.optDouble("y", 0.0).toFloat()
                val tab = if (tabId.isNullOrBlank()) bManager.getActiveTab() else bManager.tabs.find { it.id == tabId }
                val wv = tab?.webView
                val res = if (wv != null && wv.width > 0 && wv.height > 0) {
                    val fx = if (x > 1f) (x / wv.width).coerceIn(0f, 1f) else x.coerceIn(0f, 1f)
                    val fy = if (y > 1f) (y / wv.height).coerceIn(0f, 1f) else y.coerceIn(0f, 1f)
                    bManager.dispatchTap(tabId, fx, fy)
                } else {
                    bManager.dispatchTap(tabId, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
                }
                sendResponse(out, if (res.isSuccess) 200 else 500, "application/json", JSONObject().apply {
                    put("success", res.isSuccess)
                    if (res.isFailure) put("error", res.exceptionOrNull()?.message)
                }.toString())
            }
            "upload" -> {
                // chrome.antigem.uploadFiles from extensions; paths were checked by the extension host
                val paths = bodyJson.optJSONArray("file_paths")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                val res = bManager.uploadFiles(
                    bodyJson.optString("tab_id").ifBlank { null }, paths, bodyJson.optString("selector").ifBlank { null },
                    fx = if (bodyJson.has("fx")) bodyJson.getDouble("fx").toFloat() else null,
                    fy = if (bodyJson.has("fy")) bodyJson.getDouble("fy").toFloat() else null,
                    method = bodyJson.optString("method").ifBlank { "auto" },
                    timeoutMs = bodyJson.optLong("timeout_ms", 10_000).coerceIn(1_000, 30_000)
                )
                sendResponse(out, if (res.isSuccess) 200 else 400, "application/json",
                    (res.getOrNull()?.put("success", true) ?: JSONObject().put("success", false).put("error", res.exceptionOrNull()?.message)).toString())
            }
            "key" -> {
                val tabId = bodyJson.optString("tab_id").ifBlank { null }
                val key = bodyJson.optString("key", "")
                val keyCode = when (key.uppercase()) {
                    "ENTER", "RETURN" -> KeyEvent.KEYCODE_ENTER
                    "ESCAPE", "ESC" -> KeyEvent.KEYCODE_ESCAPE
                    "BACKSPACE" -> KeyEvent.KEYCODE_DEL
                    "TAB" -> KeyEvent.KEYCODE_TAB
                    "SPACE" -> KeyEvent.KEYCODE_SPACE
                    else -> bodyJson.optInt("code", KeyEvent.KEYCODE_UNKNOWN)
                }
                val res = if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    bManager.dispatchKey(tabId, keyCode)
                } else {
                    Result.failure(Exception("Unknown key: $key"))
                }
                sendResponse(out, if (res.isSuccess) 200 else 500, "application/json", JSONObject().apply {
                    put("success", res.isSuccess)
                    if (res.isFailure) put("error", res.exceptionOrNull()?.message)
                }.toString())
            }
            "close" -> {
                val tabId = bodyJson.optString("tab_id", "")
                val closed = withContext(Dispatchers.Main) { if (tabId.isNotBlank()) bManager.closeTab(tabId) else false }
                sendResponse(out, if (closed) 200 else 404, "application/json", JSONObject().apply {
                    put("success", closed)
                }.toString())
            }
            "cookies" -> {
                val url = bodyJson.optString("url", "")
                if (url.isBlank()) {
                    sendResponse(out, 400, "application/json", "{\"error\": \"url is required\"}")
                } else {
                    val cookieMgr = android.webkit.CookieManager.getInstance()
                    if (httpMethod == "POST" && bodyJson.has("cookie")) {
                        val cookieStr = bodyJson.optString("cookie")
                        cookieMgr.setCookie(url, cookieStr)
                        cookieMgr.flush()
                        sendResponse(out, 200, "application/json", "{\"success\": true}")
                    } else {
                        val cookies = cookieMgr.getCookie(url) ?: ""
                        sendResponse(out, 200, "application/json", JSONObject().apply {
                            put("cookies", cookies)
                        }.toString())
                    }
                }
            }
            "extensions" -> {
                val extMgr = com.example.gemini.ui.browser.BrowserExtensionManager.instance
                val list = extMgr.loadExtensions()
                val arr = JSONArray()
                for (ext in list) {
                    arr.put(ext.toJsonObject())
                }
                val res = JSONObject().apply {
                    put("success", true)
                    put("extensions", arr)
                }
                sendResponse(out, 200, "application/json", res.toString())
            }
            "extensions/refresh" -> {
                val manager = com.example.gemini.ui.browser.BrowserExtensionManager.instance
                manager.refreshExtensions()
                sendResponse(out, 200, "application/json", "{\"success\":true}")
            }
            "extensions/rename" -> {
                val extension = com.example.gemini.ui.browser.BrowserExtensionManager.instance.renameFolder(
                    bodyJson.getString("id"), bodyJson.getString("folder_name")
                )
                sendResponse(out, 200, "application/json", extension.toJsonObject().toString())
            }
            "extensions/install" -> {
                // The IDE bridge checks the ZIP is in the terminal home or shared storage, unpacks and installs it
                val extension = com.example.gemini.ui.browser.BrowserExtensionManager.instance.installDraft(
                    null, bodyJson.getString("zip_path"), bodyJson.getString("folder_name"),
                    bodyJson.optString("update_id").takeIf { it.isNotBlank() }
                )
                sendResponse(out, 200, "application/json", extension.toJsonObject().toString())
            }
            "extensions/toggle" -> {
                val extMgr = com.example.gemini.ui.browser.BrowserExtensionManager.instance
                val id = bodyJson.optString("id", "")
                val enabled = bodyJson.optBoolean("enabled", true)
                if (id.isNotBlank()) {
                    extMgr.setEnabled(id, enabled)
                    sendResponse(out, 200, "application/json", "{\"success\": true}")
                } else {
                    sendResponse(out, 400, "application/json", "{\"error\": \"Extension id required\"}")
                }
            }
            "extensions/reload" -> {
                val extMgr = com.example.gemini.ui.browser.BrowserExtensionManager.instance
                val id = bodyJson.optString("id", "")
                extMgr.reload(id)
                sendResponse(out, 200, "application/json", "{\"success\": true}")
            }
            else -> {
                sendResponse(out, 404, "application/json", "{\"error\": \"Unknown browser API\"}")
            }
        }
    }

    private suspend fun handleTerminalRest(
        subPath: String,
        httpMethod: String,
        body: String,
        query: String,
        out: OutputStream
    ) {
        val tBridge = LocalTerminalBridge.instance
        val bodyJson = try { JSONObject(body) } catch (_: Exception) { JSONObject() }

        when (subPath) {
            "sessions" -> {
                sendResponse(out, 200, "application/json", tBridge.listSessionsJson().toString())
            }
            "send" -> {
                val sessionId = bodyJson.optString("session_id").ifBlank { null }
                val cmd = bodyJson.optString("command", "")
                val ctrlKey = bodyJson.optString("control_key").ifBlank { null }
                val appendEnter = bodyJson.optBoolean("append_enter", true)

                val res = if (!ctrlKey.isNullOrBlank()) {
                    tBridge.sendControlKey(sessionId, ctrlKey)
                } else {
                    tBridge.sendCommand(sessionId, cmd, appendEnter)
                }
                sendResponse(out, if (res.isSuccess) 200 else 500, "application/json", "{\"success\": ${res.isSuccess}}")
            }
            "read" -> {
                val sessionId = bodyJson.optString("session_id").ifBlank { null }
                val lines = bodyJson.optInt("lines_count", 100)
                val mode = bodyJson.optString("mode", "last_command")
                if (mode == "raw") {
                    val res = tBridge.readTranscript(sessionId, lines)
                    if (res.isSuccess) {
                        val json = JSONObject().apply {
                            put("success", true)
                            put("transcript", res.getOrNull())
                        }
                        sendResponse(out, 200, "application/json", json.toString())
                    } else {
                        sendResponse(out, 500, "application/json", "{\"error\": \"${res.exceptionOrNull()?.message}\"}")
                    }
                } else {
                    val res = tBridge.getLastExecution(sessionId, lines)
                    if (res.isSuccess) {
                        val json = JSONObject().apply {
                            put("success", true)
                            put("execution", res.getOrNull())
                        }
                        sendResponse(out, 200, "application/json", json.toString())
                    } else {
                        sendResponse(out, 500, "application/json", "{\"error\": \"${res.exceptionOrNull()?.message}\"}")
                    }
                }
            }
            "create" -> {
                val workingDir = bodyJson.optString("working_dir").ifBlank { null }
                val forceShell = bodyJson.optString("force_shell").ifBlank { null }
                val res = tBridge.createSession(workingDir, forceShell)
                if (res.isSuccess) {
                    sendResponse(out, 200, "application/json", res.getOrNull()!!.toJsonObject().toString())
                } else {
                    sendResponse(out, 500, "application/json", "{\"error\": \"${res.exceptionOrNull()?.message}\"}")
                }
            }
            else -> {
                sendResponse(out, 404, "application/json", "{\"error\": \"Unknown terminal API\"}")
            }
        }
    }
}
