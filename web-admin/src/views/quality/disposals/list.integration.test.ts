import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import DisposalList from './list.vue';
import { getUploadUrl, putFileWithProgress } from '@/api/attachment';

type DisposalVm = {
  handleCreate: () => void;
  handleTargetTypeChange: () => void;
  submitDisposal: () => Promise<void>;
  disposalForm: {
    targetType: 'MATERIAL' | 'FINISHED'; batchId: string; disposalType: string; reasonCategory: string;
    quantity: number; reason: string; evidenceImages: string;
    customerConfirmedBy: string; customerConfirmedAt: string; customerConfirmationEvidence: string;
  };
  selectedCustomerOwned: boolean;
  currentBatches: { id: string }[];
  selectedBatchMax: number | null;
  createDisabled: boolean;
  handleEvidencePhotoChange: (event: Event) => Promise<void>;
  evidenceUploading: boolean;
};

const get = vi.fn();
const post = vi.fn();
const put = vi.fn();
const confirm = vi.hoisted(() => vi.fn());
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true, canAccess: () => true }) }));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => get(...args),
  post: (...args: unknown[]) => post(...args), put: (...args: unknown[]) => put(...args) }));
const routeState = reactive<{ query: Record<string, string> }>({ query: {} });
const router = { push: vi.fn() };
vi.mock('vue-router', () => ({ useRoute: () => routeState, useRouter: () => router }));
vi.mock('@/api/attachment', () => ({ getUploadUrl: vi.fn(), putFileWithProgress: vi.fn() }));
vi.mock('@/api/factoryRecall', () => ({ getFactoryRecall: vi.fn() }));
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus');
  return { ...actual, ElMessageBox: { ...actual.ElMessageBox, confirm } };
});

