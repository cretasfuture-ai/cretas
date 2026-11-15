'use strict';
// A 线后端（生产链）。挂载点 /__unified/a/api/*（由 D 线在 server.cjs 转发一行）。
// 只写 unified-v1/private/a-production/<ws>/<session>.json；只读六膳门黑湖快照与 E 线不良品项字典。
// 不读写味美账号工作区、六膳门 private 保存区、黑湖；不改任何业务单据。
//
// 1) 报工不良品项采集（BL-borrow B7，E 出字典与统计，A 采集）：
//    GET  defect-captures?ws=lsm|ydwm[&reportId=]            → { version, captures }
//    POST defect-captures/save   {ws,reportId,items:[{defectId,qty}],reason,expectedVersion}
//    POST defect-captures/revoke {ws,id,reason,expectedVersion}
//    规则：六膳门报工必须在黑湖快照里存在；黑湖已记录不良数时，各不良项之和不得超过它（黑湖为准）；
//    黑湖不良数为 0 或缺失时拒绝分摊（不凭空造不良）。不良项必须是 E 字典里启用的项，且适用于该工序。
//    采集值标「Cretas 补充」，留痕可撤销，不回写黑湖。
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const WS = ['lsm', 'ydwm'];
let ROOT = path.resolve(__dirname, '..', '..');
const LSM_SOURCE = () => path.resolve(ROOT, '..', 'liushanmen-v1', 'private', 'source-current.json');
const bad = (m, status = 400) => Object.assign(Error(m), { status });
const now = () => new Date().toISOString();
const str = (v, n) => typeof v === 'string' ? v.trim().slice(0, n) : '';
function sessionOf(u) { const s = u.searchParams.get('acceptanceSession'); if (s == null || s === '') return 'default'; if (!/^test-[a-z0-9-]{8,64}$/.test(s)) throw bad('会话名称必须以 test- 开头，只含小写字母、数字和连字符'); return s; }
function wsOf(u, b) { const w = (b && b.ws) || u.searchParams.get('ws'); if (!WS.includes(w)) throw bad('缺少工作区参数（lsm 或 ydwm）'); return w; }
const file = (ws, session) => path.join(ROOT, 'private', 'a-production', ws, session + '.json');
function read(ws, session) { const f = file(ws, session); if (!fs.existsSync(f)) return { schema: 'a-production-v1', ws, session, version: 0, captures: [] }; return JSON.parse(fs.readFileSync(f, 'utf8')); }
function write(st) { const f = file(st.ws, st.session); fs.mkdirSync(path.dirname(f), { recursive: true }); const tmp = f + '.' + process.pid + '.tmp'; fs.writeFileSync(tmp, JSON.stringify(st, null, 2)); fs.renameSync(tmp, f); }
function dictionary(ws, session) { const f = path.join(ROOT, 'private', 'e-quality-collab', ws, session + '.json'); if (!fs.existsSync(f)) return []; try { return JSON.parse(fs.readFileSync(f, 'utf8')).defects || []; } catch { return []; } }
let lsmCache = { mtime: 0, data: null };
function lsm() { const f = LSM_SOURCE(); if (!fs.existsSync(f)) throw bad('六膳门黑湖快照不存在', 503); const m = fs.statSync(f).mtimeMs; if (lsmCache.mtime !== m) lsmCache = { mtime: m, data: JSON.parse(fs.readFileSync(f, 'utf8')) }; return lsmCache.data; }
const num = v => v === null || v === undefined || v === '' ? null : Number.isFinite(Number(v)) ? Number(v) : NaN;

function saveCapture(st, b, session) {
  if (st.version !== b.expectedVersion) throw bad('数据已被他人更新，请刷新后重试', 409);
  const reportId = str(String(b.reportId ?? ''), 40); if (!reportId) throw bad('缺少报工ID');
  const reason = str(b.reason, 200); if (!reason) throw bad('请填写补录原因');
  const items = Array.isArray(b.items) ? b.items : []; if (!items.length) throw bad('至少选择一个不良项');
  const dict = dictionary(st.ws, session);
  let source = { reportId, defective: null, unit: null, process: null, project: null };
  if (st.ws === 'lsm') {
    const s = lsm(), r = (s.resources.output || []).find(x => String(x.id) === reportId); if (!r) throw bad('黑湖快照里没有报工 ' + reportId + '，不按相近记录猜配', 404);
    const p = (s.resources.process || []).find(x => String(x.id) === String(r.processId));
    source = { reportId, defective: num(r.numberofDefectives), unit: r.stockUnit || null, process: p ? { id: String(p.id), code: p.code, name: p.name } : null, project: String(r.projectId ?? '') };
    if (source.defective === null || Number.isNaN(source.defective)) throw bad('黑湖未返回这条报工的不良数，不能分摊不良项（不凭空造不良）');
    if (source.defective === 0) throw bad('黑湖记录这条报工不良数为 0，不能补录不良项');
  }
  const clean = items.map(x => { const d = dict.find(y => y.id === x.defectId); if (!d) throw bad('不良项不在字典里：' + x.defectId); if (d.active === false) throw bad('不良项已停用：' + d.code); if (source.process && d.processes && d.processes.length && !d.processes.includes(source.process.code)) throw bad('不良项 ' + d.code + ' 不适用于工序 ' + source.process.name); const q = num(x.qty); if (q === null || Number.isNaN(q) || q <= 0) throw bad('不良数量必须是正数：' + d.code); return { defectId: d.id, code: d.code, name: d.name, category: d.category || null, qty: Math.round(q * 1e6) / 1e6 }; });
  if (new Set(clean.map(x => x.defectId)).size !== clean.length) throw bad('同一不良项重复');
  const sum = clean.reduce((a, x) => a + x.qty, 0);
  if (source.defective !== null && sum - source.defective > 1e-9) { const un = source.unit ? ' ' + source.unit : ''; throw bad('黑湖记录不良 ' + source.defective + un + '，分项合计 ' + (Math.round(sum * 1e6) / 1e6) + un + '；若实际不良更多，请先在黑湖修正报工'); }
  const at = now(), by = str(b.actor, 40) || '当前账号';
  const prev = st.captures.find(c => c.reportId === reportId && !c.revoked);
  if (prev) { prev.revoked = { at, by, reason: '被新的补录取代：' + reason }; }
  const c = { id: 'DC-' + crypto.randomBytes(4).toString('hex'), reportId, ws: st.ws, project: source.project, process: source.process, unit: source.unit, sourceDefective: source.defective, items: clean, total: Math.round(sum * 1e6) / 1e6, unallocated: source.defective === null ? null : Math.round((source.defective - sum) * 1e6) / 1e6, source: 'Cretas 补充', reason, at, by, replaces: prev ? prev.id : null, revoked: null };
  st.captures.push(c); return c;
}
function revokeCapture(st, b) {
  if (st.version !== b.expectedVersion) throw bad('数据已被他人更新，请刷新后重试', 409);
  const c = st.captures.find(x => x.id === b.id); if (!c) throw bad('补录记录不存在', 404); if (c.revoked) throw bad('该补录已撤销');
  const reason = str(b.reason, 200); if (!reason) throw bad('撤销必须填写原因');
  c.revoked = { at: now(), by: str(b.actor, 40) || '当前账号', reason }; return c;
}

// 2) 工序标准工时（BL-borrow 15，Cretas 补充；黑湖没有标准工时字段）：
//    GET  standards?ws=lsm                                   → { version, standards }
//    POST standards/save   {ws,processCode,secondsPerUnit,unit,reason,expectedVersion}  同工序旧值被取代（留痕）
//    POST standards/revoke {ws,id,reason,expectedVersion}
function saveStandard(st, b) {
  if (st.version !== b.expectedVersion) throw bad('数据已被他人更新，请刷新后重试', 409);
  const code = str(b.processCode, 40); if (!code) throw bad('缺少工序编码');
  if (st.ws === 'lsm') { const s = lsm(); if (!(s.resources.process || []).some(p => p.code === code)) throw bad('黑湖工序目录里没有 ' + code + '，不按名称猜配', 404); }
  const sec = num(b.secondsPerUnit); if (sec === null || Number.isNaN(sec) || sec <= 0) throw bad('标准秒/单位必须是正数');
  const unit = str(b.unit, 20); if (!unit) throw bad('请填写标准对应的单位');
  const reason = str(b.reason, 200); if (!reason) throw bad('请填写补充原因');
  st.standards = st.standards || [];
  const at = now(), by = str(b.actor, 40) || '当前账号';
  const prev = st.standards.find(x => x.processCode === code && !x.revoked); if (prev) prev.revoked = { at, by, reason: '被新标准取代：' + reason };
  const s = { id: 'ST-' + crypto.randomBytes(4).toString('hex'), processCode: code, secondsPerUnit: Math.round(sec * 1e6) / 1e6, unit, source: 'Cretas 补充', reason, at, by, replaces: prev ? prev.id : null, revoked: null };
  st.standards.push(s); return s;
}
function revokeStandard(st, b) {
  if (st.version !== b.expectedVersion) throw bad('数据已被他人更新，请刷新后重试', 409);
  const s = (st.standards || []).find(x => x.id === b.id); if (!s) throw bad('标准不存在', 404); if (s.revoked) throw bad('该标准已撤销');
  const reason = str(b.reason, 200); if (!reason) throw bad('撤销必须填写原因');
  s.revoked = { at: now(), by: str(b.actor, 40) || '当前账号', reason }; return s;
}

