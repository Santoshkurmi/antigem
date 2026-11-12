#!/usr/bin/env node
/*
 * agapp: package and sign AntiGem mini apps (Node 18+, no dependencies).
 *
 *   node agapp.mjs keygen --developer "Name" --out dev.agkey [--force]
 *   node agapp.mjs sign <app-folder> --key dev.agkey [--out file.agapp]
 *   node agapp.mjs verify <file.agapp>
 *   node agapp.mjs info <file.agkey>
 *   node agapp.mjs publish --key dev.agkey [--repo .] [--apps apps] [--name "Catalog name"] [--force]
 *
 * publish: for a GitHub repository of mini apps. Signs every app folder in <repo>/<apps> that changed into
 * <repo>/dist/<id>-<version>.agapp and writes <repo>/antigem-mini-apps.json, the catalog AntiGem's "Get apps" screen
 * reads (package URL, sha256, size, developer and key fingerprint per app). Commit and push both.
 *
 * Password: env AGAPP_PASSWORD, else asked on the terminal (keygen asks twice).
 *
 * Package (.agapp) = ZIP with the app files at the root plus
 *   META-INF/AGAPP.json       {format, id, version, versionCode, files: {path: sha256hex}, signedAt}  (these bytes are signed)
 *   META-INF/AGAPP.CERT.json  {format, algorithm: "ECDSA-P256-SHA256", publicKey: base64 SPKI DER, developer}
 *   META-INF/AGAPP.SIG        base64 DER ECDSA signature (SHA256withECDSA) over AGAPP.json
 * Key (.agkey) = the EC P-256 PKCS8 private key, AES-256-GCM encrypted with a PBKDF2-SHA256 key from the password.
 * Fingerprint = sha256 of the SPKI DER.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const ID_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/;
const META = 'META-INF/';
const SIGNED = 'META-INF/AGAPP.json', CERT = 'META-INF/AGAPP.CERT.json', SIG = 'META-INF/AGAPP.SIG';
const ITERATIONS = 210000;
const INDEX = 'antigem-mini-apps.json';

class Fail extends Error {}
const fail = (msg) => { throw new Fail(msg); };
const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');
const b64 = (buf) => Buffer.from(buf).toString('base64');
const unb64 = (s) => Buffer.from(String(s || ''), 'base64');

/** First 16 hex chars of sha256(SPKI DER), uppercase, in groups of 4. */
const shortFingerprint = (spki) => sha256(spki).slice(0, 16).toUpperCase().match(/.{4}/g).join(':');

/**
 * version_code (a whole number, or a string of digits) if the manifest has one, else from "version": the part before
 * any - or +, a.b.c → a*1000000 + b*1000 + c (a part that isn't a number counts as 0). Same rule as the phone.
 */
function versionCode(m) {
  if (m.version_code !== undefined) {
    const v = m.version_code;
    if (Number.isInteger(v) && v >= 0 && v < 1e15) return v;
    if (typeof v === 'string' && /^\d{1,15}$/.test(v.trim())) return parseInt(v.trim(), 10);
    fail(`"version_code" must be a whole number (got ${JSON.stringify(v)})`);
  }
  const [a = 0, b = 0, c = 0] = String(m.version || '').split(/[-+]/)[0].split('.').map((x) => (/^\d+$/.test(x.trim()) ? parseInt(x.trim(), 10) : 0));
  return a * 1000000 + b * 1000 + c;
}

// ------------------------------------------------------------------ ZIP (just what we need)

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; }
  return t;
})();
function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function dosTime(d) {
  const time = (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1);
  const date = ((Math.max(1980, d.getFullYear()) - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate();
  return { time, date };
}

/** entries: [{name, data: Buffer}] → ZIP Buffer (deflate when smaller, UTF-8 names). */
function zipWrite(entries) {
  const { time, date } = dosTime(new Date());
  const locals = [], centrals = [];
  let offset = 0;
  for (const { name, data } of entries) {
    const nameBuf = Buffer.from(name, 'utf8');
    const deflated = zlib.deflateRawSync(data, { level: 9 });
    const method = deflated.length < data.length ? 8 : 0;
    const body = method === 8 ? deflated : data;
    const crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x0800, 6);
    local.writeUInt16LE(method, 8); local.writeUInt16LE(time, 10); local.writeUInt16LE(date, 12);
    local.writeUInt32LE(crc, 14); local.writeUInt32LE(body.length, 18); local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26); local.writeUInt16LE(0, 28);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8); central.writeUInt16LE(method, 10); central.writeUInt16LE(time, 12);
    central.writeUInt16LE(date, 14); central.writeUInt32LE(crc, 16); central.writeUInt32LE(body.length, 20);
    central.writeUInt32LE(data.length, 24); central.writeUInt16LE(nameBuf.length, 28);
    central.writeUInt32LE(offset, 42); // extra, comment, disk, attributes: 0
    locals.push(local, nameBuf, body);
    centrals.push(central, nameBuf);
    offset += local.length + nameBuf.length + body.length;
  }
  const cd = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, cd, end]);
}

