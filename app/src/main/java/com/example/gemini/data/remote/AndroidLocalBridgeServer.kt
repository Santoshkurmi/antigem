package com.example.gemini.data.remote

import android.content.Context
import android.util.Log
import com.example.gemini.data.local.LocalTerminalBridge
import com.example.gemini.ui.browser.BrowserSessionManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
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

    companion object {
        private const val TAG = "AndroidBridgeServer"
        const val DEFAULT_PORT = 8765
        val instance: AndroidLocalBridgeServer by lazy { AndroidLocalBridgeServer() }
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientThreadPool = Executors.newFixedThreadPool(8)
    private var appContext: Context? = null
    var boundPort: Int = DEFAULT_PORT
        private set

    fun start(context: Context, port: Int = DEFAULT_PORT) {
        if (isRunning) return
        appContext = context.applicationContext
        BrowserSessionManager.instance.init(context)
        LocalTerminalBridge.instance.init(context)

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

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            val firstLine = reader.readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val path = rawPath.substringBefore("?")
            val queryString = if (rawPath.contains("?")) rawPath.substringAfter("?") else ""

            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            var line: String? = reader.readLine()
            while (!line.isNullOrBlank()) {
                val currentLine = line
                val colonIdx = currentLine.indexOf(':')
                if (colonIdx != -1) {
                    val key = currentLine.substring(0, colonIdx).trim().lowercase()
                    val value = currentLine.substring(colonIdx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
                line = reader.readLine()
            }

            var body = ""
            if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = reader.read(buf, readTotal, contentLength - readTotal)
                    if (r == -1) break
                    readTotal += r
                }
                body = String(buf, 0, readTotal)
            }

            // Handle CORS preflight
            if (method == "OPTIONS") {
                sendResponse(output, 200, "application/json", "{}")
                return
            }

            // Route request
            runBlocking {
                when {
                    path == "/mcp" || path == "/rpc" -> {
                        val mcpRes = handleMcpJsonRpc(body)
                        sendResponse(output, 200, "application/json", mcpRes.toString())
                    }
                    path == "/api/health" -> {
                        val res = JSONObject().apply {
                            put("status", "ok")
                            put("port", boundPort)
                            put("tabsCount", BrowserSessionManager.instance.tabs.size)
                            put("terminalsCount", LocalTerminalBridge.instance.listSessions().size)
                        }
                        sendResponse(output, 200, "application/json", res.toString())
                    }
                    path.startsWith("/api/browser/") -> {
                        handleBrowserRest(path.removePrefix("/api/browser/"), method, body, queryString, output)
                    }
                    path.startsWith("/api/terminal/") -> {
                        handleTerminalRest(path.removePrefix("/api/terminal/"), method, body, queryString, output)
                    }
                    else -> {
                        sendResponse(output, 404, "application/json", "{\"error\": \"Not Found\"}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling client request", e)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendResponse(
        out: OutputStream,
        statusCode: Int,
        contentType: String,
        body: String
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    // ==========================================
    // MCP JSON-RPC 2.0 PROTOCOL HANDLER
    // ==========================================

    private suspend fun handleMcpJsonRpc(body: String): JSONObject {
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
                    put("capabilities", JSONObject().apply {
                        put("tools", JSONObject())
                    })
                    put("serverInfo", JSONObject().apply {
                        put("name", "android_device_bridge")
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
                    put("tools", getMcpToolsList())
                })
            }
            "tools/call" -> {
                val params = req.optJSONObject("params") ?: JSONObject()
                val toolName = params.optString("name", "")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val callResult = executeMcpToolCall(toolName, args)
                res.put("result", callResult)
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

    private fun getMcpToolsList(): JSONArray {
        val tools = JSONArray()

        // 1. Browser Open URL
        tools.put(JSONObject().apply {
            put("name", "browser_open_url")
            put("description", "Open a URL in the in-app WebView browser (supports localhost:port, http, https). Runs in background without closing chat.")
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
                })
                put("required", JSONArray().apply { put("url") })
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
            put("description", "Read terminal transcript / output buffer from a live in-app terminal session.")
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("session_id", JSONObject().apply { put("type", "string") })
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

        return tools
    }

    private suspend fun executeMcpToolCall(toolName: String, args: JSONObject): JSONObject {
        val bManager = BrowserSessionManager.instance
        val tBridge = LocalTerminalBridge.instance
        val content = JSONArray()

        try {
            when (toolName) {
                "browser_open_url" -> {
                    val url = args.optString("url", "")
                    val newTab = args.optBoolean("new_tab", false)
                    val tab = bManager.openUrl(url, newTab = newTab)
                    bManager.waitForPageLoad(tab.id, 5000)
                    content.put(JSONObject().apply {
                        put("type", "text")
                        put("text", "Opened $url in tab ${tab.id} ('${tab.title}')")
                    })
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
                    val res = tBridge.readTranscript(sessionId, linesCount)
                    content.put(JSONObject().apply {
                        put("type", "text")
                        put("text", if (res.isSuccess) res.getOrNull() ?: "" else "Error: ${res.exceptionOrNull()?.message}")
                    })
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
                val tab = bManager.openUrl(url, newTab = newTab)
                sendResponse(out, 200, "application/json", tab.toSummaryJson().toString())
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
