/**
 * 可复用 Skill 面板 —— 行为测试 (挂载真组件, 只桩掉网络传输层 `@/api/request`)。
 *
 * 守的是「界面上不出现后端代码」：工具名、参数类型、运行状态都显示中文；
 * 不认识的状态显示「结果未知」而不是原始代码，也不说成成功。
 */
import { flushPromises, mount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide } from 'vue';
import type { ComputedRef } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { paramTypeLabel, runStatus, toolLabel, toolsText } from '../labels';

const harness = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));

vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => harness.get(...args),
  post: (...args: unknown[]) => harness.post(...args),
}));
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}));

import ReusableSkillsPanel from '../ReusableSkillsPanel.vue';

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
  ElInput: PassThrough('ElInput'),
  ElInputNumber: PassThrough('ElInputNumber'),
  ElCheckbox: PassThrough('ElCheckbox'),
  ElSwitch: PassThrough('ElSwitch'),
  ElAlert: PassThrough('ElAlert'),
  ElDatePicker: PassThrough('ElDatePicker'),
};

const RUNS = [
  { id: 'run-ok', factoryId: 'F006', userId: 7, skillName: 'expired-materials', savedSkillId: null,
    status: 'SUCCEEDED', createdAt: '2026-10-29T09:00:00',
    steps: [{ tool: 'material_expired_query', descriptorVersion: '1.0.0', params: { limit: 5 },
      gatewayStatus: 'SUCCEEDED', auditEventId: 'a1' }] },
  { id: 'run-new', factoryId: 'F006', userId: 7, skillName: 'expired-materials', savedSkillId: null,
    status: 'SOMETHING_NEW', createdAt: '2026-10-29T08:00:00',
    steps: [{ tool: 'material_stock_summary', descriptorVersion: '1.0.0', params: {},
      gatewayStatus: 'SOMETHING_NEW', auditEventId: 'a2' }] },
];

beforeEach(() => {
  vi.clearAllMocks();
  harness.get.mockImplementation((url: string) => {
    if (url === '/F006/skills/runs') return Promise.resolve({ success: true, data: RUNS });
    if (url === '/F006/skills/saved') return Promise.resolve({ success: true, data: [] });
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
});

async function mountPanel() {
  const wrapper = mount(ReusableSkillsPanel, {
    props: { skills: [] },
    global: { stubs, directives: { loading: {} } },
  });
  await flushPromises();
  return wrapper;
}

describe('可复用 Skill 面板', () => {
  it('运行记录的工具和状态用中文, 不认识的状态显示结果未知', async () => {
    const wrapper = await mountPanel();
    const text = wrapper.text();
    expect(text).toContain('已过期原料查询');
    expect(text).toContain('原料库存汇总');
    expect(text).toContain('成功');
    expect(text).toContain('结果未知');
    for (const code of ['material_expired_query', 'material_stock_summary', 'SOMETHING_NEW', 'SUCCEEDED']) {
      expect(text, code).not.toContain(code);
    }
  });

  it('保存时参数类型显示中文', async () => {
    const wrapper = await mountPanel();
    const save = wrapper.findAll('button').find((b) => b.text().trim() === '保存为可复用');
    expect(save).toBeDefined();
    await save!.trigger('click');
    await flushPromises();
    const typeColumn = wrapper.findAll('[data-label="类型"]').map((col) => col.text()).join('');
    expect(typeColumn).toContain('整数');
    expect(typeColumn).not.toContain('INTEGER');
  });
});

describe('可复用 Skill 的中文说法', () => {
  it('工具 / 类型 / 状态', () => {
    expect(toolLabel('alert_rule_toggle')).toBe('告警规则启用/停用');
    expect(toolLabel('no_such_tool')).toBe('其他工具');
    expect(toolsText([])).toBe('无');
    expect(paramTypeLabel('DATE')).toBe('日期');
    expect(paramTypeLabel('WHATEVER')).toBe('其他');
    expect(runStatus('DENIED').label).toBe('被拒绝');
    expect(runStatus(undefined).label).toBe('结果未知');
  });
});
