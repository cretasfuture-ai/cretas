/**
 * 缺陷 50 (owner-hit, LIUSHANMEN prod) —— 生产批次列表搜索一个「存在但不在当前
 * 页/分组里」的批次号, 不再退回未过滤的分组列表。
 *
 * 复现的是 owner 在生产上的原样输入: 批次 id 10884, 批次号
 * `PB-PLAN-1788008667414-B141A9D8-8026`, 搜索框只输入了它的计划号前缀
 * `PB-PLAN-1788008667414`。根因: `/processing/batches-with-clerk-wip`
 * (ProcessingController#getBatchesWithClerkWip) 从来没接受过 `batchNumber`
 * 参数 —— Spring 静默丢弃, 服务端从不按批次号过滤; 之前搜索框输入任何内容,
 * 点搜索后台仍返回同一页未过滤数据, 前端直接渲染, 用户会把"另一个计划的分组行"
 * 读成"查到的结果", 得出"这个批次不存在"的结论。
 *
 * 这里验证前端对已加载数据做诚实的关键词过滤: 命中就只显示命中的分组/行,
 * 不命中就显示明确的空态并回显关键词 —— 绝不回退成未过滤列表。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';

const apiGet = vi.fn();

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true, canViewPrice: true }),
}));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => apiGet(...args),
  post: vi.fn(),
}));
vi.mock('@/api/printApi', () => ({ safePrint: vi.fn() }));

import BatchesList from '../list.vue';

// Plan A —— 与 owner 搜的目标批次无关, 只用来证明"命中 B 不该带出 A"。
const PLAN_A_ROW = {
  id: 'WIP-A1',
  batchNumber: 'PB-PLAN-1111111111-AAAA0000',
  batchType: 'CLERK_WIP',
  sourcePlanId: 'PLAN-A',
  sourcePlanNumber: 'PB-PLAN-1111111111',
  sourceProcessOrder: 1,
  productTypeName: '卤猪蹄',
  plannedQuantity: 50,
  actualQuantity: 48,
  status: 'IN_PROGRESS',
};

// Plan B —— owner 生产原样输入: 批次 id 10884, 搜索词是它计划号那一段前缀。
const PLAN_B_ROW = {
  id: '10884',
  batchNumber: 'PB-PLAN-1788008667414-B141A9D8-8026',
  batchType: 'CLERK_WIP',
  sourcePlanId: 'PLAN-B',
  sourcePlanNumber: 'PB-PLAN-1788008667414',
  sourceProcessOrder: 1,
  productTypeName: '风干肠',
  plannedQuantity: 80,
  actualQuantity: 76,
  status: 'IN_PROGRESS',
};

const OWNER_TYPED_KEYWORD = 'PB-PLAN-1788008667414';

function mountList() {
  return mount(BatchesList, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        teleport: true,
        transition: false,
        ConceptDisambiguationAlert: true,
        AiEntryDrawer: true,
        RowActionMenu: true,
      },
    },
  });
}

async function typeAndSearch(wrapper: ReturnType<typeof mountList>, keyword: string) {
  const input = wrapper.find('input[placeholder="批次号"]');
  await input.setValue(keyword);
  const searchButton = wrapper.findAll('button').find((btn) => btn.text().trim() === '搜索');
  if (!searchButton) throw new Error('搜索按钮没找到');
  await searchButton.trigger('click');
  await flushPromises();
}

async function clickReset(wrapper: ReturnType<typeof mountList>) {
  const resetButton = wrapper.findAll('button').find((btn) => btn.text().trim() === '重置');
  if (!resetButton) throw new Error('重置按钮没找到');
  await resetButton.trigger('click');
  await flushPromises();
}

describe('生产批次列表 — 搜索命中当前页外的批次不再回退成未过滤列表 (缺陷 50)', () => {
  beforeEach(() => {
    apiGet.mockReset();
    apiGet.mockResolvedValue({
      success: true,
      data: { content: [PLAN_A_ROW, PLAN_B_ROW], totalElements: 2 },
    });
    localStorage.clear();
  });

  it('未搜索时两个计划分组都在', async () => {
    const wrapper = mountList();
    await flushPromises();

    const groupRows = wrapper.findAll('tr.plan-group-row');
    expect(groupRows.length).toBe(2);
    expect(wrapper.text()).toContain('PB-PLAN-1111111111');
    expect(wrapper.text()).toContain('PB-PLAN-1788008667414');
  });

  it('搜索命中的批次号 → 只显示命中的那一个分组, 不带出另一个计划', async () => {
    const wrapper = mountList();
    await flushPromises();

    await typeAndSearch(wrapper, OWNER_TYPED_KEYWORD);

    const groupRows = wrapper.findAll('tr.plan-group-row');
    expect(groupRows.length).toBe(1);
    // 命中的计划号 + 它的批次号都在
    expect(wrapper.text()).toContain('PB-PLAN-1788008667414');
    expect(wrapper.text()).toContain('PB-PLAN-1788008667414-B141A9D8-8026');
    // 阴性对照: 不该带出另一个计划
    expect(wrapper.text()).not.toContain('PB-PLAN-1111111111');
    expect(wrapper.text()).not.toContain('卤猪蹄');
  });

  it('搜索一个不在已加载数据里的批次号 → 明确的空态回显关键词, 绝不回退成未过滤的分组列表', async () => {
    const wrapper = mountList();
    await flushPromises();

    const missingKeyword = 'PB-PLAN-9999999999999-NOPE';
    await typeAndSearch(wrapper, missingKeyword);

    // 阴性对照: 这才是这条缺陷的核心 —— 两个计划分组都不该出现,
    // 尤其不能出现"另一个计划的分组行"这种旧表现。
    expect(wrapper.findAll('tr.plan-group-row').length).toBe(0);
    expect(wrapper.text()).not.toContain('PB-PLAN-1111111111');
    expect(wrapper.text()).not.toContain('PB-PLAN-1788008667414');
    expect(wrapper.text()).not.toContain('卤猪蹄');
    expect(wrapper.text()).not.toContain('风干肠');

    // 正面: 空态说清"没搜到"并回显关键词, 不是笼统的"暂无数据"。
    expect(wrapper.text()).toContain('没有匹配');
    expect(wrapper.text()).toContain(missingKeyword);
    expect(wrapper.text()).toContain('的批次');
  });

  it('按计划号搜也能命中该计划下的批次 (不只是按批次自己的编号)', async () => {
    const wrapper = mountList();
    await flushPromises();

    // sourcePlanNumber 与 batchNumber 前缀相同是本例的巧合; 用不重叠的计划号
    // 单独验证「按 sourcePlanNumber/sourcePlanId 命中」这条分支本身。
    await typeAndSearch(wrapper, 'PLAN-B');

    expect(wrapper.findAll('tr.plan-group-row').length).toBe(1);
    expect(wrapper.text()).toContain('PB-PLAN-1788008667414-B141A9D8-8026');
    expect(wrapper.text()).not.toContain('PB-PLAN-1111111111');
  });

  it('清空搜索(重置)后两个分组都恢复', async () => {
    const wrapper = mountList();
    await flushPromises();

    await typeAndSearch(wrapper, 'PB-PLAN-9999999999999-NOPE');
    expect(wrapper.findAll('tr.plan-group-row').length).toBe(0);

    await clickReset(wrapper);

    expect(wrapper.findAll('tr.plan-group-row').length).toBe(2);
    expect(wrapper.text()).toContain('PB-PLAN-1111111111');
    expect(wrapper.text()).toContain('PB-PLAN-1788008667414');
    expect(wrapper.text()).not.toContain('没有匹配');
  });
});
