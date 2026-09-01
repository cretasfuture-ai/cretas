import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';

/**
 * 已入库批次按收货单分组 (2026-09-01, owner 原话见 groupBySourceDocument.ts 头部)。
 *
 * ⛔ 这是行为测试, 不是只测纯函数 groupBatchesBySourceDocument —— 纯函数测试
 * (../groupBySourceDocument.spec.ts) 证明"分组逻辑本身对不对", 这一份证明
 * "list.vue 真的把它接上了、真的渲染出来了"(硬约束: 产出端有 ≠ 消费端收得到,
 * 形态 B'/硬约束9: "flat rows gone" 断言必须配一条"grouped rows rendered"的正向断言)。
 */

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { view: 'BATCHES' }, fullPath: '/warehouse/materials' }),
  useRouter: () => ({ push: vi.fn() }),
}));
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'LIUSHANMEN', hasRole: () => false }),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({
    canWrite: () => true,
    canAccess: () => true,
    canViewPrice: true,
  }),
}));
vi.mock('@/api/manufacturer', () => ({
  listManufacturers: vi.fn().mockResolvedValue({ success: true, data: [] }),
}));

const apiGet = vi.fn();
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => apiGet(...args),
  post: vi.fn(),
  put: vi.fn(),
}));

import ListVue from '../list.vue';

const RECEIPT_1_ID = '87e02e01-3bec-47c1-b371-e2bb39e60b45';
const RECEIPT_2_ID = '5c7c71b0-4cc6-4267-8c03-e221c7f12c45';

/** owner 场景复现: 3251厂牛前 / 941厂牛后 三个批次号, 同一次收货 RCV-20260901-6529。 */
const RECEIPT_1_BATCHES = [
  {
    id: 'MB-4022', batchNumber: 'MT-20260901-4022', materialTypeName: '3251厂牛前',
    supplierName: '测试供应商', receiptQuantity: 10, quantityUnit: 'kg', status: 'AVAILABLE',
    receiptDate: '2026-09-01', createdAt: '2026-09-01T08:00:00',
    sourceDocId: RECEIPT_1_ID, sourceDocType: 'PURCHASE_RECEIVE', sourceDocNumber: 'RCV-20260901-6529',
  },
  {
    id: 'MB-4043', batchNumber: 'MT-20260901-4043', materialTypeName: '941厂牛后',
    supplierName: '测试供应商', receiptQuantity: 5, quantityUnit: 'kg', status: 'AVAILABLE',
    receiptDate: '2026-09-01', createdAt: '2026-09-01T08:05:00',
    sourceDocId: RECEIPT_1_ID, sourceDocType: 'PURCHASE_RECEIVE', sourceDocNumber: 'RCV-20260901-6529',
  },
  {
    id: 'MB-4055', batchNumber: 'MT-20260901-4055', materialTypeName: '941厂牛后',
    supplierName: '测试供应商', receiptQuantity: 2, quantityUnit: '箱', status: 'AVAILABLE',
    receiptDate: '2026-09-01', createdAt: '2026-09-01T08:10:00',
    sourceDocId: RECEIPT_1_ID, sourceDocType: 'PURCHASE_RECEIVE', sourceDocNumber: 'RCV-20260901-6529',
  },
];

/** 第二次收货, 不同 source_doc_id —— 必须落到独立的组, 不能和上面那次混在一起。 */
const RECEIPT_2_BATCH = {
  id: 'MB-3312', batchNumber: 'MT-20260901-3312', materialTypeName: '海天草菇老抽',
  supplierName: '测试供应商2', receiptQuantity: 0.02, quantityUnit: 'kg', status: 'AVAILABLE',
  receiptDate: '2026-09-02', createdAt: '2026-09-02T08:00:00',
  sourceDocId: RECEIPT_2_ID, sourceDocType: 'PURCHASE_RECEIVE', sourceDocNumber: 'RCV-20260902-0001',
};

/** 无来源单据的批次(例如历史遗留数据)—— 必须显式出现在"无关联单据"桶, 不能消失。 */
const NO_SOURCE_BATCH = {
  id: 'MB-LEGACY', batchNumber: 'MT-LEGACY-0001', materialTypeName: '历史遗留原料',
  supplierName: '测试供应商', receiptQuantity: 1, quantityUnit: 'kg', status: 'AVAILABLE',
  receiptDate: '2026-08-01', createdAt: '2026-08-01T08:00:00',
  sourceDocId: null, sourceDocType: null, sourceDocNumber: null,
};

afterEach(() => {
  vi.restoreAllMocks();
});

function mountList() {
  return mount(ListVue, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        teleport: true,
        transition: false,
        ElDialog: {
          props: ['modelValue', 'title'],
          template: '<div class="dialog-stub" :data-title="title" v-if="modelValue"><slot /><slot name="footer" /></div>',
        },
      },
    },
  });
}

function mockApiGet(rows: Record<string, unknown>[]) {
  apiGet.mockReset();
  apiGet.mockImplementation((url: string) => {
    if (url.includes('/material-batches')) {
      return Promise.resolve({ success: true, data: { content: rows, totalElements: rows.length } });
    }
    if (url.includes('/raw-material-types/active')) {
      return Promise.resolve({ success: true, data: [] });
    }
    if (url.includes('/suppliers')) {
      return Promise.resolve({ success: true, data: { content: [] } });
    }
    return Promise.resolve({ success: false });
  });
}

