'use strict';
// D 线第四批②：异常预警「预警归因」（解释模式）。
// 对象 = 一条规则提醒（E 的规则引擎生成，存在 private/e-quality-collab/<ws>/<session>.json，本文件只读）。
// 事实由代码从提醒和关联单据算出；模型只写「可能原因、核对方向」2–4 段，不生成新数字。
// 页面拿到段落后用 CretasUI.ai.checkParagraphs 逐段核对数字（数字必须在事实表里），不通过的段落折叠为「未核实内容」。
// 只在用户点击时请求；模型不可用时返回统一降级文案和事实表。不写任何业务数据。
const fs = require('fs'), path = require('path'), crypto = require('crypto'), { spawn } = require('child_process');
const ROOT = path.resolve(__dirname, '..', '..');
const MODEL = 'qwen3:8b', HOSTS = ['canada-mini', 'canada-mini-backup'];
const UNAVAILABLE = '解读服务暂不可用：下面是系统按规则算出的结果，可以直接使用；稍后可重试解读。';
const SESSION_RE = /^test-[a-z0-9-]{8,64}$/;
let chain = Promise.resolve(), queued = 0;
const cache = new Map(), inflight = new Map();

function transportOnce(payload, host) {
  return new Promise((resolve, reject) => {
    const child = spawn('ssh', ['-T', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=6', host, "curl --fail-with-body -sS --max-time 100 -H 'Content-Type: application/json' --data-binary @- http://127.0.0.1:11434/api/chat"], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    let out = '', done = false;
    const timer = setTimeout(() => finish(Object.assign(Error('timeout'), { code: 'TIMEOUT' })), 105000);
    function finish(e, v) { if (done) return; done = true; clearTimeout(timer); if (child.exitCode === null) child.kill(); e ? reject(e) : resolve(v); }
    child.on('error', () => finish(Object.assign(Error('connect'), { code: 'CONNECT' })));
    child.stdout.on('data', b => { out += b; if (out.length > 2e6) finish(Error('too large')); });
    child.on('close', code => {
      if (done) return;
      if (code === 255) return finish(Object.assign(Error('connect'), { code: 'CONNECT' }));
      if (code !== 0) return finish(Error('failed'));
      try { const v = JSON.parse(out); if (v.model !== MODEL || typeof v.message?.content !== 'string') throw Error(); finish(null, v); } catch { finish(Error('format')); }
    });
    child.stdin.end(JSON.stringify(payload));
  });
}
async function transport(payload) { let last; for (const h of HOSTS) { try { return await transportOnce(payload, h); } catch (e) { last = e; if (e.code !== 'CONNECT') break; } } throw last; }
function run(key, payload) {
  if (cache.has(key)) return Promise.resolve({ ...cache.get(key), cached: true });
  if (inflight.has(key)) return inflight.get(key);
  queued++;
  const p = (chain = chain.catch(() => {}).then(async () => { queued--; const t0 = Date.now(); try { const v = await transport(payload); const r = { content: JSON.parse(v.message.content), ms: Date.now() - t0 }; cache.set(key, r); return r; } finally { inflight.delete(key); } }));
  inflight.set(key, p);
  return p;
}

function readState(ws, session) {
  const f = path.join(ROOT, 'private', 'e-quality-collab', ws, (session || 'default') + '.json');
  try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return null; }
}
const fmt2 = v => (v === null || v === undefined || v === '' || !Number.isFinite(Number(v))) ? null : Number(v).toFixed(2);
// 事实表：label/value/unit；只放代码能确定的值，缺失写「未记录」
function facts(st, rm) {
  const F = [{ label: '提醒', value: rm.title }, { label: '规则', value: { INSPECTION_FAIL: '检验不合格未建任务', TASK_OVERDUE: '任务超期', FIRST_CHECK_PENDING: '首检未做' }[rm.rule] || rm.rule }, { label: '提醒对象', value: (rm.ref && rm.ref.code) || '未记录' }, { label: '找谁', value: rm.role || '未记录' }, { label: '提醒时间', value: new Date(rm.at).toLocaleString('zh-CN', { hour12: false, timeZone: 'Asia/Shanghai' }) + '（北京时间）' }];
  if (rm.ref && rm.ref.type === 'inspection') {
    const q = (st.inspections || []).find(x => x.id === rm.ref.id);
    if (!q) F.push({ label: '关联检验单', value: '已不在当前会话（无法读取）' });
    else {
      const std = (st.standards || []).find(s => s.id === q.standardId), ver = std && (std.versions || []).find(v => v.v === q.standardVersion);
      const STAGE = { incoming: '来料', process: '生产', inbound: '入库', outbound: '发货', other: '其他' };
      F.push({ label: '检验单', value: q.no + '（' + ({ DRAFT: '草稿', SUBMITTED: '已提交', VOID: '已作废' }[q.status] || q.status) + '）' }, { label: '检验阶段', value: STAGE[q.stage] || q.stage || '未记录' }, { label: '检验对象', value: [q.objectRef && q.objectRef.code, q.objectRef && q.objectRef.name].filter(Boolean).join(' ') || '未记录' }, { label: '检验标准', value: ver ? ver.name + ' v' + ver.v : '未记录' }, { label: '总判定（代码算）', value: q.verdict === 'FAIL' ? '不合格' : q.verdict === 'PASS' ? '合格' : (q.verdict || '未判定') });
      const fails = new Set(q.failItems || []);
      for (const r of (q.results || [])) {
        const it = ((ver && ver.items) || []).find(i => i.id === r.itemId) || {}, unit = it.unit || '';
        F.push({ label: '检验项 ' + (it.name || r.itemId) + ' 实测', value: fmt2(r.value) ?? (r.option || r.judge || '未录入'), unit, status: fails.has(it.name) ? '不合格' : '' });
        if (fmt2(it.lower) !== null) F.push({ label: (it.name || '') + ' 下限', value: fmt2(it.lower), unit });
        if (fmt2(it.upper) !== null) F.push({ label: (it.name || '') + ' 上限', value: fmt2(it.upper), unit });
      }
      if (fails.size) F.push({ label: '不合格项', value: [...fails].join('、') });
      if ((q.missingItems || []).length) F.push({ label: '缺项', value: q.missingItems.join('、') });
      const tasks = (st.tasks || []).filter(t => t.status !== 'CANCELLED' && (t.related || []).some(r => r.type === 'inspection' && r.id === q.id));
      F.push({ label: '已关联处理任务', value: tasks.length ? tasks.map(t => t.no).join('、') : '没有' });
      F.push({ label: '处置', value: q.disposal || '未填写' });
    }
  }
  if (rm.ref && rm.ref.type === 'task') {
    const t = (st.tasks || []).find(x => x.id === rm.ref.id);
    if (t) F.push({ label: '任务', value: t.no + ' ' + t.title }, { label: '截止日', value: t.due || '未记录' }, { label: '状态', value: t.status }, { label: '负责岗位', value: (t.assignee && t.assignee.role) || '未记录' });
  }
  return F;
}

async function alertExplain(body) {
  const ws = body.ws === 'lsm' ? 'lsm' : 'ydwm', session = String(body.session || '');
  if (session && !SESSION_RE.test(session)) return { status: 400, body: { success: false, message: '验收会话名不合规' } };
  const st = readState(ws, session);
  if (!st) return { status: 404, body: { success: false, message: '当前会话没有规则提醒数据' } };
  const rm = (st.reminders || []).find(r => r.id === body.id);
  if (!rm) return { status: 404, body: { success: false, message: '提醒不存在或已被处理' } };
  const F = facts(st, rm), at = new Date().toISOString();
  const base = { mode: 'explain', object: { kind: 'rule-reminder', id: rm.id, label: rm.title }, facts: F, at };
  const evidence = F.map(f => f.label + '：' + f.value + (f.unit ? ' ' + f.unit : '') + (f.status ? '（' + f.status + '）' : '')).join('\n');
  const payload = { model: MODEL, messages: [{ role: 'system', content: '你在做预警归因：只根据给出的事实，说明这条提醒可能的原因和应该核对的方向。不要编造任何数字、人名、单号；需要提到数字时只能原样使用事实里的数字。不要替人做决定，不说已处理。' }, { role: 'user', content: '返回 JSON {"paragraphs":[2到4段，每段不超过 120 字]}：第一段说触发原因，其余说可能原因与核对方向（找谁、看哪张单）。事实：\n' + evidence + (body.question ? '\n用户关注：' + String(body.question).slice(0, 200) : '') }], format: { type: 'object', properties: { paragraphs: { type: 'array', items: { type: 'string', maxLength: 160 }, minItems: 1, maxItems: 4 } }, required: ['paragraphs'], additionalProperties: false }, stream: false, think: false, options: { temperature: 0.2, num_predict: 500 } };
  const key = crypto.createHash('sha256').update(JSON.stringify([F, body.question || ''])).digest('hex').slice(0, 24);
  try {
    const r = await run(key, payload);
    const paras = Array.isArray(r.content && r.content.paragraphs) ? r.content.paragraphs.map(s => String(s).trim()).filter(Boolean).slice(0, 4) : [];
    if (!paras.length) return { status: 200, body: { success: true, data: { ...base, state: 'rejected', paragraphs: [], note: '解读结果格式不符，已整次拒绝；下面是系统事实。' } } };
    return { status: 200, body: { success: true, data: { ...base, state: 'answered', paragraphs: paras, cached: !!r.cached, ms: r.ms } } };
  } catch (e) {
    return { status: 200, body: { success: true, data: { ...base, state: 'unavailable', paragraphs: [], note: UNAVAILABLE } } };
  }
}
// 第五批②：解读审批依据（解释模式）。事实由页面用当前账号已有的读取接口取来（请购单本身 + 同物料历史请购），
// 服务端只做格式与大小校验后交给模型；模型只写「这张单要审什么、依据是否齐、该核对什么」，不替人审批、不生成新数字。
// 数字核对在页面上做（与预警归因同一套：数字绑定 + 单号遮挡 + 比较方向）。
function cleanFacts(list) {
  if (!Array.isArray(list) || !list.length || list.length > 40) return null;
  const out = [];
  for (const f of list) { if (!f || typeof f.label !== 'string') return null; out.push({ label: f.label.slice(0, 40), value: String(f.value ?? '未记录').slice(0, 200), ...(f.unit ? { unit: String(f.unit).slice(0, 12) } : {}) }); }
  return out;
}
async function approvalExplain(body) {
  const F = cleanFacts(body.facts);
  if (!F) return { status: 400, body: { success: false, message: '审批事实格式不对' } };
  const at = new Date().toISOString(), label = String(body.label || '待审批单据').slice(0, 80);
  const base = { mode: 'explain', object: { kind: 'approval', label }, facts: F, at };
  const evidence = F.map(f => f.label + '：' + f.value + (f.unit ? ' ' + f.unit : '')).join('\n');
  const payload = { model: MODEL, messages: [{ role: 'system', content: '你在帮审批人看一张待审批单据的依据：只根据给出的事实，说明这张单申请了什么、理由是否写清、和历史同类单相比有什么不同、审批前该核对什么。不要编造任何数字、人名、单号；提到数字时只能原样使用事实里的数字。不要替人决定通过或驳回。' }, { role: 'user', content: '返回 JSON {"paragraphs":[2到4段，每段不超过 120 字]}。事实：\n' + evidence }], format: { type: 'object', properties: { paragraphs: { type: 'array', items: { type: 'string', maxLength: 160 }, minItems: 1, maxItems: 4 } }, required: ['paragraphs'], additionalProperties: false }, stream: false, think: false, options: { temperature: 0.2, num_predict: 500 } };
  const key = 'ap:' + crypto.createHash('sha256').update(JSON.stringify(F)).digest('hex').slice(0, 24);
  try {
    const r = await run(key, payload);
    const paras = Array.isArray(r.content && r.content.paragraphs) ? r.content.paragraphs.map(s => String(s).trim()).filter(Boolean).slice(0, 4) : [];
    if (!paras.length) return { status: 200, body: { success: true, data: { ...base, state: 'rejected', paragraphs: [], note: '解读结果格式不符，已整次拒绝；下面是系统事实。' } } };
    return { status: 200, body: { success: true, data: { ...base, state: 'answered', paragraphs: paras, cached: !!r.cached, ms: r.ms } } };
  } catch (e) {
    return { status: 200, body: { success: true, data: { ...base, state: 'unavailable', paragraphs: [], note: UNAVAILABLE } } };
  }
}

// 第八批 R40：AI 经营报告。事实表由页面按规则算出（每个数字带来源），模型只在点击后写 2–4 段解读，不生成新数字。
async function reportExplain(body) {
  const F = cleanFacts(body.facts);
  if (!F) return { status: 400, body: { success: false, message: '报告事实格式不对' } };
  const at = new Date().toISOString(), label = String(body.label || '经营报告').slice(0, 80);
  const base = { mode: 'explain', object: { kind: 'ai-report', label }, facts: F, at };
  const evidence = F.map(f => f.label + '：' + f.value + (f.unit ? ' ' + f.unit : '')).join('\n');
  const payload = { model: MODEL, messages: [{ role: 'system', content: '你在给工厂经营报告写简短解读：只根据给出的事实表，说明本期有什么值得看的变化、哪些数字缺失或口径受限、下一步该核对什么。不要编造任何数字、人名、单号；提到数字时只能原样使用事实里的数字；不要给出事实表以外的结论，不要替人做决定。' }, { role: 'user', content: '返回 JSON {"paragraphs":[2到4段，每段不超过 120 字]}。事实：\n' + evidence }], format: { type: 'object', properties: { paragraphs: { type: 'array', items: { type: 'string', maxLength: 160 }, minItems: 1, maxItems: 4 } }, required: ['paragraphs'], additionalProperties: false }, stream: false, think: false, options: { temperature: 0.2, num_predict: 500 } };
  const key = 'rp:' + crypto.createHash('sha256').update(JSON.stringify(F)).digest('hex').slice(0, 24);
  try {
    const r = await run(key, payload);
    const paras = Array.isArray(r.content && r.content.paragraphs) ? r.content.paragraphs.map(s => String(s).trim()).filter(Boolean).slice(0, 4) : [];
    if (!paras.length) return { status: 200, body: { success: true, data: { ...base, state: 'rejected', paragraphs: [], note: '解读结果格式不符，已整次拒绝；下面是系统事实表。' } } };
    return { status: 200, body: { success: true, data: { ...base, state: 'answered', paragraphs: paras, cached: !!r.cached, ms: r.ms } } };
  } catch (e) {
    return { status: 200, body: { success: true, data: { ...base, state: 'unavailable', paragraphs: [], note: UNAVAILABLE } } };
  }
}
// 留档：事实表快照写统一入口 private/（d-ai-reports-<工作区>[.<验收会话>].json），不写任何业务数据
function archiveFile(ws, session) { return path.join(ROOT, 'private', 'd-ai-reports-' + (ws === 'ydwm' ? 'ydwm' : 'lsm') + (session ? '.' + session : '') + '.json'); }
function archiveRead(ws, session) { try { const v = JSON.parse(fs.readFileSync(archiveFile(ws, session), 'utf8')); if (v && Array.isArray(v.reports)) return v; } catch { /* 没有或损坏时从空开始 */ } return { version: 0, reports: [] }; }
async function archive(req, u, readBody) {
  const ws = u.searchParams.get('ws') === 'ydwm' ? 'ydwm' : 'lsm', session = String(req.headers['x-prototype-test-session'] || '');
  if (session && !SESSION_RE.test(session)) return { status: 400, body: { success: false, message: '验收会话名不合规' } };
  if (req.method === 'GET') return { status: 200, body: { success: true, data: archiveRead(ws, session) } };
  if (req.method !== 'POST') return { status: 405, body: { success: false, message: '只接受 GET 或 POST' } };
  if (req.headers['x-prototype-action'] !== 'explicit') return { status: 400, body: { success: false, message: '留档需要明确点击' } };
  let b = {}; try { b = JSON.parse((await readBody(req, 200000)).toString('utf8') || '{}'); } catch { return { status: 400, body: { success: false, message: '请求格式不对' } }; }
  const st = archiveRead(ws, session);
  if (Number.isInteger(b.expectedVersion) && b.expectedVersion !== st.version) return { status: 409, body: { success: false, message: '留档已被别处更新，请刷新后重试' } };
  if (b.op === 'delete') { st.reports = st.reports.filter(r => r.id !== b.id); }
  else {
    const kind = b.kind === 'month' ? 'month' : 'week', from = String(b.from || ''), to = String(b.to || '');
    if (!/^\d{4}-\d{2}-\d{2}$/.test(from) || !/^\d{4}-\d{2}-\d{2}$/.test(to) || !Array.isArray(b.rows) || !b.rows.length || b.rows.length > 80) return { status: 400, body: { success: false, message: '报告内容不完整' } };
    const rows = b.rows.map(r => ({ group: String(r.group || '').slice(0, 20), label: String(r.label || '').slice(0, 60), value: r.value == null ? null : String(r.value).slice(0, 80), prev: r.prev == null ? null : String(r.prev).slice(0, 80), source: String(r.source || '').slice(0, 120), count: Number(r.count) || 0, note: String(r.note || '').slice(0, 160) }));
    const rec = { id: 'RP-' + crypto.randomBytes(4).toString('hex'), kind, from, to, label: String(b.label || '').slice(0, 60), generatedAt: new Date().toISOString(), sourceRevision: String(b.sourceRevision || '').slice(0, 80), sourceLabel: String(b.sourceLabel || '').slice(0, 120), rows };
    st.reports = [rec, ...st.reports.filter(r => !(r.kind === kind && r.from === from))].slice(0, 120);
  }
  st.version += 1;
  const f = archiveFile(ws, session); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f + '.tmp', JSON.stringify(st)); fs.renameSync(f + '.tmp', f);
  return { status: 200, body: { success: true, data: st } };
}
async function handle({ req, res, u, send, readBody }) {
  if (u.pathname === '/__unified/d/report-archive') { const r = await archive(req, u, readBody); send(res, r.status, r.body); return; }
  const fn = { '/__unified/d/alert-explain': alertExplain, '/__unified/d/approval-explain': approvalExplain, '/__unified/d/report-explain': reportExplain }[u.pathname];
  if (!fn) { send(res, 404, { success: false, message: '未知接口' }); return; }
  if (req.method !== 'POST') { send(res, 405, { success: false, message: '只接受 POST' }); return; }
  if (req.headers['x-unified-explicit-ai'] !== '1') { send(res, 400, { success: false, message: '解读只在点击时请求' }); return; }
  let body = {}; try { body = JSON.parse((await readBody(req, 20000)).toString('utf8') || '{}'); } catch { send(res, 400, { success: false, message: '请求格式不对' }); return; }
  const r = await fn(body); send(res, r.status, r.body);
}
module.exports = { handle, alertExplain, approvalExplain, reportExplain, facts, status: () => ({ queued }) };
