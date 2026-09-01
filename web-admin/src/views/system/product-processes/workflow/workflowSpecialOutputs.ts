/**
 * 「特殊产出」的**唯一定义**（§7）。
 *
 * 三类共用工序底部同一个常驻入口 `+ 添加特殊产出`，选中后才进入各自的真实配置。
 *
 * ## ⛔ 被去掉的是「判废 / 不入库」这两个说法，不是不良品这一类
 * 2026-09-01 owner 裁定原话：**不良品是需要的**。车间的真实流程是
 * 「剔出 → 复查 → 存着 → 回料直接回过去」——
 * 剔出来的东西**要入库存着**，等复查完再作为回料回投。
 *
 * 所以要删的是当初挂在这一类上的两句错话：
 * - ⛔「判废」—— 不存在把它判死从系统里消失这个动作；
 * - ⛔「不入库」—— 正好相反，它**必须入库**，「存着」就是入库。
 *
 * 我第一版把整类删掉了，那是把 owner 的「这两个功能可以去掉」读成了「这一类可以去掉」。
 * 这段注释留着，是因为下一个人很容易照着「Java 侧没有不良品判据类」再删一次
 * —— 见下面那条，缺的不是类，是一个**它不需要**的东西。
 *
 * ## 三类各自的运行时契约
 * - 副产 → `WorkflowByproductNodes` 读 `data.isByproduct`，被标记的端口会被
 *   `getOutputOptions` **过滤掉**，走 `material_batches`；
 * - 回料 → `WorkflowReworkNodes` 读 `data.isRework`，端口**必须留在**报工选项里，
 *   并带 `isRework=true` 下发；走 `semi_finished_inventory`，移动加权成本进下一批；
 * - 不良品 → `data.isDefective` **只是显示标记**，后端没有、也不需要判据类。
 *   2026-09-01 在 `WipInventoryServiceImpl.workflowOutputOptions` 上读过实现：
 *   那段只把**副产**滤掉（`!byproductNodeIds.contains(...)`），没有标记的半成品产出
 *   照常进报工选项、照常入 `semi_finished_inventory`。
 *   ⇒ 「报工能报 + 入库存着」这条路对不良品**本来就是通的**，不靠新增后端代码。
 *
 * ⚠️ 诚实边界（⛔ 不要在 UI 上说过头）：后端**没有**「复查通过 → 自动转成回料」这一步。
 * 复查完之后回投，现在是人在回料 Cell 那边报的。画布上这两个 Cell 是两件事，
 * 不是一条自动流水线。
 */

export type SpecialOutputKind = 'byproduct' | 'defective' | 'rework';

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
    kind: 'defective',
    badge: '不',
    title: '不良品',
    description: '金检/X光 剔出来、还没复查的那部分。照常报工、照常入库存着；复查通过之后再从回料那边回投。',
  },
  {
    kind: 'rework',
    badge: '回',
    title: '回料产出',
    description: '复查过、确定要回到后续批次继续投的半成品。可以配置回投到哪些工序、按多少比例投。',
  },
] as const;

export function specialOutputOption(kind: SpecialOutputKind): SpecialOutputOption {
  const found = SPECIAL_OUTPUT_OPTIONS.find((option) => option.kind === kind);
  if (!found) throw new Error(`Unknown special output kind: ${kind}`);
  return found;
}
