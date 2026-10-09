package com.example.gemini.data.remote

import android.content.Context
import android.util.Log
import com.example.gemini.data.local.LocalTerminalBridge
import com.example.gemini.ui.browser.BrowserSessionManager
import com.example.gemini.ui.browser.FlowAction
import com.example.gemini.ui.browser.FlowAutomationManager
import com.example.gemini.ui.browser.FlowCreditBudget
import com.example.gemini.ui.browser.FlowJob
import com.example.gemini.ui.browser.FlowRequest
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
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
        /** Per app (build flavor) so com.antigem and com.termux do not collide. */
        val DEFAULT_PORT = com.example.gemini.BuildConfig.BROWSER_MCP_PORT
        val instance: AndroidLocalBridgeServer by lazy { AndroidLocalBridgeServer() }
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientThreadPool = Executors.newFixedThreadPool(8)
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
        FlowAutomationManager.instance.init(context)

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

            // Verify device ID security header for all non-OPTIONS requests
            if (cachedDeviceId.length <= 5) {
                Log.e(TAG, "Bridge server security error: cachedDeviceId is not initialized or invalid")
                sendResponse(output, 500, "application/json", "{\"error\": \"Server security error: Device ID not initialized or invalid\"}")
                return
            }

            val clientDeviceId = headers["x-device-id"]
            if (clientDeviceId.isNullOrBlank() || clientDeviceId != cachedDeviceId) {
                Log.w(TAG, "Blocked unauthorized request to $path (missing or invalid X-Device-Id header)")
                sendResponse(output, 401, "application/json", "{\"error\": \"Unauthorized: Invalid or missing X-Device-Id header\"}")
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
                "Access-Control-Allow-Headers: Content-Type, X-Device-Id\r\n" +
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

    private fun getMcpToolsList(): JSONArray {
        val tools = JSONArray()
        val browserEnabled = isBrowserAutomationEnabled()
        val terminalEnabled = isTerminalAutomationEnabled()
        val flowEnabled = isFlowAutomationEnabled()

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
            val flowAutoNote = " This tool opens, navigates, waits for and reloads the Flow tab by itself: never use browser_* tools to open, navigate, refresh or click Flow, and never ask the user to open a project page. The user only needs to be signed in to Flow in the AntiGem browser; if a tool says they are not, tell them that."
            fun flowTool(name: String, description: String, properties: JSONObject = JSONObject(), required: List<String> = emptyList()) =
                tools.put(JSONObject().apply {
                    put("name", name)
                    put("description", description + flowAutoNote)
                    put("inputSchema", JSONObject().apply {
                        put("type", "object")
                        put("properties", properties)
                        if (required.isNotEmpty()) put("required", JSONArray(required))
                    })
                })
            fun prop(type: String, description: String) = JSONObject().apply { put("type", type); put("description", description) }

            fun enumProp(values: List<String>, description: String) = JSONObject().apply {
                put("type", "string"); put("enum", JSONArray(values)); put("description", description)
            }
            fun stringArrayProp(description: String) = JSONObject().apply {
                put("type", "array"); put("items", JSONObject().apply { put("type", "string") }); put("description", description)
            }

            // 16-28. Google Flow (flow.google.com) via the user's signed-in session in the in-app browser
            flowTool(
                "flow_status",
                "Check Google Flow: opens the Flow tab if needed and reports logged_in (with the account and remaining credits), the open project, current composer settings and the job queue. If logged_in is false, tell the user to sign in to Flow in the AntiGem browser and stop."
            )
            flowTool("flow_list_models", "List the image and video model families Google Flow currently offers.")
            flowTool(
                "flow_list_projects",
                "List the user's Google Flow projects (projectId, title, created).",
                JSONObject().apply { put("limit", prop("integer", "Max projects to return (default 20)")) }
            )
            flowTool(
                "flow_create_project",
                "Create a new Google Flow project (direct, no generation). Opens it in the Flow tab by default, so the next flow_generate_image/flow_generate_video goes into it.",
                JSONObject().apply {
                    put("title", prop("string", "Project title (default: date/time like Flow's UI)"))
                    put("open", prop("boolean", "Open the new project in the Flow tab (default true)"))
                }
            )
            flowTool(
                "flow_rename_project",
                "Rename a Google Flow project.",
                JSONObject().apply {
                    put("project_id", prop("string", "Project ID"))
                    put("title", prop("string", "New title"))
                },
                listOf("project_id", "title")
            )
            flowTool(
                "flow_open_project",
                "Open a Google Flow project in the Flow tab. Generation always goes into the project open in the tab.",
                JSONObject().apply { put("project_id", prop("string", "Project ID")) },
                listOf("project_id")
            )
            flowTool("flow_get_settings", "Read Flow's current composer settings (mode, model, aspect, count) from its settings chip, without changing anything.")
            flowTool(
                "flow_list_media",
                "List images and videos in a Flow project (mediaId, type, title, prompt, size, pending). Optional query filters by title/prompt (like Flow's search box); pending_only lists videos still generating. cached_path is set when the file is already saved locally; use flow_get_media to fetch one.",
                JSONObject().apply {
                    put("project_id", prop("string", "Project ID (default: the project open in the Flow tab)"))
                    put("query", prop("string", "Optional case-insensitive text to search in titles and prompts"))
                    put("pending_only", prop("boolean", "Only media still generating (default false)"))
                    put("type", enumProp(listOf("image", "video"), "Only images or only videos"))
                    put("include_trashed", prop("boolean", "Also list media in Flow's trash (default false)"))
                }
            )
            flowTool(
                "flow_get_media",
                "Get a Flow image or video as a local file (saved once in ~/flow-media; later calls reuse the same file, never copy it). Set view=true to also receive an image inline so you can look at it. To show it in chat, write ![caption](<path>) on its own line.",
                JSONObject().apply {
                    put("media_id", prop("string", "media_id from flow_list_media or flow_generate_image/flow_generate_video"))
                    put("view", prop("boolean", "Also return the image inline for viewing (images only, default false)"))
                },
                listOf("media_id")
            )
            flowTool(
                "flow_upload_media",
                "Upload local image/video files into a Flow project (through Flow's own upload, so they appear in the project). Returns their new media ids, usable as references or frames. Not needed before flow_generate_*: those accept local paths directly.",
                JSONObject().apply {
                    put("file_paths", stringArrayProp("Absolute local file paths (.png .jpg .jpeg .webp .gif .heic .mp4 .mov ...)"))
                    put("project_id", prop("string", "Project to upload into (default: the open project, or a new one)"))
                },
                listOf("file_paths")
            )
            flowTool(
                "flow_generate_image",
                "Generate IMAGES (not videos) on Google Flow with the user's own account. For videos use flow_generate_video. For several different prompts, pass them all at once in items (each with its own prompt and optional settings): they are sent to Flow one right after another and generate in parallel — do NOT call this tool once per prompt and wait in between. All settings are applied explicitly (defaults shown). Takes 15-30 s; images cost 0 credits on most plans. Waits up to wait_seconds; if not finished, returns job_id — call flow_get_job to keep waiting. Results are saved once in ~/flow-media; to show one in chat write ![caption](<path>) on its own line.",
                JSONObject().apply {
                    put("prompt", prop("string", "What to generate (single prompt; use items for several)"))
                    put("items", JSONObject().apply {
                        put("type", "array")
                        put("description", "Several different images to generate in parallel. Each item: {prompt, and optionally model, aspect_ratio, count, references}; missing settings use the top-level values")
                        put("items", JSONObject().apply { put("type", "object") })
                    })
                    put("model", enumProp(FlowAutomationManager.IMAGE_MODELS, "Default ${FlowAutomationManager.DEFAULT_IMAGE_MODEL}"))
                    put("aspect_ratio", enumProp(FlowAutomationManager.IMAGE_ASPECTS, "Default 16:9"))
                    put("count", prop("integer", "Number of images 1-4 (default 1)"))
                    put("references", stringArrayProp("Optional reference images (variants, edits, same character): Flow media ids and/or local file paths (absolute, ~/..., or relative to the Termux home; uploaded automatically)"))
                    put("dry_run", prop("boolean", "Only build and check the request, then block it before sending (no credits); returns what would be sent"))
                    put("project_id", prop("string", "Project to generate in (default: the open project, or a new one)"))
                    put("new_project", prop("boolean", "Create a new project first (default false)"))
                    put("wait_seconds", prop("integer", "Seconds to wait for all results before returning the job ids (0-50, default 45)"))
                }
            )
            flowTool(
                "flow_generate_video",
                "Generate VIDEOS (not images) on Google Flow — from text, from a start (and optional end) frame, or from reference images with the user's own account. For images use flow_generate_image. For several different prompts, pass them all at once in items (each with its own prompt and optional settings): they are sent one right after another and generate in parallel — do NOT call this tool once per prompt and wait in between. max_credits is the total for the whole call. All settings are applied explicitly (defaults shown). Videos cost credits: Omni 1.1 Flash 360p = 4/5/6/7 and 720p = 7/10/12/15 for 4/6/8/10 s; Veo 3.1 Lite 10, Fast 20, Quality 100 (fixed 720p, 8 s); times count — set max_credits to cap it. Takes about 1-5 min: waits up to wait_seconds, then returns job_id and media_ids — call flow_get_job (or flow_video_status) to keep waiting. Results are saved once in ~/flow-media; to show one in chat write ![caption](<path>) on its own line.",
                JSONObject().apply {
                    put("prompt", prop("string", "What should happen in the video (single prompt; use items for several)"))
                    put("items", JSONObject().apply {
                        put("type", "array")
                        put("description", "Several different videos to generate in parallel. Each item: {prompt, and optionally model, aspect_ratio, resolution, duration, count, start_frame, end_frame, references}; missing settings use the top-level values")
                        put("items", JSONObject().apply { put("type", "object") })
                    })
                    put("model", enumProp(FlowAutomationManager.VIDEO_MODELS, "Default ${FlowAutomationManager.DEFAULT_VIDEO_MODEL}"))
                    put("aspect_ratio", enumProp(FlowAutomationManager.VIDEO_ASPECTS, "Default 16:9"))
                    put("resolution", enumProp(listOf("360p", "720p"), "Omni 1.1 Flash only (default 720p)"))
                    put("duration", JSONObject().apply {
                        put("type", "integer"); put("enum", JSONArray(listOf(4, 6, 8, 10))); put("description", "Seconds, Omni 1.1 Flash only (default 8)")
                    })
                    put("count", prop("integer", "Number of videos 1-4 (default 1)"))
                    put("start_frame", prop("string", "Optional first frame (Frames mode): a Flow image media id or a local image path (uploaded automatically)"))
                    put("dry_run", prop("boolean", "Only build and check the request, then block it before sending (no credits); returns what would be sent"))
                    put("end_frame", prop("string", "Optional last frame; needs start_frame"))
                    put("references", stringArrayProp("Optional reference images (Ingredients mode, max 3 recommended): Flow media ids and/or local file paths. Cannot be combined with start_frame/end_frame. Not supported by Veo 3.1 - Quality"))
                    put("max_credits", prop("integer", "Total credit limit for the whole call; prompts that would go over it are skipped before generating"))
                    put("project_id", prop("string", "Project to generate in (default: the open project, or a new one)"))
                    put("new_project", prop("boolean", "Create a new project first (default false)"))
                    put("wait_seconds", prop("integer", "Seconds to wait for all results before returning the job ids (0-50, default 45)"))
                }
            )
            flowTool(
                "flow_get_job",
                "Get the status of one or more Flow generation jobs, waiting up to wait_seconds for them to finish. Returns local file paths when done.",
                JSONObject().apply {
                    put("job_id", prop("string", "Job ID returned by flow_generate_image or flow_generate_video"))
                    put("job_ids", stringArrayProp("Several job IDs at once"))
                    put("wait_seconds", prop("integer", "Seconds to wait (0-50, default 45)"))
                }
            )
            flowTool(
                "flow_video_status",
                "Check Flow video generation status by media id (done / pending / failed). Finished videos are downloaded once to ~/flow-media and their paths returned.",
                JSONObject().apply {
                    put("media_ids", JSONObject().apply {
                        put("type", "array"); put("items", JSONObject().apply { put("type", "string") }); put("description", "Video media ids")
                    })
                    put("download", prop("boolean", "Download finished videos (default true)"))
                },
                listOf("media_ids")
            )
            flowTool(
                "flow_get_last_media",
                "Get the newest images or videos (\"the last image\", \"the previous 2 videos\"): first the results of this session's tools, then the newest items of the project. Use the returned media_id with other flow_* tools.",
                JSONObject().apply {
                    put("type", enumProp(listOf("image", "video"), "Only images or only videos"))
                    put("count", prop("integer", "How many (default 1)"))
                    put("project_id", prop("string", "Project to look in (default: the open project)"))
                }
            )
            flowTool(
                "flow_rename_media",
                "Rename an image or video in Flow.",
                JSONObject().apply {
                    put("media_id", prop("string", "Media id"))
                    put("title", prop("string", "New title"))
                },
                listOf("media_id", "title")
            )
            flowTool(
                "flow_trash_media",
                "Move images/videos to Flow's trash (reversible with flow_restore_media). Always confirm with the user before calling this.",
                JSONObject().apply { put("media_ids", stringArrayProp("Media ids to trash")) },
                listOf("media_ids")
            )
            flowTool(
                "flow_restore_media",
                "Restore images/videos from Flow's trash.",
                JSONObject().apply { put("media_ids", stringArrayProp("Media ids to restore")) },
                listOf("media_ids")
            )
            flowTool(
                "flow_combine_videos",
                "Join, trim and reorder Flow videos into one MP4 with Flow's own export (no credits). Clips play in the given order and may repeat; mixed aspects use the first clip's size. Saved in ~/flow-media; show it in chat with ![caption](<path>).",
                JSONObject().apply {
                    put("clips", JSONObject().apply {
                        put("type", "array")
                        put("description", "Clips in order: {media_id, start (seconds, optional), end (seconds, optional = to the end)}")
                        put("items", JSONObject().apply { put("type", "object") })
                    })
                    put("upload_back", prop("boolean", "Also upload the result into the open Flow project (default false)"))
                },
                listOf("clips")
            )
            flowTool(
                "flow_video_to_gif",
                "Export a Flow video as an animated GIF (270p, no credits), saved in ~/flow-media.",
                JSONObject().apply { put("media_id", prop("string", "Video media id")) },
                listOf("media_id")
            )
            flowTool(
                "flow_extract_frame",
                "Save one frame of a Flow video as a PNG (done on the phone, no credits), e.g. the last frame to continue a video with start_frame. Optionally uploads it into Flow and returns its media_id.",
                JSONObject().apply {
                    put("media_id", prop("string", "Video media id"))
                    put("at_seconds", prop("number", "Time of the frame (default: the last frame)"))
                    put("upload", prop("boolean", "Also upload the frame into the open Flow project (default false)"))
                },
                listOf("media_id")
            )
            flowTool(
                "flow_create_scene",
                "Create a Flow scene (timeline saved in Flow) from videos in the given order. For just a combined file use flow_combine_videos.",
                JSONObject().apply {
                    put("media_ids", stringArrayProp("Video media ids in order (same project)"))
                    put("aspect_ratio", enumProp(FlowAutomationManager.VIDEO_ASPECTS, "Default 16:9"))
                },
                listOf("media_ids")
            )
            flowTool(
                "flow_edit_video",
                "Edit a whole Flow video with a text instruction (Omni edit; costs credits like an Omni video of the clip length). Returns a job; the edited video is a new media item.",
                JSONObject().apply {
                    put("media_id", prop("string", "Video media id"))
                    put("prompt", prop("string", "How to change the video"))
                    put("dry_run", prop("boolean", "Only check the request and block it before sending (no credits)"))
                    put("wait_seconds", prop("integer", "Seconds to wait before returning the job_id (0-50, default 45)"))
                },
                listOf("media_id", "prompt")
            )
            flowTool(
                "flow_edit_image",
                "Edit one image into a NEW VERSION of the same tile (iterations: \"make the jacket red\", \"add a ladybug\"). Works on any version of the tile (pass that version's media_id). For a separate new image based on it, use flow_generate_image with references instead. Returns a job; the result is a new media_id in the same tile.",
                JSONObject().apply {
                    put("media_id", prop("string", "The image (version) to edit"))
                    put("prompt", prop("string", "What to change"))
                    put("model", enumProp(FlowAutomationManager.IMAGE_MODELS, "Optional; default keeps Flow's current model"))
                    put("aspect_ratio", enumProp(FlowAutomationManager.IMAGE_ASPECTS, "Optional; default keeps the current aspect"))
                    put("references", stringArrayProp("Optional extra reference images (media ids in the same project, or local file paths)"))
                    put("dry_run", prop("boolean", "Only check the request and block it before sending"))
                    put("wait_seconds", prop("integer", "Seconds to wait before returning the job_id (0-50, default 45)"))
                },
                listOf("media_id", "prompt")
            )
            flowTool(
                "flow_extend_video",
                "Continue a Veo video seamlessly with a new 8 s clip (\"what happens next\"); costs about one Veo video of that tier. Only for Veo clips — for Omni clips use flow_extract_frame (upload: true) then flow_generate_video with start_frame. Returns a job.",
                JSONObject().apply {
                    put("media_id", prop("string", "Veo video media id"))
                    put("prompt", prop("string", "What happens next"))
                    put("dry_run", prop("boolean", "Only check the request and block it before sending (no credits)"))
                    put("wait_seconds", prop("integer", "Seconds to wait before returning the job_id (0-50, default 45)"))
                },
                listOf("media_id", "prompt")
            )
            flowTool(
                "flow_upscale_video",
                "Upscale a Flow video (360p Omni → 720p; 720p videos may offer 1080p / 4K). The upscaled copy is a new media item saved in ~/flow-media. Returns a job.",
                JSONObject().apply {
                    put("media_id", prop("string", "Video media id"))
                    put("resolution", enumProp(listOf("720p", "1080p", "4K"), "Default 720p"))
                    put("wait_seconds", prop("integer", "Seconds to wait before returning the job_id (0-50, default 45)"))
                },
                listOf("media_id")
            )
            flowTool(
                "flow_upscale_image",
                "Upscale a Flow image to 2K or 4K (4K may only be offered for Nano Banana Pro images). Takes about 40 s; the upscaled copy is a new media item saved in ~/flow-media.",
                JSONObject().apply {
                    put("media_id", prop("string", "Image media id"))
                    put("resolution", enumProp(listOf("2K", "4K"), "Default 2K"))
                    put("wait_seconds", prop("integer", "Seconds to wait before returning the job_id (0-50, default 45)"))
                },
                listOf("media_id")
            )
            flowTool(
                "flow_cancel",
                "Stop waiting for a queued/running Flow job (or all when job_id is omitted). A generation Flow already accepted keeps running in Flow.",
                JSONObject().apply { put("job_id", prop("string", "Optional job ID")) }
            )
            flowTool(
                "flow_lock_tab",
                "Lock or unlock the Flow tab for the user. When locked, the user sees an overlay on the Flow tab and cannot interact with it unless they press Unlock, which only lasts until they leave the tab (it locks again when they come back). The lock is kept across app restarts until you unlock it. While any flow_* tool is working, the tab is blocked anyway.",
                JSONObject().apply { put("locked", prop("boolean", "true to lock, false to remove the lock")) },
                listOf("locked")
            )
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

    private fun flowText(text: String): JSONObject = JSONObject().apply {
        put("type", "text")
        put("text", text)
    }

    private fun flowJobsText(jobs: List<FlowJob>): String {
        val body = if (jobs.size == 1) jobs[0].toJsonObject().toString(2) else JSONArray().apply { jobs.forEach { put(it.toJsonObject()) } }.toString(2)
        val hints = mutableListOf<String>()
        if (jobs.any { it.status == "done" }) {
            hints += "To show a result in chat, write ![caption](<path>) on its own line (images and videos). Reuse these paths; the files are stored once in ~/flow-media."
        }
        val pending = jobs.filter { it.status == "queued" || it.status == "running" }.map { it.id }
        if (pending.isNotEmpty()) {
            hints += "Still working on ${pending.size} job(s). Call flow_get_job with job_ids ${JSONArray(pending)} to keep waiting."
        }
        return body + hints.joinToString("") { "\n\n$it" }
    }

    /** Validates one prompt of a flow_generate_* call; [item] values override the call-level [defaults]. */
    private fun buildFlowRequest(
        item: JSONObject,
        defaults: JSONObject,
        isVideo: Boolean,
        budget: FlowCreditBudget?,
        projectId: String?
    ): Result<FlowRequest> = runCatching {
        fun src(key: String) = if (item.has(key)) item else defaults
        fun str(key: String) = src(key).optString(key).trim().ifBlank { null }

        val prompt = item.optString("prompt").trim().ifBlank { null } ?: throw Exception("prompt is required")
        val models = if (isVideo) FlowAutomationManager.VIDEO_MODELS else FlowAutomationManager.IMAGE_MODELS
        val model = str("model")?.let { m ->
            models.find { it.equals(m, ignoreCase = true) } ?: throw Exception(
                "'$m' is not a ${if (isVideo) "video" else "image"} model. Use one of: ${models.joinToString()}" +
                    if (isVideo) " (for images use flow_generate_image)" else " (for videos use flow_generate_video)"
            )
        } ?: if (isVideo) FlowAutomationManager.DEFAULT_VIDEO_MODEL else FlowAutomationManager.DEFAULT_IMAGE_MODEL
        val aspects = if (isVideo) FlowAutomationManager.VIDEO_ASPECTS else FlowAutomationManager.IMAGE_ASPECTS
        val aspect = str("aspect_ratio") ?: "16:9"
        if (aspect !in aspects) throw Exception("aspect_ratio must be one of ${aspects.joinToString()} for ${if (isVideo) "videos" else "images"}")
        val omni = isVideo && FlowAutomationManager.isOmni(model)
        if (isVideo && !omni && (str("duration") != null || str("resolution") != null)) {
            throw Exception("$model has a fixed 8 s length and 720p; remove duration/resolution or use Omni 1.1 Flash")
        }
        val duration = if (omni) str("duration")?.filter { it.isDigit() }?.toIntOrNull() ?: 8 else null
        if (duration != null && duration !in listOf(4, 6, 8, 10)) throw Exception("duration must be 4, 6, 8 or 10 seconds")
        val resolution = if (omni) (str("resolution") ?: "720p").lowercase() else null
        if (resolution != null && resolution !in listOf("360p", "720p")) throw Exception("resolution must be 360p or 720p")
        val refsArr = src("references").optJSONArray("references")
        val references = (0 until (refsArr?.length() ?: 0)).map { refsArr!!.optString(it).trim() }.filter { it.isNotBlank() }
            .map { it.removePrefix("file://") }
        val startFrame = if (isVideo) str("start_frame")?.removePrefix("file://") else null
        val endFrame = if (isVideo) str("end_frame")?.removePrefix("file://") else null
        if (endFrame != null && startFrame == null) throw Exception("end_frame needs start_frame")
        if (references.isNotEmpty() && startFrame != null) throw Exception("Use either start_frame/end_frame or references for a video, not both")
        if (isVideo && references.isNotEmpty() && model.contains("Quality")) {
            throw Exception("Veo 3.1 - Quality doesn't support reference images (Flow silently drops them). Use Veo 3.1 - Fast, Veo 3.1 - Lite or Omni 1.1 Flash, or start_frame instead")
        }
        val fManager = FlowAutomationManager.instance
        (references + listOfNotNull(startFrame, endFrame)).filter { fManager.isLocalPath(it) }
            .firstOrNull { fManager.resolveLocalFile(it) == null }
            ?.let { throw Exception("File not found or not readable: $it (give an absolute path, ~/path, or a path in the Termux home)") }
        val count = src("count").optInt("count", 1).coerceIn(1, 4)
        FlowRequest(
            prompt = prompt,
            isVideo = isVideo,
            model = model,
            aspectRatio = aspect,
            count = count,
            resolution = resolution,
            durationSec = duration,
            budget = budget,
            projectId = projectId,
            references = references,
            startFrame = startFrame,
            endFrame = endFrame,
            dryRun = src("dry_run").optBoolean("dry_run", false)
        )
    }

    private suspend fun executeMcpToolCall(toolName: String, args: JSONObject): JSONObject {
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

        val bManager = BrowserSessionManager.instance
        val tBridge = LocalTerminalBridge.instance
        val fManager = FlowAutomationManager.instance
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
                "flow_status" -> {
                    content.put(flowText(fManager.status().toString(2)))
                }
                "flow_list_models" -> {
                    val res = fManager.listModels()
                    if (res.isFailure) return flowError("Listing Flow models failed: ${res.exceptionOrNull()?.message}")
                    content.put(flowText(res.getOrNull().toString()))
                }
                "flow_list_projects" -> {
                    val res = fManager.listProjects(args.optInt("limit", 20).coerceIn(1, 100))
                    if (res.isFailure) return flowError("Listing Flow projects failed: ${res.exceptionOrNull()?.message}")
                    content.put(flowText((res.getOrNull() as? JSONArray)?.toString(2) ?: res.getOrNull().toString()))
                }
                "flow_create_project" -> {
                    val res = fManager.createProject(args.optString("title").ifBlank { null }, args.optBoolean("open", true))
                    if (res.isFailure) return flowError("Creating Flow project failed: ${res.exceptionOrNull()?.message}")
                    content.put(flowText(res.getOrNull()!!.toString(2)))
                }
                "flow_rename_project" -> {
                    val projectId = args.optString("project_id").ifBlank { return flowError("project_id is required") }
                    val title = args.optString("title").ifBlank { return flowError("title is required") }
                    val res = fManager.renameProject(projectId, title)
                    if (res.isFailure) return flowError("Renaming Flow project failed: ${res.exceptionOrNull()?.message}")
                    content.put(flowText("Renamed project $projectId to \"$title\""))
                }
                "flow_open_project" -> {
                    val projectId = args.optString("project_id").ifBlank { return flowError("project_id is required") }
                    val res = fManager.openProject(projectId)
                    if (res.isFailure) return flowError("Opening Flow project failed: ${res.exceptionOrNull()?.message}")
                    content.put(flowText("Opened Flow project $projectId"))
                }
                "flow_get_settings" -> {
                    val res = fManager.currentSettings()
                    if (res.isFailure) return flowError("Reading Flow settings failed: ${res.exceptionOrNull()?.message}")
                    content.put(flowText(res.getOrNull()!!))
                }
                "flow_list_media" -> {
                    val res = fManager.listMedia(
                        args.optString("project_id").ifBlank { null },
                        args.optString("query").ifBlank { null },
                        args.optBoolean("pending_only", false),
                        args.optString("type").ifBlank { null },
                        args.optBoolean("include_trashed", false)
                    )
                    if (res.isFailure) return flowError("Listing Flow media failed: ${res.exceptionOrNull()?.message}")
                    val items = res.getOrNull()!!
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        fManager.cachedFile(item.optString("mediaId"))?.let { item.put("cached_path", it.absolutePath) }
                    }
                    content.put(flowText(items.toString(2)))
                }
                "flow_get_media" -> {
                    val mediaId = args.optString("media_id", "")
                    val res = fManager.getMediaFile(mediaId)
                    if (res.isFailure) return flowError("Getting Flow media failed: ${res.exceptionOrNull()?.message}")
                    val file = res.getOrNull()!!
                    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: ""
                    if (args.optBoolean("view", false) && mime.startsWith("image/") && file.length() <= 5L * 1024 * 1024) {
                        content.put(JSONObject().apply {
                            put("type", "image")
                            put("data", android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP))
                            put("mimeType", mime)
                        })
                    }
                    content.put(flowText("${file.absolutePath}\n\nTo show it in chat, write ![caption](${file.absolutePath}) on its own line. Reuse this path; do not copy the file."))
                }
                "flow_generate_image", "flow_generate_video" -> {
                    val isVideo = toolName == "flow_generate_video"
                    // One prompt (prompt) or many different prompts (items); top-level settings are defaults for every item
                    val itemsArr = args.optJSONArray("items")
                    val items = if (itemsArr != null && itemsArr.length() > 0) {
                        (0 until itemsArr.length()).map { i ->
                            itemsArr.optJSONObject(i) ?: JSONObject().apply { put("prompt", itemsArr.optString(i)) }
                        }
                    } else {
                        listOf(args)
                    }
                    if (items.size > 20) return flowError("At most 20 items per call")
                    val budget = if (args.has("max_credits")) FlowCreditBudget(args.optInt("max_credits")) else null
                    var projectId = args.optString("project_id").ifBlank { null }
                    val requests = items.mapIndexed { i, item ->
                        buildFlowRequest(item, args, isVideo, budget, projectId).getOrElse {
                            return flowError(if (items.size > 1) "items[$i]: ${it.message}" else it.message ?: "Invalid request")
                        }
                    }
                    // A new project is created once, and every prompt of this call goes into it
                    if (args.optBoolean("new_project", false)) {
                        val created = fManager.createProject(null, open = true)
                        if (created.isFailure) return flowError("Creating Flow project failed: ${created.exceptionOrNull()?.message}")
                        projectId = created.getOrNull()!!.optString("projectId")
                    }
                    val jobs = requests.map { fManager.submit(if (projectId != null) it.copy(projectId = projectId) else it) }
                    val deadline = System.currentTimeMillis() + args.optInt("wait_seconds", 45).coerceIn(0, 50) * 1000L
                    for (job in jobs) fManager.awaitJob(job.id, (deadline - System.currentTimeMillis()).coerceAtLeast(0))
                    if (jobs.all { it.status == "failed" }) return flowError(flowJobsText(jobs))
                    content.put(flowText(flowJobsText(jobs)))
                }
                "flow_upload_media" -> {
                    val pathsArr = args.optJSONArray("file_paths") ?: return flowError("file_paths is required")
                    val paths = (0 until pathsArr.length()).map { pathsArr.optString(it).trim() }.filter { it.isNotBlank() }
                    if (paths.isEmpty()) return flowError("file_paths is required")
                    val res = fManager.uploadMedia(paths, args.optString("project_id").ifBlank { null })
                    if (res.isFailure) return flowError("Uploading to Flow failed: ${res.exceptionOrNull()?.message}")
                    content.put(flowText(JSONArray().apply {
                        paths.zip(res.getOrNull()!!).forEach { (path, id) -> put(JSONObject().apply { put("file", path); put("media_id", id) }) }
                    }.toString(2)))
                }
                "flow_video_status" -> {
                    val idsArr = args.optJSONArray("media_ids") ?: return flowError("media_ids is required")
                    val ids = (0 until idsArr.length()).map { idsArr.optString(it) }.filter { it.isNotBlank() }
                    if (ids.isEmpty()) return flowError("media_ids is required")
                    val res = fManager.videoStatus(ids, args.optBoolean("download", true))
                    if (res.isFailure) return flowError("Checking Flow videos failed: ${res.exceptionOrNull()?.message}")
                    content.put(flowText(res.getOrNull()!!.toString(2) + "\n\nTo show a finished video in chat, write ![caption](<path>) on its own line."))
                }
                "flow_get_job" -> {
                    val idsArr = args.optJSONArray("job_ids")
                    val ids = ((0 until (idsArr?.length() ?: 0)).map { idsArr!!.optString(it) } + args.optString("job_id"))
                        .map { it.trim() }.filter { it.isNotBlank() }.distinct()
                    if (ids.isEmpty()) return flowError("job_id or job_ids is required")
                    val deadline = System.currentTimeMillis() + args.optInt("wait_seconds", 45).coerceIn(0, 50) * 1000L
                    val jobs = ids.map { id ->
                        fManager.awaitJob(id, (deadline - System.currentTimeMillis()).coerceAtLeast(0)) ?: return flowError("Unknown job_id '$id'")
                    }
                    if (jobs.all { it.status == "failed" }) return flowError(flowJobsText(jobs))
                    content.put(flowText(flowJobsText(jobs)))
                }
                "flow_lock_tab" -> {
                    if (!args.has("locked")) return flowError("locked is required")
                    val locked = args.optBoolean("locked")
                    fManager.lockTab(locked)
                    content.put(flowText(if (locked) "Flow tab locked for the user (they can unlock it temporarily)." else "Flow tab unlocked."))
                }
                "flow_get_last_media" -> {
                    val res = fManager.lastMedia(
                        args.optString("type").ifBlank { null },
                        args.optInt("count", 1).coerceIn(1, 20),
                        args.optString("project_id").ifBlank { null }
                    )
                    if (res.isFailure) return flowError("Finding the last Flow media failed: ${res.exceptionOrNull()?.message}")
                    content.put(flowText(JSONArray(res.getOrNull()!!).toString(2)))
                }
                "flow_rename_media" -> {
                    val mediaId = args.optString("media_id").ifBlank { return flowError("media_id is required") }
                    val title = args.optString("title").ifBlank { return flowError("title is required") }
                    val res = fManager.renameMedia(mediaId, title)
                    if (res.isFailure) return flowError("Renaming Flow media failed: ${res.exceptionOrNull()?.message}")
                    content.put(flowText("Renamed $mediaId to \"$title\""))
                }
                "flow_trash_media", "flow_restore_media" -> {
                    val idsArr = args.optJSONArray("media_ids") ?: return flowError("media_ids is required")
                    val ids = (0 until idsArr.length()).map { idsArr.optString(it).trim() }.filter { it.isNotBlank() }
                    if (ids.isEmpty()) return flowError("media_ids is required")
                    val trash = toolName == "flow_trash_media"
                    val res = fManager.setTrashed(ids, trash)
                    if (res.isFailure) return flowError("${if (trash) "Trashing" else "Restoring"} Flow media failed: ${res.exceptionOrNull()?.message}")
                    content.put(flowText("${if (trash) "Moved to trash" else "Restored"}: ${ids.joinToString()}"))
                }
                "flow_combine_videos" -> {
                    val clipsArr = args.optJSONArray("clips") ?: return flowError("clips is required")
                    val clips = (0 until clipsArr.length()).map { i ->
                        val c = clipsArr.optJSONObject(i) ?: JSONObject().apply { put("media_id", clipsArr.optString(i)) }
                        val id = c.optString("media_id").ifBlank { return flowError("clips[$i]: media_id is required") }
                        FlowAutomationManager.FlowClip(
                            id,
                            if (c.has("start")) c.optDouble("start") else null,
                            if (c.has("end")) c.optDouble("end") else null
                        )
                    }
                    if (clips.isEmpty()) return flowError("clips is required")
                    val res = fManager.combineVideos(clips, args.optBoolean("upload_back", false))
                    if (res.isFailure) return flowError("Combining Flow videos failed: ${res.exceptionOrNull()?.message}")
                    content.put(flowText(res.getOrNull()!!.toString(2) + "\n\nShow it in chat with ![caption](<path>) on its own line."))
                }
                "flow_video_to_gif" -> {
                    val mediaId = args.optString("media_id").ifBlank { return flowError("media_id is required") }
                    val res = fManager.videoToGif(mediaId)
                    if (res.isFailure) return flowError("GIF export failed: ${res.exceptionOrNull()?.message}")
                    content.put(flowText("${res.getOrNull()!!.absolutePath}\n\nShow it in chat with ![caption](${res.getOrNull()!!.absolutePath}) on its own line."))
                }
                "flow_extract_frame" -> {
                    val mediaId = args.optString("media_id").ifBlank { return flowError("media_id is required") }
                    val res = fManager.extractFrame(mediaId, if (args.has("at_seconds")) args.optDouble("at_seconds") else null, args.optBoolean("upload", false))
                    if (res.isFailure) return flowError("Extracting the frame failed: ${res.exceptionOrNull()?.message}")
                    content.put(flowText(res.getOrNull()!!.toString(2)))
                }
                "flow_create_scene" -> {
                    val idsArr = args.optJSONArray("media_ids") ?: return flowError("media_ids is required")
                    val ids = (0 until idsArr.length()).map { idsArr.optString(it).trim() }.filter { it.isNotBlank() }
                    if (ids.isEmpty()) return flowError("media_ids is required")
                    val aspect = args.optString("aspect_ratio").ifBlank { "16:9" }
                    if (aspect !in FlowAutomationManager.VIDEO_ASPECTS) return flowError("aspect_ratio must be 16:9 or 9:16")
                    val res = fManager.createScene(ids, aspect)
                    if (res.isFailure) return flowError("Creating the Flow scene failed: ${res.exceptionOrNull()?.message}")
                    content.put(flowText(res.getOrNull()!!.toString(2)))
                }
                "flow_edit_video", "flow_upscale_image", "flow_edit_image", "flow_extend_video", "flow_upscale_video" -> {
                    val mediaId = args.optString("media_id").ifBlank { return flowError("media_id is required") }
                    val action = when (toolName) {
                        "flow_edit_image" -> FlowAction.EDIT_IMAGE
                        "flow_edit_video" -> FlowAction.EDIT_VIDEO
                        "flow_extend_video" -> FlowAction.EXTEND_VIDEO
                        "flow_upscale_video" -> FlowAction.UPSCALE_VIDEO
                        else -> FlowAction.UPSCALE_IMAGE
                    }
                    val prompt = args.optString("prompt").trim()
                    if (action in listOf(FlowAction.EDIT_IMAGE, FlowAction.EDIT_VIDEO, FlowAction.EXTEND_VIDEO) && prompt.isBlank()) {
                        return flowError("prompt is required")
                    }
                    val upscaleTo = when (action) {
                        FlowAction.UPSCALE_IMAGE -> args.optString("resolution").ifBlank { "2K" }.uppercase().also {
                            if (it !in listOf("2K", "4K")) return flowError("resolution must be 2K or 4K")
                        }
                        FlowAction.UPSCALE_VIDEO -> args.optString("resolution").ifBlank { "720p" }.let { if (it.equals("4k", true)) "4K" else it.lowercase() }.also {
                            if (it !in listOf("720p", "1080p", "4K")) return flowError("resolution must be 720p, 1080p or 4K")
                        }
                        else -> "2K"
                    }
                    var model = ""
                    var aspect = ""
                    var references = emptyList<String>()
                    if (action == FlowAction.EDIT_IMAGE) {
                        model = args.optString("model").trim().let { m ->
                            if (m.isBlank()) "" else FlowAutomationManager.IMAGE_MODELS.find { it.equals(m, true) }
                                ?: return flowError("'$m' is not an image model. Use one of: ${FlowAutomationManager.IMAGE_MODELS.joinToString()}")
                        }
                        aspect = args.optString("aspect_ratio").trim().also {
                            if (it.isNotBlank() && it !in FlowAutomationManager.IMAGE_ASPECTS) return flowError("aspect_ratio must be one of ${FlowAutomationManager.IMAGE_ASPECTS.joinToString()}")
                        }
                        val refsArr = args.optJSONArray("references")
                        references = (0 until (refsArr?.length() ?: 0)).map { refsArr!!.optString(it).trim() }.filter { it.isNotBlank() }
                        references.filter { fManager.isLocalPath(it) }.firstOrNull { fManager.resolveLocalFile(it) == null }
                            ?.let { return flowError("File not found or not readable: $it") }
                        if (references.any { fManager.isLocalPath(it) }) {
                            val uploaded = fManager.uploadMedia(references.filter { fManager.isLocalPath(it) }, null)
                            if (uploaded.isFailure) return flowError("Uploading the reference files failed: ${uploaded.exceptionOrNull()?.message}")
                            val ids = uploaded.getOrNull()!!.iterator()
                            references = references.map { if (fManager.isLocalPath(it)) ids.next() else it }
                        }
                    }
                    val job = fManager.submit(
                        FlowRequest(
                            prompt = prompt,
                            isVideo = action != FlowAction.EDIT_IMAGE && action != FlowAction.UPSCALE_IMAGE,
                            model = model,
                            aspectRatio = aspect,
                            count = 1,
                            references = references,
                            action = action,
                            targetMediaId = mediaId,
                            upscaleTo = upscaleTo,
                            dryRun = args.optBoolean("dry_run", false)
                        )
                    )
                    fManager.awaitJob(job.id, args.optInt("wait_seconds", 45).coerceIn(0, 50) * 1000L)
                    if (job.status == "failed") return flowError(flowJobsText(listOf(job)))
                    content.put(flowText(flowJobsText(listOf(job))))
                }
                "flow_cancel" -> {
                    val count = fManager.cancel(args.optString("job_id").ifBlank { null })
                    content.put(flowText("Cancelled $count Flow job(s)"))
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
