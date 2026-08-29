const express = require('express');
const http = require('http');
const https = require('https');
const os = require('os');
const WebSocket = require('ws');
const path = require('path');
const cors = require('cors');
const fs = require('fs');

const { spawn, exec } = require('child_process');

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

const PORT = process.env.PORT || 8080;
const WORKSPACE_DIR = process.env.WORKSPACE_DIR || __dirname;
const BRAIN_DIR = path.join(os.homedir(), '.gemini', 'antigravity-cli', 'brain');
const OAUTH_TOKEN_FILE = path.join(os.homedir(), '.gemini', 'antigravity-cli', 'antigravity-oauth-token');

app.use(cors());
app.use(express.json({ limit: '60mb' }));
app.use(express.urlencoded({ extended: true, limit: '60mb' }));

const BRAIN_BASE_DIR = path.join(os.homedir(), '.gemini/antigravity-cli/brain');
if (fs.existsSync(BRAIN_BASE_DIR)) {
  app.use('/artifacts', express.static(BRAIN_BASE_DIR));
}

// Upload attachment to project workspace
app.post('/api/upload', (req, res) => {
  try {
    const { filename, base64Data, projectPath } = req.body;
    if (!filename || !base64Data) {
      return res.status(400).json({ error: 'filename and base64Data are required' });
    }

    const wsDir = projectPath && fs.existsSync(projectPath) ? projectPath : WORKSPACE_DIR;
    const targetDir = path.join(wsDir, '.gemini', 'attachments');
    fs.mkdirSync(targetDir, { recursive: true });

    const ext = path.extname(filename);
    const base = path.basename(filename, ext).replace(/[^a-zA-Z0-9_-]/g, '_');
    const uniqueName = `${base}_${Date.now()}${ext}`;
    const targetPath = path.join(targetDir, uniqueName);

    const buffer = Buffer.from(base64Data, 'base64');
    fs.writeFileSync(targetPath, buffer);

    const isImg = /\.(jpg|jpeg|png|gif|webp|bmp|svg)$/i.test(filename);

    console.log(`[UPLOAD] Saved attachment: ${uniqueName} (${buffer.length} bytes) at ${targetPath}`);

    res.json({
      name: filename,
      savedName: uniqueName,
      path: targetPath,
      isImage: isImg,
      size: buffer.length,
      url: `http://127.0.0.1:${PORT}/attachments/${uniqueName}`
    });
  } catch (err) {
    console.error('[Upload Error]:', err);
    res.status(500).json({ error: err.message });
  }
});

// Serve attachments
app.use('/attachments', (req, res, next) => {
  const wsDir = WORKSPACE_DIR;
  const filePath = path.join(wsDir, '.gemini', 'attachments', req.path);
  if (fs.existsSync(filePath)) {
    return res.sendFile(filePath);
  }
  next();
});

app.use(express.static(path.join(__dirname, 'public')));

// Models dynamically fetched and cached in RAM
let cachedModels = [];
let conversations = [];
let isFetchingModels = false;

// Quota Cache in RAM
let cachedQuotaSummary = null;
let lastQuotaFetchTime = 0;
let isFetchingQuota = false;

// Track active child process per client/conversation
const activeProcesses = new Map();

// Helper to extract access token from local auth file
function getLocalAccessToken() {
  try {
    if (fs.existsSync(OAUTH_TOKEN_FILE)) {
      const content = fs.readFileSync(OAUTH_TOKEN_FILE, 'utf8');
      const json = JSON.parse(content);
      return json.token?.access_token || (typeof json.token === 'string' ? json.token : null);
    }
  } catch (e) {
    console.warn('[Auth] Failed to read oauth token file:', e.message);
  }
  return null;
}

// -------------------------------------------------------------
// 1. Dynamic Model Discovery from agy CLI & Quota API
// -------------------------------------------------------------
function fetchAvailableModels(force = false) {
  if (isFetchingModels) return Promise.resolve(cachedModels || []);
  if (!force && cachedModels && cachedModels.length > 0) return Promise.resolve(cachedModels);

  isFetchingModels = true;
  return new Promise((resolve) => {
    console.log('[Models] Querying dynamic model list via agy models CLI...');
    exec('agy models < /dev/null', { env: { ...process.env, NO_COLOR: '1', AGY_AUTO_UPDATE: '0' }, timeout: 15000 }, (error, stdout) => {
      isFetchingModels = false;
      if (error || !stdout) {
        console.warn('[Models] agy models CLI call failed:', error ? error.message : 'empty stdout');
        return resolve(cachedModels || []);
      }

      const cleaned = (stdout || '')
        .replace(/\x1B\[[0-9;]*[a-zA-Z]/g, '')
        .replace(/[\u2800-\u28FF]/g, '')
        .replace(/Fetching available models\.\.\./g, '')
        .replace(/\r+/g, '\n');

      const lines = cleaned.split('\n');
      const models = [];

      for (const rawLine of lines) {
        const clean = rawLine.trim();
        if (!clean || clean.startsWith('Report') || clean.startsWith('➜') || clean.includes('Available models')) continue;

        // Split by 2 or more spaces or tab to cleanly extract ID and Display Name
        const parts = clean.split(/\s{2,}|\t+/);
        if (parts.length >= 1) {
          const id = parts[0].trim();
          const name = (parts[1] || id).trim();

          if (!id || id.length < 3 || id.includes(' ') || models.some(m => m.id === id)) continue;

          const lowerId = id.toLowerCase();
          let family = 'OTHER';
          let provider = 'OpenAI';
          let maxTokens = 131072;

          if (lowerId.includes('gemini')) {
            family = 'GEMINI';
            provider = 'Google';
            maxTokens = 1048576;
          } else if (lowerId.includes('claude')) {
            family = 'CLAUDE';
            provider = 'Anthropic';
            maxTokens = 250000;
          }

          const supportsThinking = lowerId.includes('high') ||
                                   lowerId.includes('medium') ||
                                   lowerId.includes('low') ||
                                   lowerId.includes('thinking');

          models.push({
            id,
            name,
            displayName: name,
            family,
            provider,
            maxTokens,
            supportsThinking,
            recommended: true
          });
        }
      }

      if (models.length > 0) {
        cachedModels = models;
        console.log(`[Models] Successfully loaded and categorized ${models.length} models into 3 groups.`);
      }
      resolve(cachedModels || []);
    });
  });
}

// -------------------------------------------------------------
// 1.1 Dynamic Quota Summary (5h & Weekly Windows for Gemini & Claude)
// -------------------------------------------------------------
function fetchQuotaSummary(force = false) {
  const now = Date.now();
  if (!force && cachedQuotaSummary && (now - lastQuotaFetchTime < 30000)) {
    return Promise.resolve(cachedQuotaSummary);
  }
  if (isFetchingQuota) return Promise.resolve(cachedQuotaSummary || { groups: [] });
  isFetchingQuota = true;

  return new Promise((resolve) => {
    const accessToken = getLocalAccessToken();
    if (!accessToken) {
      isFetchingQuota = false;
      return resolve(cachedQuotaSummary || { groups: [] });
    }

    const postData = JSON.stringify({});
    const req = https.request('https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary', {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer ' + accessToken,
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(postData),
        'User-Agent': 'Antigravity/1.0'
      },
      timeout: 10000
    }, (res) => {
      let raw = '';
      res.on('data', chunk => raw += chunk);
      res.on('end', () => {
        isFetchingQuota = false;
        try {
          const json = JSON.parse(raw);
          const currentTime = Date.now();
          const groups = (json.groups || []).map(group => {
            const isGemini = (group.displayName || '').toLowerCase().includes('gemini');
            const groupId = isGemini ? 'gemini' : 'claude_gpt';

            let fiveHour = null;
            let weekly = null;

            (group.buckets || []).forEach(b => {
              const resetDate = b.resetTime ? new Date(b.resetTime) : null;
              const diffMs = resetDate ? Math.max(0, resetDate.getTime() - currentTime) : 0;
              const days = Math.floor(diffMs / (1000 * 60 * 60 * 24));
              const hours = Math.floor((diffMs % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60));
              const mins = Math.floor((diffMs % (1000 * 60 * 60)) / (1000 * 60));

              let countdown = '';
              if (days > 0) countdown = `${days}d ${hours}h`;
              else if (hours > 0) countdown = `${hours}h ${mins}m`;
              else countdown = `${mins}m`;

              const remainingFraction = typeof b.remainingFraction === 'number' ? b.remainingFraction : 1.0;
              const remainingPct = `${(remainingFraction * 100).toFixed(1)}%`;
              const usedPct = `${((1.0 - remainingFraction) * 100).toFixed(1)}%`;

              const bucketInfo = {
                window: b.window || (b.bucketId?.includes('5h') ? '5h' : 'weekly'),
                displayName: b.displayName || '',
                remainingFraction,
                remainingPct,
                usedPct,
                resetTime: b.resetTime || null,
                countdown,
                description: b.description || ''
              };

              if (bucketInfo.window === '5h') {
                fiveHour = bucketInfo;
              } else {
                weekly = bucketInfo;
              }
            });

            return {
              groupId,
              groupName: group.displayName || (isGemini ? 'Google Gemini Models' : 'Anthropic Claude & GPT Models'),
              description: group.description || '',
              fiveHour,
              weekly
            };
          });

          cachedQuotaSummary = {
            groups,
            lastUpdated: new Date().toISOString()
          };
          lastQuotaFetchTime = Date.now();

          // Broadcast live quota update to all connected WebSocket clients
          broadcastQuotaUpdate(cachedQuotaSummary);

          resolve(cachedQuotaSummary);
        } catch (e) {
          console.warn('[Quotas] Error parsing quota summary:', e.message);
          resolve(cachedQuotaSummary || { groups: [] });
        }
      });
    });

    req.on('error', (e) => {
      isFetchingQuota = false;
      console.warn('[Quotas] Request error:', e.message);
      resolve(cachedQuotaSummary || { groups: [] });
    });
    req.on('timeout', () => {
      req.destroy();
      isFetchingQuota = false;
      resolve(cachedQuotaSummary || { groups: [] });
    });
    req.write(postData);
    req.end();
  });
}

