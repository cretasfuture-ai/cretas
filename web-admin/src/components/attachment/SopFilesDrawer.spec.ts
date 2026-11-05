import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus, { ElMessageBox } from 'element-plus';
import SopFilesDrawer from './SopFilesDrawer.vue';
import { canManageProcessSop } from './sopPermissions';
import {
  listAttachments, deleteAttachment, getAttachmentDownloadUrl, checkSopFile, type Attachment,
} from '@/api/attachment';

vi.mock('@/api/attachment', async (importOriginal) => {
  const real = await importOriginal<typeof import('@/api/attachment')>();
  return {
    ...real,
    listAttachments: vi.fn(),
    deleteAttachment: vi.fn(),
    uploadAndRegister: vi.fn(),
    getAttachmentDownloadUrl: vi.fn(),
  };
});

function att(id: string, fileName: string, fileType = 'application/pdf'): Attachment {
  return {
    id, factoryId: 'F1', entityType: 'WORK_PROCESS', entityId: 'wp-1', fileName, fileType,
    fileUrl: `https://oss.invalid/F1/attachments/${fileName}`, fileSize: 2048, fileCategory: 'DOCUMENT',
    fileStorage: 'OSS', uploadedBy: 1, uploadedAt: '2026-11-04T09:00:00', uploadSource: 'WEB',
  };
}

function mountDrawer(canWrite: boolean) {
  return mount(SopFilesDrawer, {
    props: { modelValue: true, entityType: 'WORK_PROCESS', entityId: 'wp-1', targetName: '切片', canWrite, factoryId: 'F1' },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  });
}
const q = (testid: string) => document.body.querySelector(`[data-testid="${testid}"]`);

beforeEach(() => {
  vi.mocked(listAttachments).mockReset();
  vi.mocked(deleteAttachment).mockReset();
  vi.mocked(getAttachmentDownloadUrl).mockReset();
});
afterEach(() => {
  document.body.innerHTML = '';
  vi.restoreAllMocks();
});

