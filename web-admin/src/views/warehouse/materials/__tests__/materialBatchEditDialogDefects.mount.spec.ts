import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus, { ElMessage } from 'element-plus';

/**
 * 缺陷 B / A / C —— 批次编辑弹窗 (LIUSHANMEN 客户真实批次 MT-20260901-3312 实测)
 *
 * DB 读数 (只读, 未在本轮修改): receipt_quantity=0.020000, quantity_unit=kg,
 * unit_price=8.85, status=AVAILABLE, box_count=NULL, weight_per_unit=NULL。
 *
 * <h3>为什么必须是行为测试, ⛔ 不能只看源码闸</h3>
 * 缺陷 B 的根因是 `el-input-number` 组件自身的 `displayValue` 计算属性
 * (`currentValue.toFixed(precision)`), 源码扫描看不出"显示成 0.0"这件事——
 * 必须真的挂载真实 ElementPlus 组件, 读渲染出来的 `<input>` 的 value。
 *
 * 缺陷 C 的根因更隐蔽: 实测 `ElMessage.info()` 会在同一个 Vue watcher flush
 * 周期内重入调度器, 让"仍在等待执行"的另一个 watch 在守卫真正置位之前就跑到
 * 判断分支——纯源码扫描 (数 `w02HintShown = true` 出现几次) 完全看不出这个重入,
 * 唯一能验证的方法是挂载 + 真的触发两个 watch + 数 ElMessage.info 被调用几次。
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
const apiPut = vi.fn();
const apiPost = vi.fn();
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => apiGet(...args),
  post: (...args: unknown[]) => apiPost(...args),
  put: (...args: unknown[]) => apiPut(...args),
}));

import ListVue from '../list.vue';

/** LIUSHANMEN 真实批次 (evidence) —— 数量 0.02kg 是极小但合法的收货量。 */
const LIUSHANMEN_ROW = {
  id: 'MB-3312',
  batchNumber: 'MT-20260901-3312',
  materialTypeId: 'MAT-XIACAOGU',
  materialTypeName: '13.9kg海天草菇老抽',
  materialName: '13.9kg海天草菇老抽',
  supplierId: 'SUP-1',
  supplierName: '测试供应商',
  receiptDate: '2026-09-01',
  receiptQuantity: 0.02,
  quantityUnit: 'kg',
  boxCount: null,
  weightPerUnit: null,
  totalWeight: null,
  totalValue: 0.18,
  unitPrice: 8.85,
  status: 'AVAILABLE',
  expireDate: '2026-12-01',
};

/** 阳性对照: 数量恰好是整数 5 —— 证明修复没有引入相反方向的过度显示(如强制补小数位)。 */
const WHOLE_NUMBER_ROW = {
  ...LIUSHANMEN_ROW,
  id: 'MB-WHOLE',
  batchNumber: 'MT-WHOLE',
  receiptQuantity: 5,
};

/** 阳性对照: 数量恰好是 0 —— 证明修复没有把"合法的 0"渲染成空白/"—"/异常。 */
const ZERO_QTY_ROW = {
  ...LIUSHANMEN_ROW,
  id: 'MB-ZERO',
  batchNumber: 'MT-ZERO',
  receiptQuantity: 0,
};

/** 原料主数据: 未配置单价 (movingAvgPrice / unitPrice 均为 null) —— 缺陷 A 复现材料。 */
const MATERIAL_TYPE_NO_MASTER_PRICE = {
  id: 'MAT-XIACAOGU',
  name: '13.9kg海天草菇老抽',
  unit: 'kg',
  unitPrice: null,
  movingAvgPrice: null,
};

