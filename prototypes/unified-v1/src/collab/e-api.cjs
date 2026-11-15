'use strict';
// E 线后端：质量检验（分阶段）与协同任务。挂载点 /__unified/e/api/*（由 D 线在 server.cjs 转发）。
// 只写 unified-v1/private/e-quality-collab/<ws>/<session>.json；只读六膳门黑湖快照用于对象列表与单号核实。
// 不读写味美账号工作区、六膳门 private 保存区、黑湖；不改任何业务单据。
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const ai = require('./e-ai.cjs');
const rules = require('../quality/qc-rules.cjs');
const firstCheck = require('../quality/first-check.cjs');
const lakeInsp = require('../quality/lake-inspections.cjs');
const taskTypes = require('./task-types.cjs');
const eRules = require('./e-rules.cjs');
const batches = require('../quality/batches.cjs');
const qrec = require('../quality/q-records.cjs');

const WS = ['lsm', 'ydwm'];
const STAGES = rules.STAGES;
const ROLES = ['质量经理', '品控', '生产主管', '采购', '仓库', '销售', '其他'];
const PRIORITY = ['高', '中', '低'];
const TASK_STATUS = { TODO: '未开始', DOING: '进行中', DONE: '已完成', CANCELLED: '已取消' };
const NEXT = { TODO: ['DOING', 'DONE', 'CANCELLED'], DOING: ['DONE', 'CANCELLED'], DONE: ['DOING'], CANCELLED: ['TODO'] };

let ROOT = path.resolve(__dirname, '..', '..');
const LSM_SOURCE = () => path.resolve(__dirname, '..', '..', '..', 'liushanmen-v1', 'private', 'source-current.json');

function sessionOf(u) {
  const s = u.searchParams.get('acceptanceSession');
  if (s == null || s === '') return 'default';
  if (!/^test-[a-z0-9-]{8,64}$/.test(s)) { const e = Error('会话名称必须以 test- 开头，只含小写字母、数字和连字符'); e.status = 400; throw e; }
  return s;
}
function wsOf(u, body) {
  const w = (body && body.ws) || u.searchParams.get('ws');
  if (!WS.includes(w)) { const e = Error('缺少工作区参数（lsm 或 ydwm）'); e.status = 400; throw e; }
  return w;
}
// A 线称重报工（第六批 R23，Cretas 主来源）：只读 A 的会话文件，取未撤销记录给首检对照；不写 A 的文件
function aWeighins(session) { try { const f = path.join(ROOT, 'private', 'a-production', 'lsm', session + '.json'); if (!fs.existsSync(f)) return []; return (JSON.parse(fs.readFileSync(f, 'utf8')).weighins || []).filter(w => !w.revoked); } catch { return []; } }
function file(ws, session) { return path.join(ROOT, 'private', 'e-quality-collab', ws, session + '.json'); }
function empty(ws, session) { return { schema: 'e-quality-collab-v1', ws, session, version: 0, seq: {}, standards: [], inspections: [], tasks: [], defects: [], records: [], ai: [] }; }
function read(ws, session) { const f = file(ws, session); if (!fs.existsSync(f)) return empty(ws, session); const st = JSON.parse(fs.readFileSync(f, 'utf8')); st.defects = st.defects || []; st.records = st.records || []; return st; }
function write(st) {
  const f = file(st.ws, st.session); fs.mkdirSync(path.dirname(f), { recursive: true });
  const tmp = f + '.' + process.pid + '.tmp'; fs.writeFileSync(tmp, JSON.stringify(st, null, 1)); fs.renameSync(tmp, f);
}
const now = () => new Date().toISOString();
const day = () => new Date(Date.now() + 8 * 3600e3).toISOString().slice(0, 10).replace(/-/g, '');
function nextNo(st, prefix) { const k = prefix + day(); st.seq[k] = (st.seq[k] || 0) + 1; return prefix + '-' + day() + '-' + String(st.seq[k]).padStart(3, '0'); }
function bad(msg, status = 400) { const e = Error(msg); e.status = status; return e; }
const str = (v, max = 400) => typeof v === 'string' ? v.trim().slice(0, max) : '';
const actorOf = b => str(b.actor, 40) || '未署名';

// ---- 对象：六膳门读黑湖快照；味美由浏览器读味美接口后作为候选传入（服务端无法核实，标未核实） ----
let lsmCache = { mtime: 0, objects: null, revision: '' };
function lsmObjects() {
  const f = LSM_SOURCE(); if (!fs.existsSync(f)) return { revision: '', objects: { material: [], product: [], project: [] } };
  const m = fs.statSync(f).mtimeMs; if (lsmCache.mtime === m && lsmCache.objects) return lsmCache;
  const raw = JSON.parse(fs.readFileSync(f, 'utf8')), s = raw.source || raw, r = s.resources || {};
  const t = v => v == null ? '' : String(v);
  // 原材料 = 在黑湖 BOM 投料行（materials）里作为投入出现的产品；成品 = 黑湖工单的产出产品。按来源事实划分，不按编码前缀猜。
  const inputs = new Set((r.materials || []).map(x => t(x.productCode))), outputs = new Set((r.project || []).map(x => t(x.productCode)));
  const prod = (x, type) => ({ type, id: t(x.id), code: t(x.productCode), name: t(x.productName), unit: t(x.unit) });
  const objects = {
    material: (r.product || []).filter(x => inputs.has(t(x.productCode))).map(x => prod(x, 'material')),
    product: (r.product || []).filter(x => outputs.has(t(x.productCode))).map(x => prod(x, 'product')),
    project: (r.project || []).map(x => ({ type: 'project', id: t(x.id ?? x.projectId), code: t(x.projectCode), name: t(x.productName), unit: t(x.projectUnit) }))
  };
  lsmCache = { mtime: m, objects, revision: t(s.revision) };
  return lsmCache;
}
// 关联/对象引用核实：六膳门按黑湖快照核实；味美按浏览器传入的候选核实（来源=味美接口），否则标未核实。
function verifyRef(ws, ref, candidates) {
  if (!ref || typeof ref !== 'object') return null;
  const out = { type: str(ref.type, 20) || 'other', id: str(ref.id, 80), code: str(ref.code, 80), name: str(ref.name, 120), source: 'manual', verified: false };
  if (!out.code && !out.id) return null;
  if (out.type === 'inspection' || out.type === 'task') { out.source = 'Cretas'; out.verified = true; return out; }
  if (ws === 'lsm') {
    const list = (lsmObjects().objects[out.type] || []);
    const hit = list.find(x => (out.id && x.id === out.id) || (out.code && x.code === out.code));
    if (hit) return { ...out, id: hit.id, code: hit.code, name: hit.name, source: '黑湖', verified: true };
    return out;
  }
  const c = (candidates || []).find(x => x && x.type === out.type && ((out.id && String(x.id) === out.id) || (out.code && String(x.code) === out.code)));
  if (c) return { ...out, source: '味美', verified: true };
  return out;
}

