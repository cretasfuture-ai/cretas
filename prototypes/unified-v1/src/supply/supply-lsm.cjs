'use strict';
// 六膳门采购销售库存 · Cretas 本地补充与库位/平面图（规格 C-supply.md 第 31–32 节；DECISIONS「四之前」、W3、W4）。
// 只读黑湖快照 liushanmen-v1/private/inventory-current.json；只写 unified-v1/private/lsm-supply-workspace.json。
// 不回写黑湖，不写六膳门 private，不读写味美。补充值必须由人填写或导入；黑湖有值时以黑湖为准。
// 接口签名与 lib/cost-lsm.cjs 的 handle 相同，由统一入口 server.cjs 的 /__lsm/supply 路由调用（D 线接入）。
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const ROOT = path.resolve(__dirname, '..', '..');
const ACCOUNTS = path.resolve(ROOT, '..');
const LSM = path.join(ACCOUNTS, 'liushanmen-v1');
const supplyAi = require('./supply-ai.cjs');
const batch = require('./supply-batch.cjs');
const gap = require('./supply-gap.cjs');
const XLSX = require(path.join(ACCOUNTS, '..', 'node_modules', 'xlsx'));
const FACTORY = 'LIUSHANMEN';
let STATE_FILE = path.join(ROOT, 'private', 'lsm-supply-workspace.json');
// 验收会话隔离（审计退回第 6 条，与 B 的 cost-lsm.cjs 同一约定）：带 test-* 会话时写 lsm-supply-workspace.<会话>.json
function stateFile(session) {
  if (!session) return STATE_FILE;
  if (!/^test-[a-z0-9-]{8,64}$/.test(session)) throw Object.assign(Error('验收会话名称不合规（须以 test- 开头，小写）：' + session), { status: 400 });
  return path.join(path.dirname(STATE_FILE), 'lsm-supply-workspace.' + session + '.json');
}
const KINDS = { stock: '在库余额', opening: '期初库存', lineBatch: '批次号', lineCost: '单据金额' };
const REASON_MAX = 200, IMAGE_MAX = 6_000_000;
const emptyState = () => ({ factoryId: FACTORY, version: 0, records: [], layouts: {}, audit: [] });
const pending = new Map();

function readInventory() {
  const inv = JSON.parse(fs.readFileSync(path.join(LSM, 'private', 'inventory-current.json'), 'utf8'));
  if (inv.factoryId !== FACTORY) throw Error('六膳门来源快照的工厂标识不一致');
  return inv;
}
// 第三批：黑湖新接口导出（liushanmen-v1/private/extras-current.json，只读）。只投影 C 线页面需要的资源，不写 liushanmen private。
const EXTRA_KINDS = ['sale_order', 'purchase_order', 'purchase_requirement', 'customer', 'vendor', 'customer_price', 'vendor_price', 'receivable', 'receipt', 'customer_ledger', 'vendor_ledger', 'invoicing', 'outsource_task', 'outsource_deliver_return', 'outsource_receive_return'];
let extrasCache = null;
function readExtras() {
  const f = path.join(LSM, 'private', 'extras-current.json');
  const st = fs.statSync(f);
  if (extrasCache && extrasCache.mtime === st.mtimeMs) return extrasCache.data;
  const d = JSON.parse(fs.readFileSync(f, 'utf8'));
  if (d.factoryId !== FACTORY) throw Error('黑湖新接口导出的工厂标识不一致');
  const resources = {}; for (const k of EXTRA_KINDS) resources[k] = Array.isArray(d.resources && d.resources[k]) ? d.resources[k] : null;
  const data = { factoryId: d.factoryId, sourceSystem: d.sourceSystem, revision: d.revision, checkedAt: d.checkedAt, at: d.extraState && d.extraState.at || {}, counts: Object.fromEntries(EXTRA_KINDS.map(k => [k, resources[k] ? resources[k].length : null])), resources };
  extrasCache = { mtime: st.mtimeMs, data }; return data;
}
function readState(session) { const f = stateFile(session); return fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, 'utf8')) : emptyState(); }
function writeState(state, session) {
  const f = stateFile(session);
  fs.mkdirSync(path.dirname(f), { recursive: true });
  const tmp = f + '.' + crypto.randomUUID() + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(state), { flag: 'wx', mode: 0o600 });
  fs.renameSync(tmp, f);
}
const num = v => (typeof v === 'number' && Number.isFinite(v)) ? v : (typeof v === 'string' && /^\s*-?\d+(?:\.\d+)?\s*$/.test(v) ? Number(v) : undefined);
const str = v => v == null ? '' : String(v).trim();
const isDate = v => /^\d{4}-\d{2}-\d{2}$/.test(str(v)) && !Number.isNaN(Date.parse(str(v)));

