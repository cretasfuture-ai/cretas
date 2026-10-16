import { describe, expect, it, vi, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus, { ElImage } from 'element-plus';
import AttachmentList from './AttachmentList.vue';
import { listAttachments, type Attachment } from '@/api/attachment';

vi.mock('@/api/attachment', () => ({ listAttachments: vi.fn(), deleteAttachment: vi.fn() }));
afterEach(() => vi.restoreAllMocks());

function attachment(fileName: string, fileType: string): Attachment {
  return { id:'A1', factoryId:'F1', entityType:'PURCHASE_RECEIPT', entityId:'R1',
    fileName, fileType, fileUrl:'https://example.invalid/receipt', fileSize:1292,
    fileCategory:'PHOTO', fileStorage:'OSS', uploadedBy:2,
    uploadedAt:'2026-10-16T17:42:00', uploadSource:'WEB' };
}

describe('receipt attachment preview', () => {
  it.each(['application/pdf', 'application/octet-stream', ''])('opens PHOTO-category PDF as a document (%s)', async fileType => {
    const att=attachment('receipt.PDF',fileType);
    vi.mocked(listAttachments).mockResolvedValue({ success:true, data:[att] });
    const open=vi.spyOn(window,'open').mockReturnValue(null);
    const wrapper=mount(AttachmentList,{props:{entityType:'PURCHASE_RECEIPT',entityId:'R1',factoryId:'F1',readonly:true},global:{plugins:[ElementPlus]}});
    await flushPromises();
    expect(wrapper.findComponent(ElImage).exists()).toBe(false);
    expect(wrapper.get('.thumb-icon').text()).toBe('PDF');
    await wrapper.get('.att-thumb').trigger('click');
    expect(open).toHaveBeenCalledWith(att.fileUrl,'_blank');
    expect(wrapper.emitted('countChange')).toEqual([[1]]);
    wrapper.unmount();
  });

  it('keeps image preview and its actual source for PHOTO PNG', async () => {
    const att=attachment('receipt.png','image/png');
    vi.mocked(listAttachments).mockResolvedValue({ success:true, data:[att] });
    const open=vi.spyOn(window,'open').mockReturnValue(null);
    const wrapper=mount(AttachmentList,{props:{entityType:'PURCHASE_RECEIPT',entityId:'R1',factoryId:'F1'},global:{plugins:[ElementPlus]}});
    await flushPromises();
    expect(wrapper.findComponent(ElImage).props('previewSrcList')).toEqual([att.fileUrl]);
    await wrapper.get('.att-thumb').trigger('click');
    expect(open).not.toHaveBeenCalled();
    wrapper.unmount();
  });
});
