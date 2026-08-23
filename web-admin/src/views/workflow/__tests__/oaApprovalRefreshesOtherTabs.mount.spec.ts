import { flushPromises, mount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide } from 'vue';
import type { ComputedRef } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useWorkspaceStore } from '@/store/modules/workspace';

/**
 * 端到端行为证明: 在**产品真实入口**上点一次「通过」, 别的标签是不是真的失效了。
 *
 * 🔴 2026-08-20 owner 实测:「我在个人 OA 里点击通过以后, 再点到财务管理里面, 还是待审批;
 * 到仓储管理的入库任务与批次, 点进去没有任何东西。但我刷新了一下网页, 它就更新好了。」
 *
 * ⚠️ 与隔壁那条源码闸分工不同, 两条都要:
 *   源码闸   钉「这一行还在不在」(改动被删掉时红)
 *   本文件   钉「点下去之后真的发生了」—— 走的是渲染出来的按钮 + 真实 pinia store,
 *            只桩掉外部 IO (HTTP / 弹窗 / 路由)。
 *
 * ⚠️ el-table 必须是**会渲染行**的桩: shallowMount 下操作列根本不渲染, 那个按钮压根不存在,
 *    「点了没反应」和「点不到」会长成同一个读数。
 */
const harness = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  push: vi.fn(),
  confirm: vi.fn(),
}));

vi.mock('vue-router', async () => {
  const { reactive } = await import('vue');
  const route = reactive({ query: {} as Record<string, string | undefined> });
  return { useRoute: () => route, useRouter: () => ({ push: harness.push }) };
});

vi.mock('@/api/request', () => ({ get: harness.get, post: harness.post }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/utils/errorToast', () => ({ handleCatchError: vi.fn() }));
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), info: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: harness.confirm, prompt: vi.fn() },
}));

import PendingApprovals from '../pending.vue';

const ROWS_KEY = 'stub-table-rows';

/**
 * 把 data 交给下面的列去渲染 —— 只做这一件事, 不模仿 Element Plus 的任何别的行为。
 *
 * ⚠️ provide 的必须是 computed: 行是 onMounted 之后异步取回来的, 直接 provide(props.data)
 *    传下去的是**挂载那一刻**的空数组, 列永远渲染 0 行 —— 「点了没反应」和「压根没按钮」
 *    会长成同一个读数。
 */
const ElTableStub = defineComponent({
  name: 'ElTable',
  props: { data: { type: Array, default: () => [] } },
  setup(props, { slots }) {
    provide(ROWS_KEY, computed(() => props.data));
    return () => h('div', { class: 'stub-table' }, slots.default?.());
  },
});

const ElTableColumnStub = defineComponent({
  name: 'ElTableColumn',
  setup(_props, { slots }) {
    const rows = inject<ComputedRef<unknown[]>>(ROWS_KEY, computed(() => []));
    return () => h('div', rows.value.map((row) => h('div', slots.default?.({ row }))));
  },
});

const ElButtonStub = defineComponent({
  name: 'ElButton',
  emits: ['click'],
  setup(_props, { slots, emit }) {
    return () => h('button', { onClick: () => emit('click') }, slots.default?.());
  },
});

const PassThrough = (name: string) => defineComponent({
  name,
  setup(_props, { slots }) {
    return () => h('div', slots.default?.());
  },
});

function mountPending() {
  return mount(PendingApprovals, {
    global: {
      directives: { loading: {} },
      stubs: {
        ElTable: ElTableStub,
        ElTableColumn: ElTableColumnStub,
        ElButton: ElButtonStub,
        ElCard: PassThrough('ElCard'),
        ElTag: PassThrough('ElTag'),
        ElTooltip: PassThrough('ElTooltip'),
        ElSelect: PassThrough('ElSelect'),
        ElOption: PassThrough('ElOption'),
        ElPagination: PassThrough('ElPagination'),
      },
    },
  });
}

/** 摆出 owner 当时的标签现场: 财务 / 仓储 / 人事 都开着, 人停在个人 OA 上。 */
function layoutTabs(store: ReturnType<typeof useWorkspaceStore>) {
  const open = (path: string, module: string) => store.openRoute({
    path, fullPath: path, title: path, module, query: {},
  });
  store.setScope('F006');
  open('/finance/costs', 'finance');
  open('/warehouse/materials', 'warehouse');
  open('/hr/employees', 'hr');
  open('/workflow/pending', 'dashboard');
}

