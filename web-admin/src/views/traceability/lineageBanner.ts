import type { LineageDepthGroup, LineageSection } from '@/api/traceability';

/**
 * 「本批来自 N 头牛」横幅的判据 —— 与手机端
 * `screens/traceability/components/SourceIndividualBanner.tsx` 的
 * `summarizeSourceIndividuals` **逐字等价**。
 *
 * 判据（两个条件都要满足）:
 * 1. `sourceIndividualCount > 0`；
 * 2. **最远那一层祖先**全部是带 `sourceIndividual` 的 `MATERIAL_BATCH`。
 *
 * 条件 2 是「这批货的源头确实是活体个体」的信号。只满足条件 1 不够 ——
 * 链上某一层混着调料/包材时，说「本批来自 N 头牛」会把辅料的来源一并盖掉。
 *
 * ⛔ N 不在前端算: 由后端按真实个体 id 去重后下发，这里只显示。
 *
 * ⚠️ 这是跨端的第二份（RN 一份、这里一份），抽不成一份（跨运行时）。
 * `__tests__/index.spec.ts` 用与 RN 同一组样本钉住两端给出同一句话。
 */

/** 物种 → 量词。⚠️ 未知物种退回「个」，⛔ 不默认说「头」（鸭不是一头）。 */
export const SPECIES_UNIT: Record<string, string> = {
  牛: '头',
  猪: '头',
  羊: '只',
  鸭: '只',
  鸡: '只',
  鹅: '只',
};

/** 最远那一层祖先（depth 最大的那组）。没有祖先时返回 null。 */
export function farthestAncestorGroup(
  lineage?: LineageSection | null
): LineageDepthGroup | null {
  const groups = lineage?.ancestors ?? [];
  let farthest: LineageDepthGroup | null = null;
  for (const g of groups) {
    if (farthest === null || (g.depth ?? 0) > (farthest.depth ?? 0)) {
      farthest = g;
    }
  }
  return farthest;
}

export interface SourceIndividualSummary {
  /** 满足判据、该显示横幅。 */
  show: boolean;
  /** 后端下发的去重个体数。 */
  count: number;
  /** 最远祖先层上出现过的物种（去重）。用于选量词和文案。 */
  species: string[];
}

/** 判据的**唯一实现**（本端）。导出它是为了让测试能直接钉判据本身。 */
export function summarizeSourceIndividuals(
  lineage?: LineageSection | null
): SourceIndividualSummary {
  const count = lineage?.sourceIndividualCount ?? 0;
  const farthest = farthestAncestorGroup(lineage);
  const nodes = farthest?.nodes ?? [];

  const allIndividualBackedMaterials =
    nodes.length > 0 &&
    nodes.every((n) => n.nodeType === 'MATERIAL_BATCH' && !!n.sourceIndividual);

  const species = Array.from(
    new Set(
      nodes
        .map((n) => n.sourceIndividual?.species)
        .filter((s): s is string => !!s)
    )
  );

  return { show: count > 0 && allIndividualBackedMaterials, count, species };
}

/** 「本批来自 3 头牛」/ 物种不唯一或未知时退成「本批来自 3 个源头个体」。 */
export function bannerText(summary: SourceIndividualSummary): string {
  if (summary.species.length === 1) {
    const s = summary.species[0] as string;
    const unit = SPECIES_UNIT[s];
    if (unit) {
      return `本批来自 ${summary.count} ${unit}${s}`;
    }
    // 认识物种名但没有对应量词 —— 说得出物种就说物种，⛔ 别硬套「头」。
    return `本批来自 ${summary.count} 个${s}源头个体`;
  }
  // 混养 / 物种未登记：不猜量词，也不挑一个物种代表全部。
  return `本批来自 ${summary.count} 个源头个体`;
}
