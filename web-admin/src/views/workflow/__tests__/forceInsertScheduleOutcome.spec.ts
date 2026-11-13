import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { scheduleOutcomeHtml, toForceInsertScheduleView } from '../forceInsertScheduleOutcome';

/** 字段与后端 UrgentInsertScheduleService.Proposal.toMap() / OA 动作结果一致。 */
const actionResult = {
  instanceId: 'INST-1',
  workflowStatus: 'APPROVED',
  businessStatus: 'APPROVED',
  planNumber: 'URGENT-001',
  productionLineId: 'L1',
  productionLineName: '一号线',
  scheduleOutcome: {
    status: 'OK',
    message: null,
    insertFirstDay: '2027-01-05',
    insertLastDay: '2027-01-06',
    warnings: ['人员未核对'],
    movedPlans: [
      { planId: 'p9', planNumber: 'PP-9', oldEnd: '2027-01-08', newEnd: '2027-01-10', delayDays: 2,
        dueDate: '2027-01-09', exceedsDue: true, reasons: [] },
      { planId: 'p8', planNumber: 'PP-8', oldEnd: '2027-01-07', newEnd: '2027-01-08', delayDays: 1,
        dueDate: null, exceedsDue: false, reasons: [] },
    ],
    exceedsDueCount: 1,
  },
};

describe('强制插单排程结果展示', () => {
  it('读出排进哪条线、哪天、挤了谁、谁超交期', () => {
    const view = toForceInsertScheduleView(actionResult)!;
    expect(view.lineText).toBe('一号线');
    expect(view.dayText).toBe('2027-01-05 ～ 2027-01-06');
    expect(view.headline).toBe('已排入，并顺延了 2 张已有计划');
    expect(view.moved.map(m => m.planNumber)).toEqual(['PP-9', 'PP-8']);
    expect(view.exceedsDueCount).toBe(1);
    expect(view.warnings).toEqual(['人员未核对']);
  });

  it('没有被挤的计划时明说没挤占, 同一天只显示一天', () => {
    const view = toForceInsertScheduleView({
      scheduleOutcome: { status: 'OK', insertFirstDay: '2027-01-05', insertLastDay: '2027-01-05', movedPlans: [] },
      productionLineId: 'L7',
    })!;
    expect(view.headline).toBe('已排入，没有挤占其它计划');
    expect(view.dayText).toBe('2027-01-05');
    expect(view.lineText).toBe('产线 L7');
  });

  it('没排进产线 (UNSCHEDULED) 不能说成已排入; 缺字段如实写「未提供」', () => {
    const view = toForceInsertScheduleView({ scheduleOutcome: { status: 'UNSCHEDULED', message: '没填日产能' } })!;
    expect(view.headline).toContain('没有排进产线');
    expect(view.lineText).toBe('未提供');
    expect(view.dayText).toBe('未提供');
    expect(view.message).toBe('没填日产能');
  });

  it('没有 scheduleOutcome (驳回 / 多级中间节点 / 老后端) 返回 null, 不弹空窗口', () => {
    expect(toForceInsertScheduleView({ instanceId: 'x', workflowStatus: 'RUNNING' })).toBeNull();
    expect(toForceInsertScheduleView(null)).toBeNull();
    expect(toForceInsertScheduleView(undefined)).toBeNull();
  });

  it('HTML 里后端字符串全部转义 (计划号 / 警告里的尖括号不能变成标签)', () => {
    const view = toForceInsertScheduleView({
      scheduleOutcome: { status: 'OK', insertFirstDay: '2027-01-05', warnings: ['<img src=x onerror=alert(1)>'],
        movedPlans: [{ planNumber: '<b>PP</b>', oldEnd: '2027-01-08', newEnd: '2027-01-09', delayDays: 1,
          dueDate: null, exceedsDue: false }] },
    })!;
    const html = scheduleOutcomeHtml(view, '<script>x</script>');
    expect(html).not.toContain('<img');
    expect(html).not.toContain('<script>');
    expect(html).not.toContain('<b>PP</b>');
    expect(html).toContain('&lt;img src=x onerror=alert(1)&gt;');
  });

  it('HTML 带出线 / 日期 / 被挤计划 / 超交期提示', () => {
    const html = scheduleOutcomeHtml(toForceInsertScheduleView(actionResult)!, 'URGENT-001');
    expect(html).toContain('一号线');
    expect(html).toContain('2027-01-05 ～ 2027-01-06');
    expect(html).toContain('PP-9：2027-01-08 → 2027-01-10，顺延 2 天，交期 2027-01-09（已超）');
    expect(html).toContain('1 张被挤后完成日晚于交期');
  });

  it('待我审批页真的接上了它 (不是写了没人调): 批准强制插单后弹出排程结果', () => {
    const pending = readFileSync(resolve(__dirname, '../pending.vue'), 'utf8');
    expect(pending).toContain("from './forceInsertScheduleOutcome'");
    expect(pending).toMatch(/action === 'APPROVE' && row\.moduleCode === 'FORCE_INSERT'/);
    expect(pending).toContain('scheduleOutcomeHtml(');
  });
});