describe('在个人 OA 点一次「通过」', () => {
  beforeEach(() => {
    sessionStorage.clear();
    setActivePinia(createPinia());
    // ⚠️ harness 是 vi.hoisted 的模块级单例: 不 reset 的话调用次数跨用例累加,
    //    「取消时没发请求」会被上一条用例的调用记录判红 —— 实测踩过。
    [harness.get, harness.post, harness.push, harness.confirm].forEach((fn) => fn.mockReset());
    harness.get.mockResolvedValue({
      success: true,
      data: {
        items: [{
          instanceId: 'inst-1',
          moduleCode: 'PURCHASE_ORDER',
          businessEntityId: 'PO-1',
          currentNodeId: 'finance-approval',
          businessCode: 'PO-20260820-0002',
        }],
        total: 1,
      },
    });
    harness.post.mockResolvedValue({ success: true });
    harness.confirm.mockResolvedValue('confirm');
  });

  it('财务与仓储标签的缓存键都变了 -> 切回去会重新取数, ⛔ 不再是「还是待审批」', async () => {
    const store = useWorkspaceStore();
    layoutTabs(store);
    const financeBefore = store.cacheKeyFor('/finance/costs');
    const warehouseBefore = store.cacheKeyFor('/warehouse/materials');

    const wrapper = mountPending();
    await flushPromises();

    const approve = wrapper.findAll('button').find((btn) => btn.text() === '通过');
    expect(approve, '渲染不出「通过」按钮 —— 闸自己瞎了, 后面每条断言都无意义').toBeTruthy();
    await approve!.trigger('click');
    await flushPromises();

    // 阳性对照: 审批请求真的发出去了 (否则下面的读数在量一次「什么都没发生」)
    expect(harness.post).toHaveBeenCalledWith(
      '/F006/workflow/instances/inst-1/actions',
      expect.objectContaining({ action: 'APPROVE' }),
    );

    expect(store.cacheKeyFor('/finance/costs')).not.toBe(financeBefore);
    expect(store.cacheKeyFor('/warehouse/materials')).not.toBe(warehouseBefore);

    wrapper.unmount();
  });

  it('阴性对照: 人事标签没被牵连 (⛔ 不是把所有标签清一遍)', async () => {
    const store = useWorkspaceStore();
    layoutTabs(store);
    const hrBefore = store.cacheKeyFor('/hr/employees');

    const wrapper = mountPending();
    await flushPromises();
    const approve = wrapper.findAll('button').find((btn) => btn.text() === '通过');
    await approve!.trigger('click');
    await flushPromises();

    expect(store.cacheKeyFor('/hr/employees')).toBe(hrBefore);
    wrapper.unmount();
  });

  it('阴性对照: 审批失败时谁都不失效 (数据没变, 让别的标签白取一次数是纯浪费)', async () => {
    harness.post.mockResolvedValue({ success: false, message: '节点已变更' });
    const store = useWorkspaceStore();
    layoutTabs(store);
    const financeBefore = store.cacheKeyFor('/finance/costs');

    const wrapper = mountPending();
    await flushPromises();
    const approve = wrapper.findAll('button').find((btn) => btn.text() === '通过');
    await approve!.trigger('click');
    await flushPromises();

    expect(harness.post).toHaveBeenCalled();   // 阳性对照: 确实走到了请求
    expect(store.cacheKeyFor('/finance/costs')).toBe(financeBefore);
    wrapper.unmount();
  });

  it('阴性对照: 用户在确认框点「取消」-> 请求没发, 也不失效', async () => {
    harness.confirm.mockRejectedValue(new Error('cancel'));
    const store = useWorkspaceStore();
    layoutTabs(store);
    const financeBefore = store.cacheKeyFor('/finance/costs');

    const wrapper = mountPending();
    await flushPromises();
    const approve = wrapper.findAll('button').find((btn) => btn.text() === '通过');
    await approve!.trigger('click');
    await flushPromises();

    expect(harness.post).not.toHaveBeenCalled();
    expect(store.cacheKeyFor('/finance/costs')).toBe(financeBefore);
    wrapper.unmount();
  });
});
