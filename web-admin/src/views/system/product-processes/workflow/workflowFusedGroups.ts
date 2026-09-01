/**
 * 融合附属卡片（fused attachment）的**唯一几何与归属真值**。
 *
 * ## 什么叫「融合」
 * 不是「靠得很近」，也不是「有线连着但还能拖开」。融合 = 同一个 owner group：
 * - 附属卡片与 owner **同 x、同宽**，边框无缝相接（间距恒等于 0，不是「留白很小」）；
 * - 附属卡片**不可独立拖动**、不参与自由布局；拖 owner 时整组一起动；
 * - 自动布局按 group 的**完整包围盒**排间距，不是只按 owner 自身高度。
 *
 * ## ⛔ 为什么位置全部等于 owner 的位置
 * 上一版把辅料 Cell 放在 `owner.y - (辅料自身高度 + GAP)`，于是「辅料高度」既决定
 * 渲染又决定坐标 —— 而高度只能从**上一帧**量到（`findNode().dimensions`）。展开/收起
 * 改变高度 ⇒ 下一次派生才纠正坐标 ⇒ 中间那一帧要么裂开一条缝要么压住 owner，
 * 而纠正坐标又会让 VueFlow 重新测量 → 触发 fit/漂移。
 *
 * 现在附属节点的坐标**恒等于 owner 的坐标**，视觉偏移完全交给 CSS：
 * - 上方附属：`position:absolute; bottom:0` —— 底边天然贴在 owner 顶边，向上生长；
 * - 下方附属：`position:absolute; top:<owner 高度 + 前面几条的高度>` —— 只是一个
 *   CSS 变量，改它不动 VueFlow 的 node position。
 *
 * ⇒ 展开/收起**结构上不可能**改变 viewport：没有任何 node position 变化可供 VueFlow
 *   重新 fit。这比「事后 setViewport 恢复」更硬 —— 后者只是把漂移擦掉，前者让它不发生。
 */

/** owner Cell 的 CSS 宽度。⛔ 与 WorkflowProcessNode.vue / WorkflowMaterialNode.vue 的
 *  `.process-node{width}` / `.material-node{width}` 必须逐字一致 —— 附属卡片「同宽」
 *  是验收项，两处漂开就是形态 D。由 __tests__/workflowFusedGroups.spec.ts 钉住。 */
export const FUSED_PROCESS_OWNER_WIDTH = 440;
export const FUSED_MATERIAL_OWNER_WIDTH = 210;

/** 融合附属卡片挂在 owner 的哪一侧。 */
export type FusedAttachmentSide = 'above' | 'below';

export interface FusedAttachmentDescriptor {
  /** 附属节点 id（VueFlow node id）。 */
  id: string;
  /** 归属 owner 的节点 id。 */
  ownerId: string;
  side: FusedAttachmentSide;
  /** 同一 owner 同一侧内的堆叠序号，从 0 开始。 */
  stackIndex: number;
}

export interface FusedSizeLike {
  width: number;
  height: number;
}

/**
 * 下方附属卡片的 CSS `top` 偏移 = owner 实测高度 + 它前面几条附属的实测高度之和。
 *
 * 量不到时返回 `null` 而不是 0 —— 0 会把「还没测量」渲染成「贴在 owner 顶部」，
 * 也就是压在 owner 身上。调用方应当在拿到 null 时先不渲染偏移（保持 visibility:hidden），
 * 不要用一个假的 0 顶上去。（形态 A¹⁰：兜底的默认值会把「我不知道」翻译成「是 0」。）
 */
export function fusedBelowOffset(
  ownerId: string,
  precedingAttachmentIds: readonly string[],
  sizes: Readonly<Record<string, FusedSizeLike>>,
): number | null {
  const owner = sizes[ownerId];
  if (!owner || !(owner.height > 0)) return null;
  let offset = owner.height;
  for (const id of precedingAttachmentIds) {
    const size = sizes[id];
    if (!size || !(size.height > 0)) return null;
    offset += size.height;
  }
  return offset;
}

