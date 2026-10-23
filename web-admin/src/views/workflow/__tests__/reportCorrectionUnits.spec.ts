import { flushPromises, mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { computed, defineComponent, h, inject, provide } from 'vue';

const get = vi.fn();
const post = vi.fn();
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => get(...args), post: (...args: unknown[]) => post(...args) }));
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006', currentRole: 'production_manager' }) }));
vi.mock('@/store/modules/workspace', () => ({ useWorkspaceStore: () => ({}) }));
vi.mock('@/utils/errorToast', () => ({ handleCatchError: vi.fn() }));
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), info: vi.fn(), error: vi.fn() }, ElMessageBox: { confirm: vi.fn() } }));

import PendingApprovals from '../pending.vue';

const ROWS = Symbol('rows');
const ElTable = defineComponent({
  props: { data: { type: Array, default: () => [] } },
  setup(props, { slots }) { provide(ROWS, computed(() => props.data)); return () => h('div', slots.default?.()); },
});
const ElTableColumn = defineComponent({
  setup(_props, { slots }) {
    const rows = inject<{ value: Array<Record<string, unknown>> }>(ROWS, { value: [] });
    return () => h('div', rows.value.map(row => slots.default?.({ row })));
  },
});
const ElButton = defineComponent({
  emits: ['click'],
  setup(_props, { slots, emit }) {
    return () => h('button', { onClick: () => emit('click') }, slots.default?.());
  },
});

describe('OA 修正摘要单位挂载回归', () => {
  it('修正待办显示报工阶段和可信单位，缺失单位显示未知', async () => {
    setActivePinia(createPinia());
    get.mockResolvedValue({ success: true, data: { items: [{
      instanceId: 'wf-1', moduleCode: 'PRODUCTION_REPORT_CORRECTION', businessEntityId: 'COR-1',
      businessCode: 'COR-1', reportCorrectionDetails: {
        reportId: 88, processCategory: '包装', reportKind: 'OUTPUT', outputUnit: null,
        reason: '现场复核', before: { outputQuantity: 10 }, after: { outputQuantity: 9 },
      },
    }], totalElements: 1 } });
    const wrapper = mount(PendingApprovals, { global: { stubs: {
      'el-table': ElTable, 'el-table-column': ElTableColumn, 'el-select': true, 'el-option': true,
      'el-pagination': true, 'el-button': ElButton, 'el-tag': true, 'el-tooltip': true,
    } } });
    await flushPromises();
    expect(wrapper.text()).toContain('完工');
    expect(wrapper.text()).toContain('单位未知');
    const confirmButton = wrapper.findAll('button').find(button => button.text().includes('通过'));
    expect(confirmButton, 'OUTPUT OA 行必须真实渲染通过按钮').toBeDefined();
    await confirmButton!.trigger('click');
    await flushPromises();
    const { ElMessageBox } = await import('element-plus');
    expect(ElMessageBox.confirm).toHaveBeenCalled();
    expect((ElMessageBox.confirm as ReturnType<typeof vi.fn>).mock.calls[0]?.[0]).toContain('完工');
    expect((ElMessageBox.confirm as ReturnType<typeof vi.fn>).mock.calls[0]?.[0]).toContain('单位未知');
  });
});
