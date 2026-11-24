#!/usr/bin/env python3
"""
Moves hardcoded UI texts of the Android app into app/src/main/assets/i18n/en.json and replaces them with the
generated accessors (ai.antigem.i18n.L, see GenerateStringsTask in app/build.gradle.kts):

    Text("Delete \"${entry.title}\"?")   ->   Text(L.settings.globalContext.deleteTitle(entry.title))

Usage (from the repo root):
    python3 scripts/i18n/extract_strings.py report [paths...]   what would change + what is left for hand work
    python3 scripts/i18n/extract_strings.py apply  [paths...]   rewrite the files and en.json
    python3 scripts/i18n/extract_strings.py prune                drop en.json keys no code uses, and unused imports of L
Add --data to treat the given data/ and domain/ files like UI files (for files whose texts are all shown to the user).
Texts by hand: scripts/i18n/add_text.py.
Paths default to app/src/main/java/ai/antigem. Leftovers go to /tmp/i18n_leftovers.txt.

What it takes: in ui/, theme/ and the app's root files, every string literal that reads like text for people
(several words, or one capitalized word; in a clear UI place like Text(...) or title = also short ones like "OK").
In data/ and domain/ only clear UI places (toasts, notifications, dialogs). Never: MCP servers and prompts (models
read them), logs, exceptions, const vals, annotations, comparisons and `when` conditions (and any text that is
compared anywhere in the app: translating it would break the comparison), commands, paths, URLs, regexes, code.
Not done automatically, listed as leftovers: texts glued with + to variables, templates that hold other strings,
raw strings, texts with literal {braces}.

Markers in the code: a line with `// i18n: keep` is left alone, and so is everything between `// i18n: off` and
`// i18n: on` (texts that must stay English: prompts, phrases compared with what the user types, protocol values).

Keys: <area>.<file>.<words>, e.g. settings.globalContext.deleteTitle; a short text used in three or more files goes
to common.<words>. The same text in the same file gets one key. Placeholders are named after the expression
(${entry.title} -> {title}, ${list.size} -> {listCount}); the call passes the expressions in order.
"""
import json
import os
import re
import sys
from collections import OrderedDict, defaultdict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
JAVA = os.path.join(ROOT, 'app/src/main/java/ai/antigem')
EN_JSON = os.path.join(ROOT, 'app/src/main/assets/i18n/en.json')
LEFTOVERS = '/tmp/i18n_leftovers.txt'

KEYWORDS = {'as', 'break', 'class', 'continue', 'do', 'else', 'false', 'for', 'fun', 'if', 'in', 'interface', 'is', 'null',
            'object', 'package', 'return', 'super', 'this', 'throw', 'true', 'try', 'typealias', 'typeof', 'val', 'var',
            'when', 'while'}

# ------------------------------------------------------------------------------------------------ Kotlin lexer


class Lit:
    def __init__(self, start, end, raw, parts, nested):
        self.start, self.end, self.raw, self.parts, self.nested = start, end, raw, parts, nested

    @property
    def has_template(self):
        return any(p[0] == 'e' for p in self.parts)

    @property
    def literal_text(self):
        return ''.join(p[1] for p in self.parts if p[0] == 't')


ESC = {'t': '\t', 'b': '\b', 'n': '\n', 'r': '\r', "'": "'", '"': '"', '\\': '\\', '$': '$'}
IDENT = re.compile(r'[A-Za-z_][A-Za-z0-9_]*')


class Lexer:
    def __init__(self, src):
        self.s = src
        self.n = len(src)
        self.mask = list(src)
        self.lits = []

    def blank(self, a, b):
        for k in range(a, b):
            if self.mask[k] != '\n':
                self.mask[k] = ' '

    def skip_block_comment(self, i):
        depth = 0
        while i < self.n:
            if self.s.startswith('/*', i):
                depth += 1
                i += 2
            elif self.s.startswith('*/', i):
                depth -= 1
                i += 2
                if depth == 0:
                    return i
            else:
                i += 1
        return self.n

    def skip_char(self, i):
        j = i + 1
        if j < self.n and self.s[j] == '\\':
            j += 2
            while j < self.n and self.s[j] != "'":
                j += 1
        else:
            j += 1
        return min(j + 1, self.n)

    def run(self):
        i = 0
        s = self.s
        while i < self.n:
            c = s[i]
            if s.startswith('//', i):
                j = s.find('\n', i)
                j = self.n if j < 0 else j
                self.blank(i, j)
                i = j
            elif s.startswith('/*', i):
                j = self.skip_block_comment(i)
                self.blank(i, j)
                i = j
            elif s.startswith('"""', i) or c == '"':
                lit, j = self.read_string(i)
                self.lits.append(lit)
                q = 3 if lit.raw else 1
                self.blank(i + q, j - q)
                i = j
            elif c == "'":
                j = self.skip_char(i)
                self.blank(i + 1, j - 1)
                i = j
            elif c == '`':
                j = s.find('`', i + 1)
                i = self.n if j < 0 else j + 1
            else:
                i += 1
        return self

    def read_string(self, i):
        s, n = self.s, self.n
        raw = s.startswith('"""', i)
        j = i + (3 if raw else 1)
        parts, buf, nested = [], [], False

        def flush():
            if buf:
                parts.append(('t', ''.join(buf)))
                buf.clear()

        while j < n:
            c = s[j]
            if raw:
                if s.startswith('"""', j):
                    k = j
                    while k < n and s[k] == '"':
                        k += 1
                    buf.append('"' * (k - j - 3))
                    j = k
                    flush()
                    return Lit(i, j, True, parts, nested), j
            else:
                if c == '\\':
                    nx = s[j + 1] if j + 1 < n else ''
                    if nx == 'u':
                        buf.append(chr(int(s[j + 2:j + 6], 16)))
                        j += 6
                    else:
                        buf.append(ESC.get(nx, nx))
                        j += 2
                    continue
                if c == '"':
                    j += 1
                    flush()
                    return Lit(i, j, False, parts, nested), j
                if c == '\n':
                    raise ValueError('unterminated string at %d' % i)
            if c == '$' and j + 1 < n:
                if s[j + 1] == '{':
                    k, has_str = self.match_template(j + 2)
                    flush()
                    parts.append(('e', s[j + 2:k], False))
                    nested = nested or has_str
                    j = k + 1
                    continue
                m = IDENT.match(s, j + 1)
                if m:
                    flush()
                    parts.append(('e', m.group(0), True))
                    j = m.end()
                    continue
            buf.append(c)
            j += 1
        raise ValueError('unterminated string at %d' % i)

    def match_template(self, k):
        """Index of the } closing a ${ that starts before k; and whether the expression holds string literals."""
        s, n = self.s, self.n
        depth, has_str = 1, False
        while k < n:
            c = s[k]
            if s.startswith('//', k):
                k = s.find('\n', k)
            elif s.startswith('/*', k):
                k = self.skip_block_comment(k)
            elif c == '"':
                _, k = self.read_string(k)
                has_str = True
            elif c == "'":
                k = self.skip_char(k)
            elif c == '{':
                depth += 1
                k += 1
            elif c == '}':
                depth -= 1
                if depth == 0:
                    return k, has_str
                k += 1
            else:
                k += 1
        raise ValueError('unterminated template')