// 3) 效率与达标率只读接口（BL-borrow 15，给 B 成本页「人工与工序」分区用）：
//    GET efficiency?ws=lsm[&project=<projectId>]  → { rule, standardsVersion, processes:[...] }
//    口径与 src/production/efficiency.ts 相同（这里是服务端同规则移植，不另造口径）：
//    标准工时=良品×标准秒/单位；效率=标准工时÷有效工时（黑湖 task.sumEffectiveTime 秒）；达标=效率≥100%；达标率=达标任务÷可计算任务。
const EFF_RULE = '标准工时=良品×标准秒/单位；实际效率=标准工时÷有效工时；达标=效率≥100%；达标率=达标任务÷可计算任务；没有标准或有效工时为 0 时不计算';
function efficiency(st, projectId) {
  const s = lsm(), r = s.resources, procById = new Map((r.process || []).map(p => [String(p.id), p]));
  const tasks = (r.task || []).filter(t => !projectId || String(t.projectId) === String(projectId));
  const unitOf = t => { const o = (r.output || []).find(x => String(x.taskId) === String(t.id)); return o && o.stockUnit ? String(o.stockUnit) : null; };
  const stds = (st.standards || []).filter(x => !x.revoked);
  const by = new Map();
  for (const t of tasks) { const p = procById.get(String(t.processId)); const code = p ? p.code : '（无工序编码）'; if (!by.has(code)) by.set(code, { code, name: p ? p.name : String(t.processName || ''), list: [] }); by.get(code).list.push(t); }
  return [...by.values()].map(g => {
    const std = stds.find(x => x.processCode === g.code) || null;
    const rows = g.list.map(t => {
      const good = num(t.approvedFineAmount), eff = num(t.sumEffectiveTime), unit = unitOf(t);
      if (!std) return { taskId: String(t.id), status: '无标准', standardSeconds: null, effectiveSeconds: eff, efficiency: null };
      if (good === null || Number.isNaN(good)) return { taskId: String(t.id), status: '未报良品', standardSeconds: null, effectiveSeconds: eff, efficiency: null };
      if (unit && std.unit && unit !== std.unit) return { taskId: String(t.id), status: '单位不一致', standardSeconds: null, effectiveSeconds: eff, efficiency: null };
      const ss = good * std.secondsPerUnit;
      if (!eff || eff <= 0) return { taskId: String(t.id), status: '无有效工时', standardSeconds: ss, effectiveSeconds: eff, efficiency: null };
      const e = ss / eff; return { taskId: String(t.id), status: e >= 1 ? '达标' : '未达标', standardSeconds: ss, effectiveSeconds: eff, efficiency: e };
    });
    const ok = rows.filter(x => x.efficiency !== null), reached = ok.filter(x => x.status === '达标').length;
    const ts = ok.length ? ok.reduce((a, x) => a + x.standardSeconds, 0) : null, te = ok.length ? ok.reduce((a, x) => a + x.effectiveSeconds, 0) : null;
    const reason = !std ? '无标准（Cretas 补充里没有这道工序的标准秒/单位）' : !ok.length ? '没有可计算的任务（无有效工时、未报良品或单位不一致）' : null;
    return { processCode: g.code, processName: g.name, standard: std ? { id: std.id, secondsPerUnit: std.secondsPerUnit, unit: std.unit, source: std.source, at: std.at } : null, tasks: rows.length, computable: ok.length, reached, rate: ok.length ? reached / ok.length : null, totalStandardSeconds: ts, totalEffectiveSeconds: te, efficiency: ts !== null && te ? ts / te : null, reason, taskRows: rows };
  });
}

// 4) 第三批：六膳门工单的委外与操作记录（黑湖新接口导出，只读 liushanmen-v1/private/extras-current.json，按 revision 缓存，不写它）：
//    GET project-extras?ws=lsm&project=<projectId>
//    → { revision, checkedAt, outsource:{tasks, delivers, receives, unlinked}, operationLog:{entries, counts, note} }
//    委外关联：outsource_task.projectId；发料/退料、收货/退货按 outsourceTaskCode 找到任务再到工单。没有工单号的整单委外放 unlinked，不按产品猜。
//    操作记录：工单本身（PROJECT、工单自定义字段）按 instanceId = 工单 id；工序任务（PROJECTPROCESS）按本工单任务 id；报工（OUTPUT）按本工单报工 id。
//    黑湖操作日志只给操作类型和字段类别，没有字段级前后值，前后值显示「黑湖未提供」。
const EXTRAS = () => path.resolve(ROOT, '..', 'liushanmen-v1', 'private', 'extras-current.json');
let extrasCache = { mtime: 0, data: null };
function extras() { const f = EXTRAS(); if (!fs.existsSync(f)) throw bad('黑湖新接口导出文件不存在（extras-current.json）', 503); const m = fs.statSync(f).mtimeMs; if (extrasCache.mtime !== m) extrasCache = { mtime: m, data: JSON.parse(fs.readFileSync(f, 'utf8')) }; return extrasCache.data; }
const pick = (o, keys) => Object.fromEntries(keys.map(k => [k, o[k] ?? null]));
function projectExtras(projectId) {
  const x = extras(), r = x.resources || {}, s = lsm().resources;
  const pid = String(projectId || ''); const project = (s.project || []).find(p => String(p.id) === pid);
  if (!project) throw bad('黑湖快照里没有工单 ' + pid, 404);
  const tasks = (r.outsource_task || []).filter(t => String(t.projectId ?? '') === pid);
  const codes = new Set(tasks.map(t => t.taskCode));
  const TASK_KEYS = ['id', 'taskCode', 'orderCode', 'processCode', 'processName', 'productCode', 'productName', 'shortName', 'outsourcePlanAmount', 'issuedAmount', 'receivedGoodAmount', 'receivedDefectiveAmount', 'pendingIssueAmount', 'pendingReceiveAmount', 'productUnit', 'pricingUnitName', 'price', 'amount', 'status', 'expectEndTime', 'taskRealEndTime', 'outsourceType'];
  const MOVE_KEYS = ['id', 'orderCode', 'outsourceTaskCode', 'postTypeName', 'amount', 'pricingUnitName', 'bizAt', 'productCode', 'productName', 'shortName', 'outsourceTypeDisplay', 'remark'];
  const delivers = (r.outsource_deliver_return || []).filter(d => codes.has(d.outsourceTaskCode)).map(d => pick(d, MOVE_KEYS));
  const receives = (r.outsource_receive_return || []).filter(d => codes.has(d.outsourceTaskCode)).map(d => pick(d, MOVE_KEYS));
  const linkedOrders = new Set((r.outsource_task || []).filter(t => t.projectId).map(t => t.orderCode));
  const unlinked = [...new Set((r.outsource_task || []).filter(t => !t.projectId).map(t => t.orderCode))].filter(c => !linkedOrders.has(c)).map(code => ({ orderCode: code, tasks: (r.outsource_task || []).filter(t => t.orderCode === code).length, products: [...new Set((r.outsource_task || []).filter(t => t.orderCode === code).map(t => t.productName || t.productCode))], reason: '黑湖整单委外，没有关联工单号' }));
  const taskIds = new Set((s.task || []).filter(t => String(t.projectId) === pid).map(t => String(t.id)));
  const outIds = new Set((s.output || []).filter(o => String(o.projectId) === pid).map(o => String(o.id)));
  // 与 D 的 /__unified/d/lsm-oplog?code= 同一口径：instanceCode = 工单号的全部日志（含已删除报工、报工/任务自定义字段）；
  // 另按 id 补上 instanceCode 缺失但 instanceId 能对上的条目。对象名取黑湖 objectName，自定义字段写「X 自定义字段」。
  const outAll = new Set((s.output || []).map(o => String(o.id)));
  const code = String(project.projectCode || '');
  const byCode = o => code && String(o.instanceCode || '') === code;
  const byId = o => ((o.objectCode === 'PROJECT' || (o.objectCode === 'CUSTOM_FIELD_VALUE' && o.objectName === '工单')) && String(o.instanceId) === pid) || (o.objectCode === 'PROJECTPROCESS' && taskIds.has(String(o.instanceId))) || (o.objectCode === 'OUTPUT' && outIds.has(String(o.instanceId)));
  const objName = o => o.objectCode === 'CUSTOM_FIELD_VALUE' ? String(o.objectName || '') + '自定义字段' : o.objectCode === 'PROJECTPROCESS' ? '工序任务' : String(o.objectName || o.objectCode || '');
  const deletedOut = o => (o.objectName === '报工' || o.objectCode === 'OUTPUT') && !outAll.has(String(o.instanceId));
  const entries = (r.operation_log || []).filter(o => byCode(o) || byId(o))
    .sort((a, b) => (b.tsMs || 0) - (a.tsMs || 0))
    .map(o => ({ at: o.operateTimeString || null, tsMs: o.tsMs || null, object: objName(o), objectCode: o.objectCode, instanceId: String(o.instanceId), instanceCode: o.instanceCode || null, action: o.operateType || null, fieldType: o.fieldType || null, before: null, after: null, source: 'blacklake', matchedBy: byCode(o) ? 'code' : 'id', deletedObject: deletedOut(o) }));
  const deletedReports = [...new Set(entries.filter(e => e.deletedObject).map(e => e.instanceId))];
  const counts = entries.reduce((a, e) => { a[e.object] = (a[e.object] || 0) + 1; return a; }, {});
  return { revision: x.revision, checkedAt: x.checkedAt, project: { id: pid, code: project.projectCode },
    outsource: { tasks: tasks.map(t => pick(t, TASK_KEYS)), delivers, receives, unlinked, note: tasks.length ? null : '黑湖没有关联到这张工单的委外任务' },
    operationLog: { entries, counts, total: (r.operation_log || []).length, note: '黑湖操作日志只给操作类型和字段类别，没有字段级前后值；人员字段已在采集时剔除', rule: '按工单号（instanceCode）取全部日志，与 D 的 lsm-oplog?code= 同口径；instanceCode 缺失时按工单、任务、报工 id 补', deletedReports } };
}

