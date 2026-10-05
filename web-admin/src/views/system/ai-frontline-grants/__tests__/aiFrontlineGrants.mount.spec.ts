/**
 * 一线角色 AI 操作权限页 —— 行为测试 (挂载真组件, 只桩掉网络传输层)。
 *
 * 桩的是 `@/api/request` 的 get/put/del, 而不是 `@/api/aiFrontlineGrants` ——
 * 这样「URL 拼得对不对」由真实 API 模块产出, 断言守的是发出去的那条路径。
 *
 * 覆盖:
 *  1. 默认开放的格子: 开 + 禁用 + 「默认」标记 (并对照: 同页非默认格子是可操作的)
 *  2. 非默认格子: 拨开 → PUT 正确路径; 再拨关 → DELETE 正确路径; 行按响应替换
 *  3. PUT 失败: 请求期间开关先显示为开 (阳性: 证明它真的动过), 失败后回到关
 *  4. 高敏感行: 拨开前先弹确认, 确认后才 PUT; 取消则不发请求
 *  5. 只读 (canWrite=false): 所有开关禁用
 *  另: 搜索 / 只看已开放
 */
import { flushPromises, mount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide } from 'vue';
import type { ComputedRef } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/types/api';
import type { FrontlineGrantIntent, FrontlineGrantsOverview } from '@/api/aiFrontlineGrants';

const harness = vi.hoisted(() => ({
  get: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  confirm: vi.fn(),
  canWrite: true,
}));

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({
    canWrite: (module: string) => module === 'system' && harness.canWrite,
  }),
}));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => harness.get(...args),
  put: (...args: unknown[]) => harness.put(...args),
  del: (...args: unknown[]) => harness.del(...args),
}));
vi.mock('element-plus', () => ({
  ElMessage: {
    success: (...a: unknown[]) => harness.success(...a),
    error: (...a: unknown[]) => harness.error(...a),
    warning: vi.fn(),
    info: vi.fn(),
  },
  ElMessageBox: { confirm: (...a: unknown[]) => harness.confirm(...a) },
}));

import GrantsPage from '../index.vue';

// ── stubs ────────────────────────────────────────────────────────────────

const ROWS_KEY = 'stub-table-rows';

const ElTableStub = defineComponent({
  name: 'ElTable',
  props: { data: { type: Array, default: () => [] } },
  setup(props, { slots }) {
    provide(ROWS_KEY, computed(() => props.data));
    return () => h('div', { class: 'stub-table' }, slots.default?.());
  },
});

/** 每一行调用一次 scoped default slot, 把真实行内容渲染出来。 */
const ElTableColumnStub = defineComponent({
  name: 'ElTableColumn',
  props: { label: { type: String, default: '' } },
  setup(props, { slots }) {
    const rows = inject<ComputedRef<unknown[]>>(ROWS_KEY, computed(() => []));
    return () => h('div', { class: 'stub-col', 'data-label': props.label },
      rows.value.map((row) => h('div', { class: 'stub-row' }, slots.default?.({ row }))));
  },
});

/** 开关 stub: 点一下就按 Element Plus 的语义 emit update:modelValue(!当前值)。 */
const ElSwitchStub = defineComponent({
  name: 'ElSwitch',
  props: {
    modelValue: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false },
    loading: { type: Boolean, default: false },
  },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () => h('button', {
      class: 'stub-switch',
      role: 'switch',
      'aria-checked': String(props.modelValue),
      disabled: props.disabled,
      onClick: () => emit('update:modelValue', !props.modelValue),
    });
  },
});

const ElInputStub = defineComponent({
  name: 'ElInput',
  props: { modelValue: { type: String, default: '' } },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () => h('input', {
      class: 'stub-input',
      value: props.modelValue,
      onInput: (e: Event) => emit('update:modelValue', (e.target as HTMLInputElement).value),
    });
  },
});

const ElCheckboxStub = defineComponent({
  name: 'ElCheckbox',
  props: { modelValue: { type: Boolean, default: false } },
  emits: ['update:modelValue'],
  setup(props, { emit, slots }) {
    return () => h('label', { class: 'stub-checkbox' }, [
      h('input', {
        type: 'checkbox',
        checked: props.modelValue,
        onChange: (e: Event) => emit('update:modelValue', (e.target as HTMLInputElement).checked),
      }),
      slots.default?.(),
    ]);
  },
});

const ElAlertStub = defineComponent({
  name: 'ElAlert',
  props: { title: { type: String, default: '' } },
  setup(props, { slots }) {
    return () => h('div', { class: 'stub-alert' }, [props.title, slots.default?.()]);
  },
});