// ---- 标准 ----
function cleanItems(items) {
  if (!Array.isArray(items) || !items.length) throw bad('至少需要一个检验项');
  if (items.length > 40) throw bad('检验项过多（最多 40 项）');
  return items.map((it, i) => {
    const kind = ['numeric', 'option'].includes(it.kind) ? it.kind : 'judge';
    const o = { id: str(it.id, 40) || 'I' + (i + 1) + '-' + crypto.randomBytes(3).toString('hex'), name: str(it.name, 60), method: str(it.method, 120), tool: str(it.tool, 40), kind, required: it.required !== false };
    if (!o.name) throw bad('第 ' + (i + 1) + ' 个检验项缺少名称');
    if (kind === 'numeric') {
      const lo = it.lower === '' || it.lower == null ? null : Number(it.lower), hi = it.upper === '' || it.upper == null ? null : Number(it.upper);
      if ((lo != null && !Number.isFinite(lo)) || (hi != null && !Number.isFinite(hi))) throw bad('「' + o.name + '」上下限必须是数字');
      if (lo == null && hi == null) throw bad('「' + o.name + '」至少填写下限或上限');
      if (lo != null && hi != null && lo > hi) throw bad('「' + o.name + '」下限不能大于上限');
      o.lower = lo; o.upper = hi; o.unit = str(it.unit, 20); if (!o.unit) throw bad('「' + o.name + '」缺少单位');
      const tg = it.target === '' || it.target == null ? null : Number(it.target);
      if (tg != null && (!Number.isFinite(tg) || (lo != null && tg < lo) || (hi != null && tg > hi))) throw bad('「' + o.name + '」标准值必须是上下限之间的数字');
      o.target = tg;
    }
    if (kind === 'option') {
      const opts = Array.from(new Set((Array.isArray(it.options) ? it.options : String(it.options || '').split(/[、,，\n]/)).map(x => String(x).trim()).filter(Boolean))).slice(0, 10);
      if (opts.length < 2) throw bad('「' + o.name + '」至少要两个选项');
      const pass = (Array.isArray(it.passOptions) ? it.passOptions : []).map(String).filter(x => opts.includes(x));
      if (!pass.length) throw bad('「' + o.name + '」至少勾选一个合格选项');
      if (pass.length === opts.length) throw bad('「' + o.name + '」所有选项都合格，判定没有意义，请改为判定项或去掉一个合格选项');
      o.options = opts; o.passOptions = pass;
    }
    return o;
  });
}
function saveStandard(st, b) {
  const stage = b.stage; if (!STAGES[stage]) throw bad('阶段不正确');
  const name = str(b.name, 60); if (!name) throw bad('标准名称不能为空');
  const items = cleanItems(b.items);
  const scope = b.scope === 'all' ? 'all' : 'object';
  const objectRef = scope === 'all' ? null : verifyRef(st.ws, b.objectRef, b.candidates);
  if (scope === 'object') {
    if (!objectRef) throw bad('请选择适用对象，或选择「本阶段全部对象」');
    if (!STAGES[stage].objectTypes.includes(objectRef.type)) throw bad(STAGES[stage].label + '标准的对象应为' + STAGES[stage].objectLabel);
  }
  // 首检要求（BL-borrow 第 17 条）：只在「生产」阶段标准上；适用工序必须在黑湖工序目录里（六膳门）
  let fc = null;
  if (stage === 'production' && b.firstCheck && b.firstCheck.enabled) {
    const procs = Array.from(new Set((b.firstCheck.processes || []).map(x => str(x, 40)).filter(Boolean))).slice(0, 50);
    if (!procs.length) throw bad('用作首检时至少选一道工序');
    if (st.ws === 'lsm') { const known = new Set(lsmProcesses().map(p => p.code)); const unk = procs.filter(c => !known.has(c)); if (unk.length) throw bad('工序不在黑湖工序目录中：' + unk.join('、')); }
    fc = { processes: procs };
  }
  const at = now(), by = actorOf(b);
  if (b.id) {
    const s = st.standards.find(x => x.id === b.id); if (!s) throw bad('标准不存在', 404);
    if (s.stage !== stage) throw bad('标准阶段新建后不能修改');
    const reason = str(b.reason, 200); if (!reason) throw bad('修改已有标准必须填写修改原因');
    const cur = s.versions[s.versions.length - 1];
    const v = { v: cur.v + 1, name, scope, objectRef, items, firstCheck: fc, at, by, reason };
    s.versions.push(v); s.log.push({ at, by, action: '修改', reason, before: summarizeStd(cur), after: summarizeStd(v) });
    return s;
  }
  const s = { id: 'QS-' + crypto.randomBytes(4).toString('hex'), no: nextNo(st, 'QS'), stage, active: true, source: 'Cretas 补充', createdAt: at, createdBy: by, versions: [{ v: 1, name, scope, objectRef, items, firstCheck: fc, at, by, reason: '新建' }], log: [{ at, by, action: '新建', reason: '新建' }] };
  st.standards.push(s); return s;
}
function summarizeStd(v) { return v.name + '｜' + (v.scope === 'all' ? '本阶段全部对象' : (v.objectRef ? v.objectRef.code + ' ' + v.objectRef.name : '')) + (v.firstCheck ? '｜首检：' + v.firstCheck.processes.join('、') : '') + '｜' + v.items.map(i => i.name + '[' + rules.range(i) + ']' + (i.required ? '' : '(选检)')).join('、'); }

