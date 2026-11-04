/**
 * 「按当前库存重新核对」的显示口径要与后端 refresh 接口的写权限一致:
 * 销售只读缺料, 点了只会 403 (D-S411-02), 已结束的实例后端也不再刷新。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

const writable = new Set<string>();
let stage = 'WAITING_SUPPLY';

vi.mock('@/api/shortageCases', () => ({
  listShortageCases: vi.fn().mockResolvedValue({ success: true, data: [] }),
  getShortageCase: vi.fn().mockImplementation(() => Promise.resolve({
    success: true,
    data: {
      case: {
        id: 'c1', sourceType: 'SALES_ORDER', sourceId: 'so1', salesOrderNumber: 'SO-1',
        salesOrderStatus: 'FINANCE_APPROVED', salesOrderStatusLabel: '财务已批准', stage,
        stageLabel: '', ownerRole: 'warehouse_manager', ownerUserId: null, gapLineCount: 1,
        gapResolvedAt: null, lastRefreshedAt: '2026-11-04T00:17:31', lastRefreshReason: 'SO_FINANCE_APPROVED',
        closedAt: null, createdAt: '2026-11-04T00:17:31',
      },
      lines: [],
      events: [],
    },
  })),
  refreshShortageCase: vi.fn(),
  getProcurementProposals: vi.fn().mockResolvedValue({ success: true, data: [] }),
  createProcurementDraft: vi.fn(),
  decideShortageCase: vi.fn(),
  proposeReschedule: vi.fn(),
  applyReschedule: vi.fn(),
  getOutcomeChecks: vi.fn().mockResolvedValue({ success: true, data: [] }),
  recheckOutcome: vi.fn(),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: (module: string) => writable.has(module) }),
}));

import ShortageCases from '../index.vue';

async function detailText(): Promise<string> {
  const wrapper = mount(ShortageCases, { global: { stubs: { 'el-table-column': true } } });
  await flushPromises();
  const vm = wrapper.vm as unknown as { openDetail: (row: { id: string }) => Promise<void> };
  await vm.openDetail({ id: 'c1' });
  await flushPromises();
  return wrapper.text();
}

describe('缺料详情「按当前库存重新核对」', () => {
  beforeEach(() => {
    writable.clear();
    stage = 'WAITING_SUPPLY';
  });

  it('只有销售写权限时不显示', async () => {
    writable.add('sales');
    expect(await detailText()).not.toContain('按当前库存重新核对');
  });

  it.each(['procurement', 'production', 'warehouse', 'quality'])('%s 有写权限时显示', async (module) => {
    writable.add(module);
    expect(await detailText()).toContain('按当前库存重新核对');
  });

  it('实例已结束时不显示', async () => {
    writable.add('procurement');
    stage = 'CLOSED';
    expect(await detailText()).not.toContain('按当前库存重新核对');
  });
});
