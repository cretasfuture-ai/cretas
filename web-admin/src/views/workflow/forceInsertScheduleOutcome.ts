/**
 * 强制插单批准后的排程结果 (排进哪条线、哪天、挤了谁)。
 *
 * 字段以后端 `UrgentInsertScheduleService.Proposal.toMap()` 为准:
 *   status / message / insertFirstDay / insertLastDay / warnings /
 *   movedPlans[{planId, planNumber, oldEnd, newEnd, delayDays, dueDate, exceedsDue, reasons}] / exceedsDueCount
 * 产线来自 OA 动作结果的 productionLineName / productionLineId (计划上的建议产线)。
 * ⛔ 缺的字段如实说「未提供」, 不编、不当 0。
 */
export interface MovedPlan {
  planNumber: string;
  oldEnd: string | null;
  newEnd: string | null;
  delayDays: number | null;
  dueDate: string | null;
  exceedsDue: boolean;
}

export interface ForceInsertScheduleView {
  /** OK / UNSCHEDULED / BLOCKED / NOT_EVALUATED / 后端新增的其它值原样 */
  status: string;
  headline: string;
  lineText: string;
  dayText: string;
  moved: MovedPlan[];
  exceedsDueCount: number;
  warnings: string[];
  message: string | null;
}

const asString = (v: unknown): string | null => (typeof v === 'string' && v.trim() ? v.trim() : null);
const asNumber = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const asRecord = (v: unknown): Record<string, unknown> | null =>
  v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null;

/** OA 动作返回 (`res.data`) → 展示用结构; 没有排程结果 (驳回 / 多级中间节点 / 老后端) 返回 null。 */
export function toForceInsertScheduleView(actionResult: unknown): ForceInsertScheduleView | null {
  const result = asRecord(actionResult);
  const outcome = asRecord(result?.scheduleOutcome);
  if (!result || !outcome) return null;

  const status = asString(outcome.status) ?? 'UNKNOWN';
  const first = asString(outcome.insertFirstDay);
  const last = asString(outcome.insertLastDay);
  const lineName = asString(result.productionLineName);
  const lineId = asString(result.productionLineId);

  const moved = (Array.isArray(outcome.movedPlans) ? outcome.movedPlans : [])
    .map(asRecord)
    .filter((row): row is Record<string, unknown> => row !== null)
    .map<MovedPlan>(row => ({
      planNumber: asString(row.planNumber) ?? asString(row.planId) ?? '未知计划',
      oldEnd: asString(row.oldEnd),
      newEnd: asString(row.newEnd),
      delayDays: asNumber(row.delayDays),
      dueDate: asString(row.dueDate),
      exceedsDue: row.exceedsDue === true,
    }));

  const headline = status === 'OK'
    ? moved.length > 0
      ? `已排入，并顺延了 ${moved.length} 张已有计划`
      : '已排入，没有挤占其它计划'
    : status === 'UNSCHEDULED'
      ? '计划已批准，但按配置没有排进产线（需要人工安排）'
      : status === 'NOT_EVALUATED'
        ? '计划已批准，但没能评估排程（未排进产线）'
        : `排程状态：${status}`;

  return {
    status,
    headline,
    lineText: lineName ?? (lineId ? `产线 ${lineId}` : '未提供'),
    dayText: first ? (last && last !== first ? `${first} ～ ${last}` : first) : '未提供',
    moved,
    exceedsDueCount: asNumber(outcome.exceedsDueCount) ?? moved.filter(m => m.exceedsDue).length,
    warnings: (Array.isArray(outcome.warnings) ? outcome.warnings : [])
      .concat(Array.isArray(result.uncheckedWarnings) ? result.uncheckedWarnings : [])
      .map(asString)
      .filter((w): w is string => w !== null),
    message: asString(outcome.message),
  };
}

const escapeHtml = (text: string): string =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

/** 给 ElMessageBox 用的 HTML (所有后端字符串都转义)。 */
export function scheduleOutcomeHtml(view: ForceInsertScheduleView, planNumber?: string | null): string {
  const e = escapeHtml;
  const rows = [
    `<p data-testid="schedule-outcome-headline"><b>${e(view.headline)}</b>${planNumber ? `（${e(planNumber)}）` : ''}</p>`,
    `<p>排进产线：<span data-testid="schedule-outcome-line">${e(view.lineText)}</span></p>`,
    `<p>插单日期：<span data-testid="schedule-outcome-day">${e(view.dayText)}</span></p>`,
  ];
  if (view.message) rows.push(`<p style="color:#E6A23C">${e(view.message)}</p>`);
  if (view.moved.length > 0) {
    rows.push(
      view.exceedsDueCount > 0
        ? `<p style="color:#F56C6C" data-testid="schedule-outcome-exceeds">其中 ${view.exceedsDueCount} 张被挤后完成日晚于交期，请联系对应客户</p>`
        : '<p>被挤计划都没有超过交期</p>',
    );
    rows.push('<ul data-testid="schedule-outcome-moved" style="padding-left:18px;margin:4px 0">');
    for (const m of view.moved) {
      const delay = m.delayDays === null ? '' : `，顺延 ${m.delayDays} 天`;
      const due = m.dueDate ? `，交期 ${m.dueDate}${m.exceedsDue ? '（已超）' : ''}` : '';
      rows.push(`<li>${e(m.planNumber)}：${e(m.oldEnd ?? '未提供')} → ${e(m.newEnd ?? '未提供')}${delay}${due}</li>`);
    }
    rows.push('</ul>');
  }
  for (const warning of view.warnings) rows.push(`<p style="color:#909399">⚠ ${e(warning)}</p>`);
  return rows.join('');
}