// ⚠️ 不放在单个 it() 末尾: 断言失败会抛出, 跳过它之后的 mockRestore()——
// 上一条测试失败时留下的活 spy 会把调用计数带进下一条, 红一条变红两条 (实测踩过)。
// 放 afterEach 保证不管前一条测试是否失败, 都会清干净。
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
        // 与本仓 SeasoningBindingDialog.spec.ts / PackagingBindingDialog.spec.ts 同一配方:
        // 真实 el-dialog 在 vue-test-utils 的 teleport 桩下不会同步渲染内容(即便 flushPromises
        // 多次也找不到 .el-dialog), 用一个按 modelValue 直接渲染 slot 的桩绕开——
        // 弹窗本身的挂载时机不是本轮要测的行为, 里面的 ElInputNumber/ElForm 才是。
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
      return Promise.resolve({ success: true, data: [MATERIAL_TYPE_NO_MASTER_PRICE] });
    }
    if (url.includes('/suppliers')) {
      return Promise.resolve({ success: true, data: { content: [{ id: 'SUP-1', name: '测试供应商' }] } });
    }
    return Promise.resolve({ success: false });
  });
}

/** 找编辑弹窗里"数量"那一格的原生 `<input>` (真实 ElInputNumber 渲染出来的)。 */
function findQuantityInput(wrapper: ReturnType<typeof mountList>) {
  const items = wrapper.findAll('.el-form-item');
  const qtyItem = items.find((item) => item.find('.el-form-item__label').text() === '数量');
  return qtyItem?.find('input');
}

/**
 * 每个测试都只 mock 一行数据, 所以"点第一个编辑按钮"就够用——不需要按 batchId 反查行。
 * batchId 参数只用于失败信息, 帮助定位是哪个用例的行没渲染出来。
 */
async function openEditDialog(wrapper: ReturnType<typeof mountList>, batchId: string) {
  const editButtons = wrapper.findAll('button').filter((b) => b.text() === '编辑');
  expect(editButtons.length, `编辑按钮一个都没渲染出来 (batchId=${batchId})——列表没加载出行`).toBeGreaterThan(0);
  await editButtons[0].trigger('click');
  await flushPromises();
  await flushPromises();
}

describe('批次编辑弹窗缺陷 B: 数量显示与 DB/列表/详情一致', () => {
  beforeEach(() => {
    apiPut.mockReset();
    apiPost.mockReset();
    apiPut.mockResolvedValue({ success: true });
  });

  it('LIUSHANMEN 真实批次: 0.02kg 显示为 "0.02"，不是 "0.0"', async () => {
    mockApiGet([LIUSHANMEN_ROW]);
    const wrapper = mountList();
    await flushPromises();
    await openEditDialog(wrapper, LIUSHANMEN_ROW.id);

    const qtyInput = findQuantityInput(wrapper);
    expect(qtyInput, '没找到"数量"这个 el-form-item 或它里面没有 input——解析器坏了').toBeTruthy();
    expect(qtyInput!.element.value).toBe('0.02');
    // ⛔ 阴性对照: 修复前的缺陷长相就是显示成 "0.0" —— 显式断言它不是那个错值,
    //    避免"断言碰巧因为别的原因绿了"。
    expect(qtyInput!.element.value).not.toBe('0.0');
    expect(qtyInput!.element.value).not.toBe('0');

    wrapper.unmount();
  });

  it('阳性对照: 整数 5 显示为 "5"，不强制补小数位 (修复没有引入相反方向的过度显示)', async () => {
    mockApiGet([WHOLE_NUMBER_ROW]);
    const wrapper = mountList();
    await flushPromises();
    await openEditDialog(wrapper, WHOLE_NUMBER_ROW.id);

    const qtyInput = findQuantityInput(wrapper);
    expect(qtyInput!.element.value).toBe('5');

    wrapper.unmount();
  });

  it('阳性对照: 数量恰好是 0 时仍显示 "0"，不是空白/"—" (0 是数量的合法值，不是"未知")', async () => {
    mockApiGet([ZERO_QTY_ROW]);
    const wrapper = mountList();
    await flushPromises();
    await openEditDialog(wrapper, ZERO_QTY_ROW.id);

    const qtyInput = findQuantityInput(wrapper);
    expect(qtyInput!.element.value).toBe('0');

    wrapper.unmount();
  });

  it('🔴 硬约束9配对: 数量字段被 disabled，且 disabled 不是"渲染失败"的假象 —— 控件确实存在且不可写', async () => {
    mockApiGet([LIUSHANMEN_ROW]);
    const wrapper = mountList();
    await flushPromises();
    await openEditDialog(wrapper, LIUSHANMEN_ROW.id);

    const qtyInput = findQuantityInput(wrapper);
    // disabled 的原生 input 在 jsdom 里仍然有 .disabled === true，而不是不存在。
    expect(qtyInput!.element.disabled).toBe(true);
    expect(qtyInput!.element.value).toBe('0.02'); // 仍然是正确的值——不是因为 disabled 才"看不出错"

    wrapper.unmount();
  });
});

