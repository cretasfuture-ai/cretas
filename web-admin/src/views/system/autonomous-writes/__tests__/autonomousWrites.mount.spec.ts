/**
 * 定时任务写入页 —— 行为测试 (挂载真组件, 只桩掉网络传输层 `@/api/request`)。
 *
 * 覆盖:
 *  1. 新工厂没有开关记录: 显示「已关闭 / 还没有打开过」(默认关闭, 与后端 fail-closed 一致)
 *  2. 打开总开关: 先弹原因框, 确认后 PUT 正确路径和请求体, 状态变为「已打开」; 取消则不发请求
 *  3. 委托授权列表: 中文工具名 / 固定参数 / 状态; 撤销先确认, 再 POST revoke 路径
 *  4. 最近的写入运行: 被拦下的运行显示中文原因, 页面上不出现后端枚举码
 */
import { flushPromises, mount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide } from 'vue';
import type { ComputedRef } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { paramsText, runOutcome, toolLabel } from '../labels';

const harness = vi.hoisted(() => ({
  get: vi.fn(),
  put: vi.fn(),
  post: vi.fn(),
  success: vi.fn(),
  prompt: vi.fn(),
  confirm: vi.fn(),
}));

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
}));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => harness.get(...args),
  put: (...args: unknown[]) => harness.put(...args),
  post: (...args: unknown[]) => harness.post(...args),
}));
vi.mock('element-plus', () => ({
  ElMessage: { success: (...a: unknown[]) => harness.success(...a), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
  ElMessageBox: {
    prompt: (...a: unknown[]) => harness.prompt(...a),
    confirm: (...a: unknown[]) => harness.confirm(...a),
  },
}));

import AutonomousWritesPage from '../index.vue';

const ROWS_KEY = 'stub-table-rows';
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
  props: { label: { type: String, default: '' }, prop: { type: String, default: '' } },
  setup(props, { slots }) {
    const rows = inject<ComputedRef<Record<string, unknown>[]>>(ROWS_KEY, computed(() => []));
    return () => h('div', { class: 'stub-col', 'data-label': props.label },
      rows.value.map((row) => h('div', { class: 'stub-row' },
        slots.default ? slots.default({ row }) : String(row[props.prop] ?? ''))));
  },
});
const PassThrough = (name: string, tag = 'div') => defineComponent({
  name,
  setup(_props, { slots }) {
    return () => h(tag, [slots.header?.(), slots.default?.(), slots.footer?.()]);
  },
});
const stubs = {
  ElTable: ElTableStub,
  ElTableColumn: ElTableColumnStub,
  ElCard: PassThrough('ElCard'),
  ElTag: PassThrough('ElTag', 'span'),
  ElButton: PassThrough('ElButton', 'button'),
  ElDialog: PassThrough('ElDialog'),
  ElForm: PassThrough('ElForm'),
  ElFormItem: PassThrough('ElFormItem'),
  ElSelect: PassThrough('ElSelect'),
  ElOption: PassThrough('ElOption'),
  ElInputNumber: PassThrough('ElInputNumber'),
  ElDatePicker: PassThrough('ElDatePicker'),
};

const SWITCH_URL = '/F006/config/v2/autonomous-writes/switch';
const GRANT = {
  id: 'g1', sourceType: 'SCHEDULER', sourceRef: '28', toolName: 'alert_rule_toggle', runAsUserId: 7,
  descriptorVersion: '2.0.0', maxRuns: 30, usedRuns: 2, expiresAt: '2026-11-30T00:00:00', grantedBy: 7,
  grantedAt: '2026-10-29T10:00:00', revokedAt: null, revokedBy: null, state: 'ACTIVE',
};
const CONFIG = {
  id: 28, factoryId: 'F006', taskCode: 'night-rule-off', cronExpression: '0 0 22 * * ?', enabled: true,
  toolOrMethod: 'alert_rule_toggle', params: { ruleId: 'r-1', enabled: false }, runAsUserId: 7,
};
const RUNS = [
  { runId: 'u1', configId: 28, taskCode: 'night-rule-off', toolName: 'alert_rule_toggle',
    startedAt: '2026-10-29T22:00:00', finishedAt: '2026-10-29T22:00:01', status: 'FAILED',
    rawStatus: 'WRITES_SUSPENDED', effectiveStatus: 'FAILED', error: 'switched off (audit a1)' },
  { runId: 'u2', configId: 28, taskCode: 'night-rule-off', toolName: 'alert_rule_toggle',
    startedAt: '2026-10-28T22:00:00', finishedAt: '2026-10-28T22:00:01', status: 'FAILED',
    rawStatus: 'GRANT_DENIED', effectiveStatus: 'FAILED', error: 'no usable grant (audit a2)' },
];

