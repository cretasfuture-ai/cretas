// 平台人员访问记录 (PR #1109) — 工厂查看「平台工作人员什么时候看了我们哪些数据」。
//
// Backend: PlatformDataAccessLogController  GET /api/mobile/{factoryId}/platform-access-log
//   @RequireRole({"factory_super_admin", "permission_admin"}); page 从 1 开始, size 默认 20、最多 100。
//   平台人员调这个接口本身也会被记录。

import { get } from './request';

export interface PlatformAccessLogEntry {
  id: number;
  accessedAt: string;
  actorType: string;
  actorUserId?: number | null;
  actorUsername?: string | null;
  actorRole?: string | null;
  httpMethod: string;
  requestPath: string;
  /** 只记参数名, 不记参数值。 */
  queryParamNames?: string | null;
  responseStatus?: number | null;
  clientIp?: string | null;
}

export interface PlatformAccessLogPage {
  content: PlatformAccessLogEntry[];
  totalElements: number;
  totalPages?: number;
  page?: number;
  size?: number;
}

export const listPlatformAccessLog = (factoryId: string, page = 1, size = 20) =>
  get<PlatformAccessLogPage>(`/${factoryId}/platform-access-log`, { params: { page, size } });