# ------------------------------------------------------------------------------------------------ deciding

PH = re.compile(r'\{([A-Za-z_][A-Za-z0-9_]*)\}')

# calls whose string arguments are never UI text
EXCLUDED_CALLS = {
    'println', 'print', 'require', 'requireNotNull', 'check', 'checkNotNull', 'error', 'TODO', 'assert',
    'Regex', 'toRegex', 'compile', 'matches', 'startsWith', 'endsWith', 'contains', 'equals', 'removePrefix', 'removeSuffix',
    'removeSurrounding', 'substringBefore', 'substringAfter', 'substringBeforeLast', 'substringAfterLast', 'split', 'replace',
    'replaceFirst', 'indexOf', 'lastIndexOf', 'trim', 'trimEnd', 'trimStart', 'lines', 'padStart', 'padEnd', 'repeat',
    'getString', 'putString', 'getBoolean', 'putBoolean', 'getInt', 'putInt', 'getLong', 'putLong', 'getFloat', 'putFloat',
    'getStringSet', 'putStringSet', 'remove', 'put', 'get', 'opt', 'optString', 'optInt', 'optBoolean', 'optLong',
    'optDouble', 'optJSONObject', 'optJSONArray', 'getJSONObject', 'getJSONArray', 'has', 'isNull', 'getStringExtra',
    'getIntExtra', 'getBooleanExtra', 'getLongExtra', 'putExtra', 'hasExtra', 'getSharedPreferences', 'Intent', 'setAction',
    'addCategory', 'setType', 'setPackage', 'setClassName', 'ComponentName', 'parse', 'File', 'get', 'exec', 'ProcessBuilder',
    'runCommand', 'execute', 'evaluateJavascript', 'loadUrl', 'loadData', 'loadDataWithBaseURL', 'addJavascriptInterface',
    'testTag', 'key', 'remember', 'rememberSaveable', 'LaunchedEffect', 'DisposableEffect', 'navigate', 'composable',
    'toMediaType', 'toMediaTypeOrNull', 'header', 'addHeader', 'setHeader', 'url', 'setRequestProperty', 'JSONObject',
    'JSONArray', 'parseColor', 'SimpleDateFormat', 'ofPattern', 'format', 'pushStringAnnotation', 'addStringAnnotation',
    'getStringAnnotations', 'StringAnnotation', 'withLink', 'LinkAnnotation', 'Url', 'Clickable', 'setProperty', 'getProperty',
    'getenv', 'putenv', 'environment', 'command', 'mimeType', 'getColumnIndex', 'query', 'rawQuery', 'execSQL', 'd', 'w', 'i',
    'v', 'e', 'wtf', 'log', 'dlog', 'logD', 'logE', 'logW', 'debugLog', 'Log', 'sendBroadcast', 'registerReceiver', 'IntentFilter',
    'getSystemService', 'createNotificationChannel', 'NotificationChannel', 'NotificationChannelGroup', 'Builder',
    'setChannelId', 'setGroup', 'setSortKey', 'setShortcutId', 'withName', 'named', 'Thread', 'thread', 'HandlerThread',
    'newPlainText', 'ClipData', 'getQueryParameter', 'appendQueryParameter', 'appendPath', 'path', 'scheme', 'authority',
    'fromParts', 'encode', 'decode', 'escape', 'quote', 'shellQuote', 'shQuote', 'q', 'sq', 'shell', 'sh', 'run', 'su', 'adb',
    'bash', 'runShell', 'runRoot', 'runAsRoot', 'rootExec', 'execRoot', 'cmd', 'send', 'sendText', 'sendCommand', 'write',
    'writeText', 'appendText', 'resolve', 'Paths', 'of', 'getAsset', 'open', 'list', 'readAsset', 'openAsset', 'assets',
    'emit', 'tryEmit', 'postJson', 'post', 'request', 'call', 'invoke', 'rpc', 'notify', 'callTool', 'tool', 'prop',
    'hidden', 'jsonObjectOf', 'jsonOf', 'buildJsonObject', 'JsonPrimitive', 'setStatus', 'MainScope',
    'WebResourceResponse', 'setStatusCodeAndReasonPhrase',
}
EXCLUDED_NAMES = {
    'tag', 'key', 'id', 'route', 'testTag', 'contentType', 'mimeType', 'url', 'path', 'command', 'cmd', 'packageName',
    'action', 'scheme', 'host', 'channelId', 'prefKey', 'fileName', 'filename', 'extension', 'pattern', 'regex', 'prefix',
    'suffix', 'separator', 'delimiter', 'language', 'lang', 'mime', 'uri', 'icon', 'iconName', 'shortcutId', 'workName',
    'kind', 'type', 'name_', 'model', 'modelId', 'agent', 'server', 'serverName', 'toolName', 'method', 'endpoint', 'origin',
    'baseUrl', 'script', 'js', 'css', 'html', 'code', 'json', 'body_', 'query', 'value_', 'raw', 'format', 'locale', 'TAG',
}
STRONG_CALLS = {'Text', 'BasicText', 'ClickableText', 'showSnackbar', 'Snackbar', 'setContentTitle', 'setContentText',
                'setSubText', 'setTicker', 'setBigContentTitle', 'bigText', 'setSummaryText', 'setTitle', 'setMessage',
                'setPositiveButton', 'setNegativeButton', 'setNeutralButton'}
