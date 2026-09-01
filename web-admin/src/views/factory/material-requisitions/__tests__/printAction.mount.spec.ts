/**
 * 领料单打印 —— 后端 `GET /{factoryId}/print/material-requisition/{id}` 已在生产验证可用
 * (200, application/pdf, ReportLab PDF)，前端此前**没有任何入口**调用它 (形态 B: built,
 * never wired; 见 CLAUDE.md `.claude/rules/measurement-and-wiring.md`)。
 *
 * <h3>为什么必须是行为测试, ⛔ 不能是源码闸</h3>
 * 「按钮渲染出来了」不是判据 —— 本仓已有先例: 按钮/入口在, 但挂的是错的 URL、
 * 或响应没被当 PDF 处理、或失败时静默/谎报成功, 用户站在那儿等一张永远不来的纸。
 * 判据是: 点击后**真的发出了对的请求**, 且响应被**真的当 PDF 处理**(构造 Blob、
 * 生成下载链接、触发 click) —— 不是「请求发出去了就算数」。
 *
 * <h3>硬约束 9 的两条对照</h3>
 * ① 成功时不许有错误提示 (阴性) —— 但必须搭配 ② 成功时 PDF 下载链路确实被触发
 * (阳性): createObjectURL 被调用、锚点 download 属性等于预期文件名、锚点 click()
 * 被调用。只断言①会被「请求发出但从没处理响应」这类半吊子实现骗过去。
 *
 * <h3>失败路径不许说谎 (brief 要求 3)</h3>
 * 请求失败时: 不许静默 (无提示)、不许显示成功 toast、必须显示真实错误。
 */
import { flushPromises, mount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide } from 'vue';
import type { ComputedRef } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const harness = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  requestGet: vi.fn(),
  push: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}));

vi.mock('vue-router', () => ({ useRouter: () => ({ push: harness.push }) }));
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true }),
}));
vi.mock('@/api/factoryWarehouse', () => ({
  listWarehouses: vi.fn().mockResolvedValue({ success: true, data: [] }),
}));
// `request` 默认导出 (打印用的 blob 调用) 与具名 get/post/put (列表加载等) 分开桩,
// 与生产代码 `import request, { get, post, put } from '@/api/request'` 的两种用法对齐。
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => harness.get(...args),
  post: (...args: unknown[]) => harness.post(...args),
  put: (...args: unknown[]) => harness.put(...args),
  default: { get: (...args: unknown[]) => harness.requestGet(...args) },
}));
vi.mock('element-plus', () => ({
  ElMessage: {
    success: (...a: unknown[]) => harness.success(...a),
    error: (...a: unknown[]) => harness.error(...a),
    warning: vi.fn(),
    info: vi.fn(),
  },
  ElMessageBox: { confirm: vi.fn().mockResolvedValue('confirm') },
}));

import RequisitionList from '../list.vue';

const ROWS_KEY = 'stub-table-rows';

/** 表格 stub: 把 `:data` 通过 provide 交给子级 `el-table-column`, 保留真实行数据。 */
const ElTableStub = defineComponent({
  name: 'ElTable',
  props: { data: { type: Array, default: () => [] } },
  setup(props, { slots }) {
    provide(ROWS_KEY, computed(() => props.data));
    return () => h('div', { class: 'stub-table' }, slots.default?.());
  },
});

/** 列 stub: 对每一行调用一次 scoped default slot, 真正渲染出行内按钮 (而不是空壳)。 */
const ElTableColumnStub = defineComponent({
  name: 'ElTableColumn',
  setup(_props, { slots }) {
    const rows = inject<ComputedRef<unknown[]>>(ROWS_KEY, computed(() => []));
    return () => h('div', rows.value.map((row) => h('div', slots.default?.({ row }))));
  },
});

/** 按钮 stub: 真正把 @click 转发出去 (⛔ 不能只是渲染文字, 否则点击测不到真实 handler)。 */
const ElButtonStub = defineComponent({
  name: 'ElButton',
  props: { loading: { type: Boolean, default: false } },
  emits: ['click'],
  setup(props, { slots, emit }) {
    return () => h(
      'button',
      { 'data-loading': String(props.loading), onClick: () => emit('click') },
      slots.default?.(),
    );
  },
});