function broadcastQuotaUpdate(quotaData) {
  if (!quotaData) return;
  const msg = JSON.stringify({ type: 'quota_update', data: quotaData });
  for (const client of wss.clients) {
    if (client.readyState === WebSocket.OPEN) {
      client.send(msg);
    }
  }
}

// -------------------------------------------------------------
// 2. High-Performance Warm Session Pool Manager
// -------------------------------------------------------------
class SessionInstance {
  constructor(model, conversationId, workspaceDir, onSessionReady) {
    this.model = model || 'gemini-3.7-flash-high';
    this.conversationId = conversationId;
    this.workspaceDir = (workspaceDir && fs.existsSync(workspaceDir)) ? workspaceDir : WORKSPACE_DIR;
    this.isReady = false;
    this.process = null;
    this.currentTurn = null;
    this.stdoutBuffer = '';
    this.createdAt = Date.now();
    this.lastUsed = Date.now();
    this.onSessionReady = onSessionReady;
    this.spawn();
  }

  spawn() {
    const args = [
      '--input-format', 'stream-json',
      '--output-format', 'stream-json',
      '--dangerously-skip-permissions',
      '--add-dir', this.workspaceDir
    ];

    if (this.model && this.model !== 'default') {
      args.push('--model', this.model);
    }
    if (this.conversationId) {
      args.push('--conversation', this.conversationId);
    }

    console.log(`[SessionPool] Spawning warm instance for [${this.model} | ${this.conversationId || 'new'} | ${this.workspaceDir}]...`);
    const child = spawn('agy', args, {
      cwd: this.workspaceDir,
      env: { ...process.env, NO_COLOR: '1' }
    });

    this.process = child;
    this.stdoutBuffer = '';

    child.stdout.on('data', (chunk) => {
      this.stdoutBuffer += chunk.toString();
      const lines = this.stdoutBuffer.split('\n');
      this.stdoutBuffer = lines.pop() || '';

      for (const line of lines) {
        if (!line.trim()) continue;
        try {
          const parsed = JSON.parse(line);
          this.handleParsedEvent(parsed, line);
        } catch (e) {
          if (this.currentTurn && this.currentTurn.ws.readyState === WebSocket.OPEN) {
            this.currentTurn.ws.send(JSON.stringify({ type: 'raw_chunk', text: line }));
          }
        }
      }
    });

    child.stderr.on('data', (chunk) => {
      const errText = chunk.toString().trim();
      if (errText && !errText.includes('Fetching available models')) {
        console.error(`[SessionPool STDERR] ${errText}`);
      }
      if (this.currentTurn && this.currentTurn.ws.readyState === WebSocket.OPEN) {
        this.currentTurn.ws.send(JSON.stringify({ type: 'stderr', text: chunk.toString() }));
      }
    });

    child.on('close', (code) => {
      console.log(`[SessionPool] Instance [${this.model} | ${this.conversationId || 'new'}] closed (code ${code})`);
      this.isReady = false;
      this.process = null;
      if (this.currentTurn && this.currentTurn.ws.readyState === WebSocket.OPEN) {
        this.currentTurn.ws.send(JSON.stringify({ type: 'done', exitCode: code }));
      }
    });
  }

