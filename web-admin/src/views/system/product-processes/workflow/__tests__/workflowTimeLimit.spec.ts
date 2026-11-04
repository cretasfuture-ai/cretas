import { describe, expect, it } from 'vitest';
import type { WorkflowPortTimeLimit } from '@/api/processTimeLimit';
import { applyWorkflowPatches, validateWorkflow } from '../workflowModel';
import {
  draftFromLimit,
  limitFromDraft,
  startEventOptions,
  timeLimitBadge,
  timeLimitProblem,
} from '../workflowTimeLimit';
import type { ProductProcessWorkflowDefinition } from '../types';

/** 缺口 14: 时限配在 INPUT 端口上, 校验要和后端 ProcessTimeLimitConfig 同口径, 且要活过保存与 AI 补丁。 */
const full: WorkflowPortTimeLimit = {
  maxMinutes: 120,
  warnMinutes: 30,
  startEvent: 'THAW_MARKED',
  pauseCodes: ['COLD_STORAGE'],
  maxPauseMinutes: 60,
  mixRule: 'PER_LOT',
  splitRule: 'RESTART_AT_SPLIT',
  reworkRule: 'FROM_REWORK_OUTPUT',
  reworkMaxMinutes: 90,
  recheckValidMinutes: 45,
};

function definition(timeLimit: WorkflowPortTimeLimit): ProductProcessWorkflowDefinition {
  const material = (id: string, kind: 'RAW_MATERIAL' | 'FINISHED_GOOD') => ({
    id, kind, position: { x: 0, y: 0 }, data: { name: id, skuId: `sku-${id}`, baseUnit: 'kg' },
  });
  return {
    schemaVersion: 1,
    status: 'DRAFT',
    version: 1,
    viewport: { x: 0, y: 0, zoom: 1 },
    nodes: [
      material('raw', 'RAW_MATERIAL'),
      {
        id: 'p1',
        kind: 'PROCESS',
        position: { x: 0, y: 0 },
        data: {
          workProcessId: 'WP-1',
          processName: '解冻',
          inputUnit: 'kg',
          outputUnit: 'kg',
          conversionRule: { mode: 'ACTUAL_WEIGHT' },
          reportingRequired: true,
          ports: [
            { id: 'in', direction: 'INPUT', materialNodeId: 'raw', unit: 'kg', ordinal: 0, timeLimit },
            { id: 'out', direction: 'OUTPUT', materialNodeId: 'fin', unit: 'kg', ordinal: 0 },
          ],
        },
      },
      material('fin', 'FINISHED_GOOD'),
    ],
    edges: [
      { id: 'e1', source: 'raw', sourceHandle: 'output', target: 'p1', targetHandle: 'in' },
      { id: 'e2', source: 'p1', sourceHandle: 'out', target: 'fin', targetHandle: 'input' },
    ],
  };
}

describe('workflowTimeLimit', () => {
  it('原料和上道半成品的起算点选项不同', () => {
    expect(startEventOptions(true).map((o) => o.label)).toEqual(['原料入库', '领到生产仓', '登记解冻']);
    expect(startEventOptions(false).map((o) => o.label)).toEqual(['上道产出入账', '上道报工', '上道完工']);
  });

  it('抽屉编辑态来回一趟不丢字段', () => {
    expect(limitFromDraft(draftFromLimit(full, true))).toEqual(full);
  });

  it('关掉开关 = 不留配置; 没开过的取后端同样的保守默认', () => {
    expect(limitFromDraft({ ...draftFromLimit(full, true), enabled: false })).toBeNull();
    const fresh = draftFromLimit(null, false);
    expect(fresh).toMatchObject({
      enabled: false, startEvent: 'OUTPUT_POSTED', mixRule: 'EARLIEST', splitRule: 'INHERIT', reworkRule: 'INHERIT_BATCH_START',
    });
  });

  it('关掉冷藏暂停时不留最多暂停', () => {
    const limit = limitFromDraft({ ...draftFromLimit(full, true), pauseAllowed: false });
    expect(limit).toMatchObject({ pauseCodes: [], maxPauseMinutes: null });
  });

  it('校验: 时限必填, 提醒要小于时限, 起算点要和料的种类对得上', () => {
    expect(timeLimitProblem(full, true)).toBeNull();
    expect(timeLimitProblem({ startEvent: 'THAW_MARKED' }, true)).toContain('请填时限');
    expect(timeLimitProblem({ ...full, maxMinutes: 1.5 }, true)).toContain('请填时限');
    expect(timeLimitProblem({ ...full, warnMinutes: 120 }, true)).toBe('临近提醒要小于时限');
    expect(timeLimitProblem(full, false)).toContain('起算点要选上道工序的');
    expect(timeLimitProblem({ ...full, recheckValidMinutes: 0 }, true)).toContain('复检放行有效期');
  });

  it('角标写人话', () => {
    expect(timeLimitBadge({ maxMinutes: 90, startEvent: 'RAW_OUTBOUND' })).toBe('限时 1 小时 30 分');
  });

  it('发布校验: 料换成原料后起算点还是上道的, 要拦; 对得上不拦', () => {
    const bad = validateWorkflow(definition({ maxMinutes: 60, startEvent: 'OUTPUT_POSTED' }), 'publish');
    expect(bad.filter((e) => e.code === 'TIME_LIMIT_INVALID').map((e) => e.nodeId)).toEqual(['p1']);
    expect(validateWorkflow(definition(full), 'publish').filter((e) => e.code === 'TIME_LIMIT_INVALID')).toEqual([]);
  });

  it('配过时限的图还能打 AI 补丁, 时限原样留着', () => {
    const { definition: result, errors } = applyWorkflowPatches(definition(full), [
      { op: 'SET_NODE_FIELD', nodeId: 'p1', path: 'reportingRequired', value: false },
    ]);
    expect(errors).toEqual([]);
    const ports = (result.nodes.find((n) => n.id === 'p1')?.data as { ports: Array<{ id: string; timeLimit?: unknown }> }).ports;
    expect(ports.find((p) => p.id === 'in')?.timeLimit).toEqual(full);
  });
});