describe('SopFilesDrawer', () => {
  it('lists the SOP files of this work process and queries by WORK_PROCESS + id', async () => {
    vi.mocked(listAttachments).mockResolvedValue({ success: true, data: [att('A1', '切片标准.pdf'), att('A2', '示意图.png', 'image/png')] });
    const w = mountDrawer(true);
    await flushPromises();
    expect(listAttachments).toHaveBeenCalledWith('WORK_PROCESS', 'wp-1', 'F1');
    expect(q('sop-list')?.textContent).toContain('切片标准.pdf');
    expect(q('sop-row-A2')?.textContent).toContain('图片');
    expect(document.body.textContent).toContain('SOP 文件 — 切片');
    w.unmount();
  });

  it('read-only user sees the list and open buttons but no upload / delete', async () => {
    vi.mocked(listAttachments).mockResolvedValue({ success: true, data: [att('A1', 'a.pdf')] });
    const w = mountDrawer(false);
    await flushPromises();
    expect(q('sop-open-A1')).not.toBeNull();
    expect(q('sop-upload-button')).toBeNull();
    expect(q('sop-delete-A1')).toBeNull();
    w.unmount();
  });

  it('shows the load failure reason with a retry instead of an empty list', async () => {
    vi.mocked(listAttachments).mockRejectedValueOnce(new Error('无权查看 [WORK_PROCESS] 附件'));
    const w = mountDrawer(true);
    await flushPromises();
    expect(q('sop-load-error')?.textContent).toContain('无权查看 [WORK_PROCESS] 附件');
    expect(q('sop-empty')).toBeNull();
    vi.mocked(listAttachments).mockResolvedValueOnce({ success: true, data: [att('A1', 'a.pdf')] });
    (q('sop-load-error')?.querySelector('button') as HTMLButtonElement).click();
    await flushPromises();
    expect(q('sop-load-error')).toBeNull();
    expect(q('sop-row-A1')).not.toBeNull();
    w.unmount();
  });

  it('delete asks for confirmation first: cancel keeps the file, confirm deletes exactly that file', async () => {
    vi.mocked(listAttachments).mockResolvedValue({ success: true, data: [att('A1', 'a.pdf')] });
    const confirm = vi.spyOn(ElMessageBox, 'confirm');
    const w = mountDrawer(true);
    await flushPromises();

    confirm.mockRejectedValueOnce('cancel');
    (q('sop-delete-A1') as HTMLButtonElement).click();
    await flushPromises();
    expect(confirm).toHaveBeenCalledTimes(1);
    expect(String(confirm.mock.calls[0][0])).toContain('切片');
    expect(String(confirm.mock.calls[0][0])).toContain('a.pdf');
    expect(deleteAttachment).not.toHaveBeenCalled();

    confirm.mockResolvedValueOnce('confirm' as never);
    vi.mocked(deleteAttachment).mockResolvedValue({});
    (q('sop-delete-A1') as HTMLButtonElement).click();
    await flushPromises();
    expect(deleteAttachment).toHaveBeenCalledTimes(1);
    expect(deleteAttachment).toHaveBeenCalledWith('A1', 'F1');
    w.unmount();
  });

  it('opens a freshly signed URL (never the raw private OSS address)', async () => {
    vi.mocked(listAttachments).mockResolvedValue({ success: true, data: [att('A1', 'a.pdf')] });
    vi.mocked(getAttachmentDownloadUrl).mockResolvedValue({ success: true, data: { url: 'https://oss.invalid/signed?sig=1' } });
    const open = vi.spyOn(window, 'open').mockReturnValue(null);
    const w = mountDrawer(false);
    await flushPromises();
    (q('sop-open-A1') as HTMLButtonElement).click();
    await flushPromises();
    expect(getAttachmentDownloadUrl).toHaveBeenCalledWith('A1', 'F1');
    expect(open).toHaveBeenCalledWith('https://oss.invalid/signed?sig=1', '_blank', 'noopener');
    expect(open).not.toHaveBeenCalledWith(expect.stringContaining('/F1/attachments/a.pdf'), expect.anything(), expect.anything());
    w.unmount();
  });
});

describe('checkSopFile (local pre-check mirrors the backend whitelist)', () => {
  it.each([
    ['a.pdf', 'application/pdf', 100, null],
    ['a.png', 'image/png', 100, null],
    ['a.webp', 'image/webp', 100, null],
  ])('accepts %s', (name, type, size, expected) => {
    expect(checkSopFile({ name, type, size })).toBe(expected);
  });

  it.each([
    ['a.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 100],
    ['a.mp4', 'video/mp4', 100],
    ['a.exe', 'application/x-msdownload', 100],
    ['a.pdf', '', 100],
  ])('rejects %s (%s) with a reason', (name, type, size) => {
    expect(checkSopFile({ name, type, size })).toContain(name);
  });

  it('rejects empty and over-10MB files', () => {
    expect(checkSopFile({ name: 'e.pdf', type: 'application/pdf', size: 0 })).toContain('空文件');
    expect(checkSopFile({ name: 'big.pdf', type: 'application/pdf', size: 10 * 1024 * 1024 + 1 })).toContain('10MB');
    expect(checkSopFile({ name: 'ok.pdf', type: 'application/pdf', size: 10 * 1024 * 1024 })).toBeNull();
  });
});

describe('canManageProcessSop (same role set as the backend resolver)', () => {
  it.each(['factory_super_admin', 'production_manager', 'workshop_supervisor'])('%s may manage', (role) => {
    expect(canManageProcessSop(role, true)).toBe(true);
  });
  it.each(['operator', 'yield_operator', 'group_leader', 'team_leader', 'dispatcher', 'equipment_admin'])(
    '%s may not, even with production write', (role) => {
      expect(canManageProcessSop(role, true)).toBe(false);
    },
  );
  it('needs production write even for a whitelisted role', () => {
    expect(canManageProcessSop('production_manager', false)).toBe(false);
  });
});