/** ZIP Buffer → Map(name → Buffer); checks CRCs, rejects absolute / ".." names and duplicates. */
function zipRead(buf) {
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  if (eocd < 0) fail('Not a ZIP file (no end of central directory)');
  const count = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  const out = new Map();
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) fail('Broken ZIP central directory');
    const method = buf.readUInt16LE(p + 10), crc = buf.readUInt32LE(p + 16);
    const csize = buf.readUInt32LE(p + 20), usize = buf.readUInt32LE(p + 24);
    const nlen = buf.readUInt16LE(p + 28), xlen = buf.readUInt16LE(p + 30), clen = buf.readUInt16LE(p + 32);
    const local = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nlen);
    p += 46 + nlen + xlen + clen;
    if (name.startsWith('/') || name.includes('\\') || /^[A-Za-z]:/.test(name) || name.split('/').includes('..'))
      fail(`Unsafe path in package: ${name}`);
    if (out.has(name)) fail(`Duplicate entry in package: ${name}`);
    if (name.endsWith('/')) continue; // directory entry
    if (buf.readUInt32LE(local) !== 0x04034b50) fail(`Broken ZIP entry: ${name}`);
    const start = local + 30 + buf.readUInt16LE(local + 26) + buf.readUInt16LE(local + 28);
    const raw = buf.subarray(start, start + csize);
    const data = method === 0 ? Buffer.from(raw) : method === 8 ? zlib.inflateRawSync(raw) : fail(`Unsupported compression in ${name}`);
    if (data.length !== usize || crc32(data) !== crc) fail(`Corrupt entry (CRC): ${name}`);
    out.set(name, data);
  }
  return out;
}

// ------------------------------------------------------------------ keys

async function askPassword(prompt) {
  if (process.env.AGAPP_PASSWORD) return process.env.AGAPP_PASSWORD;
  if (!process.stdin.isTTY) fail('No terminal to ask for the password: set AGAPP_PASSWORD');
  return new Promise((resolve) => {
    process.stdout.write(prompt);
    const stdin = process.stdin;
    let pw = '';
    const onData = (chunk) => {
      for (const c of chunk) {
        if (c === '\n' || c === '\r' || c === '\u0004') {
          stdin.setRawMode(false); stdin.pause(); stdin.off('data', onData); process.stdout.write('\n');
          return resolve(pw);
        }
        if (c === '\u0003') { process.stdout.write('\n'); process.exit(130); }
        pw = c === '\u007f' || c === '\b' ? pw.slice(0, -1) : pw + c;
      }
    };
    stdin.setRawMode(true); stdin.setEncoding('utf8'); stdin.resume(); stdin.on('data', onData);
  });
}

function encryptKey(pkcs8, password) {
  const salt = crypto.randomBytes(16), iv = crypto.randomBytes(12);
  const key = crypto.pbkdf2Sync(password, salt, ITERATIONS, 32, 'sha256');
  const c = crypto.createCipheriv('aes-256-gcm', key, iv);
  const data = Buffer.concat([c.update(pkcs8), c.final(), c.getAuthTag()]);
  return { kdf: 'PBKDF2-SHA256', iterations: ITERATIONS, salt: b64(salt), cipher: 'AES-256-GCM', iv: b64(iv), data: b64(data) };
}

function decryptKey(k, password) {
  if (k.kdf !== 'PBKDF2-SHA256' || k.cipher !== 'AES-256-GCM') fail('Unsupported key file');
  const key = crypto.pbkdf2Sync(password, unb64(k.salt), k.iterations, 32, 'sha256');
  const data = unb64(k.data);
  const d = crypto.createDecipheriv('aes-256-gcm', key, unb64(k.iv));
  d.setAuthTag(data.subarray(data.length - 16));
  try { return Buffer.concat([d.update(data.subarray(0, data.length - 16)), d.final()]); }
  catch { fail('Wrong password (or a damaged key file)'); }
}

function readKeyFile(file) {
  let k;
  try { k = JSON.parse(fs.readFileSync(file, 'utf8')); } catch (e) { fail(`Can't read key file ${file}: ${e.message}`); }
  if (k.format !== 1 || !k.publicKey || !k.data) fail(`${file} is not an .agkey file`);
  return k;
}