  handleParsedEvent(parsed, rawLine) {
    if (parsed.event === 'init') {
      this.isReady = true;
      this.conversationId = parsed.conversation_id || this.conversationId;
      console.log(`[SessionPool] Instance ready! Conversation ID: ${this.conversationId} (${this.model})`);

      if (parsed.conversation_id) {
        const existing = conversations.find(c => c.id === parsed.conversation_id);
        if (!existing && this.currentTurn) {
          conversations.unshift({
            id: parsed.conversation_id,
            title: this.currentTurn.prompt.slice(0, 32) + (this.currentTurn.prompt.length > 32 ? '...' : ''),
            created_at: new Date().toISOString()
          });
        }
      }

      if (this.onSessionReady) this.onSessionReady(this);
    }

    if (this.currentTurn) {
      const { startTime } = this.currentTurn;
      this.currentTurn.bufferedEvents.push(parsed);

      if (parsed.event === 'step_update') {
        const step = parsed.step_update;
        if (step.step_type === 'agent_response' && step.text_delta) {
          process.stdout.write(step.text_delta);
        } else if (step.step_type === 'tool' || step.tool_info || step.tool_name) {
          const toolName = step.tool_info?.name || step.tool_name || 'tool';
          const params = JSON.stringify(step.tool_info?.parameters || {});
          const status = step.state === 'DONE' ? `DONE (${step.duration_seconds ? step.duration_seconds.toFixed(2) + 's' : '0s'})` : 'ACTIVE';
          console.log(`\n[CLI ⚙ TOOL] ${toolName} [${status}] ${params}`);
        }
      }

      if (parsed.event === 'result') {
        const res = parsed.result || {};
        const duration = ((Date.now() - startTime) / 1000).toFixed(2);
        console.log(`\n[CLI ⚙ RESULT] Status: ${res.status || 'DONE'} | Tokens: ${res.usage?.total_tokens || 0} | Duration: ${duration}s`);
        console.log(`=======================================================\n`);
      }

      for (const clientWs of this.currentTurn.listeners) {
        if (clientWs && clientWs.readyState === WebSocket.OPEN) {
          clientWs.send(JSON.stringify({ type: 'agy_event', data: parsed }));
        }
      }

      if (parsed.event === 'result') {
        this.currentTurn.isDone = true;
        for (const clientWs of this.currentTurn.listeners) {
          if (clientWs && clientWs.readyState === WebSocket.OPEN) {
            clientWs.send(JSON.stringify({ type: 'done', exitCode: 0 }));
          }
        }
        this.currentTurn = null;

        // Auto-refresh quota upon completing prompt execution
        setTimeout(() => fetchQuotaSummary(true), 600);
      }
    }
  }

  attachClient(ws) {
    if (!this.currentTurn || this.currentTurn.isDone) {
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'session_attached', conversationId: this.conversationId, isRunning: false }));
        ws.send(JSON.stringify({ type: 'done', exitCode: 0 }));
      }
      return false;
    }
    this.currentTurn.listeners.add(ws);
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({
        type: 'session_attached',
        conversationId: this.conversationId,
        isRunning: true,
        prompt: this.currentTurn.prompt
      }));
      // Replay all buffered events for the current active turn
      for (const ev of this.currentTurn.bufferedEvents) {
        ws.send(JSON.stringify({ type: 'agy_event', data: ev }));
      }
    }
    return true;
  }

  sendTurn(prompt, ws, clientId) {
    this.lastUsed = Date.now();
    if (!this.process || !this.isReady) {
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
          type: 'instance_status',
          status: 'creating',
          message: 'Creating new instance for this chat...'
        }));
      }
      console.log(`[SessionPool] Waiting for instance [${this.model} | ${this.conversationId || 'new'}] to be ready...`);
      setTimeout(() => this.sendTurn(prompt, ws, clientId), 250);
      return;
    }

    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({
        type: 'instance_status',
        status: 'ready'
      }));
    }

    const listeners = new Set();
    if (ws) listeners.add(ws);
    this.currentTurn = { listeners, clientId, prompt, startTime: Date.now(), bufferedEvents: [], isDone: false };
    console.log(`\n================== [NEW PROMPT TURN] ==================`);
    console.log(`[WS ➜ REQ] Prompt: "${prompt}" [Model: ${this.model}] [Conv: ${this.conversationId || 'new'}] [Dir: ${this.workspaceDir}]`);
    console.log(`[SessionPool ➜ STDIN] Sending turn to warm instance...`);

    this.process.stdin.write(JSON.stringify({
      event: 'user',
      message: { content: prompt }
    }) + '\n');
  }

  abort() {
    if (this.currentTurn) {
      for (const ws of this.currentTurn.listeners) {
        if (ws && ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: 'done', exitCode: 130, message: 'Interrupted by user' }));
        }
      }
      this.currentTurn = null;
    }
    // Attempt double escape on stdin first
    if (this.process && this.process.stdin && !this.process.stdin.destroyed) {
      try {
        this.process.stdin.write('\x1b\x1b');
      } catch (e) {}
    }
    // Send SIGINT to kill/interrupt the background execution
    if (this.process) {
      try {
        this.process.kill('SIGINT');
      } catch (e) {}
    }
  }

  destroy() {
    this.abort();
    if (this.process) {
      this.process.removeAllListeners('close');
      try { this.process.kill('SIGKILL'); } catch (e) {}
      this.process = null;
    }
    this.isReady = false;
  }
}

class SessionPoolManager {
  constructor(maxSessions = 5) {
    this.sessions = new Map();
    this.maxSessions = maxSessions;
  }

  getKey(model = 'gemini-3.7-flash-high', conversationId = null, workspaceDir = null) {
    return `${model || 'default'}__${conversationId || 'new'}__${workspaceDir || 'default'}`;
  }

  getOrCreate(model = 'gemini-3.7-flash-high', conversationId = null, workspaceDir = null) {
    // 1. If conversationId is specified, ensure strictly 1 instance exists for this conversation
    if (conversationId && conversationId !== 'new') {
      for (const [key, existing] of this.sessions.entries()) {
        if (existing.conversationId === conversationId) {
          // If model and workspace match and process is alive, reuse it!
          if (existing.model === model && existing.workspaceDir === workspaceDir && existing.process) {
            existing.lastUsed = Date.now();
            return existing;
          }
          // Otherwise model or project changed: destroy old instance to free RAM!
          console.log(`[SessionPool] Model or project changed for conv ${conversationId} (${existing.model} -> ${model}). Terminating old instance...`);
          existing.destroy();
          this.sessions.delete(key);
        }
      }
    }

    const key = this.getKey(model, conversationId, workspaceDir);
    let session = this.sessions.get(key);

    if (session && session.process) {
      session.lastUsed = Date.now();
      return session;
    }

    // Prune oldest idle session if at capacity
    if (this.sessions.size >= this.maxSessions) {
      let oldestKey = null;
      let oldestTime = Infinity;
      for (const [k, s] of this.sessions.entries()) {
        if (!s.currentTurn && s.lastUsed < oldestTime) {
          oldestTime = s.lastUsed;
          oldestKey = k;
        }
      }
      if (oldestKey) {
        console.log(`[SessionPool] Pruning idle instance: ${oldestKey}`);
        this.sessions.get(oldestKey).destroy();
        this.sessions.delete(oldestKey);
      }
    }

    session = new SessionInstance(model, conversationId, workspaceDir, (s) => {
      if (!conversationId && s.conversationId) {
        const assignedKey = this.getKey(model, s.conversationId, workspaceDir);
        this.sessions.set(assignedKey, s);
      }
    });

    this.sessions.set(key, session);
    return session;
  }

