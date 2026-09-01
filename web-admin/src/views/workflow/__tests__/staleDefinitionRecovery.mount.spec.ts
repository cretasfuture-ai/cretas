import { flushPromises, mount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide } from 'vue';
import type { ComputedRef } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

/**
 * 缺陷 27-A —— 「审批人点通过，界面毫无反应」的行为闸。
 *
 * <h3>生产现场</h3>
 * LIUSHANMEN 的 `lsm009` 点「通过」，反复点都毫无反应。浏览器 Network 里是
 * `POST .../actions → 409 WORKFLOW_BOUND_DEFINITION_CHANGED`（×2），驳回也是同一个 409
 * ⇒ 通过与驳回两条路都堵死，而**界面什么都不说**。
 *
 * <h3>为什么会静默（两层各自以为对方会说话）</h3>
 * ① `request.ts` 的 `isVanillaOptimisticLock = 409 && !actionHint && !hintTarget`
 *    把这条 409 的 toast 整条吞掉；
 * ② `handleCatchError` 见 `err.status` 有值就闭嘴，认为拦截器已经显示过了。
 * ⇒ 合起来是**完全的沉默**。
 *
 * <h3>这组测试守的是行为，不是措辞</h3>
 * 判据是「用户被告知了，并且拿到了下一步」，⛔ 不是「弹窗文案长什么样」。
 * 阴性对照有两条：非管理员**不该**看到恢复入口（否则只是把死胡同往后挪一格），
 * 以及别的 409 **不该**被这条路径截走。
 */
const harness = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  push: vi.fn(),
  confirm: vi.fn(),
  prompt: vi.fn(),
  alert: vi.fn(),
  handleCatchError: vi.fn(),
  hasRole: vi.fn(),
  success: vi.fn(),
}));

vi.mock('vue-router', async () => {
  const { reactive } = await import('vue');
  const route = reactive({ query: {} as Record<string, string | undefined> });
  return { useRoute: () => route, useRouter: () => ({ push: harness.push }) };
});

vi.mock('@/api/request', () => ({ get: harness.get, post: harness.post }));
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006', hasRole: harness.hasRole }),
}));
vi.mock('@/utils/errorToast', () => ({ handleCatchError: harness.handleCatchError }));
vi.mock('element-plus', () => ({
  ElMessage: { success: harness.success, info: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: harness.confirm, prompt: harness.prompt, alert: harness.alert },
}));

import PendingApprovals from '../pending.vue';

const ROWS_KEY = 'stub-table-rows';

/** ⚠️ 必须 provide computed: 行是 onMounted 之后异步取回的，否则列永远渲染 0 行，
 *  「点了没反应」和「压根没有按钮」会长成同一个读数。 */
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

/** 后端那条 409 的**真实**形状 —— 含 errorCode，前端就是靠它分辨的。 */
function staleDefinition409() {
  return Object.assign(new Error('该单据绑定的审批流已被修改，无法继续审批'), {
    status: 409,
    code: 'WORKFLOW_BOUND_DEFINITION_CHANGED',
    actionHint: '请联系工厂管理员，对该单据执行「按当前审批流重新发起」后再审批',
  });
}

async function clickApprove() {
  const wrapper = mountPending();
  await flushPromises();
  const approve = wrapper.findAll('button').find((btn) => btn.text() === '通过');
  expect(approve, '渲染不出「通过」按钮 —— 闸自己瞎了，后面每条断言都无意义').toBeTruthy();
  await approve!.trigger('click');
  await flushPromises();
  return wrapper;
}

describe('缺陷 27-A: 审批流被改过之后点「通过」', () => {
  beforeEach(() => {
    sessionStorage.clear();
    setActivePinia(createPinia());
    Object.values(harness).forEach((fn) => fn.mockReset());
    harness.get.mockResolvedValue({
      success: true,
      data: {
        items: [{
          instanceId: 'inst-stuck',
          moduleCode: 'PURCHASE_ORDER',
          businessEntityId: 'PO-1',
          currentNodeId: 'finance-approval',
          businessCode: 'PO-20260901-0001',
        }],
        total: 1,
      },
    });
    harness.confirm.mockResolvedValue('confirm');
    // 默认: 点通过就撞上那条 409
    harness.post.mockRejectedValue(staleDefinition409());
  });

  it('管理员: 被明确告知 + 拿到恢复入口, 确认后真的调了 restart-binding', async () => {
    harness.hasRole.mockReturnValue(true);
    harness.prompt.mockResolvedValue({ value: '画布改过审批人角色，按新定义重走' });
    harness.post
      .mockRejectedValueOnce(staleDefinition409())      // 通过 → 409
      .mockResolvedValueOnce({ success: true });        // 重新发起 → 成功

    const wrapper = await clickApprove();

    // ⛔ 最要害的一条: 用户必须被告知。原来这里是彻底的沉默。
    expect(harness.prompt, '撞上 409 却什么都没弹 —— 又回到「点了没反应」').toHaveBeenCalled();
    expect(harness.post).toHaveBeenCalledWith(
      '/F006/workflow/instances/inst-stuck/restart-binding',
      { reason: '画布改过审批人角色，按新定义重走', confirm: true },
    );
    expect(harness.success).toHaveBeenCalled();
    wrapper.unmount();
  });

  it('阴性对照: 非管理员只被告知原因与找谁, ⛔ 不给一个点了必 403 的恢复按钮', async () => {
    harness.hasRole.mockReturnValue(false);

    const wrapper = await clickApprove();

    expect(harness.alert, '非管理员同样必须被告知为什么点不动').toHaveBeenCalled();
    expect(harness.prompt, '不该向没有权限的人提供恢复入口').not.toHaveBeenCalled();
    const restartCalls = harness.post.mock.calls
      .filter(([url]) => String(url).includes('restart-binding'));
    expect(restartCalls, '非管理员不该发出恢复请求').toHaveLength(0);
    wrapper.unmount();
  });

  it('管理员选「暂不处理」: 不发恢复请求, 也不再退回那条无意义的兜底 toast', async () => {
    harness.hasRole.mockReturnValue(true);
    harness.prompt.mockRejectedValue(new Error('cancel'));

    const wrapper = await clickApprove();

    const restartCalls = harness.post.mock.calls
      .filter(([url]) => String(url).includes('restart-binding'));
    expect(restartCalls).toHaveLength(0);
    expect(harness.handleCatchError,
      '已经明确告知过了, 不该再叠一条「审批操作失败」').not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('阴性对照: 别的 409 不被这条路径截走, 仍交给既有错误处理', async () => {
    harness.hasRole.mockReturnValue(true);
    harness.post.mockRejectedValue(Object.assign(new Error('并发审批冲突'), {
      status: 409,
      code: 'OPTIMISTIC_LOCK',
    }));

    const wrapper = await clickApprove();

    expect(harness.prompt, '把不相干的 409 也当成定义变更 —— 闸过宽').not.toHaveBeenCalled();
    expect(harness.handleCatchError).toHaveBeenCalled();
    wrapper.unmount();
  });
});
