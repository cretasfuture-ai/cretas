/**
 * 提醒 API — Sprint 4 W2 S-REMIND-1.
 *
 * 对应后端 ReminderController. Base: /api/mobile/{factoryId}/reminders.
 */
import request from './request';
import { requireFactoryId } from './currentFactoryId';
import type { ApiResponse } from '@/types/api';

export type ReminderType = 'PAYMENT_DUE';

export type ReminderStatus = 'PENDING' | 'SNOOZED' | 'DISMISSED';

export interface Reminder {
  id: string;
  factoryId: string;
  type: ReminderType;
  sourceType: string;
  sourceId: string;
  dueDate: string;
  assigneeId: number;
  status: ReminderStatus;
  snoozedUntil?: string | null;
  message?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ReminderPage {
  content: Reminder[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

const getFactoryId = requireFactoryId;

function basePath(factoryId?: string): string {
  return `/${getFactoryId(factoryId)}/reminders`;
}

/** 我的提醒列表 — status 默认 PENDING,SNOOZED. */
export function listReminders(params: {
  status?: string;
  page?: number;
  size?: number;
  factoryId?: string;
}): Promise<ApiResponse<ReminderPage>> {
  const q = new URLSearchParams();
  if (params.status) q.set('status', params.status);
  q.set('page', String(params.page ?? 0));
  q.set('size', String(params.size ?? 20));
  return request.get(`${basePath(params.factoryId)}?${q.toString()}`);
}

/** Bell badge 计数. */
export function getReminderBadge(
  factoryId?: string,
): Promise<{ success: boolean; data: { pending: number } }> {
  // _silent: 无 reminder 读权限的角色会收到 403，由铃铛自行隐藏，不弹全局阻断弹窗
  return request.get(`${basePath(factoryId)}/badge`, { _silent: true } as Record<string, unknown>);
}

/** Snooze. */
export function snoozeReminder(
  id: string,
  snoozedUntil: string,
  factoryId?: string,
): Promise<{ success: boolean; data: Reminder }> {
  return request.post(`${basePath(factoryId)}/${id}/snooze`, { snoozedUntil });
}

/** Dismiss. */
export function dismissReminder(
  id: string,
  factoryId?: string,
): Promise<{ success: boolean; data: Reminder }> {
  return request.post(`${basePath(factoryId)}/${id}/dismiss`, {});
}