// 5) 第四批 BL 26 报工时段规则（Cretas 补充，按会话存在本文件的保存区；不做拖拽配置界面）：
//    GET  shift-rules?ws=lsm                         → { version, rule(当前生效), history }
//    POST shift-rules/save   {ws,shifts:[{name,start:'HH:MM',end:'HH:MM',breaks:[{start,end}]}],reason,expectedVersion}
//    POST shift-rules/revoke {ws,id,reason,expectedVersion}
//    GET  shift-hours?ws=lsm[&project=][&reports=id1,id2]  → 每条报工按班次规则算的总时长、休息、班外（加班）、有效工时（秒）
//    口径（北京时间）：总时长 = 报工结束 − 开始；休息 = 与班次内休息时段的重叠；班外 = 不在任何班次内的时长（记为加班）；有效工时 = 总时长 − 休息。
//    夜班跨零点：下班早于上班时按次日。报工没有起止时间写「黑湖未返回报工时段，无法计算」；起止相同写「起止时间相同，无时长」；没配置规则写「未配置班次规则」。不猜。
const HM = /^([01]\d|2[0-3]):([0-5]\d)$/;
const BJ = 8 * 3600e3, DAY = 864e5;
const hm = v => { const m = HM.exec(String(v || '')); return m ? (Number(m[1]) * 60 + Number(m[2])) * 60e3 : null; };
const bjParse = v => { if (!v) return null; const raw = String(v); const t = Date.parse(raw.replace(' ', 'T') + (/[zZ]$|[-+]\d\d:\d\d$/.test(raw) ? '' : '+08:00')); return Number.isFinite(t) ? t : null; };
function validShifts(shifts) {
  if (!Array.isArray(shifts) || !shifts.length) throw bad('至少填写一个班次');
  return shifts.slice(0, 6).map((x, i) => { const name = str(x.name, 20) || '班次' + (i + 1); const a = hm(x.start), b = hm(x.end); if (a === null || b === null) throw bad(name + '：上下班时间格式应为 HH:MM'); if (a === b) throw bad(name + '：上下班时间不能相同');
    const breaks = (Array.isArray(x.breaks) ? x.breaks : []).slice(0, 4).map(k => { const c = hm(k.start), d = hm(k.end); if (c === null || d === null || c === d) throw bad(name + '：休息时段格式应为 HH:MM-HH:MM'); return { start: k.start, end: k.end }; });
    return { name, start: x.start, end: x.end, breaks }; });
}
function saveShiftRule(st, b) {
  if (st.version !== b.expectedVersion) throw bad('数据已被他人更新，请刷新后重试', 409);
  const shifts = validShifts(b.shifts); const reason = str(b.reason, 200); if (!reason) throw bad('请填写补充原因');
  st.shiftRules = st.shiftRules || []; const at = now(), by = str(b.actor, 40) || '当前账号';
  const prev = st.shiftRules.find(x => !x.revoked); if (prev) prev.revoked = { at, by, reason: '被新规则取代：' + reason };
  const r = { id: 'SR-' + crypto.randomBytes(4).toString('hex'), shifts, source: 'Cretas 补充', reason, at, by, replaces: prev ? prev.id : null, revoked: null };
  st.shiftRules.push(r); return r;
}
function revokeShiftRule(st, b) {
  if (st.version !== b.expectedVersion) throw bad('数据已被他人更新，请刷新后重试', 409);
  const r = (st.shiftRules || []).find(x => x.id === b.id); if (!r) throw bad('班次规则不存在', 404); if (r.revoked) throw bad('该规则已撤销');
  const reason = str(b.reason, 200); if (!reason) throw bad('撤销必须填写原因'); r.revoked = { at: now(), by: str(b.actor, 40) || '当前账号', reason }; return r;
}
const overlap = (a0, a1, b0, b1) => Math.max(0, Math.min(a1, b1) - Math.max(a0, b0));
function shiftSplit(startStr, endStr, rule) {
  if (!startStr || !endStr) return { status: '黑湖未返回报工时段，无法计算' };
  const t0 = bjParse(startStr), t1 = bjParse(endStr);
  if (t0 === null || t1 === null) return { status: '报工时段格式无效，无法计算' };
  if (t1 < t0) return { status: '结束早于开始，无法计算' };
  if (t1 === t0) return { status: '起止时间相同，无时长', totalSeconds: 0 };
  if (!rule) return { status: '未配置班次规则', totalSeconds: (t1 - t0) / 1000 };
  const day0 = Math.floor((t0 + BJ) / DAY) * DAY - BJ - DAY, dayN = Math.floor((t1 + BJ) / DAY) * DAY - BJ;
  let inShift = 0, rest = 0;
  for (let d = day0; d <= dayN; d += DAY) for (const sh of rule.shifts) {
    const a = d + hm(sh.start); let b = d + hm(sh.end); if (b <= a) b += DAY;
    inShift += overlap(t0, t1, a, b);
    for (const k of sh.breaks) { let c = d + hm(k.start), e = d + hm(k.end); if (c < a) { c += DAY; e += DAY; } if (e <= c) e += DAY; rest += overlap(t0, t1, Math.max(c, a), Math.min(e, b)); }
  }
  const total = t1 - t0, outside = Math.max(0, total - inShift);
  return { status: '已计算', totalSeconds: total / 1000, breakSeconds: rest / 1000, overtimeSeconds: outside / 1000, effectiveSeconds: (total - rest) / 1000, inShiftSeconds: (inShift - rest) / 1000 };
}
function currentRule(st) { return (st.shiftRules || []).find(x => !x.revoked) || null; }
function shiftHours(st, q) {
  const s = lsm(), rule = currentRule(st); const pid = q.get('project'), ids = (q.get('reports') || '').split(',').map(x => x.trim()).filter(Boolean);
  const weighed = new Set(activeW(st).map(w => w.taskId));
  const outs = (s.resources.output || []).filter(o => (!pid || String(o.projectId) === String(pid)) && (!ids.length || ids.includes(String(o.id))) && !weighed.has(String(o.taskId)));
  const wrows = ids.length ? [] : activeW(st).filter(w => !pid || w.projectId === String(pid)).map(w => ({ reportId: 'W:' + w.id, taskId: w.taskId, projectId: w.projectId, start: w.start, end: w.end, blacklakeEffectiveSeconds: null, timeSource: 'Cretas 称重', ...shiftSplit(w.start, w.end, rule) }));
  return { rule: rule ? { id: rule.id, shifts: rule.shifts, source: rule.source, at: rule.at, reason: rule.reason } : null, formula: '有效工时 = 报工总时长 − 班次内休息；班外时长记为加班（含在有效工时内）；北京时间，夜班跨零点按次日',
    rows: outs.map(o => ({ reportId: String(o.id), taskId: String(o.taskId ?? ''), projectId: String(o.projectId ?? ''), start: o.starttimeofReport || null, end: o.endtimeofReport || null, blacklakeEffectiveSeconds: num(o.effectiveTime), timeSource: '黑湖报工', ...shiftSplit(o.starttimeofReport, o.endtimeofReport, rule) })).concat(wrows), timeBasis: '有 Cretas 称重的任务用称重的起止时间（R23），其余用黑湖报工起止时间' };
}

