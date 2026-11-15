'use strict';
// F 线后端：数字班组自己的状态（工厂启用配置、等我确认收件箱、工作记录与额度、主动汇报处理状态、入职草稿）。
// 挂载点 /__unified/f/api/*（由 D 在 server.cjs 转发）。只写 unified-v1/private/f-crew/<ws>/<session>.json；写入必须带 test- 会话。
// 业务写入本身不在这里做：确认后由页面调用现有接口（E 线协同任务 /__unified/e/api/tasks/save），这里只登记结果。
// R29：交给人办（建了协同任务）记「已交办」，不扣额度；回读 E 保存区看到该任务「已完成」才记「已办成」扣 1 个额度；任务取消记「没办成」。
// 凭证绑定：任务的 origin.ref 必须是「F:<本事项 id>」，别的任务号不能拿来充数；核实不到记「结果不明」。
const fs = require('fs'), path = require('path'), crypto = require('crypto');

let ROOT = path.resolve(__dirname, '..', '..');
const WS = ['lsm', 'ydwm'];
const CREW = ['clerk', 'keeper', 'planner', 'inspector', 'accountant'];
const OUTCOME = { handed: '已交办', done: '已办成', failed: '没办成', unknown: '结果不明', demo: '原型演示，未执行' };
const CONCLUSIONS = ['已处理', '误报', '接受偏差'];

function bad(msg, status = 400) { const e = Error(msg); e.status = status; return e; }
const str = (v, max = 400) => typeof v === 'string' ? v.trim().slice(0, max) : '';
const now = () => new Date().toISOString();
function sessionOf(u) {
  const s = u.searchParams.get('acceptanceSession');
  if (s == null || s === '') return 'default';
  if (!/^test-[a-z0-9-]{8,64}$/.test(s)) throw bad('会话名称必须以 test- 开头，只含小写字母、数字和连字符');
  return s;
}
function wsOf(u, b) { const w = (b && b.ws) || u.searchParams.get('ws'); if (!WS.includes(w)) throw bad('缺少工作区参数（lsm 或 ydwm）'); return w; }
const file = (ws, s) => path.join(ROOT, 'private', 'f-crew', ws, s + '.json');
function empty(ws, session) { return { schema: 'f-crew-v1', ws, session, version: 0, config: { enabled: Object.fromEntries(CREW.map(c => [c, true])), log: [] }, inbox: [], records: [], reports: {}, onboard: [] }; }
function read(ws, s) { const f = file(ws, s); if (!fs.existsSync(f)) return empty(ws, s); return JSON.parse(fs.readFileSync(f, 'utf8')); }
function write(st) { const f = file(st.ws, st.session); fs.mkdirSync(path.dirname(f), { recursive: true }); const t = f + '.' + process.pid + '.tmp'; fs.writeFileSync(t, JSON.stringify(st, null, 1)); fs.renameSync(t, f); }
const id = p => p + '-' + crypto.randomBytes(4).toString('hex');
const actor = b => str(b.by, 40) || '未署名';

// 回读 E 线保存区核实协同任务（同一工作区、同一会话）
function verifyETask(ws, session, taskId) {
  const f = path.join(ROOT, 'private', 'e-quality-collab', ws, session + '.json');
  if (!fs.existsSync(f)) return null;
  try { const st = JSON.parse(fs.readFileSync(f, 'utf8')); const t = (st.tasks || []).find(x => x.id === taskId); return t ? { id: t.id, no: t.no, title: t.title, status: t.status, assignee: t.assignee, ref: (t.origin && t.origin.ref) || '', doneAt: t.doneAt || '' } : null; } catch { return null; }
}

