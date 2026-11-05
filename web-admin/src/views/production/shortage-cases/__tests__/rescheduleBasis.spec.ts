import { describe, it, expect, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

const proposeRescheduleMock = vi.fn();

vi.mock('@/api/shortageCases', () => ({
  listShortageCases: vi.fn().mockResolvedValue({ success: true, data: [] }),
  getShortageCase: vi.fn().mockResolvedValue({
    success: true,
    data: {
      case: {
        id: 'c1', sourceType: 'SALES_ORDER', sourceId: 'so1', salesOrderNumber: 'SO-1',
        salesOrderStatus: 'FINANCE_APPROVED', salesOrderStatusLabel: '财务已批准', stage: 'WAITING_SUPPLY',
        stageLabel: '等到货 / 放行', ownerRole: 'production_manager', ownerUserId: null, gapLineCount: 1,
        gapResolvedAt: null, lastRefreshedAt: '2026-11-04T00:17:31', lastRefreshReason: 'SO_FINANCE_APPROVED',
        closedAt: null, createdAt: '2026-11-04T00:17:31',
      },
      lines: [],
      events: [],
    },
  }),
  refreshShortageCase: vi.fn(),
  getProcurementProposals: vi.fn().mockResolvedValue({ success: true, data: [] }),
  createProcurementDraft: vi.fn(),
  decideShortageCase: vi.fn(),
  proposeReschedule: (...args: unknown[]) => proposeRescheduleMock(...args),
  applyReschedule: vi.fn(),
  getOutcomeChecks: vi.fn().mockResolvedValue({ success: true, data: [] }),
  recheckOutcome: vi.fn(),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true }) }));

import ShortageCases from '../index.vue';

async function openAndPropose(response: unknown) {
  proposeRescheduleMock.mockResolvedValue(response);
  const wrapper = mount(ShortageCases, { global: { stubs: { 'el-table-column': true } } });
  await flushPromises();
  const vm = wrapper.vm as unknown as {
    openDetail: (row: { id: string }) => Promise<void>;
    propose: () => Promise<void>;
  };
  await vm.openDetail({ id: 'c1' });
  await vm.propose();
  await flushPromises();
  return wrapper;
}

const BASIS = {
  productionPlanId: 'PP-1', workflowId: '377', workflowVersion: '1', workflowRevisionId: '541',
  bomRecipeId: 'BOM-395', bomVersion: '1',
  capacityPerDay: '1000', capacitySource: 'MANUAL', unit: '袋', dateBasis: 'APS_DAILY_CAPACITY',
  days: [{ date: '2026-11-10', quantity: '1000', shareOfDay: '1' }],
};

describe('缺料局部重排 — 正式依据展示', () => {
  it('展示 Workflow/BOM 固定快照与 APS 日产能口径', async () => {
    const wrapper = await openAndPropose({
      success: true,
      data: {
        id: 'p1', status: 'OPEN', statusLabel: '开放', expiresAt: '2026-11-05T13:00:00',
        createdAt: '2026-11-05T12:30:00', appliedAt: null, appliedCandidate: null,
        candidates: [{ key: 'SAME_LINE_POSTPONE', label: '同线顺延 1 天', applicable: true, moves: [], notes: [], basis: [BASIS] }],
      },
    });
    const text = wrapper.text();
    expect(text).toContain('Workflow ID 377 / v1 / 修订 541；BOM 配方 BOM-395 v1');
    expect(text).toContain('APS 人工日产能 1000 袋/天');
    expect(text).toContain('未验证工序级精确耗时');
  });

  it('缺少依据时明确不可应用而不是显示可执行按钮', async () => {
    const wrapper = await openAndPropose({
      success: true,
      data: {
        id: 'p2', status: 'OPEN', statusLabel: '开放', expiresAt: '2026-11-05T13:00:00',
        createdAt: '2026-11-05T12:30:00', appliedAt: null, appliedCandidate: null,
        candidates: [{ key: 'SAME_LINE_POSTPONE', label: '同线顺延', applicable: false, moves: [], notes: ['缺少正式依据'], basis: [] }],
      },
    });
    expect(wrapper.text()).toContain('缺少正式依据');
    expect(wrapper.text()).not.toContain('应用此方案');
  });

  it('兼容旧版 proposal 时要求重新生成且不展示旧的精确动作', async () => {
    const wrapper = await openAndPropose({
      success: true,
      data: {
        id: 'legacy', status: 'OPEN', statusLabel: '开放', expiresAt: '2026-11-05T13:00:00',
        createdAt: '2026-11-05T12:30:00', appliedAt: null, appliedCandidate: null,
        candidates: [{ key: 'SAME_LINE_POSTPONE', label: '同线顺延', applicable: true,
          moves: [{ fromEnd: '2026-11-10T16:00:00', toEnd: '2026-11-12T16:00:00' }], notes: ['旧记录原有的到货说明'] }],
      },
    });
    expect(wrapper.text()).toContain('该方案来自旧版本，缺少正式排程依据，请重新生成');
    expect(wrapper.text()).not.toContain('应用此方案');
    expect(wrapper.text()).not.toContain('2026-11-12');
  });
});
