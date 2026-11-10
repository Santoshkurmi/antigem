package com.example.gemini.ui.browser.mcp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.example.gemini.ui.browser.BrowserSessionManager
import com.example.gemini.ui.browser.BrowserTabSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * browser_mcp: drives the in-app browser like a person, modelled on Claude in Chrome.
 * Served by AndroidLocalBridgeServer at /browser.
 *
 * - Page understanding: a helper injected into the page (assets/browser-mcp/page-agent.js) gives elements refs
 *   (ref_12) and powers read_page, find, form_input and page text.
 * - Input: real (trusted) mouse, keyboard and text input through the WebView's own DevTools socket ([BrowserCdp]);
 *   when DevTools is unavailable (e.g. Google sign-in pages) it falls back to Android touch/key events and page JS.
 * - Coordinates: screenshots are sized for model vision (long side <= 1568 px, ~1568 tokens) and remembered per tab,
 *   so coordinates the model reads off a screenshot are mapped back to the page exactly.
 * - Feedback: actions report navigation and new tabs; an overlay shows the agent's cursor and clicks to the user.
 */
object BrowserMcp {

    const val INSTRUCTIONS = "Controls the app's built-in browser (Android WebView tabs) like a person would. Look, then act: " +
        "open a page with browser_open_url, then get element refs (like ref_12) from browser_find (one element by description) or " +
        "browser_read_page (the page's structure), and act on refs with browser_computer (left_click, type, key, hover, scroll_to) " +
        "or browser_form_input (fill fields, pick options, tick boxes). Take a screenshot (browser_computer action=screenshot) when " +
        "you need to see the page; coordinates you pass are pixels in the latest screenshot of that tab. Read articles with " +
        "browser_get_page_text. Put steps you can predict into one browser_batch call (e.g. click a field, type, press Enter, " +
        "screenshot). Check results: actions report page changes and new tabs; read the page again or take a screenshot when " +
        "unsure. Refs stay valid until the page changes. Never click file inputs: use browser_upload_file with the ref. " +
        "For a task of several steps, call browser_lock once first (the user's touches, the keyboard and zoom stay out of your way on " +
        "every tab you use or open) and browser_lock with locked=false when you finish (it releases all of them). If the page changes in ways you did not cause, " +
        "the user may have unlocked it: check browser_list_tabs (lockedByAgent), read the page again and lock it again. " +
        "If a page opens an alert/confirm/prompt, " +
        "answer it with browser_handle_dialog. Pass action_summary (a few words) on actions so the user can follow what you do. " +
        "Omit tab_id to use the active tab; browser_list_tabs lists all tabs."

    private const val AGENT_VERSION = 10
    private const val PX_PER_TOKEN = 28
    private const val MAX_PX = 1568
    private const val MAX_TOKENS = 1568

    private val HIDDEN = setOf("browser_screenshot", "browser_inspect_dom", "browser_interact")

    /** Older tool names kept callable for existing prompts and scripts, but not listed. */
    fun isHiddenTool(name: String) = name in HIDDEN

    private class ToolError(message: String) : Exception(message)

    private class Out {
        val content = JSONArray()
        fun text(s: String) = apply { content.put(JSONObject().put("type", "text").put("text", s)) }
        fun image(base64: String) = apply { content.put(JSONObject().put("type", "image").put("data", base64).put("mimeType", "image/jpeg")) }
        fun json(error: Boolean = false): JSONObject = JSONObject().put("content", content).apply { if (error) put("isError", true) }
    }

    /** The latest screenshot of a tab: its size and the visible page area (CSS px) it shows. */
    private class Frame(val shotW: Int, val shotH: Int, val cssW: Double, val cssH: Double, val url: String)

    /** Coordinates cannot be used; [computer] answers with a fresh screenshot so the next try can succeed. */
    private class FrameError(message: String) : Exception(message)

    /** In a batch, coordinates refer to the screenshot taken before the batch; new frames apply after it. */
    private class Batch { val frames = HashMap<String, Frame>() }

    private val frames = ConcurrentHashMap<String, Frame>()
    private val tabLocks = ConcurrentHashMap<String, Mutex>()
    @Volatile private var agentSource: String? = null
    private val manager get() = BrowserSessionManager.instance

    // ======================= tool list =======================

    private fun prop(type: String, description: String, enum: List<String>? = null) = JSONObject().apply {
        put("type", type); put("description", description)
        enum?.let { put("enum", JSONArray(it)) }
    }

    private fun numberPair(description: String, size: Int = 2) = JSONObject().apply {
        put("type", "array"); put("description", description)
        put("items", JSONObject().put("type", "number")); put("minItems", size); put("maxItems", size)
    }

