import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus, { ElSelect, ElMessage } from 'element-plus';
import { reactive } from 'vue';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const state = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));
const route = reactive<{ query: Record<string, string> }>({ query: {} });

vi.mock('vue-router', () => ({ useRoute: () => route }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => state.get(...args),
  post: (...args: unknown[]) => state.post(...args),
  put: (...args: unknown[]) => state.put(...args),
}));

import DefectsPage from '../list.vue';

const ok = (data: unknown) => ({ success: true, data });
const source = {
  id: 'inspection-f006',
  batchNumber: 'B-F006-01',
  productName: '冷冻鱼排',
  inspectionDate: '2026-10-23',
  result: 'FAIL',
};

function mountPage() {
  return mount(DefectsPage, { global: { plugins: [ElementPlus] } });
}

async function clickText(wrapper: VueWrapper, text: string) {
  const button = wrapper.findAll('button').find(item => item.text() === text);
  expect(button, text).toBeTruthy();
  await button!.trigger('click');
  await flushPromises();
}

beforeEach(() => {
  vi.clearAllMocks();
  route.query = {};
  state.get.mockImplementation((url: string) => {
    if (url.endsWith('/quality-defects')) {
      return Promise.resolve(ok({ content: [], totalElements: 0 }));
    }
    if (url.endsWith('/processing/quality/inspections')) {
      return Promise.resolve(ok({ content: [source], totalElements: 1 }));
    }
    if (url.endsWith('/processing/quality/inspections/inspection-f006')) {
      return Promise.resolve(ok(source));
    }
    if (url.endsWith('/users/directory')) {
      return Promise.resolve(ok([
        { id: 7, fullName: '李质检', department: '质量部', isActive: true },
        { id: 8, fullName: '停用人员', department: '质量部', isActive: false },
      ]));
    }
    return Promise.resolve(ok([]));
  });
  state.post.mockResolvedValue(ok({ id: 'defect-new' }));
  state.put.mockResolvedValue(ok({ id: 'defect-1' }));
});

describe('S3-11 source and assignee workflow', () => {
  it('inspection page exposes a real source entry that routes the inspection id', () => {
    const inspections = readFileSync(
      resolve(process.cwd(), 'src/views/quality/inspections/list.vue'),
      'utf-8',
    );
    expect(inspections).toContain('登记不良');
    expect(inspections).toContain("path: '/quality/defects'");
    expect(inspections).toContain('inspectionId: String(row.id)');
  });

  it('loads the real inspection source, saves, and refreshes the defect list', async () => {
    route.query = { inspectionId: source.id };
    const wrapper = mountPage();
    await flushPromises();
    await clickText(wrapper, '登记不良');
    expect(wrapper.text()).toContain('B-F006-01');
    expect(wrapper.text()).toContain('冷冻鱼排');
    const sourceSelect = wrapper.findAllComponents(ElSelect)
      .find(select => select.props('placeholder') === '选择真实质检来源')!;
    expect(sourceSelect.props('modelValue')).toBe(source.id);
    await clickText(wrapper, '提交');

    expect(state.post).toHaveBeenCalledWith('/F006/quality-defects', expect.objectContaining({
      qualityInspectionId: source.id,
      defectType: 'OTHER',
      quantity: 1,
    }));
    expect(state.get.mock.calls.filter(call => call[0] === '/F006/quality-defects')).toHaveLength(2);
    wrapper.unmount();
  });

  it('only offers active same-factory names and rejects an old invalid selection before save', async () => {
    state.get.mockImplementation((url: string) => {
      if (url.endsWith('/quality-defects')) {
        return Promise.resolve(ok({
          content: [{
            id: 'defect-1', status: 'OPEN', handlingAction: '返工', assignedTo: 99,
            qualityInspectionId: source.id, defectType: 'OTHER', quantity: 1,
          }],
          totalElements: 1,
        }));
      }
      if (url.endsWith('/users/directory')) {
        return Promise.resolve(ok([{ id: 7, fullName: '李质检', department: '质量部', isActive: true }]));
      }
      return Promise.resolve(ok([]));
    });
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({ close: vi.fn() }));
    const wrapper = mountPage();
    await flushPromises();
    await clickText(wrapper, '分派');
    expect(wrapper.text()).toContain('当前处理人已失效或不属于当前工厂');
    await clickText(wrapper, '提交');
    expect(state.put).not.toHaveBeenCalled();
    expect(warning).toHaveBeenCalledWith('处理人已失效或不属于当前工厂，请重新选择');
    wrapper.unmount();
    warning.mockRestore();
  });

  it('saves a selected active assignee by id and refreshes the list', async () => {
    state.get.mockImplementation((url: string) => {
      if (url.endsWith('/quality-defects')) {
        return Promise.resolve(ok({
          content: [{
            id: 'defect-1', status: 'OPEN', handlingAction: '返工', assignedTo: null,
            qualityInspectionId: source.id, defectType: 'OTHER', quantity: 1,
          }],
          totalElements: 1,
        }));
      }
      if (url.endsWith('/users/directory')) {
        return Promise.resolve(ok([{ id: 7, fullName: '李质检', department: '质量部', isActive: true }]));
      }
      return Promise.resolve(ok([]));
    });
    const wrapper = mountPage();
    await flushPromises();
    await clickText(wrapper, '分派');
    const assigneeSelect = wrapper.findAllComponents(ElSelect).at(-1)!;
    expect(state.get).toHaveBeenCalledWith('/F006/users/directory');
    assigneeSelect.vm.$emit('update:modelValue', 7);
    await clickText(wrapper, '提交');

    expect(state.put).toHaveBeenCalledWith('/F006/quality-defects/defect-1/assign', {
      handlingAction: '返工',
      assignedTo: 7,
    });
    expect(state.get.mock.calls.filter(call => call[0] === '/F006/quality-defects')).toHaveLength(2);
    wrapper.unmount();
  });
});