/**
 * 融合附属卡片的**几何决策**（纯函数，唯一出处）。
 *
 * ⛔ 刻意抽出来而不是写在组件的 computed 里：jsdom 的 CSSStyleDeclaration 会把
 * `transform` 这类它不认识的属性**静默丢掉**，于是在组件上断言 `style` 属性读到的是空串 ——
 * 那不是「几何错了」，是仪器读不到（形态 A⁗：读取工具把「有」变成「我没看到」）。
 * 抽成纯函数之后，几何决策可以被直接量，不必隔着一层会吃掉读数的 DOM。
 */
export function fusedStripTransform(
  side: FusedAttachmentSide,
  ownerOffset: number | null,
): string {
  // 上方附属：位移恰好等于**自身高度**，所以底边永远压在 owner 顶边上，
  // 展开/收起自己长高长矮都自动跟上，一次测量都不需要。
  if (side === 'above') return 'translateY(-100%)';
  // 下方附属：位移 = owner 高度（+ 前面几条），只能实测；量不到时不显形（见 is-unmeasured），
  // 这里返回 0 只是为了让 style 合法，配套的 visibility:hidden 保证它不会以错误位置出现。
  return `translateY(${ownerOffset ?? 0}px)`;
}

export interface FusedGroupBounds {
  /** owner 顶边**之上**被附属卡片占掉的高度。 */
  above: number;
  /** owner 底边**之下**被附属卡片占掉的高度。 */
  below: number;
}

/**
 * 一个 owner group 在 owner 位置之外额外占用的上下高度。
 *
 * 自动布局用它把 group 当成一个不可拆分的矩形排版；只按 owner 自身高度排，
 * 上下附属就会互相碰撞（§11 明确列为必须避免的回归）。
 *
 * `fallbackHeight` 是量不到时的**保守下限**：布局宁可多留白也不能压上去。
 */
export function fusedGroupBounds(
  attachments: readonly FusedAttachmentDescriptor[],
  sizes: Readonly<Record<string, FusedSizeLike>>,
  fallbackHeight: number,
): FusedGroupBounds {
  let above = 0;
  let below = 0;
  for (const attachment of attachments) {
    const measured = sizes[attachment.id]?.height;
    const height = typeof measured === 'number' && measured > 0 ? measured : fallbackHeight;
    if (attachment.side === 'above') above += height;
    else below += height;
  }
  return { above, below };
}

/**
 * 一个 owner 及其全部融合附属的 id 集合。
 *
 * 聚焦/淡化（§9）与拖动（§6）都以 group 为单位：只高亮 owner 而让它的辅料条淡下去，
 * 视觉上就把「融合」这件事又拆开了。
 */
export function fusedGroupNodeIds(
  ownerIds: Iterable<string>,
  attachments: readonly FusedAttachmentDescriptor[],
): Set<string> {
  const owners = new Set(ownerIds);
  const result = new Set(owners);
  for (const attachment of attachments) {
    if (owners.has(attachment.ownerId)) result.add(attachment.id);
  }
  return result;
}

/**
 * 把 focus id 集合与**当前实际存在的节点**对账。
 *
 * 🔴 这是「孤儿 focus ⇒ 整张画布变白」的唯一防线（§9）。节点重建、草稿切换、删除、
 * undo 之后，旧的 focus id 可能一个都不存在了；此时若仍然「非 focus 的全部淡化」，
 * 结果就是**每一个 Cell 都被淡化** —— 用户看到一张空白画布，而且没有任何报错。
 *
 * 判据：对账后为空 ⇒ 返回 null ⇒ 调用方必须回到「全部正常显示」，
 * ⛔ 不允许回到「全部 5% 透明」。
 */
export function reconcileFocusIds(
  focusIds: Iterable<string> | null | undefined,
  liveNodeIds: ReadonlySet<string>,
): Set<string> | null {
  if (!focusIds) return null;
  const reconciled = new Set<string>();
  for (const id of focusIds) {
    if (liveNodeIds.has(id)) reconciled.add(id);
  }
  return reconciled.size > 0 ? reconciled : null;
}