describe('批次编辑弹窗缺陷 B: disabled 的账实字段不可能进入提交体', () => {
  beforeEach(() => {
    apiPut.mockReset();
    apiPost.mockReset();
    apiPut.mockResolvedValue({ success: true });
    mockApiGet([LIUSHANMEN_ROW]);
  });

  it('打开编辑、直接点确定 -> PUT 请求体里没有 receiptQuantity/totalValue/totalWeight/materialTypeId/supplierId/quantityUnit/receiptDate', async () => {
    const wrapper = mountList();
    await flushPromises();
    await openEditDialog(wrapper, LIUSHANMEN_ROW.id);

    const confirmButtons = wrapper.findAll('button').filter((b) => b.text() === '确定');
    expect(confirmButtons.length, '"确定"按钮没找到').toBeGreaterThan(0);
    await confirmButtons[0].trigger('click');
    await flushPromises();

    expect(apiPut, 'PUT 一次都没被调用——提交路径本身没走通').toHaveBeenCalled();
    const [, payload] = apiPut.mock.calls[0] as [string, Record<string, unknown>];
    const leaked = ['receiptQuantity', 'totalValue', 'totalWeight', 'materialTypeId', 'supplierId', 'quantityUnit', 'receiptDate']
      .filter((key) => key in payload);
    expect(leaked, `账实字段泄漏进了提交体: ${leaked.join(', ')} —— disabled 挡不住 v-model 绑定进 formData, 必须是白名单挡住`).toEqual([]);

    // 阳性对照: 白名单里的描述性字段确实能发出去 (不是整个 payload 是空对象碰巧不含那些 key)。
    expect(Object.keys(payload).length, 'payload 是空对象——白名单本身没生效, 不是"账实字段被挡住了"').toBeGreaterThan(0);

    wrapper.unmount();
  });
});

describe('批次编辑弹窗缺陷 A: "未配置单价"提示在编辑态下不再是死路', () => {
  beforeEach(() => {
    mockApiGet([LIUSHANMEN_ROW]);
  });

  it('编辑 LIUSHANMEN 批次(主数据未配置单价, 但批次自己 unitPrice=8.85) -> 提示不再弹出', async () => {
    const infoSpy = vi.spyOn(ElMessage, 'info').mockImplementation(() => ({}) as never);
    const wrapper = mountList();
    await flushPromises();
    await openEditDialog(wrapper, LIUSHANMEN_ROW.id);

    const priceToasts = infoSpy.mock.calls.filter((c) => JSON.stringify(c).includes('未配置单价'));
    expect(priceToasts, '编辑态下仍然弹出了"请手动输入总价值"——而总价值字段在编辑态是 disabled 的, 这条指令不可能被执行').toEqual([]);

    infoSpy.mockRestore();
    wrapper.unmount();
  });

  it('阳性对照: 新增模式下, 真的价格缺失的原料仍然会提示 (证明修复没有把整个提示机制关闭, 只是编辑态跳过)', async () => {
    const infoSpy = vi.spyOn(ElMessage, 'info').mockImplementation(() => ({}) as never);
    const wrapper = mountList();
    await flushPromises();

    type Vm = { handleCreate: () => void; formData: Record<string, unknown> };
    const vm = wrapper.vm as unknown as Vm;
    vm.handleCreate();
    await flushPromises();
    // 新增模式下手动选中未配置单价的原料 + 填数量, 驱动两个 watch。
    vm.formData.materialTypeId = 'MAT-XIACAOGU';
    await flushPromises();
    vm.formData.receiptQuantity = 3;
    await flushPromises();
    await flushPromises();

    const priceToasts = infoSpy.mock.calls.filter((c) => JSON.stringify(c).includes('未配置单价'));
    expect(priceToasts.length, '新增模式下价格缺失的原料应该仍然提示——修复不应该把提示机制整个关掉').toBeGreaterThan(0);

    infoSpy.mockRestore();
    wrapper.unmount();
  });
});