// 索引：编码 → 来源 ID（编码不唯一时不猜）
function indexes(inv) {
  const products = new Map(), lines = new Map(), stock = new Map();
  const addProduct = (code, id, name) => { if (!code) return; const k = String(code); const e = products.get(k) || new Set(); e.add(String(id) + '\u0000' + (name || '')); products.set(k, e); };
  for (const s of inv.stock) { addProduct(s.productCode, s.productId, s.productName); stock.set(String(s.productId) + '@' + String(s.warehouseId), s); }
  for (const o of inv.orders) for (const l of o.lines) { addProduct(l.productCode, l.productId, l.productName); lines.set(String(l.id), { line: l, order: o }); }
  const warehouses = new Map(inv.warehouses.map(w => [String(w.code), w]));
  const warehouseById = new Map(inv.warehouses.map(w => [String(w.id), w]));
  return { products, lines, stock, warehouses, warehouseById };
}
function resolveProduct(ix, code) {
  const e = ix.products.get(str(code));
  if (!e) return { error: '产品编码在来源中不存在' };
  if (e.size > 1) return { error: '产品编码对应多个来源产品，不猜测' };
  const [id, name] = [...e][0].split('\u0000');
  return { id, name };
}

// 校验一条补充，返回 {record} 或 {error} 或 {superseded}
function validate(inv, ix, input) {
  const kind = str(input.kind), reason = str(input.reason), value = input.value || {};
  if (!KINDS[kind]) return { error: '补充类型无效' };
  if (!reason) return { error: '必须填写补充原因' };
  if (reason.length > REASON_MAX) return { error: '原因不超过 ' + REASON_MAX + ' 字' };
  let key, clean;
  if (kind === 'stock' || kind === 'opening') {
    const pid = str(input.productId), wid = str(input.warehouseId);
    if (!pid || !wid) return { error: '缺少产品或仓库' };
    if (!ix.warehouseById.has(wid)) return { error: '仓库不在来源仓库档案中' };
    const qty = num(value.qty);
    if (qty === undefined) return { error: '数量必须是数字' };
    if (qty < 0) return { error: '补充数量不能为负；负库存只能来自黑湖' };
    const unit = str(value.unit);
    if (!unit) return { error: '必须填写单位' };
    const asOf = str(value.asOf);
    if (!isDate(asOf)) return { error: '时点必须是 YYYY-MM-DD' };
    clean = { qty, unit, asOf };
    key = kind === 'stock' ? pid + '@' + wid : pid + '@' + wid + '|' + unit + '|' + asOf;
    if (kind === 'stock' && ix.stock.has(pid + '@' + wid)) return { superseded: true, error: '黑湖已返回该组合余额，以黑湖为准，补充值不写入', key };
  } else {
    const lid = str(input.lineId), hit = ix.lines.get(lid);
    if (!hit) return { error: '单据明细行不在来源中' };
    key = lid;
    if (kind === 'lineBatch') {
      const batchNo = str(value.batchNo);
      if (!batchNo || batchNo.length > 64) return { error: '批次号必填，且不超过 64 字' };
      clean = { batchNo };
    } else {
      const amount = num(value.amount);
      if (amount === undefined || amount < 0) return { error: '金额必须是非负数字' };
      const currency = str(value.currency);
      if (!currency) return { error: '必须填写币种' };
      clean = { amount, currency };
      if (hit.line.realCostAmount !== null && hit.line.realCostAmount !== undefined && hit.line.realCostAmount !== '') return { superseded: true, error: '黑湖已有该行金额，以黑湖为准，补充值不写入', key };
    }
  }
  return { record: { kind, key, productId: str(input.productId) || undefined, warehouseId: str(input.warehouseId) || undefined, lineId: str(input.lineId) || undefined, value: clean, reason } };
}