// 6) 第五批 ① 六膳门「Cretas 补充」Excel 批量导入（共享 supplementImport：下载模板 → 预检逐行报错 → 确认 → 重算）：
//    GET  import-template?ws=lsm            → xlsx，三张表：BOM投料工序 / 产品路线 / 工序标准。只预填缺口行和参考列，补充列留空（不自动填值）
//    POST import/preflight {ws,fileBase64}  → { token, rows:[{sheet,row,target,status:'ok'|'error',message}], skipped }
//    POST import/confirm   {ws,token,expectedVersion} → 整批写入，batchId 相同；黑湖已有值的行在预检里就拒绝，不覆盖来源
//    POST import/revoke    {ws,batchId,reason,expectedVersion} → 整批撤销，留痕
//    GET  supplements?ws=lsm                → { version, bomFeeds, routes, standards(有效), batches }
//    「重算」由前端读 supplements 后重新计算画布挂接与路线对应；补充值标 Cretas 补充，黑湖有值时以黑湖为准。
let XLSX = null;
function xlsx() { if (!XLSX) XLSX = require(path.resolve(ROOT, '..', '..', 'node_modules', 'xlsx')); return XLSX; }
const SHEETS = { bom: 'BOM投料工序', route: '产品路线', std: '工序标准' };
const importTokens = new Map();
function routeIndex(s) { return new Map((s.resources.routing || []).map(r => [r.processRoutingCode, r])); }
function blackRouteCodes(s, product) { const r = routeIndex(s).get(product && product.prcessRoutingCode); const st = Array.isArray(r && r.processRoutingSteps) ? r.processRoutingSteps : []; return st.map(x => x.code); }
const activeOf = list => (list || []).filter(x => !x.revoked);
function templateBook(st) {
  const s = lsm(), X = xlsx(), wb = X.utils.book_new();
  const prods = s.resources.product || [], pBy = new Map(prods.map(p => [p.productCode, p])), procs = s.resources.process || [];
  const fedBom = new Set(activeOf(st.bomFeeds).map(x => x.bomId));
  const bomRows = (s.resources.bom || []).filter(b => !b.feedProcessCode && !fedBom.has(String(b.id))).map(b => ({ 'BOM行ID': String(b.id), '父产品编码（参考）': b.lastProductCode || '', '父产品名称（参考）': (pBy.get(b.lastProductCode) || {}).productName || '', '投入编码（参考）': b.nextProductCode || '', '投入名称（参考）': (pBy.get(b.nextProductCode) || {}).productName || '', '投料工序编码（填写）': '', '原因（填写）': '' }));
  const routed = new Set(activeOf(st.routes).map(x => x.productCode)), ordered = new Set((s.resources.project || []).map(p => p.productCode));
  const routeRows = prods.filter(p => ordered.has(p.productCode) && !blackRouteCodes(s, p).length && !routed.has(p.productCode)).map(p => ({ '产品编码': p.productCode, '产品名称（参考）': p.productName || '', '工序编码（按顺序，用 ; 分隔，填写）': '', '原因（填写）': '' }));
  const stdOn = new Set(activeOf(st.standards).map(x => x.processCode));
  const stdRows = procs.filter(p => !stdOn.has(p.code)).map(p => ({ '工序编码': p.code, '工序名称（参考）': p.name || '', '标准秒/单位（填写）': '', '单位（填写）': '', '原因（填写）': '' }));
  const add = (rows, name, head) => X.utils.book_append_sheet(wb, X.utils.json_to_sheet(rows.length ? rows : [Object.fromEntries(head.map(h => [h, '']))], { header: head }), name);
  add(bomRows, SHEETS.bom, ['BOM行ID', '父产品编码（参考）', '父产品名称（参考）', '投入编码（参考）', '投入名称（参考）', '投料工序编码（填写）', '原因（填写）']);
  add(routeRows, SHEETS.route, ['产品编码', '产品名称（参考）', '工序编码（按顺序，用 ; 分隔，填写）', '原因（填写）']);
  add(stdRows, SHEETS.std, ['工序编码', '工序名称（参考）', '标准秒/单位（填写）', '单位（填写）', '原因（填写）']);
  X.utils.book_append_sheet(wb, X.utils.aoa_to_sheet([['说明'], ['只填「（填写）」列；参考列不会被导入。留空的行跳过，不会自动填值。'], ['黑湖已有投料工序或路线的不接受补充（黑湖为准）；投料工序必须在该产品的路线里（黑湖路线或本文件 / 已有的补充路线）。'], ['导入后整批可撤销；只写本机原型的 Cretas 补充，不回写黑湖。']]), '说明');
  return X.write(wb, { type: 'buffer', bookType: 'xlsx' });
}
function cell(r, key) { const k = Object.keys(r).find(x => x.replace(/\s/g, '').startsWith(key)); const v = k == null ? '' : r[k]; return v == null ? '' : String(v).trim(); }
function preflightImport(st, buf) {
  const s = lsm(), X = xlsx(); let wb; try { wb = X.read(buf, { type: 'buffer' }); } catch { throw bad('文件不是可读取的 Excel / CSV'); }
  const prods = s.resources.product || [], pBy = new Map(prods.map(p => [p.productCode, p])), procCodes = new Set((s.resources.process || []).map(p => p.code)), bomBy = new Map((s.resources.bom || []).map(b => [String(b.id), b]));
  const out = [], seen = new Set(); let skipped = 0;
  const sheet = name => wb.Sheets[name] ? X.utils.sheet_to_json(wb.Sheets[name], { defval: '' }) : null;
  if (!sheet(SHEETS.bom) && !sheet(SHEETS.route) && !sheet(SHEETS.std)) throw bad('没有找到模板里的工作表（' + Object.values(SHEETS).join(' / ') + '），请用下载的模板');
  // 先收路线（本文件内的补充路线可供同文件的投料工序校验）
  const newRoutes = new Map();
  (sheet(SHEETS.route) || []).forEach((r, i) => { const row = i + 2, code = cell(r, '产品编码'), steps = cell(r, '工序编码'), reason = cell(r, '原因');
    if (!steps && !reason) { skipped++; return; }
    const o = { sheet: SHEETS.route, row, target: code || '（未填产品编码）', kind: 'route' }; const err = m => out.push({ ...o, status: 'error', message: m });
    const p = pBy.get(code); if (!code) return err('缺少产品编码'); if (!p) return err('黑湖产品目录里没有 ' + code + '，不按名称猜配');
    if (blackRouteCodes(s, p).length) return err('黑湖已有路线 ' + p.prcessRoutingCode + '，不接受补充（黑湖为准）');
    if (activeOf(st.routes).some(x => x.productCode === code)) return err('已有生效的补充路线，请先撤销原批次再导入');
    if (seen.has('route:' + code)) return err('本文件里重复'); seen.add('route:' + code);
    const list = steps.split(/[;；,，\s]+/).map(x => x.trim()).filter(Boolean); if (!list.length) return err('工序编码未填写');
    const unknown = list.filter(c => !procCodes.has(c)); if (unknown.length) return err('工序目录里没有：' + unknown.join('、'));
    if (new Set(list).size !== list.length) return err('同一路线里工序重复');
    if (!reason) return err('请填写原因');
    newRoutes.set(code, list); out.push({ ...o, status: 'ok', message: '补充路线 ' + list.join(' → '), data: { productCode: code, steps: list, reason } }); });
  const routeOf = code => { const p = pBy.get(code); const b = p ? blackRouteCodes(s, p) : []; if (b.length) return { steps: b, from: '黑湖路线' }; const a = activeOf(st.routes).find(x => x.productCode === code); if (a) return { steps: a.steps, from: '已有补充路线' }; if (newRoutes.has(code)) return { steps: newRoutes.get(code), from: '本文件补充路线' }; return null; };
  (sheet(SHEETS.bom) || []).forEach((r, i) => { const row = i + 2, id = cell(r, 'BOM行ID'), feed = cell(r, '投料工序编码'), reason = cell(r, '原因');
    if (!feed && !reason) { skipped++; return; }
    const o = { sheet: SHEETS.bom, row, target: 'BOM ' + (id || '（未填ID）'), kind: 'bom' }; const err = m => out.push({ ...o, status: 'error', message: m });
    const b = bomBy.get(id); if (!id) return err('缺少 BOM行ID'); if (!b) return err('黑湖快照里没有 BOM 行 ' + id);
    if (b.feedProcessCode) return err('黑湖已有投料工序 ' + b.feedProcessCode + '，不接受补充（黑湖为准）');
    if (activeOf(st.bomFeeds).some(x => x.bomId === id)) return err('已有生效的补充，请先撤销原批次再导入');
    if (seen.has('bom:' + id)) return err('本文件里重复'); seen.add('bom:' + id);
    if (!feed) return err('投料工序编码未填写'); if (!procCodes.has(feed)) return err('工序目录里没有 ' + feed);
    const rt = routeOf(b.lastProductCode); if (!rt) return err('父产品 ' + b.lastProductCode + ' 没有路线，先在「产品路线」表补路线');
    if (!rt.steps.includes(feed)) return err(feed + ' 不在父产品的' + rt.from + '（' + rt.steps.join(' → ') + '）里');
    if (!reason) return err('请填写原因');
    out.push({ ...o, status: 'ok', message: '投料工序 ' + feed + '（依据' + rt.from + '）', data: { bomId: id, productCode: b.lastProductCode, processCode: feed, reason } }); });
  (sheet(SHEETS.std) || []).forEach((r, i) => { const row = i + 2, code = cell(r, '工序编码'), sec = cell(r, '标准秒'), unit = cell(r, '单位'), reason = cell(r, '原因');
    if (!sec && !unit && !reason) { skipped++; return; }
    const o = { sheet: SHEETS.std, row, target: code || '（未填工序编码）', kind: 'std' }; const err = m => out.push({ ...o, status: 'error', message: m });
    if (!code) return err('缺少工序编码'); if (!procCodes.has(code)) return err('工序目录里没有 ' + code);
    if (seen.has('std:' + code)) return err('本文件里重复'); seen.add('std:' + code);
    const n = num(sec); if (n === null) return err('标准秒/单位未填写'); if (Number.isNaN(n) || n <= 0) return err('标准秒/单位必须是正数，现在是「' + sec + '」');
    if (!unit) return err('单位未填写'); if (!reason) return err('请填写原因');
    const prev = activeOf(st.standards).find(x => x.processCode === code);
    out.push({ ...o, status: 'ok', message: '标准 ' + n + ' 秒/' + unit + (prev ? '（取代现有 ' + prev.secondsPerUnit + ' 秒/' + prev.unit + '）' : ''), data: { processCode: code, secondsPerUnit: n, unit, reason } }); });
  return { rows: out, skipped };
}
function confirmImport(st, b, key) {
  const t = importTokens.get(b.token); if (!t || t.key !== key) throw bad('预检结果已失效，请重新选择文件', 410);
  if (st.version !== b.expectedVersion || t.version !== st.version) throw bad('数据已被他人更新，请重新预检', 409);
  const ok = t.rows.filter(r => r.status === 'ok'); if (!ok.length) throw bad('没有可导入的行');
  const at = now(), by = str(b.actor, 40) || '当前账号', batchId = 'IMP-' + crypto.randomBytes(4).toString('hex');
  st.bomFeeds = st.bomFeeds || []; st.routes = st.routes || []; st.standards = st.standards || []; st.importBatches = st.importBatches || [];
  for (const r of ok) {
    const base = { source: 'Cretas 补充', reason: r.data.reason, at, by, batchId, revoked: null };
    if (r.kind === 'route') st.routes.push({ id: 'RT-' + crypto.randomBytes(4).toString('hex'), productCode: r.data.productCode, steps: r.data.steps, ...base });
    else if (r.kind === 'bom') st.bomFeeds.push({ id: 'BF-' + crypto.randomBytes(4).toString('hex'), bomId: r.data.bomId, productCode: r.data.productCode, processCode: r.data.processCode, ...base });
    else { const prev = st.standards.find(x => x.processCode === r.data.processCode && !x.revoked); if (prev) prev.revoked = { at, by, reason: '被导入批次 ' + batchId + ' 取代' };
      st.standards.push({ id: 'ST-' + crypto.randomBytes(4).toString('hex'), processCode: r.data.processCode, secondsPerUnit: Math.round(r.data.secondsPerUnit * 1e6) / 1e6, unit: r.data.unit, replaces: prev ? prev.id : null, ...base }); }
  }
  const counts = { route: ok.filter(r => r.kind === 'route').length, bom: ok.filter(r => r.kind === 'bom').length, std: ok.filter(r => r.kind === 'std').length };
  st.importBatches.push({ batchId, at, by, file: str(t.file, 120), counts, rejected: t.rows.length - ok.length, revoked: null });
  importTokens.delete(b.token);
  return { id: batchId, batchId, written: ok.length, counts };
}
function revokeImport(st, b) {
  if (st.version !== b.expectedVersion) throw bad('数据已被他人更新，请刷新后重试', 409);
  const batch = (st.importBatches || []).find(x => x.batchId === b.batchId); if (!batch) throw bad('导入批次不存在', 404); if (batch.revoked) throw bad('该批次已撤销');
  const reason = str(b.reason, 200); if (!reason) throw bad('撤销必须填写原因');
  const mark = { at: now(), by: str(b.actor, 40) || '当前账号', reason: '整批撤销：' + reason }; let n = 0;
  for (const list of [st.bomFeeds, st.routes, st.standards]) for (const x of list || []) if (x.batchId === b.batchId && !x.revoked) { x.revoked = mark; n++; }
  batch.revoked = mark; return { id: b.batchId, batchId: b.batchId, revokedCount: n };
}
function supplementsView(st) { return { version: st.version, bomFeeds: activeOf(st.bomFeeds), routes: activeOf(st.routes), standards: activeOf(st.standards), batches: (st.importBatches || []).slice().reverse() }; }