  terminateInstance(conversationId) {
    let found = false;
    for (const [key, s] of this.sessions.entries()) {
      if (s.conversationId === conversationId || key.includes(`__${conversationId}__`)) {
        console.log(`[SessionPool] Explicitly terminating instance for conversation: ${conversationId}`);
        s.destroy();
        this.sessions.delete(key);
        found = true;
      }
    }
    return found;
  }

  abortSession(conversationId) {
    if (!conversationId) {
      this.abortAll();
      return;
    }
    for (const [key, s] of this.sessions.entries()) {
      if (s.conversationId === conversationId || key.includes(`__${conversationId}__`)) {
        console.log(`[SessionPool] Aborting generation for conversation: ${conversationId}`);
        const model = s.model;
        const workspaceDir = s.workspaceDir;
        s.destroy();
        this.sessions.delete(key);
        // Pre-warm clean instance immediately in background
        this.prewarm(model, conversationId, workspaceDir);
      }
    }
  }

  findRunningSession(conversationId) {
    if (!conversationId) return null;
    for (const s of this.sessions.values()) {
      if (s.conversationId === conversationId && s.currentTurn && !s.currentTurn.isDone) {
        return s;
      }
    }
    return null;
  }

  getActiveInstances() {
    const list = [];
    for (const [key, s] of this.sessions.entries()) {
      if (s.process && s.conversationId) {
        list.push({
          conversationId: s.conversationId,
          model: s.model,
          workspaceDir: s.workspaceDir,
          pid: s.process.pid || 0,
          uptimeSeconds: Math.floor((Date.now() - (s.createdAt || Date.now())) / 1000),
          lastUsedAgoSeconds: Math.floor((Date.now() - s.lastUsed) / 1000),
          isReady: s.isReady,
          isBusy: !!s.currentTurn
        });
      }
    }
    return list;
  }

  getPoolStatus() {
    return {
      activeSessions: this.sessions.size,
      maxSessions: this.maxSessions,
      instances: this.getActiveInstances()
    };
  }

  prewarm(model, conversationId, workspaceDir) {
    this.getOrCreate(model, conversationId, workspaceDir);
  }

  abortAll() {
    for (const [key, s] of this.sessions.entries()) {
      s.destroy();
    }
    this.sessions.clear();
  }
}

const sessionManager = new SessionPoolManager();

function extractPromptText(content) {
  if (!content) return '';
  const match = content.match(/<USER_REQUEST>([\s\S]*?)<\/USER_REQUEST>/);
  if (match) return match[1].trim();

  // Strip system context blocks so they never leak into the visible prompt
  let cleaned = content
    .replace(/<CONTEXT_SUMMARY>[\s\S]*?<\/CONTEXT_SUMMARY>/g, '')
    .replace(/<ADDITIONAL_METADATA>[\s\S]*?<\/ADDITIONAL_METADATA>/g, '')
    .replace(/<USER_SETTINGS_CHANGE>[\s\S]*?<\/USER_SETTINGS_CHANGE>/g, '');

  return cleaned.replace(/<[^>]+>/g, '').trim();
}

function getStoredConversations() {
  const result = [];
  const seenIds = new Set();

  // 1. Add in-memory active conversations
  for (const c of conversations) {
    seenIds.add(c.id);
    result.push(c);
  }

  // 2. Read conversations from AGY brain storage
  if (fs.existsSync(BRAIN_DIR)) {
    try {
      const dirs = fs.readdirSync(BRAIN_DIR);
      for (const dir of dirs) {
        if (seenIds.has(dir)) continue;

        const transcriptPath = path.join(BRAIN_DIR, dir, '.system_generated/logs/transcript.jsonl');
        if (fs.existsSync(transcriptPath)) {
          try {
            const stat = fs.statSync(transcriptPath);
            const content = fs.readFileSync(transcriptPath, 'utf8');
            const lines = content.split('\n').filter(l => l.trim());
            let title = dir.slice(0, 8);
            const customTitlePath = path.join(BRAIN_DIR, dir, 'custom_title.txt');
            if (fs.existsSync(customTitlePath)) {
              try {
                const custom = fs.readFileSync(customTitlePath, 'utf8').trim();
                if (custom) title = custom;
              } catch (e) {}
            } else {
              // Find first USER_INPUT step for conversation title
              for (const line of lines) {
                try {
                  const step = JSON.parse(line);
                  if (step.type === 'USER_INPUT' && step.content) {
                    const cleaned = extractPromptText(step.content);
                    if (cleaned) {
                      title = cleaned.slice(0, 36) + (cleaned.length > 36 ? '...' : '');
                      break;
                    }
                  }
                } catch (e) {}
              }
            }

            result.push({
              id: dir,
              title: title,
              created_at: stat.mtime.toISOString(),
              mtimeMs: stat.mtimeMs,
              steps_count: lines.length
            });
            seenIds.add(dir);
          } catch (err) {}
        }
      }
    } catch (e) {
      console.error('[Conversations] Error reading brain storage:', e.message);
    }
  }

  // Sort descending by most recently updated
  return result.sort((a, b) => (b.mtimeMs || 0) - (a.mtimeMs || 0));
}

// -------------------------------------------------------------
// 3. API Endpoints
// -------------------------------------------------------------

// List dynamic models (returns instantly with cached data, or awaits initial fetch)
app.get('/api/models', async (req, res) => {
  if (!cachedModels || cachedModels.length === 0) {
    await fetchAvailableModels();
  }
  res.json({ models: cachedModels || [] });
});

// Force refresh model list
app.post('/api/models/refresh', async (req, res) => {
  await fetchAvailableModels(true);
  res.json({ models: cachedModels || [] });
});

// Get user quotas (cached in RAM, 30s TTL, force=true to bypass)
app.get('/api/quotas', async (req, res) => {
  const force = req.query.force === 'true';
  const quotas = await fetchQuotaSummary(force);
  res.json(quotas || { groups: [] });
});

// Force refresh user quotas
app.post('/api/quotas/refresh', async (req, res) => {
  const quotas = await fetchQuotaSummary(true);
  res.json(quotas || { groups: [] });
});

// List all projects under ~/projects/
const PROJECTS_BASE_DIR = path.join(os.homedir(), 'projects');
app.get('/api/projects', (req, res) => {
  try {
    if (!fs.existsSync(PROJECTS_BASE_DIR)) {
      return res.json({ projects: [{ name: 'agy_test', path: WORKSPACE_DIR }] });
    }
    const entries = fs.readdirSync(PROJECTS_BASE_DIR, { withFileTypes: true });
    const list = [];
    for (const entry of entries) {
      if (entry.isDirectory() && !entry.name.startsWith('.')) {
        list.push({
          name: entry.name,
          path: path.join(PROJECTS_BASE_DIR, entry.name)
        });
      }
    }
    res.json({ projects: list });
  } catch (e) {
    res.status(500).json({ error: e.message, projects: [] });
  }
});

// List all past and active conversations
app.get('/api/conversations', (req, res) => {
  const list = getStoredConversations();
  res.json({ conversations: list });
});

