import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';
import { Platform } from 'react-native';
import {
  LabelQcReviewTaskRequest,
  LabelQcTaskDetail,
  LabelQcTaskPage,
  LabelQcTaskStatus,
} from '../../types/labelQc';

interface ApiEnvelope<T> {
  success: boolean;
  code: number;
  message: string;
  data: T;
}

export interface LabelQcCaptureQualityResult {
  accepted: boolean;
  reasonCode: 'RESOLUTION_TOO_LOW' | 'PHOTO_BLURRY' | 'TRAYS_NOT_FOUND' | 'TRAY_CROPPED_AT_SIDE' | null;
  message: string;
  imageWidth: number;
  imageHeight: number;
  trayCount: number;
  sharpness: number;
  leftMarginRatio: number | null;
  rightMarginRatio: number | null;
}

class LabelQcApi {
  private base(factoryId?: string): string {
    const fid = getCurrentFactoryId(factoryId);
    if (!fid) throw new Error('factoryId 是必需的，请先登录');
    return `/api/mobile/${fid}/label-qc`;
  }

  async createTask(
    request: {
      productTypeId: string;
      batchNumber?: string;
      productionDate: string;
      idempotencyKey: string;
    },
    factoryId?: string,
  ): Promise<LabelQcTaskDetail> {
    const response = await apiClient.post<ApiEnvelope<LabelQcTaskDetail>>(
      `${this.base(factoryId)}/tasks`,
      request,
    );
    return response.data;
  }

  async checkCaptureQuality(
    file: { uri: string; name: string; type: string },
    factoryId?: string,
  ): Promise<LabelQcCaptureQualityResult> {
    const formData = new FormData();
    if (Platform.OS === 'web') {
      const blob = await (await fetch(file.uri)).blob();
      formData.append('image', blob, file.name);
    } else {
      formData.append('image', {
        uri: file.uri,
        name: file.name,
        type: file.type,
      } as unknown as Blob);
    }
    const response = await apiClient.post<ApiEnvelope<LabelQcCaptureQualityResult>>(
      `${this.base(factoryId)}/capture-quality`,
      formData,
      {
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: 5000,
      },
    );
    return response.data;
  }

  async addPhoto(
    taskId: string,
    request: {
      attachmentId: string;
      orderIndex: number;
      imageWidth: number;
      imageHeight: number;
    },
    factoryId?: string,
  ): Promise<void> {
    await apiClient.post<ApiEnvelope<unknown>>(
      `${this.base(factoryId)}/tasks/${taskId}/photos`,
      request,
    );
  }

  async submitTask(taskId: string, factoryId?: string): Promise<LabelQcTaskDetail> {
    const response = await apiClient.post<ApiEnvelope<LabelQcTaskDetail>>(
      `${this.base(factoryId)}/tasks/${taskId}/submit`,
    );
    return response.data;
  }

  async getTask(taskId: string, factoryId?: string): Promise<LabelQcTaskDetail> {
    const response = await apiClient.get<ApiEnvelope<LabelQcTaskDetail>>(
      `${this.base(factoryId)}/tasks/${taskId}`,
    );
    return response.data;
  }

  async listTasks(
    options: {
      statuses?: LabelQcTaskStatus[];
      archived?: boolean;
      page?: number;
      size?: number;
    } = {},
    factoryId?: string,
  ): Promise<LabelQcTaskPage> {
    const response = await apiClient.get<ApiEnvelope<LabelQcTaskPage>>(
      `${this.base(factoryId)}/tasks`,
      {
        params: {
          statuses: options.statuses?.join(','),
          archived: options.archived ?? false,
          page: options.page ?? 1,
          size: options.size ?? 20,
        },
      },
    );
    return response.data;
  }

  async reviewTask(
    taskId: string,
    request: LabelQcReviewTaskRequest,
    factoryId?: string,
  ): Promise<LabelQcTaskDetail> {
    const response = await apiClient.put<ApiEnvelope<LabelQcTaskDetail>>(
      `${this.base(factoryId)}/tasks/${taskId}/review`,
      request,
    );
    return response.data;
  }
}

export const labelQcApi = new LabelQcApi();