// ---- 检验单 ----
function saveInspection(st, b, submit) {
  const at = now(), by = actorOf(b);
  let q = b.id ? st.inspections.find(x => x.id === b.id) : null;
  if (b.id && !q) throw bad('检验单不存在', 404);
  if (q && q.status !== 'DRAFT') throw bad('已提交或已作废的检验单不能再修改');
  const stage = q ? q.stage : b.stage; if (!STAGES[stage]) throw bad('阶段不正确');
  // 挂批次的前置条件先于标准校验（第六批自查）：味美工作区、非来料 / 入库阶段直接说明不能挂批次
  if (str(b.batchNo, 64)) { if (st.ws !== 'lsm') throw bad('只有六膳门工作区的检验可以挂入库批次'); if (!batches.BATCH_STAGES.includes(stage)) throw bad('只有来料、入库检验可以挂入库批次'); }
  const std = st.standards.find(x => x.id === b.standardId);
  if (!std) throw bad('请选择检验标准（该阶段还没有标准时先去配置）');
  if (std.stage !== stage) throw bad('所选标准不属于' + STAGES[stage].label + '阶段');
  if (!std.active && !(q && q.standardId === std.id)) throw bad('该标准已停用');
  const sv = std.versions[std.versions.length - 1];
  // 第六批：六膳门来料 / 入库检验可挂 C 的入库批次；挂了批次时检验对象由批次带出（产品以批次为准，不允许另填）
  let batchRef = null;
  const batchNo = str(b.batchNo, 64);
  if (batchNo) {
    if (st.ws !== 'lsm') throw bad('只有六膳门工作区的检验可以挂入库批次');
    if (!batches.BATCH_STAGES.includes(stage)) throw bad('只有来料、入库检验可以挂入库批次');
    const { l, hit } = batches.find(ROOT, st.session, batchNo);
    if (!l.available) throw bad(l.reason);
    if (!hit) throw bad('入库批次 ' + batchNo + ' 不存在（以 C 入库批次为准）');
    if (stage === 'incoming' && hit.kind !== 'purchase') throw bad('来料检验只能挂采购入库批次；' + batchNo + ' 是生产入库批次');
    batchRef = { batchNo: hit.batchNo, kind: hit.kind, productCode: hit.productCode, productName: hit.productName, productionDate: hit.productionDate, expiryDate: hit.expiryDate, qty: hit.qty, unit: hit.unit, warehouse: hit.warehouse, lakeInboundNo: hit.lakeInboundNo, source: 'C 入库批次' };
    if (q && q.batchRef && q.batchRef.batchNo !== batchNo) throw bad('草稿已挂批次 ' + q.batchRef.batchNo + '，不能换批次；请作废后重开');
    b = { ...b, objectRef: { type: hit.productType, code: hit.productCode, name: hit.productName } };
  }
  const objectRef = verifyRef(st.ws, b.objectRef, b.candidates);
  if (!objectRef) throw bad('请选择或填写检验对象');
  if (!STAGES[stage].objectTypes.includes(objectRef.type)) throw bad(STAGES[stage].label + '检验的对象应为' + STAGES[stage].objectLabel);
  if (sv.scope === 'object' && sv.objectRef && !(sv.objectRef.code === objectRef.code || sv.objectRef.id === objectRef.id)) throw bad('所选标准只适用于 ' + sv.objectRef.code + ' ' + sv.objectRef.name);
  const related = (Array.isArray(b.related) ? b.related : []).slice(0, 10).map(r => verifyRef(st.ws, r, b.candidates)).filter(Boolean);
  // 实测值：空或只有空格按缺失（不当 0）；数值最多两位小数，多了直接拒绝，不静默取整（BATCH2-AUDIT 13）
  const num2 = (v, label) => { if (v == null) return null; const t = String(v).trim(); if (t === '') return null; if (!/^-?\d+(\.\d{1,2})?$/.test(t)) throw bad('「' + label + '」必须是数字，最多两位小数：' + t); return Number(t); };
  const results = sv.items.map(it => { const r = (b.results || []).find(x => x.itemId === it.id) || {}; return { itemId: it.id, value: it.kind === 'numeric' ? num2(r.value, it.name) : it.kind === 'option' ? ((it.options || []).includes(r.value) ? r.value : null) : (['合格', '不合格'].includes(r.value) ? r.value : null) }; });
  // 首检：标准用作首检时，生产检验必须指定工序，且工序在标准的首检工序内
  let processCode = '', kind = 'routine';
  if (stage === 'production' && sv.firstCheck) {
    processCode = str(b.processCode, 40);
    if (!processCode) throw bad('首检必须选择工序');
    if (!sv.firstCheck.processes.includes(processCode)) throw bad('工序 ' + processCode + ' 不在该标准的首检工序内');
    kind = 'first';
  }
  for (const r of results) if (typeof r.value === 'number' && !Number.isFinite(r.value)) throw bad('实测值必须是数字');
  const sampleQty = num2(b.sampleQty, '抽样数量');
  if (sampleQty != null && (!Number.isFinite(sampleQty) || sampleQty < 0)) throw bad('抽样数量必须是非负数字');
  const fields = { stage, kind, processCode, batchRef, standardId: std.id, standardVersion: sv.v, objectRef, related, results, sampleQty, sampleUnit: str(b.sampleUnit, 20), inspector: str(b.inspector, 40), inspectedAt: str(b.inspectedAt, 30), note: str(b.note, 1000) };
  const verdict = rules.judge(sv.items, results);
  let disposal = null, disposalReason = '';
  if (submit) {
    if (verdict.overall === 'INCOMPLETE') throw bad('必检项未填完：' + verdict.missing.map(m => m.name).join('、'));
    if (!fields.inspector) throw bad('请填写检验人');
    disposal = str(b.disposal, 20);
    const allowed = rules.disposals(verdict.overall);
    if (!allowed.includes(disposal)) throw bad(verdict.overall === 'FAIL' ? '不合格时处置只能是：' + allowed.join(' / ') : '请选择处置');
    disposalReason = str(b.disposalReason, 300);
    if (disposal === '让步接收' && !disposalReason) throw bad('让步接收必须填写原因');
  }
  if (!q) {
    q = { id: 'QI-' + crypto.randomBytes(4).toString('hex'), no: nextNo(st, 'QI'), source: 'Cretas 补充', createdAt: at, createdBy: by, status: 'DRAFT', rev: 0, log: [] };
    st.inspections.push(q);
  }
  const before = q.rev ? JSON.stringify({ results: q.results, objectRef: q.objectRef && q.objectRef.code, standardVersion: q.standardVersion }) : '';
  Object.assign(q, fields, { verdict: verdict.overall, failItems: verdict.fails.map(f => f.name), missingItems: verdict.missing.map(m => m.name), updatedAt: at, updatedBy: by, rev: q.rev + 1 });
  if (submit) Object.assign(q, { status: 'SUBMITTED', disposal, disposalReason, submittedAt: at, submittedBy: by });
  q.log.push({ at, by, action: submit ? '提交结果' : (q.rev === 1 ? '新建草稿' : '保存草稿'), detail: submit ? rules.VERDICT[verdict.overall] + ' · 处置 ' + disposal + (disposalReason ? '（' + disposalReason + '）' : '') : rules.VERDICT[verdict.overall], before });
  return q;
}
// 第六批：不合格批次的处置结论（放行 / 让步放行 → 解除待处置；退货 / 报废 → 不可用）。只记结论，不改 C 的批次、不回写黑湖
function closeBatch(st, b) {
  const q = st.inspections.find(x => x.id === b.id); if (!q) throw bad('检验单不存在', 404);
  if (!q.batchRef) throw bad('这张检验单没有挂入库批次');
  if (q.status !== 'SUBMITTED' || q.verdict !== 'FAIL') throw bad('只有已提交且不合格的检验单需要批次处置');
  if (q.batchClose) throw bad('批次已有处置结论：' + q.batchClose.result);
  const result = str(b.result, 10); if (!batches.CLOSE_RESULTS[result]) throw bad('处置结论只能是：' + Object.keys(batches.CLOSE_RESULTS).join(' / '));
  const reason = str(b.reason, 300); if (!reason) throw bad('请填写处置说明');
  const at = now(), by = actorOf(b);
  q.batchClose = { result, reason, at, by }; q.rev += 1; q.updatedAt = at;
  q.log.push({ at, by, action: '批次处置', detail: q.batchRef.batchNo + ' → ' + result + '（' + reason + '）', before: '待处置' });
  return q;
}
function voidInspection(st, b) {
  const q = st.inspections.find(x => x.id === b.id); if (!q) throw bad('检验单不存在', 404);
  if (q.status === 'VOID') throw bad('已作废');
  const reason = str(b.reason, 200); if (!reason) throw bad('作废必须填写原因');
  const at = now(), by = actorOf(b);
  q.log.push({ at, by, action: '作废', detail: reason, before: q.status }); q.status = 'VOID'; q.voidReason = reason; q.rev += 1; q.updatedAt = at;
  return q;
}