const PassThrough = (name: string, tag = 'div') => defineComponent({
  name,
  setup(_props, { slots }) {
    return () => h(tag, slots.default?.());
  },
});

const stubs = {
  ElTable: ElTableStub,
  ElTableColumn: ElTableColumnStub,
  ElSwitch: ElSwitchStub,
  ElInput: ElInputStub,
  ElCheckbox: ElCheckboxStub,
  ElAlert: ElAlertStub,
  ElCard: PassThrough('ElCard'),
  ElTag: PassThrough('ElTag', 'span'),
  ElTooltip: PassThrough('ElTooltip', 'span'),
  ElButton: PassThrough('ElButton', 'button'),
};

// ── fixtures ─────────────────────────────────────────────────────────────

const ROLES = [
  { code: 'operator', name: '操作员' },
  { code: 'yield_operator', name: '出成率录入员' },
  { code: 'warehouse_worker', name: '仓库员' },
  { code: 'quality_inspector', name: '质检员' },
];

function intent(partial: Partial<FrontlineGrantIntent> & Pick<FrontlineGrantIntent, 'intentCode'>): FrontlineGrantIntent {
  return {
    intentName: partial.intentCode,
    category: 'PRODUCTION',
    sensitivityLevel: 'LOW',
    description: '',
    defaultOpenRoles: [],
    grantedRoles: [],
    ...partial,
  };
}

const REPORT = intent({
  intentCode: 'PROCESSING_WORK_REPORT', intentName: '报工', sensitivityLevel: 'LOW',
  description: '提交本班次报工', defaultOpenRoles: ['operator'],
});
const ADJUST = intent({
  intentCode: 'INVENTORY_ADJUST', intentName: '库存调整', sensitivityLevel: 'MEDIUM',
  description: '修正库存数量', grantedRoles: ['warehouse_worker'],
});
const RELEASE = intent({
  intentCode: 'MATERIAL_BATCH_RELEASE', intentName: '批次放行', sensitivityLevel: 'HIGH',
  description: '放行原料批次',
});

function overview(): FrontlineGrantsOverview {
  return {
    roles: ROLES.map((r) => ({ ...r })),
    intents: [REPORT, ADJUST, RELEASE].map((i) => ({
      ...i, defaultOpenRoles: [...i.defaultOpenRoles], grantedRoles: [...i.grantedRoles],
    })),
  };
}

async function mountPage() {
  const wrapper = mount(GrantsPage, {
    global: { stubs, directives: { loading: {} } },
  });
  await flushPromises();
  return wrapper;
}

function cellSwitch(wrapper: Awaited<ReturnType<typeof mountPage>>, intentCode: string, roleCode: string) {
  const cell = wrapper.find(`[data-cell="${intentCode}::${roleCode}"]`);
  expect(cell.exists(), `格子 ${intentCode}::${roleCode} 没渲染出来`).toBe(true);
  return { cell, sw: cell.find('[role="switch"]') };
}

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

beforeEach(() => {
  harness.get.mockReset();
  harness.put.mockReset();
  harness.del.mockReset();
  harness.success.mockReset();
  harness.error.mockReset();
  harness.confirm.mockReset();
  harness.canWrite = true;
  harness.get.mockResolvedValue({ success: true, data: overview(), message: 'OK' });
});

// ── tests ────────────────────────────────────────────────────────────────