// Helper to format tool command from args
function formatToolCommand(toolName, args) {
  if (!args) return toolName || 'tool';
  if (typeof args === 'string') return args.replace(/^"|"$/g, '');

  if (args.CommandLine) {
    return typeof args.CommandLine === 'string' ? args.CommandLine.replace(/^"|"$/g, '') : JSON.stringify(args.CommandLine);
  }
  if (args.AbsolutePath) {
    return typeof args.AbsolutePath === 'string' ? args.AbsolutePath.replace(/^"|"$/g, '') : JSON.stringify(args.AbsolutePath);
  }
  if (args.DirectoryPath) {
    return typeof args.DirectoryPath === 'string' ? args.DirectoryPath.replace(/^"|"$/g, '') : JSON.stringify(args.DirectoryPath);
  }
  if (args.Query && args.SearchPath) {
    const q = String(args.Query).replace(/^"|"$/g, '');
    const sp = String(args.SearchPath).replace(/^"|"$/g, '');
    return `${q} in ${sp}`;
  }
  if (args.query) {
    return String(args.query).replace(/^"|"$/g, '');
  }
  if (args.Url) {
    return String(args.Url).replace(/^"|"$/g, '');
  }
  if (args.TargetFile) {
    return String(args.TargetFile).replace(/^"|"$/g, '');
  }
  return JSON.stringify(args);
}

function scanSkills(baseSkillsDir) {
  if (!fs.existsSync(baseSkillsDir)) return [];
  const skills = [];
  try {
    const dirs = fs.readdirSync(baseSkillsDir, { withFileTypes: true });
    for (const d of dirs) {
      if (!d.isDirectory()) continue;
      const skillPath = path.join(baseSkillsDir, d.name, 'SKILL.md');
      if (fs.existsSync(skillPath)) {
        const raw = fs.readFileSync(skillPath, 'utf8');
        const match = raw.match(/---\s*([\s\S]*?)\s*---/);
        let name = d.name;
        let description = '';
        if (match) {
          const frontmatter = match[1];
          const nameMatch = frontmatter.match(/name:\s*([^\n]+)/);
          if (nameMatch) name = nameMatch[1].trim();
          const descMatch = frontmatter.match(/description:\s*(?:>-|>)?\s*([\s\S]*?)(?=\n[a-zA-Z0-9_-]+:|$)/);
          if (descMatch) {
            description = descMatch[1].replace(/\n\s+/g, ' ').trim();
          }
        }
        skills.push({ name, path: skillPath, description });
      }
    }
  } catch (e) {}
  return skills;
}

function scanWorkspaceRules(workspaceDir) {
  if (!workspaceDir || !fs.existsSync(workspaceDir)) return '';
  const rules = [];
  try {
    const geminiMd = path.join(workspaceDir, 'GEMINI.md');
    if (fs.existsSync(geminiMd)) {
      rules.push(`[Workspace Rule: GEMINI.md]\n${fs.readFileSync(geminiMd, 'utf8').trim()}`);
    }
    const rulesDir = path.join(workspaceDir, '.gemini/rules');
    if (fs.existsSync(rulesDir)) {
      const files = fs.readdirSync(rulesDir).filter(f => f.endsWith('.md'));
      for (const f of files) {
        rules.push(`[Rule: ${f}]\n${fs.readFileSync(path.join(rulesDir, f), 'utf8').trim()}`);
      }
    }
  } catch (e) {}
  return rules.join('\n\n');
}

