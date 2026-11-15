import type { SrcId, TSrc } from './todayData';

export type TodoTabId = 'confirm' | 'work' | 'assigned' | 'approve' | 'mine' | 'cc';
export interface TodoTab { id: TodoTabId; label: string; sources: SrcId[]; reason?: string }

export const TODO_TABS: TodoTab[] = [
  // P8：数字员工交办的写操作，我有权确认的（服务端按登录人权限筛）。页签内容是可操作的确认列表（CrewInboxList）
  { id: 'confirm', label: '等我确认', sources: ['crewConfirm'] },
  { id: 'work', label: '业务待办', sources: ['risk', 'expiry', 'receiving', 'unordered', 'salesOrders'] },
  { id: 'assigned', label: '指派给我', sources: ['issueTodo', 'assigned'] },
  { id: 'approve', label: '待我审批', sources: ['approve'] },
  { id: 'mine', label: '我发起的', sources: ['mine'] },
  { id: 'cc', label: '抄送与已处理', sources: ['copied', 'acted'] },
];

/**
 * G-2：页签角标只在「读到了数」时给；读不到 / 不适用 / 没有来源 ⇒ null（不画角标，不画「—」），
 * 原因写在页签内容里（SourceBlock 的 note、页签自己的 reason）。
 * 多来源页签：只要有一路读取失败（或还没读完），总数就不可信 ⇒ 不画；「不适用」的来源不参与（权限外 / 本厂没有）。
 * 页签里的来源全部不适用 ⇒ 没有数 ⇒ 不画。
 */
export function tabBadge(id: TodoTabId, s: Record<SrcId, TSrc>): number | null {
  const tab = TODO_TABS.find((t) => t.id === id);
  if (!tab || !tab.sources.length) return null;
  const parts = tab.sources.map((k) => s[k]);
  if (parts.some((p) => p.state === 'error' || p.state === 'loading' || p.state === 'idle')) return null;
  const ok = parts.filter((p) => p.state === 'ok');
  if (!ok.length) return null;
  return ok.reduce((a, p) => a + p.count, 0);
}