// ---- 任务 ----
function cleanDue(v) { const s = str(v, 10); if (!/^\d{4}-\d{2}-\d{2}$/.test(s) || isNaN(Date.parse(s))) throw bad('请填写截止日期'); return s; }
function cleanAssignee(a) {
  a = a || {};
  if (a.kind === 'agent') throw bad('「指派给 Agent」本轮只做占位，不能保存');
  const role = ROLES.includes(a.role) ? a.role : ''; if (!role) throw bad('请选择负责岗位');
  return { kind: 'role', role, name: str(a.name, 30), verified: false };
}
function taskFields(st, b) {
  const title = str(b.title, 60); if (!title) throw bad('任务标题不能为空');
  const type = taskTypes.TYPES[b.type] ? b.type : 'other';
  return { type, fields: taskTypes.cleanFields(type, b.fields, bad), title, description: str(b.description, 2000), assignee: cleanAssignee(b.assignee), due: cleanDue(b.due), priority: PRIORITY.includes(b.priority) ? b.priority : '中', related: (Array.isArray(b.related) ? b.related : []).slice(0, 10).map(r => verifyRef(st.ws, r, b.candidates)).filter(Boolean) };
}
function diffTask(a, b) {
  const show = { title: '标题', description: '说明', due: '截止', priority: '优先级' }, out = [];
  for (const k of Object.keys(show)) if ((a[k] || '') !== (b[k] || '')) out.push({ field: show[k], before: a[k] || '', after: b[k] || '' });
  const as = x => x ? x.role + (x.name ? ' · ' + x.name : '') : '';
  if (as(a.assignee) !== as(b.assignee)) out.push({ field: '负责人', before: as(a.assignee), after: as(b.assignee) });
  const rs = x => (x || []).map(r => r.code || r.id).join('、');
  if (rs(a.related) !== rs(b.related)) out.push({ field: '关联单据', before: rs(a.related), after: rs(b.related) });
  const tl = x => (taskTypes.TYPES[x] || taskTypes.TYPES.other).label;
  if ((a.type || 'other') !== b.type) out.push({ field: '类型', before: tl(a.type || 'other'), after: tl(b.type) });
  const ff = (t, f) => (taskTypes.TYPES[t] || taskTypes.TYPES.other).fields.map(d => f && f[d.key] != null && f[d.key] !== '' ? d.label + ' ' + f[d.key] : '').filter(Boolean).join('；');
  if (ff(a.type || 'other', a.fields) !== ff(b.type, b.fields)) out.push({ field: '专属字段', before: ff(a.type || 'other', a.fields), after: ff(b.type, b.fields) });
  return out;
}
function saveTask(st, b) {
  const f = taskFields(st, b), at = now(), by = actorOf(b);
  if (b.id) {
    const t = st.tasks.find(x => x.id === b.id); if (!t) throw bad('任务不存在', 404);
    const changes = diffTask(t, f); if (!changes.length) throw bad('没有修改');
    Object.assign(t, f, { updatedAt: at, rev: t.rev + 1 }); t.log.push({ at, by, action: '修改', changes });
    return t;
  }
  const origin = b.origin && ['manual', 'inspection', 'draft', 'reminder'].includes(b.origin.kind) ? { kind: b.origin.kind, ref: str(b.origin.ref, 80), draftBy: str(b.origin.draftBy, 20) } : { kind: 'manual', ref: '' };
  const t = { id: 'TK-' + crypto.randomBytes(4).toString('hex'), no: nextNo(st, 'TK'), ...f, status: 'TODO', origin, createdAt: at, createdBy: by, updatedAt: at, rev: 1, comments: [], log: [{ at, by, action: '创建', changes: [] }] };
  st.tasks.push(t); return t;
}
function taskStatus(st, b) {
  const t = st.tasks.find(x => x.id === b.id); if (!t) throw bad('任务不存在', 404);
  const to = b.to; if (!NEXT[t.status] || !NEXT[t.status].includes(to)) throw bad('不能从「' + TASK_STATUS[t.status] + '」改为「' + (TASK_STATUS[to] || to) + '」');
  const reason = str(b.reason, 200);
  if ((to === 'CANCELLED' || (t.status === 'DONE' && to === 'DOING') || (t.status === 'CANCELLED' && to === 'TODO')) && !reason) throw bad((to === 'CANCELLED' ? '取消' : '重新打开') + '必须填写原因');
  const at = now(), by = actorOf(b);
  t.log.push({ at, by, action: '状态', changes: [{ field: '状态', before: TASK_STATUS[t.status], after: TASK_STATUS[to] }], reason });
  t.status = to; t.updatedAt = at; t.rev += 1; if (to === 'DONE') t.doneAt = at; return t;
}
function taskComment(st, b) {
  const t = st.tasks.find(x => x.id === b.id); if (!t) throw bad('任务不存在', 404);
  const text = str(b.text, 1000); if (!text) throw bad('评论不能为空');
  const c = { id: crypto.randomBytes(4).toString('hex'), at: now(), by: actorOf(b), text, fromSummary: b.fromSummary === true };
  t.comments.push(c); t.updatedAt = c.at; t.rev += 1; return t;
}