type Vm = { handleViewModeChange: (mode: string) => void };

/**
 * 切到"按收货单分组"视图。直接调用 handleViewModeChange (与
 * materialBatchEditDialogDefects.mount.spec.ts 里 vm.handleCreate() 同一惯用法) ——
 * el-radio-button 在 jsdom 下驱动真实的隐藏 input change 事件链路脆弱且与本条要测的
 * 行为(分组渲染)无关, 这里只关心"切换之后 DOM 长什么样"是不是真的接上了。
 */
async function switchToDocumentView(wrapper: ReturnType<typeof mountList>) {
  const vm = wrapper.vm as unknown as Vm;
  vm.handleViewModeChange('byDocument');
  await flushPromises();
  await flushPromises();
}

describe('已入库批次按收货单分组: 成员归属', () => {
  it('一次收货产出的 3 个批次号渲染成一个组, 组内恰好 3 条', async () => {
    mockApiGet(RECEIPT_1_BATCHES);
    const wrapper = mountList();
    await flushPromises();
    await switchToDocumentView(wrapper);

    const text = wrapper.text();
    expect(text).toContain('RCV-20260901-6529');
    expect(text).toContain('3 个批次');

    // 展开该组, 断言组内真的是那 3 个批次号(成员归属, 不是只看计数)。
    const expandIcons = wrapper.findAll('.el-table__expand-icon');
    expect(expandIcons.length, '分组表没有可展开的行——分组没渲染出来').toBeGreaterThan(0);
    await expandIcons[0].trigger('click');
    await flushPromises();

    const expandedText = wrapper.text();
    expect(expandedText).toContain('MT-20260901-4022');
    expect(expandedText).toContain('MT-20260901-4043');
    expect(expandedText).toContain('MT-20260901-4055');

    wrapper.unmount();
  });

  it('第二次收货(不同 source_doc_id)渲染成独立的组, 不与第一组合并', async () => {
    mockApiGet([...RECEIPT_1_BATCHES, RECEIPT_2_BATCH]);
    const wrapper = mountList();
    await flushPromises();
    await switchToDocumentView(wrapper);

    const text = wrapper.text();
    expect(text).toContain('共'); // "共 N 个收货单据分组" 提示行
    expect(text).toContain('2 个收货单据分组');
    expect(text).toContain('RCV-20260901-6529');
    expect(text).toContain('RCV-20260902-0001');

    wrapper.unmount();
  });

  it('阳性对照: 单批次收货仍渲染为独立可见的组(组大小为 1 不会被隐藏/显得异常)', async () => {
    mockApiGet([RECEIPT_2_BATCH]);
    const wrapper = mountList();
    await flushPromises();
    await switchToDocumentView(wrapper);

    const text = wrapper.text();
    expect(text).toContain('RCV-20260902-0001');
    expect(text).toContain('1 个批次');
    expect(wrapper.find('.el-table__expand-icon').exists(), '单批次组必须仍然可展开, 与多批次组一致').toBe(true);

    wrapper.unmount();
  });

  it('无 source_doc_id 的批次落入显式的"无关联单据"桶, 不从列表里消失', async () => {
    mockApiGet([...RECEIPT_1_BATCHES, NO_SOURCE_BATCH]);
    const wrapper = mountList();
    await flushPromises();
    await switchToDocumentView(wrapper);

    const text = wrapper.text();
    expect(text).toContain('无关联单据');
    expect(text).toContain('无来源单据');

    // 硬约束9配对: "flat rows are gone"(明细表 v-if 不渲染)必须配一条"grouped rows rendered"的正向断言。
    expect(wrapper.find('.pagination-wrapper').exists(), '按批次明细的分页条不该还在——已经切到分组视图').toBe(false);
    expect(wrapper.findAll('.el-table__expand-icon').length, '分组行必须真的渲染出来, 不是切换后整个表消失').toBeGreaterThan(0);

    // 展开无关联单据组, 确认那条遗留批次真的在里面(成员归属), 不是只在表头数字上"存在"。
    const expandIcons = wrapper.findAll('.el-table__expand-icon');
    await expandIcons[expandIcons.length - 1].trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('MT-LEGACY-0001');

    wrapper.unmount();
  });
});

describe('已入库批次按收货单分组: 本次总量按单位拆分', () => {
  it('同一收货单内混单位(kg + 箱)不相加成一个假总数, 分别展示', async () => {
    mockApiGet(RECEIPT_1_BATCHES); // 10kg + 5kg + 2箱
    const wrapper = mountList();
    await flushPromises();
    await switchToDocumentView(wrapper);

    const text = wrapper.text();
    expect(text).toContain('15'); // 10 + 5 kg
    expect(text).toContain('kg');
    expect(text).toContain('箱');
    // ⛔ 阴性对照: 绝不能把 15(kg) 和 2(箱) 加成 17 或把三条数量简单相加成 17/18 之类的假总数当成单一总量展示。
    expect(text).not.toMatch(/17\s*(kg|箱)/);
  });
});
