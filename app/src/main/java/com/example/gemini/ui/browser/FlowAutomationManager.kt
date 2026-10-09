package com.example.gemini.ui.browser

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import com.example.gemini.data.local.LocalEnvironmentManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One generation request. Every setting is applied explicitly (Flow shares aspect/count between image and
 * video mode), so defaults here mirror the spec's defaults.
 */
data class FlowRequest(
    val prompt: String,
    val isVideo: Boolean,
    val model: String,
    val aspectRatio: String,
    val count: Int,
    val resolution: String? = null,
    val durationSec: Int? = null,
    /** Shared credit cap for all prompts of one tool call (videos) */
    val budget: FlowCreditBudget? = null,
    val projectId: String? = null,
    val newProject: Boolean = false,
    /** Reference images (image refs, or video "Ingredients"): Flow media ids or absolute local file paths */
    val references: List<String> = emptyList(),
    /** Video "Frames" mode start/end frame: Flow media id or absolute local file path */
    val startFrame: String? = null,
    val endFrame: String? = null,
    /** What to do in Flow; edit/upscale act on [targetMediaId] */
    val action: FlowAction = FlowAction.GENERATE,
    val targetMediaId: String? = null,
    /** Upscale target: "2K" / "4K" for images, "720p" / "1080p" / "4K" for videos */
    val upscaleTo: String = "2K",
    /** Let the Send Guard capture the request and block it on purpose (no credits) */
    val dryRun: Boolean = false
)

enum class FlowAction { GENERATE, EDIT_IMAGE, EDIT_VIDEO, EXTEND_VIDEO, UPSCALE_IMAGE, UPSCALE_VIDEO }

/** Total credit limit shared by every prompt of one tool call; a prompt that would exceed it is skipped. */
class FlowCreditBudget(val limit: Int) {
    var used = 0
        private set

    @Synchronized
    fun tryReserve(cost: Int): Boolean {
        if (used + cost > limit) return false
        used += cost
        return true
    }

    @Synchronized
    fun release(cost: Int) {
        used = (used - cost).coerceAtLeast(0)
    }
}

data class FlowMediaFile(val mediaId: String, val type: String, val path: String, val title: String = "") {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("media_id", mediaId)
        put("type", type)
        put("path", path)
        if (title.isNotBlank()) put("title", title)
    }
}

class FlowJob(val id: String, val request: FlowRequest) {
    @Volatile var status = "queued"
    @Volatile var stage = "waiting in queue"
    @Volatile var error: String? = null
    @Volatile var projectId: String? = null
    @Volatile var cost: Int? = null
    @Volatile var mediaIds: List<String> = emptyList()
    @Volatile var files: List<FlowMediaFile> = emptyList()
    /** Media ids of the project before an upscale (to find the new copy if the reply doesn't name it) */
    @Volatile var knownMedia: Set<String> = emptySet()
    /** Dry run: what the page would have sent (long strings such as tokens redacted) */
    @Volatile var sent: JSONObject? = null
    val createdAt = System.currentTimeMillis()

    val isFinished: Boolean
        get() = status == "done" || status == "failed" || status == "cancelled"

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("job_id", id)
        put("status", status)
        put("stage", stage)
        put("type", when (request.action) {
            FlowAction.EDIT_IMAGE -> "image edit"
            FlowAction.EDIT_VIDEO -> "video edit"
            FlowAction.EXTEND_VIDEO -> "video extend"
            FlowAction.UPSCALE_IMAGE -> "image upscale"
            FlowAction.UPSCALE_VIDEO -> "video upscale"
            FlowAction.GENERATE -> if (request.isVideo) "video" else "image"
        })
        if (request.action == FlowAction.GENERATE) put("model", request.model)
        put("prompt", request.prompt.take(200))
        projectId?.let { put("project_id", it) }
        cost?.let { put("credits_used", it) }
        if (mediaIds.isNotEmpty()) put("media_ids", JSONArray(mediaIds))
        error?.let { put("error", it) }
        sent?.let { put("request_sent", it) }
        put("files", JSONArray().apply { files.forEach { put(it.toJsonObject()) } })
    }
}

/**
 * Google Flow (flow.google.com) automation inside the in-app browser, following google-flow-mcp-spec.md:
 * reads and project management go through Flow's own RPCs from inside the page; generation goes through the
 * real UI (the page creates its own reCAPTCHA token), the outgoing request is verified and the result is read
 * from the page's response. Jobs run one at a time. Media files are stored once in ~/flow-media, named by
 * Flow's media id, so the same image/video is never saved twice.
 */
class FlowAutomationManager private constructor() {

    companion object {
        private const val TAG = "FlowAutomation"
        const val FLOW_URL = "https://flow.google.com"
        private const val MAX_JOBS_KEPT = 50
        private const val MAX_IN_FLIGHT = 4
        private const val BLOB_CHUNK = 512 * 1024
        private val UPLOAD_TYPES = listOf("png", "jpg", "jpeg", "webp", "gif", "heif", "heic", "mp4", "m4v", "mov", "3gp", "avi")
        const val NOT_SIGNED_IN = "NOT SIGNED IN: the user is not signed in to Google Flow. Stop here and ask them to open the Flow tab in the AntiGem browser and sign in with their Google account. Do not retry any flow_* tool until they confirm."

        const val DEFAULT_IMAGE_MODEL = "Nano Banana 2.1"
        const val DEFAULT_VIDEO_MODEL = "Omni 1.1 Flash"
        val IMAGE_MODELS = listOf("Nano Banana 2.1", "Nano Banana Pro", "Nano Banana 2 Lite")
        val VIDEO_MODELS = listOf("Omni 1.1 Flash", "Veo 3.1 - Lite", "Veo 3.1 - Fast", "Veo 3.1 - Quality")
        val IMAGE_ASPECTS = listOf("16:9", "4:3", "1:1", "3:4", "9:16")
        val VIDEO_ASPECTS = listOf("16:9", "9:16")

        // Verified request encodings (spec §6) used to check what the page actually sent
        private val IMAGE_MODEL_KEYS = mapOf("Nano Banana 2.1" to "BELUGA", "Nano Banana Pro" to "GEM_PIX_2", "Nano Banana 2 Lite" to "HARBOR_SEAL")
        private val IMAGE_ASPECT_ENUM = mapOf("1:1" to 1, "9:16" to 2, "16:9" to 3, "3:4" to 4, "4:3" to 5)
        private val VIDEO_ASPECT_ENUM = mapOf("9:16" to 1, "16:9" to 2)

        // jwpduf / GetMedia status values (spec §5b)
        private const val VIDEO_DONE = 3
        // 1 = queued (seen on upscales), 6 = submitted, 2 = generating
        private val VIDEO_PENDING = setOf(1, 2, 6)

        val instance: FlowAutomationManager by lazy { FlowAutomationManager() }

        fun isFlowUrl(url: String?): Boolean {
            val uri = try { android.net.Uri.parse(url ?: "") } catch (_: Exception) { null } ?: return false
            return uri.host?.lowercase() == "flow.google.com"
        }

        fun isOmni(model: String) = model.contains("omni", ignoreCase = true)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<FlowJob>(Channel.UNLIMITED)
    private val jobs = ConcurrentHashMap<String, FlowJob>()
    /** Coroutine of every job that is submitting or waiting for its result */
    private val workers = ConcurrentHashMap<String, Job>()
    /** At most this many jobs are submitted-and-generating at the same time */
    private val inFlight = Semaphore(MAX_IN_FLIGHT)
    /** Jobs whose reply is still expected on the Flow page (the tab must not navigate away meanwhile) */
    private val pageRepliesPending = AtomicInteger(0)
    /** Number of running operations that drive the Flow page (user input is blocked meanwhile) */
    private val busyCount = AtomicInteger(0)

    /** The model asked to lock the Flow tab (saved across restarts until it unlocks it) */
    var tabLocked by mutableStateOf(false)
        private set

    /** True while an AI operation is driving the Flow page */
    var isBusy by mutableStateOf(false)
        private set
    private var workerStarted = false
    /** Local file (path|size|mtime) → media id, so the same file is never uploaded twice in a session */
    private val uploadCache = ConcurrentHashMap<String, String>()
    /** Pending delayed "AI finished" (debounced so back-to-back tool calls don't flash the keyboard/overlay) */
    @Volatile private var unbusyJob: Job? = null
    /** Media returned by this session's tools, newest last ("the last image", "the previous video") */
    private val history = java.util.concurrent.ConcurrentLinkedDeque<FlowMediaFile>()
    private var appContext: Context? = null
    private var rpcCounter = 0
    /** Serializes everything that drives the Flow page UI (generation, uploads). */
    private val uiMutex = Mutex()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        tabLocked = prefs()?.getBoolean("tab_locked", false) ?: false
    }

    private fun prefs() = appContext?.getSharedPreferences("antigem_flow_automation", Context.MODE_PRIVATE)

    /** Locks/unlocks the Flow tab for the user. A user's temporary unlock is reset either way. */
    fun lockTab(locked: Boolean) {
        tabLocked = locked
        prefs()?.edit()?.putBoolean("tab_locked", locked)?.apply()
        BrowserSessionManager.instance.tabs.toList().filter { isFlowUrl(it.url) }.forEach { syncTab(it) }
    }

    /** Applies the current lock/busy state to a Flow tab. */
    fun syncTab(tab: BrowserTabSession) {
        tab.aiLocked = tabLocked
        tab.aiBusy = isBusy
        if (!tabLocked) tab.userUnlocked = false
    }

    /** Marks the Flow page as driven by AI for the duration of [block]: the tab shows the busy overlay. */
    private suspend fun <T> busy(block: suspend () -> T): T {
        if (busyCount.incrementAndGet() == 1) markBusy(true)
        try {
            return block()
        } finally {
            if (busyCount.decrementAndGet() == 0) markBusy(false)
        }
    }

    private fun markBusy(value: Boolean) {
        if (value) {
            unbusyJob?.cancel()
            unbusyJob = null
            if (isBusy) return
            isBusy = true
            findFlowTab()?.let { tab ->
                syncTab(tab)
                BrowserSessionManager.instance.hideKeyboard(tab)
            }
        } else {
            unbusyJob?.cancel()
            unbusyJob = scope.launch {
                delay(1500)
                if (busyCount.get() > 0) return@launch
                val tab = findFlowTab()
                if (tab != null) {
                    // Drop the page's input focus first, so unblocking input doesn't pop the keyboard up
                    runCatching { jsValue(tab.id, "s.blurAll()") }
                    runCatching { jsValue(tab.id, "s.freezeZoom(false)") }
                    BrowserSessionManager.instance.releaseFocus(tab)
                }
                isBusy = false
                tab?.let { syncTab(it) }
            }
        }
    }

    fun mediaDir(): File? = appContext?.let { File(LocalEnvironmentManager.getHomeDir(it), "flow-media") }

    // ==========================================
    // JOB QUEUE
    // ==========================================

    @Synchronized
    private fun ensureWorker() {
        if (workerStarted) return
        workerStarted = true
        // Jobs are submitted one after another without waiting for results: the next prompt is sent as soon as
        // the previous request has left the page; results are awaited in parallel (max MAX_IN_FLIGHT at once).
        scope.launch {
            for (job in queue) {
                if (job.isFinished) continue
                inFlight.acquire()
                if (job.isFinished) {
                    inFlight.release()
                    continue
                }
                val submitted = CompletableDeferred<Unit>()
                val worker = scope.launch {
                    try {
                        runJob(job, submitted)
                    } finally {
                        submitted.complete(Unit)
                        inFlight.release()
                        workers.remove(job.id)
                        if (!job.isFinished) {
                            job.status = "cancelled"
                            job.stage = "cancelled"
                        }
                    }
                }
                workers[job.id] = worker
                submitted.await()
            }
        }
    }