// 7) 第六批（R23）六膳门「称重报工」——Cretas 为主来源（来源标「Cretas」，不是「Cretas 补充」），不回写黑湖：
//    GET  weighins?ws=lsm[&task=][&project=]          → { version, asOf, entries(未撤销), tasks:{[taskId]: 汇总} }
//    POST weighins/save   {ws,taskId,inputKg,goodGrossKg,tareKg,defects:[{defectId,kg}],byproducts:[{productCode,kg}],start,end,note?,expectedVersion}
//    POST weighins/revoke {ws,id,reason,expectedVersion}
//    GET  yield-standards / POST yield-standards/save {processCode,rate(0~1.5),tolerance(百分点,默认5),reason} / revoke  —— 标准出成率（Cretas 补充）
//    GET  weighin-reconcile[&project=]  → 按任务并排 Cretas 称重合计与黑湖报工（给 D 的对账组件）
//    GET  weighins/inbound[&project=][&product=] → 给 C 生产入库带出良品净重
//    GET  weighing-summary  → 按工单汇总（给 B 成本；同名函数 weighingSummary(ws, session) 导出给服务端直接调用）
//    口径：良品净重 = 良品毛重 − 皮重；工序出成率 = 良品净重 ÷ 投入；副产品单列不计入良品；单位 kg，两位小数。
//    起止时间按北京时间录入；结束必须晚于开始。产出（良品净重＋不良＋副产品）不得大于投入。
const kg2 = v => Math.round(Number(v) * 100) / 100;
const kgOf = (v, label, { required = false, positive = false } = {}) => { if (v === null || v === undefined || v === '') { if (required) throw bad(label + '未填写'); return 0; } const n = Number(v); if (!Number.isFinite(n)) throw bad(label + '不是数字'); if (n < 0) throw bad(label + '不能是负数'); if (positive && n <= 0) throw bad(label + '必须大于 0'); return kg2(n); };
const bjIso = v => { const raw = String(v || '').trim(); if (!raw) return null; const t = Date.parse(raw.replace(' ', 'T') + (/[zZ]$|[-+]\d\d:?\d\d$/.test(raw) ? '' : (raw.length <= 16 ? ':00' : '') + '+08:00')); return Number.isFinite(t) ? new Date(t).toISOString() : null; };
function taskInfo(s, taskId) {
  const t = (s.resources.task || []).find(x => String(x.id) === String(taskId)); if (!t) throw bad('黑湖快照里没有任务 ' + taskId + '，不按相近记录猜配', 404);
  const proc = (s.resources.process || []).find(p => String(p.id) === String(t.processId)), proj = (s.resources.project || []).find(p => String(p.id) === String(t.projectId));
  return { t, proc, proj, processCode: proc ? proc.code : null, processName: t.processName || (proc && proc.name) || null };
}
function saveWeighin(st, b, session) {
  if (st.version !== b.expectedVersion) throw bad('数据已被他人更新，请刷新后重试', 409);
  const s = lsm(), { t, proj, processCode, processName } = taskInfo(s, str(String(b.taskId ?? ''), 40));
  const inputKg = kgOf(b.inputKg, '投入重量', { required: true, positive: true }), goodGrossKg = kgOf(b.goodGrossKg, '良品毛重', { required: true }), tareKg = kgOf(b.tareKg, '皮重');
  if (tareKg > goodGrossKg) throw bad('皮重 ' + tareKg + ' kg 大于良品毛重 ' + goodGrossKg + ' kg，请核对');
  const goodKg = kg2(goodGrossKg - tareKg);
  const dict = dictionary(st.ws, session), defects = (Array.isArray(b.defects) ? b.defects : []).filter(x => x && (x.defectId || x.kg)).slice(0, 20).map(x => {
    const d = dict.find(y => y.id === x.defectId && y.active !== false); if (!d) throw bad('不良项不存在或已停用，请在「不良分析」维护后再选');
    if (Array.isArray(d.processes) && d.processes.length && processCode && !d.processes.includes(processCode)) throw bad('不良项「' + d.name + '」不适用于工序 ' + processName);
    return { defectId: d.id, code: d.code || '', name: d.name, kg: kgOf(x.kg, '不良「' + d.name + '」重量', { positive: true }) }; });
  const prodBy = new Map((s.resources.product || []).map(p => [p.productCode, p]));
  const byproducts = (Array.isArray(b.byproducts) ? b.byproducts : []).filter(x => x && (x.productCode || x.kg)).slice(0, 20).map(x => {
    const p = prodBy.get(str(x.productCode, 40)); if (!p) throw bad('副产品要从产品目录里选（' + (x.productCode || '未选') + ' 不在目录里）');
    return { productCode: p.productCode, name: p.productName || p.productCode, kg: kgOf(x.kg, '副产品「' + (p.productName || p.productCode) + '」重量', { positive: true }) }; });
  const defectKg = kg2(defects.reduce((n, x) => n + x.kg, 0)), byproductKg = kg2(byproducts.reduce((n, x) => n + x.kg, 0)), outKg = kg2(goodKg + defectKg + byproductKg);
  if (outKg > inputKg + 0.005) throw bad('良品净重＋不良＋副产品 = ' + outKg + ' kg，大于投入 ' + inputKg + ' kg，请核对称重');
  const start = bjIso(b.start), end = bjIso(b.end); if (!start || !end) throw bad('请填写开始和结束时间');
  if (start === end) throw bad('开始和结束时间相同，请改结束时间'); if (end < start) throw bad('结束时间早于开始时间'); if (Date.parse(start) > Date.now() + 5 * 60e3 || Date.parse(end) > Date.now() + 5 * 60e3) throw bad('开始或结束时间晚于现在（超过 5 分钟），请改成已经发生的时间');
  st.weighins = st.weighins || [];
  const w = { id: 'WI-' + crypto.randomBytes(4).toString('hex'), taskId: String(t.id), projectId: String(t.projectId), projectCode: proj ? proj.projectCode : null, processCode, processName, productCode: t.productCode || (proj && proj.productCode) || null,
    inputKg, goodGrossKg, tareKg, goodKg, defects, defectKg, byproducts, byproductKg, lossKg: kg2(inputKg - outKg), start, end, note: str(b.note, 200), source: 'Cretas', at: now(), by: str(b.actor, 40) || '当前账号', revoked: null };
  st.weighins.push(w); return w;
}
function revokeWeighin(st, b) {
  if (st.version !== b.expectedVersion) throw bad('数据已被他人更新，请刷新后重试', 409);
  const w = (st.weighins || []).find(x => x.id === b.id); if (!w) throw bad('称重记录不存在', 404); if (w.revoked) throw bad('该记录已撤销');
  const reason = str(b.reason, 200); if (!reason) throw bad('撤销必须填写原因'); w.revoked = { at: now(), by: str(b.actor, 40) || '当前账号', reason }; return w;
}
function saveYieldStd(st, b) {
  if (st.version !== b.expectedVersion) throw bad('数据已被他人更新，请刷新后重试', 409);
  const code = str(b.processCode, 40); if (!(lsm().resources.process || []).some(p => p.code === code)) throw bad('工序目录里没有 ' + code);
  const rate = Number(b.rate); if (!Number.isFinite(rate) || rate <= 0 || rate > 1.5) throw bad('标准出成率应在 0 ~ 150% 之间');
  const tol = b.tolerance === undefined || b.tolerance === '' ? 5 : Number(b.tolerance); if (!Number.isFinite(tol) || tol < 0 || tol > 50) throw bad('允许偏离应在 0 ~ 50 个百分点之间');
  const reason = str(b.reason, 200); if (!reason) throw bad('请填写原因'); st.yieldStandards = st.yieldStandards || []; const at = now(), by = str(b.actor, 40) || '当前账号';
  const prev = st.yieldStandards.find(x => x.processCode === code && !x.revoked); if (prev) prev.revoked = { at, by, reason: '被新标准取代：' + reason };
  const y = { id: 'YS-' + crypto.randomBytes(4).toString('hex'), processCode: code, rate: Math.round(rate * 10000) / 10000, tolerance: tol, source: 'Cretas 补充', reason, at, by, replaces: prev ? prev.id : null, revoked: null }; st.yieldStandards.push(y); return y;
}
function revokeYieldStd(st, b) { if (st.version !== b.expectedVersion) throw bad('数据已被他人更新，请刷新后重试', 409); const y = (st.yieldStandards || []).find(x => x.id === b.id); if (!y) throw bad('标准不存在', 404); if (y.revoked) throw bad('已撤销'); const reason = str(b.reason, 200); if (!reason) throw bad('撤销必须填写原因'); y.revoked = { at: now(), by: str(b.actor, 40) || '当前账号', reason }; return y; }
const activeW = st => (st.weighins || []).filter(w => !w.revoked);
// 第七批 R34（R30 落地）：录入明显有问题的称重记录标「数据疑点 · 找谁核」，只标不改原值，也不当业务异常。
// 规则：①良品净重 ÷ 投入 ≥ 100%；②开始 = 结束；③单位明显不符：投入或良品 ≥ 5000 kg（像把克当千克）、投入 < 0.1 kg（像把吨当千克）。
function suspectsOf(w) {
  const r = []; if (w.inputKg > 0 && w.goodKg / w.inputKg >= 1) r.push('出成率 ' + (Math.round(w.goodKg / w.inputKg * 10000) / 100).toFixed(2) + '%，达到或超过 100%');
  if (w.start && w.start === w.end) r.push('开始和结束时间相同');
  if (w.inputKg >= 5000 || w.goodKg >= 5000) r.push('重量 ≥ 5000 kg，像是把克当成了千克'); else if (w.inputKg > 0 && w.inputKg < 0.1) r.push('投入不到 0.1 kg，像是把吨当成了千克');
  return r;
}
function taskSummary(st, list) {
  const stds = new Map((st.yieldStandards || []).filter(y => !y.revoked).map(y => [y.processCode, y]));
  const by = {}; for (const w of list) { const x = by[w.taskId] = by[w.taskId] || { suspectCount: 0, suspectReasons: [], taskId: w.taskId, projectId: w.projectId, projectCode: w.projectCode, processCode: w.processCode, processName: w.processName, records: 0, inputKg: 0, goodKg: 0, defectKg: 0, byproductKg: 0, byproducts: {}, firstStart: null, lastEnd: null };
    x.records++; { const sp = suspectsOf(w); if (sp.length) { x.suspectCount = (x.suspectCount || 0) + 1; x.suspectReasons = [...new Set([...(x.suspectReasons || []), ...sp])]; } } x.inputKg += w.inputKg; x.goodKg += w.goodKg; x.defectKg += w.defectKg; x.byproductKg += w.byproductKg; for (const p of w.byproducts) { const k = x.byproducts[p.productCode] = x.byproducts[p.productCode] || { productCode: p.productCode, name: p.name, kg: 0 }; k.kg += p.kg; }
    if (!x.firstStart || w.start < x.firstStart) x.firstStart = w.start; if (!x.lastEnd || w.end > x.lastEnd) x.lastEnd = w.end; }
  for (const x of Object.values(by)) { for (const k of ['inputKg', 'goodKg', 'defectKg', 'byproductKg']) x[k] = kg2(x[k]); x.byproducts = Object.values(x.byproducts).map(p => ({ ...p, kg: kg2(p.kg) }));
    x.yieldRate = x.inputKg > 0 ? Math.round(x.goodKg / x.inputKg * 10000) / 10000 : null; const sd = stds.get(x.processCode) || null;
    x.standard = sd ? { rate: sd.rate, tolerance: sd.tolerance, id: sd.id } : null; x.deviationPts = sd && x.yieldRate !== null ? Math.round((x.yieldRate - sd.rate) * 10000) / 100 : null;
    x.yieldStatus = x.yieldRate === null ? '没有投入，不计算' : !sd ? '未设标准出成率' : Math.abs(x.deviationPts) > sd.tolerance ? (x.deviationPts < 0 ? '低于标准' : '高于标准') : '在标准范围内'; }
  return by;
}
function blackTask(s, taskId) {
  const t = (s.resources.task || []).find(x => String(x.id) === String(taskId)); const outs = (s.resources.output || []).filter(o => String(o.taskId) === String(taskId));
  const units = [...new Set(outs.map(o => o.stockUnit).filter(Boolean))], kgOnly = units.length === 1 && /^kg$/i.test(units[0]);
  return { reports: outs.length, unit: units.join(' / ') || null, goodKg: kgOnly ? kg2(outs.reduce((n, o) => n + (num(o.numberofgoodProducts) || 0), 0)) : null, defectKg: kgOnly ? kg2(outs.reduce((n, o) => n + (num(o.numberofDefectives) || 0), 0)) : null,
    effectiveHours: t && num(t.sumEffectiveTime) !== null ? Math.round(num(t.sumEffectiveTime) / 36) / 100 : null, note: kgOnly || !outs.length ? null : '黑湖单位是 ' + units.join(' / ') + '，不和 kg 比较' };
}
function weighinsView(st, q) {
  const task = q.get('task'), project = q.get('project'); const list = activeW(st).filter(w => (!task || w.taskId === String(task)) && (!project || w.projectId === String(project)));
  return { version: st.version, asOf: lsm().checkedAt || null, revision: lsm().revision || null, entries: list.slice().sort((a, b) => b.start.localeCompare(a.start)).map(w => ({ ...w, suspects: suspectsOf(w) })), revoked: (st.weighins || []).filter(w => w.revoked && (!task || w.taskId === String(task))).length, tasks: taskSummary(st, list) };
}
function reconcileView(st, q) {
  const s = lsm(), project = q.get('project'), sum = taskSummary(st, activeW(st).filter(w => !project || w.projectId === String(project)));
  const pids = new Set(Object.values(sum).map(x => x.projectId)); if (project) pids.add(String(project));
  // 第六批审计退回 A-5：不指定工单时，范围 = 有称重的工单的全部任务 ＋ 所有执行中（processStatus 10）且黑湖已有报工的任务；执行中但两边都没有记录的只计数
  const hasOut = new Set((s.resources.output || []).map(o => String(o.taskId)));
  const running = (s.resources.task || []).filter(t => Number(t.processStatus) === 10);
  const tasks = (s.resources.task || []).filter(t => pids.has(String(t.projectId)) || (!project && Number(t.processStatus) === 10 && hasOut.has(String(t.id))));
  const rows = tasks.map(t => { const c = sum[String(t.id)] || null, bl = blackTask(s, t.id), proj = (s.resources.project || []).find(p => String(p.id) === String(t.projectId));
    return { key: String(t.id), label: (proj ? proj.projectCode : t.projectId) + ' · ' + (t.processName || '') + (c && c.suspectCount ? ' · 数据疑点' : ''), projectId: String(t.projectId), owner: c && c.suspectCount ? '车间（数据疑点 · 先核称重录入：' + c.suspectReasons.join('；') + '）' : '车间', suspects: c && c.suspectCount ? c.suspectReasons : [], cretas: c ? { goodKg: c.goodKg, defectKg: c.defectKg, byproductKg: c.byproductKg, yieldRate: c.yieldRate, count: c.records, effectiveHours: null } : null,
      blacklake: bl.reports ? { goodKg: bl.goodKg, defectKg: bl.defectKg, count: bl.reports, effectiveHours: bl.effectiveHours, unit: bl.unit, note: bl.note } : null }; }).filter(r => r.cretas || r.blacklake);
  const noRecord = project ? 0 : running.filter(t => !hasOut.has(String(t.id)) && !sum[String(t.id)]).length;
  return { asOf: s.checkedAt || null, revision: s.revision || null, fields: [{ key: 'goodKg', label: '良品（kg）' }, { key: 'defectKg', label: '不良（kg）' }, { key: 'count', label: '笔数' }], rows,
    scope: project ? '本工单全部任务' : '有称重的工单的全部任务，加上所有执行中且黑湖已有报工的任务（没称重的归「只在黑湖」）', runningTasks: project ? null : running.length, runningNoRecord: noRecord, runningNoRecordNote: noRecord ? '另有 ' + noRecord + ' 个执行中任务两边都还没有记录，不进对账' : null };
}
function routeCodes(s, productCode) { const p = (s.resources.product || []).find(x => x.productCode === productCode); const r = p && (s.resources.routing || []).find(x => x.processRoutingCode === p.prcessRoutingCode); return Array.isArray(r && r.processRoutingSteps) ? r.processRoutingSteps.map(x => x.code) : []; }
function inboundView(st, q) {
  const s = lsm(), project = q.get('project'), product = q.get('product'); const sum = taskSummary(st, activeW(st).filter(w => (!project || w.projectId === String(project)) && (!product || w.productCode === product)));
  const rows = Object.values(sum).map(x => { const proj = (s.resources.project || []).find(p => String(p.id) === x.projectId); const pc = proj ? proj.productCode : null, codes = pc ? routeCodes(s, pc) : [];
    const ws = activeW(st).filter(w => w.taskId === x.taskId); return { projectId: x.projectId, projectCode: x.projectCode, productCode: pc, productName: proj ? proj.productName || null : null, taskId: x.taskId, processCode: x.processCode, processName: x.processName,
      isLastProcess: codes.length ? codes[codes.length - 1] === x.processCode : null, goodNetKg: x.goodKg, entries: x.records, firstAt: x.firstStart, lastAt: x.lastEnd, source: 'Cretas', entryIds: ws.map(w => w.id) }; });
  return { asOf: now(), rows };
}
function weighingSummary(ws, session) {
  // 第六批 · 口径（organizer）：按工序分开给，标出路线第一道 / 最后一道；整单良品只取路线最后一道工序，整单投入只取路线第一道工序；
  // 整单出成率 = 最后一道良品 ÷ 第一道投入。最后一道（或第一道）还没称重、或产品没有路线时为 null 并写原因，不拿别的工序代替（与 C 生产入库带出同一口径）。
  const st = read(ws, session), s = lsm(), list = activeW(st), projects = {};
  const byProj = {}; for (const w of list) (byProj[w.projectId] = byProj[w.projectId] || []).push(w);
  for (const [pid, ws2] of Object.entries(byProj)) {
    const proj = (s.resources.project || []).find(p => String(p.id) === pid), codes = proj ? routeCodes(s, proj.productCode) : [];
    const procs = {}; for (const w of ws2) { const k = w.processCode || ('task:' + w.taskId); const x = procs[k] = procs[k] || { code: w.processCode, name: w.processName, taskIds: [], inputKg: 0, goodKg: 0, defectKg: 0, byproductKg: 0, records: 0 };
      if (!x.taskIds.includes(w.taskId)) x.taskIds.push(w.taskId); x.inputKg += w.inputKg; x.goodKg += w.goodKg; x.defectKg += w.defectKg; x.byproductKg += w.byproductKg; x.records++; }
    const firstCode = codes[0] || null, lastCode = codes.length ? codes[codes.length - 1] : null, pname = c => ((s.resources.process || []).find(p => p.code === c) || {}).name || c;
    const plist = Object.values(procs).map(x => ({ ...x, inputKg: kg2(x.inputKg), goodKg: kg2(x.goodKg), defectKg: kg2(x.defectKg), byproductKg: kg2(x.byproductKg),
      routeIndex: codes.indexOf(x.code), isFirst: !!firstCode && x.code === firstCode, isLast: !!lastCode && x.code === lastCode,
      yieldRate: x.inputKg > 0 ? Math.round(x.goodKg / x.inputKg * 10000) / 10000 : null })).sort((a, b) => (a.routeIndex < 0 ? 999 : a.routeIndex) - (b.routeIndex < 0 ? 999 : b.routeIndex));
    const first = plist.find(x => x.isFirst) || null, last = plist.find(x => x.isLast) || null;
    const missing = !codes.length ? '产品没有路线，不能认定第一道 / 最后一道工序' : [!first ? '第一道工序「' + pname(firstCode) + '」还没有称重' : '', !last ? '最后一道工序「' + pname(lastCode) + '」还没有称重' : ''].filter(Boolean).join('；') || null;
    const bp = {}; for (const w of ws2) for (const p of w.byproducts) { const k = bp[p.productCode] = bp[p.productCode] || { productCode: p.productCode, name: p.name, kg: 0 }; k.kg += p.kg; }
    const inputKg = first ? first.inputKg : null, goodKg = last ? last.goodKg : null;
    projects[pid] = { records: ws2.length, inputKg, goodKg, defectKg: kg2(ws2.reduce((n, w) => n + w.defectKg, 0)), byproducts: Object.values(bp).map(x => ({ ...x, kg: kg2(x.kg) })),
      firstProcess: firstCode ? { code: firstCode, name: first ? first.name : pname(firstCode), weighed: !!first } : null, finalProcess: lastCode ? { code: lastCode, name: last ? last.name : pname(lastCode), weighed: !!last } : null,
      finalBasis: '整单良品只取产品路线最后一道工序、整单投入只取路线第一道工序；缺哪一道就写缺，不拿别的工序代替（与 C 生产入库同口径）', missing,
      yieldRate: inputKg && goodKg !== null ? Math.round(goodKg / inputKg * 10000) / 10000 : null,
      startAt: ws2.reduce((m, w) => !m || w.start < m ? w.start : m, null), endAt: ws2.reduce((m, w) => !m || w.end > m ? w.end : m, null), lastAt: ws2.reduce((m, w) => !m || w.at > m ? w.at : m, null),
      // B 约定的别名字段（口径同上）：routeFinal 不管有没有称重都给路线最后一道；finalWeighed 没有路线时为 false
      routeFinal: lastCode ? { code: lastCode, name: last ? last.name : pname(lastCode) } : null, finalWeighed: !!last,
      processes: plist.map(x => ({ ...x, routeIndex: x.routeIndex < 0 ? null : x.routeIndex })) };
  }
  return { revision: s.revision || null, at: now(), projects };
}