STRONG_ARG1_CALLS = {'makeText', 'createChooser', 'addAction'}
STRONG_NAMES = {'text', 'title', 'label', 'placeholder', 'contentDescription', 'subtitle', 'description', 'message', 'hint',
                'supportingText', 'confirmText', 'dismissText', 'confirmLabel', 'dismissLabel', 'buttonText', 'buttonLabel',
                'actionLabel', 'emptyText', 'emptyTitle', 'emptyMessage', 'header', 'headline', 'caption', 'tooltip',
                'summary', 'detail', 'details', 'errorMessage', 'statusMessage', 'error', 'status', 'note', 'info', 'warning',
                'about', 'helper', 'helperText', 'onClickLabel', 'stateDescription', 'heading', 'question', 'answer',
                'positiveText', 'negativeText', 'confirmButtonText', 'dismissButtonText', 'actionText', 'reason',
                'explanation', 'tip', 'blurb', 'displayName'}
DATA_NAMES = {'errorMessage', 'statusMessage', 'contentDescription'}

COMMANDS = ('pkg ', 'apt ', 'apt-get ', 'cd ', 'ls ', 'git ', 'npm ', 'pnpm ', 'npx ', 'node ', 'python', 'pip ', 'sh ',
            'bash ', 'su ', 'sudo ', 'chmod ', 'chown ', 'mkdir ', 'rm ', 'cat ', 'echo ', 'export ', 'curl ', 'wget ',
            'termux-', 'am ', 'pm ', 'adb ', 'cp ', 'mv ', 'tar ', 'unzip ', 'claude ', 'codex ', 'opencode ', 'agy ', 'gh ',
            'kill ', 'pkill ', 'ps ', 'find ', 'grep ', 'which ', 'command -v', 'source ', 'ssh ', 'proot', 'chroot ',
            'mount ', 'umount ', 'uname', 'getprop', 'setprop', 'cmd ', 'settings ', 'dumpsys', 'logcat', 'service ', 'ln ',
            'touch ', 'head ', 'tail ', 'sed ', 'awk ', 'java ', 'gradle', 'test ', 'if ', 'for ', 'set ', 'exec ', 'env ',
            'nohup ', 'timeout ', 'printf ', 'tee ', 'xargs ', 'sort ', 'uniq ', 'wc ', 'dpkg', 'rish ', 'android-su',
            'chrootctl', 'pip3 ', 'yarn ', 'flutter ', 'dart ', 'cargo ', 'go ', 'make ', 'cmake ', 'ninja ', 'apk ',
            'busybox', 'toybox', 'stat ', 'du ', 'df ', 'free ', 'top ', 'id ', 'whoami', 'true', 'false', 'exit')
CODE_BITS = ('://', '=>', '&&', '||', '$(', '`', '</', '/>', '\\', '#!/', '%s', '%d', '%.', '%1$', '%2$', '==', '!=',
             '->', '<-', '::', '()', '{}', '[]', '\t', '#{', '<?', '<!', 'Bearer ')


def code_like(lt, full):
    t = lt.strip()
    if not t:
        return True
    if any(b in lt for b in CODE_BITS):
        return True
    if t.startswith(('/', '~/', './', '../', '-', '#', '.', '{', '[', '<', '@', '*', '$', '=', '|', '>', '+', '^')):
        return True
    low = t.lower()
    if low.startswith(COMMANDS) and not re.match(r'^(If|For|Set|Test|Find|Free|Top|Make|Go|Sort|Source|Id|Exit) ', t):
        if not t[:1].isupper():
            return True
    if re.search(r'\S=\S', lt) or re.search(r'\w\(\w*\)', lt) or re.search(r'\S;\S|\);|;\s*$', lt):
        return True
    if lt.count('"') and re.search(r'"\s*:\s*["{\[\d]', lt):
        return True
    if re.search(r'[A-Za-z]/\d', lt) or 'Mozilla/' in lt:
        return True
    # date patterns (SimpleDateFormat), Content-Security-Policy rules
    if re.fullmatch(r"[EMdyHhmsaSzZXkK'GT ,.:/\-]+", PH.sub('', full).strip() or 'x') and re.search(r'yy|MM|HH|mm|EEE|Hm', lt):
        return True
    if re.search(r"'(self|none|unsafe-[a-z]+)'|\b[a-z]+-src\b|\bbase-uri\b|\bform-action\b", lt):
        return True
    if PH.search(lt):  # literal {braces} would turn into placeholders
        return True
    words = re.findall(r"[A-Za-z][A-Za-z0-9_'’.-]*", PH.sub(' ', full))
    if len(words) >= 2:
        caps = sum(1 for w in words if re.fullmatch(r'[A-Z0-9_]{2,}', w))
        if caps / len(words) > 0.6:
            return True
        ident = sum(1 for w in words if '_' in w or re.search(r'[a-z][A-Z]', w) or re.search(r'\w\.\w', w))
        if ident / len(words) > 0.5:
            return True
    return False


def nl_kind(full, lt):
    """multi / cap / caps / other / None for text that has no letters or looks like code."""
    t = PH.sub(' ', full).strip()
    if not re.search(r'[A-Za-z]', t):
        return None
    if code_like(lt, full):
        return None
    words = re.findall(r"[A-Za-z][A-Za-z'’]*", t)
    if re.search(r'\s', t) and len(words) >= 2:
        return 'multi'
    if re.fullmatch(r"[A-Z][a-z]+(?:[-'’][a-z]+)*[!?.:…]*", t):
        return 'cap'
    if re.fullmatch(r"[A-Z]{2,6}[!?.:…]*", t):
        return 'caps'
    if re.fullmatch(r"[A-Za-z][A-Za-z0-9'’+&-]*[!?.:…]*", t):
        return 'other'
    return None


