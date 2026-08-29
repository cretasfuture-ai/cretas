/**
 * 回归网: 业务阈值配置页, 15 个分类共享同一个 rows ref (2026-08-29 实测)。
 *
 * 缺陷长相 (owner 实测): `v-for="cat in CATEGORY_ORDER"` 建了 15 个 el-tab-pane,
 * 每个里面都塞了一份 `<el-table :data="rows">`。Element Plus 默认不会卸载已经
 * "显示过"的 tab-pane (只用 v-show 隐藏), 于是 DOM 里同时存在多份指向同一个
 * `rows` ref 的表格。切到「销售」分类时, `.el-table__row` 抓到 15 行完全相同的内容
 * (同一条 `sales.delivery.over_ship_rate` 被渲染了 15 次)。
 *
 * 更隐蔽的第二个问题: 切 tab 触发的 loadCurrent() 是异步的, 在它 resolve 之前,
 * 共享的 rows ref 还是切换前那个分类的数据 —— 新面板会先顶着新标题显示旧数据
 * (「在错误的标题下显示错误的数据」)。
 *
 * ⚠️ 本文件刻意**不 mock element-plus** —— 真实 ElTabs/ElTable 的挂载/隐藏行为
 * (v-show 不卸载) 正是这个缺陷的成因, 桩掉它就测不出「重复渲染」这件事本身。
 * 参照 production/batches/__tests__/batchesListTable.spec.ts 的写法:
 * `global: { plugins: [ElementPlus] }`。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { nextTick } from 'vue';
import ElementPlus from 'element-plus';
import type { FactoryThreshold } from '@/api/thresholdsHub';

const harness = vi.hoisted(() => ({
  listThresholds: vi.fn(),
}));

vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true }),
}));
vi.mock('@/api/thresholdsHub', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/thresholdsHub')>();
  return {
    ...actual,
    listThresholds: harness.listThresholds,
    createThreshold: vi.fn(),
    updateThreshold: vi.fn(),
    resetCategoryToDefault: vi.fn(),
  };
});

import BusinessThresholds from '../index.vue';

function row(key: string, category: string): FactoryThreshold {
  return {
    id: `${key}-id`,
    factoryId: 'F006',
    thresholdKey: key,
    category: category as FactoryThreshold['category'],
    valueType: 'DECIMAL',
    thresholdValue: '1.05',
    defaultValue: '1.00',
    description: `${key} 描述`,
    unit: '%',
    minValue: null,
    maxValue: null,
    enabled: true,
    version: 1,
  };
}

/** 每个分类各自唯一的一行数据 —— 用来分辨"渲染的是哪个分类"而不只是"有几行"。 */
const DATA: Record<string, FactoryThreshold[]> = {
  INVENTORY: [row('inventory.min_stock_ratio', 'INVENTORY')],
  SALES: [row('sales.delivery.over_ship_rate', 'SALES')],
};

function mountThresholds() {
  return mount(BusinessThresholds, {
    global: {
      plugins: [ElementPlus],
    },
  });
}

function tableRowKeys(wrapper: ReturnType<typeof mountThresholds>): string[] {
  return wrapper.findAll('.el-table__row .key-cell').map((n) => n.text());
}

describe('业务阈值配置 — tab 与表格不再共享重复渲染', () => {
  beforeEach(() => {
    harness.listThresholds.mockReset();
    harness.listThresholds.mockImplementation(
      async (_factoryId: string, category?: string) => DATA[category ?? ''] ?? [row(`${category}.generic`, String(category))],
    );
  });

  it('初次挂载 (默认分类 INVENTORY) 只渲染 1 行, 不是 15 行', async () => {
    const wrapper = mountThresholds();
    await flushPromises();

    expect(tableRowKeys(wrapper)).toEqual(['inventory.min_stock_ratio']);
    wrapper.unmount();
  });

  it('依次点开全部 15 个分类 tab 之后, 页面上仍然只存在 1 份 el-table 行 (不是 15 份重复行)', async () => {
    const wrapper = mountThresholds();
    await flushPromises();

    const tabs = wrapper.findAll('.el-tabs__item');
    expect(tabs, '页签数量应仍是 15 个分类').toHaveLength(15);

    for (const tab of tabs) {
      await tab.trigger('click');
      await flushPromises();
    }

    // 阳性对照: 真的换了很多趟数据, 不是同一份没变过。
    // 挂载时先取一次 INVENTORY (默认分类); 循环里第一个 tab 正好也是 INVENTORY
    // (已经是当前 tab), 点它不触发 tab-change, 之后 14 个分类各触发一次 -> 共 15 次。
    expect(harness.listThresholds).toHaveBeenCalledTimes(15);

    expect(wrapper.findAll('.el-table__row')).toHaveLength(1);
    wrapper.unmount();
  });

  it('切换分类的瞬间 (新数据还没返回) 不显示旧分类的数据 —— 不串台', async () => {
    let resolveSales!: (v: FactoryThreshold[]) => void;
    harness.listThresholds.mockImplementation((_factoryId: string, category?: string) => {
      if (category === 'INVENTORY') return Promise.resolve(DATA.INVENTORY);
      if (category === 'SALES') return new Promise<FactoryThreshold[]>((resolve) => { resolveSales = resolve; });
      return Promise.resolve([]);
    });

    const wrapper = mountThresholds();
    await flushPromises();
    expect(tableRowKeys(wrapper)).toEqual(['inventory.min_stock_ratio']);

    const salesTab = wrapper.findAll('.el-tabs__item').find((n) => n.text() === '销售');
    expect(salesTab, '找不到「销售」页签 —— 后面的断言没有意义').toBeTruthy();
    await salesTab!.trigger('click');
    await nextTick();
    await nextTick();

    // 请求还没 resolve: 旧分类那一行不该还留在 DOM 里 (串台), 也不该叠加成 2 行
    expect(wrapper.text()).not.toContain('inventory.min_stock_ratio');
    expect(wrapper.findAll('.el-table__row').length).toBeLessThanOrEqual(1);

    resolveSales(DATA.SALES);
    await flushPromises();

    expect(tableRowKeys(wrapper)).toEqual(['sales.delivery.over_ship_rate']);
    wrapper.unmount();
  });
});