function routeGet(switchView: Record<string, unknown>) {
  harness.get.mockImplementation((url: string) => {
    if (url === SWITCH_URL) return Promise.resolve({ success: true, data: switchView });
    if (url === '/F006/config/v2/autonomous-grants') return Promise.resolve({ success: true, data: [GRANT] });
    if (url === '/F006/config/v2/scheduler') return Promise.resolve({ success: true, data: [CONFIG] });
    if (url === '/F006/config/v2/scheduler/night-rule-off/runs') {
      return Promise.resolve({ success: true, data: { content: RUNS, totalElements: 2 } });
    }
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
}

async function mountPage() {
  const wrapper = mount(AutonomousWritesPage, {
    global: { stubs, directives: { loading: {} } },
  });
  await flushPromises();
  return wrapper;
}

function buttonByText(wrapper: Awaited<ReturnType<typeof mountPage>>, text: string) {
  const button = wrapper.findAll('button').find((b) => b.text().trim() === text);
  expect(button, `button ${text}`).toBeDefined();
  return button!;
}

beforeEach(() => {
  vi.clearAllMocks();
  routeGet({ factoryId: 'F006', writesEnabled: false, reason: null, changedBy: null, changedAt: null });
});

describe('定时任务写入页', () => {
  it('新工厂没有开关记录时显示已关闭 (默认关闭)', async () => {
    const wrapper = await mountPage();
    expect(wrapper.find('[data-testid="switch-state"]').text()).toBe('已关闭');
    expect(wrapper.text()).toContain('还没有打开过');
    expect(buttonByText(wrapper, '打开').exists()).toBe(true);
  });

  it('打开总开关先填原因, 确认后 PUT 并显示已打开; 取消不发请求', async () => {
    const wrapper = await mountPage();
    harness.prompt.mockRejectedValueOnce('cancel');
    await buttonByText(wrapper, '打开').trigger('click');
    await flushPromises();
    expect(harness.put).not.toHaveBeenCalled();

    harness.prompt.mockResolvedValueOnce({ value: '试点开启' });
    harness.put.mockResolvedValueOnce({ success: true, data: {
      factoryId: 'F006', writesEnabled: true, reason: '试点开启', changedBy: 7, changedAt: '2026-10-29T09:00:00' } });
    await buttonByText(wrapper, '打开').trigger('click');
    await flushPromises();
    expect(harness.put).toHaveBeenCalledWith(SWITCH_URL, { writesEnabled: true, reason: '试点开启' });
    expect(wrapper.find('[data-testid="switch-state"]').text()).toBe('已打开');
    expect(wrapper.text()).toContain('用户 #7');
    expect(wrapper.text()).toContain('试点开启');
  });

  it('授权列表用中文显示, 撤销先确认再 POST revoke', async () => {
    const wrapper = await mountPage();
    const text = wrapper.text();
    expect(text).toContain('告警规则启用/停用');
    expect(text).toContain('night-rule-off');
    expect(text).toContain('设为停用');
    expect(text).toContain('生效中');
    expect(text).toContain('2 / 30');

    harness.confirm.mockResolvedValueOnce('confirm');
    harness.post.mockResolvedValueOnce({ success: true, data: { ...GRANT, state: 'REVOKED' } });
    await buttonByText(wrapper, '撤销').trigger('click');
    await flushPromises();
    expect(harness.confirm).toHaveBeenCalled();
    expect(harness.post).toHaveBeenCalledWith('/F006/config/v2/autonomous-grants/g1/revoke', {});
  });

  it('被拦下的运行显示中文原因, 页面不出现后端枚举码', async () => {
    const wrapper = await mountPage();
    const text = wrapper.text();
    expect(text).toContain('定时任务写入总开关没有打开或已关闭');
    expect(text).toContain('没有可用的委托授权');
    for (const code of ['WRITES_SUSPENDED', 'GRANT_DENIED', 'ACTIVE', 'alert_rule_toggle', 'SCHEDULER']) {
      expect(text, code).not.toContain(code);
    }
  });
});

describe('定时任务写入页的中文说法', () => {
  it('未识别的运行结果按结果未知处理, 从不说成成功', () => {
    expect(runOutcome('SOMETHING_NEW').label).toBe('结果未知');
    expect(runOutcome(null).label).toBe('结果未知');
    expect(runOutcome('OUTCOME_UNKNOWN').label).toBe('结果未知');
    expect(runOutcome('SUCCESS').label).toBe('已执行');
  });

  it('固定参数与工具名用中文', () => {
    expect(paramsText({ ruleId: 'r-1', enabled: true })).toBe('规则 r-1，设为启用');
    expect(paramsText(null)).toBe('无');
    expect(toolLabel('alert_rule_toggle')).toBe('告警规则启用/停用');
    expect(toolLabel('unknown_tool')).toBe('其他工具');
  });
});