// ---- 不良品项字典（Cretas 补充）与工序不良分布（B7） ----
function lsmSource() { const f = LSM_SOURCE(); if (!fs.existsSync(f)) return null; const raw = JSON.parse(fs.readFileSync(f, 'utf8')); return raw.source || raw; }
function lsmProcesses() {
  const f = LSM_SOURCE(); if (!fs.existsSync(f)) return [];
  const raw = JSON.parse(fs.readFileSync(f, 'utf8')), r = (raw.source || raw).resources || {};
  return (r.process || []).map(x => ({ id: String(x.id), code: String(x.code || ''), name: String(x.name || '') }));
}
function saveDefect(st, b) {
  const code = str(b.code, 30), name = str(b.name, 40), category = str(b.category, 20), note = str(b.note, 200);
  if (!code) throw bad('不良项编码不能为空'); if (!name) throw bad('不良项名称不能为空');
  const procs = Array.isArray(b.processes) ? b.processes.map(x => str(x, 40)).filter(Boolean).slice(0, 50) : [];
  if (st.ws === 'lsm') { const known = new Set(lsmProcesses().map(p => p.code)); const unk = procs.filter(c => !known.has(c)); if (unk.length) throw bad('工序不在黑湖工序目录中：' + unk.join('、')); }
  const at = now(), by = actorOf(b);
  if (st.defects.some(d => d.code === code && d.id !== b.id)) throw bad('不良项编码已存在：' + code);
  if (b.id) {
    const d = st.defects.find(x => x.id === b.id); if (!d) throw bad('不良项不存在', 404);
    const reason = str(b.reason, 200); if (!reason) throw bad('修改不良项必须填写原因');
    const before = d.code + ' ' + d.name + '｜' + (d.category || '—') + '｜' + (d.processes.join('、') || '全部工序');
    Object.assign(d, { code, name, category, note, processes: procs, updatedAt: at });
    d.log.push({ at, by, action: '修改', reason, before, after: code + ' ' + name + '｜' + (category || '—') + '｜' + (procs.join('、') || '全部工序') });
    return d;
  }
  const d = { id: 'DF-' + crypto.randomBytes(4).toString('hex'), code, name, category, note, processes: procs, active: true, source: 'Cretas 补充', createdAt: at, createdBy: by, updatedAt: at, log: [{ at, by, action: '新建', reason: '新建' }] };
  st.defects.push(d); return d;
}
// 工序不良分布：只用黑湖报工的良品数与不良数；不同单位分开汇总，不相加；零分母不算比率。
function defectStats(ws, from, to) {
  if (ws !== 'lsm') return { available: false, reason: '味美工作区的报工记录没有不良数量字段，未采集不良数据。', rows: [], totals: [] };
  const f = LSM_SOURCE(); if (!fs.existsSync(f)) return { available: false, reason: '黑湖快照不存在', rows: [], totals: [] };
  const raw = JSON.parse(fs.readFileSync(f, 'utf8')), s = raw.source || raw, r = s.resources || {};
  const procs = new Map((r.process || []).map(p => [String(p.id), p]));
  const num = v => v == null || v === '' ? null : Number(v);
  const groups = new Map(), defectReports = []; let skipped = 0, used = 0;
  for (const o of r.output || []) {
    const d = String(o.starttimeofReport || o.endtimeofReport || '').slice(0, 10);
    if (from && (!d || d < from)) continue; if (to && (!d || d > to)) continue;
    const g = num(o.numberofgoodProducts), bdv = num(o.numberofDefectives);
    if (g == null || !Number.isFinite(g) || bdv == null || !Number.isFinite(bdv)) { skipped++; continue; }
    used++;
    const key = String(o.processId) + '|' + String(o.stockUnit || '');
    let row = groups.get(key);
    if (!row) { const p = procs.get(String(o.processId)); row = { processId: String(o.processId), processCode: p ? String(p.code) : '', processName: p ? String(p.name) : '工序 ' + o.processId + '（目录未返回）', unit: String(o.stockUnit || ''), reports: 0, good: 0, defective: 0, withDefect: 0 }; groups.set(key, row); }
    row.reports++; row.good += g; row.defective += bdv; if (bdv > 0) { row.withDefect++; defectReports.push({ id: String(o.id), processId: String(o.processId), unit: String(o.stockUnit || ''), defective: bdv }); }
  }
  const rate = (dv, g) => g + dv > 0 ? +(dv / (g + dv) * 100).toFixed(4) : null;
  const rows = [...groups.values()].map(x => ({ ...x, good: +x.good.toFixed(6), defective: +x.defective.toFixed(6), rate: rate(x.defective, x.good) })).sort((a, b) => b.defective - a.defective || b.reports - a.reports);
  const tot = new Map(); for (const x of rows) { const t = tot.get(x.unit) || { unit: x.unit, reports: 0, good: 0, defective: 0 }; t.reports += x.reports; t.good += x.good; t.defective += x.defective; tot.set(x.unit, t); }
  const totals = [...tot.values()].map(t => ({ ...t, rate: rate(t.defective, t.good) }));
  return { available: true, source: '黑湖报工（良品数、不良数）', revision: String(s.revision || ''), from: from || '', to: to || '', used, skipped, rows, totals, defectReports, itemsCollected: false, itemsNote: '黑湖报工只返回不良数量，没有不良项明细；不良项由 A 线在报工详情「补录不良项」按本字典分项补录（Cretas 补充），合计不超过黑湖不良数。' };
}

