import { get, post } from './request';
import { ApiError, type ApiResponse } from '@/types/api';

export type PreparationPurpose = 'training' | 'acceptance';
export type PhotoVerdict = 'good' | 'defect' | 'uncertain';
export type VisionPreparationStage = 'dataset' | 'train';
export type VisionPreparationJobStatus = 'queued' | 'running' | 'succeeded' | 'failed' | 'blocked';

export interface VisionPhotoBox {
  label: string;
  x0: number;
  y0: number;
  x1: number;
  y1: number;
}

export interface VisionPreparationPhoto {
  photoId: string;
  fileName: string;
  purpose: PreparationPurpose;
  captureGroup: string;
  width: number;
  height: number;
  verdict: PhotoVerdict | null;
  reviewed: boolean;
  boxes: VisionPhotoBox[];
  transcription: string;
  imageSha256: string;
  reviewer?: string;
  reviewedAt?: string;
}

export interface VisionPreparationStandard {
  passCriteria: string;
  failCriteria: string;
  maxMissRate: number;
  maxFalseAlarmRate: number;
  confirmed: boolean;
  confirmedBy: string | null;
  confirmedAt: string | null;
}

export interface VisionPreparationState {
  revision: number;
  taskFamily: string;
  photos: VisionPreparationPhoto[];
  standard: VisionPreparationStandard | null;
  blockers: string[];
  readyToSubmit: boolean;
  canContinueTraining?: boolean;
  submission: {
    submissionId: string;
    snapshotSha256: string;
    status: 'waiting_configuration' | 'queued';
    message: string;
    stage?: VisionPreparationStage;
    jobStatus?: VisionPreparationJobStatus;
    executionProjectId?: string;
    jobId?: string;
  } | null;
  limits: {
    maxBytes: number;
    maxPhotos: number;
    minTrainingPhotos: number;
    minAcceptancePhotos: number;
    maxStoredBytes?: number;
    storedBytes?: number;
  };
  labels: Array<{ id: string; name: string }>;
}

export interface VisionPreparationImage {
  contentBase64: string;
  mimeType: string;
}

export interface VisionPreparationUpload {
  expectedRevision: number;
  fileName: string;
  contentBase64: string;
  purpose: PreparationPurpose;
  captureGroup: string;
}

const absolute = { baseURL: '' } as const;

function prefix(factoryId: string, projectId: string): string {
  if (!factoryId.trim() || !projectId.trim()) throw new Error('请先选择工厂和检查项目');
  return `/api/mobile/${encodeURIComponent(factoryId)}/vision-training/drafts/${encodeURIComponent(projectId)}/preparation`;
}

function required<T>(response: ApiResponse<T>): T {
  if (!response.success || response.data == null) {
    throw new ApiError(response.message || '照片准备服务未返回有效结果', response.code);
  }
  return response.data;
}

export async function getVisionPreparation(factoryId: string, projectId: string): Promise<VisionPreparationState> {
  return required(await get<VisionPreparationState>(prefix(factoryId, projectId), absolute));
}

export async function uploadVisionPreparationPhoto(
  factoryId: string,
  projectId: string,
  payload: VisionPreparationUpload,
): Promise<VisionPreparationState> {
  return required(await post<VisionPreparationState>(`${prefix(factoryId, projectId)}/photos`, payload, absolute));
}

export async function getVisionPreparationImage(
  factoryId: string,
  projectId: string,
  photoId: string,
): Promise<VisionPreparationImage> {
  return required(await post<VisionPreparationImage>(`${prefix(factoryId, projectId)}/image`, { photoId }, absolute));
}

export async function reviewVisionPreparationPhoto(
  factoryId: string,
  projectId: string,
  payload: {
    expectedRevision: number;
    photoId: string;
    verdict: PhotoVerdict;
    boxes: VisionPhotoBox[];
    transcription?: string;
  },
): Promise<VisionPreparationState> {
  return required(await post<VisionPreparationState>(`${prefix(factoryId, projectId)}/review`, payload, absolute));
}

export async function saveVisionPreparationStandard(
  factoryId: string,
  projectId: string,
  payload: {
    expectedRevision: number;
    passCriteria: string;
    failCriteria: string;
    maxMissRate: number;
    maxFalseAlarmRate: number;
    confirmed: true;
  },
): Promise<VisionPreparationState> {
  return required(await post<VisionPreparationState>(`${prefix(factoryId, projectId)}/standard`, payload, absolute));
}

export async function submitVisionPreparation(
  factoryId: string,
  projectId: string,
  expectedRevision: number,
): Promise<VisionPreparationState> {
  return required(await post<VisionPreparationState>(`${prefix(factoryId, projectId)}/submit`, { expectedRevision, confirmed: true }, absolute));
}