def enclosing(mask, start):
    """(callee, receiver, arg_index, named_arg, is_annotation, lambda_assign) for a literal at start."""
    depth = 0
    k = start - 1
    commas = 0
    seg_start = None
    while k >= 0:
        c = mask[k]
        if c in ')]}':
            depth += 1
        elif c in '([{':
            if depth == 0:
                if c == '{':
                    # require(...) { "message" }: an exception message
                    head = ''.join(mask[max(0, k - 400):k]).rstrip()
                    if head.endswith(')'):
                        d, j = 0, len(head) - 1
                        while j >= 0:
                            if head[j] == ')':
                                d += 1
                            elif head[j] == '(':
                                d -= 1
                                if d == 0:
                                    break
                            j -= 1
                        if re.search(r'\b(require|check|requireNotNull|checkNotNull|assert)\s*$', head[:max(j, 0)]):
                            return ('require', '', 0, None, False)
                    return None
                if c != '(':
                    return None
                head = ''.join(mask[max(0, k - 160):k])
                m = re.search(r'(@?)((?:[A-Za-z_]\w*\s*(?:\?\.|\.)\s*)*)([A-Za-z_]\w*)\s*(?:<[^()<>]*>)?\s*$', head)
                if seg_start is None:
                    seg_start = k + 1
                seg = ''.join(mask[seg_start:start])
                nm = re.match(r'\s*([A-Za-z_]\w*)\s*=(?!=)', seg)
                if not m:
                    return ('', '', commas, nm.group(1) if nm else None, False)
                recv = re.sub(r'\s', '', m.group(2)).rstrip('.?')
                return (m.group(3), recv, commas, nm.group(1) if nm else None, m.group(1) == '@')
            depth -= 1
        elif c == ',' and depth == 0:
            if seg_start is None:
                seg_start = k + 1
            commas += 1
        k -= 1
    return None


def const_spans(mask_s):
    spans = []
    for m in re.finditer(r'\bconst\s+val\b', mask_s):
        k = mask_s.find('=', m.end())
        if k < 0:
            continue
        depth = 0
        j = k + 1
        n = len(mask_s)
        while j < n:
            c = mask_s[j]
            if c in '([{':
                depth += 1
            elif c in ')]}':
                depth -= 1
                if depth < 0:
                    break
            elif c == '\n' and depth == 0:
                line = mask_s[mask_s.rfind('\n', 0, j) + 1:j].rstrip()
                nxt = mask_s[j + 1:j + 200].lstrip()
                if not re.search(r'[+(\-*/,=:|&?]$', line) and not nxt.startswith(('+', '.', '?:', '&&', '||', '?.')):
                    break
            j += 1
        spans.append((m.start(), j))
    return spans


def is_logic(mask_s, lit):
    before = mask_s[max(0, lit.start - 200):lit.start].rstrip()
    after = mask_s[lit.end:lit.end + 400]
    if re.search(r'(==|!=|===|!==)$', before) or re.match(r'\s*(==|!=)', after):
        return True
    if re.match(r'\s*(,\s*"[^"\n]*"\s*)*->', after):
        return True
    if before.endswith('[') or re.search(r'\b(in|is)$', before):
        return True
    return False


def ph_name(expr, short):
    e = expr.strip()
    if short:
        base = e
    else:
        e2 = re.sub(r'\s+', '', e).replace('!!', '').replace('?.', '.')
        if re.fullmatch(r'[A-Za-z_]\w*', e2):
            base = e2
        else:
            m = re.fullmatch(r'(?:[\w.]+\.)?([A-Za-z_]\w*)\.([A-Za-z_]\w*)(?:\(\))?', e2)
            m1 = re.fullmatch(r'(?:[\w.]+\.)?([A-Za-z_]\w*)(?:\(\))?', e2)
            m2 = re.fullmatch(r'(?:[\w.]+\.)?([A-Za-z_]\w*)\(.*\)', e2, re.S)
            if m and m.group(2) in ('size', 'length', 'count'):
                base = m.group(1) + 'Count'
            elif m and m.group(2) in ('toString', 'trim', 'lowercase', 'uppercase', 'orEmpty', 'name', 'value', 'label', 'title', 'text') \
                    and m.group(2) in ('toString', 'trim', 'lowercase', 'uppercase', 'orEmpty'):
                base = m.group(1)
            elif m1:
                base = m1.group(1)
            elif m2 and m2.group(1) not in ('format', 'let', 'run', 'also', 'apply', 'joinToString', 'takeIf', 'ifBlank',
                                            'ifEmpty', 'coerceAtLeast', 'coerceAtMost', 'coerceIn', 'toInt', 'roundToInt',
                                            'toString', 'get', 'getOrNull', 'first', 'firstOrNull', 'last', 'take', 'drop'):
                base = m2.group(1)
            else:
                base = 'value'
    if base in ('it', 'this', 'toString', 'get', 'invoke') or base in KEYWORDS or not re.fullmatch(r'[A-Za-z_]\w*', base):
        base = 'value'
    if base.startswith('_'):
        base = base.lstrip('_') or 'value'
    base = base[0].lower() + base[1:]
    if base in KEYWORDS:
        base = 'value'
    return base


def build_text(lit):
    """(english text with {placeholders}, [(name, expr)]) for a regular string literal."""
    names = OrderedDict()   # expr -> name
    used = set()
    out = []
    for p in lit.parts:
        if p[0] == 't':
            out.append(p[1])
            continue
        expr = p[1].strip()
        if expr not in names:
            nm = ph_name(expr, p[2])
            base, k = nm, 2
            while nm in used:
                nm = '%s%d' % (base, k)
                k += 1
            used.add(nm)
            names[expr] = nm
        out.append('{%s}' % names[expr])
    return ''.join(out), [(nm, expr) for expr, nm in names.items()]


