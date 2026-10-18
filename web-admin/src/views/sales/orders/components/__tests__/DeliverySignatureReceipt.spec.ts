import { describe, expect, it, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus, { ElImage } from 'element-plus';
import DeliverySignatureReceipt from '../DeliverySignatureReceipt.vue';
import { listAttachments, getAttachmentDownloadUrl, type Attachment } from '@/api/attachment';

vi.mock('@/api/attachment', () => ({ listAttachments: vi.fn(), getAttachmentDownloadUrl: vi.fn() }));
const props = { factoryId: 'TEST-A', deliveryId: 'S1', photoUrls: ['https://example.invalid/stored.png'],
  signedByName: '验收签收员', signedAt: '2026-10-18T09:45:37', remark: '测试凭证' };
const attachment: Attachment = { id: 'A1', factoryId: 'TEST-A', entityType: 'SHIPMENT', entityId: 'S1',
  fileName: 'test.png', fileUrl: props.photoUrls[0], fileSize: 100, fileType: 'image/png', fileCategory: 'SIGNATURE',
  fileStorage: 'OSS', uploadedBy: 1, uploadedAt: props.signedAt, uploadSource: 'WEB' };
const open = (overrides = {}) => mount(DeliverySignatureReceipt, { props: { ...props, ...overrides }, global: { plugins: [ElementPlus] } });
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(listAttachments).mockResolvedValue({ success: true, data: [attachment] });
  vi.mocked(getAttachmentDownloadUrl).mockResolvedValue({ success: true, data: { url: 'https://example.invalid/fresh.png?test=1' } });
});

describe('已保存签收凭证只读查看', () => {
  it('shows persisted signer/time/remark and obtains a fresh authorized image link', async () => {
    const wrapper = open(); await flushPromises();
    expect(listAttachments).toHaveBeenCalledWith('SHIPMENT', 'S1', 'TEST-A');
    expect(getAttachmentDownloadUrl).toHaveBeenCalledWith('A1', 'TEST-A');
    expect(wrapper.text()).toContain('验收签收员');
    expect(wrapper.text()).toContain('09:45:37');
    expect(wrapper.text()).toContain('测试凭证');
    expect(wrapper.findComponent(ElImage).props('previewSrcList')).toEqual(['https://example.invalid/fresh.png?test=1']);
    expect(wrapper.find('input[type=file]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('保存凭证并确认签收');
    expect(wrapper.text()).not.toContain('删除');
    wrapper.unmount();
  });
  it.each([{ factoryId: 'OTHER' }, { entityId: 'OTHER' }, { fileCategory: 'PHOTO' },
    { fileUrl: 'https://example.invalid/not-saved.png' }, { entityType: 'INVOICE' }])('rejects unrelated metadata %o', async override => {
    vi.mocked(listAttachments).mockResolvedValue({ success: true, data: [{ ...attachment, ...override } as Attachment] });
    const wrapper = open(); await flushPromises();
    expect(wrapper.text()).toContain('签收凭证加载失败');
    expect(getAttachmentDownloadUrl).not.toHaveBeenCalled();
    expect(wrapper.findComponent(ElImage).exists()).toBe(false);
    wrapper.unmount();
  });
  it('selects the saved proof instead of another image uploaded to the same shipment', async () => {
    vi.mocked(listAttachments).mockResolvedValue({ success: true, data: [
      { ...attachment, id: 'UNRELATED', fileUrl: 'https://example.invalid/not-saved.png' }, attachment,
    ] });
    const wrapper = open(); await flushPromises();
    expect(getAttachmentDownloadUrl).toHaveBeenCalledTimes(1);
    expect(getAttachmentDownloadUrl).toHaveBeenCalledWith('A1', 'TEST-A');
    wrapper.unmount();
  });
  it('surfaces link denial and can retry without mutating business data', async () => {
    vi.mocked(getAttachmentDownloadUrl).mockRejectedValueOnce(new Error('forbidden'));
    const wrapper = open(); await flushPromises();
    expect(wrapper.text()).toContain('签收凭证加载失败');
    await wrapper.get('button').trigger('click'); await flushPromises();
    expect(wrapper.findComponent(ElImage).exists()).toBe(true);
    wrapper.unmount();
  });
  it('does not invent an image for a legacy record without stored proof', async () => {
    const wrapper = open({ photoUrls: [] }); await flushPromises();
    expect(wrapper.text()).toContain('尚未保存签收照片');
    expect(listAttachments).not.toHaveBeenCalled();
    wrapper.unmount();
  });
});
