'use strict';
// 第六批（DECISIONS 第十三节 C 行、R23）：六膳门入库批次由 Cretas 作主来源。
// 只写 unified-v1/private/c/lsm/<会话>.json（无会话时 main.json）；只读黑湖出入库快照用于校验关联单号与对账。不回写黑湖。
// 批次号、生产日期、保质期 / 到期日、储存温度是黑湖没有的字段，一律 Cretas 主来源；数量 Cretas 录入为准，黑湖入库数量只作对照。
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const ROOT = path.resolve(__dirname, '..', '..');
const DIR = path.join(ROOT, 'private', 'c', 'lsm');
const KIND = { production: '生产入库', purchase: '采购入库' };
const BL_TYPE = { production: '成品入库', purchase: '采购入库' };
const STORAGE = ['冷冻（-18℃ 以下）', '冷藏（0–4℃）', '常温'];
const empty = () => ({ factoryId: 'LIUSHANMEN', version: 0, batches: [], productConfig: {}, audit: [] });
function file(session) {
  if (session && !/^test-[a-z0-9-]{8,64}$/.test(session)) throw Object.assign(Error('验收会话名称不合规（须以 test- 开头，小写）'), { status: 400 });
  return path.join(DIR, (session || 'main') + '.json');
}
function read(session) { const f = file(session); return fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, 'utf8')) : empty(); }
function write(state, session) {
  const f = file(session); fs.mkdirSync(DIR, { recursive: true });
  const tmp = f + '.' + crypto.randomUUID() + '.tmp'; fs.writeFileSync(tmp, JSON.stringify(state), { flag: 'wx', mode: 0o600 }); fs.renameSync(tmp, f);
}
const str = v => (v == null ? '' : String(v)).trim();
const isDate = s => /^\d{4}-\d{2}-\d{2}$/.test(s) && !Number.isNaN(Date.parse(s + 'T00:00:00Z'));
const addDays = (d, n) => new Date(Date.parse(d + 'T00:00:00Z') + n * 864e5).toISOString().slice(0, 10);
const fail = (field, message) => { throw Object.assign(Error(message), { status: 422, field }); };

/** 批次号默认规则：产品码（去空格）＋生产日期 YYYYMMDD＋两位流水（同产品同日已有批次时递增） */
function suggestNo(state, productCode, date) {
  const base = str(productCode).replace(/\s+/g, '') + '-' + (date || '').replace(/-/g, '');
  const used = new Set(state.batches.filter(b => !b.revokedAt).map(b => b.batchNo));
  for (let i = 1; i < 100; i++) { const n = base + '-' + String(i).padStart(2, '0'); if (!used.has(n)) return n; }
  return base + '-' + Date.now();
}

function create(state, inv, body, by) {
  const kind = str(body.kind); if (!KIND[kind]) fail('kind', '请选择入库类型（生产入库或采购入库）');
  const productCode = str(body.productCode), productName = str(body.productName), unit = str(body.unit);
  if (!productCode) fail('productCode', '请选择产品');
  if (!unit) fail('unit', '请填写单位');
  const qty = body.qty === '' || body.qty == null ? NaN : Number(body.qty); if (!(qty > 0) || !Number.isFinite(qty)) fail('qty', '数量要大于 0');
  const productionDate = str(body.productionDate); if (!isDate(productionDate)) fail('productionDate', '生产日期格式应为 年-月-日');
  const shelfDays = body.shelfDays === '' || body.shelfDays == null ? NaN : Number(body.shelfDays); if (!Number.isInteger(shelfDays) || shelfDays < 0 || shelfDays > 3650) fail('shelfDays', body.shelfDays === '' || body.shelfDays == null ? '请填写保质期天数（填一次可记为这个产品的默认值）' : '保质期天数填 0 到 3650 的整数');
  const storage = str(body.storage); if (!STORAGE.includes(storage)) fail('storage', '请选择储存温度');
  const batchNo = str(body.batchNo) || suggestNo(state, productCode, productionDate);
  if (!/^[A-Za-z0-9\-_.]{3,40}$/.test(batchNo)) fail('batchNo', '批次号只用字母、数字和 - _ .，3 到 40 位');
  if (state.batches.some(b => !b.revokedAt && b.batchNo === batchNo)) fail('batchNo', '这个批次号已经用过，换一个或用默认值');
  // 关联黑湖入库单（只读选择）：必须是同类型入库单里有这个产品的一行；关联不上就不关联，页面写「未关联黑湖单」
  let blOrderNo = str(body.blOrderNo) || null, blLineId = str(body.blLineId) || null, blQty = null, blUnit = null, projectCode = str(body.projectCode) || null;
  if (blOrderNo) {
    const o = inv.orders.find(x => String(x.orderNo) === blOrderNo && String(x.bizTypeDisplay) === BL_TYPE[kind]);
    if (!o) fail('blOrderNo', '黑湖快照里没有这张' + BL_TYPE[kind] + '单');
    const line = (o.lines || []).find(l => (blLineId ? String(l.id) === blLineId : true) && String(l.productCode) === productCode);
    if (!line) fail('blOrderNo', '这张黑湖单里没有所选产品');
    blLineId = String(line.id); blQty = line.qty ?? null; blUnit = line.unitName ?? null;
    if (kind === 'production' && line.associatedBizOrderCode && !projectCode) projectCode = String(line.associatedBizOrderCode);
  }
  // 第七批：按快照日已过期的批次允许建，但必须写原因（页面先弹确认层）；原因记进批次
  const expiredDays = Math.round((Date.parse(snapshotDay(inv) + 'T00:00:00Z') - Date.parse(addDays(productionDate, shelfDays) + 'T00:00:00Z')) / 864e5);
  const expiredReason = str(body.expiredReason);
  if (expiredDays > 0 && !expiredReason) fail('expiredReason', '该批次按快照日已过期 ' + expiredDays + ' 天，请写明仍要建的原因');
  const weightSource = str(body.weightSource) === '称重' ? '称重' : '手填';
  const at = new Date().toISOString();
  const b = { id: crypto.randomUUID(), batchNo, kind, kindLabel: KIND[kind], productCode, productName, unit, qty, productionDate, shelfDays, expiryDate: addDays(productionDate, shelfDays), storage,
    blOrderNo, blLineId, blQty, blUnit, projectCode, weightSource, weighingRef: body.weighingRef || null, warehouseId: str(body.warehouseId) || null, status: 'ok', source: 'Cretas', by: by || '仓管', at, ...(expiredDays > 0 ? { expiredDays, expiredReason } : {}) };
  state.batches.push(b);
  if (body.saveDefault) state.productConfig[productCode] = { shelfDays, storage, source: 'Cretas', at };
  state.version++; state.audit.push({ at, action: 'create', batchNo, by: b.by });
  return b;
}
function revoke(state, body, by) {
  const b = state.batches.find(x => x.id === str(body.id) && !x.revokedAt); if (!b) fail('id', '批次不存在或已撤销');
  const reason = str(body.reason); if (reason.length < 2) fail('reason', '撤销要写原因（至少 2 个字）');
  b.revokedAt = new Date().toISOString(); b.revokeReason = reason.slice(0, 200); state.version++; state.audit.push({ at: b.revokedAt, action: 'revoke', batchNo: b.batchNo, by: by || '仓管', reason: b.revokeReason });
  return b;
}
module.exports = { read, write, create, revoke, suggestNo, file, STORAGE, KIND, BL_TYPE };