function commit(state, records, action) {
  const at = new Date().toISOString();
  for (const r of records) {
    // 同 key 的旧有效记录被新记录替换（保留，标替换时间）
    for (const old of state.records) if (old.kind === r.kind && old.key === r.key && !old.revokedAt && !old.replacedAt) old.replacedAt = at;
    const rec = { id: crypto.randomUUID(), ...r, by: '本机用户', at };
    state.records.push(rec);
    state.audit.push({ at, action, recordId: rec.id, kind: r.kind, key: r.key, value: r.value, reason: r.reason });
  }
  state.version++;
}
function checkVersion(state, body) { if (body.expectedVersion !== undefined && body.expectedVersion !== state.version) throw Object.assign(Error('保存区已被其他操作更新，请刷新后再试'), { status: 409 }); }

function templateBook() {
  const head = ['类型（在库余额/期初库存/批次号/单据金额）', '产品编码', '仓库编码', '单位', '数量', '时点（YYYY-MM-DD）', '单据明细行ID', '批次号', '金额', '币种', '原因'];
  const example = ['在库余额', '（填黑湖产品编码）', '（填黑湖仓库编码）', 'KG', '', '2026-11-11', '', '', '', '', '现场点数'];
  const note = [['说明'], ['只补黑湖没有返回的数据；黑湖已有值的行在预检时会被忽略。'], ['在库余额 / 期初库存：填产品编码、仓库编码、单位、数量、时点。'], ['批次号 / 单据金额：填单据明细行ID（在出入库单据的明细行详情里可见）。'], ['补充值只保存在本机原型，不回写黑湖；每条留痕，可在「补充记录」中撤销。']];
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet([head, example]), '补充');
  XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet(note), '说明');
  return XLSX.write(wb, { type: 'buffer', bookType: 'xlsx' });
}
const KIND_BY_LABEL = Object.fromEntries(Object.entries(KINDS).map(([k, v]) => [v, k]));
function preflight(inv, buf) {
  const wb = XLSX.read(buf, { type: 'buffer' }), sheet = wb.Sheets['补充'] || wb.Sheets[wb.SheetNames[0]];
  const rows = XLSX.utils.sheet_to_json(sheet, { header: 1, raw: false, defval: '' }).slice(1);
  const ix = indexes(inv), out = [];
  rows.forEach((r, i) => {
    if (r.every(c => str(c) === '')) return;
    const [label, pcode, wcode, unit, qty, asOf, lineId, batchNo, amount, currency, reason] = r.map(str);
    const kind = KIND_BY_LABEL[label];
    const row = { row: i + 2, label };
    if (!kind) { out.push({ ...row, status: 'error', message: '类型无效' }); return; }
    const input = { kind, reason, value: { qty, unit, asOf, batchNo, amount, currency }, lineId };
    if (kind === 'stock' || kind === 'opening') {
      const p = resolveProduct(ix, pcode); if (p.error) { out.push({ ...row, status: 'error', message: p.error }); return; }
      const w = ix.warehouses.get(wcode); if (!w) { out.push({ ...row, status: 'error', message: '仓库编码在来源中不存在' }); return; }
      input.productId = p.id; input.warehouseId = String(w.id); row.target = p.name + ' @ ' + w.name;
    } else row.target = '明细行 ' + lineId;
    const v = validate(inv, ix, input);
    if (v.superseded) out.push({ ...row, status: 'ignored', message: v.error });
    else if (v.error) out.push({ ...row, status: 'error', message: v.error });
    else out.push({ ...row, status: 'ok', message: '可写入', record: v.record });
  });
  return out;
}