// ------------------------------------------------------------------ app folder

/** Every app file (relative "/" paths, sorted), skipping dot files/folders, node_modules, META-INF and symlinks. */
function collectFiles(root) {
  const out = [];
  const walk = (dir, rel) => {
    for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
      const r = rel ? `${rel}/${ent.name}` : ent.name;
      if (ent.name.startsWith('.') || ent.name === 'node_modules' || (!rel && ent.name === 'META-INF')) continue;
      if (ent.isSymbolicLink()) { console.warn(`  skipped symlink: ${r}`); continue; }
      if (ent.isDirectory()) walk(path.join(dir, ent.name), r);
      else if (ent.isFile()) out.push(r);
    }
  };
  walk(root, '');
  return out.sort();
}

function readManifest(buf, where) {
  let m;
  try { m = JSON.parse(buf.toString('utf8')); } catch (e) { fail(`${where}: manifest.json is not valid JSON (${e.message})`); }
  if (!ID_RE.test(String(m.id || ''))) fail(`${where}: manifest "id" must match ${ID_RE} (got "${m.id ?? ''}")`);
  return m;
}

// ------------------------------------------------------------------ commands

async function keygen(args) {
  const developer = (args.developer || '').trim() || fail('--developer "Name" is required');
  const out = args.out || fail('--out file.agkey is required');
  if (fs.existsSync(out) && !args.force) fail(`${out} exists (use --force to overwrite)`);
  const password = await askPassword('Password for the new key: ');
  if (password.length < 8) fail('Use a password of at least 8 characters');
  if (!process.env.AGAPP_PASSWORD && (await askPassword('Again: ')) !== password) fail('The passwords differ');
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const spki = publicKey.export({ type: 'spki', format: 'der' });
  const pkcs8 = privateKey.export({ type: 'pkcs8', format: 'der' });
  const file = { format: 1, developer, publicKey: b64(spki), ...encryptKey(pkcs8, password) };
  fs.writeFileSync(out, JSON.stringify(file, null, 2) + '\n', { mode: 0o600 });
  console.log(`Key for ${developer} written to ${out}\nFingerprint ${shortFingerprint(spki)}\nKeep it safe: updates of your apps must be signed with this key.`);
}

/** The key file's private key, checked against its public key. */
async function loadKey(file) {
  const k = readKeyFile(file || fail('--key dev.agkey is required'));
  const privateKey = crypto.createPrivateKey({ key: decryptKey(k, await askPassword(`Password for ${file}: `)), format: 'der', type: 'pkcs8' });
  const derived = crypto.createPublicKey(privateKey).export({ type: 'spki', format: 'der' });
  if (!derived.equals(unb64(k.publicKey))) fail('The key file is inconsistent (public key does not match the private key)');
  return { k, privateKey };
}

function readAppFolder(folder) {
  if (!fs.statSync(folder, { throwIfNoEntry: false })?.isDirectory()) fail(`${folder} is not a folder`);
  const mf = path.join(folder, 'manifest.json');
  if (!fs.existsSync(mf)) fail(`${folder} has no manifest.json`);
  return readManifest(fs.readFileSync(mf), folder);
}

/** What signing [folder] now would cover: {path: sha256}. */
function hashFiles(folder) {
  const files = {};
  for (const rel of collectFiles(folder)) files[rel] = sha256(fs.readFileSync(path.join(folder, ...rel.split('/'))));
  return files;
}

function signFolder(folder, m, k, privateKey, out) {
  const version = String(m.version || '0.0.0');
  const entries = [], files = {};
  for (const rel of collectFiles(folder)) {
    const data = fs.readFileSync(path.join(folder, ...rel.split('/')));
    files[rel] = sha256(data);
    entries.push({ name: rel, data });
  }
  const signed = Buffer.from(JSON.stringify({ format: 1, id: m.id, version, versionCode: versionCode(m), files, signedAt: Date.now() }, null, 2), 'utf8');
  const signature = crypto.sign('sha256', signed, privateKey);
  const cert = Buffer.from(JSON.stringify({ format: 1, algorithm: 'ECDSA-P256-SHA256', publicKey: k.publicKey, developer: k.developer }, null, 2), 'utf8');
  entries.push({ name: SIGNED, data: signed }, { name: CERT, data: cert }, { name: SIG, data: Buffer.from(b64(signature)) });
  fs.writeFileSync(out, zipWrite(entries));
  return files;
}

