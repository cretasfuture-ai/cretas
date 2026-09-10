import { describe, expect, it, vi, beforeEach } from 'vitest';
import { shallowMount, flushPromises } from '@vue/test-utils';
import { readApplyChangesResult } from '../applyChangesResult';
import { ApiError } from '@/types/api';
import type { ConfigDiff } from '@/types/canvas';

const api = vi.hoisted(() => ({ aiApplyDiffs: vi.fn() }));
vi.mock('@/api/canvasApi', async importOriginal => ({ ...await importOriginal<typeof import('@/api/canvasApi')>(), aiApplyDiffs: api.aiApplyDiffs }));
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: vi.fn() }) }));
vi.mock('../composables/useCanvasEditor', async () => {
  const { ref } = await import('vue');
  return { useCanvasEditor: () => ({ factoryId: ref('LOCAL_B05'), selectedModule: ref(''), activeTab: ref('workflow'),
    dirtyCount: ref(0), leftCollapsed: ref(false), rightCollapsed: ref(false), isOnboarding: ref(false),
    inFlightAction: ref(null), toggleLeft: vi.fn(), toggleRight: vi.fn(), enterFocusMode: vi.fn(),
    exitFocusMode: vi.fn(), loadVersion: vi.fn(), applyResponsive: vi.fn(), clearDirty: vi.fn() }) };
});
import CanvasEditor from '../index.vue';

function result(statuses: string[]) {
  return { operationId: 'local-operation', total: statuses.length,
    applied: statuses.filter(s => s === 'SUCCESS').length, reused: statuses.filter(s => s === 'REUSED').length,
    items: statuses.map((status, index) => ({ index, status, message: status })) };
}
interface EditorVm {
  pendingChanges: ConfigDiff[];
  handleAIDiff: (diffs: ConfigDiff[]) => void;
  applyChanges: () => Promise<void>;
  applyResultMessage: string;
}

describe('receipt shape gate', () => {
  it.each(['missing', 'duplicate', 'out-of-range', 'fractional', 'count', 'applied', 'status'])(
    'quarantines the entire ambiguous receipt: %s', kind => {
      const data = result(['SUCCESS', 'FAILURE']);
      if (kind === 'missing') data.items.pop();
      if (kind === 'duplicate') data.items[1]!.index = 0;
      if (kind === 'out-of-range') data.items[1]!.index = 2;
      if (kind === 'fractional') data.items[1]!.index = 0.5;
      if (kind === 'count') data.total = 3;
      if (kind === 'applied') data.applied = 2;
      if (kind === 'status') data.items[1]!.status = 'UNRECOGNIZED';
      expect(readApplyChangesResult(data, 2).items.map(i => i.status)).toEqual(['UNKNOWN', 'UNKNOWN']);
    });
  it('uses indices rather than response order', () => {
    const data = result(['SUCCESS', 'FAILURE']);
    data.items.reverse();
    expect(readApplyChangesResult(data, 2).items[0]!.status).toBe('SUCCESS');
  });
  it('does not coerce array-shaped statuses into valid strings', () => {
    const data = { operationId: 'test', total: 1, applied: 0, reused: 0,
      items: [{ index: 0, status: ['SUCCESS'], message: '' }] };
    expect(readApplyChangesResult(data, 1).items[0]!.status).toBe('UNKNOWN');
  });
});

describe('batch consumer prevents replay', () => {
  beforeEach(() => vi.clearAllMocks());
  it('allows a new enable-disable-enable proposal while blocking the old proposal', async () => {
    api.aiApplyDiffs.mockResolvedValue({ data: result(['SUCCESS']) });
    const wrapper = shallowMount(CanvasEditor);
    await flushPromises();
    const vm = wrapper.vm as unknown as EditorVm;
    for (const enabled of [true, false, true]) {
      const proposal = [{ type: 'FIELD_CHANGE', path: 'canvas_test', after: { enabled } }] as ConfigDiff[];
      vm.handleAIDiff(proposal);
      await vm.applyChanges();
      vm.handleAIDiff(proposal);
      expect(vm.pendingChanges).toEqual([]);
      await vm.applyChanges();
    }
    expect(api.aiApplyDiffs).toHaveBeenCalledTimes(3);
    wrapper.unmount();
  });
  const diffs = [0, 1, 2, 3].map(index => ({ type: 'FIELD_CHANGE', path: 'canvas_test', after: { index }, description: `change ${index}` })) as ConfigDiff[];
  it('retries only FAILURE, never SUCCESS, REUSED or UNKNOWN', async () => {
    const receipt = result(['SUCCESS', 'REUSED', 'FAILURE', 'UNKNOWN']);
    api.aiApplyDiffs.mockRejectedValueOnce(new ApiError('partial', undefined, undefined, undefined, receipt));
    api.aiApplyDiffs.mockResolvedValueOnce({ data: result(['SUCCESS']) });
    const wrapper = shallowMount(CanvasEditor);
    await flushPromises();
    const vm = wrapper.vm as unknown as EditorVm;
    vm.handleAIDiff(diffs);
    await vm.applyChanges();
    expect(vm.pendingChanges).toEqual([diffs[2]]);
    expect(vm.applyResultMessage).toContain('UNKNOWN');
    vm.handleAIDiff(diffs);
    expect(vm.pendingChanges).toEqual([diffs[2]]);
    await vm.applyChanges();
    expect(api.aiApplyDiffs.mock.calls[1]![1]).toHaveLength(1);
    expect(api.aiApplyDiffs.mock.calls[1]![1][0].params).toEqual({ index: 2 });
    expect(vm.pendingChanges).toEqual([]);
    wrapper.unmount();
  });
  it('network ambiguity removes the batch from execution without claiming rollback', async () => {
    api.aiApplyDiffs.mockRejectedValue(new Error('connection lost'));
    const wrapper = shallowMount(CanvasEditor);
    await flushPromises();
    const vm = wrapper.vm as unknown as EditorVm;
    vm.handleAIDiff(diffs);
    await vm.applyChanges();
    vm.handleAIDiff(diffs);
    await vm.applyChanges();
    expect(api.aiApplyDiffs).toHaveBeenCalledTimes(1);
    expect(vm.pendingChanges).toEqual([]);
    expect(vm.applyResultMessage).toContain('执行结果不明');
    wrapper.unmount();
  });
});
