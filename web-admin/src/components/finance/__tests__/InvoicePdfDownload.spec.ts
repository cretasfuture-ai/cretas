import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus, { ElMessage } from 'element-plus';
import { readFileSync } from 'node:fs';
import InvoicePdfDownload from '../InvoicePdfDownload.vue';
import { get } from '@/api/request';
vi.mock('@/api/request', () => ({ get: vi.fn() }));
const target = { opener: {}, location: { href: '' }, close: vi.fn() };
const open = () => mount(InvoicePdfDownload, { props: { factoryId: 'TEST-A', invoiceId: 'INV-1', fileName: 'test.pdf' }, global: { plugins: [ElementPlus] } });
beforeEach(() => {
  vi.restoreAllMocks(); vi.mocked(get).mockReset(); target.location.href = ''; target.close.mockReset();
  vi.spyOn(window, 'open').mockReturnValue(target as unknown as Window);
  vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: vi.fn() }));
  vi.mocked(get).mockResolvedValue({ success: true, data: { url: 'https://example.invalid/fresh.pdf' } });
});
describe('private issued invoice PDF download', () => {
  it('gets an authorized fresh link on every click, without exposing a stored raw href', async () => {
    const w = open(); expect(w.text()).toContain('test.pdf'); expect(w.find('a[href]').exists()).toBe(false);
    await w.get('button').trigger('click'); await flushPromises();
    expect(get).toHaveBeenCalledWith('/TEST-A/finance/invoices/INV-1/download-url');
    expect(target.location.href).toBe('https://example.invalid/fresh.pdf'); expect(target.opener).toBeNull();
    await w.get('button').trigger('click'); await flushPromises(); expect(get).toHaveBeenCalledTimes(2); w.unmount();
  });
  it('closes a denied download and permits retry', async () => {
    vi.mocked(get).mockRejectedValueOnce(new Error('forbidden'));
    const w = open(); await w.get('button').trigger('click'); await flushPromises();
    expect(target.close).toHaveBeenCalledOnce(); expect(target.location.href).toBe(''); expect(ElMessage.error).toHaveBeenCalled();
    await w.get('button').trigger('click'); await flushPromises(); expect(target.location.href).toContain('fresh.pdf'); w.unmount();
  });
  it('reports a blocked popup without requesting a link', async () => {
    vi.mocked(window.open).mockReturnValue(null); const w = open(); await w.get('button').trigger('click');
    expect(get).not.toHaveBeenCalled(); expect(ElMessage.error).toHaveBeenCalled(); w.unmount();
  });
  it('rejects an empty link', async () => {
    vi.mocked(get).mockResolvedValue({ success: true, data: { url: '' } }); const w = open();
    await w.get('button').trigger('click'); await flushPromises(); expect(target.close).toHaveBeenCalledOnce(); expect(target.location.href).toBe(''); w.unmount();
  });
  it('discards a link if factory context changes while the request is pending', async () => {
    let finish: (v: { success: boolean; data: { url: string } }) => void = () => {};
    vi.mocked(get).mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    const w = open(); await w.get('button').trigger('click'); await w.setProps({ factoryId: 'TEST-B' });
    finish({ success: true, data: { url: 'https://example.invalid/old.pdf' } }); await flushPromises();
    expect(target.close).toHaveBeenCalledOnce(); expect(target.location.href).toBe(''); w.unmount();
  });
  it('mounts the protected downloader in both real invoice entry points', () => {
    for (const path of ['../../../views/sales/orders/detail.vue', '../../../views/finance/invoices/list.vue']) {
      const source = readFileSync(new URL(path, import.meta.url), 'utf8');
      expect(source).toContain('<InvoicePdfDownload v-if="row.invoicePdfUrl" :factory-id="factoryId" :invoice-id="row.id" :file-name="row.invoiceFileName" />');
      expect(source).not.toContain(':href="row.invoicePdfUrl"');
    }
  });
});