function cleanPreview(p) {
  p = p || {};
  const rows = (Array.isArray(p.rows) ? p.rows : []).slice(0, 40).map(r => ({ label: str(r.label, 40), value: str(String(r.value ?? ''), 400) })).filter(r => r.label);
  const list = k => (Array.isArray(p[k]) ? p[k] : []).slice(0, 20).map(x => str(String(x), 400)).filter(Boolean);
  return { rows, impact: list('impact'), basis: list('basis'), notDone: list('notDone') };
}
function cleanAction(a) {
  a = a || {};
  if (a.type === 'e-task') return { type: 'e-task', payload: a.payload && typeof a.payload === 'object' ? JSON.parse(JSON.stringify(a.payload)) : {} };
  if (a.type === 'crew-onboard') return { type: 'crew-onboard', payload: a.payload && typeof a.payload === 'object' ? JSON.parse(JSON.stringify(a.payload)) : {} };
  return { type: 'demo', reason: str(a.reason, 300) || '原型演示：没有接现有接口' };
}

function createInbox(st, b) {
  if (!CREW.includes(b.employee)) throw bad('未知的数字员工');
  if (!st.config.enabled[b.employee]) throw bad('这位数字员工在本厂没有启用');
  const sourceKey = str(b.sourceKey, 120);
  // 幂等（防呆 Rule 4）：同一来源已有未处理的确认事项，不再新建
  if (sourceKey) { const dup = st.inbox.find(x => x.sourceKey === sourceKey && ['pending', 'executing'].includes(x.status)); if (dup) { const e = bad('已有同一事项在等你确认（' + dup.title + '）'); e.status = 409; e.existing = dup.id; throw e; } }
  const x = { id: id('IB'), employee: b.employee, kind: str(b.kind, 30) || 'other', title: str(b.title, 80) || '未命名事项', module: str(b.module, 30) || 'production', sourceKey, context: str(b.context, 200), preview: cleanPreview(b.preview), action: cleanAction(b.action), status: 'pending', createdAt: now(), createdBy: actor(b), log: [{ at: now(), by: actor(b), action: '生成预览' }] };
  st.inbox.push(x); return x;
}
function confirmInbox(st, b) {
  const x = st.inbox.find(y => y.id === b.id); if (!x) throw bad('事项不存在', 404);
  if (x.status !== 'pending') throw bad('这件事已经' + ({ executing: '在办理中', done: '处理完', cancelled: '取消' }[x.status] || '处理') + '，不能重复确认');
  if (b.perm !== 'rw') throw bad('你没有「' + x.module + '」模块的办理权限，不能确认这件事', 403);
  x.status = 'executing'; x.confirmedAt = now(); x.confirmedBy = actor(b); x.log.push({ at: x.confirmedAt, by: x.confirmedBy, action: '确认，开始办理' });
  // 入职：班组配置是本线自己的保存区，确认即在这里执行并回读
  if (x.action.type === 'crew-onboard') {
    const p = x.action.payload || {}, before = { ...st.config.enabled };
    for (const c of CREW) if (Array.isArray(p.enable) && p.enable.includes(c)) st.config.enabled[c] = true;
    const ob = { id: id('OB'), at: now(), by: x.confirmedBy, productCode: str(p.productCode, 40), productName: str(p.productName, 80), steps: (p.steps || []).slice(0, 30), rules: (p.rules || []).slice(0, 20), crewReady: p.crewReady || {}, state: '已上岗' };
    st.onboard.push(ob); st.config.log.push({ at: ob.at, by: ob.by, action: '入职确认', before, after: { ...st.config.enabled }, ref: ob.id });
    finish(st, x, { outcome: 'done', ref: { type: 'onboard', id: ob.id, no: ob.id, label: ob.productName + ' 入职记录' }, note: '班组配置已保存并回读；工序草稿未写入工艺（工艺在「工序配置」页由人保存）', quota: 0 });
  }
  return x;
}
function finish(st, x, r) {
  x.status = r.outcome === 'demo' ? 'done' : ({ done: 'done', handed: 'handed', failed: 'failed' }[r.outcome] || 'unknown');
  x.result = { outcome: r.outcome, label: OUTCOME[r.outcome], ref: r.ref || null, note: str(r.note, 400), at: now() };
  x.log.push({ at: x.result.at, by: '系统', action: OUTCOME[r.outcome] + (r.ref ? ' · ' + (r.ref.no || r.ref.id) : '') });
  const quota = r.quota != null ? r.quota : (r.outcome === 'done' ? 1 : 0); // 只有已办成扣额度
  st.records.push({ id: id('WR'), inboxId: x.id, employee: x.employee, kind: x.kind, title: x.title, at: x.result.at, confirmedBy: x.confirmedBy || '', outcome: r.outcome, label: OUTCOME[r.outcome], quota, ref: r.ref || null, note: x.result.note });
}
function resultInbox(st, b) {
  const x = st.inbox.find(y => y.id === b.id); if (!x) throw bad('事项不存在', 404);
  if (x.status !== 'executing') throw bad('只有办理中的事项才能登记结果');
  let outcome = ['done', 'failed', 'unknown', 'demo'].includes(b.outcome) ? b.outcome : 'unknown';
  let ref = null, note = str(b.message, 400);
  if (outcome === 'done') {
    if (x.action.type !== 'e-task') throw bad('只有接了现有接口的事项才能登记已办成');
    const t = verifyETask(st.ws, st.session, str(b.taskId, 60));
    if (t && t.ref === 'F:' + x.id) {
      ref = { type: 'task', id: t.id, no: t.no, label: '协同任务 ' + t.no + ' · ' + t.title + ' · 负责 ' + (t.assignee && t.assignee.role || '') };
      outcome = taskOutcome(t.status); note = outcome === 'handed' ? '已交给' + (t.assignee && t.assignee.role || '负责人') + '，任务完成后才算已办成' : '';
    } else if (t) { outcome = 'unknown'; note = '回读到的任务 ' + t.no + ' 不是本事项建的（凭证不匹配），按结果不明记录，不扣额度'; }
    else { outcome = 'unknown'; note = '协同任务接口返回成功，但回读 E 保存区没找到任务 ' + str(b.taskId, 60) + '，按结果不明记录，不扣额度'; }
  }
  if (outcome === 'demo' && x.action.type !== 'demo') throw bad('这件事接了现有接口，不能记成原型演示');
  finish(st, x, { outcome, ref, note: note || (outcome === 'demo' ? x.action.reason : '') });
  return x;
}
const taskOutcome = s => s === 'DONE' ? 'done' : s === 'CANCELLED' ? 'failed' : 'handed';
// 回读 E 保存区，把「已交办」的记录按任务实际状态更新（R29）；旧记录（11-12 按「已办成」记的）也按任务状态重算
function syncRecords(st) {
  let changed = 0;
  for (const r of st.records) {
    if (!r.ref || r.ref.type !== 'task' || !['handed', 'done', 'failed'].includes(r.outcome)) continue;
    const t = verifyETask(st.ws, st.session, r.ref.id); if (!t) continue;
    const to = taskOutcome(t.status); if (to === r.outcome) continue;
    const before = r.label; r.outcome = to; r.label = OUTCOME[to]; r.quota = to === 'done' ? 1 : 0;
    r.note = to === 'done' ? '任务 ' + t.no + ' 已完成，回读核实' : to === 'failed' ? '任务 ' + t.no + ' 已取消' : '任务 ' + t.no + ' 还没完成，按已交办记，不扣额度';
    (r.history = r.history || []).push({ at: now(), from: before, to: r.label, by: '回读协同任务' }); if (to === 'done') r.doneAt = t.doneAt || now();
    const x = st.inbox.find(y => y.id === r.inboxId); if (x) { x.status = { done: 'done', handed: 'handed', failed: 'failed' }[to]; if (x.result) { x.result.outcome = to; x.result.label = OUTCOME[to]; x.result.note = r.note; } x.log.push({ at: now(), by: '系统', action: OUTCOME[to] + ' · ' + t.no }); }
    changed++;
  }
  return { id: 'sync', changed };
}
function cancelInbox(st, b) {
  const x = st.inbox.find(y => y.id === b.id); if (!x) throw bad('事项不存在', 404);
  if (x.status !== 'pending') throw bad('只有等确认的事项可以不办');
  const reason = str(b.reason, 200); if (reason.length < 2) throw bad('请写不办的原因');
  x.status = 'cancelled'; x.log.push({ at: now(), by: actor(b), action: '不办', reason }); return x;
}
function saveConfig(st, b) {
  const before = { ...st.config.enabled }, en = b.enabled || {};
  for (const c of CREW) if (typeof en[c] === 'boolean') st.config.enabled[c] = en[c];
  if (!Object.values(st.config.enabled).some(Boolean)) throw bad('至少启用一位数字员工');
  const changed = CREW.filter(c => before[c] !== st.config.enabled[c]); if (!changed.length) throw bad('没有修改');
  const reason = str(b.reason, 200); if (reason.length < 2) throw bad('请写调整原因');
  st.config.log.push({ at: now(), by: actor(b), action: '启用调整', before, after: { ...st.config.enabled }, reason });
  return { id: 'config' };
}
function reportState(st, b) {
  const key = str(b.key, 160); if (!key) throw bad('缺少汇报编号');
  const to = ['dismissed', 'converted', 'open'].includes(b.status) ? b.status : null; if (!to) throw bad('状态不对');
  const reason = str(b.reason, 200), conclusion = CONCLUSIONS.includes(b.conclusion) ? b.conclusion : '';
  if (to === 'dismissed' && (!conclusion || reason.length < 2)) throw bad('关闭要选结论（已处理 / 误报 / 接受偏差）并写一句说明');
  st.reports[key] = { status: to, at: now(), by: actor(b), reason, conclusion, inboxId: str(b.inboxId, 40) };
  return { id: key };
}

