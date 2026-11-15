'use strict';
// E 线解读：本机解读服务（与六膳门同一转发路径）；不可用时规则结果照常输出。
// 严格模式（检验解读）：模型只选核对代码；创建类（任务草稿）：枚举字段 + 数字绑定；解释模式（任务进展）：逐句数字绑定 + 矛盾检查。
const { spawn } = require('child_process'), crypto = require('crypto');
const rules = require('../quality/qc-rules.cjs');
const MODEL = 'qwen3:8b', HOSTS = ['canada-mini', 'canada-mini-backup'];
let chain = Promise.resolve(), queued = 0, busy = false;
const cache = new Map(), inflight = new Map();
function status() { return { queued, busy }; }

function transportOnce(payload, host) {
  return new Promise((resolve, reject) => {
    const child = spawn('ssh', ['-T', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=6', host, "curl --fail-with-body -sS --max-time 100 -H 'Content-Type: application/json' --data-binary @- http://127.0.0.1:11434/api/chat"], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    let out = '', done = false;
    const timer = setTimeout(() => finish(Object.assign(Error('解读服务超时'), { code: 'TIMEOUT' })), 105000);
    function finish(e, v) { if (done) return; done = true; clearTimeout(timer); if (child.exitCode === null) child.kill(); e ? reject(e) : resolve(v); }
    child.on('error', () => finish(Object.assign(Error('解读连接暂不可用'), { code: 'CONNECT' })));
    child.stdout.on('data', b => { out += b; if (out.length > 2e6) finish(Error('解读响应过大')); });
    child.on('close', code => {
      if (done) return;
      if (code === 255) return finish(Object.assign(Error('解读连接暂不可用'), { code: 'CONNECT' }));
      if (code !== 0) return finish(Error('解读服务失败'));
      try { const v = JSON.parse(out); if (v.model !== MODEL || typeof v.message?.content !== 'string') throw Error(); finish(null, v); } catch { finish(Error('解读结果未通过格式校验')); }
    });
    child.stdin.end(JSON.stringify(payload));
  });
}
async function transport(payload) {
  let last;
  for (const h of HOSTS) { try { return await transportOnce(payload, h); } catch (e) { last = e; if (e.code !== 'CONNECT') break; } }
  throw last;
}
// 排队：不设上限，按缓存键去重；同一键在途时合并
function run(key, payload) {
  if (cache.has(key)) return Promise.resolve({ ...cache.get(key), cached: true });
  if (inflight.has(key)) return inflight.get(key);
  queued++;
  const p = (chain = chain.catch(() => {}).then(async () => { queued--; busy = true; const t0 = Date.now(); try { const v = await transport(payload); const r = { content: JSON.parse(v.message.content), ms: Date.now() - t0, usage: { prompt: v.prompt_eval_count, completion: v.eval_count } }; cache.set(key, r); return r; } finally { busy = false; inflight.delete(key); } }));
  inflight.set(key, p);
  return p;
}
const sha = v => crypto.createHash('sha256').update(JSON.stringify(v)).digest('hex').slice(0, 24);
const msgs = (sys, user) => [{ role: 'system', content: sys }, { role: 'user', content: user }];
const unavailable = e => ({ model: 'unavailable', modelNote: '解读服务暂不可用（' + (e && e.message || '未知') + '），以下为规则结果。' });

// 1) 解读这张检验单（严格模式）
async function inspection(f, question) {
  const base = { mode: 'strict', ruleVersion: 'qc-rules-v1', at: new Date().toISOString() };
  if (!f.allowed.length) return { ...base, model: 'skipped', codes: [], ...rules.renderInspection(f, []), modelNote: '当前事实没有可选核对项。' };
  const payload = { model: MODEL, messages: msgs('只读核对质量检验单，不生成任何数字，不改判定，不执行操作。', '仅返回 JSON {"reviewCodes":[]}，最多选 3 个允许的核对代码：' + JSON.stringify(f.allowed.map(k => ({ code: k, text: rules.CODES[k].text }))) + '。已核实事实：' + JSON.stringify({ 阶段: f.stageLabel, 对象: f.object, 标准: f.standard, 判定: f.verdictLabel, 不合格: f.fails, 缺项: f.missing, 处置: f.disposal }) + (question ? '。用户关注：' + question : '')), format: { type: 'object', properties: { reviewCodes: { type: 'array', items: { type: 'string', enum: f.allowed }, uniqueItems: true, maxItems: 3 } }, required: ['reviewCodes'], additionalProperties: false }, stream: false, think: false, options: { temperature: 0.1, num_predict: 200 } };
  try {
    const r = await run(sha(['insp', f, question, base.ruleVersion]), payload);
    const codes = r.content && Array.isArray(r.content.reviewCodes) ? r.content.reviewCodes : null;
    if (!codes || !codes.length || codes.some(c => !f.allowed.includes(c)) || new Set(codes).size !== codes.length || codes.length > 3) return { ...base, model: 'rejected', modelNote: '解读结果含未允许的核对项，已整次拒绝，以下为规则结果。', codes: f.defaultCodes, ...rules.renderInspection(f, f.defaultCodes) };
    return { ...base, model: 'ok', cached: !!r.cached, ms: r.ms, codes, ...rules.renderInspection(f, codes) };
  } catch (e) { return { ...base, ...unavailable(e), codes: f.defaultCodes, ...rules.renderInspection(f, f.defaultCodes) }; }
}

// 数字绑定：文本里的每个数字必须在依据文本中出现
const numbersIn = s => (String(s).match(/\d+(?:\.\d+)?/g) || []);
function bound(text, evidence) { const ev = String(evidence); return numbersIn(text).every(n => ev.includes(n) || ev.includes(String(Number(n)))); }

// 2) 整理成任务草稿（创建类）
const DUE = { today: 0, tomorrow: 1, d3: 3, d7: 7 };
const DUE_LABEL = { today: '今天', tomorrow: '明天', d3: '3 天内', d7: '7 天内' };
function addDays(today, n) { const d = new Date(today + 'T00:00:00Z'); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); }
function ruleDraft({ text, insp, candidates, today }) {
  const first = String(text).split(/[。！？!?\n]/).map(s => s.trim()).filter(Boolean)[0] || String(text).slice(0, 30);
  const role = insp ? ({ incoming: '采购', warehousing: '采购', production: '生产主管', shipping: '仓库', other: '质量经理' })[insp.stage] : '质量经理';
  const fail = insp && insp.verdict === 'FAIL';
  const dueOption = fail ? 'tomorrow' : 'd3';
  // 任务类型：来自检验单 → 质量异常；首检相关 → 首件确认；否则其他（BL-borrow 第 19 条）
  const type = insp ? (insp.kind === 'first' && insp.verdict !== 'FAIL' ? 'first' : 'quality') : 'other';
  return { type, fields: insp ? { failItems: (insp.fails || []).map(x => x.name).join('、') } : {}, title: ('处理：' + first).slice(0, 40), description: String(text).slice(0, 2000), role, dueOption, due: addDays(today, DUE[dueOption]), priority: fail ? '高' : '中', relatedIds: candidates.map(c => c.code), fieldSource: { title: '系统', description: '系统', role: '系统', due: '系统', priority: '系统', related: '系统' } };
}
async function taskDraft(input) {
  const { text, insp, candidates, roles, priority, today } = input;
  const evidence = text + JSON.stringify(insp || {}) + JSON.stringify(candidates);
  const rule = ruleDraft(input);
  const base = { at: new Date().toISOString(), dueLabels: DUE_LABEL, rule };
  const codes = candidates.map(c => c.code);
  const payload = { model: MODEL, messages: msgs('把异常说明整理成一条待办任务草稿。只能使用给出的枚举值；不要编造任何数字、人名或单号；标题不超过 30 字，说明写清要核对什么。', '异常说明：' + text + (insp ? '\n检验事实：' + JSON.stringify({ 阶段: insp.stageLabel, 单号: insp.no, 对象: insp.object, 判定: insp.verdictLabel, 不合格: insp.fails, 缺项: insp.missing }) : '') + '\n可关联单号：' + JSON.stringify(codes)), format: { type: 'object', properties: { title: { type: 'string', maxLength: 40 }, description: { type: 'string', maxLength: 400 }, role: { type: 'string', enum: roles }, dueOption: { type: 'string', enum: Object.keys(DUE) }, priority: { type: 'string', enum: priority }, relatedIds: { type: 'array', items: { type: 'string', enum: codes.length ? codes : ['无'] }, uniqueItems: true } }, required: ['title', 'description', 'role', 'dueOption', 'priority', 'relatedIds'], additionalProperties: false }, stream: false, think: false, options: { temperature: 0.2, num_predict: 500 } };
  try {
    const r = await run(sha(['draft', text, insp && insp.no, insp && insp.rev, codes]), payload), c = r.content || {};
    const d = { ...rule, fieldSource: { ...rule.fieldSource } }, rejected = [];
    if (typeof c.title === 'string' && c.title.trim() && c.title.length <= 40 && bound(c.title, evidence)) { d.title = c.title.trim(); d.fieldSource.title = '解读建议'; } else rejected.push('标题');
    if (typeof c.description === 'string' && c.description.trim() && bound(c.description, evidence)) { d.description = c.description.trim() + '\n\n原始说明：' + text; d.fieldSource.description = '解读建议'; } else rejected.push('说明');
    if (roles.includes(c.role)) { d.role = c.role; d.fieldSource.role = '解读建议'; } else rejected.push('负责岗位');
    if (DUE[c.dueOption] != null) { d.dueOption = c.dueOption; d.due = addDays(today, DUE[c.dueOption]); d.fieldSource.due = '解读建议'; } else rejected.push('截止');
    if (priority.includes(c.priority)) { d.priority = c.priority; d.fieldSource.priority = '解读建议'; } else rejected.push('优先级');
    if (Array.isArray(c.relatedIds) && c.relatedIds.every(x => codes.includes(x))) { d.relatedIds = Array.from(new Set([...(insp ? [insp.no] : []), ...c.relatedIds])); d.fieldSource.related = '解读建议'; } else rejected.push('关联单据');
    return { ...base, model: 'ok', cached: !!r.cached, ms: r.ms, draft: d, rejected, modelNote: rejected.length ? '以下字段未通过校验，已改用系统规则值：' + rejected.join('、') : '' };
  } catch (e) { return { ...base, ...unavailable(e), draft: rule, rejected: [] }; }
}

// 3) 概括任务进展（解释模式）
function ruleSummary(f) { return f.status + ' · 截止 ' + f.due + (f.overdueDays ? ' · 超期 ' + f.overdueDays + ' 天' : '') + ' · 负责 ' + f.assignee + ' · 评论 ' + f.commentCount + ' 条' + (f.lastComment ? ' · 最近：' + f.lastComment : ''); }
async function taskSummary(f) {
  const base = { mode: 'explain', at: new Date().toISOString(), ruleSummary: ruleSummary(f) };
  if (!f.commentCount) return { ...base, model: 'skipped', sentences: [], modelNote: '还没有进展记录，无法概括；以下只有系统摘要。' };
  const evidence = JSON.stringify(f);
  const payload = { model: MODEL, messages: msgs('概括任务进展。只用给出的事实，不编数字；不要声称状态与事实不同。', '返回 JSON {"sentences":[2到4句]}：进展、卡点、建议下一步找谁。事实：' + evidence), format: { type: 'object', properties: { sentences: { type: 'array', items: { type: 'string', maxLength: 120 }, minItems: 1, maxItems: 4 } }, required: ['sentences'], additionalProperties: false }, stream: false, think: false, options: { temperature: 0.2, num_predict: 400 } };
  try {
    const r = await run(sha(['sum', f]), payload), arr = Array.isArray(r.content && r.content.sentences) ? r.content.sentences.slice(0, 4) : [];
    const sentences = arr.map(s => {
      const text = String(s).trim();
      if (!bound(text, evidence)) return { text, ok: false, why: '含依据中没有的数字' };
      if (f.statusCode !== 'DONE' && /(已经?完成|已办结|已关闭)/.test(text) && !/未完成/.test(text)) return { text, ok: false, why: '与任务状态矛盾' };
      if (f.statusCode === 'DONE' && /(尚未|还未|未)(开始|完成)/.test(text)) return { text, ok: false, why: '与任务状态矛盾' };
      return { text, ok: true };
    });
    return { ...base, model: 'ok', cached: !!r.cached, ms: r.ms, sentences };
  } catch (e) { return { ...base, ...unavailable(e), sentences: [] }; }
}
// 规则结果（不调用模型）：AiFloat 扩展接入前，页面只显示这些
function inspectionRules(f) { return { mode: 'strict', ruleVersion: 'qc-rules-v1', at: new Date().toISOString(), model: 'rules', codes: f.defaultCodes, ...rules.renderInspection(f, f.defaultCodes) }; }
function summaryRules(f) { return { mode: 'explain', at: new Date().toISOString(), model: 'rules', ruleSummary: ruleSummary(f), sentences: [] }; }
function draftRules(input) { return { at: new Date().toISOString(), model: 'rules', dueLabels: DUE_LABEL, draft: ruleDraft(input), rejected: [] }; }
module.exports = { inspection, taskDraft, taskSummary, inspectionRules, summaryRules, draftRules, status, _bound: bound };