async function handle({ req, res, u, p, send, readBody, root }) {
  if (root) ROOT = root;
  const sub = p.replace(/^\/__unified\/a\/api\//, '');
  const json = (status, data) => send(res, status, data);
  try {
    const session = sessionOf(u);
    if (req.method === 'GET') {
      const ws = wsOf(u);
      if (sub === 'project-extras') { if (ws !== 'lsm') return json(200, { success: true, data: { available: false, reason: '味美工作区没有黑湖委外和操作日志' } }); return json(200, { success: true, data: { available: true, ...projectExtras(u.searchParams.get('project')) } }); }
      if (sub === 'import-template') { if (ws !== 'lsm') return json(400, { success: false, message: '批量补充模板只用于六膳门工作区' }); const buf = templateBook(read(ws, session)); res.writeHead(200, { 'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'content-disposition': "attachment; filename*=UTF-8''" + encodeURIComponent('六膳门生产补充模板-' + session + '.xlsx'), 'cache-control': 'no-store' }); return res.end(buf); }
      if (sub === 'weighins' || sub === 'weighin-reconcile' || sub === 'weighins/inbound' || sub === 'weighing-summary' || sub === 'yield-standards') { if (ws !== 'lsm') return json(200, { success: true, data: { available: false, reason: '称重报工只用于六膳门工作区' } }); const st = read(ws, session);
        if (sub === 'weighins') return json(200, { success: true, data: weighinsView(st, u.searchParams) });
        if (sub === 'weighin-reconcile') return json(200, { success: true, data: reconcileView(st, u.searchParams) });
        if (sub === 'weighins/inbound') return json(200, { success: true, data: inboundView(st, u.searchParams) });
        if (sub === 'yield-standards') return json(200, { success: true, data: { version: st.version, standards: (st.yieldStandards || []).filter(y => !y.revoked), history: st.yieldStandards || [] } });
        return json(200, { success: true, data: weighingSummary(ws, session) }); }
      if (sub === 'supplements') { const st = read(ws, session); return json(200, { success: true, data: supplementsView(st) }); }
      if (sub === 'shift-rules') { const st = read(ws, session); return json(200, { success: true, data: { version: st.version, rule: currentRule(st), history: st.shiftRules || [] } }); }
      if (sub === 'shift-hours') { if (ws !== 'lsm') return json(200, { success: true, data: { available: false, reason: '味美工作区的报工没有起止时段字段，不计算' } }); const st = read(ws, session); return json(200, { success: true, data: { available: true, ...shiftHours(st, u.searchParams) } }); }
      if (sub === 'efficiency') { if (ws !== 'lsm') return json(200, { success: true, data: { available: false, reason: '味美工作区没有工序任务与有效工时数据，不计算效率', processes: [] } }); const st = read(ws, session); return json(200, { success: true, data: { available: true, rule: EFF_RULE, standardsVersion: st.version, project: u.searchParams.get('project') || null, processes: efficiency(st, u.searchParams.get('project')) } }); }
      if (sub === 'standards') { const st = read(ws, session); return json(200, { success: true, data: { version: st.version, standards: st.standards || [] } }); }
      if (sub === 'defect-captures') { const st = read(ws, session), rid = u.searchParams.get('reportId'); return json(200, { success: true, data: { version: st.version, captures: rid ? st.captures.filter(c => c.reportId === rid) : st.captures } }); }
      return json(404, { success: false, message: '未找到' });
    }
    if (req.method !== 'POST') return json(405, { success: false, message: '不支持的方法' });
    if (req.headers['x-prototype-action'] !== 'explicit') return json(403, { success: false, message: '请从页面上的明确操作触发保存。' });
    let b; try { const raw = await readBody(req, sub === 'import/preflight' ? 8000000 : 400000); b = Buffer.isBuffer(raw) ? JSON.parse(raw.toString('utf8') || '{}') : typeof raw === 'string' ? JSON.parse(raw || '{}') : (raw || {}); } catch { return json(400, { success: false, message: '请求格式不正确' }); }
    const ws = wsOf(u, b); const st = read(ws, session); let out;
    if (sub === 'import/preflight') { if (ws !== 'lsm') return json(400, { success: false, message: '批量补充只用于六膳门工作区' }); const buf = Buffer.from(String(b.fileBase64 || ''), 'base64'); if (!buf.length) return json(400, { success: false, message: '文件为空' }); if (buf.length > 5000000) return json(400, { success: false, message: '文件超过 5MB' }); const r = preflightImport(st, buf); const token = crypto.randomUUID(); for (const [k, v] of importTokens) if (Date.now() - v.t > 3600e3) importTokens.delete(k); importTokens.set(token, { key: ws + '/' + session, version: st.version, rows: r.rows, file: b.fileName, t: Date.now() }); return json(200, { success: true, data: { token, skipped: r.skipped, rows: r.rows.map(({ data, ...x }) => x) } }); }
    if (sub === 'defect-captures/save') out = saveCapture(st, b, session);
    else if (sub === 'defect-captures/revoke') out = revokeCapture(st, b);
    else if (sub === 'standards/save') out = saveStandard(st, b);
    else if (sub === 'standards/revoke') out = revokeStandard(st, b);
    else if (sub === 'shift-rules/save') out = saveShiftRule(st, b);
    else if (sub === 'shift-rules/revoke') out = revokeShiftRule(st, b);
    else if (sub === 'weighins/save') out = saveWeighin(st, b, session);
    else if (sub === 'weighins/revoke') out = revokeWeighin(st, b);
    else if (sub === 'yield-standards/save') out = saveYieldStd(st, b);
    else if (sub === 'yield-standards/revoke') out = revokeYieldStd(st, b);
    else if (sub === 'import/confirm') out = confirmImport(st, b, ws + '/' + session);
    else if (sub === 'import/revoke') out = revokeImport(st, b);
    else return json(404, { success: false, message: '未找到' });
    st.version += 1; st.updatedAt = now(); write(st);
    return json(200, { success: true, data: { id: out.id, version: st.version, capture: out } });
  } catch (e) { return json(e.status || 500, { success: false, message: String(e.message || e) }); }
}
module.exports = { handle, _saveCapture: saveCapture, _saveStandard: saveStandard, _shiftSplit: shiftSplit, _preflightImport: preflightImport, _templateBook: templateBook, _confirmImport: confirmImport, _revokeImport: revokeImport, _tokens: importTokens, weighingSummary, _saveWeighin: saveWeighin, _taskSummary: taskSummary, _reconcileView: reconcileView, _inboundView: inboundView };
