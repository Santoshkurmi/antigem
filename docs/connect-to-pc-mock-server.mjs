// TEST-ONLY mock of the PC side of "Connect to PC" pairing (docs/connect-to-pc-protocol.md), to test the phone app
// before the VS Code extension has it. Uses a throwaway sshd (port 2222, its own authorized_keys) and the TEST
// window's bridge (1294/1295). Usage: node server.mjs <sshtest dir>
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';

const SSHDIR = process.argv[2];
const PAIR_PORT = Number(process.env.PAIR_PORT || 1250);
const LABEL = 'antigem-pair-v1';

const P = BigInt('0x' +
  'FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD' +
  'EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED' +
  'EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F' +
  '83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B' +
  'E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510' +
  '15728E5A8AACAA68FFFFFFFFFFFFFFFF');
const Q = (P - 1n) / 2n;
const G = 2n;

const modPow = (b, e, m) => { let r = 1n; b %= m; while (e > 0n) { if (e & 1n) r = (r * b) % m; e >>= 1n; b = (b * b) % m; } return r; };
const toBig = (buf) => BigInt('0x' + (buf.toString('hex') || '0'));
const toBytes = (v) => Buffer.from(v.toString(16).padStart(512, '0'), 'hex');
const sha = (...parts) => { const h = crypto.createHash('sha256'); parts.forEach((p) => h.update(p)); return h.digest(); };
const hmac = (k, d) => crypto.createHmac('sha256', k).update(d).digest();
const hashToGroup = (seed) => {
  const bufs = []; for (let i = 0; i <= 8; i++) bufs.push(sha(Buffer.from(seed, 'utf8'), Buffer.from([i])));
  return modPow(toBig(Buffer.concat(bufs)) % P, 2n, P);
};
const M = hashToGroup(`${LABEL} M`);
const N = hashToGroup(`${LABEL} N`);
const passwordScalar = (code) => { const w = toBig(sha(Buffer.from(`${LABEL} w`, 'utf8'), Buffer.from(code.trim(), 'utf8'))) % Q; return w === 0n ? 1n : w; };
const modInv = (a, m) => modPow(a, m - 2n, m); // p prime
const transcript = (phoneId, x, y, z, w) => Buffer.concat([Buffer.from(LABEL), Buffer.from(phoneId, 'utf8'), x, y, z, w].flatMap((b) => { const l = Buffer.alloc(4); l.writeUInt32BE(b.length); return [l, b]; }));
const seal = (key, plain) => { const n = crypto.randomBytes(12); const c = crypto.createCipheriv('aes-256-gcm', key, n); c.setAAD(Buffer.from(LABEL)); const ct = Buffer.concat([c.update(plain), c.final()]); return Buffer.concat([n, ct, c.getAuthTag()]).toString('base64'); };
const open = (key, box) => { const raw = Buffer.from(box, 'base64'); const d = crypto.createDecipheriv('aes-256-gcm', key, raw.subarray(0, 12)); d.setAAD(Buffer.from(LABEL)); d.setAuthTag(raw.subarray(raw.length - 16)); return Buffer.concat([d.update(raw.subarray(12, raw.length - 16)), d.final()]); };

let code = process.env.CODE || String(crypto.randomInt(0, 1e6)).padStart(6, '0');
let tries = 0;
const sessions = new Map();
const newCode = () => { code = String(crypto.randomInt(0, 1e6)).padStart(6, '0'); tries = 0; console.log(`NEW CODE ${code}`); };
console.log(`CODE ${code}`);

const lanAddresses = () => Object.values(os.networkInterfaces()).flat().filter((a) => a && a.family === 'IPv4' && !a.internal).map((a) => a.address);
const hostKeys = () => fs.readdirSync(SSHDIR).filter((f) => /^hostkey.*\.pub$/.test(f)).map((f) => { const [type, key] = fs.readFileSync(path.join(SSHDIR, f), 'utf8').trim().split(/\s+/); return { type, key }; });

const send = (res, status, obj) => { res.writeHead(status, { 'content-type': 'application/json' }); res.end(JSON.stringify(obj)); };