// ---- 第六批审计退回（D-1 必修、R28）：入库批次对账的服务端结果，给 D 的对账汇总页和 C 自己的页面共用 ----
// 格式与 A 的 weighin-reconcile 相同：{ asOf, snapshotDay, fields:[{key,label}], rows:[{key,label,owner,cretas:{qty,unit}|null,blacklake:{qty,unit}|null}], mismatch:[…] }
// 匹配键＝「黑湖入库单号 · 第 N 行」（不露内部行 ID）；没关联黑湖单的批次键为「未关联 · 批次号」。
// 「只在黑湖」取快照日（R28：checkedAt 换算北京时间的日期）往前 30 天内还没建批次的入库单行，加上所有被批次关联的行。
// 两边单位不同的配对不进 rows（对账组件不换算单位），单列 mismatch。
const BL_INBOUND = ['成品入库', '采购入库'];
const lc = s => String(s == null ? '' : s).trim().toLowerCase();
function snapshotDay(inv) { const t = Date.parse(inv.checkedAt || inv.materialAt || ''); return Number.isFinite(t) ? new Date(t + 8 * 3600e3).toISOString().slice(0, 10) : new Date(Date.now() + 8 * 3600e3).toISOString().slice(0, 10); }
function lineKeys(inv) {
  const m = new Map();
  for (const o of inv.orders) if (BL_INBOUND.includes(String(o.bizTypeDisplay))) (o.lines || []).forEach((l, i) => m.set(String(o.orderNo) + '#' + String(l.id), { key: String(o.orderNo) + ' · 第 ' + (i + 1) + ' 行', o, l, date: String(o.bizAt || '').slice(0, 10) }));
  return m;
}
function reconcileData(state, inv) {
  const day = snapshotDay(inv), since = addDays(day, -30), lines = lineKeys(inv), active = state.batches.filter(b => !b.revokedAt);
  const rows = new Map(), mismatch = [], linked = new Set();
  for (const b of active) {
    const ref = b.blOrderNo ? b.blOrderNo + '#' + b.blLineId : null, x = ref ? lines.get(ref) : null;
    if (ref) linked.add(ref);
    const key = x ? x.key : (b.blOrderNo ? b.blOrderNo + ' · 快照里没有这行' : '未关联 · ' + b.batchNo);
    const label = (b.blOrderNo || '未关联黑湖单') + ' · ' + b.productCode + ' ' + b.productName + ' · 批次 ' + b.batchNo;
    if (x && lc(x.l.unitName) !== lc(b.unit)) { mismatch.push({ key, label, cretas: { qty: b.qty, unit: b.unit }, blacklake: { qty: x.l.qty, unit: x.l.unitName }, owner: '仓库（核对单位）' }); continue; }
    rows.set(key, { key, label, owner: b.kind === 'production' ? '车间（称重）与仓库' : '仓库与采购', cretas: { qty: b.qty, unit: b.unit, batchNo: b.batchNo }, blacklake: x ? { qty: Number(x.l.qty), unit: x.l.unitName } : null });
  }
  for (const [ref, x] of lines) {
    if (linked.has(ref) || x.date < since || x.date > day) continue;
    rows.set(x.key, { key: x.key, label: x.o.orderNo + ' · ' + x.l.productCode + ' ' + x.l.productName, owner: '仓库（待建批次）', cretas: null, blacklake: { qty: Number(x.l.qty), unit: x.l.unitName } });
  }
  return { asOf: inv.materialAt || inv.checkedAt || null, checkedAt: inv.checkedAt || null, snapshotDay: day, revision: inv.revision || null, keyLabel: '黑湖入库单 · 行', fields: [{ key: 'qty', label: '入库数量' }], rows: [...rows.values()], mismatch };
}
module.exports.reconcileData = reconcileData;
module.exports.snapshotDay = snapshotDay;
