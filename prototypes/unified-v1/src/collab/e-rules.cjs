'use strict';
// 规则触发的提醒（BL-borrow 第 24 条，E 出任务侧）：固定 3 条规则，不做通用事件设计器。
// 规则只生成「提醒」（谁该做什么），不自动建任务、不改业务数据；要建任务仍走「预览 → 确认」。
// 何时检查：每次本模块写入之后、以及手动「立即检查」。查询（GET）不触发写入。
// 每次触发写一条执行日志；同一对象同一规则已有未处理提醒时记「跳过（已有提醒）」，不重复提醒。
const crypto = require('crypto');
const RULES = {
  INSPECTION_FAIL: { name: '检验不合格未建任务', trigger: '检验单提交且判定不合格', action: '提醒质量经理生成质量异常任务', role: '质量经理' },
  TASK_OVERDUE: { name: '任务超期', trigger: '未完成任务的截止日早于今天', action: '提醒负责岗位处理', role: '' },
  FIRST_CHECK_PENDING: { name: '首检未做', trigger: '黑湖工序任务执行中，且该工序要求首检但没有首检记录', action: '提醒品控做首检', role: '品控' }
};
function ensure(st) {
  st.rules = st.rules || {}; for (const k of Object.keys(RULES)) if (!st.rules[k]) st.rules[k] = { enabled: true };
  st.ruleLog = st.ruleLog || []; st.reminders = st.reminders || [];
}
function run(st, ctx) {
  ensure(st);
  const at = new Date().toISOString(), added = [];
  const log = (rule, key, result, detail) => { const e = { id: crypto.randomBytes(4).toString('hex'), at, rule, key, result, detail, by: ctx.by || '系统' }; st.ruleLog.push(e); added.push(e); };
  const fire = (rule, key, title, role, ref, reopenAuto) => {
    if (st.reminders.some(r => r.rule === rule && r.key === key && r.status === 'open')) return; // 已有未处理提醒：不重复记日志，避免日志被刷屏
    if (st.reminders.some(r => r.rule === rule && r.key === key && r.status !== 'open' && r.closedFor === ctx.today && !(reopenAuto && r.closedBy === '系统'))) return; // 当天已处理过的同一对象不再提醒
    st.reminders.push({ id: 'RM-' + crypto.randomBytes(4).toString('hex'), rule, key, title, role, ref, status: 'open', at });
    log(rule, key, '已生成提醒', title);
  };
  const on = k => st.rules[k] && st.rules[k].enabled;
  if (on('INSPECTION_FAIL')) for (const q of st.inspections) {
    if (q.status !== 'SUBMITTED' || q.verdict !== 'FAIL') continue;
    const hasTask = st.tasks.some(t => t.status !== 'CANCELLED' && (t.related || []).some(r => r.type === 'inspection' && r.id === q.id));
    const key = 'inspection:' + q.id;
    if (hasTask) { closeAuto(st, 'INSPECTION_FAIL', key, '已有关联任务', ctx); continue; }
    fire('INSPECTION_FAIL', key, '检验 ' + q.no + ' 不合格（' + (q.failItems || []).join('、') + '），还没有处理任务', '质量经理', { type: 'inspection', id: q.id, code: q.no });
  }
  if (on('TASK_OVERDUE')) for (const t of st.tasks) {
    const key = 'task:' + t.id + ':' + t.due;
    if (['DONE', 'CANCELLED'].includes(t.status) || !(t.due < ctx.today)) { closeAuto(st, 'TASK_OVERDUE', key, '任务已完成或截止已调整', ctx); continue; }
    const days = Math.round((Date.parse(ctx.today) - Date.parse(t.due)) / 864e5);
    fire('TASK_OVERDUE', key, '任务 ' + t.no + ' 超期 ' + days + ' 天：' + t.title, t.assignee.role, { type: 'task', id: t.id, code: t.no });
  }
  if (on('FIRST_CHECK_PENDING') && ctx.firstRows) {
    const seen = new Set(), active = new Set(ctx.firstActiveProcesses || []);
    for (const r of ctx.firstRows) {
      const key = 'first:' + r.taskId; seen.add(key);
      if (r.taskState !== '10' || r.status !== 'PENDING') { closeAuto(st, 'FIRST_CHECK_PENDING', key, r.status === 'PENDING' ? '工序任务不在执行中' : '已有首检记录', ctx); continue; }
      // 首检单作废后重新判断：仍无有效首检记录且任务执行中 → 重新打开（系统关闭过的不受「当天不再提醒」限制）
      fire('FIRST_CHECK_PENDING', key, '工单 ' + r.projectCode + ' 的「' + r.processName + '」执行中，还没有首检记录', '品控', { type: 'project', id: r.projectId, code: r.projectCode, process: r.processCode, standardId: r.standardId }, true);
    }
    // 标准停用：不再有任何启用标准要求该工序首检 → 该工序下未处理提醒自动关闭，结论「标准已停用」（别的标准的提醒不动）
    for (const m of st.reminders) if (m.rule === 'FIRST_CHECK_PENDING' && m.status === 'open' && !seen.has(m.key)) {
      const proc = m.ref && m.ref.process != null ? String(m.ref.process) : '';
      closeAuto(st, 'FIRST_CHECK_PENDING', m.key, active.has(proc) ? '工序任务已不在快照' : '标准已停用', ctx);
    }
  }
  st.ruleLog = st.ruleLog.slice(-500);
  return added;
}
// 条件不再成立时自动关闭提醒，并记日志
function closeAuto(st, rule, key, why, ctx) {
  for (const r of st.reminders) if (r.rule === rule && r.key === key && r.status === 'open') {
    r.status = 'resolved'; r.closedAt = new Date().toISOString(); r.closedBy = '系统'; r.reason = why; r.closedFor = ctx.today;
    st.ruleLog.push({ id: crypto.randomBytes(4).toString('hex'), at: r.closedAt, rule, key, result: '条件解除，提醒关闭', detail: why, by: '系统' });
  }
}
function summary(st) {
  ensure(st);
  const since = Date.now() - 3 * 864e5;
  return Object.entries(RULES).map(([id, r]) => {
    const mine = st.ruleLog.filter(e => e.rule === id);
    return { id, ...r, enabled: st.rules[id].enabled, lastAt: mine.length ? mine[mine.length - 1].at : null, count3d: mine.filter(e => Date.parse(e.at) >= since && e.result === '已生成提醒').length, open: st.reminders.filter(x => x.rule === id && x.status === 'open').length };
  });
}
module.exports = { RULES, run, summary, ensure };