const PassThrough = (name: string) => defineComponent({
  name,
  setup(_props, { slots }) {
    return () => h('div', slots.default?.());
  },
});

const stubs = {
  ElTable: ElTableStub,
  ElTableColumn: ElTableColumnStub,
  ElButton: ElButtonStub,
  ElCard: PassThrough('ElCard'),
  ElTag: PassThrough('ElTag'),
  ElSelect: PassThrough('ElSelect'),
  ElOption: PassThrough('ElOption'),
  ElPagination: PassThrough('ElPagination'),
  ElDialog: PassThrough('ElDialog'),
  ElForm: PassThrough('ElForm'),
  ElFormItem: PassThrough('ElFormItem'),
  ElDescriptions: PassThrough('ElDescriptions'),
  ElDescriptionsItem: PassThrough('ElDescriptionsItem'),
  ElInput: PassThrough('ElInput'),
  ElInputNumber: PassThrough('ElInputNumber'),
  ElAlert: PassThrough('ElAlert'),
};

const ROW = {
  id: 'REQ-1',
  requisitionNo: 'MR-20260901-0001',
  status: 'PENDING',
  items: [],
};

function mountList() {
  return mount(RequisitionList, { global: { stubs } });
}

async function clickPrint() {
  const wrapper = mountList();
  await flushPromises();
  const btn = wrapper.findAll('button').find((b) => b.text() === '打印');
  expect(btn, '渲染不出「打印」按钮 —— 闸自己瞎了, 后面每条断言都无意义').toBeTruthy();
  await btn!.trigger('click');
  await flushPromises();
  return wrapper;
}

