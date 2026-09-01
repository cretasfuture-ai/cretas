// @ts-nocheck
/**
 * attachmentApi 单元测试 — 列表/上传 URL/下载路径契约
 */

import { attachmentApi } from '../../../services/api/attachmentApi';
import { createApiMock, resetApiMock } from '../../utils/mockApiClient';
import MockAdapter from 'axios-mock-adapter';

const DEFAULT_FACTORY_ID = 'CRETAS_2024_001';
const BASE = `/api/mobile/${DEFAULT_FACTORY_ID}/attachments`;

describe('attachmentApi', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = createApiMock();
  });

  afterEach(() => {
    resetApiMock(mock);
  });

  describe('getById', () => {
    it('fetches a single attachment', async () => {
      mock.onGet(`${BASE}/att-1`).reply(200, {
        success: true,
        data: { id: 'att-1', fileName: 'proof.jpg' },
      });

      const row = await attachmentApi.getById('att-1');
      expect(row.id).toBe('att-1');
    });
  });

  describe('list', () => {
    it('fetches attachments for an entity', async () => {
      mock
        .onGet(`${BASE}?entityType=PURCHASE_ORDER&entityId=PO-001`)
        .reply(200, { success: true, data: [{ id: 'att-1', entityId: 'PO-001' }] });

      const rows = await attachmentApi.list('PURCHASE_ORDER', 'PO-001');
      expect(rows).toHaveLength(1);
      expect(rows[0].id).toBe('att-1');
    });
  });

  describe('getUploadUrl', () => {
    it('returns upload and file URLs', async () => {
      mock.onPost(`${BASE}/upload-url`).reply(200, {
        success: true,
        data: {
          uploadUrl: 'https://oss.example/upload?sig=1',
          fileUrl: 'https://oss.example/file.jpg',
        },
      });

      const result = await attachmentApi.getUploadUrl('file.jpg', 'image/jpeg');
      expect(result.uploadUrl).toContain('upload');
      expect(result.fileUrl).toContain('file.jpg');
    });
  });

  describe('downloadUrl', () => {
    it('builds the download endpoint path', () => {
      expect(attachmentApi.downloadUrl('att-99')).toBe(`${BASE}/att-99/download`);
    });
  });

  describe('register', () => {
    it('posts attachment metadata', async () => {
      mock.onPost(BASE).reply(200, {
        success: true,
        data: { id: 'att-new', entityId: 'PO-002', fileName: 'a.jpg' },
      });

      const saved = await attachmentApi.register({
        entityType: 'PURCHASE_ORDER',
        entityId: 'PO-002',
        fileName: 'a.jpg',
        fileUrl: 'https://oss.example/a.jpg',
        fileSize: 100,
        fileType: 'image/jpeg',
      });
      expect(saved.id).toBe('att-new');
    });
  });

  describe('uploadAndRegister', () => {
    it('uses React Native FileReader to register the SHA-256 and byte size', async () => {
      const bytes = Uint8Array.from([10, 20, 30, 40]);
      const blob = {
        size: bytes.byteLength,
      };
      const originalFileReader = global.FileReader;
      const readAsArrayBuffer = jest.fn();
      class MockFileReader {
        error = null;
        result = null;
        onerror = null;
        onload = null;

        readAsArrayBuffer(input) {
          readAsArrayBuffer(input);
          this.result = bytes.buffer;
          this.onload?.();
        }
      }
      global.FileReader = MockFileReader;
      const fetchMock = jest
        .spyOn(global, 'fetch')
        .mockResolvedValueOnce({ blob: async () => blob } as Response)
        .mockResolvedValueOnce({ ok: true } as Response);
      mock.onPost(`${BASE}/upload-url`).reply(200, {
        success: true,
        data: {
          uploadUrl: 'https://oss.example/upload?sig=1',
          fileUrl: 'https://oss.example/file.jpg',
        },
      });
      mock.onPost(BASE).reply(200, {
        success: true,
        data: { id: 'att-hashed', fileName: 'file.jpg' },
      });

      await attachmentApi.uploadAndRegister(
        { uri: 'file:///capture.jpg', name: 'file.jpg', type: 'image/jpeg', size: 999 },
        'QUALITY_CHECK',
        'qc-task-1',
      );

      const registerPayload = JSON.parse(mock.history.post[1]?.data ?? '{}');
      expect(registerPayload.fileSize).toBe(4);
      expect(registerPayload.fileHash).toBe(
        '5f53c0ff07ba5d9a330e68c95dabb1a9bc49e29f9ed53f6fa7c6d99abb000050',
      );
      expect(readAsArrayBuffer).toHaveBeenCalledWith(blob);
      expect(fetchMock).toHaveBeenLastCalledWith(
        'https://oss.example/upload?sig=1',
        expect.objectContaining({ method: 'PUT', body: blob }),
      );
      fetchMock.mockRestore();
      global.FileReader = originalFileReader;
    });
  });

  describe('update', () => {
    it('updates attachment metadata', async () => {
      mock.onPut(`${BASE}/att-1`).reply(200, {
        success: true,
        data: { id: 'att-1', description: 'updated' },
      });

      const row = await attachmentApi.update('att-1', { description: 'updated' });
      expect(row.description).toBe('updated');
    });
  });

  describe('delete', () => {
    it('soft-deletes an attachment', async () => {
      mock.onDelete(`${BASE}/att-del`).reply(200, { success: true });
      await expect(attachmentApi.delete('att-del')).resolves.toBeUndefined();
    });
  });

  describe('batchCount', () => {
    it('returns per-entity attachment counts', async () => {
      mock.onPost(`${BASE}/batch-by-entity`).reply(200, {
        success: true,
        data: { 'PO-001': 2, 'PO-002': 0 },
      });

      const counts = await attachmentApi.batchCount('PURCHASE_ORDER', ['PO-001', 'PO-002']);
      expect(counts['PO-001']).toBe(2);
      expect(counts['PO-002']).toBe(0);
    });
  });
});