// ---- AI ----
function inspectionFacts(st, q) {
  const std = st.standards.find(s => s.id === q.standardId), sv = std && std.versions.find(v => v.v === q.standardVersion);
  return rules.inspectionFacts(q, std, sv);
}
const local = iso => new Date(Date.parse(iso) + 8 * 3600e3).toISOString().slice(0, 16).replace('T', ' ');
function taskFacts(t) {
  const today = new Date(Date.now() + 8 * 3600e3).toISOString().slice(0, 10);
  const overdue = !['DONE', 'CANCELLED'].includes(t.status) && t.due < today ? Math.round((Date.parse(today) - Date.parse(t.due)) / 864e5) : 0;
  const last = t.comments[t.comments.length - 1];
  return { no: t.no, title: t.title, description: t.description, status: TASK_STATUS[t.status], statusCode: t.status, due: t.due, overdueDays: overdue, assignee: t.assignee.role + (t.assignee.name ? ' · ' + t.assignee.name : ''), commentCount: t.comments.length, comments: t.comments.slice(-12).map(c => ({ at: local(c.at), by: c.by, text: c.text })), related: t.related.map(r => r.type + ' ' + (r.code || r.id)), lastUpdate: local(t.updatedAt), lastComment: last ? last.by + ' ' + local(last.at) + '：' + last.text.slice(0, 60) : '' };
}

