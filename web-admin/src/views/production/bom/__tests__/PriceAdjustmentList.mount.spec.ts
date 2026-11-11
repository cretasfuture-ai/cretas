/**
 * D-S5-09 (2026-11-11): BOM 调价建议此前网页上没有入口、也没有驳回。挂载真实的 PriceAdjustmentList.vue,
 * 走真实入口 (加载列表 → 打开驳回 → 提交 / 批准), 读它发出去的请求。
 *
 * - 有审批权 (approval_bom_price_adjustment = rw): 驳回原因不选不能提交; 选「其他」必须写具体原因;
 *   提交带原因; 批准带上审批人看到的建议价 (expectedUnitPrice) 与当前登录人。
 * - 阴性对照 (没有审批权): 入口函数直接返回, 一个写请求都不发; 权限判断确实按这个审批码问的。
 */
import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const harness = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  confirm: vi.fn(),
  levels: {} as Record<string, string>,
  asked: [] as string[],
}));

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006', user: { id: 42 } }),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({
    hasFullAccess: (code: string) => {
      harness.asked.push(code);
      return harness.levels[code] === 'rw';
    },
  }),
}));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => harness.get(...args),
  post: (...args: unknown[]) => harness.post(...args),
}));
vi.mock('element-plus', () => ({
  ElMessage: Object.assign(vi.fn(), { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() }),
  ElMessageBox: { confirm: (...a: unknown[]) => harness.confirm(...a) },
}));

import PriceAdjustmentList from '../PriceAdjustmentList.vue';

const ROW = {
  id: 7,
  factoryId: 'F006',
  recipeId: 'BOM-1',
  recipeItemId: 11,
  recipeCode: 'QA-REC-1',
  productName: 'QA-卤猪蹄',
  materialTypeId: 'RMT-1',
  materialName: 'QA-包材',
  currentUnitPrice: 0.5,
  proposedUnitPrice: 0.7501,
  deltaAmount: 0.2501,
  deltaPercent: 50.02,
  affectedProductCount: 1,
  status: 'PENDING',
  sourceType: 'PURCHASE_RECEIVE',
};

interface Vm {
  canApprove: boolean;
  rows: typeof ROW[];
  rejectOpen: boolean;
  rejectChoice: string;
  rejectOther: string;
  rejectInvalid: boolean;
  openReject: (row: typeof ROW) => void;
  submitReject: () => Promise<void>;
  handleApprove: (row: typeof ROW) => Promise<void>;
}

async function mountPage() {
  // 表格列 / 抽屉的作用域插槽在浅渲染里拿不到 row, 不渲染它们 (本测试读的是入口函数发出的请求)
  const wrapper = shallowMount(PriceAdjustmentList, {
    global: { stubs: { 'el-table-column': { template: '<i />' }, 'el-drawer': { template: '<i />' } } },
  });
  await flushPromises();
  return wrapper.vm as unknown as Vm;
}

beforeEach(() => {
  harness.get.mockReset().mockResolvedValue({
    success: true,
    data: { content: [ROW], totalElements: 1, totalPages: 1 },
  });
  harness.post.mockReset().mockResolvedValue({ success: true, data: { ...ROW, status: 'REJECTED' } });
  harness.confirm.mockReset().mockResolvedValue('confirm');
  harness.levels = {};
  harness.asked = [];
});

describe('BOM 调价建议页 (D-S5-09)', () => {
  it('加载时按「待审批」查本厂的建议', async () => {
    const vm = await mountPage();
    expect(harness.get).toHaveBeenCalledWith('/F006/bom/price-adjustments', {
      params: { status: 'PENDING', page: 0, size: 20 },
    });
    expect(vm.rows).toHaveLength(1);
  });

  it('有审批权: 驳回原因必填, 选「其他」要写具体原因, 提交带原因', async () => {
    harness.levels.approval_bom_price_adjustment = 'rw';
    const vm = await mountPage();
    expect(vm.canApprove).toBe(true);

    vm.openReject(ROW);
    expect(vm.rejectOpen).toBe(true);
    expect(vm.rejectInvalid).toBe(true);           // 不选原因不能提交
    await vm.submitReject();
    expect(harness.post).not.toHaveBeenCalled();

    vm.rejectChoice = '其他';
    vm.rejectOther = '   ';
    await flushPromises();
    expect(vm.rejectInvalid).toBe(true);           // 「其他」只写空白不算
    vm.rejectOther = 'QA-本月不调整标准成本';
    await flushPromises();
    expect(vm.rejectInvalid).toBe(false);

    await vm.submitReject();
    expect(harness.post).toHaveBeenCalledWith('/F006/bom/price-adjustments/7/reject', {
      reason: 'QA-本月不调整标准成本',
    });
  });

  it('有审批权: 批准带当前登录人和审批人看到的建议价', async () => {
    harness.levels.approval_bom_price_adjustment = 'rw';
    const vm = await mountPage();
    await vm.handleApprove(ROW);
    expect(harness.confirm).toHaveBeenCalledTimes(1);
    expect(String(harness.confirm.mock.calls[0][1])).toContain('QA-包材');   // 弹窗标题带物料与配方
    expect(harness.post).toHaveBeenCalledWith('/F006/bom/price-adjustments/7/approve', {
      approverId: 42,
      expectedUnitPrice: 0.7501,
    });
  });

  it('阴性对照: 没有审批权时批准 / 驳回入口一个写请求都不发', async () => {
    harness.levels.approval_bom_price_adjustment = 'r';
    harness.levels.production = 'rw';                // 只有生产读写不等于能批
    const vm = await mountPage();
    expect(vm.canApprove).toBe(false);
    expect(harness.asked).toContain('approval_bom_price_adjustment');

    vm.openReject(ROW);
    expect(vm.rejectOpen).toBe(false);
    await vm.handleApprove(ROW);
    expect(harness.confirm).not.toHaveBeenCalled();
    expect(harness.post).not.toHaveBeenCalled();
  });
});