function getFullToolDeclarations() {
  return [
    {
      name: "view_file",
      description: "View the contents of a file from the local filesystem. Supports text files and binary files (image, pdf, video, audio).",
      parameters: {
        type: "object",
        properties: {
          AbsolutePath: { type: "string", description: "Path to file to view. Must be an absolute path." },
          StartLine: { type: "integer", description: "Optional start line (1-indexed)." },
          EndLine: { type: "integer", description: "Optional end line (1-indexed)." },
          ContentOffset: { type: "integer", description: "Optional byte offset for viewing truncated content." },
          toolAction: { type: "string" },
          toolSummary: { type: "string" }
        },
        required: ["AbsolutePath", "toolSummary", "toolAction"]
      }
    },
    {
      name: "replace_file_content",
      description: "Use this tool to edit an existing file by replacing a single contiguous block of text with new content.",
      parameters: {
        type: "object",
        properties: {
          TargetFile: { type: "string", description: "The target file to modify. Must be an absolute path." },
          StartLine: { type: "integer", description: "The starting line number of the chunk (1-indexed)." },
          EndLine: { type: "integer", description: "The ending line number of the chunk (1-indexed)." },
          TargetContent: { type: "string", description: "The exact string to be replaced." },
          ReplacementContent: { type: "string", description: "The content to replace the target content with." },
          Instruction: { type: "string", description: "Description of the changes." },
          Description: { type: "string", description: "User-facing explanation of what this change did." },
          AllowMultiple: { type: "boolean", description: "If true, multiple occurrences will be replaced." },
          TargetLintErrorIds: { type: "array", items: { type: "string" } },
          toolAction: { type: "string" },
          toolSummary: { type: "string" }
        },
        required: ["TargetFile", "Instruction", "Description", "AllowMultiple", "TargetContent", "ReplacementContent", "StartLine", "EndLine", "toolSummary", "toolAction"]
      }
    },
    {
      name: "write_to_file",
      description: "Use this tool to create new files or overwrite existing files.",
      parameters: {
        type: "object",
        properties: {
          TargetFile: { type: "string", description: "The target file to create. Must be an absolute path." },
          Overwrite: { type: "boolean", description: "Set true to overwrite an existing file." },
          CodeContent: { type: "string", description: "The code contents to write to the file." },
          Description: { type: "string", description: "User-facing explanation of what this change did." },
          ArtifactMetadata: {
            type: "object",
            properties: {
              UserFacing: { type: "boolean" },
              Summary: { type: "string" },
              RequestFeedback: { type: "boolean" }
            },
            required: ["Summary", "UserFacing", "RequestFeedback"]
          },
          toolAction: { type: "string" },
          toolSummary: { type: "string" }
        },
        required: ["TargetFile", "Overwrite", "CodeContent", "Description", "toolSummary", "toolAction"]
      }
    },
    {
      name: "run_command",
      description: "PROPOSE a command to run on behalf of the user. Operating System: linux. Shell: bash.",
      parameters: {
        type: "object",
        properties: {
          CommandLine: { type: "string", description: "The exact command line string to execute." },
          Cwd: { type: "string", description: "The current working directory for the command." },
          WaitMsBeforeAsync: { type: "integer", description: "Milliseconds to wait before sending task to background." },
          RunPersistent: { type: "boolean", description: "Set true to preserve variables in persistent terminal." },
          RequestedTerminalID: { type: "string", description: "Optional terminal ID to reuse." },
          toolAction: { type: "string" },
          toolSummary: { type: "string" }
        },
        required: ["Cwd", "WaitMsBeforeAsync", "CommandLine", "toolSummary", "toolAction"]
      }
    },
    {
      name: "grep_search",
      description: "Use ripgrep to find exact pattern matches within files or directories.",
      parameters: {
        type: "object",
        properties: {
          SearchPath: { type: "string", description: "The path to search. Must be an absolute path." },
          Query: { type: "string", description: "The search term or regex pattern." },
          IsRegex: { type: "boolean" },
          CaseInsensitive: { type: "boolean" },
          MatchPerLine: { type: "boolean" },
          Includes: { type: "array", items: { type: "string" } },
          toolAction: { type: "string" },
          toolSummary: { type: "string" }
        },
        required: ["SearchPath", "Query", "toolSummary", "toolAction"]
      }
    },
    {
      name: "find_by_name",
      description: "Search for files and subdirectories within a specified directory using fd.",
      parameters: {
        type: "object",
        properties: {
          SearchDirectory: { type: "string" },
          Pattern: { type: "string" },
          Extensions: { type: "array", items: { type: "string" } },
          Excludes: { type: "array", items: { type: "string" } },
          FullPath: { type: "boolean" },
          MaxDepth: { type: "integer" },
          Type: { type: "string", enum: ["file", "directory", "any"] },
          toolAction: { type: "string" },
          toolSummary: { type: "string" }
        },
        required: ["SearchDirectory", "Pattern", "toolSummary", "toolAction"]
      }
    },
    {
      name: "list_dir",
      description: "List the contents of a directory, including recursive child counts and byte sizes.",
      parameters: {
        type: "object",
        properties: {
          DirectoryPath: { type: "string" },
          toolAction: { type: "string" },
          toolSummary: { type: "string" }
        },
        required: ["DirectoryPath", "toolSummary", "toolAction"]
      }
    },
    {
      name: "ask_question",
      description: "Render interactive multiple-choice question modal to clarify requirements or user preferences.",
      parameters: {
        type: "object",
        properties: {
          questions: {
            type: "array",
            items: {
              type: "object",
              properties: {
                question: { type: "string" },
                options: { type: "array", items: { type: "string" } },
                is_multi_select: { type: "boolean" }
              },
              required: ["question", "options"]
            }
          },
          toolAction: { type: "string" },
          toolSummary: { type: "string" }
        },
        required: ["questions", "toolSummary", "toolAction"]
      }
    },
    {
      name: "invoke_subagent",
      description: "Invokes one or more subagents by name with isolated prompt contexts.",
      parameters: {
        type: "object",
        properties: {
          Subagents: {
            type: "array",
            items: {
              type: "object",
              properties: {
                TypeName: { type: "string" },
                Role: { type: "string" },
                Prompt: { type: "string" },
                Model: { type: "string", enum: ["inherit", "flash_lite", "flash", "pro"] },
                Workspace: { type: "string", enum: ["inherit", "branch", "share"] }
              },
              required: ["TypeName", "Role", "Prompt"]
            }
          },
          toolAction: { type: "string" },
          toolSummary: { type: "string" }
        },
        required: ["Subagents", "toolSummary", "toolAction"]
      }
    },
    {
      name: "define_subagent",
      description: "Defines a new type of subagent that can be invoked via invoke_subagent.",
      parameters: {
        type: "object",
        properties: {
          name: { type: "string" },
          description: { type: "string" },
          system_prompt: { type: "string" },
          enable_write_tools: { type: "boolean" },
          enable_mcp_tools: { type: "boolean" },
          enable_subagent_tools: { type: "boolean" },
          toolAction: { type: "string" },
          toolSummary: { type: "string" }
        },
        required: ["name", "description", "system_prompt", "toolSummary", "toolAction"]
      }
    },
    {
      name: "manage_subagents",
      description: "Manage existing subagents (list, kill, kill_all).",
      parameters: {
        type: "object",
        properties: {
          Action: { type: "string", enum: ["list", "kill", "kill_all"] },
          ConversationIds: { type: "array", items: { type: "string" } },
          toolAction: { type: "string" },
          toolSummary: { type: "string" }
        },
        required: ["Action", "toolSummary", "toolAction"]
      }
    },
    {
      name: "schedule",
      description: "Schedule a one-shot timer or recurring cron job that sends notification wakeups in background.",
      parameters: {
        type: "object",
        properties: {
          DurationSeconds: { type: "integer" },
          CronExpression: { type: "string" },
          MaxIterations: { type: "integer" },
          Prompt: { type: "string" },
          TimerCondition: { type: "string" },
          toolAction: { type: "string" },
          toolSummary: { type: "string" }
        },
        required: ["Prompt", "toolSummary", "toolAction"]
      }
    },
    {
      name: "search_web",
      description: "Performs a web search for documentation, packages, or real-time internet search results.",
      parameters: {
        type: "object",
        properties: {
          query: { type: "string" },
          domain: { type: "string" },
          toolAction: { type: "string" },
          toolSummary: { type: "string" }
        },
        required: ["query", "toolSummary", "toolAction"]
      }
    },
    {
      name: "read_url_content",
      description: "Fetch content from a URL via HTTP request and convert HTML to markdown.",
      parameters: {
        type: "object",
        properties: {
          Url: { type: "string" },
          toolAction: { type: "string" },
          toolSummary: { type: "string" }
        },
        required: ["Url", "toolSummary", "toolAction"]
      }
    },
    {
      name: "generate_image",
      description: "Generate an image or UI asset based on text prompt.",
      parameters: {
        type: "object",
        properties: {
          Prompt: { type: "string" },
          ImageName: { type: "string" },
          AspectRatio: { type: "string", enum: ["1:1", "2:3", "3:2", "3:4", "4:3", "9:16", "16:9"] },
          ImagePaths: { type: "array", items: { type: "string" } },
          toolAction: { type: "string" },
          toolSummary: { type: "string" }
        },
        required: ["Prompt", "ImageName", "toolSummary", "toolAction"]
      }
    }
  ];
}