http.createServer((req, res) => {
  let body = '';
  req.on('data', (d) => { body += d; if (body.length > 64_000) req.destroy(); });
  req.on('end', () => {
    let j; try { j = JSON.parse(body || '{}'); } catch { return send(res, 400, { error: 'bad json' }); }
    try {
      if (req.method === 'POST' && req.url === '/antigem/pair/start') {
        if (tries >= 3) { newCode(); return send(res, 423, { error: 'Too many wrong codes. Use the new code shown on the PC.' }); }
        tries++;
        const X = toBig(Buffer.from(j.x, 'hex'));
        if (!(X > 1n && X < P - 1n) || modPow(X, Q, P) !== 1n) return send(res, 400, { error: 'bad value' });
        const w = passwordScalar(code);
        let y; do { y = toBig(crypto.randomBytes(256)) % Q; } while (y === 0n);
        const Y = (modPow(G, y, P) * modPow(N, w, P)) % P;
        const Z = modPow((X * modInv(modPow(M, w, P), P)) % P, y, P);
        const tt = transcript(String(j.phoneId), toBytes(X), toBytes(Y), toBytes(Z), toBytes(w));
        const K = sha(tt);
        const der = (l) => hmac(K, Buffer.from(l));
        const id = crypto.randomUUID();
        sessions.set(id, { tt, der, phoneId: j.phoneId, at: Date.now() });
        console.log(`start from ${j.phoneName} (${req.socket.remoteAddress}) try ${tries}`);
        return send(res, 200, { v: 1, session: id, y: toBytes(Y).toString('hex'), confirm: hmac(der('confirm-pc'), tt).toString('hex') });
      }
      if (req.method === 'POST' && req.url === '/antigem/pair/finish') {
        const s = sessions.get(j.session);
        if (!s) return send(res, 404, { error: 'No such pairing. Start again.' });
        sessions.delete(j.session);
        const want = hmac(s.der('confirm-phone'), s.tt);
        const got = Buffer.from(String(j.confirm), 'hex');
        if (got.length !== want.length || !crypto.timingSafeEqual(got, want)) return send(res, 403, { error: 'Wrong code.' });
        const phone = JSON.parse(open(s.der('enc-phone-to-pc'), j.box).toString('utf8'));
        if (phone.phoneId !== s.phoneId || !/^ecdsa-sha2-nistp256 [A-Za-z0-9+/=]+ /.test(phone.publicKey)) return send(res, 400, { error: 'bad key' });
        // test: only the tunnel to the test bridge; one line per phone (replaces an old one)
        const ak = path.join(SSHDIR, 'authorized_keys');
        const lines = fs.readFileSync(ak, 'utf8').split('\n').filter((l) => l && !l.includes(`antigem-phone:${phone.phoneId}`));
        lines.push(`command="internal-sftp",restrict,port-forwarding,permitopen="127.0.0.1:1294",permitopen="127.0.0.1:1295" ${phone.publicKey}`);
        fs.writeFileSync(ak, lines.join('\n') + '\n', { mode: 0o600 });
        const token = fs.readFileSync(path.join(os.homedir(), '.antigem_bridge', 'csrf_token.txt'), 'utf8').trim();
        const info = { pcId: 'mock-' + os.hostname(), pcName: `${os.hostname()} (test)`, os: process.platform, user: os.userInfo().username, sshPort: 2222,
          addresses: lanAddresses(), hostKeys: hostKeys(), bridgePort: 1294, hubPort: 1295, token };
        console.log(`PAIRED ${phone.phoneName} ${phone.phoneId}`);
        tries = 0;
        return send(res, 200, { v: 1, box: seal(s.der('enc-pc-to-phone'), Buffer.from(JSON.stringify(info))) });
      }
      send(res, 404, { error: 'not pairing' });
    } catch (e) { console.log('ERR', e.message); send(res, 400, { error: 'pairing failed' }); }
  });
}).listen(PAIR_PORT, process.env.BIND || '127.0.0.1', () => console.log(`pairing on ${process.env.BIND || '127.0.0.1'}:${PAIR_PORT}`));

// mDNS announce (avahi) only when asked (MDNS=1): it announces on the LAN
const av = process.env.MDNS !== '1' ? { kill() {} } : spawn('avahi-publish-service', [`${os.hostname()} (test)`, '_antigem-pc._tcp', String(PAIR_PORT), 'v=1', `id=mock-${os.hostname()}`, `name=${os.hostname()} (test)`, `os=${process.platform}`], { stdio: 'ignore' });
process.on('exit', () => av.kill());
process.on('SIGTERM', () => process.exit(0));