describe('disposal finished-goods selector wiring', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    confirm.mockReset();
    routeState.query = {};
    vi.mocked(getUploadUrl).mockReset();
    vi.mocked(putFileWithProgress).mockReset();
    get.mockImplementation(async (url: string) => url.endsWith('eligible-finished-goods')
      ? { success: true, data: [
          { id: 'PARTIAL', batchNumber: 'FG-PARTIAL', productName: '成品', unit: 'kg', availableQuantity: 30 },
          { id: 'SHIPPED', batchNumber: 'FG-SHIPPED', availableQuantity: 0 }
        ] }
      : { success: true, data: [] });
    post.mockResolvedValue({ success: true });
    put.mockResolvedValue({ success: true });
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
    // 不手动设处置方式: 表单默认必须是后端批准认的 SCRAP (S3-10 曾因表单只给原因类别而永远批不了)
    expect(vm.disposalForm.disposalType).toBe('SCRAP');
    vm.disposalForm.reasonCategory = '损坏';
    vm.disposalForm.quantity = 30;
    vm.disposalForm.reason = '破损';
    vm.disposalForm.evidenceImages = 'https://example.test/evidence.jpg';
    await vm.submitDisposal();
    expect(post).toHaveBeenCalledWith('/F006/disposal-records', expect.objectContaining({
      finishedGoodsBatchId: 'PARTIAL', disposalQuantity: 30, disposalType: 'SCRAP',
      disposalReason: '【损坏】破损'
    }));
    expect(post.mock.calls[0][1]).not.toHaveProperty('materialBatchId');
  });

  it('customer-owned returned goods need customer confirmation and may be handed back', async () => {
    get.mockImplementation(async (url: string) => url.endsWith('eligible-finished-goods')
      ? { success: true, data: [
          { id: 'TOLL-RTN', batchNumber: 'RTN-RT-1-1', productName: '卤牛肉', unit: '盒', availableQuantity: 16, customerOwned: true },
          { id: 'PARTIAL', batchNumber: 'FG-PARTIAL', availableQuantity: 30, customerOwned: false }
        ] }
      : { success: true, data: [] });
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
    vm.disposalForm.batchId = 'TOLL-RTN';
    expect(vm.selectedCustomerOwned).toBe(true);
    vm.disposalForm.disposalType = 'RETURN_TO_CUSTOMER';
    vm.disposalForm.reasonCategory = '质量问题';
    vm.disposalForm.quantity = 16;
    vm.disposalForm.reason = '召回退回';
    vm.disposalForm.evidenceImages = 'https://example.test/goods.jpg';
    await vm.submitDisposal();
    expect(post).not.toHaveBeenCalled();

    vm.disposalForm.customerConfirmedBy = '客户王经理';
    vm.disposalForm.customerConfirmedAt = '2026-10-28';
    vm.disposalForm.customerConfirmationEvidence = 'https://example.test/signed.jpg';
    await vm.submitDisposal();
    expect(post).toHaveBeenCalledWith('/F006/disposal-records', expect.objectContaining({
      finishedGoodsBatchId: 'TOLL-RTN', disposalType: 'RETURN_TO_CUSTOMER',
      customerConfirmedBy: '客户王经理', customerConfirmedAt: '2026-10-28',
      customerConfirmationEvidence: 'https://example.test/signed.jpg'
    }));

    // 换回公司货: 退还客户不再可选, 自动回到报废, 也不再带客户确认
    post.mockClear();
    vm.disposalForm.batchId = 'PARTIAL';
    await flushPromises();
    expect(vm.selectedCustomerOwned).toBe(false);
    expect(vm.disposalForm.disposalType).toBe('SCRAP');
    vm.disposalForm.quantity = 1;
    await vm.submitDisposal();
    expect(post.mock.calls[0][1]).not.toHaveProperty('customerConfirmedBy');
  });

  it('passes status, keyword and exact finished-goods context without overloading type', async () => {
    routeState.query = { finishedGoodsBatchId: 'PARTIAL', disposalId: '17', recallId: 'recall-1', batchId: '7' };
    get.mockImplementation(async (url: string) => {
      if (url.endsWith('eligible-finished-goods')) return { success: true, data: [{ id: 'PARTIAL', availableQuantity: 30 }] };
      if (url.endsWith('/17')) return { success: true, data: { id: 17, finishedGoodsBatchId: 'PARTIAL' } };
      if (url.includes('disposal-records')) return { success: true, data: [], totalElements: 0 };
      return { success: true, data: [] };
    });
    const wrapper = shallowMount(DisposalList, { global: { stubs: { 'el-table': { template: '<div />' }, 'el-dialog': { template: '<div />' } }, directives: { loading: () => {} } } });
    const vm = wrapper.vm as unknown as DisposalVm & { searchForm: { keyword: string; status: string } };
    await flushPromises();
    vm.searchForm.keyword = '17';
    vm.searchForm.status = 'PENDING';
    await (vm as unknown as { handleSearch: () => Promise<void> }).handleSearch();
    const listCalls = get.mock.calls.filter(([url]) => String(url) === '/F006/disposal-records');
    const listCall = listCalls[listCalls.length - 1];
    expect(listCall?.[1].params).toMatchObject({ status: 'PENDING', keyword: '17', finishedGoodsBatchId: 'PARTIAL' });
    expect(listCall?.[1].params).not.toHaveProperty('type');
    wrapper.unmount();
  });

  it('blocks creation when route-selected finished goods has no eligible balance', async () => {
    routeState.query = { finishedGoodsBatchId: 'SHIPPED' };
    const wrapper = shallowMount(DisposalList, { global: { stubs: { 'el-table': { template: '<div />' }, 'el-dialog': { template: '<div />' } }, directives: { loading: () => {} } } });
    const vm = wrapper.vm as unknown as DisposalVm;
    await flushPromises();
    expect(vm.createDisabled).toBe(true);
    vm.handleCreate();
    expect(vm.disposalForm.batchId).toBe('SHIPPED');
    wrapper.unmount();
  });

  it('uploads a selected evidence photo through the signed URL and keeps URL compatibility', async () => {
    const wrapper = shallowMount(DisposalList, { global: { stubs: { 'el-table': { template: '<div />' }, 'el-dialog': { template: '<div />' } }, directives: { loading: () => {} } } });
    const vm = wrapper.vm as unknown as DisposalVm;
    vi.mocked(getUploadUrl).mockResolvedValue({ data: { uploadUrl: 'https://oss.test/put', fileUrl: 'https://cdn.test/photo.jpg' } });
    vi.mocked(putFileWithProgress).mockResolvedValue();
    const file = new File(['photo'], 'photo.jpg', { type: 'image/jpeg' });
    await vm.handleEvidencePhotoChange({ target: { files: [file], value: 'selected' } } as unknown as Event);
    expect(getUploadUrl).toHaveBeenCalledWith('photo.jpg', 'image/jpeg', 'F006');
    expect(putFileWithProgress).toHaveBeenCalledWith('https://oss.test/put', file, expect.any(Function));
    expect(vm.disposalForm.evidenceImages).toContain('https://cdn.test/photo.jpg');
    expect(vm.evidenceUploading).toBe(false);
    wrapper.unmount();
  });

  it('refreshes eligible balance after approval and fails closed while it is loading', async () => {
    routeState.query = { finishedGoodsBatchId: 'PARTIAL', recallId: '4', batchId: '10' };
    let eligibleReads = 0;
    get.mockImplementation(async (url: string) => {
      if (url.endsWith('eligible-finished-goods')) {
        eligibleReads += 1;
        return { success: true, data: eligibleReads === 1 ? [{ id: 'PARTIAL', availableQuantity: 10 }] : [] };
      }
      return { success: true, data: [] };
    });
    confirm.mockResolvedValue(true);
    const wrapper = shallowMount(DisposalList, { global: {
      stubs: { 'el-table': { template: '<div />' }, 'el-dialog': { template: '<div />' } },
      directives: { loading: () => {} },
    } });
    const vm = wrapper.vm as unknown as DisposalVm & { handleApprove: (row: Record<string, unknown>) => Promise<void> };
    await flushPromises();
    expect(vm.createDisabled).toBe(false);
    put.mockResolvedValue({ success: true });
    await vm.handleApprove({ id: 3, status: 'PENDING', disposalType: 'SCRAP', disposalQuantity: 10 });
    expect(eligibleReads).toBe(2);
    expect(vm.createDisabled).toBe(true);
    wrapper.unmount();
  });

  it('does not approve a previous batch after route changes during confirmation', async () => {
    routeState.query = { finishedGoodsBatchId: 'PARTIAL' };
    let resolve!: (value: boolean) => void;
    confirm.mockReturnValue(new Promise<boolean>(done => { resolve = done; }));
    const wrapper = shallowMount(DisposalList, { global: {
      stubs: { 'el-table': { template: '<div />' }, 'el-dialog': { template: '<div />' } },
      directives: { loading: () => {} },
    } });
    const vm = wrapper.vm as unknown as DisposalVm & { handleApprove: (row: Record<string, unknown>) => Promise<void> };
    await flushPromises();
    const approving = vm.handleApprove({ id: 3, status: 'PENDING', disposalType: 'SCRAP', disposalQuantity: 10 });
    routeState.query = { finishedGoodsBatchId: 'SHIPPED' };
    await flushPromises();
    resolve(true); await approving;
    expect(put).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('keeps evidence URLs empty after a failed photo upload and clears uploading state', async () => {
    const wrapper = shallowMount(DisposalList, { global: {
      stubs: { 'el-table': { template: '<div />' }, 'el-dialog': { template: '<div />' } },
      directives: { loading: () => {} },
    } });
    const vm = wrapper.vm as unknown as DisposalVm;
    vi.mocked(getUploadUrl).mockResolvedValue({ data: { uploadUrl: 'https://oss.test/put', fileUrl: 'https://cdn.test/photo.jpg' } });
    vi.mocked(putFileWithProgress).mockRejectedValue(new Error('OSS 上传失败'));
    const file = new File(['photo'], 'photo.jpg', { type: 'image/jpeg' });
    await vm.handleEvidencePhotoChange({ target: { files: [file], value: 'selected' } } as unknown as Event);
    expect(vm.disposalForm.evidenceImages).toBe('');
    expect(vm.evidenceUploading).toBe(false);
    wrapper.unmount();
  });
});