function getCompiledSystemPrompt(conversationId, workspaceDir) {
  const home = os.homedir();
  const ws = workspaceDir || WORKSPACE_DIR;
  const appData = path.join(home, '.gemini/antigravity-cli');
  const brainDir = path.join(appData, 'brain', conversationId || 'active');

  const builtinSkills = scanSkills(path.join(appData, 'builtin/skills'));
  const workspaceSkills = scanSkills(path.join(ws, '.gemini/skills'));
  const allSkills = [...builtinSkills, ...workspaceSkills];

  const skillLines = allSkills.length > 0
    ? allSkills.map(s => `- ${s.name} (${s.path}): ${s.description}`).join('\n')
    : 'No active skills detected.';

  const workspaceRules = scanWorkspaceRules(ws);
  const toolSchemas = getFullToolDeclarations();
  const toolSchemasJson = JSON.stringify(toolSchemas, null, 2);

  return `<identity>
You are Antigravity, a powerful agentic AI coding assistant designed by the Google Deepmind team working on Advanced Agentic Coding.
You are pair programming with a USER to solve their coding task. The task may require creating a new codebase, modifying or debugging an existing codebase, or simply answering a question.
The USER will send you requests, which you must always prioritize addressing. User requests are enclosed within <USER_REQUEST> tags.
</identity>

<user_information>
The USER's OS version is linux.
The user has active workspace:
${ws} -> ${ws}
App Data Directory: ${appData}
Conversation ID: ${conversationId || 'active'}
</user_information>

<skills>
You can use specialized 'skills' to help you with complex tasks.
Available skills:
${skillLines}
</skills>

<subagents>
Available subagents:
- self: Subagent that inherits the parent agent's full configuration including tools, system prompt, and model.
- research: Research subagent with read-only tools for exploring the codebase, searching the web, and reading files.
</subagents>

<messaging>
You are connected to a messaging system where you may receive messages from: agents, background tasks, user-queued messages.
</messaging>

<conversation_transcript>
Conversation transcripts are stored locally under: <appDataDir>/brain/<conversation-id>/.system_generated/logs/transcript.jsonl
</conversation_transcript>

<artifacts>
Artifact Directory Path: ${brainDir}
</artifacts>

<slash_commands>
Available slash commands you can recommend to the user:
- /goal, /schedule, /browser, /plan, /grill-me, /teamwork-preview, /learn
</slash_commands>

<guidelines>
- Maintain documentation integrity. Preserve all existing comments and docstrings.
</guidelines>

${workspaceRules ? `<workspace_rules>\n${workspaceRules}\n</workspace_rules>\n\n` : ''}<communication_style>
- Keep your responses concise.
- Format your responses in github-style markdown.
- Create clickable links for all files and code symbols.
</communication_style>

<tool_declarations>
${toolSchemasJson}
</tool_declarations>`;
}

app.get('/api/conversations/:id/system-prompt', (req, res) => {
  const { id } = req.params;
  const wsDir = req.query.workspaceDir || WORKSPACE_DIR;
  const prompt = getCompiledSystemPrompt(id, wsDir);
  res.json({ conversationId: id, systemPrompt: prompt });
});

app.get('/api/system-prompt', (req, res) => {
  const convId = req.query.conversationId || 'active';
  const wsDir = req.query.workspaceDir || WORKSPACE_DIR;
  const prompt = getCompiledSystemPrompt(convId, wsDir);
  res.json({ conversationId: convId, systemPrompt: prompt });
});

function extractContextSummary(content) {
  if (!content) return null;
  const match = content.match(/<CONTEXT_SUMMARY>([\s\S]*?)<\/CONTEXT_SUMMARY>/);
  return match ? match[1].trim() : null;
}

function extractSystemMetadata(content) {
  if (!content) return null;
  const metadata = {};
  const metaMatch = content.match(/<ADDITIONAL_METADATA>([\s\S]*?)<\/ADDITIONAL_METADATA>/);
  if (metaMatch) metadata.additionalMetadata = metaMatch[1].trim();
  const settingsMatch = content.match(/<USER_SETTINGS_CHANGE>([\s\S]*?)<\/USER_SETTINGS_CHANGE>/);
  if (settingsMatch) metadata.userSettingsChange = settingsMatch[1].trim();
  return Object.keys(metadata).length > 0 ? metadata : null;
}

// Get full transcript and messages for a conversation
app.get('/api/conversations/:id', (req, res) => {
  const { id } = req.params;
  const transcriptPath = path.join(BRAIN_DIR, id, '.system_generated/logs/transcript.jsonl');

  if (!fs.existsSync(transcriptPath)) {
    return res.status(404).json({ error: 'Conversation not found', messages: [] });
  }

  try {
    const content = fs.readFileSync(transcriptPath, 'utf8');
    const lines = content.split('\n').filter(l => l.trim());
    const messages = [];

    let currentAgentTurn = null;
    let lastPendingTool = null;
    let pendingCheckpointSummary = null;

    function flushAgentTurn() {
      if (currentAgentTurn && (currentAgentTurn.content.trim() || currentAgentTurn.tool_calls.length > 0)) {
        // Auto-embed generated image artifacts if present
        if (currentAgentTurn.tool_calls.some(t => t.name === 'generate_image')) {
          const convBrain = path.join(BRAIN_DIR, id);
          if (fs.existsSync(convBrain)) {
            const files = fs.readdirSync(convBrain).filter(f => f.endsWith('.jpg') || f.endsWith('.png'));
            for (const f of files) {
              if (!currentAgentTurn.content.includes(f)) {
                currentAgentTurn.content += `\n\n![${f.replace(/\.[^/.]+$/, '')}](http://127.0.0.1:8080/artifacts/${id}/${f})\n`;
              }
            }
          }
        }

        const msg = {
          role: 'agent',
          content: currentAgentTurn.content.trim(),
          thinking: currentAgentTurn.thinking.trim() || undefined,
          tool_calls: currentAgentTurn.tool_calls,
          stepIndex: currentAgentTurn.stepIndex,
          durationSeconds: currentAgentTurn.durationSeconds,
          created_at: currentAgentTurn.created_at || new Date().toISOString()
        };
        if (currentAgentTurn.tokenUsage) msg.tokenUsage = currentAgentTurn.tokenUsage;
        messages.push(msg);
      }
      currentAgentTurn = null;
      lastPendingTool = null;
    }

    for (const line of lines) {
      try {
        const step = JSON.parse(line);
        if (step.type === 'CHECKPOINT') {
          flushAgentTurn();
          pendingCheckpointSummary = step.content || null;
        } else if (step.type === 'USER_INPUT') {
          flushAgentTurn();
          const raw = step.content || '';
          const cleanPrompt = extractPromptText(raw);
          const summary = extractContextSummary(raw) || pendingCheckpointSummary;
          pendingCheckpointSummary = null;
          const metadata = extractSystemMetadata(raw);

          const userMsg = {
            role: 'user',
            content: cleanPrompt,
            rawContent: raw,
            stepIndex: step.step_index,
            created_at: step.created_at || new Date().toISOString()
          };
          if (summary) userMsg.contextSummary = summary;
          if (metadata) userMsg.systemMetadata = metadata;

          messages.push(userMsg);
        } else if (step.type === 'PLANNER_RESPONSE') {
          if (!currentAgentTurn) {
            currentAgentTurn = {
              content: '',
              thinking: '',
              tool_calls: [],
              stepIndex: step.step_index,
              durationSeconds: step.duration_seconds,
              tokenUsage: step.usage ? {
                inputTokens: step.usage.input_tokens,
                outputTokens: step.usage.output_tokens,
                thinkingTokens: step.usage.thinking_tokens,
                cacheReadTokens: step.usage.cache_read_tokens,
                totalTokens: step.usage.total_tokens
              } : undefined,
              created_at: step.created_at || new Date().toISOString()
            };
          }

          if (step.thinking && step.thinking.trim()) {
            currentAgentTurn.content += `\n<!-- thought -->\n${step.thinking.trim()}\n<!-- /thought -->\n`;
            if (!currentAgentTurn.thinking) {
              currentAgentTurn.thinking = step.thinking.trim();
            }
          }

          if (step.tool_calls && Array.isArray(step.tool_calls)) {
            for (const tool of step.tool_calls) {
              const toolIdx = currentAgentTurn.tool_calls.length;
              const toolId = `tc_${id}_${messages.length}_${toolIdx}`;
              const cmd = formatToolCommand(tool.name, tool.args || tool.parameters);
              const toolObj = {
                id: toolId,
                name: tool.name || 'tool',
                command: cmd,
                status: 'SUCCESS',
                output: ''
              };
              currentAgentTurn.tool_calls.push(toolObj);
              lastPendingTool = toolObj;
              currentAgentTurn.content += `\n<!-- tool_call:${toolId} -->\n`;
            }
          }

          if (step.content && step.content.trim()) {
            currentAgentTurn.content += `\n${step.content.trim()}\n`;
          }
        } else if (step.type === 'GENERIC') {
          if (lastPendingTool && step.content) {
            lastPendingTool.output = step.content;
          }
          const imgMatch = (step.content || '').match(/Generated image is saved at\s+([^\s\.]+\.(?:jpg|png|webp|jpeg))/i) ||
                           (step.content || '').match(/Generated image is saved at\s+([^\s]+\.(?:jpg|png|webp|jpeg))/i);
          if (imgMatch && currentAgentTurn) {
            const fullPath = imgMatch[1].replace(/\.$/, '');
            const fileName = path.basename(fullPath);
            if (!currentAgentTurn.content.includes(fileName)) {
              currentAgentTurn.content += `\n\n![${fileName.replace(/\.[^/.]+$/, '')}](http://127.0.0.1:8080/artifacts/${id}/${fileName})\n`;
            }
          }
          lastPendingTool = null;
        }
      } catch (e) {}
    }

    flushAgentTurn();
    res.json({ id, messages });
  } catch (err) {
    res.status(500).json({ error: err.message, messages: [] });
  }
});

