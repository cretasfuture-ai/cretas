import { flushPromises, mount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide } from 'vue';
import { describe, expect, it, vi } from 'vitest';

const getCorrectableReports = vi.fn();
vi.mock('@/api/processProduction', () => ({
  getPendingApprovals: vi.fn().mockResolvedValue({ success: true, data: { content: [] } }),
  getCorrectableReports: (...args: unknown[]) => getCorrectableReports(...args),
  submitReportCorrection: vi.fn(),
  approveReport: vi.fn(), rejectReport: vi.fn(), batchApproveReports: vi.fn(),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true }) }));
vi.mock('@/utils/processSheetUnits', () => ({ displayProcessUnit: (v: unknown) => String(v ?? '') }));
vi.mock('@/components/approval/OpinionInputDialog.vue', () => ({ default: { render: () => null } }));

import ApprovalList from '../list.vue';

const ROWS = Symbol('rows');
const ElTable = defineComponent({
  props: { data: { type: Array, default: () => [] } },
  setup(props, { slots }) {
    provide(ROWS, computed(() => props.data));
    return () => h('div', slots.default?.());
  },
});
const ElTableColumn = defineComponent({
  setup(_props, { slots }) {
    const rows = inject<{ value: Array<Record<string, unknown>> }>(ROWS, { value: [] });
    return () => h('div', rows.value.map(row => slots.default?.({ row })));
  },
});
const ElButton = defineComponent({
  props: { disabled: Boolean },
  emits: ['click'],
  setup(props, { slots, emit }) {
    return () => h('button', { disabled: props.disabled, onClick: () => emit('click') }, slots.default?.());
  },
});
const ElDialog = defineComponent({
  props: { modelValue: Boolean, title: String },
  setup(props, { slots }) {
    return () => props.modelValue
      ? h('section', { role: 'dialog', 'aria-label': props.title }, [slots.default?.(), slots.footer?.()])
      : null;
  },
});
const stubs = {
  ElTable, ElTableColumn, ElButton, ElDialog,
  'el-table': ElTable, 'el-table-column': ElTableColumn,
  'el-button': ElButton, 'el-dialog': ElDialog,
};

describe('报工修正阶段边界挂载回归', () => {
  it('INPUT 行显示阶段和未知单位且不可打开修正', async () => {
    getCorrectableReports.mockResolvedValue({ success: true, data: { content: [{
      id: 88, reportKind: 'INPUT', outputUnit: null, outputQuantity: 12,
      goodQuantity: null, defectQuantity: null, dataCorrected: false,
      reporterName: '操作员', productName: '原料', processCategory: '投料',
    }] } });
    const wrapper = mount(ApprovalList, { global: { stubs } });
    await flushPromises();
    expect(wrapper.text()).toContain('投入');
    expect(wrapper.text()).toContain('单位未知');
    const button = wrapper.findAll('button').find(candidate => candidate.text().includes('核对修正'));
    expect(button, 'INPUT 行必须渲染出核对按钮作为负向对照').toBeDefined();
    expect(button!.attributes('disabled')).toBeDefined();
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it('OUTPUT 行点击后才打开核对弹窗并显示完工阶段和可信单位', async () => {
    getCorrectableReports.mockResolvedValue({ success: true, data: { content: [{
      id: 89, reportKind: 'OUTPUT', outputUnit: 'kg', outputQuantity: 0.81,
      correctionEligible: true, goodQuantity: null, defectQuantity: null,
      dataCorrected: false, reporterName: '操作员', productName: '半成品',
      processCategory: '加工',
    }] } });
    const wrapper = mount(ApprovalList, { global: { stubs } });
    await flushPromises();
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
    const button = wrapper.findAll('button').find(candidate => candidate.text().includes('核对修正'));
    expect(button, 'OUTPUT 正向对照必须能从实际行按钮进入').toBeDefined();
    expect(button!.attributes('disabled')).toBeUndefined();
    await button!.trigger('click');
    const dialog = wrapper.get('[role="dialog"][aria-label="报工核对修正"]');
    expect(dialog.text()).toContain('完工');
    expect(dialog.text()).toContain('kg');
    expect(dialog.text()).toContain('0.81');
    wrapper.unmount();
  });
});