describe('一线角色 AI 操作权限页', () => {
  it('加载时请求本工厂的 ai-frontline-grants, 列头来自 roles[].name', async () => {
    const wrapper = await mountPage();
    expect(harness.get).toHaveBeenCalledWith('/F006/ai-frontline-grants');
    const labels = wrapper.findAll('.stub-col').map((c) => c.attributes('data-label'));
    expect(labels).toEqual(['操作', '操作员', '出成率录入员', '仓库员', '质检员']);
    // 3 个意图 × 4 个角色 = 12 个格子 —— 证明表格真的按数据渲染了, 后面的断言不是在空表上成立
    expect(wrapper.findAll('[data-cell]')).toHaveLength(12);
  });

  it('默认开放的格子: 开 + 禁用 + 「默认」; 同页的非默认格子可操作 (对照)', async () => {
    const wrapper = await mountPage();
    const def = cellSwitch(wrapper, 'PROCESSING_WORK_REPORT', 'operator');
    expect(def.sw.attributes('aria-checked')).toBe('true');
    expect(def.sw.attributes('disabled')).toBeDefined();
    expect(def.cell.text()).toContain('默认');

    // 对照: 同一行另一个角色不是默认, 不禁用、不带「默认」
    const other = cellSwitch(wrapper, 'PROCESSING_WORK_REPORT', 'warehouse_worker');
    expect(other.sw.attributes('aria-checked')).toBe('false');
    expect(other.sw.attributes('disabled')).toBeUndefined();
    expect(other.cell.text()).not.toContain('默认');

    // 已额外开放 (granted) 的格子显示为开, 但仍可操作
    const granted = cellSwitch(wrapper, 'INVENTORY_ADJUST', 'warehouse_worker');
    expect(granted.sw.attributes('aria-checked')).toBe('true');
    expect(granted.sw.attributes('disabled')).toBeUndefined();

    // 点默认格子什么都不发生
    await def.sw.trigger('click');
    await flushPromises();
    expect(harness.put).not.toHaveBeenCalled();
    expect(harness.del).not.toHaveBeenCalled();
  });

  it('拨开非默认格子 → PUT 对的路径; 再拨关 → DELETE 同一路径; 行按响应替换', async () => {
    const wrapper = await mountPage();
    harness.put.mockResolvedValueOnce({
      success: true,
      data: { ...ADJUST, grantedRoles: ['warehouse_worker', 'operator'] },
      message: 'OK',
    });

    await cellSwitch(wrapper, 'INVENTORY_ADJUST', 'operator').sw.trigger('click');
    await flushPromises();

    expect(harness.put).toHaveBeenCalledTimes(1);
    expect(harness.put.mock.calls[0][0]).toBe('/F006/ai-frontline-grants/operator/INVENTORY_ADJUST');
    expect(harness.confirm).not.toHaveBeenCalled(); // MEDIUM 不弹确认
    expect(cellSwitch(wrapper, 'INVENTORY_ADJUST', 'operator').sw.attributes('aria-checked')).toBe('true');
    expect(harness.success).toHaveBeenCalledTimes(1);
    expect(String(harness.success.mock.calls[0][0])).toContain('操作员');

    harness.del.mockResolvedValueOnce({
      success: true,
      data: { ...ADJUST, grantedRoles: ['warehouse_worker'] },
      message: 'OK',
    });
    await cellSwitch(wrapper, 'INVENTORY_ADJUST', 'operator').sw.trigger('click');
    await flushPromises();

    expect(harness.del).toHaveBeenCalledTimes(1);
    expect(harness.del.mock.calls[0][0]).toBe('/F006/ai-frontline-grants/operator/INVENTORY_ADJUST');
    expect(harness.put).toHaveBeenCalledTimes(1); // 关不是又发一次 PUT
    expect(cellSwitch(wrapper, 'INVENTORY_ADJUST', 'operator').sw.attributes('aria-checked')).toBe('false');
    expect(harness.error).not.toHaveBeenCalled();
  });

  it('PUT 失败: 请求期间先显示为开, 失败后回到关, 并提示错误 (不提示成功)', async () => {
    const wrapper = await mountPage();
    const d = deferred<unknown>();
    harness.put.mockReturnValueOnce(d.promise);

    await cellSwitch(wrapper, 'INVENTORY_ADJUST', 'operator').sw.trigger('click');
    await flushPromises();
    // 阳性: 开关真的动过 —— 否则「最后是关」可能只是「从来没开过」
    expect(cellSwitch(wrapper, 'INVENTORY_ADJUST', 'operator').sw.attributes('aria-checked')).toBe('true');

    d.reject(new ApiError('权限不足', 'FORBIDDEN', 403));
    await flushPromises();

    expect(cellSwitch(wrapper, 'INVENTORY_ADJUST', 'operator').sw.attributes('aria-checked')).toBe('false');
    expect(harness.error).toHaveBeenCalledTimes(1);
    expect(String(harness.error.mock.calls[0][0])).toContain('开放失败');
    expect(harness.success).not.toHaveBeenCalled();
    // 同一行其他格子不受影响
    expect(cellSwitch(wrapper, 'INVENTORY_ADJUST', 'warehouse_worker').sw.attributes('aria-checked')).toBe('true');
  });

  it('PUT 返回 success:false 也回退 (不只是抛异常那一种失败)', async () => {
    const wrapper = await mountPage();
    harness.put.mockResolvedValueOnce({ success: false, message: '后端拒绝', data: undefined });
    await cellSwitch(wrapper, 'INVENTORY_ADJUST', 'operator').sw.trigger('click');
    await flushPromises();
    expect(cellSwitch(wrapper, 'INVENTORY_ADJUST', 'operator').sw.attributes('aria-checked')).toBe('false');
    expect(String(harness.error.mock.calls[0][0])).toContain('后端拒绝');
  });

  it('高敏感行: 拨开时先弹确认 (含角色名), 确认后才 PUT', async () => {
    const wrapper = await mountPage();
    harness.confirm.mockResolvedValueOnce('confirm');
    harness.put.mockResolvedValueOnce({
      success: true, data: { ...RELEASE, grantedRoles: ['quality_inspector'] }, message: 'OK',
    });

    await cellSwitch(wrapper, 'MATERIAL_BATCH_RELEASE', 'quality_inspector').sw.trigger('click');
    await flushPromises();

    expect(harness.confirm).toHaveBeenCalledTimes(1);
    expect(String(harness.confirm.mock.calls[0][0]))
      .toBe('这是高敏感操作（例如放行、销毁、调拨），确定对「质检员」开放吗？');
    expect(harness.put).toHaveBeenCalledTimes(1);
    expect(harness.put.mock.calls[0][0]).toBe('/F006/ai-frontline-grants/quality_inspector/MATERIAL_BATCH_RELEASE');
    // 顺序: 先确认, 后请求
    expect(harness.confirm.mock.invocationCallOrder[0]).toBeLessThan(harness.put.mock.invocationCallOrder[0]);
    expect(cellSwitch(wrapper, 'MATERIAL_BATCH_RELEASE', 'quality_inspector').sw.attributes('aria-checked')).toBe('true');
  });

  it('高敏感行: 取消确认 → 不发请求, 开关保持关', async () => {
    const wrapper = await mountPage();
    harness.confirm.mockRejectedValueOnce('cancel');

    await cellSwitch(wrapper, 'MATERIAL_BATCH_RELEASE', 'operator').sw.trigger('click');
    await flushPromises();

    expect(harness.confirm).toHaveBeenCalledTimes(1);
    expect(harness.put).not.toHaveBeenCalled();
    expect(cellSwitch(wrapper, 'MATERIAL_BATCH_RELEASE', 'operator').sw.attributes('aria-checked')).toBe('false');
  });

  it('只读 (canWrite=false): 所有开关禁用, 并显示只读说明', async () => {
    harness.canWrite = false;
    const wrapper = await mountPage();
    const switches = wrapper.findAll('[role="switch"]');
    expect(switches).toHaveLength(12);
    const enabled = switches.filter((s) => s.attributes('disabled') === undefined);
    expect(enabled).toHaveLength(0);
    expect(wrapper.text()).toContain('你当前只能查看');
  });

  it('可写模式下 (对照): 12 个格子里只有 1 个默认格子是禁用的', async () => {
    const wrapper = await mountPage();
    const switches = wrapper.findAll('[role="switch"]');
    expect(switches).toHaveLength(12);
    expect(switches.filter((s) => s.attributes('disabled') !== undefined)).toHaveLength(1);
    expect(wrapper.text()).not.toContain('你当前只能查看');
  });

  it('搜索按名称或编码过滤; 「只看已开放」去掉一个角色都没开的行', async () => {
    const wrapper = await mountPage();
    const rowCodes = () => wrapper.findAll('[data-intent]').map((n) => n.attributes('data-intent'));
    expect(rowCodes()).toEqual(['PROCESSING_WORK_REPORT', 'INVENTORY_ADJUST', 'MATERIAL_BATCH_RELEASE']);

    await wrapper.find('.stub-input').setValue('放行');
    expect(rowCodes()).toEqual(['MATERIAL_BATCH_RELEASE']);
    await wrapper.find('.stub-input').setValue('inventory');
    expect(rowCodes()).toEqual(['INVENTORY_ADJUST']);
    await wrapper.find('.stub-input').setValue('');

    await wrapper.find('.stub-checkbox input').setValue(true);
    expect(rowCodes()).toEqual(['PROCESSING_WORK_REPORT', 'INVENTORY_ADJUST']);
  });

  it('加载失败: 显示错误, 不渲染表格 (不给假数据)', async () => {
    harness.get.mockReset();
    harness.get.mockRejectedValueOnce(new ApiError('服务异常 (500),请稍后重试', undefined, 500));
    const wrapper = await mountPage();
    expect(wrapper.text()).toContain('加载失败');
    expect(wrapper.findAll('[data-cell]')).toHaveLength(0);
  });
});