def camel(word):
    w = re.sub(r'[^A-Za-z0-9]', '', word)
    return (w[:1].lower() + w[1:]) if w else ''


def namespace(path):
    rel = os.path.relpath(path, JAVA).replace(os.sep, '/')
    parts = rel.split('/')
    dirs, stem = parts[:-1], parts[-1][:-3]
    if dirs and dirs[0] in ('ui', 'data', 'domain'):
        dirs = dirs[1:]
    area = camel(dirs[0]) if dirs else 'app'
    if area in ('common',) or area in KEYWORDS:
        area += 'Ui'
    f = stem
    if f.lower().startswith(area.lower()) and len(f) > len(area) and f[len(area)].isupper():
        f = f[len(area):]
    for suf in ('SubScreen', 'Screen', 'Dialog', 'Page', 'Sheet', 'Panel', 'Content'):
        if f.endswith(suf) and len(f) > len(suf):
            f = f[:-len(suf)]
            break
    f = camel(f) or 'main'
    if f in KEYWORDS:
        f += 'Ui'
    return '%s.%s' % (area, f)


def slug(text):
    t = PH.sub(lambda m: ' %s ' % m.group(1), text).replace("'", '').replace('’', '')
    words = re.findall(r'[A-Za-z0-9]+', t)[:6]
    if not words:
        return 'text'
    s = ''
    for n, w in enumerate(words):
        piece = w.lower() if n == 0 else (w[0].upper() + w[1:].lower())
        if n > 0 and len(s) + len(piece) > 40:
            break
        s += piece
    if s[0].isdigit():
        s = 'n' + s
    if s in KEYWORDS:
        s += 'Text'
    return s


def scope_of(path):
    rel = os.path.relpath(path, JAVA).replace(os.sep, '/')
    name = os.path.basename(rel)
    if rel.startswith('i18n/'):
        return None
    if name.endswith('Mcp.kt') or '/mcp/' in rel or 'Prompt' in name or name in (
            'GlobalContext.kt', 'ThemeTokens.kt', 'PromptExpansion.kt', 'JarvisSkill.kt', 'WebViewExtensionDeveloper.kt',
            'CrashHandler.kt', 'ChatWebViewCache.kt', 'BrowserIdentity.kt'):
        return None
    if rel.startswith(('ui/', 'theme/', 'crash/')) or '/' not in rel:
        return 'ui'
    if DATA_AS_UI and rel.startswith(('data/', 'domain/')):
        return 'ui'
    if rel.startswith(('data/', 'domain/')):
        return 'data'
    return None


def kotlin_files(paths):
    out = []
    for p in paths:
        p = os.path.abspath(p)
        if os.path.isfile(p) and p.endswith('.kt'):
            out.append(p)
        else:
            for d, _, fs in os.walk(p):
                out += [os.path.join(d, f) for f in fs if f.endswith('.kt')]
    return sorted(set(out))



# ------------------------------------------------------------------------------------------------ "a" + x + "b" chains

def _skip_ws(mask, j, allow_newline):
    n = len(mask)
    while j < n and mask[j] in ' \t' or (allow_newline and j < n and mask[j] == '\n'):
        j += 1
    return j


def _balanced(mask, j):
    """Index after the bracket group starting at j (mask[j] in '([{'), or -1."""
    o = mask[j]
    cl = {'(': ')', '[': ']', '{': '}'}[o]
    d = 0
    n = len(mask)
    while j < n:
        if mask[j] == o:
            d += 1
        elif mask[j] == cl:
            d -= 1
            if d == 0:
                return j + 1
        j += 1
    return -1


def operand_end(mask, j):
    """End of a simple operand (name, call, member chain, (...), when ...) starting at j, or -1."""
    n = len(mask)
    m = IDENT.match(mask, j)
    if mask[j] == '(':
        k = _balanced(mask, j)
    elif m and m.group(0) == 'when':
        k = _skip_ws(mask, m.end(), False)
        if k < n and mask[k] == '(':
            k = _skip_ws(mask, _balanced(mask, k), False)
        if k >= n or mask[k] != '{':
            return -1
        return _balanced(mask, k)
    elif m and m.group(0) not in KEYWORDS - {'this'}:
        k = m.end()
    elif re.match(r'\d', mask[j]):
        k = re.match(r'[\d_.]+[LlFf]?', mask[j:]).end() + j
    else:
        return -1
    if k < 0:
        return -1
    while k < n:
        if mask.startswith('!!', k):
            k += 2
        elif mask[k] in '([':
            e = _balanced(mask, k)
            if e < 0:
                return -1
            k = e
        elif re.match(r'[ \t]*\{', mask[k:k + 40]) and re.match(r'[\w)]', mask[k - 1]):
            e = _balanced(mask, mask.index('{', k))
            if e < 0:
                return -1
            k = e
        else:
            q = _skip_ws(mask, k, True)
            if mask.startswith('?.', q) or (q < n and mask[q] == '.' and not mask.startswith('..', q)):
                q2 = _skip_ws(mask, q + (2 if mask.startswith('?.', q) else 1), False)
                m2 = IDENT.match(mask, q2)
                if not m2:
                    return -1
                k = m2.end()
            else:
                break
    return k


