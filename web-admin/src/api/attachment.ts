/**
 * 附件 API — C-ATT-1.
 *
 * 对应后端 AttachmentController. 8 endpoint 完整封装.
 * Base: /api/mobile/{factoryId}/attachments (request baseURL='/api/mobile')
 */
import request from './request';
import { requireSandboxReady, sandboxSession } from '@/demo/f006Sandbox';

/** 关联实体类型 — 后端 Attachment.EntityType 枚举完整复刻 (18 值). */
export type AttachmentEntityType =
  | 'CUSTOMER'
  | 'CUSTOMER_TRACKING'
  | 'PURCHASE_ORDER'
  | 'PURCHASE_RECEIPT'
  | 'CUSTOMER_SUPPLIED_RECEIPT'
  | 'QUALITY_CHECK'
  | 'PRODUCTION_BATCH'
  | 'PAYMENT_VOUCHER'
  | 'INVOICE'
  | 'RD_SAMPLE'
  | 'RECEIPT'
  | 'RETURN_ORDER'
  | 'SHIPMENT'
  | 'WASTAGE_RECORD'
  | 'GROUP_LEADER_REPORT'
  | 'EXPENSE_REPORT'
  | 'LEAVE_REQUEST'
  | 'TIMECLOCK_PHOTO'
  | 'SALES_ORDER'
  | 'INVENTORY'
  | 'ECN'
  | 'CALL_RECORD'
  | 'PRODUCTION_REPORT'
  | 'WORK_PROCESS'
  | 'EQUIPMENT'
  | 'GENERIC';

export type AttachmentFileCategory =
  | 'PHOTO'
  | 'VIDEO'
  | 'DOCUMENT'
  | 'VOUCHER'
  | 'SIGNATURE'
  | 'CONTRACT'
  | 'OTHER';

export interface Attachment {
  id: string;
  factoryId: string;
  entityType: AttachmentEntityType;
  entityId: string;
  fileName: string;
  fileUrl: string;
  thumbnailUrl?: string;
  fileSize: number;
  fileType: string;
  fileCategory: AttachmentFileCategory;
  fileStorage: string;
  fileHash?: string;
  businessTag?: string;
  description?: string;
  uploadedBy: number;
  uploadedAt: string;
  uploadSource: string;
}

/** SOP-only response; the server intentionally omits fileUrl/thumbnailUrl. */
export interface SopAttachmentMetadata {
  id: string;
  factoryId: string;
  entityType: 'WORK_PROCESS' | 'EQUIPMENT';
  entityId: string;
  fileName: string;
  fileSize: number;
  fileType: string;
  fileCategory: AttachmentFileCategory;
  fileStorage: string;
  fileHash?: string;
  businessTag?: string;
  description?: string;
  uploadedAt: string;
  uploadedByActor?: { type: 'FACTORY_USER' | 'PLATFORM_ADMIN'; id: number };
}

export interface RegisterAttachmentRequest {
  entityType: AttachmentEntityType;
  entityId: string;
  fileName: string;
  fileUrl: string;
  thumbnailUrl?: string;
  fileSize: number;
  fileType: string;
  fileCategory?: AttachmentFileCategory;
  fileStorage?: string;
  fileHash?: string;
  businessTag?: string;
  description?: string;
  uploadSource?: string;
}

export interface UpdateAttachmentRequest {
  description?: string;
  businessTag?: string;
  fileCategory?: AttachmentFileCategory;
}

export interface UploadUrlResponse {
  uploadUrl: string;
  fileUrl: string;
  requiredHeaders?: Record<string, string>;
}

function getFactoryId(override?: string): string {
  if (override) return override;
  const raw = localStorage.getItem('cretas_user');
  if (!raw || raw === 'undefined' || raw === 'null') {
    throw new Error('未登录 — 请重新登录');
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new Error('登录态损坏 — 请重新登录');
  }
  // User object 结构: { factoryUser: { factoryId, ... }, ... } (auth store schema)
  // Also tolerate top-level factoryId for platform/legacy callers.
  const p = parsed as { factoryId?: string; factoryUser?: { factoryId?: string } } | null;
  const fid = p?.factoryUser?.factoryId ?? p?.factoryId;
  if (!fid) {
    throw new Error('当前账号未绑定工厂 — 无法查看/上传附件');
  }
  return fid;
}

