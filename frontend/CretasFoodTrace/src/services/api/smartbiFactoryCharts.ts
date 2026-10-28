import { apiClient } from './apiClient';
import { API_BASE_URL } from '../../constants/config';
import { requireFactoryId } from '../../utils/factoryIdHelper';
import { useAuthStore } from '../../store/authStore';
import * as FileSystem from 'expo-file-system';
import {
  FactoryChartDetailsRequest,
  FactoryChartDetailsResponse,
  FactoryChartExportFile,
  FactoryChartExportFormat,
  FactoryChartExportPayload,
  FactoryChartSource,
  FactoryChartUpload,
  FactoryRecommendationRequest,
  FactoryRecommendationsResponse,
  FactoryChartSelectionResponse,
} from '../../types/factoryCharts';

const SMARTBI_PREFIX = '/api/smartbi/financial-dashboard';

function trimUrl(value: string): string {
  return value.trim().replace(/\/+$/, '');
}

/** Resolve only company production origins or explicitly local development URLs. */
export function resolveSmartbiApiBaseUrl(configuredUrl: string): string {
  const configured = trimUrl(configuredUrl);
  if (/^https:\/\/admin\.cretaceousfuture\.com\/smartbi-api$/i.test(configured)) {
    return configured;
  }
  if (/^https:\/\/www\.cretaceousfuture\.com(?:\/api)?$/i.test(configured)) {
    return 'https://admin.cretaceousfuture.com/smartbi-api';
  }
  if (/^https:\/\/api\.cretaceousfuture\.com(?:\/api)?$/i.test(configured)) {
    return 'https://admin.cretaceousfuture.com/smartbi-api';
  }
  try {
    const parsed = new URL(configured);
    const hostname = parsed.hostname;
    const privateIpv4 = /^(?:127\.0\.0\.1|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})$/.test(hostname);
    if ((parsed.protocol === 'http:' || parsed.protocol === 'https:') && (hostname === 'localhost' || privateIpv4)) {
      return configured;
    }
  } catch {
    // The explicit failure below is the public configuration error.
  }
  throw new Error('SmartBI API gateway is not configured for this environment');
}

export function smartbiApiBaseUrl(): string {
  return resolveSmartbiApiBaseUrl(API_BASE_URL);
}

function pathFor(path: string): string {
  const base = smartbiApiBaseUrl();
  return base.startsWith('http') ? `${base}${path}` : path;
}

type Identity = { factoryId: string; userId: string; role: string };
function captureIdentity(): Identity {
  const factoryId = requireFactoryId();
  const userId = useAuthStore.getState().getUserId();
  const role = useAuthStore.getState().getUserRole();
  if (userId === null || userId === undefined || !role) throw new Error('SmartBI requires an authenticated user');
  return { factoryId, userId: String(userId), role };
}
function assertIdentity(identity: Identity): void {
  const currentFactory = requireFactoryId();
  const currentUser = useAuthStore.getState().getUserId();
  const currentRole = useAuthStore.getState().getUserRole();
  if (currentFactory !== identity.factoryId || String(currentUser) !== identity.userId || currentRole !== identity.role) throw new Error('SmartBI authentication, role, or factory changed during request');
}

function scoped<T extends { success: boolean; data?: { factoryId?: string; factory_id?: string } }>(
  response: T,
  factoryId: string,
): T {
  const responseFactory = response.data?.factoryId ?? response.data?.factory_id;
  if (response.success !== true || responseFactory !== factoryId) {
    throw new Error('SmartBI response factory scope mismatch');
  }
  return response;
}

export async function getFactoryRecommendations(
  request: FactoryRecommendationRequest,
): Promise<FactoryRecommendationsResponse> {
  const identity = captureIdentity();
  const factoryId = identity.factoryId;
  const body = { ...request, factory_id: factoryId };
  const response = await apiClient.post<FactoryRecommendationsResponse>(
    pathFor(`${SMARTBI_PREFIX}/factory-recommendations`), body,
  );
  assertIdentity(identity);
  return scoped(response, factoryId);
}

export async function getFactoryChartDetails(
  request: FactoryChartDetailsRequest & { factory_id?: string },
): Promise<FactoryChartDetailsResponse> {
  const identity = captureIdentity();
  const factoryId = identity.factoryId;
  const body = { ...request, factory_id: factoryId };
  const response = await apiClient.post<FactoryChartDetailsResponse>(
    pathFor(`${SMARTBI_PREFIX}/factory-details`), body,
  );
  assertIdentity(identity);
  return scoped(response, factoryId);
}

export async function getFactoryChartSelection(
  source: FactoryChartSource,
): Promise<FactoryChartSelectionResponse> {
  const identity = captureIdentity();
  const factoryId = identity.factoryId;
  const response = await apiClient.get<FactoryChartSelectionResponse>(
    pathFor(`${SMARTBI_PREFIX}/factory-selection`),
    { params: { source, factory_id: factoryId } },
  );
  assertIdentity(identity);
  return scoped(response, factoryId);
}

