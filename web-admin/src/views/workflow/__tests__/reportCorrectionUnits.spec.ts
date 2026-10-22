import { flushPromises, mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
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
      ElTable, ElTableColumn, 'el-table': ElTable, 'el-table-column': ElTableColumn, ElCard: true, ElSelect: true, ElOption: true,
      ElPagination: true, ElButton: true, ElTag: true, ElTooltip: true,
    } } });
    await flushPromises();
    expect(wrapper.exists()).toBe(true);
    const source = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../pending.vue'), 'utf8');
    expect(source).toContain('reportCorrectionKindLabel(row.reportCorrectionDetails.reportKind)');
    expect(source).toContain("row.reportCorrectionDetails.outputUnit || '单位未知'");
  });
});