function basePath(factoryId?: string): string {
  return `/${getFactoryId(factoryId)}/attachments`;
}

/** 1. 列表 — 某实体的所有附件. */
export function listAttachments(
  entityType: 'WORK_PROCESS' | 'EQUIPMENT',
  entityId: string,
  factoryId?: string,
): Promise<{ data: Array<Attachment | SopAttachmentMetadata>; success: boolean }>;
export function listAttachments(
  entityType: AttachmentEntityType,
  entityId: string,
  factoryId?: string,
): Promise<{ data: Attachment[]; success: boolean }>;
export function listAttachments(
  entityType: AttachmentEntityType,
  entityId: string,
  factoryId?: string,
): Promise<{ data: Array<Attachment | SopAttachmentMetadata>; success: boolean }> {
  return request.get(`${basePath(factoryId)}?entityType=${entityType}&entityId=${encodeURIComponent(entityId)}`);
}

/** 2. 详情. */
export function getAttachment(id: string, factoryId?: string): Promise<{ data: Attachment }> {
  return request.get(`${basePath(factoryId)}/${id}`);
}

/** 3. 下载 — 后端 302 重定向. 直接打开 URL 即可. */
export function downloadAttachmentUrl(id: string, factoryId?: string): string {
  return `/api/mobile${basePath(factoryId)}/${id}/download`;
}

/** Fresh authorized link for in-page private-file previews. Never persist the expiring URL. */
export function getAttachmentDownloadUrl(
  id: string,
  factoryId?: string,
): Promise<{ success: boolean; data: { url: string } }> {
  return request.get(`${basePath(factoryId)}/${encodeURIComponent(id)}/download-url`);
}

/** 4. 取 OSS pre-signed PUT URL — 5min. */
export function getUploadUrl(
  fileName: string,
  fileType: string,
  factoryId?: string,
  target?: { entityType: AttachmentEntityType; entityId: string; fileSize?: number },
): Promise<{ data: UploadUrlResponse }> {
  // target 只在 SOP (工序/设备) 上传时带: 后端据此在发签名地址前就校验写权限、目标归属本厂、文件类型。
  return request.post(`${basePath(factoryId)}/upload-url`, target ? { fileName, fileType, ...target } : { fileName, fileType });
}

/** 工序 / 设备 SOP: 后端只收 PDF 与常见图片, 上限 10MB (与 AttachmentPermissionResolver.SOP_* 一致). */
export const SOP_ACCEPT = 'application/pdf,image/jpeg,image/png,image/webp,image/gif';
export const SOP_MAX_BYTES = 10 * 1024 * 1024;
const SOP_MIME = new Set(['application/pdf', 'image/jpeg', 'image/jpg', 'image/png', 'image/webp', 'image/gif']);
export function isSopEntityType(t: AttachmentEntityType): boolean {
  return t === 'WORK_PROCESS' || t === 'EQUIPMENT';
}
/** 选文件后、上传前的本地校验; 返回给人看的原因, 通过返回 null. */
export function checkSopFile(file: { name: string; type: string; size: number }): string | null {
  if (!SOP_MIME.has((file.type || '').toLowerCase())) {
    return `「${file.name}」不是 PDF 或图片，SOP 只支持 PDF、jpg、png、webp、gif`;
  }
  if (file.size <= 0) return `「${file.name}」是空文件`;
  if (file.size > SOP_MAX_BYTES) return `「${file.name}」超过 10MB，请压缩后再传`;
  return null;
}

