/**
 * 缺料实例详情里的「处理结果核对」: 三种状态必须分得开 ——
 * 有核对 (显示最新结论) / 还没核对过 / 核对记录加载失败。
 * 加载失败不能长成「还没核对过」, 否则用户以为没做过核对 (同 approvalLoadFailureNotZero)。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

const getOutcomeChecksMock = vi.fn();

vi.mock('@/api/shortageCases', () => ({
  listShortageCases: vi.fn().mockResolvedValue({ success: true, data: [] }),
  getShortageCase: vi.fn().mockResolvedValue({
    success: true,
    data: {
      case: {
        id: 'c1', sourceType: 'SALES_ORDER', sourceId: 'so1', salesOrderNumber: 'SO-1',
        salesOrderStatus: 'FINANCE_APPROVED', salesOrderStatusLabel: '财务已批准', stage: 'WAITING_SUPPLY',
        stageLabel: '等到货 / 放行', ownerRole: 'warehouse_manager', ownerUserId: null, gapLineCount: 1,
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
  proposeReschedule: vi.fn(),
  applyReschedule: vi.fn(),
  getOutcomeChecks: (...args: unknown[]) => getOutcomeChecksMock(...args),
  recheckOutcome: vi.fn(),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true }) }));

import ShortageCases from '../index.vue';

async function mountAndOpen() {
  const wrapper = mount(ShortageCases, { global: { stubs: { 'el-table-column': true } } });
  await flushPromises();
  const vm = wrapper.vm as unknown as { openDetail: (row: { id: string }) => Promise<void> };
  await vm.openDetail({ id: 'c1' });
  await flushPromises();
  return wrapper;
}

const CHECK = {
  id: 1, actionId: 7, trigger: 'PURCHASE_DRAFT', verdict: 'NOT_ACHIEVED', verdictLabel: '未达成',
  gapClosed: false, openGapLines: 1, dueDate: '2026-11-20', latestPlannedEnd: null, lateScheduleCount: 0,
  supplyReadyDate: null, detail: '未达成: 缺口未关闭, 盐料 没有已下给供应商的采购单 (草稿不算)',
  createdAt: '2026-11-04T00:18:12',
};

describe('缺料实例 — 处理结果核对', () => {
  beforeEach(() => getOutcomeChecksMock.mockReset());

  it('有核对记录: 显示最新结论、触发来源和原文说明', async () => {
    getOutcomeChecksMock.mockResolvedValue({ success: true, data: [CHECK] });
    const text = (await mountAndOpen()).text();
    expect(text).toContain('未达成');
    expect(text).toContain('生成采购草稿后');
    expect(text).toContain('草稿不算');
    expect(text).not.toContain('还没核对过');
  });

  it('没有记录: 说还没核对过', async () => {
    getOutcomeChecksMock.mockResolvedValue({ success: true, data: [] });
    const text = (await mountAndOpen()).text();
    expect(text).toContain('还没核对过');
  });

  it('加载失败: 说加载失败, 不说成还没核对过', async () => {
    getOutcomeChecksMock.mockResolvedValue({ success: false, data: null, message: '权限不足' });
    const text = (await mountAndOpen()).text();
    expect(text).toContain('核对记录加载失败');
    expect(text).not.toContain('还没核对过');
  });
});
