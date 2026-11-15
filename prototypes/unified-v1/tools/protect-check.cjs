'use strict';
// 只读保护校验：不写任何受保护目录；回执写到 unified-v1/evidence/unified/
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const accounts = path.resolve(__dirname, '..', '..');
const ydwm = path.join(accounts, 'ydwm-v1');
const lsm = path.join(accounts, 'liushanmen-v1');
const tag = process.argv[2] || 'run';
const hash = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const out = { tag, at: new Date().toISOString(), sets: {} };

function check(name, root, files, isProtected) {
  let same = 0, diff = [], missing = [];
  for (const f of files) {
    const p = path.join(root, f.path);
    if (!fs.existsSync(p)) { missing.push(f.path); continue; }
    if (hash(p) === f.sha256) same++; else diff.push(f.path);
  }
  out.sets[name] = { total: files.length, same, diff, missing };
}

// 1) 味美 account-flows 基线：受保护口径（与 verify-account-integrity.cjs 同判据）
const base = JSON.parse(fs.readFileSync(path.join(ydwm, 'evidence/account-flows/baseline.json'), 'utf8'));
const prot = base.files.filter(f => /^public\/native\//.test(f.path) || /^tools\/engine\//.test(f.path) || /^data\/v3\//.test(f.path) || (/^private\//.test(f.path) && f.path !== 'private/server-state.json') || f.path === 'tools/asset-compat.cjs');
check('ydwm-account-flows-protected', ydwm, prot);
// 2) 六膳门记录的 protected.json（指向味美目录，3880 个）
const lp = JSON.parse(fs.readFileSync(path.join(lsm, 'evidence/protected.json'), 'utf8'));
check('liushanmen-protected-json(ydwm files)', lp.peer, lp.files);
// 3) 六膳门自身的关键源文件（只读监控，不属于受保护清单，仅用于确认没被误改）
const own = ['private/source-current.json', 'private/inventory-current.json', 'private/workspace.json', 'private/local-workflows.json'];
out.sets['liushanmen-private-hashes'] = Object.fromEntries(own.filter(f => fs.existsSync(path.join(lsm, f))).map(f => [f, hash(path.join(lsm, f))]));
// 4) 味美 server.cjs 与 public 根文件哈希（原样快照）
const snap = {};
for (const f of ['server.cjs', 'private/fusion-state/account.json']) { const p = path.join(ydwm, f); if (fs.existsSync(p)) snap[f] = hash(p); }
for (const e of fs.readdirSync(path.join(ydwm, 'public'), { withFileTypes: true })) if (e.isFile()) snap['public/' + e.name] = hash(path.join(ydwm, 'public', e.name));
out.sets['ydwm-server-and-public-root'] = snap;
// 5) 味美 component-states-20261107 的 3896 份受保护文件清单（绝对路径 + SHA256，口径与 component-parity / component-states 交付相同）
{
  const list = JSON.parse(fs.readFileSync(path.join(ydwm, 'evidence/component-states-20261107/protected-before.json'), 'utf8'));
  let same = 0; const diff = [], missing = [];
  for (const f of list) { if (!fs.existsSync(f.path)) { missing.push(f.path); continue; } if (hash(f.path) === String(f.sha256).toLowerCase()) same++; else diff.push(path.relative(accounts, f.path)); }
  out.sets['ydwm-protected-3896'] = { total: list.length, same, diff, missing };
}
const dir = path.join(accounts, 'unified-v1', 'evidence', 'unified');
fs.mkdirSync(dir, { recursive: true });
fs.writeFileSync(path.join(dir, `protect-${tag}.json`), JSON.stringify(out, null, 2));
const s = out.sets;
console.log(JSON.stringify({
  tag,
  ydwmProtected: `${s['ydwm-account-flows-protected'].same}/${s['ydwm-account-flows-protected'].total}`, ydwmDiff: s['ydwm-account-flows-protected'].diff.slice(0, 5), ydwmMissing: s['ydwm-account-flows-protected'].missing.length,
  protected3896: `${s['ydwm-protected-3896'].same}/${s['ydwm-protected-3896'].total}`, diff3896: s['ydwm-protected-3896'].diff, missing3896: s['ydwm-protected-3896'].missing.length,
  lsmProtected: `${s['liushanmen-protected-json(ydwm files)'].same}/${s['liushanmen-protected-json(ydwm files)'].total}`, lsmDiff: s['liushanmen-protected-json(ydwm files)'].diff.slice(0, 5), lsmMissing: s['liushanmen-protected-json(ydwm files)'].missing.length
}));