/** 5. 注册元数据 (OSS 直传完后). */
export function registerAttachment(
  req: RegisterAttachmentRequest,
  factoryId?: string,
): Promise<{ data: Attachment }> {
  return request.post(basePath(factoryId), req);
}

/** 6. 更新 description/businessTag/fileCategory. */
export function updateAttachment(
  id: string,
  req: UpdateAttachmentRequest,
  factoryId?: string,
): Promise<{ data: Attachment }> {
  return request.put(`${basePath(factoryId)}/${id}`, req);
}

/** 7. 软删除. */
export function deleteAttachment(id: string, factoryId?: string): Promise<unknown> {
  return request.delete(`${basePath(factoryId)}/${id}`);
}

/** 8. 批量计数 — 列表页徽章. */
export function batchCountAttachments(
  entityType: AttachmentEntityType,
  entityIds: string[],
  factoryId?: string,
): Promise<{ data: Record<string, number> }> {
  return request.post(`${basePath(factoryId)}/batch-by-entity`, { entityType, entityIds });
}

/**
 * 端到端上传辅助 — 浏览器 File → getUploadUrl → PUT to OSS → register.
 */
export async function uploadAndRegister(
  file: File,
  entityType: AttachmentEntityType,
  entityId: string,
  extras?: { businessTag?: string; description?: string; fileCategory?: AttachmentFileCategory },
  factoryId?: string,
  onProgress?: (percentage: number) => void,
): Promise<Attachment> {
  onProgress?.(2);
  const urlResp = await getUploadUrl(
    file.name, file.type, factoryId,
    isSopEntityType(entityType) ? { entityType, entityId, fileSize: file.size } : undefined,
  );
  const { uploadUrl, fileUrl } = urlResp.data;
  const requiredHeaders = urlResp.data.requiredHeaders ?? {};
  await putFileWithProgress(uploadUrl, file, (percentage) => {
    onProgress?.(Math.min(90, 5 + Math.round(percentage * 0.85)));
  }, requiredHeaders);
  onProgress?.(94);

  const saved = await registerAttachment(
    {
      entityType,
      entityId,
      fileName: file.name,
      fileUrl,
      fileSize: file.size,
      fileType: file.type,
      fileCategory: extras?.fileCategory,
      businessTag: extras?.businessTag,
      description: extras?.description,
      uploadSource: 'WEB',
    },
    factoryId,
  );
  onProgress?.(100);
  return saved.data;
}

export async function putFileWithProgress(
  uploadUrl: string,
  file: File,
  onProgress?: (percentage: number) => void,
  requiredHeaders: Record<string, string> = {},
): Promise<void> {
  const headers = { 'Content-Type': file.type, ...requiredHeaders };
  if (sandboxSession.value) {
    requireSandboxReady();
    if (!/^\/F006\/demo-sandbox-local-files\/[A-Za-z0-9_-]+$/.test(uploadUrl)) {
      throw new Error('隔离演示文件必须上传至当前沙盒文件目录');
    }
    await request.put(uploadUrl, file, { headers, onUploadProgress: (event) => {
      if (event.total && event.total > 0) onProgress?.(Math.round(event.loaded * 100 / event.total));
    } });
    onProgress?.(100);
    return;
  }
  if (typeof XMLHttpRequest === 'undefined') {
    const response = await fetch(uploadUrl, {
      method: 'PUT', headers, body: file,
    });
    if (!response.ok) throw new Error(`OSS 上传失败 HTTP ${response.status}`);
    onProgress?.(100);
    return;
  }
  await new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', uploadUrl, true);
    Object.entries(headers).forEach(([name, value]) => xhr.setRequestHeader(name, value));
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && event.total > 0) {
        onProgress?.(Math.round((event.loaded / event.total) * 100));
      }
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) resolve();
      else reject(new Error(`OSS 上传失败 HTTP ${xhr.status}`));
    };
    xhr.onerror = () => reject(new Error('OSS 上传网络失败'));
    xhr.onabort = () => reject(new Error('OSS 上传已取消'));
    xhr.send(file);
  });
}