    fun submit(request: FlowRequest): FlowJob {
        ensureWorker()
        val job = FlowJob(java.util.UUID.randomUUID().toString().take(8), request)
        jobs[job.id] = job
        pruneJobs()
        queue.trySend(job)
        return job
    }

    suspend fun awaitJob(jobId: String, waitMs: Long): FlowJob? {
        val job = jobs[jobId] ?: return null
        withTimeoutOrNull(waitMs) {
            while (!job.isFinished) delay(500)
        }
        return job
    }

    /** Stops waiting for queued/running jobs. A generation Flow already accepted keeps running in Flow. */
    fun cancel(jobId: String?): Int {
        val targets = jobs.values.filter { !it.isFinished && (jobId.isNullOrBlank() || it.id == jobId) }
        targets.forEach { job ->
            job.status = "cancelled"
            job.stage = "cancelled"
            workers[job.id]?.cancel()
        }
        return targets.size
    }

    private fun pruneJobs() {
        if (jobs.size <= MAX_JOBS_KEPT) return
        jobs.values.filter { it.isFinished }
            .sortedBy { it.createdAt }
            .take(jobs.size - MAX_JOBS_KEPT)
            .forEach { jobs.remove(it.id) }
    }

    // ==========================================
    // READ & MANAGE (Flow RPCs from inside the page, no reCAPTCHA)
    // ==========================================

    suspend fun status(): JSONObject {
        val res = JSONObject()
        res.put("queued_jobs", jobs.values.count { it.status == "queued" })
        jobs.values.filter { it.status == "running" }.takeIf { it.isNotEmpty() }?.let { running ->
            res.put("running_jobs", JSONArray().apply { running.forEach { put(it.toJsonObject()) } })
        }
        mediaDir()?.let { res.put("media_dir", it.absolutePath) }
        res.put("tab_locked", tabLocked)
        val login = checkLogin()
        res.put("flow_tab_open", findFlowTab() != null)
        for (key in login.keys()) res.put(key, login.get(key))
        return res
    }

    /**
     * Opens the Flow tab if needed and checks the user is signed in: not redirected to Google sign-in, Flow's
     * session token present, and a real credits call accepted. Never throws; reports logged_in false with a reason.
     */
    suspend fun checkLogin(): JSONObject {
        val res = JSONObject()
        val tabId = try {
            ensureFlowTab()
        } catch (e: Exception) {
            res.put("logged_in", false)
            res.put("reason", e.message)
            return res
        }
        val tab = findFlowTab()
        val ui = runCatching { readUi(tabId) }.getOrNull()
        if (tab?.url?.contains("accounts.google.com") == true || ui == null || !ui.optBoolean("ready")) {
            res.put("logged_in", false)
            res.put("reason", NOT_SIGNED_IN)
            return res
        }
        val credits = runCatching { (rpc(tabId, FlowPageScripts.RPC_CREDITS, JSONArray(), "credits") as? JSONObject)?.opt("credits") }
        if (credits.isFailure) {
            res.put("logged_in", false)
            res.put("reason", credits.exceptionOrNull()?.message)
            return res
        }
        res.put("logged_in", true)
        ui.optString("account").takeIf { it.isNotBlank() && it != "null" }?.let { res.put("account", it) }
        credits.getOrNull()?.let { res.put("credits", it) }
        res.put("url", tab?.url)
        projectIdOf(ui)?.let {
            res.put("project_id", it)
            // Only meaningful where the prompt bar exists (not on Flow's home page)
            res.put("agent_mode_on", ui.optBoolean("agentOn") || ui.optBoolean("agentInstructions"))
        }
        ui.optString("settingsText").takeIf { it.isNotBlank() && it != "null" }?.let { res.put("current_settings", it) }
        return res
    }

    /** Throws [NOT_SIGNED_IN] unless the user is signed in (used before every generation). */
    private suspend fun requireLogin(tabId: String) {
        val ui = readUi(tabId)
        if (requireTab(tabId).url.contains("accounts.google.com") || ui == null || !ui.optBoolean("ready")) throw Exception(NOT_SIGNED_IN)
        rpc(tabId, FlowPageScripts.RPC_CREDITS, JSONArray(), "credits")
    }

    suspend fun listModels(): Result<Any?> = runCatching {
        rpc(ensureFlowTab(), FlowPageScripts.RPC_MODELS, JSONArray(), "models")
    }

    suspend fun listProjects(limit: Int): Result<Any?> = runCatching {
        val args = JSONArray().apply {
            put("projects/*"); put(limit); put(JSONObject.NULL); put(JSONObject.NULL); put(JSONObject.NULL); put(JSONObject.NULL)
            put(JSONArray().apply { put(1) })
        }
        rpc(ensureFlowTab(), FlowPageScripts.RPC_PROJECTS, args, "projects")
    }

    /** Creates a project with a direct RPC (no reCAPTCHA needed) and optionally opens it in the Flow tab. */
    suspend fun createProject(title: String?, open: Boolean): Result<JSONObject> = runCatching {
        busy {
            val tabId = ensureFlowTab()
            val created = createProjectRpc(tabId, title)
            if (open) openProject(tabId, created.getString("projectId"))
            created
        }
    }

    suspend fun renameProject(projectId: String, title: String): Result<Any?> = runCatching {
        val args = JSONArray().apply {
            put("projects/$projectId")
            put(JSONArray().apply { put(title) })
            put(JSONArray().apply { put(JSONArray().apply { put("project_title") }) })
            put(JSONArray().apply { put(JSONObject.NULL); put(22) })
        }
        rpc(ensureFlowTab(), FlowPageScripts.RPC_RENAME_PROJECT, args, "renamed")
    }

    suspend fun openProject(projectId: String): Result<String> = runCatching {
        busy { openProject(ensureFlowTab(), projectId) }
        projectId
    }

    /** Current composer settings as shown on Flow's "Settings trigger" chip (no popover opened). */
    suspend fun currentSettings(): Result<String> = runCatching {
        readUi(ensureFlowTab())?.optString("settingsText")?.takeIf { it.isNotBlank() && it != "null" }
            ?: throw Exception("Flow's settings chip is not visible (no project open, or Agent mode is on)")
    }