async function handle({ req, res, u, p, send, readBody, root }) {
  if (root) ROOT = root;
  const sub = p.replace(/^\/__unified\/e\/api\//, '');
  const json = (status, data) => send(res, status, data);
  try {
    const session = sessionOf(u);
    if (req.method === 'GET') {
      // 跨线只读（C 的先到期先出要跳过）：不传 ws 时按六膳门
      if (sub === 'batch-holds') { const st = read(u.searchParams.get('ws') === 'ydwm' ? 'ydwm' : 'lsm', session); return json(200, { success: true, data: { session, holds: batches.holds(st), rule: '同一批次最新一张已提交的来料 / 入库检验不合格且没有放行结论 → 待处置；结论为退货 / 报废 → 不可用。两者都不进先到期先出建议' } }); }
      const ws = wsOf(u);
      if (sub === 'batches') { const st = read(ws, session); if (ws !== 'lsm') return json(200, { success: true, data: { available: false, reason: '味美工作区不挂 C 入库批次', rows: [], holds: [] } }); const l = batches.list(ROOT, session); return json(200, { success: true, data: { ...l, holds: batches.holds(st) } }); }
      if (sub === 'state') { const st0 = read(ws, session); eRules.ensure(st0); return json(200, { success: true, data: { ...st0, timings: Object.fromEntries(st0.tasks.map(t => [t.id, taskTypes.timings(t)])), taskTypes: taskTypes.TYPES, rulesSummary: eRules.summary(st0), meta: { stages: STAGES, roles: ROLES, priority: PRIORITY, taskStatus: TASK_STATUS, next: NEXT, disposals: rules.DISPOSALS, verdict: rules.VERDICT, recordTypes: qrec.TYPES, disposalMethods: qrec.DISPOSAL_METHODS, recallLevels: qrec.RECALL_LEVELS, labelItems: qrec.LABEL_ITEMS, today: new Date(Date.now() + 8 * 3600e3).toISOString().slice(0, 10) } } }); }
      if (sub === 'objects') { if (ws !== 'lsm') return json(200, { success: true, data: { source: '味美接口由浏览器读取', objects: null } }); const o = lsmObjects(); return json(200, { success: true, data: { source: '黑湖快照', revision: o.revision, objects: o.objects } }); }
      if (sub === 'ai/status') return json(200, { success: true, data: ai.status() });
      if (sub === 'lake') return json(200, { success: true, data: ws === 'lsm' ? lakeInsp.load() : { available: false, reason: '味美工作区没有黑湖数据', rows: [], counts: {} } });
      if (sub === 'task-types') return json(200, { success: true, data: taskTypes.TYPES });
      if (sub === 'first-checks') { const st = read(ws, session); return json(200, { success: true, data: firstCheck.rows(st, ws === 'lsm' ? lsmSource() : null, ws === 'lsm' ? aWeighins(session) : null) }); }
      if (sub === 'rules') { const st = read(ws, session); eRules.ensure(st); const tm = Object.fromEntries(st.tasks.map(t => [t.id, taskTypes.timings(t)])); return json(200, { success: true, data: { rules: eRules.summary(st), reminders: st.reminders.slice().reverse(), log: st.ruleLog.slice(-200).reverse(), timings: tm } }); }
      if (sub === 'processes') return json(200, { success: true, data: ws === 'lsm' ? { source: '黑湖工序目录', list: lsmProcesses() } : { source: '味美', list: null } });
      if (sub === 'defect-stats') { const d = v => /^\d{4}-\d{2}-\d{2}$/.test(v || '') ? v : ''; return json(200, { success: true, data: defectStats(ws, d(u.searchParams.get('from')), d(u.searchParams.get('to'))) }); }
      return json(404, { success: false, message: '未找到' });
    }
    if (req.method !== 'POST') return json(405, { success: false, message: '不支持的方法' });
    if (!req.headers.origin && req.headers['x-prototype-action'] !== 'explicit') return json(403, { success: false, message: '缺少同源请求头' });
    let b; try { b = JSON.parse((await readBody(req, 400000)).toString('utf8') || '{}'); } catch { return json(400, { success: false, message: '请求格式不正确' }); }
    const ws = wsOf(u, b), st = read(ws, session);
    // 只读的 AI 调用不检查版本
    if (sub === 'ai/inspection') {
      const q = st.inspections.find(x => x.id === b.id); if (!q) throw bad('检验单不存在', 404);
      const facts = inspectionFacts(st, q), r = b.rulesOnly ? ai.inspectionRules(facts) : await ai.inspection(facts, str(b.question, 300));
      return json(200, { success: true, data: { ...r, objectKey: 'inspection:' + q.id, objectRev: q.rev, facts } });
    }
    if (sub === 'ai/task-summary') {
      const t = st.tasks.find(x => x.id === b.id); if (!t) throw bad('任务不存在', 404);
      const facts = taskFacts(t), r = b.rulesOnly ? ai.summaryRules(facts) : await ai.taskSummary(facts);
      return json(200, { success: true, data: { ...r, objectKey: 'task:' + t.id, objectRev: t.rev, facts } });
    }
    if (sub === 'tasks/draft') {
      const text = str(b.text, 2000); if (!text) throw bad('请先输入异常说明');
      let insp = null; if (b.inspectionId) { const q = st.inspections.find(x => x.id === b.inspectionId); if (q) insp = inspectionFacts(st, q); }
      // 候选关联：当前检验单 + 文本里出现、且本工作区可核实的单号
      const cands = [];
      if (insp) {
        cands.push({ type: 'inspection', id: b.inspectionId, code: insp.no, name: insp.stageLabel + '检验 · ' + insp.object });
        const q0 = st.inspections.find(x => x.id === b.inspectionId);
        for (const r of [q0.objectRef, ...(q0.related || [])]) if (r && r.verified && r.code && !cands.some(c => c.code === r.code)) cands.push({ type: r.type, id: r.id, code: r.code, name: r.name });
      }
      const unverified = [];
      const tokens = Array.from(new Set((text.match(/[A-Za-z]{1,6}[-_]?\d[\w-]{3,}/g) || []).slice(0, 12)));
      for (const tk of tokens) {
        if (cands.some(c => c.code === tk || String(c.code).includes(tk))) continue;
        let hit = null;
        if (ws === 'lsm') { const o = lsmObjects().objects; for (const k of ['project', 'material', 'product']) { const x = o[k].find(y => y.code === tk); if (x) { hit = { type: k, id: x.id, code: x.code, name: x.name }; break; } } }
        else { const c = (b.candidates || []).find(x => x && String(x.code) === tk); if (c) hit = { type: c.type, id: String(c.id), code: String(c.code), name: String(c.name || '') }; }
        const qi = st.inspections.find(x => x.no === tk); if (!hit && qi) hit = { type: 'inspection', id: qi.id, code: qi.no, name: STAGES[qi.stage].label + '检验' };
        const tki = st.tasks.find(x => x.no === tk); if (!hit && tki) hit = { type: 'task', id: tki.id, code: tki.no, name: tki.title };
        if (hit) cands.push(hit); else unverified.push(tk);
      }
      const today = new Date(Date.now() + 8 * 3600e3).toISOString().slice(0, 10);
      const input = { text, insp, candidates: cands, roles: ROLES, priority: PRIORITY, today }, r = b.rulesOnly ? ai.draftRules(input) : await ai.taskDraft(input);
      return json(200, { success: true, data: { ...r, candidates: cands, unverified } });
    }
    const expected = Number(b.expectedVersion);
    if (!Number.isInteger(expected) || expected !== st.version) return json(409, { success: false, message: '数据已被更新，请重新读取后再保存', version: st.version });
    let out;
    if (sub === 'standards/save') out = saveStandard(st, b);
    else if (sub === 'standards/toggle') { const s = st.standards.find(x => x.id === b.id); if (!s) throw bad('标准不存在', 404); const reason = str(b.reason, 200); if (!reason) throw bad((b.active ? '启用' : '停用') + '必须填写原因'); if (s.active === !!b.active) throw bad('状态未变化'); s.active = !!b.active; s.log.push({ at: now(), by: actorOf(b), action: b.active ? '启用' : '停用', reason }); out = s; }
    else if (sub === 'inspections/save') out = saveInspection(st, b, b.submit === true);
    else if (sub === 'inspections/void') out = voidInspection(st, b);
    else if (sub === 'inspections/batch-close') out = closeBatch(st, b);
    else if (sub === 'records/save') { const bl = st.ws === 'lsm' ? batches.list(ROOT, st.session) : { available: false, reason: '味美工作区不挂 C 入库批次', rows: [] }; out = qrec.save(st, b, { lsm: st.ws === 'lsm', processes: st.ws === 'lsm' ? lsmProcesses() : [], batchesAvailable: bl.available, batchesReason: bl.reason, batchFind: no => bl.rows.find(x => x.batchNo === no) || null, inspections: st.inspections }, { now, actorOf, rand: () => crypto.randomBytes(4).toString('hex'), nextNo }); }
    else if (sub === 'records/close') out = qrec.close(st, b, { now, actorOf });
    else if (sub === 'records/void') out = qrec.voidRec(st, b, { now, actorOf });
    else if (sub === 'tasks/save') out = saveTask(st, b);
    else if (sub === 'tasks/status') out = taskStatus(st, b);
    else if (sub === 'tasks/comment') out = taskComment(st, b);
    else if (sub === 'defects/save') out = saveDefect(st, b);
    else if (sub === 'rules/run') out = { id: 'run' };
    else if (sub === 'rules/toggle') { eRules.ensure(st); if (!eRules.RULES[b.id]) throw bad('规则不存在', 404); const reason = str(b.reason, 200); if (!reason) throw bad((b.enabled ? '启用' : '停用') + '规则必须填写原因'); if (st.rules[b.id].enabled === !!b.enabled) throw bad('状态未变化'); st.rules[b.id].enabled = !!b.enabled; st.ruleLog.push({ id: crypto.randomBytes(4).toString('hex'), at: now(), rule: b.id, key: '', result: b.enabled ? '规则启用' : '规则停用', detail: reason, by: actorOf(b) }); out = { id: b.id }; }
    else if (sub === 'reminders/dismiss') { eRules.ensure(st); const r = st.reminders.find(x => x.id === b.id); if (!r) throw bad('提醒不存在', 404); if (r.status !== 'open') throw bad('提醒已处理'); const reason = str(b.reason, 200); if (!reason) throw bad('忽略提醒必须填写原因'); Object.assign(r, { status: 'dismissed', closedAt: now(), closedBy: actorOf(b), reason, closedFor: new Date(Date.now() + 8 * 3600e3).toISOString().slice(0, 10) }); st.ruleLog.push({ id: crypto.randomBytes(4).toString('hex'), at: r.closedAt, rule: r.rule, key: r.key, result: '人工忽略', detail: reason, by: r.closedBy }); out = r; }
    else if (sub === 'defects/toggle') { const d = st.defects.find(x => x.id === b.id); if (!d) throw bad('不良项不存在', 404); const reason = str(b.reason, 200); if (!reason) throw bad((b.active ? '启用' : '停用') + '必须填写原因'); if (d.active === !!b.active) throw bad('状态未变化'); d.active = !!b.active; d.log.push({ at: now(), by: actorOf(b), action: b.active ? '启用' : '停用', reason }); out = d; }
    else return json(404, { success: false, message: '未找到' });
    // 每次写入后检查规则（BL-borrow 第 24 条）：只生成提醒与日志，不改业务数据
    eRules.run(st, { today: new Date(Date.now() + 8 * 3600e3).toISOString().slice(0, 10), ...(st.ws === 'lsm' ? (fc => ({ firstRows: fc.rows, firstActiveProcesses: fc.activeProcesses || [] }))(firstCheck.rows(st, lsmSource(), aWeighins(st.session))) : { firstRows: null }), by: sub === 'rules/run' ? actorOf(b) : '系统' });
    st.version += 1; st.updatedAt = now(); write(st);
    return json(200, { success: true, data: { id: out.id, version: st.version } });
  } catch (e) {
    return json(e.status || 500, { success: false, message: String(e.message || e) });
  }
}
// 同进程跨线调用（C 线可 require）：返回该会话六膳门的待处置 / 不可用批次
function batchHolds(root, session, ws = 'lsm') { if (root) ROOT = root; if (!/^test-[a-z0-9-]{8,64}$/.test(session || '') && session !== 'default') return []; return batches.holds(read(ws, session)); }
module.exports = { handle, batchHolds, _rules: rules };