    private fun tool(name: String, description: String, required: List<String>, vararg props: Pair<String, JSONObject>) = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("inputSchema", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            if (required.isNotEmpty()) put("required", JSONArray(required))
        })
    }

    private val tabId = "tab_id" to prop("string", "Tab id from browser_list_tabs; omit for the active tab")
    private val summaryProp = "action_summary" to prop("string", "Optional: 2-5 words saying what you are doing, shown next to the cursor so the user can " +
        "follow along, e.g. \"Opening Filters menu\", \"Searching for shoes\". Never include passwords or other secrets.")

    fun tools(): JSONArray = JSONArray().apply {
        put(tool("browser_list_tabs", "List the in-app browser's tabs: id, URL, title, which is active, loading state and view mode.", emptyList()))
        put(tool("browser_open_url",
            "Open a URL in the in-app browser and wait for it to load (up to 10 s), or move in a tab's history. Opens in the active " +
                "tab unless new_tab=true or tab_id is given. Returns the tab id, final URL and title.",
            listOf("url"),
            "url" to prop("string", "URL (https://…, localhost:5173, or words to search), or \"back\", \"forward\" or \"reload\""),
            tabId,
            "new_tab" to prop("boolean", "Open in a new tab (default false)"),
            "mode" to prop("string", "View mode for the tab: mobile or desktop (wide desktop layout)", listOf("mobile", "desktop")),
            "desktop_mode" to prop("boolean", "Same as mode=desktop when true, mode=mobile when false"),
            summaryProp,
        ))
        put(tool("browser_switch_tab", "Show this tab in the browser (make it the active tab).", listOf("tab_id"), "tab_id" to prop("string", "Tab to activate")))
        put(tool("browser_close_tab", "Close a tab.", listOf("tab_id"), "tab_id" to prop("string", "Tab to close")))
        put(tool("browser_set_view_mode",
            "Switch a tab between mobile and desktop view (desktop user agent and wide layout). Reloads the page.",
            emptyList(), tabId,
            "mode" to prop("string", "mobile or desktop; omit both to toggle", listOf("mobile", "desktop")),
            "desktop_mode" to prop("boolean", "true for desktop, false for mobile"),
        ))
        put(tool("browser_read_page",
            "Read the page as an indented tree of its elements: role, name, state and value, each with a ref (e.g. ref_12) that " +
                "browser_computer, browser_form_input and browser_upload_file accept. Short text blocks appear as one line. " +
                "filter=\"interactive\" lists only what you can click or type into (much smaller). Controls outside the visible " +
                "area are marked (off-screen); acting on a ref scrolls to it. Refs stay the same for an element until the page changes. " +
                "Output is cut at max_chars; use ref_id to read one part of a big page.",
            emptyList(), tabId,
            "filter" to prop("string", "all (default) or interactive", listOf("all", "interactive")),
            "depth" to prop("integer", "Maximum tree depth (default 25)"),
            "ref_id" to prop("string", "Read only this element and what is inside it"),
            "max_chars" to prop("integer", "Maximum characters returned (default 50000)"),
        ))
        put(tool("browser_find",
            "Find elements by what they are or what they say, e.g. \"search box\", \"Add to cart button\", \"price of the blue shirt\", " +
                "\"email field\". Returns up to 20 matches, best first, each with a ref to act on. Cheaper than reading the whole page.",
            listOf("query"), tabId,
            "query" to prop("string", "What to look for, in plain words"),
        ))
        put(tool("browser_get_page_text",
            "Read the page's main text as plain text (the article or main content when the page has one): articles, docs, " +
                "search results, messages. Has no refs; use browser_find or browser_read_page to act on things. Says when part of the " +
                "text is hidden (collapsed sections, tabs); include_hidden=true reads that too.",
            emptyList(), tabId,
            "max_chars" to prop("integer", "Maximum characters returned (default 50000)"),
            "include_hidden" to prop("boolean", "Also read hidden text: collapsed sections, inactive tabs, closed menus (default false)"),
        ))
        put(tool("browser_computer",
            "Use the mouse and keyboard on a tab, and take screenshots. Input is real (trusted) input, like a person's. Target " +
                "elements by ref (from browser_find / browser_read_page; preferred, it scrolls the element into view) or by coordinate: " +
                "[x, y] pixels in the latest screenshot of this tab (take one first). Click the centre of elements. Each action reports " +
                "what it hit and whether the page navigated or opened a tab.\n" +
                "Actions:\n" +
                "* screenshot: see the visible part of the page (sets the coordinate frame for this tab)\n" +
                "* left_click / double_click / triple_click / right_click: click ref or coordinate (modifiers like \"ctrl\" or \"shift\" optional)\n" +
                "* hover: move the mouse over ref or coordinate (menus, tooltips)\n" +
                "* left_click_drag: drag from start_coordinate to coordinate\n" +
                "* type: type text into the focused text field (click it first; nothing is typed and you get an error if no text field has focus); \\n presses Enter\n" +
                "* key: press keys, space-separated: \"Enter\", \"Tab\", \"Escape\", \"Backspace\", \"ArrowDown\", \"ctrl+a\", \"shift+Tab\"; repeat for many presses\n" +
                "* scroll: scroll_direction up/down/left/right by scroll_amount wheel ticks (default 3) at coordinate (default: page centre); returns a screenshot\n" +
                "* scroll_to: scroll ref into view\n" +
                "* zoom: closer look at region [x0, y0, x1, y1] of the latest screenshot (coordinates stay in the full screenshot's frame)\n" +
                "* wait: wait duration seconds (max 10) for the page to change",
            listOf("action"), tabId,
            "action" to prop("string", "What to do", listOf("screenshot", "left_click", "right_click", "double_click", "triple_click", "hover",
                "left_click_drag", "type", "key", "scroll", "scroll_to", "zoom", "wait")),
            "ref" to prop("string", "Element ref, e.g. ref_12 (clicks, hover, scroll_to)"),
            "coordinate" to numberPair("[x, y] in pixels of the latest screenshot (clicks, hover, scroll; drag end point)"),
            "start_coordinate" to numberPair("[x, y] where left_click_drag starts"),
            "text" to prop("string", "Text to type (type), or keys to press (key)"),
            "modifiers" to prop("string", "Keys held during a click: ctrl, shift, alt, meta; combine with + (e.g. \"ctrl+shift\")"),
            "repeat" to prop("integer", "key: press the sequence this many times (1-100, default 1)"),
            "scroll_direction" to prop("string", "scroll direction", listOf("up", "down", "left", "right")),
            "scroll_amount" to prop("integer", "scroll: wheel ticks, 1-10 (default 3)"),
            "region" to numberPair("zoom: [x0, y0, x1, y1] rectangle in pixels of the latest screenshot", 4),
            "duration" to prop("number", "wait: seconds, up to 10"),
            "scale" to prop("number", "screenshot/zoom: 0.1-1 to get a smaller image (fewer tokens); coordinates still use the full-size frame"),
            summaryProp,
        ))
        put(tool("browser_form_input",
            "Set a form field by ref, the way frameworks like React expect: text, number, date and time fields and textareas " +
                "(value replaces the content), <select> dropdowns (option text or value; a list for multi-selects), checkboxes and " +
                "switches (true/false; never clicks twice), radio buttons (true), and contenteditable editors. For custom dropdowns, " +
                "click them with browser_computer instead.",
            listOf("ref", "value"), tabId,
            "ref" to prop("string", "Field ref from browser_find / browser_read_page"),
            "value" to JSONObject().put("description", "New value: string, number, boolean, or a list of options for a multi-select")
                .put("type", JSONArray(listOf("string", "number", "boolean", "array"))),
            summaryProp,
        ))
        put(tool("browser_eval_js",
            "Run JavaScript in the page and get the value of the last expression (top-level await works; values come back as JSON). " +
                "For reading data or debugging; prefer browser_computer and browser_form_input for acting, which behave like a real user.",
            listOf("script"), tabId,
            "script" to prop("string", "JavaScript to run, e.g. document.querySelectorAll('li').length or await fetch('/api').then(r => r.json())"),
        ))
        put(tool("browser_get_console_logs",
            "Read the tab's console messages (console.log/warn/error and uncaught errors), oldest first. Use pattern to filter.",
            emptyList(), tabId,
            "pattern" to prop("string", "Regular expression (case-insensitive) the message must match"),
            "only_errors" to prop("boolean", "Only errors"),
            "level" to prop("string", "Only this level", listOf("ALL", "ERROR", "WARN", "LOG", "INFO", "DEBUG")),
            "limit" to prop("integer", "Return the newest N messages, listed oldest first (default 100, max 300)"),
            "clear" to prop("boolean", "Clear the messages after reading, so the next read shows only new ones"),
        ))
        put(tool("browser_read_network",
            "List the tab's network requests (documents, XHR/fetch, scripts, images…) with method, status, type, size and time. " +
                "Recording starts the first time the browser MCP uses the tab, so reload the page to see its initial requests.",
            emptyList(), tabId,
            "url_pattern" to prop("string", "Only URLs containing this text (e.g. /api/)"),
            "only_failed" to prop("boolean", "Only failed requests and HTTP errors (status >= 400)"),
            "limit" to prop("integer", "Return the newest N requests, listed oldest first (default 100, max 500)"),
            "clear" to prop("boolean", "Clear the list after reading"),
        ))
        put(tool("browser_upload_file",
            "Upload local files to the page like a person picking them in a file dialog: give the ref of the upload button, its label " +
                "or the file input (hidden inputs work), or omit it when the page has a single file input. The site's own file chooser " +
                "receives the files; no picker is shown. For drag-and-drop-only areas it falls back to dropping the files (up to 25 MiB). " +
                "Check the page afterwards to confirm the site accepted them.",
            listOf("file_paths"), tabId,
            "file_paths" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                .put("description", "1-20 local files: absolute, ~/... or relative to the terminal home; inside the terminal home or /sdcard"),
            "ref" to prop("string", "Ref of the upload button, its label or the file input"),
            "selector" to prop("string", "Instead of ref: CSS selector of the upload element"),
            "method" to prop("string", "auto (default): file chooser, else in-page fallback; chooser: only the real file chooser; drop: only build the files in the page", listOf("auto", "chooser", "drop")),
            "timeout_seconds" to prop("integer", "How long to wait for the page to open its file chooser (1-30, default 10)"),
            summaryProp,
        ))
        put(tool("browser_lock",
            "Take or give back control of the browser for your task. locked=true (default) turns on the agent lock: the tab (tab_id, " +
                "or the active tab) is locked now, and so is every tab you use or open while it is on (new tabs, popups from clicks). " +
                "On locked tabs the user's touches are blocked and the keyboard and zoom stay put, so pages behave consistently; the user " +
                "sees an Unlock button and can take over a tab at any time. There is no timeout. locked=false without tab_id turns the " +
                "lock off and releases every locked tab: do this when your task is done. locked=false with tab_id releases only that tab. " +
                "Recommended for any task of several steps. (Every browser call already keeps the keyboard away for a few seconds.)",
            emptyList(), "tab_id" to prop("string", "Lock: the tab to lock now (default: the active tab). Unlock: release only this tab (default: all)"),
            "locked" to prop("boolean", "true to lock (default), false to unlock"),
            summaryProp,
        ))
        put(tool("browser_handle_dialog",
            "Answer the JavaScript alert, confirm or prompt (or \"Leave this page?\") a page opened. While one is open every page is " +
                "paused, and other tools say so. accept=true presses OK (or Leave), accept=false presses Cancel (or Stay). browser_list_tabs " +
                "shows open dialogs.",
            listOf("accept"), "tab_id" to prop("string", "Tab with the dialog; omit for the one that has it"),
            "accept" to prop("boolean", "true for OK / Leave, false for Cancel / Stay"),
            "prompt_text" to prop("string", "For a prompt: the text to answer with (default: the page's suggested text)"),
        ))
        put(tool("browser_batch",
            "Run several browser tool calls in one round trip, in order, stopping at the first error. Use it whenever you can predict " +
                "two or more steps, e.g. click a field, type, press Enter, then screenshot. Each item is {\"name\": tool name, \"input\": " +
                "that tool's arguments}. Coordinates inside a batch refer to the screenshot taken before the batch. Results and images " +
                "come back in order. Batches cannot be nested.",
            listOf("actions"),
            "actions" to JSONObject().put("type", "array").put("minItems", 1).put("description", "Tool calls to run in order")
                .put("items", JSONObject().put("type", "object")
                    .put("properties", JSONObject()
                        .put("name", prop("string", "Tool name, e.g. browser_computer"))
                        .put("input", JSONObject().put("type", "object").put("description", "The tool's arguments")))
                    .put("required", JSONArray(listOf("name", "input")))),
        ))
    }

    // ======================= dispatch =======================

    suspend fun call(context: Context, name: String, args: JSONObject): JSONObject = run(context, name, args, null)

    /** Tools that work in one tab's page: the tab is locked (keyboard, zoom) for the call. */
    private val pageTools = setOf("browser_read_page", "browser_find", "browser_get_page_text", "browser_computer", "browser_form_input",
        "browser_eval_js", "browser_upload_file")

    /** Tools that still work while a page dialog has the browser paused. */
    private val dialogSafe = setOf("browser_list_tabs", "browser_handle_dialog", "browser_close_tab", "browser_switch_tab",
        "browser_get_console_logs", "browser_batch")

    private suspend fun run(context: Context, name: String, args: JSONObject, batch: Batch?): JSONObject {
        val out = Out()
        var lockedTab: BrowserTabSession? = null
        return try {
            if (name !in dialogSafe) dialogGuard()
            val pageTab = if (name in pageTools) tab(context, args).also {
                BrowserAgentLock.begin(it)
                lockedTab = it
                if (BrowserAgentLock.takeUserUnlocked(it.id)) out.text("Note: the user unlocked this tab and may have used it, so earlier refs and screenshots can be out of date: " +
                    "read the page again before relying on them. If your task is not finished, lock it again with browser_lock.")
            } else null
            when (name) {
                "browser_list_tabs" -> out.text(withContext(Dispatchers.Main) { manager.listTabsJson() }.toString(2))
                "browser_open_url" -> openUrl(context, args, out)
                "browser_switch_tab" -> {
                    val id = args.optString("tab_id")
                    if (!withContext(Dispatchers.Main) { manager.switchTab(id) }) throw ToolError(noTab(id))
                    out.text("Switched to tab $id")
                }
                "browser_close_tab" -> {
                    val id = args.optString("tab_id")
                    if (!withContext(Dispatchers.Main) { manager.closeTab(id) }) throw ToolError(noTab(id))
                    frames.remove(id); tabLocks.remove(id)
                    out.text("Closed tab $id")
                }
                "browser_set_view_mode" -> setViewMode(context, args, out)
                "browser_lock" -> lockTool(context, args, out)
                "browser_handle_dialog" -> handleDialog(args, out)
                "browser_read_page" -> {
                    val tab = pageTab!!
                    val opts = JSONObject().put("filter", args.optString("filter", "all"))
                    if (args.has("depth")) opts.put("depth", args.optInt("depth"))
                    args.optString("ref_id").takeIf { it.isNotBlank() }?.let { opts.put("refId", it) }
                    if (args.has("max_chars")) opts.put("maxChars", args.optInt("max_chars"))
                    BrowserAgentIndicator.activity(tab.id, "Reading the page")
                    out.text(agentText(context, tab, "tree", opts))
                }
                "browser_find" -> {
                    val query = args.optString("query").trim()
                    if (query.isEmpty()) throw ToolError("query is empty: describe the element, e.g. \"search box\"")
                    val tab = pageTab!!
                    BrowserAgentIndicator.activity(tab.id, "Looking for \"${clip(query, 24)}\"")
                    out.text(agentText(context, tab, "find", query))
                }
                "browser_get_page_text" -> {
                    val tab = pageTab!!
                    BrowserAgentIndicator.activity(tab.id, "Reading the text")
                    out.text(agentText(context, tab, "pageText", args.optInt("max_chars", 50000), args.optBoolean("include_hidden", false)))
                }
                "browser_computer" -> computer(context, pageTab!!, args, batch, out)
                "browser_form_input" -> formInput(context, pageTab!!, args, out)
                "browser_eval_js" -> evalJs(context, pageTab!!, args, out)
                "browser_get_console_logs" -> consoleLogs(context, tab(context, args), args, out)
                "browser_read_network" -> network(context, tab(context, args), args, out)
                "browser_upload_file" -> upload(context, pageTab!!, args, out)
                "browser_batch" -> {
                    if (batch != null) throw ToolError("browser_batch cannot be nested")
                    return runBatch(context, args)
                }
                "browser_screenshot", "browser_inspect_dom", "browser_interact" -> return legacy(name, args)
                else -> throw ToolError("Unknown tool: $name")
            }
            out.json()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DialogOpened) {
            out.text("${dialogText(e.tab)}. The page is paused until it is answered: call browser_handle_dialog (accept=true or false).").json(error = true)
        } catch (e: ToolError) {
            out.text(e.message ?: "Failed").json(error = true)
        } catch (e: Exception) {
            out.text("$name failed: ${e.message ?: e.javaClass.simpleName}").json(error = true)
        } finally {
            lockedTab?.let { BrowserAgentLock.end(it) }
        }
    }

    private suspend fun noTab(id: String): String {
        val open = withContext(Dispatchers.Main) { manager.tabs.map { "${it.id} (${it.url.ifBlank { "new tab" }})" } }
        return "No tab with id $id: it was closed, or the app restarted (which replaces every tab, so earlier tab ids and refs are gone). " +
            "Open tabs now: ${open.joinToString().ifEmpty { "none" }}."
    }

    // ======================= lock & dialogs =======================

    private fun tabLine(t: BrowserTabSession) = "${t.id} (${clip(t.url.ifBlank { "new tab" }, 80)})"

    private suspend fun lockTool(context: Context, args: JSONObject, out: Out) {
        if (args.optBoolean("locked", true)) {
            val tab = tab(context, args)
            ensureUnlocked(tab)
            val locked = BrowserAgentLock.lock(tab)
            BrowserAgentIndicator.activity(tab.id, summary(args) ?: "Taking control")
            out.text("Agent lock on. Locked now: ${locked.joinToString { tabLine(it) }}.\n" +
                "Every tab you use or open from now on (new tabs, popups from clicks) is locked too: the user's touches are blocked and " +
                "the keyboard and zoom stay put. The user sees an Unlock button on locked tabs. When your task is done, call browser_lock " +
                "with locked=false (no tab_id) to release them all.")
            return
        }
        val id = args.optString("tab_id").trim()
        if (id.isEmpty()) {
            val released = BrowserAgentLock.unlockAll()
            released.forEach { BrowserAgentIndicator.activity(it.id, summary(args) ?: "Done") }
            out.text(if (released.isEmpty()) "Agent lock off. No tab was locked." else
                "Agent lock off. Released ${released.size} tab${if (released.size == 1) "" else "s"}: ${released.joinToString { tabLine(it) }}. The user can use ${if (released.size == 1) "it" else "them"} again.")
            return
        }
        val tab = tab(context, args)
        val was = BrowserAgentLock.unlockOne(tab)
        val still = BrowserAgentLock.lockedTabs()
        val stillText = if (still.isEmpty()) "No tab is locked now." else "Still locked: ${still.joinToString { tabLine(it) }}."
        out.text(if (was) "Released tab ${tab.id}; it stays unlocked until you lock it again. $stillText"
            else "Tab ${tab.id} was not locked. $stillText")
    }

    private fun anyDialogTab(): BrowserTabSession? = manager.tabs.toList().firstOrNull { it.pendingDialog != null }

    private fun dialogText(tab: BrowserTabSession): String {
        val d = tab.pendingDialog ?: return "A page dialog was open on tab ${tab.id}"
        val what = when (d.type) {
            "beforeunload" -> "\"Leave this page?\" dialog"
            else -> "JavaScript ${d.type}"
        }
        return "A $what is open on tab ${tab.id}${if (d.message.isNotBlank()) ": \"${clip(d.message, 200)}\"" else ""}" +
            if (d.type == "prompt") " (it asks for text: pass prompt_text)" else ""
    }

    private suspend fun dialogGuard() {
        val tab = withContext(Dispatchers.Main) { anyDialogTab() } ?: return
        throw ToolError("${dialogText(tab)}. Every page is paused until it is answered (tabs share one page engine), so nothing was done. " +
            "Call browser_handle_dialog with accept=true (OK${if (tab.pendingDialog?.type == "beforeunload") "/Leave" else ""}) or accept=false (Cancel).")
    }

    private suspend fun handleDialog(args: JSONObject, out: Out) {
        val id = args.optString("tab_id").trim()
        val tab = withContext(Dispatchers.Main) {
            if (id.isNotEmpty()) manager.tabs.firstOrNull { it.id == id } else anyDialogTab()
        } ?: throw ToolError(if (id.isNotEmpty()) noTab(id) else "No page dialog is open.")
        val d = tab.pendingDialog ?: throw ToolError("No page dialog is open on tab ${tab.id}.")
        if (!args.has("accept")) throw ToolError("accept is required: true for OK (or Leave), false for Cancel (or Stay).")
        val accept = args.optBoolean("accept")
        val text = if (args.has("prompt_text")) args.optString("prompt_text") else null
        withContext(Dispatchers.Main) {
            d.answer(accept, text)
            if (tab.pendingDialog === d) tab.pendingDialog = null
        }
        BrowserAgentIndicator.activity(tab.id, if (accept) "Answering the dialog" else "Dismissing the dialog")
        delay(300)
        val reply = if (d.type == "prompt" && accept) " with \"${clip(text ?: d.defaultText, 60)}\"" else ""
        val next = withContext(Dispatchers.Main) { anyDialogTab() }?.let { "\nAnother dialog opened right after: ${dialogText(it)}." }.orEmpty()
        out.text("${if (accept) "Accepted" else "Dismissed"} the ${d.type} \"${clip(d.message, 120)}\"$reply on tab ${tab.id}.$next")
    }

    /** The page stopped answering because it opened a dialog (alert/confirm/prompt). */
    private class DialogOpened(val tab: BrowserTabSession) : Exception()

    /**
     * Waits for [block], which needs the page to answer, but stops as soon as a page dialog pauses the page
     * (throws [DialogOpened]) or after [timeoutMs] (returns null).
     */
    private suspend fun <T> pageWait(timeoutMs: Long, block: suspend () -> T): T? = coroutineScope {
        val job = async { block() }
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val done = withTimeoutOrNull(60) { job.await() }
            if (done != null || job.isCompleted) return@coroutineScope job.await()
            anyDialogTab()?.let { job.cancel(); throw DialogOpened(it) }
            if (SystemClock.uptimeMillis() > deadline) { job.cancel(); return@coroutineScope null }
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    /** A DevTools command that needs the page to answer (input events, script runs). */
    private suspend fun cdpCall(cdp: CdpSession, method: String, params: JSONObject, timeoutMs: Long = 10_000): JSONObject {
        val queued = withContext(Dispatchers.IO) { cdp.enqueue(method, params) }
        return pageWait(timeoutMs + 500) { cdp.await(method, queued, timeoutMs) }
            ?: throw ToolError("The page did not answer $method within ${timeoutMs / 1000} s: it may be busy or frozen. Take a screenshot to see it.")
    }

    private suspend fun tab(context: Context, args: JSONObject): BrowserTabSession {
        val id = args.optString("tab_id").trim()
        val tab = withContext(Dispatchers.Main) {
            if (id.isEmpty()) manager.getActiveTab() else manager.tabs.firstOrNull { it.id == id }
        } ?: throw ToolError(if (id.isEmpty()) "No browser tab is open. Open a page with browser_open_url." else noTab(id))
        if (tab.webView == null) withContext(Dispatchers.Main) { manager.ensureWebViewAttached(context, tab, true) }
        return tab
    }

    /** Another automation (e.g. the Google Flow extension) owns this tab: don't send input into it. */
    private fun ensureUnlocked(tab: BrowserTabSession) {
        if (tab.aiBusy || tab.aiLocked) {
            throw ToolError("This tab is being automated by a browser extension (e.g. Google Flow), so no input was sent. Use another tab: browser_open_url with new_tab=true.")
        }
    }

    private suspend fun <T> locked(tab: BrowserTabSession, block: suspend () -> T): T = tabLocks.getOrPut(tab.id) { Mutex() }.withLock { block() }

    // ======================= page helper =======================

    private fun agentSource(context: Context): String = agentSource
        ?: context.assets.open("browser-mcp/page-agent.js").bufferedReader().use { it.readText() }.also { agentSource = it }

    /** Calls window.__antigemAgent.[fn] in the page (injecting the helper when missing) and returns its JSON result. */
    private suspend fun agent(context: Context, tab: BrowserTabSession, fn: String, vararg args: Any?): JSONObject {
        val call = "(function(){try{var a=window.__antigemAgent;if(!a||a.version!==$AGENT_VERSION)return JSON.stringify({__missing:true});" +
            "return JSON.stringify(a.$fn.apply(a,${JSONArray(args.toList())}));}catch(e){return JSON.stringify({ok:false,error:String(e&&e.message||e)});}})()"
        repeat(3) {
            val raw = pageWait(10_000) { manager.evaluateJs(tab.id, call) }
                ?: throw ToolError("The page did not answer within 10 s: it may be busy or still loading. Take a screenshot to see it.")
            val text = raw.getOrElse { throw ToolError("Could not run in the page: ${it.message}") }
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: if (text == "null") null else throw ToolError("The page returned something unexpected: ${text.take(200)}")
            if (json != null && !json.optBoolean("__missing")) return json
            // Missing (new page) or the page is between documents: (re)inject the helper and try again
            pageWait(10_000) { manager.evaluateJs(tab.id, agentSource(context) + "\n;true") }
            delay(100)
        }
        throw ToolError("Could not load the page helper: the page may be navigating, blocking scripts, or not an HTML page. Try again, or take a screenshot.")
    }

    private suspend fun agentText(context: Context, tab: BrowserTabSession, fn: String, vararg args: Any?): String {
        val r = agent(context, tab, fn, *args)
        if (!r.optBoolean("ok")) throw ToolError(r.optString("error", "Failed"))
        return r.optString("text")
    }

    // ======================= navigation & tabs =======================

    private suspend fun openUrl(context: Context, args: JSONObject, out: Out) {
        val url = args.optString("url").trim()
        if (url.isEmpty()) throw ToolError("url is empty")
        val desktop = when {
            args.has("mode") -> args.optString("mode").lowercase().let { it == "desktop" || it == "web" }
            args.has("desktop_mode") -> args.optBoolean("desktop_mode")
            else -> null
        }
        val tab = when (url.lowercase()) {
            "back", "forward", "reload" -> {
                val tab = tab(context, args)
                val moved = withContext(Dispatchers.Main) {
                    when (url.lowercase()) {
                        "back" -> manager.goBackTab(tab.id)
                        "forward" -> manager.goForwardTab(tab.id)
                        else -> { manager.reloadTab(tab.id); true }
                    }
                }
                if (!moved) throw ToolError("This tab has no ${if (url.equals("back", true)) "earlier" else "later"} page in its history.")
                delay(300)
                tab
            }
            else -> {
                val id = args.optString("tab_id").trim().ifEmpty { null }
                if (id != null && withContext(Dispatchers.Main) { manager.tabs.none { it.id == id } }) throw ToolError(noTab(id))
                withContext(Dispatchers.Main) { manager.openUrl(url, tabId = id, newTab = args.optBoolean("new_tab", false), desktopMode = desktop) }
            }
        }
        val opening = when (url.lowercase()) {
            "back" -> "Going back"; "forward" -> "Going forward"; "reload" -> "Reloading"
            else -> "Opening ${runCatching { android.net.Uri.parse(BrowserSessionManager.formatUrl(url)).host?.removePrefix("www.") }.getOrNull() ?: clip(url, 24)}"
        }
        BrowserAgentIndicator.activity(tab.id, summary(args) ?: opening)
        BrowserAgentLock.begin(tab)
        val loaded = try { waitLoad(tab, 10_000) } finally { BrowserAgentLock.end(tab) }
        withContext(Dispatchers.Main) { anyDialogTab() }?.let {
            out.text("Tab ${tab.id}: opening $url. The page opened a dialog while loading: ${dialogText(it).replaceFirstChar { c -> c.lowercase() }}. " +
                "It is paused until answered: call browser_handle_dialog.")
            return
        }
        val (finalUrl, title) = withContext(Dispatchers.Main) { (tab.webView?.url ?: tab.url) to tab.title }
        val mode = if (tab.isDesktopMode) "desktop" else "mobile"
        val error = tab.lastError?.let { "\nThe page reported an error: ${it.description}" }.orEmpty()
        val lockState = if (tab.agentLocked) ", locked" else ""
        out.text("Tab ${tab.id}: ${if (loaded) "loaded" else "still loading after 10 s"} $finalUrl — \"$title\" [$mode view$lockState]$error\n" +
            "Next: browser_find or browser_read_page for refs, or browser_computer action=screenshot to see it.")
    }

    private suspend fun setViewMode(context: Context, args: JSONObject, out: Out) {
        val tab = tab(context, args)
        val desktop = when {
            args.has("mode") -> args.optString("mode").lowercase().let { it == "desktop" || it == "web" }
            args.has("desktop_mode") -> args.optBoolean("desktop_mode")
            else -> !tab.isDesktopMode
        }
        withContext(Dispatchers.Main) { manager.setDesktopMode(tab, desktop) }
        delay(300)
        manager.waitForPageLoad(tab.id, 10_000)
        out.text("Switched tab ${tab.id} to ${if (desktop) "desktop" else "mobile"} view (${tab.url}). Take a new screenshot before using coordinates.")
    }

    // ======================= computer =======================

    private suspend fun computer(context: Context, tab: BrowserTabSession, args: JSONObject, batch: Batch?, out: Out) {
        try {
            computerAction(context, tab, args, batch, out)
        } catch (e: FrameError) {
            screenshot(context, tab, 1.0, batch, out)
            throw ToolError("${e.message} Nothing was sent. A fresh screenshot is attached above: use its pixels and try again.")
        }
    }

    /** A note when coordinates come from a screenshot of a page that has since been left. */
    private fun staleNote(tab: BrowserTabSession, vp: JSONObject): String {
        val frame = frames[tab.id] ?: return ""
        val now = vp.optString("url")
        return if (frame.url.isNotEmpty() && now.isNotEmpty() && frame.url != now)
            "\nNote: these coordinates come from a screenshot of ${frame.url}; the tab now shows $now. Take a new screenshot if that was not intended." else ""
    }

    private suspend fun computerAction(context: Context, tab: BrowserTabSession, args: JSONObject, batch: Batch?, out: Out) {
        when (val action = args.optString("action")) {
            "screenshot" -> {
                BrowserAgentIndicator.activity(tab.id, summary(args) ?: "Looking at the page")
                screenshot(context, tab, args.optDouble("scale", 1.0), batch, out)
            }
            "zoom" -> {
                BrowserAgentIndicator.activity(tab.id, summary(args) ?: "Looking closer")
                zoom(context, tab, args, out)
            }
            "wait" -> {
                val seconds = args.optDouble("duration", Double.NaN)
                if (seconds.isNaN() || seconds <= 0) throw ToolError("wait needs duration in seconds (up to 10)")
                if (seconds > 10) throw ToolError("duration cannot exceed 10 seconds")
                BrowserAgentIndicator.activity(tab.id, summary(args) ?: "Waiting")
                delay((seconds * 1000).toLong())
                out.text("Waited ${num(seconds)} s")
            }
            "left_click", "right_click", "double_click", "triple_click" -> locked(tab) { click(context, tab, args, action, out) }
            "hover" -> locked(tab) { hover(context, tab, args, out) }
            "left_click_drag" -> locked(tab) { drag(context, tab, args, out) }
            "type" -> locked(tab) { type(context, tab, args, out) }
            "key" -> locked(tab) { key(context, tab, args, out) }
            "scroll" -> locked(tab) { scroll(context, tab, args, batch, out) }
            "scroll_to" -> locked(tab) {
                ensureUnlocked(tab)
                val ref = args.optString("ref").trim().ifEmpty { throw ToolError("scroll_to needs ref") }
                BrowserAgentIndicator.activity(tab.id, summary(args) ?: "Scrolling to it")
                val r = agent(context, tab, "scrollIntoViewRef", ref)
                if (!r.optBoolean("ok")) throw ToolError(r.optString("error"))
                out.text("Scrolled ${r.optString("desc")} [$ref] into view${r.optString("note")}" + if (!r.optBoolean("onScreen")) " (it is still outside the visible area; it may be inside a hidden or clipped box)" else "")
            }
            "" -> throw ToolError("action is required")
            else -> throw ToolError("Unknown action \"$action\"")
        }
    }

    private class Target(val x: Double, val y: Double, val vw: Double, val vh: Double, val desc: String, val ref: String,
                         val fileInput: Boolean, val coveredBy: String, val byRef: Boolean, val note: String)

    /** Where ref or coordinate points, in visible-area CSS px (what DevTools input uses). */
    private suspend fun target(context: Context, tab: BrowserTabSession, args: JSONObject, what: String): Target {
        val ref = args.optString("ref").trim()
        if (ref.isNotEmpty()) {
            val r = agent(context, tab, "resolve", ref, true)
            if (!r.optBoolean("ok")) throw ToolError(r.optString("error") + " No mouse event was sent.")
            return Target(r.getDouble("x"), r.getDouble("y"), r.getDouble("vw"), r.getDouble("vh"), r.optString("desc"), r.optString("ref"),
                r.optBoolean("fileInput"), r.optString("coveredBy"), true, r.optString("note"))
        }
        val vp = agent(context, tab, "viewport")
        val (x, y) = point(tab, args.optJSONArray("coordinate"), what, vp)
        val hit = agent(context, tab, "hitTest", x, y)
        return Target(x, y, vp.getDouble("w"), vp.getDouble("h"), hit.optString("desc"), hit.optString("ref"), hit.optBoolean("fileInput"), "", false, staleNote(tab, vp))
    }

    /** Maps screenshot pixels to visible-area CSS px using the tab's latest screenshot. */
    private fun point(tab: BrowserTabSession, coordinate: JSONArray?, what: String, vp: JSONObject): Pair<Double, Double> {
        if (coordinate == null || coordinate.length() != 2) throw ToolError("$what needs ref, or coordinate [x, y] in pixels of the latest screenshot.")
        val frame = frames[tab.id] ?: throw FrameError("There is no screenshot of this tab yet, so coordinates have no meaning (or pass ref from browser_find / browser_read_page instead).")
        val x = coordinate.optDouble(0); val y = coordinate.optDouble(1)
        if (x.isNaN() || y.isNaN() || x < 0 || y < 0 || x > frame.shotW + 2 || y > frame.shotH + 2) {
            throw FrameError("Coordinate (${num(x)}, ${num(y)}) is outside the latest screenshot (${frame.shotW}x${frame.shotH}): use pixels of the full-size screenshot.")
        }
        val w = vp.optDouble("w"); val h = vp.optDouble("h")
        if (abs(w - frame.cssW) > frame.cssW * 0.03 || abs(h - frame.cssH) > frame.cssH * 0.03) {
            throw FrameError("The visible page area changed since the last screenshot (zoom, rotation or view mode), so its coordinates no longer fit.")
        }
        return x * frame.cssW / frame.shotW to y * frame.cssH / frame.shotH
    }

    private suspend fun mouse(cdp: CdpSession, type: String, x: Double, y: Double, button: String = "none", buttons: Int = 0, clickCount: Int = 0, modifiers: Int = 0) {
        cdpCall(cdp, "Input.dispatchMouseEvent", JSONObject().put("type", type).put("x", x).put("y", y).put("button", button)
            .put("buttons", buttons).put("clickCount", clickCount).put("modifiers", modifiers))
    }

    private suspend fun click(context: Context, tab: BrowserTabSession, args: JSONObject, action: String, out: Out) {
        ensureUnlocked(tab)
        val t = target(context, tab, args, action)
        val button = if (action == "right_click") "right" else "left"
        if (t.fileInput && button == "left") {
            throw ToolError("${t.desc} opens a file picker, which cannot be used here. Use browser_upload_file with ref=${t.ref.ifEmpty { "<its ref>" }} instead. No click was sent.")
        }
        val count = when (action) { "double_click" -> 2; "triple_click" -> 3; else -> 1 }
        val modifiers = runCatching { BrowserKeys.parseModifiers(args.optString("modifiers")) }.getOrElse { throw ToolError(it.message ?: "Bad modifiers") }
        val verb = when (action) { "double_click" -> "Double-clicked"; "triple_click" -> "Triple-clicked"; "right_click" -> "Right-clicked"; else -> "Clicked" }
        val before = pageState(tab)
        showPointer(tab, t, summary(args) ?: "${verb.removeSuffix("ed")}ing ${short(t.desc)}")
        val cdp = BrowserCdp.session(tab)
        if (cdp != null) {
            try {
                mouse(cdp, "mouseMoved", t.x, t.y, modifiers = modifiers)
                val mask = if (button == "right") 2 else 1
                for (i in 1..count) {
                    mouse(cdp, "mousePressed", t.x, t.y, button, mask, i, modifiers)
                    mouse(cdp, "mouseReleased", t.x, t.y, button, 0, i, modifiers)
                    if (i < count) delay(70)
                }
            } catch (e: DialogOpened) {
                BrowserAgentIndicator.click(tab.id)
                out.text("$verb ${t.desc}${if (t.ref.isNotEmpty()) " [${t.ref}]" else ""}. That opened a page dialog: ${dialogText(e.tab).replaceFirstChar { it.lowercase() }}. " +
                    "The page is paused until it is answered: call browser_handle_dialog (accept=true or false).")
                return
            }
        } else if (button == "right") {
            agent(context, tab, "pointerEvent", t.x, t.y, "context")
        } else {
            repeat(count) { i ->
                manager.dispatchTap(tab.id, (t.x / t.vw).toFloat(), (t.y / t.vh).toFloat()).getOrElse { throw ToolError("Tap failed: ${it.message}") }
                if (i < count - 1) delay(90)
            }
        }
        BrowserAgentIndicator.click(tab.id)
        val where = if (t.byRef) "${t.desc} [${t.ref}]" else "(${num(t.x)}, ${num(t.y)} CSS px) on ${t.desc}${if (t.ref.isNotEmpty()) " [${t.ref}]" else ""}"
        val covered = if (t.coveredBy.isNotEmpty()) "\nNote: ${t.coveredBy} was covering it at that point, so the click may have gone there (e.g. a popup or banner)." else ""
        out.text("$verb $where${t.note}$covered${settle(tab, before)}")
    }

    private suspend fun hover(context: Context, tab: BrowserTabSession, args: JSONObject, out: Out) {
        ensureUnlocked(tab)
        val t = target(context, tab, args, "hover")
        showPointer(tab, t, summary(args) ?: "Pointing at ${short(t.desc)}")
        val cdp = BrowserCdp.session(tab)
        if (cdp != null) mouse(cdp, "mouseMoved", t.x, t.y) else agent(context, tab, "pointerEvent", t.x, t.y, "hover")
        delay(150)
        out.text("Moved the mouse over ${t.desc}${if (t.ref.isNotEmpty()) " [${t.ref}]" else ""}${t.note}")
    }

    private suspend fun drag(context: Context, tab: BrowserTabSession, args: JSONObject, out: Out) {
        ensureUnlocked(tab)
        val vp = agent(context, tab, "viewport")
        val (sx, sy) = point(tab, args.optJSONArray("start_coordinate"), "left_click_drag start_coordinate", vp)
        val (ex, ey) = point(tab, args.optJSONArray("coordinate"), "left_click_drag coordinate", vp)
        val w = vp.getDouble("w"); val h = vp.getDouble("h")
        val label = summary(args) ?: "Dragging"
        BrowserAgentIndicator.moveTo(tab.id, sx / w, sy / h, label)
        if (tab.isOnScreen) delay(230)
        BrowserAgentIndicator.press(tab.id, true)
        val steps = 12
        val cdp = BrowserCdp.session(tab)
        if (cdp != null) {
            mouse(cdp, "mouseMoved", sx, sy)
            mouse(cdp, "mousePressed", sx, sy, "left", 1, 1)
            for (i in 1..steps) {
                val x = sx + (ex - sx) * i / steps; val y = sy + (ey - sy) * i / steps
                mouse(cdp, "mouseMoved", x, y, "left", 1)
                BrowserAgentIndicator.moveTo(tab.id, x / w, y / h, null)
                delay(16)
            }
            mouse(cdp, "mouseReleased", ex, ey, "left", 0, 1)
        } else {
            touchDrag(tab, sx / w, sy / h, ex / w, ey / h, steps)
            BrowserAgentIndicator.moveTo(tab.id, ex / w, ey / h, null)
        }
        BrowserAgentIndicator.press(tab.id, false)
        out.text("Dragged from (${num(sx)}, ${num(sy)}) to (${num(ex)}, ${num(ey)}) CSS px")
    }

    /** Fallback drag without DevTools: a real touch gesture on the WebView. */
    private suspend fun touchDrag(tab: BrowserTabSession, fx0: Double, fy0: Double, fx1: Double, fy1: Double, steps: Int) {
        val wv = tab.webView ?: throw ToolError("The tab has no page view")
        val down = SystemClock.uptimeMillis()
        suspend fun send(action: Int, fx: Double, fy: Double) = withContext(Dispatchers.Main) {
            val ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, (fx * wv.width).toFloat(), (fy * wv.height).toFloat(), 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            wv.dispatchTouchEvent(ev)
            ev.recycle()
        }
        send(MotionEvent.ACTION_DOWN, fx0, fy0)
        for (i in 1..steps) {
            delay(16)
            send(MotionEvent.ACTION_MOVE, fx0 + (fx1 - fx0) * i / steps, fy0 + (fy1 - fy0) * i / steps)
        }
        send(MotionEvent.ACTION_UP, fx1, fy1)
    }

    private suspend fun type(context: Context, tab: BrowserTabSession, args: JSONObject, out: Out) {
        ensureUnlocked(tab)
        val text = args.optString("text")
        if (text.isEmpty()) throw ToolError("type needs text")
        if (text.length > 20_000) throw ToolError("text is too long (${text.length} characters, max 20000); type it in parts")
        // A click can focus its field a moment later (e.g. a search overlay opening): give it time
        var focus = agent(context, tab, "focusInfo")
        val until = SystemClock.uptimeMillis() + 1_500
        while (focus.optString("kind") in setOf("none", "other") && SystemClock.uptimeMillis() < until) {
            delay(150)
            focus = agent(context, tab, "focusInfo")
        }
        when (focus.optString("kind")) {
            "none" -> throw ToolError("Nothing was typed: no text field has keyboard focus. Click the field first (its ref from browser_find), " +
                "or set it with browser_form_input. To press keys on the page itself use action=key.")
            "other" -> throw ToolError("Nothing was typed: keyboard focus is on ${focus.optString("desc")}, which is not a text field. Click the " +
                "text field first (its ref from browser_find), or set it with browser_form_input. To press keys there use action=key.")
        }
        val secret = focus.optBoolean("sensitive")
        BrowserAgentIndicator.activity(tab.id, summary(args) ?: if (secret) "Typing a password" else "Typing \"${clip(text, 24)}\"")
        val before = pageState(tab)
        val cdp = BrowserCdp.session(tab)
        if (cdp != null) {
            withContext(Dispatchers.IO) {
                val queue = ArrayList<Pair<String, Pair<Int, CompletableDeferred<JSONObject>>>>()
                val q: (String, JSONObject) -> Unit = { m, p -> queue += m to cdp.enqueue(m, p) }
                val plain = StringBuilder()
                fun flush() { if (plain.isNotEmpty()) { q("Input.insertText", JSONObject().put("text", plain.toString())); plain.setLength(0) } }
                for (c in text) {
                    when (c) {
                        '\r' -> Unit
                        '\n' -> { flush(); keyEvents(q, BrowserKeys.enter(), 0, null) }
                        '\t' -> { flush(); keyEvents(q, BrowserKeys.tab(), 0, null) }
                        else -> {
                            val k = BrowserKeys.forChar(c)
                            if (k != null) { flush(); keyEvents(q, k.first, if (k.second) BrowserKeys.SHIFT else 0, c.toString()) } else plain.append(c)
                        }
                    }
                }
                flush()
                pageWait(30_000) { for ((m, d) in queue) cdp.await(m, d, 15_000) }
                    ?: throw ToolError("The page stopped taking keyboard input (busy or frozen). Take a screenshot to see it.")
            }
        } else {
            // Fallback: insert text like a keyboard (execCommand), Enter/Tab as Android key presses
            val parts = text.replace("\r", "").split('\n')
            parts.forEachIndexed { i, line ->
                line.split('\t').forEachIndexed { j, piece ->
                    if (j > 0) androidKey(tab, KeyEvent.KEYCODE_TAB, 0)
                    if (piece.isNotEmpty()) {
                        val r = agent(context, tab, "insertText", piece)
                        if (!r.optBoolean("ok")) throw ToolError(r.optString("error"))
                    }
                }
                if (i < parts.size - 1) androidKey(tab, KeyEvent.KEYCODE_ENTER, 0)
            }
        }
        val shown = if (secret) "${text.length} characters" else "\"${clip(text, 80)}\""
        // Enter or Tab may send, clear or leave the field, so only plain text can be checked
        if (focus.optString("kind") == "editable" && '\n' !in text && '\t' !in text) {
            delay(80)
            val after = agent(context, tab, "focusInfo")
            if (after.optString("ref") == focus.optString("ref") && after.optString("sig") == focus.optString("sig")) {
                throw ToolError("Typed $shown into ${focus.optString("desc")}, but its content did not change: the page may have blocked the input " +
                    "or swapped the field. Take a screenshot to check, or use browser_form_input.")
            }
        }
        val frameNote = if (focus.optString("kind") == "frame") " (inside a frame from another site, so the result could not be checked)" else ""
        out.text("Typed $shown into ${focus.optString("desc")}$frameNote${settle(tab, before)}")
    }

    /** Queues keyDown + keyUp for [def]; [text] is what it types (null for keys like Tab or arrows). */
    private fun keyEvents(q: (String, JSONObject) -> Unit, def: KeyDef, modifiers: Int, text: String?) {
        val shifted = def.text?.let { if (modifiers and BrowserKeys.SHIFT != 0 && it.length == 1 && it[0].isLetter()) it.uppercase() else it }
        val typed = (text ?: shifted)?.takeIf { modifiers and (BrowserKeys.CTRL or BrowserKeys.ALT or BrowserKeys.META) == 0 }
        val base = { type: String ->
            JSONObject().put("type", type).put("key", def.key).put("code", def.code).put("windowsVirtualKeyCode", def.keyCode)
                .put("nativeVirtualKeyCode", def.keyCode).put("modifiers", modifiers).put("location", def.location)
        }
        q("Input.dispatchKeyEvent", base(if (typed != null) "keyDown" else "rawKeyDown").apply { if (typed != null) { put("text", typed); put("unmodifiedText", typed) } })
        q("Input.dispatchKeyEvent", base("keyUp"))
    }

    private suspend fun androidKey(tab: BrowserTabSession, keyCode: Int, meta: Int) {
        val wv = tab.webView ?: throw ToolError("The tab has no page view")
        withContext(Dispatchers.Main) {
            val now = SystemClock.uptimeMillis()
            wv.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
            wv.dispatchKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0, meta))
        }
    }

    private suspend fun key(context: Context, tab: BrowserTabSession, args: JSONObject, out: Out) {
        ensureUnlocked(tab)
        val spec = args.optString("text").trim()
        val steps = runCatching { BrowserKeys.parse(spec) }.getOrElse { throw ToolError(it.message ?: "Bad keys") }
        val repeat = if (args.has("repeat")) args.optInt("repeat", 0) else 1
        if (repeat !in 1..100) throw ToolError("repeat must be between 1 and 100")
        val single = steps.singleOrNull() as? KeyStep.Press
        val withCtrl = single != null && single.modifiers and (BrowserKeys.CTRL or BrowserKeys.META) != 0
        if (single != null && withCtrl && single.def.key in setOf("=", "+", "-", "0")) {
            throw ToolError("Page zoom shortcuts are not supported. To look closer, use action=zoom with a region of the screenshot.")
        }
        if (single != null && repeat == 1 && (single.def.key == "F5" || (withCtrl && single.def.key.equals("r", true)))) {
            withContext(Dispatchers.Main) { manager.reloadTab(tab.id) }
            delay(300)
            manager.waitForPageLoad(tab.id, 10_000)
            out.text("Reloaded the page (${tab.url})")
            return
        }
        BrowserAgentIndicator.activity(tab.id, summary(args) ?: "Pressing ${clip(spec, 24)}")
        val before = pageState(tab)
        val cdp = BrowserCdp.session(tab)
        if (cdp != null) {
            withContext(Dispatchers.IO) {
                val queue = ArrayList<Pair<String, Pair<Int, CompletableDeferred<JSONObject>>>>()
                val q: (String, JSONObject) -> Unit = { m, p -> queue += m to cdp.enqueue(m, p) }
                repeat(repeat) {
                    for (s in steps) when (s) {
                        is KeyStep.Text -> q("Input.insertText", JSONObject().put("text", s.text))
                        is KeyStep.Press -> {
                            val held = BrowserKeys.modifierKeys(s.modifiers).filter { it != s.def }
                            var bits = 0
                            for (m in held) {
                                bits = bits or m.modifierBit
                                q("Input.dispatchKeyEvent", JSONObject().put("type", "rawKeyDown").put("key", m.key).put("code", m.code)
                                    .put("windowsVirtualKeyCode", m.keyCode).put("modifiers", bits).put("location", m.location))
                            }
                            keyEvents(q, s.def, s.modifiers, null)
                            for (m in held.reversed()) {
                                bits = bits and m.modifierBit.inv()
                                q("Input.dispatchKeyEvent", JSONObject().put("type", "keyUp").put("key", m.key).put("code", m.code)
                                    .put("windowsVirtualKeyCode", m.keyCode).put("modifiers", bits).put("location", m.location))
                            }
                        }
                    }
                }
                pageWait(30_000) { for ((m, d) in queue) cdp.await(m, d, 15_000) }
                    ?: throw ToolError("The page stopped taking keyboard input (busy or frozen). Take a screenshot to see it.")
            }
        } else {
            repeat(repeat) {
                for (s in steps) when (s) {
                    is KeyStep.Text -> agent(context, tab, "insertText", s.text)
                    is KeyStep.Press -> androidKey(tab, s.def.androidCode, BrowserKeys.androidMeta(s.modifiers))
                }
            }
        }
        val count = steps.size
        out.text("Pressed ${if (count == 1) "key" else "$count keys"}: $spec${if (repeat > 1) " ($repeat times)" else ""}${settle(tab, before)}")
    }

    private suspend fun scroll(context: Context, tab: BrowserTabSession, args: JSONObject, batch: Batch?, out: Out) {
        ensureUnlocked(tab)
        val direction = args.optString("scroll_direction", "down").ifBlank { "down" }
        val amount = args.optInt("scroll_amount", 3).coerceIn(1, 10)
        val vp = agent(context, tab, "viewport")
        val w = vp.getDouble("w"); val h = vp.getDouble("h")
        val (x, y) = if (args.optJSONArray("coordinate") != null) point(tab, args.optJSONArray("coordinate"), "scroll", vp) else (w / 2) to (h / 2)
        val ticks = amount * 100.0
        val (dx, dy) = when (direction) {
            "up" -> 0.0 to -ticks; "down" -> 0.0 to ticks; "left" -> -ticks to 0.0; "right" -> ticks to 0.0
            else -> throw ToolError("scroll_direction must be up, down, left or right")
        }
        BrowserAgentIndicator.moveTo(tab.id, x / w, y / h, summary(args) ?: "Scrolling $direction")
        val cdp = BrowserCdp.session(tab)
        if (cdp != null) {
            cdpCall(cdp, "Input.dispatchMouseEvent", JSONObject().put("type", "mouseWheel").put("x", x).put("y", y).put("deltaX", dx).put("deltaY", dy))
        } else {
            agent(context, tab, "scrollAt", x, y, dx, dy)
        }
        delay(350)
        val after = agent(context, tab, "viewport")
        fun at(v: JSONObject, k: String, o: String) = v.optDouble(k) + v.optDouble(o)
        val moved = abs(at(after, "sx", "ox") - at(vp, "sx", "ox")) + abs(at(after, "sy", "oy") - at(vp, "sy", "oy")) > 0.5
        val where = "now showing ${num(at(after, "sx", "ox"))},${num(at(after, "sy", "oy"))} of ${after.optInt("pw")}x${after.optInt("ph")}"
        out.text("Scrolled $direction by $amount ticks at (${num(x)}, ${num(y)} CSS px), $where" +
            if (!moved) " (the page itself did not move: an inner box may have scrolled, or this is already the end)" else "")
        screenshot(context, tab, 1.0, batch, out)
    }

    // ======================= screenshots =======================

    private class Raw(val bitmap: Bitmap, val vp: JSONObject)

    private suspend fun captureRaw(context: Context, tab: BrowserTabSession): Raw {
        val vp = agent(context, tab, "viewport")
        var bmp: Bitmap? = null
        // DevTools draws exactly what is shown; off-screen tabs are drawn by the WebView itself
        if (tab.isOnScreen) {
            BrowserCdp.session(tab)?.let { cdp ->
                bmp = runCatching {
                    val r = cdp.send("Page.captureScreenshot", JSONObject().put("format", "jpeg").put("quality", 90)
                        .put("fromSurface", true).put("captureBeyondViewport", false), 6_000)
                    val bytes = Base64.decode(r.getString("data"), Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
        }
        val bitmap = bmp ?: manager.captureScreenshot(tab.id, false, 90).getOrElse { throw ToolError("Could not capture the page: ${it.message}") }
        return Raw(bitmap, vp)
    }

    /** Largest size with the same aspect that fits model vision: long side <= 1568 px and ~1568 tokens of 28x28 px. */
    private fun fit(w: Int, h: Int): Pair<Int, Int> {
        fun tokens(a: Int, b: Int) = ((a - 1) / PX_PER_TOKEN + 1) * ((b - 1) / PX_PER_TOKEN + 1)
        if (w <= MAX_PX && h <= MAX_PX && tokens(w, h) <= MAX_TOKENS) return w to h
        if (h > w) return fit(h, w).let { it.second to it.first }
        val ratio = w.toDouble() / h
        var lo = 1; var hi = w
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            val mh = max(1, (mid / ratio).roundToInt())
            if (mid <= MAX_PX && tokens(mid, mh) <= MAX_TOKENS) lo = mid else hi = mid
        }
        return lo to max(1, (lo / ratio).roundToInt())
    }

    private fun jpeg(bitmap: Bitmap, w: Int, h: Int): String {
        val scaled = if (bitmap.width == w && bitmap.height == h) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        val bytes = ByteArrayOutputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 75, it); it.toByteArray() }
        if (scaled !== bitmap) scaled.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private suspend fun screenshot(context: Context, tab: BrowserTabSession, scale: Double, batch: Batch?, out: Out) {
        // Desktop layouts are wider than the phone screen; a screenshot of the left part alone misleads, so zoom out to fit
        var fitNote = ""
        val before = agent(context, tab, "viewport")
        if (before.optDouble("iw", 0.0) > before.optDouble("w") * 1.15) {
            withContext(Dispatchers.Main) { manager.zoomOutFully(tab) }
            delay(500)
            val now = agent(context, tab, "viewport")
            if (now.optDouble("w") > before.optDouble("w") * 1.1) fitNote = "\nZoomed the tab out so the page's full width is visible."
        }
        val raw = captureRaw(context, tab)
        try {
            val (fw, fh) = fit(raw.bitmap.width, raw.bitmap.height)
            val s = if (scale.isNaN()) 1.0 else scale.coerceIn(0.1, 1.0)
            val ow = max(1, (fw * s).roundToInt()); val oh = max(1, (fh * s).roundToInt())
            val frame = Frame(fw, fh, raw.vp.getDouble("w"), raw.vp.getDouble("h"), raw.vp.optString("url"))
            if (batch != null) batch.frames[tab.id] = frame else frames[tab.id] = frame
            val scaleNote = if (s < 1.0) ", shown at ${num(s)} scale: give coordinates in the full ${fw}x${fh} frame" else ""
            out.text("Screenshot of tab ${tab.id} — \"${clip(raw.vp.optString("title"), 80)}\" ${raw.vp.optString("url")}\n" +
                "Size ${fw}x${fh}$scaleNote. Coordinates for clicks are pixels in this ${fw}x${fh} image.$fitNote")
            out.image(jpeg(raw.bitmap, ow, oh))
        } finally {
            raw.bitmap.recycle()
        }
    }

    private suspend fun zoom(context: Context, tab: BrowserTabSession, args: JSONObject, out: Out) {
        val region = args.optJSONArray("region")
        if (region == null || region.length() != 4) throw ToolError("zoom needs region [x0, y0, x1, y1] in pixels of the latest screenshot")
        val frame = frames[tab.id] ?: throw FrameError("There is no screenshot of this tab yet: the zoom region is given in its pixels.")
        val (x0, y0, x1, y1) = (0 until 4).map { region.optDouble(it) }
        if (listOf(x0, y0, x1, y1).any { it.isNaN() } || x1 <= x0 || y1 <= y0) throw ToolError("region must be [x0, y0, x1, y1] with x1 > x0 and y1 > y0")
        if (x0 < 0 || y0 < 0 || x1 > frame.shotW + 2 || y1 > frame.shotH + 2) throw FrameError("region is outside the latest screenshot (${frame.shotW}x${frame.shotH}).")
        val raw = captureRaw(context, tab)
        try {
            // screenshot px -> CSS px -> pixels of this capture
            val kx = frame.cssW / frame.shotW * raw.bitmap.width / raw.vp.getDouble("w")
            val ky = frame.cssH / frame.shotH * raw.bitmap.height / raw.vp.getDouble("h")
            val left = (x0 * kx).toInt().coerceIn(0, raw.bitmap.width - 1)
            val top = (y0 * ky).toInt().coerceIn(0, raw.bitmap.height - 1)
            val width = ((x1 * kx).toInt() - left).coerceIn(1, raw.bitmap.width - left)
            val height = ((y1 * ky).toInt() - top).coerceIn(1, raw.bitmap.height - top)
            val crop = Bitmap.createBitmap(raw.bitmap, left, top, width, height)
            try {
                var (zw, zh) = fit(min(width * 3, MAX_PX * 4), min(height * 3, MAX_PX * 4)).let { (a, b) ->
                    // Enlarge small regions up to 3x so details are legible, within the image budget
                    val k = min(a.toDouble() / width, b.toDouble() / height)
                    max(1, (width * k).roundToInt()) to max(1, (height * k).roundToInt())
                }
                val s = args.optDouble("scale", 1.0).let { if (it.isNaN()) 1.0 else it.coerceIn(0.1, 1.0) }
                zw = max(1, (zw * s).roundToInt()); zh = max(1, (zh * s).roundToInt())
                out.text("Zoomed view of (${num(x0)}, ${num(y0)})-(${num(x1)}, ${num(y1)}) at ${zw}x${zh}. Click coordinates still refer to the full ${frame.shotW}x${frame.shotH} screenshot.")
                out.image(jpeg(crop, zw, zh))
            } finally {
                if (crop !== raw.bitmap) crop.recycle()
            }
        } finally {
            raw.bitmap.recycle()
        }
    }

    // ======================= forms, scripts, logs =======================

    private suspend fun formInput(context: Context, tab: BrowserTabSession, args: JSONObject, out: Out) = locked(tab) {
        ensureUnlocked(tab)
        val ref = args.optString("ref").trim().ifEmpty { throw ToolError("ref is required") }
        if (!args.has("value")) throw ToolError("value is required")
        val value = args.opt("value")
        val where = agent(context, tab, "resolve", ref, true)
        if (!where.optBoolean("ok")) throw ToolError(where.optString("error"))
        BrowserAgentIndicator.moveTo(tab.id, where.getDouble("x") / where.getDouble("vw"), where.getDouble("y") / where.getDouble("vh"),
            summary(args) ?: "Filling ${short(where.optString("desc"))}")
        val before = pageState(tab)
        val r = agent(context, tab, "formInput", ref, value)
        BrowserAgentIndicator.click(tab.id)
        if (!r.optBoolean("ok")) throw ToolError(r.optString("error"))
        out.text(r.optString("text") + settle(tab, before))
    }

    private suspend fun evalJs(context: Context, tab: BrowserTabSession, args: JSONObject, out: Out) {
        val script = args.optString("script").ifBlank { args.optString("text") }
        if (script.isBlank()) throw ToolError("script is empty")
        BrowserAgentIndicator.activity(tab.id, "Running a script")
        val cdp = BrowserCdp.session(tab)
        if (cdp == null) {
            val r = manager.evaluateJs(tab.id, script).getOrElse { throw ToolError("Error: ${it.message}") }
            out.text("$r\n(DevTools unavailable on this page: top-level await is not supported here)")
            return
        }
        val r = cdpCall(cdp, "Runtime.evaluate", JSONObject().put("expression", script).put("replMode", true).put("awaitPromise", true)
            .put("returnByValue", true).put("userGesture", true).put("timeout", 30_000), 32_000)
        r.optJSONObject("exceptionDetails")?.let { ex ->
            val desc = ex.optJSONObject("exception")?.optString("description").orEmpty().ifBlank { ex.optString("text") }
            throw ToolError("JavaScript error: ${desc.take(4000)}")
        }
        val result = r.optJSONObject("result") ?: JSONObject()
        val text = when {
            result.optString("type") == "undefined" -> "undefined"
            result.has("value") -> when (val v = result.get("value")) {
                is String -> v
                JSONObject.NULL -> "null"
                is JSONObject -> v.toString(2)
                is JSONArray -> v.toString(2)
                else -> v.toString()
            }
            else -> result.optString("description", result.optString("type"))
        }
        out.text(if (text.length > 100_000) text.take(100_000) + "\n[cut at 100000 characters]" else text)
    }

    private suspend fun consoleLogs(context: Context, tab: BrowserTabSession, args: JSONObject, out: Out) {
        val regex = args.optString("pattern").takeIf { it.isNotBlank() }?.let { p ->
            runCatching { Regex(p, RegexOption.IGNORE_CASE) }.getOrElse { Regex(Regex.escape(p), RegexOption.IGNORE_CASE) }
        }
        val level = args.optString("level").uppercase().takeIf { it.isNotBlank() && it != "ALL" }
        val limit = args.optInt("limit", 100).coerceIn(1, 300)
        val all = tab.consoleLogs.toList()
        val picked = all.filter { m ->
            (!args.optBoolean("only_errors") || m.level == "ERROR") && (level == null || m.level == level) &&
                (regex == null || regex.containsMatchIn(m.message) || regex.containsMatchIn(m.sourceId))
        }.takeLast(limit)
        if (args.optBoolean("clear")) tab.consoleLogs.clear()
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val lines = picked.map { m ->
            val source = m.sourceId.substringBefore('?').substringAfterLast('/').take(60).takeIf { it.isNotBlank() }?.let { " ($it:${m.lineNumber})" }.orEmpty()
            "[${fmt.format(Date(m.timestamp))}] ${m.level} ${m.message.take(2000)}$source"
        }
        out.text(if (lines.isEmpty()) "No console messages${if (regex != null || level != null || args.optBoolean("only_errors")) " match" else " yet"} (${all.size} recorded in total)."
            else "${if (picked.size < all.size) "Newest ${picked.size} of ${all.size}" else "All ${all.size}"} console messages, oldest first:\n" + lines.joinToString("\n"))
    }

    private suspend fun network(context: Context, tab: BrowserTabSession, args: JSONObject, out: Out) {
        val pattern = args.optString("url_pattern").trim()
        val onlyFailed = args.optBoolean("only_failed")
        val limit = args.optInt("limit", 100).coerceIn(1, 500)
        val cdp = BrowserCdp.session(tab)
        val log = BrowserCdp.network(tab.id)
        if (cdp == null || log == null) {
            val errors = manager.getNetworkErrors(tab.id, limit).filter { pattern.isEmpty() || it.url.contains(pattern) }
            out.text("Full request recording needs DevTools, which is not available on this page. Failed requests seen by the browser:\n" +
                (errors.joinToString("\n") { "FAILED ${it.errorCode} ${it.url} — ${it.description}" }.ifEmpty { "(none)" }))
            return
        }
        val all = log.snapshot()
        val picked = all.filter { e ->
            (pattern.isEmpty() || e.url.contains(pattern)) && (!onlyFailed || e.error != null || e.status >= 400)
        }.takeLast(limit)
        if (args.optBoolean("clear")) log.clear()
        val lines = picked.map { e ->
            val status = e.error?.let { "FAILED($it)" } ?: if (e.status == 0) "pending" else e.status.toString()
            val size = if (e.bytes >= 0) " ${if (e.bytes >= 1024) "${num(e.bytes / 1024.0)} KB" else "${e.bytes} B"}" else ""
            val time = if (e.durationMs >= 0) " ${e.durationMs} ms" else ""
            "${e.method} $status ${e.type.lowercase()}${if (e.mime.isNotBlank()) " ${e.mime}" else ""}$size$time${if (e.fromCache) " (cache)" else ""} ${clip(e.url, 300)}"
        }
        out.text(if (lines.isEmpty()) "No requests${if (pattern.isNotEmpty() || onlyFailed) " match" else " recorded yet"} (${all.size} in total). Recording started when the browser MCP first used this tab; reload to capture a page's own requests."
            else "${if (picked.size < all.size) "Newest ${picked.size} of ${all.size}" else "All ${all.size}"} requests, oldest first:\n" + lines.joinToString("\n"))
    }

    private suspend fun upload(context: Context, tab: BrowserTabSession, args: JSONObject, out: Out) = locked(tab) {
        ensureUnlocked(tab)
        val paths = args.optJSONArray("file_paths")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        if (paths.isEmpty()) throw ToolError("file_paths is empty")
        var x: Float? = null
        var y: Float? = null
        val ref = args.optString("ref").trim()
        if (ref.isNotEmpty()) {
            val r = agent(context, tab, "resolve", ref, true)
            if (!r.optBoolean("ok")) throw ToolError(r.optString("error"))
            x = r.getDouble("cx").toFloat(); y = r.getDouble("cy").toFloat()
            BrowserAgentIndicator.moveTo(tab.id, r.getDouble("x") / r.getDouble("vw"), r.getDouble("y") / r.getDouble("vh"),
                summary(args) ?: "Uploading ${paths.size} file${if (paths.size == 1) "" else "s"}")
        } else {
            BrowserAgentIndicator.activity(tab.id, summary(args) ?: "Uploading ${paths.size} file${if (paths.size == 1) "" else "s"}")
        }
        val res = manager.uploadFiles(
            tab.id, paths, args.optString("selector").ifBlank { null }, x, y,
            method = args.optString("method").ifBlank { "auto" },
            timeoutMs = args.optInt("timeout_seconds", 10).coerceIn(1, 30) * 1000L
        )
        val json = res.getOrElse { throw ToolError("Upload failed: ${it.message}") }
        if (ref.isNotEmpty()) BrowserAgentIndicator.click(tab.id)
        out.text(json.toString(2))
    }

    // ======================= batch =======================

    private val aliases = mapOf(
        "computer" to "browser_computer", "navigate" to "browser_open_url", "javascript_tool" to "browser_eval_js",
        "tabs_context" to "browser_list_tabs", "tabs_context_mcp" to "browser_list_tabs", "read_console_messages" to "browser_get_console_logs",
        "read_network_requests" to "browser_read_network", "file_upload" to "browser_upload_file",
    )

    private suspend fun runBatch(context: Context, args: JSONObject): JSONObject {
        val actions = args.optJSONArray("actions") ?: return Out().text("actions is required: a list of {name, input}").json(error = true)
        if (actions.length() == 0) return Out().text("actions is empty").json(error = true)
        if (actions.length() > 50) return Out().text("At most 50 actions per batch").json(error = true)
        val batch = Batch()
        val out = Out()
        var failed = false
        try {
            for (i in 0 until actions.length()) {
                val item = actions.optJSONObject(i)
                val raw = item?.optString("name").orEmpty().trim()
                val name = aliases[raw] ?: if (raw.startsWith("browser_")) raw else "browser_$raw"
                val input = item?.optJSONObject("input") ?: JSONObject()
                val label = "[${i + 1}/${actions.length()}] $name${input.optString("action").takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}"
                if (item == null || raw.isEmpty()) {
                    out.text("$label: each action needs a name and an input object. Stopped here.")
                    failed = true
                    break
                }
                if (name == "browser_batch") {
                    out.text("$label: batches cannot be nested. Stopped here.")
                    failed = true
                    break
                }
                val result = run(context, name, input, batch)
                val content = result.optJSONArray("content") ?: JSONArray()
                var first = true
                for (j in 0 until content.length()) {
                    val c = content.getJSONObject(j)
                    if (c.optString("type") == "text" && first) {
                        out.content.put(JSONObject().put("type", "text").put("text", "$label: ${c.optString("text")}"))
                        first = false
                    } else out.content.put(c)
                }
                if (first) out.text("$label: done")
                if (result.optBoolean("isError")) {
                    out.text("Batch stopped at step ${i + 1} of ${actions.length()} because it failed; later steps were not run.")
                    failed = true
                    break
                }
            }
        } finally {
            frames.putAll(batch.frames)
        }
        return out.json(error = failed)
    }

    // ======================= legacy tools (hidden) =======================

    private suspend fun legacy(name: String, args: JSONObject): JSONObject {
        val out = Out()
        val tabId = args.optString("tab_id").ifBlank { null }
        when (name) {
            "browser_screenshot" -> {
                val res = manager.captureScreenshotBase64(tabId, args.optBoolean("full_page", false), args.optInt("quality", 80))
                val data = res.getOrElse { return out.text("Screenshot failed: ${it.message}").json(error = true) }
                out.content.put(JSONObject().put("type", "image").put("data", data.removePrefix("data:image/jpeg;base64,")).put("mimeType", "image/jpeg"))
            }
            "browser_inspect_dom" -> {
                val res = manager.inspectDom(tabId)
                out.text(res.getOrElse { return out.text("Inspect DOM failed: ${it.message}").json(error = true) }.toString(2))
            }
            "browser_interact" -> {
                val selector = args.optString("selector").ifBlank { null }
                val res = when (args.optString("action")) {
                    "click" -> manager.clickElement(tabId, selector, if (args.has("x")) args.optDouble("x").toFloat() else null, if (args.has("y")) args.optDouble("y").toFloat() else null)
                    "type" -> manager.typeText(tabId, selector ?: "input", args.optString("text"), args.optBoolean("clear_first", false))
                    "scroll" -> manager.scrollPage(tabId, args.optInt("dx", 0), args.optInt("dy", 0), selector)
                    else -> Result.failure(Exception("action must be click, type or scroll"))
                }
                out.text(if (res.isSuccess) "Done" else "Failed: ${res.exceptionOrNull()?.message}")
            }
        }
        return out.json()
    }

    // ======================= helpers =======================

    private class PageState(val url: String, val tabIds: Set<String>, val loading: Boolean)

    private suspend fun pageState(tab: BrowserTabSession) = withContext(Dispatchers.Main) {
        PageState(tab.webView?.url ?: tab.url, manager.tabs.map { it.id }.toSet(), tab.isLoading)
    }

    /** Waits until [tab] finished loading; false on timeout or when a page dialog pauses it. */
    private suspend fun waitLoad(tab: BrowserTabSession, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (tab.isLoading || tab.progress < 100) {
            if (anyDialogTab() != null || SystemClock.uptimeMillis() > deadline) return false
            delay(100)
        }
        return true
    }

    /** Lets the page react, then reports navigation, new tabs and dialogs. */
    private suspend fun settle(tab: BrowserTabSession, before: PageState): String {
        delay(250)
        var now = pageState(tab)
        // Wait only for loads this action started: some pages never reach 100% (long polling)
        if (now.url != before.url || (!before.loading && now.loading)) {
            waitLoad(tab, 6_000)
            now = pageState(tab)
        }
        val notes = StringBuilder()
        withContext(Dispatchers.Main) { anyDialogTab() }?.let {
            notes.append("\nThat opened a page dialog: ${dialogText(it).replaceFirstChar { c -> c.lowercase() }}. The page is paused until it is answered: call browser_handle_dialog.")
        }
        if (now.url != before.url) {
            notes.append("\nThe page changed to ${now.url} — \"${clip(tab.title, 80)}\"${if (tab.isLoading) " (still loading)" else ""}. Earlier refs may be gone.")
        }
        val opened = withContext(Dispatchers.Main) {
            manager.tabs.filter { it.id !in before.tabIds }.map { "${it.id} (${it.url.ifBlank { "loading" }}${if (it.agentLocked) ", locked like this one" else ""})" }
        }
        if (opened.isNotEmpty()) notes.append("\nThis opened a new tab: ${opened.joinToString()}. Pass its tab_id to work in it.")
        return notes.toString()
    }

    /** Shows the agent cursor travelling to [t] and gives the user a moment to see it. */
    private suspend fun showPointer(tab: BrowserTabSession, t: Target, label: String) {
        BrowserAgentIndicator.moveTo(tab.id, t.x / t.vw, t.y / t.vh, label)
        if (tab.isOnScreen) delay(230)
    }

    /** Short name for captions: `button "Sign in to your account"` -> `Sign in to your acco…`. */
    private fun short(desc: String): String {
        val name = Regex("\"(.+)\"").find(desc)?.groupValues?.get(1)?.trim()?.trimEnd(':')
        return clip(if (name.isNullOrBlank()) desc else name, 28)
    }

    private fun summary(args: JSONObject): String? = args.optString("action_summary").trim().takeIf { it.isNotEmpty() }?.let { clip(it, 48) }

    private fun clip(s: String, n: Int): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.length > n) t.take(n - 1) + "…" else t
    }

    private fun num(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else String.format(Locale.US, "%.1f", d)
}