function validLayout(l) {
  if (!l || typeof l !== 'object') throw Error('布局格式无效');
  const zones = Array.isArray(l.zones) ? l.zones : [], locations = Array.isArray(l.locations) ? l.locations : [];
  if (zones.length > 200 || locations.length > 2000) throw Error('区域或库位数量超出原型上限');
  const clamp = v => { const n = num(v); return n === undefined ? 0 : Math.max(0, Math.min(100, n)); };
  const zoneIds = new Set(), codes = new Set();
  const z = zones.map(x => { const id = str(x.id) || crypto.randomUUID(), name = str(x.name); if (!name || name.length > 40) throw Error('区域名称必填，不超过 40 字'); if (zoneIds.has(id)) throw Error('区域重复'); zoneIds.add(id); return { id, name, prefix: str(x.prefix).slice(0, 8), placed: Boolean(x.placed), x: clamp(x.x), y: clamp(x.y), w: Math.max(4, clamp(x.w) || 20), h: Math.max(4, clamp(x.h) || 15) }; });
  const loc = locations.map(x => { const code = str(x.code); if (!code || code.length > 32) throw Error('库位编码必填，不超过 32 字'); if (codes.has(code)) throw Error('库位编码重复：' + code); codes.add(code); const zoneId = str(x.zoneId); if (!zoneIds.has(zoneId)) throw Error('库位 ' + code + ' 所属区域不存在'); return { id: str(x.id) || crypto.randomUUID(), zoneId, code, name: str(x.name).slice(0, 40) }; });
  let image = null;
  if (l.image && l.image.dataUrl) {
    const d = String(l.image.dataUrl);
    if (!/^data:image\/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$/.test(d)) throw Error('底图只接受 PNG、JPEG、WebP');
    if (d.length > IMAGE_MAX) throw Error('底图压缩后仍超过原型上限，请换更小的图片');
    image = { dataUrl: d, name: str(l.image.name).slice(0, 80), at: str(l.image.at) || new Date().toISOString() };
  }
  return { zones: z, locations: loc, image };
}