def operand_start(mask, end):
    """Start of the simple operand ending at end (exclusive), or -1."""
    k = end
    while k > 0:
        c = mask[k - 1]
        if c in ')]}':
            o = {')': '(', ']': '[', '}': '{'}[c]
            d = 0
            j = k - 1
            while j >= 0:
                if mask[j] == c:
                    d += 1
                elif mask[j] == o:
                    d -= 1
                    if d == 0:
                        break
                j -= 1
            if j < 0:
                return -1
            k = j
        elif re.match(r'[\w!]', c):
            k -= 1
        elif c == '.' or (c == '?' and k < len(mask) and mask[k] == '.'):
            k -= 1
        elif c in ' \t' and k < end and mask[k] in '({' and re.search(r'(\w|\))[ \t]+$', mask[max(0, k - 80):k]):
            # "ifBlank {", "when (", ") {": the group belongs to what comes before it
            while k > 0 and mask[k - 1] in ' \t':
                k -= 1
        else:
            break
    while k < end and mask[k] in ' \t\n':
        k += 1
    head = mask[k:end]
    if not head or re.match(r'\d', head) and not re.fullmatch(r'[\d_.]+[LlFf]?', head):
        return -1
    if re.match(r'(if|else|return|in|is|as|to|and|or)\b', head):
        return -1
    return k


def expand_chain(mask, src, lits_by_start, g):
    """[('lit', Lit) | ('expr', text)] and the span of the whole "a" + x + "b" chain around group g, or None."""
    items = [('lit', l) for l in g]
    start, end = g[0].start, g[-1].end
    # leftwards: operand + ...
    while True:
        b = mask[:start].rstrip(' \t\n')
        if not b.endswith('+') or b.endswith('++'):
            break
        oe = len(b) - 1
        oe2 = len(mask[:oe].rstrip(' \t\n'))
        if oe2 in [l.end for l in lits_by_start.values()]:
            lit = next(l for l in lits_by_start.values() if l.end == oe2)
            if lit.raw:
                return None
            items.insert(0, ('lit', lit))
            start = lit.start
            continue
        os_ = operand_start(mask, oe2)
        if os_ < 0:
            return None
        items.insert(0, ('expr', src[os_:oe2]))
        start = os_
    # rightwards: + operand ...
    while True:
        m = re.match(r'[ \t]*\+(?![+=])[ \t]*\n?[ \t]*', mask[end:])
        if not m or mask[end:end + m.end()].count('\n') > 1:
            break
        j = end + m.end()
        if j in lits_by_start:
            lit = lits_by_start[j]
            if lit.raw:
                return None
            items.append(('lit', lit))
            end = lit.end
            continue
        oe = operand_end(mask, j)
        if oe < 0:
            return None
        items.append(('expr', src[j:oe]))
        end = oe
    # what is around must bind looser than +
    b = mask[:start].rstrip(' \t')
    a = mask[end:].lstrip(' \t')
    if b.endswith(')'):
        d, j = 0, len(b) - 1
        while j >= 0:
            d += 1 if b[j] == ')' else -1 if b[j] == '(' else 0
            if d == 0:
                break
            j -= 1
        if re.search(r'\bif\s*$', b[:max(j, 0)]):
            b = b + ' else'
    if re.search(r'([*/%!.\-\w)\]}]|\bas\??)$', b) and not re.search(r'\b(return|else|in|to|is)$', b) or \
            re.match(r'([*/%\-{]|\.(?!\.)|\?\.|as\b|\[|\()', a):
        return None
    if any(k == 'expr' and (not it.strip() or it.strip()[0] in '{}') for k, it in items):
        return None
    if not any(k == 'expr' for k, _ in items):
        return None
    if items[0][0] == 'expr' and not any(k == 'lit' for k, _ in items[:2]):
        return None
    return items, start, end

# ------------------------------------------------------------------------------------------------ main pass


class Candidate:
    def __init__(self, path, lits, text, args, start, end, line):
        self.path, self.lits, self.text, self.args, self.start, self.end, self.line = path, lits, text, args, start, end, line
        self.key = None