    /** Lists media in a project (the open project when [projectId] is null), optionally filtered by [query]. */
    suspend fun listMedia(
        projectId: String?,
        query: String? = null,
        pendingOnly: Boolean = false,
        type: String? = null,
        includeTrashed: Boolean = false
    ): Result<JSONArray> = runCatching {
        val tabId = ensureFlowTab()
        val pid = projectId ?: readUi(tabId)?.let { projectIdOf(it) }
            ?: throw Exception("No project is open in the Flow tab; pass project_id (see flow_list_projects)")
        val all = projectContents(tabId, pid)
        val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val out = JSONArray()
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            if (pendingOnly && !item.optBoolean("pending")) continue
            if (!includeTrashed && item.optBoolean("trashed")) continue
            if (type != null && item.optString("type") != type) continue
            if (q != null && !item.optString("title").lowercase().contains(q) && !item.optString("prompt").lowercase().contains(q)) continue
            out.put(item)
        }
        out
    }

    /** Video generation status via jwpduf; downloads finished videos once when [download] is set. */
    suspend fun videoStatus(mediaIds: List<String>, download: Boolean): Result<JSONArray> = runCatching {
        val tabId = ensureFlowTab()
        val statuses = videoStatuses(tabId, mediaIds)
        val out = JSONArray()
        for (id in mediaIds) {
            val code = statuses[id]
            val state = when (code) {
                VIDEO_DONE -> "done"
                in VIDEO_PENDING -> "pending"
                null -> "unknown"
                else -> "failed"
            }
            out.put(JSONObject().apply {
                put("media_id", id)
                put("status", state)
                code?.let { put("status_code", it) }
                if (state == "done" && download) {
                    runCatching { getMediaFile(id).getOrThrow() }
                        .onSuccess { put("path", it.absolutePath) }
                        .onFailure { put("download_error", it.message) }
                }
            })
        }
        out
    }

    fun cachedFile(mediaId: String): File? =
        mediaDir()?.listFiles()?.firstOrNull { it.name.startsWith("flow_$mediaId.") && !it.name.endsWith(".part") && it.length() > 0 }

    /** Returns the local file for a media item, downloading it once (fresh signed URL via GetMedia) if needed. */
    suspend fun getMediaFile(mediaId: String): Result<File> = runCatching {
        cachedFile(mediaId) ?: run {
            val media = getMedia(ensureFlowTab(), mediaId)
            val url = media.optString("url").takeIf { it.startsWith("https://") }
                ?: throw Exception("Media $mediaId has no download URL yet (still generating?)")
            download(mediaId, url, media.optString("type"))
        }
    }

    // ==========================================
    // GENERATION (real UI submit → Send Guard checks the request → read the page's response)
    // ==========================================

    /** A request the page has sent: [seq] identifies it in the page watcher. [dryRun]: captured and blocked on purpose. */
    private class Submission(val tabId: String, val seq: Int, val rpc: String, val sent: JSONObject?, val dryRun: Boolean = false)

    private class GuardOutcome(val submission: Submission?, val problems: List<String>)

    private suspend fun runJob(job: FlowJob, submitted: CompletableDeferred<Unit>) {
        job.status = "running"
        try {
            val sub = try {
                uiMutex.withLock {
                    busy {
                        try {
                            submitJob(job)
                        } catch (e: Exception) {
                            // Don't leave references or text behind for the next request
                            if (e !is CancellationException) findFlowTab()?.let { tab -> runCatching { clearPrompt(tab.id) } }
                            throw e
                        }
                    }
                }
            } finally {
                submitted.complete(Unit)
            }
            if (sub.dryRun) {
                job.sent = sub.sent
                job.status = "done"
                job.stage = if (sub.sent?.optBoolean("guard_ok", true) == false) {
                    "dry run: blocked before sending (no credits used) — the guard found problems, see request_sent.guard_problems"
                } else {
                    "dry run: the request was captured, passed the guard, and was blocked before sending (no credits used)"
                }
                return
            }
            val files = when (job.request.action) {
                FlowAction.UPSCALE_IMAGE -> runUpscale(job, sub)
                FlowAction.EDIT_IMAGE -> runImage(job, sub)
                FlowAction.EDIT_VIDEO, FlowAction.EXTEND_VIDEO, FlowAction.UPSCALE_VIDEO -> runVideo(job, sub)
                FlowAction.GENERATE -> if (job.request.isVideo) runVideo(job, sub) else runImage(job, sub)
            }
            if (job.status == "running") {
                job.files = files
                job.status = "done"
                job.stage = "done"
                rememberMedia(files)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Flow job ${job.id} failed", e)
            if (job.status == "running") {
                job.error = e.message ?: e.toString()
                job.status = "failed"
                job.stage = "failed"
            }
        }
    }

    private suspend fun submitJob(job: FlowJob): Submission = when (job.request.action) {
        FlowAction.GENERATE -> submitGenerate(job)
        FlowAction.EDIT_IMAGE -> submitEditImage(job)
        FlowAction.EDIT_VIDEO -> submitEdit(job)
        FlowAction.EXTEND_VIDEO -> submitExtend(job)
        FlowAction.UPSCALE_IMAGE -> submitUpscale(job)
        FlowAction.UPSCALE_VIDEO -> submitUpscaleVideo(job)
    }

    /**
     * Drives the page up to "Start generation" and returns once the request has been sent (not answered).
     * The Send Guard validates the request inside the page; a blocked request never reaches Google (no credits),
     * and is retried once with references re-attached (the page clears chips on submit).
     */
    private suspend fun submitGenerate(job: FlowJob): Submission {
        val req = job.request
        job.stage = "opening Flow"
        val tabId = ensureFlowTab()
        requireLogin(tabId)
        job.stage = "opening project"
        val projectId = ensureProject(tabId, req)
        job.projectId = projectId
        ensureAgentModeOff(tabId)

        // Local files are uploaded first (through Flow's own picker, which is then closed), then referenced by their real ids
        val uploaded = LinkedHashMap<String, String>()
        (req.references + listOfNotNull(req.startFrame, req.endFrame)).filter { isLocalPath(it) }.distinct().forEach { path ->
            val file = resolveLocalFile(path) ?: throw Exception("File not found or not readable: $path")
            job.stage = "uploading ${file.name}"
            uploaded[path] = uploadFile(tabId, file)
        }
        fun resolve(value: String) = uploaded[value] ?: value
        val refIds = req.references.map { resolve(it) }
        val startId = req.startFrame?.let { resolve(it) }
        val endId = req.endFrame?.let { resolve(it) }
        // Every reference must exist on the server (a temporary client id would silently be ignored by Flow)
        (refIds + listOfNotNull(startId, endId)).distinct().forEach { id ->
            runCatching { getMedia(tabId, id) }.onFailure { throw Exception("Reference media $id does not exist in Flow (${it.message})") }
        }
        val expectedChips = listOfNotNull(startId, endId) + refIds
        val expectation = generationExpectation(req, refIds, startId, endId)
        // Spec §7.3b: attach through the grid tile menu (Animate = start frame, Add to prompt = end frame / reference)
        val attachList = buildList {
            startId?.let { add(Triple(it, "Animate", "Start")) }
            endId?.let { add(Triple(it, "Add to prompt", "End")) }
            refIds.forEach { add(Triple(it, "Add to prompt", null)) }
        }
        val tags = if (attachList.isNotEmpty()) {
            job.stage = "preparing references"
            tagTiles(tabId, attachList.map { it.first }.distinct())
        } else emptyMap()

        var reserved = 0
        try {
            var lastProblems = emptyList<String>()
            for (attempt in 1..2) {
                job.stage = if (attempt == 1) "clearing prompt" else "retrying (the guard blocked: ${lastProblems.joinToString("; ")})"
                clearPrompt(tabId)
                job.stage = "applying settings"
                val cost = applySettings(tabId, req)
                job.cost = cost
                if (attempt == 1 && cost != null && req.budget != null && !req.dryRun) {
                    if (!req.budget.tryReserve(cost)) {
                        throw Exception("Skipped: this would use $cost credits, over max_credits=${req.budget.limit} for this call (${req.budget.used} already used). Nothing was generated.")
                    }
                    reserved = cost
                }
                job.stage = "entering prompt"
                enterPrompt(tabId, req.prompt)
                attachList.forEachIndexed { i, (id, menuItem, slot) ->
                    job.stage = "attaching ${slot?.lowercase()?.let { "$it frame" } ?: "reference"} ${i + 1}/${attachList.size}"
                    attachViaTile(tabId, id, menuItem, slot, tags[id]?.tag)
                }
                if (attachList.isNotEmpty()) {
                    verifyChips(tabId, expectedChips)
                    // "Animate" switches the bar to Video → Frames and may change settings: re-check them
                    job.stage = "re-checking settings"
                    applySettings(tabId, req)
                    ensurePromptText(tabId, req.prompt)
                }
                verifyChips(tabId, expectedChips)
                job.stage = "submitting"
                val outcome = submitThroughGuard(tabId, expectation) { tapStart(tabId) }
                outcome.submission?.let { sub ->
                    if (!sub.dryRun) reserved = 0 // credits are spent from here on
                    return sub
                }
                lastProblems = outcome.problems
                Log.w(TAG, "Send Guard blocked job ${job.id} (attempt $attempt): $lastProblems")
            }
            throw Exception("Blocked before sending, no credits used: ${lastProblems.joinToString("; ")}")
        } finally {
            if (reserved > 0) req.budget?.release(reserved)
            untagTiles(tabId, tags)
        }
    }

    /** Edit a whole video with a prompt in Flow's single-clip editor (Omni edit, jIps6). */
    private suspend fun submitEdit(job: FlowJob): Submission {
        val req = job.request
        val mediaId = req.targetMediaId ?: throw Exception("media_id is required")
        job.stage = "opening Flow"
        val tabId = ensureFlowTab()
        requireLogin(tabId)
        val media = getMedia(tabId, mediaId)
        if (media.optString("type") != "video") throw Exception("Media $mediaId is not a video")
        val projectId = media.optString("projectId")
        val workflowId = media.optString("workflowId").takeIf { it.isNotBlank() && it != "null" }
            ?: throw Exception("Could not find the workflow of video $mediaId")
        job.projectId = projectId
        job.stage = "opening the video editor"
        openPage(tabId, "/project/$projectId/edit/$workflowId")
        ensureAgentModeOff(tabId)
        job.stage = "entering prompt"
        enterPrompt(tabId, req.prompt)
        val expectation = JSONObject().apply {
            put("rpcs", JSONArray(listOf(FlowPageScripts.RPC_EDIT_VIDEO)))
            put("mediaId", mediaId)
            if (req.dryRun) put("dryRun", true)
        }
        job.stage = "submitting"
        val outcome = submitThroughGuard(tabId, expectation) { tapStart(tabId) }
        return outcome.submission ?: throw Exception("Blocked before sending, no credits used: ${outcome.problems.joinToString("; ")}")
    }

    /** Upscale an image through its grid tile menu (Download ▸ 2K/4K Upscaled, SPrCad). */
    private suspend fun submitUpscale(job: FlowJob): Submission {
        val req = job.request
        val mediaId = req.targetMediaId ?: throw Exception("media_id is required")
        job.stage = "opening Flow"
        val tabId = ensureFlowTab()
        requireLogin(tabId)
        val media = getMedia(tabId, mediaId)
        if (media.optString("type") == "video") throw Exception("Media $mediaId is a video; use flow_upscale_video")
        val projectId = media.optString("projectId")
        job.projectId = projectId
        openProject(tabId, projectId)
        val tags = tagTiles(tabId, listOf(mediaId))
        try {
            ensureAgentModeOff(tabId)
            val target = "${req.upscaleTo} Upscaled"
            val expectation = JSONObject().apply {
                put("rpcs", JSONArray(listOf(FlowPageScripts.RPC_UPSCALE_IMAGE)))
                put("mediaId", mediaId)
                if (req.dryRun) put("dryRun", true)
            }
            job.stage = "submitting"
            val outcome = submitThroughGuard(tabId, expectation) {
                tileAction(tabId, tags.getValue(mediaId).tag, listOf("Download", target), "Flow offers no \"$target\" for this image (4K may need a Nano Banana Pro image)")
            }
            return outcome.submission ?: throw Exception("Blocked before sending, no credits used: ${outcome.problems.joinToString("; ")}")
        } finally {
            closeMenus(tabId)
            clearGridSearch(tabId)
            untagTiles(tabId, tags)
        }
    }

    /** Upscale a video through its grid tile menu (Download ▸ 720p/1080p/4K, p0UkFb); async like any video. */
    private suspend fun submitUpscaleVideo(job: FlowJob): Submission {
        val req = job.request
        val mediaId = req.targetMediaId ?: throw Exception("media_id is required")
        job.stage = "opening Flow"
        val tabId = ensureFlowTab()
        requireLogin(tabId)
        val media = getMedia(tabId, mediaId)
        if (media.optString("type") != "video") throw Exception("Media $mediaId is not a video; use flow_upscale_image")
        val projectId = media.optString("projectId")
        job.projectId = projectId
        openProject(tabId, projectId)
        job.knownMedia = projectContents(tabId, projectId).mediaIds()
        val tags = tagTiles(tabId, listOf(mediaId))
        try {
            ensureAgentModeOff(tabId)
            val expectation = JSONObject().apply {
                put("rpcs", JSONArray(listOf(FlowPageScripts.RPC_UPSCALE_VIDEO)))
                put("mediaId", mediaId)
                if (req.dryRun) put("dryRun", true)
            }
            job.stage = "submitting"
            val outcome = submitThroughGuard(tabId, expectation) {
                tileAction(tabId, tags.getValue(mediaId).tag, listOf("Download", req.upscaleTo), "Flow offers no ${req.upscaleTo} upscale for this video")
            }
            return outcome.submission ?: throw Exception("Blocked before sending, no credits used: ${outcome.problems.joinToString("; ")}")
        } finally {
            closeMenus(tabId)
            clearGridSearch(tabId)
            untagTiles(tabId, tags)
        }
    }

    /**
     * Spec §7.3d: a new version of one image in its own tile. Opens the image editor, selects the requested
     * version from history if it isn't the latest, applies model/aspect if given, and submits the edit.
     */
    private suspend fun submitEditImage(job: FlowJob): Submission {
        val req = job.request
        val mediaId = req.targetMediaId ?: throw Exception("media_id is required")
        job.stage = "opening Flow"
        val tabId = ensureFlowTab()
        requireLogin(tabId)
        val media = getMedia(tabId, mediaId)
        if (media.optString("type") == "video") throw Exception("Media $mediaId is a video; use flow_edit_video")
        val projectId = media.optString("projectId")
        val workflowId = media.optString("workflowId").takeIf { it.isNotBlank() && it != "null" }
            ?: throw Exception("Could not find the tile of image $mediaId")
        job.projectId = projectId
        val extras = req.references
        val tags = if (extras.isNotEmpty()) tagTiles(tabId, extras) else emptyMap()
        try {
            job.stage = "opening the image editor"
            openPage(tabId, "/project/$projectId/edit/$workflowId", forceReload = extras.isNotEmpty())
            ensureAgentModeOff(tabId)
            val versions = projectContents(tabId, projectId).let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .filter { it.optString("workflowId") == workflowId }
                    .sortedBy { it.optLong("created") }
                    .map { it.optString("mediaId") }
            }
            if (versions.isNotEmpty() && versions.last() != mediaId) {
                job.stage = "selecting the image version"
                selectHistoryVersion(tabId, mediaId)
            }
            if (req.model.isNotBlank() || req.aspectRatio.isNotBlank()) {
                job.stage = "applying settings"
                openSettings(tabId)
                try {
                    if (req.model.isNotBlank()) pickModel(tabId, req.model)
                    if (req.aspectRatio.isNotBlank()) pickRadio(tabId, req.aspectRatio, "aspect ratio")
                } finally {
                    closeSettings(tabId)
                }
            }
            clearPrompt(tabId)
            job.stage = "entering prompt"
            enterPrompt(tabId, req.prompt)
            extras.forEachIndexed { i, id ->
                job.stage = "attaching reference ${i + 1}/${extras.size}"
                attachById(tabId, id, null, tags[id]?.tag ?: id)
            }
            ensurePromptText(tabId, req.prompt)
            val expectation = JSONObject().apply {
                put("rpcs", JSONArray(listOf(FlowPageScripts.RPC_GENERATE_IMAGES)))
                put("count", 1)
                put("refs", JSONArray(listOf(mediaId) + extras))
                put("workflowId", workflowId)
                IMAGE_MODEL_KEYS[req.model]?.let { put("modelKey", it) }
                IMAGE_ASPECT_ENUM[req.aspectRatio]?.let { put("aspect", it) }
                if (req.dryRun) put("dryRun", true)
            }
            job.stage = "submitting"
            val outcome = submitThroughGuard(tabId, expectation) { tapStart(tabId) }
            return outcome.submission ?: throw Exception("Blocked before sending, no credits used: ${outcome.problems.joinToString("; ")}")
        } finally {
            untagTiles(tabId, tags)
        }
    }

    /** Spec §8.0 Extend (Veo clips only): continues a clip with "What happens next?" in the video editor (fZytfe). */
    private suspend fun submitExtend(job: FlowJob): Submission {
        val req = job.request
        val mediaId = req.targetMediaId ?: throw Exception("media_id is required")
        job.stage = "opening Flow"
        val tabId = ensureFlowTab()
        requireLogin(tabId)
        val media = getMedia(tabId, mediaId)
        if (media.optString("type") != "video") throw Exception("Media $mediaId is not a video")
        if (!media.optString("modelKey").startsWith("veo")) {
            throw Exception("Extend only works on Veo clips (this one is ${media.optString("modelKey").ifBlank { "not Veo" }}). Continue it instead: flow_extract_frame (upload: true) → flow_generate_video with start_frame")
        }
        val projectId = media.optString("projectId")
        val workflowId = media.optString("workflowId").takeIf { it.isNotBlank() && it != "null" }
            ?: throw Exception("Could not find the tile of video $mediaId")
        job.projectId = projectId
        job.stage = "opening the video editor"
        openPage(tabId, "/project/$projectId/edit/$workflowId")
        ensureAgentModeOff(tabId)
        job.stage = "opening extend mode"
        // The timeline (with its "Add clip" button) renders a moment after the editor's prompt box
        if (waitFor(8_000, 400) { jsObject(tabId, "s.clickAria('Add clip')")?.takeIf { it.optBoolean("ok") } } == null) {
            throw Exception("Flow's \"Add clip\" button was not found in the video editor")
        }
        val extend = waitFor(3_000) { jsObject(tabId, "s.menuClick('Extend')")?.takeIf { it.optBoolean("ok") || it.optBoolean("disabled") } }
            ?: throw Exception("Flow's Add clip menu has no Extend entry")
        if (extend.optBoolean("disabled")) throw Exception("Extend is disabled for this clip in Flow")
        if (!waitUntil(5_000) { readUi(tabId)?.optBoolean("prompt") == true }) throw Exception("Flow's extend prompt did not appear")
        job.stage = "entering prompt"
        enterPrompt(tabId, req.prompt)
        val expectation = JSONObject().apply {
            put("rpcs", JSONArray(listOf(FlowPageScripts.RPC_EXTEND_VIDEO)))
            put("mediaId", mediaId)
            if (req.dryRun) put("dryRun", true)
        }
        job.stage = "submitting"
        val outcome = submitThroughGuard(tabId, expectation) { tapStart(tabId) }
        return outcome.submission ?: throw Exception("Blocked before sending, no credits used: ${outcome.problems.joinToString("; ")}")
    }

    /** Taps Flow's "Start generation" button (a real tap: image submits ignore script clicks). */
    private suspend fun tapStart(tabId: String) {
        val start = jsObject(tabId, "s.aria('Start generation')")?.takeIf { it.optBoolean("found") }
            ?: throw Exception("Flow's \"Start generation\" button was not found. Visible controls: ${visibleControls(tabId)}")
        if (start.optBoolean("disabled")) throw Exception("Flow's \"Start generation\" button is disabled (prompt not accepted?)")
        tap(tabId, start)
    }

    /** Arms the Send Guard, runs [trigger] (tap Start / click a menu item) and waits until the page has sent (or the guard blocked) the request. */
    private suspend fun submitThroughGuard(tabId: String, expectation: JSONObject, trigger: suspend () -> Unit): GuardOutcome {
        val mark = jsObject(tabId, "s.capMark()")?.optInt("n") ?: 0
        jsValue(tabId, "s.expect(${q(expectation.toString())})")
        try {
            trigger()
        } catch (e: Exception) {
            jsValue(tabId, "s.clearExpect()")
            throw e
        }
        val request = waitFor(30_000, 300) { jsObject(tabId, "s.capSent($mark)")?.takeIf { it.optBoolean("found") } }
        if (request == null) {
            jsValue(tabId, "s.clearExpect()")
            throw Exception("Flow did not send the request after tapping start. Check the Flow tab in the AntiGem browser.")
        }
        val problems = request.optJSONArray("problems")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val dryRun = problems.firstOrNull() == "dry-run"
        if (request.optBoolean("blocked") && !dryRun) return GuardOutcome(null, problems)
        val sent = request.optJSONObject("sent")?.apply {
            if (dryRun) {
                put("guard_ok", problems.size == 1)
                if (problems.size > 1) put("guard_problems", JSONArray(problems.drop(1)))
            }
        }
        val sub = Submission(tabId, request.optInt("seq"), request.optString("rpc"), sent, dryRun)
        if (!dryRun) pageRepliesPending.incrementAndGet()
        return GuardOutcome(sub, emptyList())
    }

    /** What the Send Guard requires of the generate request (spec §1.3 / §4.2). */
    private fun generationExpectation(req: FlowRequest, refIds: List<String>, startId: String?, endId: String?): JSONObject = JSONObject().apply {
        if (req.dryRun) put("dryRun", true)
        put("count", req.count)
        if (!req.isVideo) {
            put("rpcs", JSONArray(listOf(FlowPageScripts.RPC_GENERATE_IMAGES)))
            IMAGE_MODEL_KEYS[req.model]?.let { put("modelKey", it) }
            IMAGE_ASPECT_ENUM[req.aspectRatio]?.let { put("aspect", it) }
            put("refs", JSONArray(refIds))
        } else {
            put("rpcs", JSONArray(listOf(expectedVideoRpc(req))))
            VIDEO_ASPECT_ENUM[req.aspectRatio]?.let { put("aspect", it) }
            val must = mutableListOf<String>()
            val mustNot = mutableListOf<String>()
            when {
                startId != null && endId != null -> must += "first_last|_fl\$|interpolation"
                startId != null -> { must += "i2v"; mustNot += "first_last|_fl\$|interpolation" }
                refIds.isNotEmpty() -> must += "r2v"
                else -> must += "t2v"
            }
            val model = req.model.lowercase()
            when {
                isOmni(req.model) -> {
                    must += "^(abra|omni)_"
                    req.durationSec?.let { must += "_${it}s" }
                    if (req.resolution == "360p") must += "_360p\$" else mustNot += "_360p"
                }
                "lite" in model -> must += "^veo_3_1_.*lite"
                else -> {
                    if ("fast" in model) must += "^veo_3_1_.*fast" else { must += "^veo_3_1_"; mustNot += "lite|fast" }
                    if (req.aspectRatio == "9:16") must += "portrait" else mustNot += "portrait"
                }
            }
            put("keyMust", JSONArray(must))
            put("keyMustNot", JSONArray(mustNot))
            if (refIds.isNotEmpty()) put("refs", JSONArray(refIds))
            startId?.let { put("start", it) }
            endId?.let { put("end", it) }
        }
    }

    /** Waits for the page's reply to request [sub]; always clears this job's pending-reply mark. */
    private suspend fun waitForReply(sub: Submission, timeoutMs: Long, what: String): JSONObject {
        try {
            var reply: JSONObject? = null
            withTimeoutOrNull(timeoutMs) {
                while (reply == null) {
                    delay(1000)
                    val res = jsObject(sub.tabId, "s.capResult(${sub.seq})") ?: continue
                    if (!res.optBoolean("found")) {
                        throw Exception("Flow's reply to the $what request was lost because the Flow page reloaded. The result may still appear in the project — check flow_list_media.")
                    }
                    if (res.optBoolean("done")) reply = res
                }
            }
            return reply ?: throw Exception("Flow did not answer the $what request within ${timeoutMs / 60_000.0} min. Check the Flow tab in the AntiGem browser.")
        } finally {
            pageRepliesPending.decrementAndGet()
        }
    }

    private suspend fun runImage(job: FlowJob, sub: Submission): List<FlowMediaFile> {
        job.stage = "generating image"
        val reply = waitForReply(sub, 180_000, "image")
        reply.optString("error").takeIf { it.isNotBlank() && it != "null" }?.let { throw Exception(it) }
        val images = reply.optJSONArray("images") ?: JSONArray()
        if (images.length() == 0) throw Exception("Flow's response contained no images")
        job.mediaIds = (0 until images.length()).mapNotNull { images.optJSONObject(it)?.optString("mediaId") }
        job.stage = "saving results"
        return (0 until images.length()).mapNotNull { i ->
            val img = images.optJSONObject(i) ?: return@mapNotNull null
            val mediaId = img.optString("mediaId")
            val url = img.optString("url")
            val file = if (url.startsWith("https://")) download(mediaId, url, "image") else getMediaFile(mediaId).getOrThrow()
            FlowMediaFile(mediaId, "image", file.absolutePath, img.optString("title"))
        }
    }

    private suspend fun runUpscale(job: FlowJob, sub: Submission): List<FlowMediaFile> {
        job.stage = "upscaling image"
        val reply = waitForReply(sub, 240_000, "upscale")
        reply.optString("error").takeIf { it.isNotBlank() && it != "null" }?.let { throw Exception(it) }
        val newId = reply.optString("mediaId").takeIf { it.isNotBlank() && it != "null" }
            ?: throw Exception("Flow's upscale response contained no media id")
        job.mediaIds = listOf(newId)
        job.stage = "saving results"
        val file = getMediaFile(newId).getOrThrow()
        return listOf(FlowMediaFile(newId, "image", file.absolutePath, "${job.request.upscaleTo} upscale"))
    }

    /** Videos (and edits) are async: read the media ids from the submit reply, poll jwpduf, then fetch the MP4 via GetMedia. */
    private suspend fun runVideo(job: FlowJob, sub: Submission): List<FlowMediaFile> {
        val tabId = sub.tabId
        job.stage = "submitting video"
        val reply = waitForReply(sub, 60_000, "video")
        reply.optString("error").takeIf { it.isNotBlank() && it != "null" }?.let { throw Exception(it) }
        var ids = reply.optJSONArray("mediaIds")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
        if (ids.isEmpty() && job.request.action == FlowAction.UPSCALE_VIDEO && job.projectId != null) {
            // The upscale reply doesn't always name the new copy: find the new video in the project
            ids = waitFor(120_000, 5_000) {
                projectContents(tabId, job.projectId!!).let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                        .filter { it.optString("type") == "video" && it.optString("mediaId") !in job.knownMedia }
                        .map { it.optString("mediaId") }
                }.takeIf { it.isNotEmpty() }
            }.orEmpty()
        }
        if (ids.isEmpty()) throw Exception("Flow's video response contained no media ids")
        job.mediaIds = ids

        val deadline = System.currentTimeMillis() + 20 * 60_000L
        val done = LinkedHashMap<String, File>()
        while (System.currentTimeMillis() < deadline && done.size < ids.size) {
            delay(5000)
            val statuses = runCatching { videoStatuses(tabId, ids) }.getOrDefault(emptyMap())
            for (id in ids) {
                if (id in done) continue
                when (val code = statuses[id]) {
                    VIDEO_DONE -> done[id] = getMediaFile(id).getOrThrow()
                    null, in VIDEO_PENDING -> Unit
                    else -> throw Exception("Flow reports video $id failed (status $code)")
                }
            }
            job.stage = "generating video (${done.size}/${ids.size} ready)"
        }
        if (done.size < ids.size) {
            throw Exception("Videos were not finished after 20 min (${done.size}/${ids.size} ready). Use flow_video_status with media_ids $ids to check later.")
        }
        return done.map { (id, file) -> FlowMediaFile(id, "video", file.absolutePath) }
    }

    // ==========================================
    // TAB / PROJECT / SETTINGS
    // ==========================================

    private fun findFlowTab(): BrowserTabSession? =
        BrowserSessionManager.instance.tabs.toList().firstOrNull { isFlowUrl(it.url) }

    private fun requireTab(tabId: String): BrowserTabSession =
        BrowserSessionManager.instance.tabs.toList().find { it.id == tabId } ?: throw Exception("The Flow tab was closed")

    private fun projectIdOf(ui: JSONObject): String? = ui.optString("projectId").takeIf { it.isNotBlank() && it != "null" }

    /** Loads Flow (desktop mode) into an empty tab, such as the browser's starting "New Tab", instead of adding a tab. */
    private suspend fun reuseEmptyTab(): BrowserTabSession? {
        val bManager = BrowserSessionManager.instance
        val empty = bManager.tabs.toList().firstOrNull { it.url.isBlank() || it.url == "about:blank" } ?: return null
        withContext(Dispatchers.Main) {
            bManager.setDesktopMode(empty, true)
            bManager.loadUrlInTab(empty, FLOW_URL)
        }
        return empty
    }

    private suspend fun ensureFlowTab(): String {
        val bManager = BrowserSessionManager.instance
        val tab = findFlowTab()
            ?: reuseEmptyTab()
            ?: withContext(Dispatchers.Main) { bManager.addNewTab(FLOW_URL, activate = false, desktopMode = true) }
        syncTab(tab)
        when {
            !tab.isDesktopMode -> bManager.setDesktopMode(tab, true)
            tab.webView == null -> withContext(Dispatchers.Main) { bManager.loadUrlInTab(tab, tab.url) }
        }
        bManager.waitForPageLoad(tab.id, 30_000)
        if (waitUntil(30_000) { readUi(tab.id)?.optBoolean("ready") == true }) {
            bManager.zoomOutFully(tab)
            return tab.id
        }
        if (readUi(tab.id)?.optBoolean("signIn") == true || tab.url.contains("accounts.google.com")) throw Exception(NOT_SIGNED_IN)
        // Page stuck (crashed renderer, stale session, wrong page): reload Flow once ourselves
        withContext(Dispatchers.Main) { bManager.loadUrlInTab(tab, if (isFlowUrl(tab.url)) tab.url else FLOW_URL) }
        delay(1500)
        bManager.waitForPageLoad(tab.id, 30_000)
        if (!waitUntil(30_000) { readUi(tab.id)?.optBoolean("ready") == true }) {
            if (readUi(tab.id)?.optBoolean("signIn") == true || tab.url.contains("accounts.google.com")) throw Exception(NOT_SIGNED_IN)
            throw Exception("The Flow page did not finish loading, even after a reload. $NOT_SIGNED_IN")
        }
        bManager.zoomOutFully(tab)
        return tab.id
    }

    /** Navigates the Flow tab to [path] (e.g. a video editor) once no reply is pending on the current page. */
    private suspend fun openPage(tabId: String, path: String, forceReload: Boolean = false) {
        val bManager = BrowserSessionManager.instance
        if (forceReload || readUi(tabId)?.optString("path")?.trimEnd('/') != path) {
            if (!waitUntil(240_000, 1_000) { pageRepliesPending.get() <= 0 }) {
                throw Exception("Still waiting for earlier Flow generations to answer; try again shortly")
            }
            withContext(Dispatchers.Main) { bManager.loadUrlInTab(requireTab(tabId), FLOW_URL + path) }
            delay(1500)
            bManager.waitForPageLoad(tabId, 30_000)
        }
        if (!waitUntil(30_000) { readUi(tabId)?.let { it.optBoolean("ready") && it.optBoolean("prompt") } == true }) {
            throw Exception("Flow page $path did not open. Visible controls: ${visibleControls(tabId)}")
        }
    }

    private suspend fun openProject(tabId: String, projectId: String) {
        val bManager = BrowserSessionManager.instance
        // Generation needs the plain project page (not the video editor or a scene inside it)
        if (readUi(tabId)?.optString("path")?.trimEnd('/') != "/project/$projectId") {
            // Navigating would drop replies still expected on this page: let in-flight generations answer first
            if (!waitUntil(240_000, 1_000) { pageRepliesPending.get() <= 0 }) {
                throw Exception("Still waiting for earlier Flow generations to answer; try again shortly")
            }
            withContext(Dispatchers.Main) { bManager.loadUrlInTab(requireTab(tabId), "$FLOW_URL/project/$projectId") }
            delay(1500)
            bManager.waitForPageLoad(tabId, 30_000)
        }
        if (!waitUntil(30_000) { readUi(tabId)?.let { projectIdOf(it) == projectId && it.optBoolean("ready") && it.optBoolean("prompt") } == true }) {
            throw Exception("Flow project $projectId did not open. Visible controls: ${visibleControls(tabId)}")
        }
    }

    private suspend fun createProjectRpc(tabId: String, title: String?): JSONObject {
        val name = title?.takeIf { it.isNotBlank() } ?: SimpleDateFormat("MMM dd - HH:mm", Locale.US).format(Date())
        val args = JSONArray().apply {
            put("projects/*")
            put(JSONArray().apply { put(JSONObject.NULL); put(JSONArray().apply { put(name) }) })
            put(JSONArray().apply { put(JSONObject.NULL); put(22) })
        }
        val created = rpc(tabId, FlowPageScripts.RPC_CREATE_PROJECT, args, "created") as? JSONObject
        created?.optString("projectId")?.takeIf { it.isNotBlank() && it != "null" }
            ?: throw Exception("Flow did not return a new project id")
        return created
    }

    /** Generation always goes into the project open in the tab. */
    private suspend fun ensureProject(tabId: String, req: FlowRequest): String {
        val current = readUi(tabId)?.let { projectIdOf(it) }
        val target = when {
            req.projectId != null -> req.projectId
            req.newProject || current == null -> createProjectRpc(tabId, null).getString("projectId")
            else -> current
        }
        openProject(tabId, target)
        return target
    }

    private suspend fun ensureAgentModeOff(tabId: String) {
        val state = jsObject(tabId, "s.agentState()") ?: return
        if (!state.optBoolean("on") && !state.optBoolean("instructions") && !state.optBoolean("panel")) return
        jsValue(tabId, "s.agentOff()")
        val off = waitUntil(5_000, 300) {
            jsObject(tabId, "s.agentState()")?.let { !it.optBoolean("on") && !it.optBoolean("instructions") } == true
        }
        if (!off) {
            // Script click didn't take: try a real tap on the pill
            jsObject(tabId, "s.agentToggle()")?.takeIf { it.optBoolean("found") }?.let { tap(tabId, it) }
            if (!waitUntil(5_000, 300) { jsObject(tabId, "s.agentState()")?.let { !it.optBoolean("on") && !it.optBoolean("instructions") } == true }) {
                throw Exception("Could not turn off Flow's Agent mode. Turn it off in the Flow tab and try again.")
            }
        }
    }

    /**
     * Spec §6 settings procedure: open the popover (retrying, the trigger is flaky), set mode, sub-mode,
     * model (before resolution/duration), aspect, resolution, duration and count explicitly, read the cost,
     * then close. Returns the credit cost Flow shows, if any.
     */
    private suspend fun applySettings(tabId: String, req: FlowRequest): Int? {
        openSettings(tabId)
        try {
            pickRadio(tabId, if (req.isVideo) "Video" else "Image", "mode")
            if (req.isVideo) pickRadio(tabId, if (req.references.isNotEmpty()) "Ingredients" else "Frames", "video mode")
            pickModel(tabId, req.model)
            pickRadio(tabId, req.aspectRatio, "aspect ratio")
            if (req.isVideo && isOmni(req.model)) {
                req.resolution?.let { pickRadio(tabId, it, "resolution", optional = true) }
                req.durationSec?.let { pickRadio(tabId, "${it}s", "duration", optional = true) }
            }
            pickRadio(tabId, "x${req.count}", "output count")
            return jsObject(tabId, "s.cost()")?.takeIf { !it.isNull("cost") }?.optInt("cost")
        } finally {
            closeSettings(tabId)
        }
    }

    private suspend fun openSettings(tabId: String) {
        repeat(3) {
            if (readUi(tabId)?.optBoolean("settingsOpen") == true) return
            val trigger = jsObject(tabId, "s.aria('Settings trigger')")?.takeIf { it.optBoolean("found") }
                ?: throw Exception("Flow's \"Settings trigger\" was not found. Visible controls: ${visibleControls(tabId)}")
            tap(tabId, trigger)
            if (waitUntil(1_500, 250) { readUi(tabId)?.optBoolean("settingsOpen") == true }) return
        }
        throw Exception("Flow's settings popover did not open after 3 tries")
    }

    private suspend fun closeSettings(tabId: String) {
        repeat(3) {
            if (readUi(tabId)?.optBoolean("settingsOpen") != true) return
            jsValue(tabId, "s.escape()")
            BrowserSessionManager.instance.dispatchKey(tabId, KeyEvent.KEYCODE_ESCAPE)
            waitUntil(1_000, 250) { readUi(tabId)?.optBoolean("settingsOpen") != true }
        }
    }

    /** Taps the settings radio whose label ends with [label] (icon ligatures come first), unless already checked. */
    private suspend fun pickRadio(tabId: String, label: String, what: String, optional: Boolean = false) {
        val pattern = "(^|\\s)${reEscape(label)}( info)?\$"
        val radio = jsObject(tabId, "s.radio(${q(pattern)})")?.takeIf { it.optBoolean("found") }
        if (radio == null) {
            if (optional) return
            throw Exception("Flow has no $what option '$label'. Options: ${listText(tabId, "s.radios()")}")
        }
        if (radio.optBoolean("checked")) return
        tap(tabId, radio)
        if (!waitUntil(2_000, 250) { jsObject(tabId, "s.radio(${q(pattern)})")?.optBoolean("checked") == true }) {
            throw Exception("Flow did not select $what '$label'. Options: ${listText(tabId, "s.radios()")}")
        }
    }

    private suspend fun pickModel(tabId: String, model: String) {
        val button = jsObject(tabId, "s.modelButton()")?.takeIf { it.optBoolean("found") }
            ?: throw Exception("Flow's \"Select model family\" button was not found in the settings popover")
        if (modelMatches(button.optString("label"), model)) return
        tap(tabId, button)
        if (!waitUntil(2_000, 250) { readUi(tabId)?.optBoolean("menuOpen") == true }) {
            throw Exception("Flow's model menu did not open")
        }
        val item = jsObject(tabId, "s.menuItem(${q(model)})")?.takeIf { it.optBoolean("found") }
        if (item == null) {
            val offered = listText(tabId, "s.menuItems()")
            jsValue(tabId, "s.escape()")
            throw Exception("Flow has no model '$model'. Models offered: $offered")
        }
        tap(tabId, item)
        val applied = waitUntil(3_000, 250) {
            jsObject(tabId, "s.modelButton()")?.let { modelMatches(it.optString("label"), model) } == true
        }
        if (!applied) throw Exception("Flow did not switch to model '$model'")
    }

    /** "Nano Banana 2" must not count as "Nano Banana 2.1" / "Nano Banana 2 Lite" and vice versa. */
    private fun modelMatches(buttonText: String, model: String): Boolean {
        val text = buttonText.lowercase()
        val wanted = model.lowercase()
        val idx = text.indexOf(wanted)
        if (idx < 0) return false
        val rest = text.substring(idx + wanted.length).trimStart()
        return !(rest.startsWith(".") || rest.startsWith("lite") && !wanted.endsWith("lite"))
    }

    private suspend fun enterPrompt(tabId: String, prompt: String) {
        val box = jsObject(tabId, "s.findPrompt()")?.takeIf { it.optBoolean("found") }
            ?: throw Exception("Flow's prompt box was not found. Visible controls: ${visibleControls(tabId)}")
        tap(tabId, box)
        val res = jsObject(tabId, "s.setPrompt(${q(prompt)})")
        if (res?.optBoolean("ok") != true) {
            throw Exception("Flow's prompt box did not accept the text (${res?.optString("error")?.ifBlank { null } ?: "no response"})")
        }
        requireTab(tabId).let { BrowserSessionManager.instance.zoomOutFully(it) }
        delay(500)
    }

    // ==========================================
    // REFERENCE MEDIA (upload through Flow's picker, attach by media id, verify by chip id)
    // ==========================================

    /** A value the model passed as a reference: a local path (anything with a slash) or else a Flow media id. */
    fun isLocalPath(value: String): Boolean =
        value.startsWith("/") || value.startsWith("file://") || value.startsWith("~/") || value.contains('/')

    /**
     * Resolves a path given by the model to a readable file: absolute, file://, ~/..., relative to the Termux home,
     * /sdcard/..., or a path inside the Ubuntu (proot) environment.
     */
    fun resolveLocalFile(raw: String): File? {
        val ctx = appContext ?: return null
        var path = raw.trim().removePrefix("file://")
        if (path.contains('%')) path = runCatching { java.net.URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
        val home = LocalEnvironmentManager.getHomeDir(ctx)
        val candidates = mutableListOf<File>()
        when {
            path.startsWith("~/") -> candidates += File(home, path.removePrefix("~/"))
            !path.startsWith("/") -> candidates += File(home, path)
            else -> {
                candidates += File(path)
                if (path.startsWith("/sdcard/")) candidates += File("/storage/emulated/0/" + path.removePrefix("/sdcard/"))
                candidates += File(LocalEnvironmentManager.getUbuntuRootDir(ctx), path.removePrefix("/"))
            }
        }
        return candidates.firstOrNull { it.isFile && it.canRead() && it.length() > 0 }
    }

    private fun expectedVideoRpc(req: FlowRequest): String = when {
        req.startFrame != null && req.endFrame != null -> FlowPageScripts.RPC_VIDEO_START_END
        req.startFrame != null -> FlowPageScripts.RPC_VIDEO_START
        req.references.isNotEmpty() -> FlowPageScripts.RPC_VIDEO_REFERENCES
        else -> FlowPageScripts.RPC_VIDEO_TEXT
    }

    /** Uploads local files into the project open in the Flow tab; returns their new media ids (in order). */
    suspend fun uploadMedia(paths: List<String>, projectId: String?): Result<List<String>> = runCatching {
        val files = paths.map { resolveLocalFile(it) ?: throw Exception("File not found or not readable: $it") }
        uiMutex.withLock { busy {
            val tabId = ensureFlowTab()
            val current = readUi(tabId)?.let { projectIdOf(it) }
            when {
                projectId != null -> openProject(tabId, projectId)
                current == null -> openProject(tabId, createProjectRpc(tabId, null).getString("projectId"))
                else -> openProject(tabId, current)
            }
            files.map { uploadFile(tabId, it) }.also { ids -> ids.zip(files).forEach { (id, f) -> rememberMedia(listOf(FlowMediaFile(id, mediaTypeOf(f), f.absolutePath, f.name))) } }
        } }
    }

    private fun mediaTypeOf(file: File): String =
        if (file.extension.lowercase() in setOf("mp4", "m4v", "mov", "3gp", "avi", "webm")) "video" else "image"

    /**
     * Copies a file into the app's private cache under a clean name (so the browser can always read it), converting
     * image formats Flow doesn't accept to PNG.
     */
    private fun prepareUpload(file: File): File {
        val ctx = appContext ?: throw Exception("Flow automation is not initialized")
        val dir = File(ctx.cacheDir, "flow-upload/${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val ext = file.extension.lowercase()
        val base = file.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifBlank { "upload" }
        if (ext in UPLOAD_TYPES) {
            return File(dir, "$base.$ext").also { file.copyTo(it, overwrite = true) }
        }
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            ?: throw Exception("Flow can't take .$ext files (${file.name}). Supported: ${UPLOAD_TYPES.joinToString(" ")}")
        return File(dir, "$base.png").also { out ->
            out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    /**
     * Uploads one local file and returns its REAL media id (from the page's maseQ reply). Spec §7.3c: through the
     * top-bar "Add media menu → Upload" (fallback: the picker's "Upload media"), then closes the menu/picker —
     * attaching from that same picker session would give a broken temporary id. The same file (path, size, mtime)
     * is uploaded only once per session.
     */
    private suspend fun uploadFile(tabId: String, file: File): String {
        val cacheKey = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        uploadCache[cacheKey]?.let { cached ->
            if (runCatching { getMedia(tabId, cached) }.isSuccess) return cached
            uploadCache.remove(cacheKey)
        }
        val ctx = appContext ?: throw Exception("Flow automation is not initialized")
        val bManager = BrowserSessionManager.instance
        val prepared = prepareUpload(file)
        try {
            val mark = jsObject(tabId, "s.capMark()")?.optInt("n") ?: 0
            bManager.pendingChooserFiles = listOf(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", prepared))
            // The file chooser needs a user gesture, so both paths use real taps
            var delivered = false
            jsObject(tabId, "s.aria('Add media menu')")?.takeIf { it.optBoolean("found") }?.let { menuButton ->
                tap(tabId, menuButton)
                waitFor(3_000) { jsObject(tabId, "s.menuItem('Upload')")?.takeIf { it.optBoolean("found") } }?.let { item ->
                    tap(tabId, item)
                    delivered = waitUntil(5_000, 250) { bManager.pendingChooserFiles == null }
                }
                if (!delivered) closeMenus(tabId)
            }
            if (!delivered) {
                openPicker(tabId, null)
                val upload = jsObject(tabId, "s.pickerButton('upload\\\\s*media')")?.takeIf { it.optBoolean("found") }
                    ?: throw Exception("Flow's upload button was not found (Add media menu and picker)")
                tap(tabId, upload)
                delivered = waitUntil(5_000, 250) { bManager.pendingChooserFiles == null }
            }
            if (!delivered) throw Exception("Flow did not open its file chooser for the upload")
            val mediaId = waitFor(180_000, 1000) {
                val res = jsObject(tabId, "s.uploadsAfter($mark)")
                res?.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let { throw Exception("Flow upload of ${file.name} failed: ${it.optString(0)}") }
                res?.optJSONArray("ids")?.takeIf { it.length() > 0 }?.optString(0)
            } ?: throw Exception("Flow did not finish uploading ${file.name} within 3 min")
            // Must be a real server id (a temporary client id would be NOT_FOUND)
            runCatching { getMedia(tabId, mediaId) }.onFailure { throw Exception("Flow's upload of ${file.name} returned an id it can't find ($mediaId)") }
            uploadCache[cacheKey] = mediaId
            return mediaId
        } finally {
            bManager.pendingChooserFiles = null
            closeMenus(tabId)
            closePicker(tabId)
            prepared.parentFile?.deleteRecursively()
        }
    }

    /** Opens the asset picker: via the "+" button, or via the "Start" / "End" frame slot. */
    private suspend fun openPicker(tabId: String, slot: String?) {
        if (readUi(tabId)?.optBoolean("pickerOpen") == true) return
        val opener = (if (slot == null) jsObject(tabId, "s.aria('Add ingredients to the prompt box')") else jsObject(tabId, "s.text(${q("^\\s*${slot}\\s*$")})"))
            ?.takeIf { it.optBoolean("found") }
            ?: throw Exception(
                if (slot == null) "Flow's \"Add ingredients\" (+) button was not found. Visible controls: ${visibleControls(tabId)}"
                else "Flow's \"$slot\" frame slot was not found (does this video model support frames?). Visible controls: ${visibleControls(tabId)}"
            )
        tap(tabId, opener)
        if (!waitUntil(4_000, 250) { readUi(tabId)?.optBoolean("pickerOpen") == true }) {
            throw Exception("Flow's asset picker did not open")
        }
    }

    private suspend fun closePicker(tabId: String) {
        repeat(3) {
            if (readUi(tabId)?.optBoolean("pickerOpen") != true) return
            jsValue(tabId, "s.escape()")
            BrowserSessionManager.instance.dispatchKey(tabId, KeyEvent.KEYCODE_ESCAPE)
            waitUntil(1_000, 250) { readUi(tabId)?.optBoolean("pickerOpen") != true }
        }
    }

    private suspend fun closeMenus(tabId: String) {
        repeat(3) {
            if (readUi(tabId)?.optBoolean("menuOpen") != true) return
            jsValue(tabId, "s.escape()")
            BrowserSessionManager.instance.dispatchKey(tabId, KeyEvent.KEYCODE_ESCAPE)
            delay(400)
        }
    }

    /**
     * Spec §7.4 fallback: attaches media [mediaId] as an ingredient (slot null) or a Start/End frame through the
     * asset picker, searching for [searchTitle] (a unique tag when the tile was tagged). The attached chip's real
     * media id is verified; a wrong chip is removed and the attach retried.
     */
    private suspend fun attachById(tabId: String, mediaId: String, slot: String?, searchTitle: String) {
        for (attempt in 0 until 3) {
            val before = chipIds(tabId)
            if (mediaId in before) return
            openPicker(tabId, slot)
            try {
                val option = findPickerOption(tabId, searchTitle, if (searchTitle.startsWith("mcpref-")) 0 else attempt)
                    ?: throw Exception("Media $mediaId (\"$searchTitle\") was not found in Flow's picker. References must be in the project open in Flow (pass a local file path to upload it).")
                tap(tabId, option)
                if (!waitUntil(1_500, 250) { chipIds(tabId).size > before.size }) {
                    jsObject(tabId, "s.pickerButton('add\\\\s*to\\\\s*prompt')")?.takeIf { it.optBoolean("found") }?.let { tap(tabId, it) }
                }
            } finally {
                closePicker(tabId)
            }
            // The chip's thumbnail carries the real id; it is empty for a moment while loading
            waitUntil(5_000, 300) { chipIds(tabId).let { ids -> mediaId in ids || (ids.size > before.size && ids.none { it.isBlank() }) } }
            if (mediaId in chipIds(tabId)) return
            removeWrongChip(tabId, before)
        }
        throw Exception("Could not attach media $mediaId to Flow's prompt (the attached chip had a different id)")
    }

    private suspend fun removeWrongChip(tabId: String, before: List<String>) {
        val after = chipIds(tabId)
        after.withIndex().firstOrNull { (i, id) -> i >= before.size || id !in before }?.let { (i, _) ->
            jsObject(tabId, "s.chipPoint($i)")?.takeIf { it.optBoolean("found") }?.let { tap(tabId, it) }
            delay(500)
        }
    }

    /** A tile renamed to a unique tag so the grid search finds exactly it; restored afterwards. */
    private data class TileTag(val tag: String, val workflowId: String, val projectId: String, val original: String)

    private fun tagFor(mediaId: String) = "mcpref-${mediaId.take(8)}"

    /**
     * Spec §7.3b: renames each media's tile to a unique tag (direct RPC), then reloads the page once — the grid
     * caches titles, so an API rename isn't visible until reload. Only media of the open project can be tagged.
     */
    private suspend fun tagTiles(tabId: String, mediaIds: List<String>): Map<String, TileTag> {
        val currentProject = readUi(tabId)?.let { projectIdOf(it) }
        val tags = LinkedHashMap<String, TileTag>()
        for (id in mediaIds.distinct()) {
            val media = getMedia(tabId, id)
            val workflowId = media.optString("workflowId").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val projectId = media.optString("projectId")
            if (currentProject != null && projectId != currentProject) continue
            val original = runCatching {
                projectContents(tabId, projectId).let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("mediaId") == id }?.optString("title")
                }
            }.getOrNull() ?: media.optString("title")
            renameWorkflow(tabId, workflowId, projectId, tagFor(id))
            tags[id] = TileTag(tagFor(id), workflowId, projectId, original)
        }
        if (tags.isNotEmpty()) reloadPage(tabId)
        return tags
    }

    private suspend fun untagTiles(tabId: String, tags: Map<String, TileTag>) {
        tags.values.forEach { t -> runCatching { renameWorkflow(tabId, t.workflowId, t.projectId, t.original) } }
    }

    /** Reloads the Flow page (after replies still expected on it have arrived) and waits until it's usable again. */
    private suspend fun reloadPage(tabId: String) {
        val bManager = BrowserSessionManager.instance
        if (!waitUntil(240_000, 1_000) { pageRepliesPending.get() <= 0 }) {
            throw Exception("Still waiting for earlier Flow generations to answer; try again shortly")
        }
        val tab = requireTab(tabId)
        withContext(Dispatchers.Main) { bManager.loadUrlInTab(tab, tab.url) }
        delay(1500)
        bManager.waitForPageLoad(tabId, 30_000)
        if (!waitUntil(30_000) { readUi(tabId)?.let { it.optBoolean("ready") && it.optBoolean("prompt") } == true }) {
            throw Exception("The Flow page did not come back after reloading. Visible controls: ${visibleControls(tabId)}")
        }
        ensureAgentModeOff(tabId)
    }

    private suspend fun clearGridSearch(tabId: String) {
        runCatching { jsValue(tabId, "s.gridSearch('')") }
    }

    /** Finds the tagged tile through the grid search, opens its menu and clicks [path] (e.g. Download ▸ 2K Upscaled). */
    private suspend fun tileAction(tabId: String, tag: String, path: List<String>, missing: String) {
        jsValue(tabId, "s.gridSearch(${q(tag)})")
        if (waitFor(6_000, 400) { jsObject(tabId, "s.tileCount(${q(tag)})")?.takeIf { it.optInt("count") == 1 } } == null) {
            throw Exception("The tile \"$tag\" was not found in the Flow grid")
        }
        var opened = false
        for (i in 0 until 4) {
            opened = jsObject(tabId, "s.tileMenu(${q(tag)})")?.optBoolean("ok") == true &&
                waitUntil(1_500, 250) { readUi(tabId)?.optBoolean("menuOpen") == true }
            if (opened) break
            delay(500)
        }
        if (!opened) throw Exception("The tile's \"More options\" menu did not open")
        for (item in path) {
            val clicked = waitFor(3_000) { jsObject(tabId, "s.menuClick(${q(item)})")?.takeIf { it.optBoolean("ok") || it.optBoolean("disabled") } }
                ?: throw Exception(missing)
            if (clicked.optBoolean("disabled")) throw Exception("\"$item\" is disabled for this item in Flow")
            delay(400)
        }
    }

    /**
     * Spec §7.3b: attaches [mediaId] with its tile menu ("Animate" = start frame, "Add to prompt" = reference or end
     * frame) and verifies the chip carries that id. Falls back to the picker if the tile route fails.
     */
    private suspend fun attachViaTile(tabId: String, mediaId: String, menuItem: String, slot: String?, tag: String?) {
        if (mediaId in chipIds(tabId)) return
        if (tag != null) {
            val before = chipIds(tabId)
            val viaTile = runCatching {
                tileAction(tabId, tag, listOf(menuItem), "The tile menu has no \"$menuItem\" entry")
                waitUntil(5_000, 300) { mediaId in chipIds(tabId) }
            }
            closeMenus(tabId)
            clearGridSearch(tabId)
            if (viaTile.getOrNull() == true) return
            Log.w(TAG, "Tile attach of $mediaId failed (${viaTile.exceptionOrNull()?.message}); trying the picker")
            if (chipIds(tabId).size > before.size) removeWrongChip(tabId, before)
        }
        attachById(tabId, mediaId, slot, tag ?: runCatching { getMedia(tabId, mediaId).optString("title") }.getOrDefault(mediaId))
    }

    /** In the image editor: shows history and selects the version whose main image is [mediaId]. */
    private suspend fun selectHistoryVersion(tabId: String, mediaId: String) {
        if (jsObject(tabId, "s.mainImageId()")?.optString("id") == mediaId) return
        if (jsObject(tabId, "s.openHistory()")?.optBoolean("ok") != true) throw Exception("Flow's \"Show history\" button was not found")
        val count = waitFor(4_000) { jsObject(tabId, "s.historyCount()")?.optInt("count")?.takeIf { it > 0 } }
            ?: throw Exception("Flow's version history is empty")
        for (i in (count - 1) downTo 0) {
            jsValue(tabId, "s.historyClick($i)")
            if (waitUntil(2_000, 300) { jsObject(tabId, "s.mainImageId()")?.optString("id") == mediaId }) return
        }
        throw Exception("Version $mediaId was not found in this image's history")
    }

    /** Re-types the prompt if attaching references cleared it, then checks the chips survived. */
    private suspend fun ensurePromptText(tabId: String, prompt: String) {
        val current = (jsValue(tabId, "s.promptValue()") as? JSONObject)?.optString("value").orEmpty()
        if (current.contains(prompt.trim().take(30))) return
        val chips = chipIds(tabId)
        enterPrompt(tabId, prompt)
        if (chipIds(tabId) != chips) throw Exception("Re-typing the prompt removed the attached references")
    }

    /** Searches the open picker for [title], also trying its Uploads / All / Images tabs; returns option #[index]. */
    private suspend fun findPickerOption(tabId: String, title: String, index: Int): JSONObject? {
        suspend fun search(): JSONObject? {
            jsValue(tabId, "s.pickerSearch(${q(title)})")
            val options = waitFor(2_500, 400) { jsObject(tabId, "s.pickerOptions()")?.takeIf { it.optInt("count") > 0 } } ?: return null
            if (index == 0) return options.optJSONObject("first")
            return jsObject(tabId, "s.pickerOptionAt($index)")?.takeIf { it.optBoolean("found") } ?: options.optJSONObject("first")
        }
        search()?.let { return it }
        for (tab in listOf("Uploads", "All", "Images")) {
            val tabPoint = jsObject(tabId, "s.pickerTab(${q(tab)})")?.takeIf { it.optBoolean("found") } ?: continue
            tap(tabId, tabPoint)
            search()?.let { return it }
        }
        return null
    }

    private suspend fun chipIds(tabId: String): List<String> {
        val arr = jsValue(tabId, "s.chipIds()") as? JSONArray ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }
    }

    /**
     * Before Start: the attached chips must carry exactly the expected media ids (order matters for frames).
     * Extra chips (leftovers) are removed; a missing or misordered reference is an error.
     */
    private suspend fun verifyChips(tabId: String, expected: List<String>) {
        if (waitUntil(5_000, 300) { chipIds(tabId) == expected }) return
        repeat(6) {
            val ids = chipIds(tabId)
            if (ids == expected) return
            val missing = expected.filter { it !in ids }
            if (missing.isNotEmpty()) {
                throw Exception("Requested references are not attached in Flow: $missing (attached: $ids)")
            }
            val extra = extraChipIndex(ids, expected) ?: return@repeat
            Log.w(TAG, "Removing leftover reference chip ${ids[extra]}")
            removeChipAt(tabId, extra)
        }
        if (chipIds(tabId) == expected) return
        throw Exception("The references attached in Flow (${chipIds(tabId)}) don't match the requested ones ($expected)")
    }

    /** Index of the first chip that isn't one of the expected ids (or is a duplicate beyond them). */
    private fun extraChipIndex(ids: List<String>, expected: List<String>): Int? {
        val remaining = expected.toMutableList()
        ids.forEachIndexed { i, id -> if (!remaining.remove(id)) return i }
        return null
    }

    private suspend fun removeChipAt(tabId: String, index: Int) {
        val before = chipIds(tabId).size
        val chip = jsObject(tabId, "s.chipPoint($index)")?.takeIf { it.optBoolean("found") } ?: return
        tap(tabId, chip)
        waitUntil(2_000, 250) { chipIds(tabId).size < before }
    }

    /** Removes leftover text and reference chips so they don't leak into the next request (verified empty). */
    private suspend fun clearPrompt(tabId: String) {
        jsObject(tabId, "s.aria('Clear prompt')")?.takeIf { it.optBoolean("found") }?.let {
            tap(tabId, it)
            delay(300)
        }
        repeat(8) {
            if (chipIds(tabId).isEmpty()) return
            removeChipAt(tabId, 0)
        }
        if (chipIds(tabId).isNotEmpty()) Log.w(TAG, "Leftover reference chips could not be removed: ${chipIds(tabId)}")
    }

    /** Renames a media's tile (workflow) with a direct RPC. */
    private suspend fun renameWorkflow(tabId: String, workflowId: String, projectId: String, title: String) {
        val args = JSONArray().apply {
            put(JSONArray().apply { put(workflowId); put(JSONObject.NULL); put(JSONObject.NULL); put(JSONArray().apply { put(title) }); put(projectId) })
            put(JSONArray().apply { put(JSONArray().apply { put("metadata.display_name") }) })
        }
        rpc(tabId, FlowPageScripts.RPC_RENAME_WORKFLOW, args, "ok")
    }

    private fun rememberMedia(files: List<FlowMediaFile>) {
        files.forEach { history.addLast(it) }
        while (history.size > 200) history.pollFirst()
    }

    // ==========================================
    // MEDIA MANAGEMENT & POST-PRODUCTION (direct RPCs, no generation)
    // ==========================================

    /** Renames the tile of a media item. */
    suspend fun renameMedia(mediaId: String, title: String): Result<Unit> = runCatching {
        val tabId = ensureFlowTab()
        val media = getMedia(tabId, mediaId)
        renameWorkflow(tabId, media.optString("workflowId"), media.optString("projectId"), title)
    }

    /** Moves media to Flow's trash ([trashed] true) or restores it (false). */
    suspend fun setTrashed(mediaIds: List<String>, trashed: Boolean): Result<Int> = runCatching {
        val tabId = ensureFlowTab()
        val entries = JSONArray()
        mediaIds.forEach { id ->
            val media = getMedia(tabId, id)
            entries.put(JSONArray().apply {
                put(media.optString("workflowId")); put(JSONObject.NULL); put(JSONObject.NULL)
                put(JSONArray().apply { put(JSONObject.NULL); put(JSONObject.NULL); put(trashed) })
                put(media.optString("projectId"))
            })
        }
        rpc(tabId, FlowPageScripts.RPC_UPDATE_WORKFLOWS, JSONArray().apply {
            put(entries)
            put(JSONArray().apply { put(JSONArray().apply { put("metadata.archived") }) })
        }, "ok")
        mediaIds.size
    }

    /** Newest media: first this session's own results, then the project's newest items (not trashed). */
    suspend fun lastMedia(type: String?, count: Int, projectId: String?): Result<List<JSONObject>> = runCatching {
        val out = mutableListOf<JSONObject>()
        history.reversed().filter { type == null || it.type == type }.distinctBy { it.mediaId }.take(count).forEach { out += it.toJsonObject() }
        if (out.size < count) {
            val items = listMedia(projectId).getOrNull()
            if (items != null) {
                (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                    .filter { !it.optBoolean("trashed") && !it.optBoolean("pending") && (type == null || it.optString("type") == type) }
                    .filter { item -> out.none { it.optString("media_id") == item.optString("mediaId") } }
                    .sortedByDescending { it.optLong("created") }
                    .take(count - out.size)
                    .forEach { item ->
                        out += JSONObject().apply {
                            put("media_id", item.optString("mediaId"))
                            put("type", item.optString("type"))
                            put("title", item.optString("title"))
                            cachedFile(item.optString("mediaId"))?.let { put("path", it.absolutePath) }
                        }
                    }
            }
        }
        out
    }

    data class FlowClip(val mediaId: String, val startSec: Double?, val endSec: Double?)

    /** Joins/trims/reorders videos into one MP4 with Flow's own concatenation (no credits), saved in ~/flow-media. */
    suspend fun combineVideos(clips: List<FlowClip>, uploadBack: Boolean): Result<JSONObject> = runCatching {
        val tabId = ensureFlowTab()
        val clipArgs = JSONArray()
        clips.forEach { clip ->
            val end = clip.endSec ?: videoDurationSec(clip.mediaId)
            clipArgs.put(JSONArray().apply {
                put(clip.mediaId); put(JSONObject.NULL)
                put(JSONArray().apply { clip.startSec?.let { put(it) } })
                put(JSONArray().apply { put(end) })
            })
        }
        val started = rpc(tabId, FlowPageScripts.RPC_CONCAT, JSONArray().apply { put(clipArgs) }, "concatStart") as? JSONObject
        val jobName = started?.optString("job")?.takeIf { it.isNotBlank() && it != "null" } ?: throw Exception("Flow did not start combining the videos")
        val status = waitFor(5 * 60_000L, 2_000) {
            val st = rpc(tabId, FlowPageScripts.RPC_CONCAT_STATUS, JSONArray().apply { put(JSONArray().apply { put(JSONArray().apply { put(jobName) }) }) }, "concatStatus") as? JSONObject
            st?.takeIf { it.optInt("state") == 3 && it.optInt("len") > 0 }
        } ?: throw Exception("Flow did not finish combining the videos within 5 min")
        val dir = mediaDir() ?: throw Exception("Flow automation is not initialized")
        val out = File(dir.apply { mkdirs() }, "combined_${System.currentTimeMillis()}.mp4")
        readBlob(tabId, status.optString("key"), status.optInt("len"), out)
        JSONObject().apply {
            put("path", out.absolutePath)
            if (uploadBack) uploadMedia(listOf(out.absolutePath), null).getOrThrow().firstOrNull()?.let { put("media_id", it) }
        }
    }

    /** Converts a video to an animated GIF (270p) with Flow's own export, cached in ~/flow-media. */
    suspend fun videoToGif(mediaId: String): Result<File> = runCatching {
        val dir = mediaDir() ?: throw Exception("Flow automation is not initialized")
        File(dir, "flow_${mediaId}.gif").takeIf { it.length() > 0 }?.let { return@runCatching it }
        val tabId = ensureFlowTab()
        val res = rpc(tabId, FlowPageScripts.RPC_GIF, JSONArray().apply { put(JSONObject.NULL); put(mediaId) }, "gif", timeoutMs = 120_000) as? JSONObject
        if ((res?.optInt("len") ?: 0) <= 0) throw Exception("Flow returned no GIF for $mediaId")
        val out = File(dir.apply { mkdirs() }, "flow_${mediaId}.gif")
        readBlob(tabId, res!!.optString("key"), res.optInt("len"), out)
        out
    }

    /** Creates a Flow scene (timeline) from videos in the given order. */
    suspend fun createScene(mediaIds: List<String>, aspectRatio: String): Result<JSONObject> = runCatching {
        val tabId = ensureFlowTab()
        val medias = mediaIds.map { getMedia(tabId, it) }
        val projectId = medias.first().optString("projectId")
        val args = JSONArray().apply {
            put("projects/$projectId")
            put(JSONArray(medias.map { it.optString("workflowId") }))
            put(JSONObject.NULL); put(JSONObject.NULL)
            put(VIDEO_ASPECT_ENUM[aspectRatio] ?: 2)
        }
        (rpc(tabId, FlowPageScripts.RPC_CREATE_SCENE, args, "scene") as? JSONObject ?: JSONObject()).apply { put("project_id", projectId) }
    }

    /** Saves a frame of a video as a PNG in ~/flow-media (locally, no credits); optionally uploads it into Flow. */
    suspend fun extractFrame(mediaId: String, atSec: Double?, upload: Boolean): Result<JSONObject> = runCatching {
        val video = getMediaFile(mediaId).getOrThrow()
        val retriever = android.media.MediaMetadataRetriever()
        val out = try {
            retriever.setDataSource(video.absolutePath)
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atMs = ((atSec?.times(1000))?.toLong() ?: (durationMs - 50)).coerceIn(0, maxOf(0, durationMs - 1))
            val frame = retriever.getFrameAtTime(atMs * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                ?: throw Exception("Could not read a frame at ${atMs} ms from video $mediaId")
            File(video.parentFile, "frame_${mediaId}_${atMs}.png").also { file ->
                file.outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                frame.recycle()
            }
        } finally {
            retriever.release()
        }
        JSONObject().apply {
            put("path", out.absolutePath)
            if (upload) uploadMedia(listOf(out.absolutePath), null).getOrThrow().firstOrNull()?.let { put("media_id", it) }
        }
    }

    private suspend fun videoDurationSec(mediaId: String): Double {
        val file = getMediaFile(mediaId).getOrThrow()
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            (retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
        } finally {
            retriever.release()
        }
    }

    /** Reads a large base64 result kept in the page (combined MP4, GIF) into [out], in chunks. */
    private suspend fun readBlob(tabId: String, key: String, length: Int, out: File) {
        val part = File(out.parentFile, out.name + ".part")
        try {
            part.outputStream().use { stream ->
                var offset = 0
                while (offset < length) {
                    val chunk = jsValue(tabId, "s.blobChunk(${q(key)}, $offset, $BLOB_CHUNK)") as? String
                    if (chunk.isNullOrEmpty()) throw Exception("Reading the result from the Flow page failed")
                    stream.write(android.util.Base64.decode(chunk, android.util.Base64.DEFAULT))
                    offset += chunk.length
                }
            }
            if (!part.renameTo(out)) throw Exception("Could not save ${out.name}")
        } finally {
            part.delete()
            jsValue(tabId, "s.blobDrop(${q(key)})")
        }
    }

    // ==========================================
    // DOWNLOAD (one cached file per media id; signed URLs need no cookies)
    // ==========================================

    private suspend fun download(mediaId: String, url: String, type: String): File = withContext(Dispatchers.IO) {
        cachedFile(mediaId)?.let { return@withContext it }
        val dir = mediaDir() ?: throw Exception("Flow automation is not initialized")
        dir.mkdirs()
        val request = Request.Builder().url(url).header("User-Agent", BrowserSessionManager.DESKTOP_USER_AGENT).build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Downloading media $mediaId failed: HTTP ${resp.code}")
            val body = resp.body ?: throw Exception("Downloading media $mediaId returned no data")
            val mime = body.contentType()?.let { "${it.type}/${it.subtype}" } ?: ""
            val part = File(dir, "flow_$mediaId.part")
            part.outputStream().use { out -> body.byteStream().copyTo(out) }
            val isVideo = type == "video" || url.contains("/video/") || mime.startsWith("video/")
            val target = File(dir, "flow_$mediaId.${extensionFor(mime, isVideo)}")
            if (!part.renameTo(target)) {
                part.delete()
                throw Exception("Could not save ${target.name}")
            }
            target
        }
    }

    private fun extensionFor(mime: String, isVideo: Boolean): String = when {
        mime.contains("png") -> "png"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("webp") -> "webp"
        mime.contains("gif") -> "gif"
        mime.contains("webm") -> "webm"
        mime.contains("quicktime") -> "mov"
        mime.startsWith("video/") -> "mp4"
        else -> if (isVideo) "mp4" else "jpg"
    }

    // ==========================================
    // PAGE HELPERS
    // ==========================================

    private fun q(value: String): String = JSONObject.quote(value)

    private fun reEscape(value: String): String = value.replace(Regex("[.*+?^\${}()|\\[\\]\\\\]")) { "\\" + it.value }

    private suspend fun jsValue(tabId: String, expr: String): Any? {
        requireTab(tabId)
        val raw = BrowserSessionManager.instance.evaluateJs(tabId, FlowPageScripts.call(expr)).getOrNull() ?: return null
        return try { JSONTokener(raw).nextValue() } catch (_: Exception) { null }
    }

    private suspend fun jsObject(tabId: String, expr: String): JSONObject? = jsValue(tabId, expr) as? JSONObject

    private suspend fun readUi(tabId: String): JSONObject? = jsObject(tabId, "s.ui()")

    /** Calls a Flow RPC inside the page and waits for its (optionally post-processed) result. */
    private suspend fun rpc(tabId: String, rpcId: String, args: JSONArray, post: String? = null, timeoutMs: Long = 30_000): Any? {
        val key = "r${synchronized(this) { ++rpcCounter }}"
        val started = jsObject(tabId, "s.rpcStart(${q(key)}, ${q(rpcId)}, ${q(args.toString())}, ${post?.let { q(it) } ?: "null"})")
        if (started?.optBoolean("ok") != true) throw Exception("Could not call Flow ($rpcId): ${started?.optString("error") ?: "page not ready"}")
        var result: JSONObject? = null
        withTimeoutOrNull(timeoutMs) {
            while (result == null) {
                delay(250)
                result = jsObject(tabId, "s.result(${q(key)})")?.takeIf { it.optBoolean("done") }
            }
        }
        val res = result ?: throw Exception("Flow did not answer $rpcId in time")
        if (res.has("error")) {
            val error = res.optString("error")
            if (error.contains("HTTP 401") || error.contains("not ready")) throw Exception(NOT_SIGNED_IN)
            throw Exception("Flow $rpcId failed: $error")
        }
        return res.opt("value")
    }

    private suspend fun getMedia(tabId: String, mediaId: String): JSONObject =
        rpc(tabId, FlowPageScripts.RPC_GET_MEDIA, JSONArray().apply { put(mediaId) }, "media") as? JSONObject
            ?: throw Exception("Flow returned no data for media $mediaId")

    private suspend fun projectContents(tabId: String, projectId: String): JSONArray =
        rpc(tabId, FlowPageScripts.RPC_PROJECT_CONTENTS, JSONArray().apply { put("projects/$projectId") }, "contents") as? JSONArray
            ?: JSONArray()

    private fun JSONArray.mediaIds(): Set<String> =
        (0 until length()).mapNotNull { optJSONObject(it)?.optString("mediaId")?.takeIf { id -> id.isNotBlank() } }.toSet()

    private suspend fun videoStatuses(tabId: String, mediaIds: List<String>): Map<String, Int> {
        val args = JSONArray().apply {
            put(JSONObject.NULL)
            put(JSONObject.NULL)
            put(JSONArray().apply { mediaIds.forEach { id -> put(JSONArray().apply { put(id) }) } })
        }
        val arr = rpc(tabId, FlowPageScripts.RPC_VIDEO_STATUS, args, "videoStatus") as? JSONArray ?: return emptyMap()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { it.optString("mediaId").isNotBlank() && !it.isNull("status") }
            .associate { it.optString("mediaId") to it.optInt("status") }
    }

    private suspend fun listText(tabId: String, expr: String): String {
        val arr = try { jsValue(tabId, expr) as? JSONArray } catch (_: Exception) { null } ?: return "(none)"
        return (0 until arr.length()).joinToString(" | ") { arr.optString(it) }.ifBlank { "(none)" }
    }

    private suspend fun visibleControls(tabId: String): String = listText(tabId, "s.controls()")

    private suspend fun tap(tabId: String, target: JSONObject) {
        // While AI drives the page, keep it at its zoomed-out width (no auto-zoom into focused fields)
        if (isBusy) jsValue(tabId, "s.freezeZoom(true)")
        // Let the scrollIntoView done while locating the element settle before tapping
        delay(250)
        BrowserSessionManager.instance.dispatchTap(tabId, target.optDouble("fx").toFloat(), target.optDouble("fy").toFloat()).getOrThrow()
        delay(700)
    }

    private suspend fun <T> waitFor(timeoutMs: Long, intervalMs: Long = 400, probe: suspend () -> T?): T? {
        var value: T? = null
        withTimeoutOrNull(timeoutMs) {
            while (value == null) {
                value = probe()
                if (value == null) delay(intervalMs)
            }
        }
        return value
    }

    private suspend fun waitUntil(timeoutMs: Long, intervalMs: Long = 700, check: suspend () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!check()) delay(intervalMs)
            true
        } ?: false
}