describe('批次编辑弹窗缺陷 C: 同一句提示不再弹两次', () => {
  it('编辑 LIUSHANMEN 批次: 未配置单价提示总调用次数为 0 (缺陷 A 的修复本身消灭了编辑态的重复)', async () => {
    mockApiGet([LIUSHANMEN_ROW]);
    const infoSpy = vi.spyOn(ElMessage, 'info').mockImplementation(() => ({}) as never);
    const wrapper = mountList();
    await flushPromises();
    await openEditDialog(wrapper, LIUSHANMEN_ROW.id);

    const priceToasts = infoSpy.mock.calls.filter((c) => JSON.stringify(c).includes('未配置单价'));
    expect(priceToasts.length).toBe(0);

    infoSpy.mockRestore();
    wrapper.unmount();
  });

  it('🔴 新增模式: materialTypeId 与 receiptQuantity 两个 watch 同时触发时, 提示只弹一次 (缺陷 C 的核心: ElMessage.info 重入)', async () => {
    mockApiGet([LIUSHANMEN_ROW]);
    // ⚠️ 这里刻意**不** .mockImplementation() —— 必须是 spy-through(真实实现照跑,
    // 只是顺便记调用)。缺陷 C 的重入机制本身就是"真实 ElMessage.info() 内部挂载消息
    // 组件时, 会在返回调用方之前重入调度器, 让仍在等待执行的另一个 watch 提前跑到判断分支"。
    // 实测: 换成 `.mockImplementation(() => ({}) as never)` 之后, 这条断言在 mutation M3
    // (把"先置位再弹"改回"先弹再置位")下**纹丝不动仍然是 1**——因为桩掉的 ElMessage.info
    // 是纯同步空函数, 根本不会重入, 等于把本条要守的那个行为一起桩没了(见 C″/C⁗)。
    // 只有 spy-through 才能让这条断言在 mutation 下真的变红。
    const infoSpy = vi.spyOn(ElMessage, 'info');
    const wrapper = mountList();
    await flushPromises();

    type Vm = { handleCreate: () => void; formData: Record<string, unknown> };
    const vm = wrapper.vm as unknown as Vm;
    vm.handleCreate();
    await flushPromises();

    // 关键: materialTypeId 与 receiptQuantity 在同一个 Object.assign 风格的连续赋值里变化,
    // 让两个 watch 在同一个 flush 周期里都变成"待执行"——这正是缺陷 C 复现的条件。
    Object.assign(vm.formData, { materialTypeId: 'MAT-XIACAOGU', receiptQuantity: 3 });
    await flushPromises();
    await flushPromises();

    const priceToasts = infoSpy.mock.calls.filter((c) => JSON.stringify(c).includes('未配置单价'));
    expect(priceToasts.length, `提示弹了 ${priceToasts.length} 次——应该恰好 1 次`).toBe(1);

    infoSpy.mockRestore();
    wrapper.unmount();
  });
});