def analyse(path, src, logic_only=False, logic=None):
    lx = Lexer(src).run()
    mask_s = ''.join(lx.mask)
    lits = lx.lits
    found, left, logic_texts = [], [], set()
    for lit in lits:
        if not lit.raw and not lit.has_template and is_logic(mask_s, lit):
            logic_texts.add(lit.literal_text)
        if not lit.raw and not lit.has_template:
            enc = enclosing(lx.mask, lit.start)
            if enc and enc[0] in ('startsWith', 'endsWith', 'contains', 'equals', 'removePrefix', 'removeSuffix',
                                  'substringBefore', 'substringAfter', 'substringBeforeLast', 'substringAfterLast',
                                  'indexOf', 'split', 'setOf', 'hashSetOf') and enc[0] != '':
                logic_texts.add(lit.literal_text)
    if logic_only:
        return logic_texts
    scope = scope_of(path)
    if scope is None:
        return found, left
    if re.search(r'\b(class|object|interface|val|var|fun)\s+L\b|<\s*L\s*[,>]', mask_s):
        return found, left
    consts = const_spans(mask_s)
    lits_by_start = {l.start: l for l in lits}
    # `// i18n: keep` lines and `// i18n: off` … `// i18n: on` regions
    kept = []
    for m in re.finditer(r'//\s*i18n:\s*keep[^\n]*', src):
        a = src.rfind('\n', 0, m.start()) + 1
        kept.append((a, m.end()))
    for m in re.finditer(r'//\s*i18n:\s*off\b', src):
        e = re.compile(r'//\s*i18n:\s*on\b').search(src, m.end())
        kept.append((m.start(), e.start() if e else len(src)))
    consts += kept

    def line_of(pos):
        return src.count('\n', 0, pos) + 1

    # group "a" + "b" chains
    groups, i = [], 0
    while i < len(lits):
        g = [lits[i]]
        while i + 1 < len(lits) and re.fullmatch(r'\s*\+\s*', mask_s[lits[i].end:lits[i + 1].start]):
            i += 1
            g.append(lits[i])
        groups.append(g)
        i += 1

    taken_until = -1
    for g in groups:
        if g[0].start < taken_until:
            continue
        first, last = g[0], g[-1]
        full = ''.join(build_text(l)[0] for l in g) if not any(l.raw for l in g) else ''
        lt = ''.join(l.literal_text for l in g)
        line = line_of(first.start)
        if any(l.raw for l in g):
            if re.search(r'[A-Za-z]{3,} [A-Za-z]{3,}', lt) and scope == 'ui':
                left.append((line, 'raw string', lt[:90]))
            continue
        kind = nl_kind(full, lt)
        if kind is None:
            continue
        if any(a <= first.start < b for a, b in consts):
            continue
        before = mask_s[max(0, first.start - 200):first.start].rstrip()
        after = mask_s[last.end:last.end + 200]
        chain = None
        if before.endswith('+') or re.match(r'\s*\+', after):
            chain = expand_chain(mask_s, src, lits_by_start, g)
            if chain is None:
                if kind in ('multi', 'cap') or scope == 'ui':
                    left.append((line, 'joined with + to code', full[:90]))
                continue
            items, cstart, cend = chain
            g = [l for k2, l in items if k2 == 'lit']
            first, last = g[0], g[-1]
            if any(re.search(r'[A-Za-z]{2,}', l.literal_text) is None for l in g) and not any(
                    re.search(r'[A-Za-z]{3,} ', l.literal_text) for l in g):
                continue
        if is_logic(mask_s, first) or is_logic(mask_s, last):
            continue
        enc = enclosing(lx.mask, first.start)
        callee, recv, argi, named, annot = enc if enc else (None, '', 0, None, False)
        # only shown, never stored: safe even when the same words are compared somewhere
        display_only = (callee in STRONG_CALLS and named in (None, 'text')) or (callee in STRONG_ARG1_CALLS and argi == 1)
        if not first.has_template and len(g) == 1 and lt in logic and not display_only:
            if kind in ('multi', 'cap'):
                left.append((line, 'compared elsewhere', full[:90]))
            continue
        assign = re.search(r'(?<![=!<>])\b([A-Za-z_]\w*)\s*=$', before)
        target = named or (assign.group(1) if assign and not enc else None) or (assign.group(1) if assign and enc and named is None and before.endswith('=') and re.search(r'\{[^{}]*\b' + assign.group(1) + r'\s*=$', before) else None)
        if annot:
            continue
        if target == 'label' and callee and (callee.startswith('animate') or 'Transition' in callee or callee in (
                'Crossfade', 'AnimatedContent', 'AnimatedVisibility', 'infiniteRepeatable', 'tween', 'spring')):
            continue
        if callee and (callee in EXCLUDED_CALLS and not (callee in STRONG_ARG1_CALLS)) and not (callee in STRONG_CALLS):
            if recv in ('Log', 'Timber', 'logger', 'android.util.Log') or callee in ('println', 'print', 'log', 'Log'):
                continue
            if callee in ('error', 'require', 'check', 'requireNotNull', 'checkNotNull', 'TODO', 'assert'):
                continue
            if callee == 'get' or callee == 'put':
                continue
            continue
        if callee and (callee.endswith('Exception') or callee.endswith('Error') or callee == 'Throwable'):
            continue
        if callee in ('mapOf', 'mutableMapOf', 'hashMapOf', 'linkedMapOf') and re.match(r'\s*to\b', after):
            continue
        if target in EXCLUDED_NAMES:
            continue
        if recv in ('Log', 'Timber', 'logger'):
            continue
        strong = (callee in STRONG_CALLS and named in (None, 'text', 'title', 'message')) or \
                 (callee in STRONG_ARG1_CALLS and argi == 1) or (target in STRONG_NAMES)
        if scope == 'data':
            if not (strong and (callee in STRONG_CALLS or callee in STRONG_ARG1_CALLS or target in DATA_NAMES)):
                continue
        if kind == 'caps' and not strong:
            continue
        if kind == 'other' and not (callee in STRONG_CALLS and named in (None, 'text')):
            continue
        if kind in ('other', 'caps') and re.search(r'[a-z][A-Z]', full):
            continue
        if kind == 'other' and len(PH.sub('', full).strip()) < 2:
            continue
        text, args = '', []
        if chain:
            parts = []
            for k2, it in chain[0]:
                parts += it.parts if k2 == 'lit' else [('e', it.strip(), False)]
            text, args = build_text(Lit(0, 0, False, parts, False))
            if PH.sub('', text).strip() == '' or not re.search(r'[A-Za-z]{2,}', PH.sub('', text)):
                continue
        else:
            for l in g:
                t, a = build_text(l)
                text += t
                args += a
        # placeholders named apart across joined pieces
        seen, fixed = {}, []
        for nm, ex in args:
            if ex in seen:
                continue
            base, k, n2 = nm, 2, nm
            while n2 in seen.values():
                n2 = '%s%d' % (base, k)
                k += 1
            if n2 != nm:
                text = text.replace('{%s}' % nm, '{%s}' % n2, 1)
            seen[ex] = n2
            fixed.append((n2, ex))
        found.append(Candidate(path, g, text, fixed, chain[1] if chain else first.start, chain[2] if chain else last.end, line))
        taken_until = chain[2] if chain else last.end
    return found, left


def prune():
    every = kotlin_files([JAVA])
    used = set()
    for p in every:
        src = open(p, encoding='utf-8').read()
        for chain in re.findall(r'\bL\.([a-z][A-Za-z0-9]*(?:\.[a-z][A-Za-z0-9]*)+)', src):
            segs = chain.split('.')
            used |= {'.'.join(segs[:n]) for n in range(2, len(segs) + 1)}
        if 'import ai.antigem.i18n.L\n' in src and not re.search(r'\bL\.[a-z]', src.replace('import ai.antigem.i18n.L\n', '')):
            open(p, 'w', encoding='utf-8').write(src.replace('import ai.antigem.i18n.L\n', ''))
            print('unused import removed: %s' % os.path.relpath(p, ROOT))
    en = json.load(open(EN_JSON, encoding='utf-8'), object_pairs_hook=OrderedDict)
    keep = OrderedDict((k, v) for k, v in en['strings'].items() if k in used or any(u.startswith(k + '.') for u in ()) )
    dropped = [k for k in en['strings'] if k not in keep]
    en['strings'] = keep
    if 'notes' in en:
        en['notes'] = OrderedDict((k, v) for k, v in en['notes'].items() if k in keep)
    with open(EN_JSON, 'w', encoding='utf-8') as f:
        json.dump(en, f, ensure_ascii=False, indent=2)
        f.write('\n')
    print('dropped %d unused keys, %d left' % (len(dropped), len(keep)))