// 回查 A 的生产入库称重（只读，同一统一入口、同一会话）
function verifyWeighing(req, session, body) {
  const ref = body.weighingRef || {}; const qty = Number(body.qty);
  if (!ref.taskId || !ref.projectCode) return Promise.resolve({ ok: false, note: '没有称重引用（任务、工单），按手填记' });
  const host = (req && req.headers && req.headers.host) || '127.0.0.1:19063';
  const q = new URLSearchParams({ ws: 'lsm', product: str(body.productCode) }); if (session) q.set('acceptanceSession', session);
  return new Promise(resolve => {
    const r = require('http').get({ host: host.split(':')[0], port: Number(host.split(':')[1] || 80), path: '/__unified/a/api/weighins/inbound?' + q, headers: session ? { 'x-acceptance-session': session } : {}, timeout: 5000 }, res => {
      let buf = ''; res.on('data', d => { buf += d; }); res.on('end', () => {
        try { const j = JSON.parse(buf), rows = (j.data || j).rows || []; const w = rows.find(x => String(x.taskId) === String(ref.taskId) && String(x.projectCode) === String(ref.projectCode));
          if (!w) return resolve({ ok: false, note: 'A 的称重里没有这条任务（工单 ' + ref.projectCode + '），按手填记' });
          if (w.isLastProcess !== true) return resolve({ ok: false, note: '这条称重不是路线最后一道工序（' + (w.processName || '') + '），按手填记' });
          if (Math.abs(Number(w.goodNetKg) - qty) >= 0.005) return resolve({ ok: false, note: '数量 ' + qty + ' 与 A 的良品净重 ' + w.goodNetKg + ' kg 不一致，按手填记' });
          if (str(body.unit).toLowerCase() !== 'kg') return resolve({ ok: false, note: '称重是 kg，批次单位不是 kg，按手填记' });
          resolve({ ok: true, note: '已回查 A 称重：' + w.processName + ' ' + w.goodNetKg + ' kg（' + w.entries + ' 笔）' }); }
        catch { resolve({ ok: false, note: 'A 的称重读取失败，按手填记' }); } }); });
    r.on('error', () => resolve({ ok: false, note: 'A 的称重接口不可用，按手填记' })); r.on('timeout', () => { r.destroy(); resolve({ ok: false, note: 'A 的称重接口超时，按手填记' }); });
  });
}
async function handle({ req, res, u, method, send, rawBody }) {
  const p = u.pathname.replace(/^\/__lsm\/supply/, '') || '/';
  try {
    const session = String((req && req.headers && req.headers['x-acceptance-session']) || u.searchParams.get('acceptanceSession') || '');
    stateFile(session);
    const body = method === 'POST' && rawBody ? JSON.parse(rawBody) : {};
    if (method === 'GET' && p === '/state') { send(res, 200, { success: true, data: { ...readState(session), session: session || null } }); return; }
    // 第六批：Cretas 入库批次（主来源，按会话隔离写 private/c/lsm/）
    if (method === 'GET' && p === '/batches') { const st = batch.read(session), inv = readInventory(); send(res, 200, { success: true, data: { ...st, session: session || null, storageOptions: batch.STORAGE, inventoryRevision: inv.revision, inventoryAt: inv.materialAt, inventoryCheckedAt: inv.checkedAt, snapshotDay: batch.snapshotDay(inv) } }); return; }
    if (method === 'GET' && p === '/batch-reconcile') { send(res, 200, { success: true, data: batch.reconcileData(batch.read(session), readInventory()) }); return; }
    if (method === 'GET' && p === '/batch-no') { send(res, 200, { success: true, data: { batchNo: batch.suggestNo(batch.read(session), u.searchParams.get('code'), u.searchParams.get('date')) } }); return; }
    if (method === 'GET' && p === '/gap') { const st = gap.read(session); send(res, 200, { success: true, data: { ...st, session: session || null, kinds: gap.KINDS } }); return; }
    if (method === 'GET' && p === '/extras') { send(res, 200, { success: true, data: readExtras() }); return; }
    if (method === 'GET' && p === '/template') {
      const buf = templateBook();
      res.writeHead(200, { 'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'Content-Disposition': "attachment; filename*=UTF-8''" + encodeURIComponent('六膳门库存补充模板.xlsx'), 'Cache-Control': 'no-store' });
      res.end(buf); return;
    }
    if (method !== 'POST') { send(res, 404, { success: false, message: '未知接口' }); return; }
    const inv = readInventory(), state = readState(session);
    if (p === '/gap-record' || p === '/gap-revoke') {
      if (!req || req.headers['x-prototype-user-action'] !== 'local-edit') { send(res, 403, { success: false, message: '请从页面上的明确操作保存' }); return; }
      const st = gap.read(session);
      try { const r = p === '/gap-record' ? gap.create(st, body, str(body.by)) : gap.revoke(st, body, str(body.by)); gap.write(st, session); send(res, 200, { success: true, data: { record: r, version: st.version } }); }
      catch (e) { send(res, e.status || 400, { success: false, message: e.message, field: e.field || null }); }
      return;
    }
    if (p === '/batch' || p === '/batch-revoke') {
      if (!req || req.headers['x-prototype-user-action'] !== 'local-edit') { send(res, 403, { success: false, message: '请从页面上的明确操作保存' }); return; }
      const st = batch.read(session);
      // 审计退回 C-7：页面说「称重」不算数，服务端按 weighingRef 回查 A 同一会话的称重（工单、任务、最后工序、净重），对不上就记「手填」并写原因
      if (p === '/batch' && str(body.weightSource) === '称重') { const v = await verifyWeighing(req, session, body); body.weightSource = v.ok ? '称重' : '手填'; body.weighingRef = { ...(body.weighingRef || {}), verified: v.ok, verifyNote: v.note, verifiedAt: new Date().toISOString() }; }
      try { const b = p === '/batch' ? batch.create(st, inv, body, str(body.by)) : batch.revoke(st, body, str(body.by)); batch.write(st, session); send(res, 200, { success: true, data: { batch: b, version: st.version } }); }
      catch (e) { send(res, e.status || 400, { success: false, message: e.message, field: e.field || null }); }
      return;
    }
    if (p === '/explain') {
      // 第二批③：stock / document 严格模式解读；事实在服务端从黑湖只读快照计算
      const kind = str(body.kind), id = str(body.id);
      let facts = null;
      if (kind === 'stock') facts = supplyAi.stockFacts(inv, id);
      else if (kind === 'document') { let projects = []; try { projects = JSON.parse(fs.readFileSync(path.join(LSM, 'private', 'source-current.json'), 'utf8')).resources.project || []; } catch { /* 工单来源读不到时不判断关联 */ } facts = supplyAi.documentFacts(inv, id, projects); }
      else if (kind === 'extra') { const X = readExtras(); facts = supplyAi.extraFacts(X, id); if (facts) { send(res, 200, { success: true, data: { ...(await supplyAi.explain(facts, str(body.question), !!body.rulesOnly)), objectRev: X.revision } }); return; } }
      if (!facts) { send(res, 404, { success: false, message: '对象不存在或类型无效' }); return; }
      send(res, 200, { success: true, data: { ...(await supplyAi.explain(facts, str(body.question), !!body.rulesOnly)), objectRev: inv.revision } }); return;
    }
    if (p === '/supplement') {
      checkVersion(state, body);
      const v = validate(inv, indexes(inv), body);
      if (v.error) { send(res, 400, { success: false, message: v.error }); return; }
      commit(state, [v.record], '补充'); writeState(state, session); send(res, 200, { success: true, data: state }); return;
    }
    if (p === '/revoke') {
      checkVersion(state, body);
      const r = state.records.find(x => x.id === str(body.id));
      if (!r) { send(res, 404, { success: false, message: '补充记录不存在' }); return; }
      if (r.revokedAt) { send(res, 400, { success: false, message: '该补充已撤销' }); return; }
      const reason = str(body.reason); if (!reason) { send(res, 400, { success: false, message: '撤销必须填写原因' }); return; }
      const at = new Date().toISOString(); r.revokedAt = at; r.revokeReason = reason;
      state.audit.push({ at, action: '撤销', recordId: r.id, kind: r.kind, key: r.key, reason }); state.version++;
      writeState(state, session); send(res, 200, { success: true, data: state }); return;
    }
    if (p === '/preflight') {
      const buf = Buffer.from(str(body.fileBase64), 'base64');
      if (!buf.length || buf.length > 5_000_000) { send(res, 400, { success: false, message: '文件为空或超过 5MB' }); return; }
      const rows = preflight(inv, buf), token = crypto.randomUUID();
      pending.set(token, { rows, at: Date.now(), version: state.version, session });
      for (const [k, v] of pending) if (Date.now() - v.at > 30 * 60e3) pending.delete(k);
      send(res, 200, { success: true, data: { token, rows: rows.map(({ record, ...r }) => r) } }); return;
    }
    if (p === '/confirm') {
      const job = pending.get(str(body.token));
      if (!job || job.session !== session) { send(res, 400, { success: false, message: '预检已过期或不属于当前会话，请重新上传' }); return; }
      if (job.version !== state.version) { pending.delete(str(body.token)); send(res, 409, { success: false, message: '预检后保存区已变化，请重新预检' }); return; }
      const recs = job.rows.filter(r => r.status === 'ok').map(r => r.record);
      if (!recs.length) { send(res, 400, { success: false, message: '没有可写入的行' }); return; }
      commit(state, recs, '批量导入'); pending.delete(str(body.token)); writeState(state, session);
      send(res, 200, { success: true, data: { state, written: recs.length } }); return;
    }
    if (p === '/layout') {
      checkVersion(state, body);
      const wid = str(body.warehouseId);
      if (!inv.warehouses.some(w => String(w.id) === wid)) { send(res, 400, { success: false, message: '仓库不在来源仓库档案中' }); return; }
      const layout = validLayout(body.layout);
      const at = new Date().toISOString();
      state.layouts[wid] = { ...layout, savedAt: at };
      state.audit.push({ at, action: '保存布局', kind: 'layout', key: wid, value: { zones: layout.zones.length, locations: layout.locations.length, image: Boolean(layout.image) }, reason: str(body.reason) || '布局调整' });
      state.version++; writeState(state, session); send(res, 200, { success: true, data: state }); return;
    }
    send(res, 404, { success: false, message: '未知接口' });
  } catch (e) { send(res, e.status || 400, { success: false, message: String(e.message || e) }); }
}
module.exports = { handle, _test: { stateFile, setStateFile: f => { STATE_FILE = f; }, validate, indexes, preflight, validLayout, templateBook, readInventory } };