describe('领料单打印 — 点击后必须发出正确请求, 且响应被当 PDF 处理', () => {
  let clickSpy: ReturnType<typeof vi.spyOn>;
  let appendChildSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    Object.values(harness).forEach((fn) => fn.mockReset());
    harness.get.mockResolvedValue({
      success: true,
      data: { content: [ROW], totalElements: 1 },
    });

    // ⚠️ `vi.spyOn` 对**已经被 spy 过**的方法不会重置调用历史 (同一个 jsdom `document`/
    // 原型对象跨测试复用) —— 用 `vi.isMockFunction` 判断是否已 spy 过, 已 spy 就只
    // `mockClear()`, 否则才第一次 spyOn。漏了这一步, 后一个测试会读到前一个测试的调用记录
    // (实测: 第 2 个用例断言的文件名读到了第 1 个用例的旧值)。
    if (!URL.createObjectURL || !vi.isMockFunction(URL.createObjectURL)) {
      // @ts-expect-error jsdom 没有实现, 补一个可断言的桩
      URL.createObjectURL = vi.fn();
    }
    (URL.createObjectURL as ReturnType<typeof vi.fn>).mockReset();
    (URL.createObjectURL as ReturnType<typeof vi.fn>).mockReturnValue('blob:mock-material-requisition');

    if (!URL.revokeObjectURL || !vi.isMockFunction(URL.revokeObjectURL)) {
      // @ts-expect-error jsdom 没有实现
      URL.revokeObjectURL = vi.fn();
    }
    (URL.revokeObjectURL as ReturnType<typeof vi.fn>).mockReset();

    if (!vi.isMockFunction(HTMLAnchorElement.prototype.click)) {
      vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    }
    clickSpy = HTMLAnchorElement.prototype.click as unknown as ReturnType<typeof vi.fn>;
    clickSpy.mockClear();

    if (!vi.isMockFunction(document.body.appendChild)) {
      vi.spyOn(document.body, 'appendChild');
    }
    appendChildSpy = document.body.appendChild as unknown as ReturnType<typeof vi.fn>;
    appendChildSpy.mockClear();
  });

  it('①②③ 请求 URL/id 正确 + Blob 被真的构造并触发下载 + 不显示错误', async () => {
    const pdfBlob = new Blob(['%PDF-1.4 fake bytes'], { type: 'application/pdf' });
    harness.requestGet.mockResolvedValueOnce({ data: pdfBlob });

    const wrapper = await clickPrint();

    // ① 请求打到了正确的 URL, 带正确的 requisition id, 走 blob responseType —— 不是随便一个 GET。
    expect(harness.requestGet).toHaveBeenCalledTimes(1);
    expect(harness.requestGet).toHaveBeenCalledWith(
      '/F006/print/material-requisition/REQ-1',
      { responseType: 'blob' },
    );

    // ② 响应被真的当 PDF 处理: 构造了 Blob → objectURL → <a download> → click()。
    // ⛔ 不是「请求发出去了就算」——半吊子实现(发了请求但收到响应什么都不做)在①上也会绿。
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1);
    const anchorCall = appendChildSpy.mock.calls.find(([el]) => el instanceof HTMLAnchorElement);
    expect(anchorCall, '没有把 <a download> 节点挂上 document.body —— 下载链路没真的跑').toBeTruthy();
    const anchor = anchorCall![0] as HTMLAnchorElement;
    expect(anchor.download).toBe('领料单_MR-20260901-0001.pdf');
    expect(clickSpy, '锚点从没被 click() —— 浏览器不会真的下载/弹出打印文件').toHaveBeenCalledTimes(1);

    // ③ 硬约束 9: 「没有错误提示」必须与②(阳性: PDF 链路确实跑了)成对出现,
    //    否则可能只是请求整个没发生, 「没报错」是假读数。
    expect(harness.error).not.toHaveBeenCalled();
    expect(harness.success).toHaveBeenCalledTimes(1);

    wrapper.unmount();
  });

  it('阳性对照: 换一个不同的 id/单号, 请求与文件名跟着变 —— 不是恒定字符串', async () => {
    // 覆盖 mountList 用的固定 ROW: 用不同 id 重新挂载, 证明 URL/文件名不是写死的。
    harness.get.mockResolvedValue({
      success: true,
      data: { content: [{ id: 'REQ-9', requisitionNo: 'MR-DIFFERENT-9', status: 'PENDING', items: [] }], totalElements: 1 },
    });
    harness.requestGet.mockResolvedValueOnce({ data: new Blob(['x'], { type: 'application/pdf' }) });

    const wrapper = await clickPrint();

    expect(harness.requestGet).toHaveBeenCalledWith(
      '/F006/print/material-requisition/REQ-9',
      { responseType: 'blob' },
    );
    const anchorCall = appendChildSpy.mock.calls.find(([el]) => el instanceof HTMLAnchorElement);
    expect((anchorCall![0] as HTMLAnchorElement).download).toBe('领料单_MR-DIFFERENT-9.pdf');

    wrapper.unmount();
  });

  it('失败路径: 请求失败必须显示真实错误, ⛔ 不许静默、⛔ 不许显示成功', async () => {
    harness.requestGet.mockRejectedValueOnce(new Error('后端 500'));

    const wrapper = await clickPrint();

    // 必须有真实错误提示 —— 不是静默无提示。
    expect(harness.error, '请求失败却没有任何提示 —— 仓管员会以为点了没反应').toHaveBeenCalledTimes(1);
    expect(harness.error).toHaveBeenCalledWith('打印失败，请稍后重试');

    // ⛔ 不许在失败时也弹「成功」——这是本轮 brief 里点名的缺陷形状(谎报成功)。
    expect(harness.success, '请求失败但仍弹出了成功提示 —— 谎报成功').not.toHaveBeenCalled();

    // ⛔ 下载链路不许被触发 —— 没有 PDF 数据却生成了下载, 同样是一种谎报。
    expect(URL.createObjectURL).not.toHaveBeenCalled();
    expect(clickSpy).not.toHaveBeenCalled();

    wrapper.unmount();
  });

  it('连点两次同一行在第一次请求完成前不重复发起 (loading guard)', async () => {
    let resolveFirst: (v: { data: Blob }) => void = () => {};
    harness.requestGet.mockImplementationOnce(
      () => new Promise((resolve) => { resolveFirst = resolve; }),
    );

    const wrapper = mountList();
    await flushPromises();
    const btn = wrapper.findAll('button').find((b) => b.text() === '打印');
    expect(btn).toBeTruthy();

    await btn!.trigger('click');
    await btn!.trigger('click');
    await flushPromises();

    expect(harness.requestGet, '重复点击在第一次请求挂起时又发了一次 —— 会打两次印').toHaveBeenCalledTimes(1);

    resolveFirst({ data: new Blob(['x'], { type: 'application/pdf' }) });
    await flushPromises();
    wrapper.unmount();
  });
});
