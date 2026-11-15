'use strict';
// 首检状态（BL-borrow 第 17 条）：首检要求 = 「生产」阶段检验标准上的属性（用作首检 + 适用工序），来源 Cretas 补充；
// 工序任务、任务状态取自黑湖快照（只读）。状态由代码按检验单匹配算出；未通过首检只标红提醒，不阻断报工。
const TASK_STATE = { 0: '未开始', 10: '执行中', 20: '已结束' };
// 第六批可选项（DECISIONS 第十三节 E②）：首检挂到 A 的称重报工首笔上。weighins = A 会话里未撤销的称重记录（只读）；
// 每个工序任务取开始时间最早的一笔作「首笔称重」，并对照首检：首笔称重开始时还没有已提交的首检 → 标「先称重后首检」（只提醒，不阻断）
function rows(st, src, weighins) {
  const reqs = st.standards.filter(s => s.stage === 'production' && s.active).map(s => ({ s, v: s.versions[s.versions.length - 1] })).filter(x => x.v.firstCheck && x.v.firstCheck.processes && x.v.firstCheck.processes.length);
  if (!src) return { available: false, reason: '本工作区没有可读的工序任务（味美接口未提供工序任务），首检状态只在六膳门显示。', rows: [], requirements: reqs.length };
  const r = src.resources || {};
  const procs = new Map((r.process || []).map(p => [String(p.id), p]));
  const projects = new Map((r.project || []).map(p => [String(p.id), p]));
  const out = [];
  for (const t of r.task || []) {
    const p = procs.get(String(t.processId)); if (!p) continue;
    const req = reqs.find(x => x.v.firstCheck.processes.includes(String(p.code)));
    if (!req) continue;
    const pj = projects.get(String(t.projectId)) || {};
    const insp = st.inspections.filter(q => q.kind === 'first' && q.processCode === String(p.code) && q.objectRef && (q.objectRef.code === String(pj.projectCode) || q.objectRef.id === String(t.projectId)) && q.status !== 'VOID').sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))[0];
    const status = !insp ? 'PENDING' : insp.status === 'DRAFT' ? 'DRAFT' : insp.verdict === 'PASS' ? 'PASS' : 'FAIL';
    const fw = (weighins || []).filter(w => String(w.taskId) === String(t.id)).sort((a, b) => String(a.start).localeCompare(String(b.start)))[0];
    // A 存的是 UTC 时刻（…Z）；这里统一换成北京时间「YYYY-MM-DDTHH:mm」，与检验时间（北京时间本地值）同一口径比较
    const bj = v => { const t = Date.parse(v); return Number.isFinite(t) ? new Date(t + 8 * 3600e3).toISOString().slice(0, 16) : ''; };
    const firstWeighin = fw ? { id: fw.id, start: bj(fw.start), end: bj(fw.end), inputKg: fw.inputKg, goodKg: fw.goodKg, by: fw.by, source: 'Cretas 称重（A）' } : null;
    const inspAt = insp && insp.status === 'SUBMITTED' ? (insp.inspectedAt ? insp.inspectedAt.slice(0, 16) : '') || (insp.submittedAt ? new Date(Date.parse(insp.submittedAt) + 8 * 3600e3).toISOString().slice(0, 16) : '') : '';
    const fwAt = fw ? bj(fw.start) : '';
    const order = !fw ? null : !inspAt ? 'WEIGHED_NO_CHECK' : inspAt <= fwAt ? 'CHECK_FIRST' : 'WEIGHED_FIRST';
    out.push({ taskId: String(t.id), projectId: String(t.projectId), projectCode: String(pj.projectCode || ''), productName: String(pj.productName || ''), processCode: String(p.code), processName: String(p.name), taskState: String(t.processStatus), taskStateLabel: TASK_STATE[t.processStatus] || '未返回', standardId: req.s.id, standardName: req.v.name, status, inspection: insp ? { id: insp.id, no: insp.no, disposal: insp.disposal || '', at: inspAt } : null, firstWeighin, order });
  }
  const order = { FAIL: 0, PENDING: 1, DRAFT: 2, PASS: 3 }, live = { '10': 0, '0': 1, '20': 2 };
  out.sort((a, b) => (live[a.taskState] ?? 3) - (live[b.taskState] ?? 3) || order[a.status] - order[b.status] || a.projectCode.localeCompare(b.projectCode));
  return { available: true, source: '黑湖工序任务 + Cretas 补充首检要求', rows: out, requirements: reqs.length, activeProcesses: [...new Set(reqs.flatMap(x => x.v.firstCheck.processes.map(String)))] };
}
module.exports = { rows, TASK_STATE };
