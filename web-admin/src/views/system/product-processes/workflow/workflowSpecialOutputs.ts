/**
 * 「特殊产出」的**唯一定义**（§7）。
 *
 * 两类共用工序底部同一个常驻入口 `+ 添加特殊产出`，选中后才进入各自的真实配置。
 *
 * ## ⛔ 为什么只有两类，没有「不良品」
 * 交接书原本要求三选一（副产 / 不良品 / 回料）。**2026-09-01 owner 裁定：业务上不存在
 * 「判废 / 不入库」这个场景** —— 车间的真实流程是「复查 → 存着 → 回料直接回投」，
 * 剔出来的东西最终都走回料那条链，没有一类需要被判废后从系统里消失。
 *
 * 所以这一类**整个去掉**，不是「暂时禁用」：
 * - 留一个永远点不动的选项，是给用户一个他这辈子都用不到的选择题；
 * - 而它当初之所以被列出来，是照着原型的三分法写的，不是照着车间。
 *
 * ⇒ 与此同时，Java 侧「没有不良品判据类」这件事也就不再是缺口了 —— 它本来就不该有。
 *
 * ## 两类各自的运行时契约（都是真的，不是留痕）
 * - 副产 → `WorkflowByproductNodes` 读 `data.isByproduct`，被标记的端口会被
 *   `getOutputOptions` **过滤掉**，走 `material_batches`；
 * - 回料 → `WorkflowReworkNodes` 读 `data.isRework`，端口**必须留在**报工选项里，
 *   走 `semi_finished_inventory` 并带移动加权成本进下一批。
 */

export type SpecialOutputKind = 'byproduct' | 'rework';

export interface SpecialOutputOption {
  kind: SpecialOutputKind;
  badge: string;
  title: string;
  description: string;
}

export const SPECIAL_OUTPUT_OPTIONS: readonly SpecialOutputOption[] = [
  {
    kind: 'byproduct',
    badge: '副',
    title: '副产',
    description: '肥油、料头一类可入库的伴生产出。选择物料档案中已标记为副产的物料；数量在报工时填。',
  },
  {
    kind: 'rework',
    badge: '回',
    title: '回料产出',
    description: '金检/X光剔出、复查后回到后续批次继续投的半成品。可以配置回投到哪些工序、按多少比例投。',
  },
] as const;

export function specialOutputOption(kind: SpecialOutputKind): SpecialOutputOption {
  const found = SPECIAL_OUTPUT_OPTIONS.find((option) => option.kind === kind);
  if (!found) throw new Error(`Unknown special output kind: ${kind}`);
  return found;
}
