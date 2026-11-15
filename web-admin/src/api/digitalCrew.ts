/**
 * P8「数字班组」接口（后端 DigitalCrewController，/api/mobile/{factoryId}/digital-crew）。
 *
 * 身份与权限全在服务端按会话判：这里的请求体只有业务内容，没有确认人、权限之类的字段。
 * 写操作：对话只「交办」（记已交办，进等我确认）；确认人在等我确认里先按自己身份预览，再确认。
 * 确认凭据走 X-Cretas-Confirmation-Token 请求头，请求体恰好四个字段（与 AI 写入确认同一契约）。
 */
import request from '@/api/request';
import type { ConfirmablePreviewSource } from '@/views/smart-bi/components/chat/aiWriteConfirmRows';

export type CrewMemberKey = 'clerk' | 'keeper' | 'planner' | 'inspector' | 'accountant';
export type CrewItemStatus = 'DELEGATED' | 'CONFIRMING' | 'DONE' | 'NOT_DONE' | 'OUTCOME_UNKNOWN';

export interface CrewCapability { label: string; kind: 'query' | 'write'; module: string }
export interface CrewMember {
  key: CrewMemberKey; name: string; shortName: string; duty: string; enabled: boolean;
  modules: string[]; moduleLevels: Record<string, string>; level: 'rw' | 'r' | '-';
  capabilities: CrewCapability[]; workdesks: string[];
}
export interface CrewItem {
  id: number; memberKey: CrewMemberKey; memberName: string; intentCode: string; intentName: string | null;
  title: string; userInput: string; module: string; requiredPermission: string;
  status: CrewItemStatus; statusLabel: string; quotaCost: number; previewSummary: string | null;
  createdByName: string; createdAt: string; confirmedByName: string | null; confirmedAt: string | null;
  resolvedAt: string | null; resolvedDate: string | null; resultStatus: string | null; resultNote: string | null;
  evidence: string | null; previewedByMe: boolean; canConfirm: boolean;
}
export interface CrewInbox { mine: CrewItem[]; hiddenCount: number; hiddenModules: string[] }
export interface CrewSettingLog { at: string; by: string; kind: 'MEMBERS' | 'ROLE_MAPPING'; reason: string; before: string; after: string }
export interface CrewSettings {
  members: CrewMember[]; roleMapping: Record<string, string | null>; roleLabels: Record<string, string>;
  logs: CrewSettingLog[]; canEdit: boolean;
}
export interface CrewAsk {
  kind: 'ANSWER' | 'DELEGATED' | 'OUT_OF_SCOPE' | 'REFUSED'; memberKey: string; status: string | null; text: string;
  intentCode: string | null; intentName: string | null; capabilityPack: string | null; ownerKey: string | null;
  item: CrewItem | null; clarificationChoices: string[]; sessionId: string | null;
}
export interface CrewConfirmableAction extends ConfirmablePreviewSource {
  confirmToken?: string; commandDigest?: string; expiresAt?: string; description?: string; impactSummary?: string;
}
export interface CrewPreviewResponse {
  status?: string; message?: string; formattedText?: string; confirmableAction?: CrewConfirmableAction | null;
}
export interface CrewPreview { item: CrewItem; preview: CrewPreviewResponse }
export interface CrewMemberCount {
  memberKey: string; memberName: string; delegated: number; confirming: number; done: number;
  notDone: number; outcomeUnknown: number; quota: number;
}
export interface CrewLedger {
  today: string; quotaToday: number; quotaThisMonth: number; quotaTotal: number; delegated: number; done: number;
  perMember: CrewMemberCount[]; records: CrewItem[];
}

interface Envelope<T> { success?: boolean; message?: string; data?: T }
type RequestConfig = Record<string, unknown>;

function unwrap<T>(res: unknown): T {
  const env = res as Envelope<T>;
  if (env && env.success === false) throw new Error(env.message || '接口返回失败');
  if (!env || env.data === undefined) throw new Error('没有收到数据');
  return env.data;
}

const base = (f: string) => `/${f}/digital-crew`;

export const crewApi = {
  members: async (f: string, config?: RequestConfig) => unwrap<CrewMember[]>(await request.get(`${base(f)}/members`, config)),
  settings: async (f: string, config?: RequestConfig) => unwrap<CrewSettings>(await request.get(`${base(f)}/settings`, config)),
  updateMembers: async (f: string, enabled: Record<string, boolean>, reason: string) =>
    unwrap<CrewSettings>(await request.put(`${base(f)}/settings/members`, { enabled, reason })),
  updateRoleMapping: async (f: string, mapping: Record<string, string | null>, reason: string) =>
    unwrap<CrewSettings>(await request.put(`${base(f)}/settings/role-mapping`, { mapping, reason })),
  ask: async (f: string, member: string, text: string, sessionId?: string | null) =>
    unwrap<CrewAsk>(await request.post(`${base(f)}/members/${encodeURIComponent(member)}/ask`, { text, sessionId: sessionId || null }, { timeout: 90_000 })),
  inbox: async (f: string, config?: RequestConfig) => unwrap<CrewInbox>(await request.get(`${base(f)}/inbox`, config)),
  preview: async (f: string, id: number) =>
    unwrap<CrewPreview>(await request.post(`${base(f)}/items/${id}/preview`, {}, { timeout: 90_000 })),
  reject: async (f: string, id: number, reason: string) =>
    unwrap<CrewItem>(await request.post(`${base(f)}/items/${id}/reject`, { reason })),
  ledger: async (f: string, member?: string, config?: RequestConfig) =>
    unwrap<CrewLedger>(await request.get(`${base(f)}/ledger`, { ...(config || {}), params: member ? { member } : {} })),
  /** 确认执行：凭据只在请求头里；requestId / idempotencyKey 由凭据摘要派生，响应丢了重发也是同一次尝试。 */
  confirm: async (f: string, id: number, action: CrewConfirmableAction) => {
    if (!action.confirmToken || !action.commandDigest || !action.expiresAt) throw new Error('预览没有给出确认凭据，没有执行任何写操作');
    const identity = `crew-${id}-${action.commandDigest.slice(0, 32)}`;
    return unwrap<CrewItem>(await request.post(`${base(f)}/items/${id}/confirm`,
      { commandDigest: action.commandDigest, expiresAt: action.expiresAt, requestId: identity, idempotencyKey: identity },
      { timeout: 90_000, headers: { 'X-Cretas-Confirmation-Token': action.confirmToken } }));
  },
};

export const CREW_AVATARS: Record<CrewMemberKey, string> = {
  clerk: '/digital-employees/clerk.png', keeper: '/digital-employees/keeper.png', planner: '/digital-employees/planner.png',
  inspector: '/digital-employees/inspector.png', accountant: '/digital-employees/accountant.png',
};

export const MODULE_CN: Record<string, string> = {
  sales: '销售', procurement: '采购', warehouse: '仓储', inventory: '库存', production: '生产', scheduling: '排产',
  work_report: '报工', quality: '质量', finance: '财务', analytics: '数据分析', system: '系统',
};
export const LEVEL_CN: Record<string, string> = { rw: '能查能办', r: '只能查', w: '只能办', '-': '没有权限' };
export const STATUS_TONE: Record<CrewItemStatus, 'info' | 'warning' | 'success' | 'neutral' | 'error'> = {
  DELEGATED: 'info', CONFIRMING: 'warning', DONE: 'success', NOT_DONE: 'neutral', OUTCOME_UNKNOWN: 'error',
};