async function sign(args) {
  const folder = args._[0] || fail('Usage: sign <app-folder> --key dev.agkey [--out file.agapp]');
  const m = readAppFolder(folder);
  const version = String(m.version || '0.0.0');
  const { k, privateKey } = await loadKey(args.key);
  const out = args.out || `${m.id}-${version}.agapp`;
  const files = signFolder(folder, m, k, privateKey, out);
  console.log(`Signed ${m.id} ${version} (code ${versionCode(m)}, ${Object.keys(files).length} files) as ${k.developer} ${shortFingerprint(unb64(k.publicKey))}\n→ ${out}`);
}

/** id, files and public key of a package already in dist/ (no signature check: publish wrote it). */
function packageInfo(file) {
  const zip = zipRead(fs.readFileSync(file));
  const info = JSON.parse((zip.get(SIGNED) || fail(`${file} has no ${SIGNED}`)).toString('utf8'));
  const cert = JSON.parse((zip.get(CERT) || fail(`${file} has no ${CERT}`)).toString('utf8'));
  return { id: info.id, version: info.version, files: info.files || {}, publicKey: cert.publicKey };
}

const sameFiles = (a, b) => { const ka = Object.keys(a).sort(), kb = Object.keys(b).sort(); return ka.length === kb.length && ka.every((k, i) => k === kb[i] && a[k] === b[k]); };
const posix = (p) => p.split(path.sep).join('/');

async function publish(args) {
  const repo = path.resolve(args.repo || '.');
  const appsDir = path.resolve(repo, args.apps || 'apps');
  const dist = path.join(repo, 'dist');
  const indexFile = path.join(repo, INDEX);
  if (!fs.statSync(appsDir, { throwIfNoEntry: false })?.isDirectory()) fail(`${appsDir} is not a folder (the app folders go there)`);
  const old = fs.existsSync(indexFile) ? JSON.parse(fs.readFileSync(indexFile, 'utf8')) : { apps: [] };
  const { k, privateKey } = await loadKey(args.key);
  const fingerprint = sha256(unb64(k.publicKey));
  fs.mkdirSync(dist, { recursive: true });

  const folders = fs.readdirSync(appsDir, { withFileTypes: true })
    .filter((d) => d.isDirectory() && !d.name.startsWith('.') && fs.existsSync(path.join(appsDir, d.name, 'manifest.json')))
    .map((d) => d.name).sort();
  const apps = [], keep = new Set(), ids = new Set();
  for (const name of folders) {
    const folder = path.join(appsDir, name);
    const m = readAppFolder(folder);
    if (ids.has(m.id)) fail(`Two app folders use the id ${m.id}`);
    ids.add(m.id);
    const version = String(m.version || '0.0.0');
    const code = versionCode(m);
    const prev = (old.apps || []).find((a) => a.id === m.id);
    if (prev && prev.fingerprint && prev.fingerprint !== fingerprint && !args.force)
      fail(`${m.id} was published with another key (${prev.developer}): phones that installed it can't update to a package signed with this one`);
    if (prev && prev.version_code > code) fail(`${name}: version ${version} (code ${code}) is lower than the published ${prev.version} (code ${prev.version_code})`);
    const pkgName = `${m.id}-${version}.agapp`;
    const pkgPath = path.join(dist, pkgName);
    let status = 'signed';
    if (fs.existsSync(pkgPath)) {
      const p = packageInfo(pkgPath);
      if (p.publicKey === k.publicKey && sameFiles(p.files, hashFiles(folder))) status = 'unchanged';
      else if (!args.force) fail(`${name} changed but its version is still ${version}: raise "version" in ${posix(path.relative(repo, folder))}/manifest.json (phones only update to a higher version)`);
    }
    if (status === 'signed') signFolder(folder, m, k, privateKey, pkgPath);
    keep.add(pkgName);
    const pkg = fs.readFileSync(pkgPath);
    const icon = typeof m.icon === 'string' && m.icon.trim() && fs.existsSync(path.join(folder, m.icon.trim().replace(/^\/+/, '')))
      ? posix(path.relative(repo, path.join(folder, m.icon.trim().replace(/^\/+/, '')))) : undefined;
    apps.push({
      path: posix(path.relative(repo, folder)), id: m.id, name: m.name || name, version, version_code: code,
      description: m.description || '', icon, package: `dist/${pkgName}`, sha256: sha256(pkg), size: pkg.length,
      developer: k.developer, fingerprint, permissions: m.permissions ?? {}, ...(m.shared_id ? { shared_id: m.shared_id } : {}),
      updated: status === 'signed' || !prev?.updated ? new Date().toISOString() : prev.updated,
    });
    console.log(`${status === 'signed' ? 'signed   ' : 'unchanged'}  ${m.id} ${version}`);
  }
  // older versions and apps that are gone
  for (const f of fs.readdirSync(dist)) {
    if (f.endsWith('.agapp') && !keep.has(f)) { fs.rmSync(path.join(dist, f)); console.log(`removed    dist/${f}`); }
  }
  const index = { name: args.name || old.name || 'AntiGem mini apps', format: 1, generated: new Date().toISOString(), apps };
  fs.writeFileSync(indexFile, JSON.stringify(index, null, 2) + '\n');
  console.log(`\n${apps.length} apps → ${posix(path.relative(process.cwd(), indexFile)) || INDEX}\nSigned by ${k.developer} ${shortFingerprint(unb64(k.publicKey))}. Commit dist/ and ${INDEX}, then push.`);
}

