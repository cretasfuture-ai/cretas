/**
 * 「特殊产出」三类的**唯一定义**（§7）。
 *
 * 三类共用工序底部同一个常驻入口 `+ 添加特殊产出`，选中后才进入各自的真实配置。
 *
 * ## 为什么第三类当前不可用
 * 副产与回料在 Java 侧各有一条**权威运行契约**：
 * - 副产 → `WorkflowByproductNodes` 读物料节点的 `data.isByproduct`，被标记的端口会被
 *   `getOutputOptions` 过滤掉，走 `material_batches`；
 * - 回料 → `WorkflowReworkNodes` 读 `data.isRework`，端口**必须留在**报工选项里，
 *   走 `semi_finished_inventory` 并带移动加权成本。
 *
 * 不良品**一条都没有**：后端既没有对应的节点标记判据，也没有任何运行时消费者
 * （`defectQuantity` 是报工请求体上的一个数，由 App 端汇总，与画布节点毫无关系）。
 *
 * ⇒ 交接 §7 的裁定是 fail closed：**禁止凭想象补一个字段并宣称可用**。所以它在选择器里
 * 是常驻可见但不可选的一项，并写出准确缺口 —— 不是悄悄隐藏（隐藏会让「这里不能配」和
 * 「这个功能不存在」长得一模一样），也不是做一个存得下却没人读的假闭环。
 */

export type SpecialOutputKind = 'byproduct' | 'defective' | 'rework';

export interface SpecialOutputOption {
  kind: SpecialOutputKind;
  badge: string;
  title: string;
  description: string;
  available: boolean;
  /** 不可用时的**具体**缺口，⛔ 不许写成「暂不支持」。 */
  blockedReason: string;
}

export const SPECIAL_OUTPUT_OPTIONS: readonly SpecialOutputOption[] = [
  {
    kind: 'byproduct',
    badge: '副',
    title: '副产',
    description: '肥油、料头一类可入库的伴生产出。选择物料档案中已标记为副产的物料；数量在报工时填。',
    available: true,
    blockedReason: '',
  },
  {
    kind: 'defective',
    badge: '不',
    title: '不良品',
    description: '判废、不进入回投库存的剔除产出。与「回料」是两件事，不能默认当成可回投库存。',
    available: false,
    blockedReason: '后端缺少权威契约：画布节点上没有不良品判据（副产是 WorkflowByproductNodes'
      + '、回料是 WorkflowReworkNodes，不良品一个都没有），也没有任何运行时消费者。'
      + '现在做出来只能存下一个谁也不读的字段，所以本轮停在这里，不伪造闭环。'
      + '需要它请先定后端持久化与报工消费契约。',
  },
  {
    kind: 'rework',
    badge: '回',
    title: '回料产出',
    description: '金检/X光剔出、可回到后续批次继续投的半成品。需要配置显示名称、SKU、单位和允许回投的目标工序。',
    available: true,
    blockedReason: '',
  },
] as const;

export function specialOutputOption(kind: SpecialOutputKind): SpecialOutputOption {
  const found = SPECIAL_OUTPUT_OPTIONS.find((option) => option.kind === kind);
  if (!found) throw new Error(`Unknown special output kind: ${kind}`);
  return found;
}
