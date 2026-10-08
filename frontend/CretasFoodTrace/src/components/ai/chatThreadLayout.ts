/**
 * 对话流版式的两处小计算 (AI 对话标准 L1 / F3), 抽出来单独测。
 */

/** 对话流左右边距 (标准 S1) */
export const THREAD_SIDE_PADDING = 16;

/**
 * 滚动容器的经典滚动条宽度 (offsetWidth − clientWidth)。
 * 手机浏览器的滚动条是浮在内容上的 (宽 0); 桌面 Chrome 是 15px、占内容宽 ——
 * Codex 本机 375×812 实测: 出现滚动条后正文从 343px 掉到 328px (标准 L1 要求 屏宽 − 32 = 343)。
 * 拿不到 DOM 节点 (原生端) 时返回 0。
 */
export function measureScrollbarWidth(node: unknown): number {
  if (!node || typeof node !== 'object') return 0;
  const { offsetWidth, clientWidth } = node as { offsetWidth?: unknown; clientWidth?: unknown };
  if (typeof offsetWidth !== 'number' || typeof clientWidth !== 'number') return 0;
  return Math.max(0, offsetWidth - clientWidth);
}

/**
 * 把滚动条算进右边距: 右内边距 = 16 − 滚动条宽, 正文列仍是 屏宽 − 32。
 * 滚动条比 16 还宽时右内边距为 0 (正文比 343 窄的部分只有「滚动条 − 16」那一点)。
 */
export function threadRightPadding(scrollbarWidth: number): number {
  return Math.max(0, THREAD_SIDE_PADDING - scrollbarWidth);
}

export interface ClarificationActionLike {
  label: string;
  description?: string | null;
}

/** 说明文字只算「非空且与标签不同」的 */
function hasDistinctDescription(action: ClarificationActionLike): boolean {
  const description = action.description?.trim();
  return Boolean(description) && description !== action.label;
}

/**
 * 标准 F3: 同一组澄清选项「说明要么每个都有、要么都没有」。
 * 一组里只要有一个没说明, 整组都不显示说明 (Codex 实测: 只有第 2 个带说明, 按钮高 48 / 64 / 48 / 48)。
 * 说明不丢: 一律并进无障碍标签, 读屏照样读得到。
 */
export function shouldShowActionDescriptions(actions: ClarificationActionLike[]): boolean {
  return actions.length > 0 && actions.every(hasDistinctDescription);
}

export function clarificationAccessibilityLabel(action: ClarificationActionLike): string {
  return hasDistinctDescription(action) ? `${action.label}，${action.description!.trim()}` : action.label;
}