async function handle({ req, res, u, p, send, readBody, root }) {
  if (root) ROOT = root;
  const sub = p.replace(/^\/__unified\/f\/api\//, '');
  const json = (s, d) => send(res, s, d);
  try {
    const session = sessionOf(u);
    if (req.method === 'GET') {
      if (sub === 'state') { const ws = wsOf(u); const st = read(ws, session); return json(200, { success: true, data: { ...st, outcomes: OUTCOME, writable: session !== 'default' } }); }
      return json(404, { success: false, message: '未找到' });
    }
    if (req.method !== 'POST') return json(405, { success: false, message: '不支持的方法' });
    if (!req.headers.origin && req.headers['x-prototype-action'] !== 'explicit') return json(403, { success: false, message: '缺少同源请求头' });
    if (session === 'default') return json(400, { success: false, message: '数字班组的演示写入只进验收会话：请在地址上带 ?acceptanceSession=test-f-20261112' });
    let b; try { b = JSON.parse((await readBody(req, 200000)).toString('utf8') || '{}'); } catch { return json(400, { success: false, message: '请求格式不正确' }); }
    const ws = wsOf(u, b), st = read(ws, session);
    const expected = Number(b.expectedVersion);
    if (!Number.isInteger(expected) || expected !== st.version) return json(409, { success: false, message: '数据已被更新，请重新读取后再操作', version: st.version });
    let out;
    if (sub === 'inbox/create') out = createInbox(st, b);
    else if (sub === 'inbox/confirm') out = confirmInbox(st, b);
    else if (sub === 'inbox/result') out = resultInbox(st, b);
    else if (sub === 'inbox/cancel') out = cancelInbox(st, b);
    else if (sub === 'config/save') out = saveConfig(st, b);
    else if (sub === 'reports/state') out = reportState(st, b);
    else if (sub === 'records/sync') { out = syncRecords(st); if (!out.changed) return json(200, { success: true, data: { id: 'sync', version: st.version, changed: 0 } }); }
    else return json(404, { success: false, message: '未找到' });
    st.version += 1; st.updatedAt = now(); write(st);
    return json(200, { success: true, data: { id: out.id, version: st.version, item: out.status ? out : undefined, changed: out.changed } });
  } catch (e) {
    return json(e.status || 500, { success: false, message: String(e.message || e), existing: e.existing });
  }
}
module.exports = { handle, OUTCOME };