export async function saveFactoryChartSelection(
  source: FactoryChartSource,
  selectedIds: readonly string[],
): Promise<FactoryChartSelectionResponse> {
  const identity = captureIdentity();
  const factoryId = identity.factoryId;
  const response = await apiClient.put<FactoryChartSelectionResponse>(
    pathFor(`${SMARTBI_PREFIX}/factory-selection`),
    { source, factory_id: factoryId, selected_ids: [...selectedIds] },
  );
  assertIdentity(identity);
  return scoped(response, factoryId);
}

export async function getFactoryChartUploads(): Promise<FactoryChartUpload[]> {
  const identity = captureIdentity();
  const factoryId = identity.factoryId;
  const response = await apiClient.get<{ success: boolean; data?: { content?: Array<Record<string, unknown>> } | Array<Record<string, unknown>> }>(
    `/api/mobile/${encodeURIComponent(factoryId)}/smart-bi/uploads`,
    { params: { page: 0, size: 200 } },
  );
  assertIdentity(identity);
  if (response.success !== true) throw new Error('SmartBI upload history request failed');
  const rows = Array.isArray(response.data) ? response.data : response.data?.content;
  if (!Array.isArray(rows)) throw new Error('SmartBI upload history response is malformed');
  return rows
    .filter(upload => String(upload.status || '').toUpperCase() === 'COMPLETED')
    .map(upload => ({ id: Number(upload.id), fileName: upload.fileName }))
    .filter(upload => Number.isInteger(upload.id) && upload.id > 0 && typeof upload.fileName === 'string' && upload.fileName.length > 0) as FactoryChartUpload[];
}

function isBinaryBody(value: unknown): value is ArrayBuffer | Uint8Array | string {
  return value instanceof ArrayBuffer || value instanceof Uint8Array || typeof value === 'string';
}

function bytesToBase64(value: ArrayBuffer | Uint8Array | string): string {
  if (typeof value === 'string') return value;
  const bytes = value instanceof Uint8Array ? value : new Uint8Array(value);
  let binary = '';
  for (let i = 0; i < bytes.length; i += 1) binary += String.fromCharCode(bytes[i]!);
  if (!globalThis.btoa) throw new Error('Base64 encoder is unavailable');
  return globalThis.btoa(binary);
}

function binaryLooksValid(format: FactoryChartExportFormat, value: ArrayBuffer | Uint8Array | string): boolean {
  let bytes: Uint8Array | undefined;
  if (typeof value === 'string') {
    if (!globalThis.atob) return false;
    try {
      const decoded = globalThis.atob(value);
      bytes = Uint8Array.from(decoded, char => char.charCodeAt(0));
    } catch {
      return false;
    }
  } else {
    bytes = value instanceof Uint8Array ? value : new Uint8Array(value);
  }
  if (!bytes || bytes.length < 100) return false;
  if (format === 'pdf') return bytes[0] === 0x25 && bytes[1] === 0x50 && bytes[2] === 0x44 && bytes[3] === 0x46;
  return bytes[0] === 0x50 && bytes[1] === 0x4b && bytes[2] === 0x03 && bytes[3] === 0x04;
}

export async function exportFactoryCharts(
  format: FactoryChartExportFormat,
  payload: FactoryChartExportPayload,
): Promise<FactoryChartExportFile> {
  const identity = captureIdentity();
  const factoryId = identity.factoryId;
  if (payload.factory_id !== factoryId) throw new Error('SmartBI export payload factory scope mismatch');
  const body = { ...payload, factory_id: factoryId };
  const response = await apiClient.post<ArrayBuffer | Uint8Array | string>(
    pathFor(`${SMARTBI_PREFIX}/export-${format}`), body,
    { responseType: 'arraybuffer' },
  );
  assertIdentity(identity);
  if (!isBinaryBody(response) || !binaryLooksValid(format, response)) {
    throw new Error(`SmartBI ${format} export returned invalid binary data`);
  }
  const ext = format === 'excel' ? 'xlsx' : format === 'pdf' ? 'pdf' : 'pptx';
  const mimeType = format === 'excel'
    ? 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    : format === 'pdf' ? 'application/pdf'
      : 'application/vnd.openxmlformats-officedocument.presentationml.presentation';
  const name = `smartbi-${factoryId}-${Date.now()}.${ext}`;
  if (!FileSystem.cacheDirectory) throw new Error('Expo file cache is unavailable');
  const uri = `${FileSystem.cacheDirectory}${name}`;
  const base64 = bytesToBase64(response);
  await FileSystem.writeAsStringAsync(uri, base64, { encoding: FileSystem.EncodingType.Base64 });
  assertIdentity(identity);
  const size = typeof response === 'string'
    ? (globalThis.atob ? globalThis.atob(response).length : 0)
    : response instanceof Uint8Array ? response.length : response.byteLength;
  return { uri, name, mimeType, size, factoryId, userId: identity.userId, role: identity.role };
}

export async function shareFactoryChartFile(file: FactoryChartExportFile): Promise<void> {
  const identity = captureIdentity();
  if (file.factoryId !== identity.factoryId || file.userId !== identity.userId || file.role !== identity.role) throw new Error('SmartBI file owner, role, or factory mismatch');
  const Sharing = await import('expo-sharing');
  if (!(await Sharing.isAvailableAsync())) throw new Error('系统不支持文件分享');
  assertIdentity(identity);
  await Sharing.shareAsync(file.uri, { mimeType: file.mimeType, dialogTitle: file.name });
}
