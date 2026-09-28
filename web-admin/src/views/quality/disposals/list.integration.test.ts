import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import DisposalList from './list.vue';

type DisposalVm = {
  handleCreate: () => void;
  handleTargetTypeChange: () => void;
  submitDisposal: () => Promise<void>;
  disposalForm: {
    targetType: 'MATERIAL' | 'FINISHED'; batchId: string; disposalType: string;
    quantity: number; reason: string; evidenceImages: string;
  };
  currentBatches: { id: string }[];
  selectedBatchMax: number | null;
};

const get = vi.fn();
const post = vi.fn();
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true }) }));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => get(...args),
  post: (...args: unknown[]) => post(...args), put: vi.fn() }));

describe('disposal finished-goods selector wiring', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    get.mockImplementation(async (url: string) => url.endsWith('eligible-finished-goods')
      ? { success: true, data: [
          { id: 'PARTIAL', batchNumber: 'FG-PARTIAL', productName: '成品', unit: 'kg', availableQuantity: 30 },
          { id: 'SHIPPED', batchNumber: 'FG-SHIPPED', availableQuantity: 0 }
        ] }
      : { success: true, data: [] });
    post.mockResolvedValue({ success: true });
  });

  it('loads the disposal endpoint and submits only the unshipped balance batch', async () => {
    const wrapper = shallowMount(DisposalList, { global: {
      stubs: { 'el-table': { template: '<div />' }, 'el-dialog': { template: '<div />' } },
      directives: { loading: () => {} }
    } });
    const vm = wrapper.vm as unknown as DisposalVm;
    await flushPromises();
    vm.handleCreate();
    vm.disposalForm.targetType = 'FINISHED';
    vm.handleTargetTypeChange();
    await flushPromises();
    expect(get).toHaveBeenCalledWith('/F006/disposal-records/eligible-finished-goods');
    expect(vm.currentBatches.map(batch => batch.id)).toEqual(['PARTIAL']);
    vm.disposalForm.batchId = 'PARTIAL';
    expect(vm.selectedBatchMax).toBe(30);
    vm.disposalForm.disposalType = 'SCRAP';
    vm.disposalForm.quantity = 30;
    vm.disposalForm.reason = '破损';
    vm.disposalForm.evidenceImages = 'https://example.test/evidence.jpg';
    await vm.submitDisposal();
    expect(post).toHaveBeenCalledWith('/F006/disposal-records', expect.objectContaining({
      finishedGoodsBatchId: 'PARTIAL', disposalQuantity: 30
    }));
    expect(post.mock.calls[0][1]).not.toHaveProperty('materialBatchId');
  });
});