function verify(args) {
  const file = args._[0] || fail('Usage: verify <file.agapp>');
  const zip = zipRead(fs.readFileSync(file));
  for (const name of zip.keys()) if (name.startsWith(META) && ![SIGNED, CERT, SIG].includes(name)) fail(`Unexpected file in META-INF: ${name}`);
  const signed = zip.get(SIGNED) || fail(`Not signed: ${SIGNED} is missing`);
  const certBuf = zip.get(CERT) || fail(`Not signed: ${CERT} is missing`);
  const sigBuf = zip.get(SIG) || fail(`Not signed: ${SIG} is missing`);

  let cert, info;
  try { cert = JSON.parse(certBuf.toString('utf8')); info = JSON.parse(signed.toString('utf8')); } catch (e) { fail(`Broken signature files: ${e.message}`); }
  if (cert.format !== 1 || cert.algorithm !== 'ECDSA-P256-SHA256') fail(`Unsupported signature algorithm: ${cert.algorithm}`);
  const spki = unb64(cert.publicKey);
  let publicKey;
  try { publicKey = crypto.createPublicKey({ key: spki, format: 'der', type: 'spki' }); } catch { fail('Invalid public key in the certificate'); }
  if (publicKey.asymmetricKeyDetails?.namedCurve !== 'prime256v1') fail('The certificate key is not EC P-256');
  if (!crypto.verify('sha256', signed, publicKey, unb64(sigBuf.toString('utf8').trim()))) fail('Signature does not match: the package was changed or signed with another key');

  if (info.format !== 1 || !info.files || typeof info.files !== 'object') fail('Unsupported AGAPP.json');
  for (const [rel, hash] of Object.entries(info.files)) {
    const data = zip.get(rel) || fail(`Missing file: ${rel}`);
    if (sha256(data) !== hash) fail(`File changed after signing: ${rel}`);
  }
  for (const name of zip.keys()) if (!name.startsWith(META) && !(name in info.files)) fail(`File not covered by the signature: ${name}`);
  const m = readManifest(zip.get('manifest.json') || fail('No manifest.json in the package'), file);
  if (m.id !== info.id) fail(`manifest id "${m.id}" differs from the signed id "${info.id}"`);
  if (String(m.version || '0.0.0') !== info.version) fail(`manifest version "${m.version}" differs from the signed version "${info.version}"`);
  if (versionCode(m) !== info.versionCode) fail(`versionCode ${info.versionCode} does not match the manifest (${versionCode(m)})`);

  console.log(`OK  ${info.id} ${info.version} (code ${info.versionCode})\n    ${Object.keys(info.files).length} files, signed ${new Date(info.signedAt).toISOString()}\n    developer ${cert.developer}  ${shortFingerprint(spki)}`);
}

function info(args) {
  const k = readKeyFile(args._[0] || fail('Usage: info <file.agkey>'));
  console.log(`${k.developer}\n${shortFingerprint(unb64(k.publicKey))}  (sha256 ${sha256(unb64(k.publicKey))})`);
}

function parseArgs(argv) {
  const a = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) { const k = argv[i].slice(2); a[k] = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : true; }
    else a._.push(argv[i]);
  }
  return a;
}

const [cmd, ...rest] = process.argv.slice(2);
const commands = { keygen, sign, verify, info, publish };
try {
  if (!commands[cmd]) {
    console.log('agapp: package and sign AntiGem mini apps\n  keygen --developer "Name" --out dev.agkey\n  sign <app-folder> --key dev.agkey [--out file.agapp]\n  verify <file.agapp>\n  info <file.agkey>\n  publish --key dev.agkey [--repo .] [--apps apps] [--name "Catalog"] [--force]');
    process.exit(cmd ? 1 : 0);
  }
  await commands[cmd](parseArgs(rest));
} catch (e) {
  console.error(`agapp: ${e instanceof Fail ? e.message : e.stack || e}`);
  process.exit(1);
}