DATA_AS_UI = False


def main():
    global DATA_AS_UI
    if '--data' in sys.argv:
        sys.argv.remove('--data')
        DATA_AS_UI = True
    if len(sys.argv) >= 2 and sys.argv[1] == 'prune':
        prune()
        return
    if len(sys.argv) < 2 or sys.argv[1] not in ('report', 'apply'):
        print(__doc__)
        sys.exit(1)
    mode = sys.argv[1]
    paths = sys.argv[2:] or [JAVA]
    every = kotlin_files([JAVA])
    files = kotlin_files(paths)

    sources = {p: open(p, encoding='utf-8').read() for p in every}
    logic = set()
    for p in every:
        try:
            logic |= analyse(p, sources[p], logic_only=True)
        except ValueError as e:
            print('skip (lexer) %s: %s' % (p, e), file=sys.stderr)

    en = json.load(open(EN_JSON, encoding='utf-8'), object_pairs_hook=OrderedDict)
    strings = en['strings']
    existing_by_ns_text = {}
    for k, v in strings.items():
        if isinstance(v, str):
            existing_by_ns_text[(k.rsplit('.', 1)[0], v)] = k
    used_leaves = defaultdict(set)
    for k in strings:
        ns, leaf = k.rsplit('.', 1)
        used_leaves[ns].add(leaf)

    per_file, leftovers = {}, []
    for p in files:
        try:
            found, left = analyse(p, sources[p], logic=logic)
        except ValueError as e:
            print('skip (lexer) %s: %s' % (p, e), file=sys.stderr)
            continue
        per_file[p] = found
        rel = os.path.relpath(p, ROOT)
        leftovers += ['%s:%d: %s: %s' % (rel, ln, why, t.replace('\n', '\\n')) for ln, why, t in left]

    # short texts used in 3+ namespaces go to common
    spread = defaultdict(set)
    for p, found in per_file.items():
        for c in found:
            if not c.args and len(c.text.split()) <= 4 and len(c.text) <= 40:
                spread[c.text].add(namespace(p))
    common = {t for t, nss in spread.items() if len(nss) >= 3}

    new_keys = OrderedDict()
    for p, found in per_file.items():
        ns_file = namespace(p)
        for c in found:
            ns = 'common' if c.text in common else ns_file
            k = existing_by_ns_text.get((ns, c.text))
            if not k:
                base = slug(c.text)
                leaf, n = base, 2
                while leaf in used_leaves[ns]:
                    leaf = '%s%d' % (base, n)
                    n += 1
                used_leaves[ns].add(leaf)
                k = '%s.%s' % (ns, leaf)
                existing_by_ns_text[(ns, c.text)] = k
                new_keys[k] = c.text
            c.key = k

    total = sum(len(f) for f in per_file.values())
    by_area = defaultdict(int)
    for p, found in per_file.items():
        by_area[namespace(p).split('.')[0]] += len(found)
    print('texts found: %d in %d files, new keys: %d (common: %d), leftovers: %d' % (
        total, sum(1 for f in per_file.values() if f), len(new_keys), len(common), len(leftovers)))
    for a, n in sorted(by_area.items(), key=lambda x: -x[1])[:40]:
        print('  %-20s %d' % (a, n))
    with open(LEFTOVERS, 'w', encoding='utf-8') as f:
        f.write('\n'.join(leftovers) + '\n')
    print('leftovers: %s' % LEFTOVERS)

    if mode == 'report':
        with open('/tmp/i18n_found.txt', 'w', encoding='utf-8') as f:
            for p, found in per_file.items():
                for c in found:
                    call = 'L.%s' % c.key + ('(%s)' % ', '.join(re.sub(r'\s+', ' ', e) for _, e in c.args) if c.args else '')
                    f.write('%s:%d: %s  <=  %s\n' % (os.path.relpath(p, ROOT), c.line, call, c.text.replace('\n', '\\n')))
        print('found: /tmp/i18n_found.txt')
        return

    # apply: rewrite files
    changed = 0
    for p, found in per_file.items():
        if not found:
            continue
        src = sources[p]
        for c in sorted(found, key=lambda c: -c.start):
            call = 'L.%s' % c.key
            if c.args:
                call += '(%s)' % ', '.join(e.strip() for _, e in c.args)
            src = src[:c.start] + call + src[c.end:]
        if 'import ai.antigem.i18n.L\n' not in src and not re.match(r'\s*package ai\.antigem\.i18n\b', src):
            imports = list(re.finditer(r'^import [^\n]+\n', src, re.M))
            if imports:
                at = imports[-1].end()
                src = src[:at] + 'import ai.antigem.i18n.L\n' + src[at:]
            else:
                m = re.search(r'^package [^\n]+\n', src, re.M)
                at = m.end() if m else 0
                src = src[:at] + '\nimport ai.antigem.i18n.L\n' + src[at:]
        with open(p, 'w', encoding='utf-8') as f:
            f.write(src)
        changed += 1

    # en.json: common first, then by namespace, each in the order found
    merged = OrderedDict(strings)
    merged.update(new_keys)
    order = list(merged.keys())
    pos = {k: n for n, k in enumerate(order)}
    ns_first = {}
    for k in order:
        ns_first.setdefault(k.rsplit('.', 1)[0], pos[k])
    ordered = sorted(order, key=lambda k: (0 if k.startswith('common.') else 1, k.rsplit('.', 1)[0], pos[k]))
    en['strings'] = OrderedDict((k, merged[k]) for k in ordered)
    with open(EN_JSON, 'w', encoding='utf-8') as f:
        json.dump(en, f, ensure_ascii=False, indent=2)
        f.write('\n')
    print('rewrote %d files, en.json now has %d texts' % (changed, len(en['strings'])))


if __name__ == '__main__':
    main()