// Health check endpoint
app.get('/api/health', (req, res) => {
  res.json({
    status: 'ok',
    uptime: process.uptime(),
    timestamp: new Date().toISOString(),
    version: '1.0.0'
  });
});

app.get('/api/status', (req, res) => {
  res.json({
    status: 'ok',
    uptime: process.uptime(),
    timestamp: new Date().toISOString(),
    version: '1.0.0',
    pool: sessionManager.getPoolStatus()
  });
});

// List all active warm instances
app.get('/api/instances', (req, res) => {
  res.json({ instances: sessionManager.getActiveInstances() });
});

// Terminate a specific conversation instance to free RAM
app.post('/api/instances/:id/terminate', (req, res) => {
  const { id } = req.params;
  const terminated = sessionManager.terminateInstance(id);
  res.json({ status: terminated ? 'terminated' : 'not_found', conversationId: id });
});

// Update conversation title (persists to brain/custom_title.txt)
app.patch('/api/conversations/:id', (req, res) => {
  const { id } = req.params;
  const { title } = req.body || {};
  if (title && title.trim()) {
    const cleanTitle = title.trim();
    const mem = conversations.find(c => c.id === id);
    if (mem) mem.title = cleanTitle;

    const convDir = path.join(BRAIN_DIR, id);
    if (!fs.existsSync(convDir)) {
      try { fs.mkdirSync(convDir, { recursive: true }); } catch (e) {}
    }
    if (fs.existsSync(convDir)) {
      try {
        fs.writeFileSync(path.join(convDir, 'custom_title.txt'), cleanTitle, 'utf8');
        console.log(`[Conversations] Saved custom title for ${id}: "${cleanTitle}"`);
      } catch (e) {
        console.warn(`[Conversations] Failed to write custom_title.txt for ${id}:`, e.message);
      }
    }
  }
  res.json({ status: 'ok', id, title });
});

// Delete conversation and remove its brain storage from disk
app.delete('/api/conversations/:id', (req, res) => {
  const { id } = req.params;
  sessionManager.terminateInstance(id);

  const idx = conversations.findIndex(c => c.id === id);
  if (idx >= 0) conversations.splice(idx, 1);

  const convDir = path.join(BRAIN_DIR, id);
  if (fs.existsSync(convDir)) {
    try {
      fs.rmSync(convDir, { recursive: true, force: true });
      console.log(`[Conversations] Deleted brain directory on disk for ${id}`);
    } catch (e) {
      console.warn(`[Conversations] Failed to delete brain directory for ${id}:`, e.message);
    }
  }
  res.json({ status: 'deleted', id });
});

// Pre-warm conversation instance in background
app.post('/api/conversations/:id/warm', (req, res) => {
  const { id } = req.params;
  const { model, workspaceDir } = req.body || {};
  const targetId = id === 'new' ? null : id;
  const targetModel = model || 'gemini-3.7-flash-high';
  sessionManager.prewarm(targetModel, targetId, workspaceDir);
  res.json({ status: 'warming', model: targetModel, conversationId: targetId, workspaceDir });
});

// Abort active generation
app.post('/api/abort', (req, res) => {
  const { conversationId } = req.body || {};
  if (conversationId) {
    sessionManager.abortSession(conversationId);
  } else {
    sessionManager.abortAll();
  }
  res.json({ status: 'aborted', conversationId });
});

// -------------------------------------------------------------
// 4. WebSocket Streaming Handler
// -------------------------------------------------------------
wss.on('connection', (ws) => {
  console.log('[WS] Client connected');
  const clientId = Date.now().toString() + '_' + Math.random().toString(36).slice(2, 7);

  ws.on('message', (message) => {
    try {
      const data = JSON.parse(message.toString());

      if (data.type === 'send_prompt') {
        const { prompt, model, conversationId, workspaceDir } = data;
        const targetModel = model || 'gemini-3.7-flash-high';
        const targetConv = (conversationId && conversationId !== 'new') ? conversationId : null;

        // Retrieve or spawn warm session from the pool
        const session = sessionManager.getOrCreate(targetModel, targetConv, workspaceDir);
        session.sendTurn(prompt, ws, clientId);
      } else if (data.type === 'attach_session') {
        const { conversationId } = data;
        const runningSession = sessionManager.findRunningSession(conversationId);
        if (runningSession) {
          console.log(`[WS] Reconnecting / attaching client to running turn for conv: ${conversationId}`);
          runningSession.attachClient(ws);
        } else {
          ws.send(JSON.stringify({ type: 'session_attached', conversationId, isRunning: false }));
          ws.send(JSON.stringify({ type: 'done', exitCode: 0 }));
        }
      } else if (data.type === 'abort') {
        const { conversationId } = data;
        sessionManager.abortSession(conversationId);
        ws.send(JSON.stringify({ type: 'aborted', conversationId }));
      }
    } catch (e) {
      console.error('[WS] Error processing message:', e);
    }
  });

  ws.on('close', () => {
    console.log('[WS] Client disconnected');
  });
});

// -------------------------------------------------------------
// 5. Server Startup
// -------------------------------------------------------------
server.listen(PORT, '0.0.0.0', async () => {
  console.log(`🚀 Antigravity Workbench running at http://localhost:${PORT}`);
  // 1. Refresh dynamic models from agy in background
  fetchAvailableModels();
  // 2. Pre-warm default session immediately
  sessionManager.prewarm('gemini-3.7-flash-high', null);
});

