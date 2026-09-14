import React from 'react';
import { View, StyleSheet } from 'react-native';
import { Text, Surface } from 'react-native-paper';
import { MaterialCommunityIcons } from '@expo/vector-icons';

import type {
  LineageSection,
  LineageDepthGroup,
} from '../../../services/api/traceabilityApiClient';

/**
 * 「本批来自 N 头牛」横幅 —— 溯源详情页与公开溯源页<b>共用这一份</b>。
 *
 * ⛔ 不要在两个屏幕里各抄一份判据：判据一旦有两份，两屏会在边界情况上给出不同的话，
 * 而没有任何测试会因此变红（形态 D）。
 *
 * <h3>判据（两个条件都要满足）</h3>
 * 1. `sourceIndividualCount > 0`；
 * 2. <b>最远那一层祖先</b>全部是带 `sourceIndividual` 的 `MATERIAL_BATCH`。
 *
 * 条件 2 是「这批货的源头确实是活体个体」的信号。只满足条件 1 不够 ——
 * 链上某一层混着调料/包材时，说「本批来自 N 头牛」会把辅料的来源一并盖掉。
 *
 * <h3>⛔ N 不在前端算</h3>
 * 公开接口上 `individualCode` 是 null、`tagNumber` 只剩末 4 位，
 * 按脱敏值去重会把「末 4 位相同的两头牛」数成一头 —— 只会**少报**。
 * N 由后端按真实个体 id 去重后下发，这里只显示。
 */

export const SOURCE_INDIVIDUAL_BANNER_TEST_ID = 'lineage-source-individual-banner';

/** 物种 → 量词。⚠️ 未知物种退回「个」，⛔ 不默认说「头」（鸭不是一头）。 */
const SPECIES_UNIT: Record<string, string> = {
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

/**
 * 判据的**唯一实现**。导出它是为了让测试能直接钉判据本身，
 * 而不是只能透过渲染结果间接推断。
 */
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

export interface SourceIndividualBannerProps {
  lineage?: LineageSection | null;
}

const SourceIndividualBanner: React.FC<SourceIndividualBannerProps> = ({ lineage }) => {
  const summary = summarizeSourceIndividuals(lineage);
  if (!summary.show) {
    // ⚠️ 这里**可以**整块不渲染：与「无上游血缘记录」不同，
    // 「这批货的源头不是活体个体」是绝大多数产品的常态，不是缺陷也不是故障，
    // 没有一句话需要对用户交代。
    return null;
  }
  return (
    <Surface style={styles.banner} elevation={1} testID={SOURCE_INDIVIDUAL_BANNER_TEST_ID}>
      <MaterialCommunityIcons name="cow" size={22} color="#5D4037" />
      <Text style={styles.text}>{bannerText(summary)}</Text>
    </Surface>
  );
};

const styles = StyleSheet.create({
  banner: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginHorizontal: 16,
    marginBottom: 12,
    paddingVertical: 10,
    paddingHorizontal: 14,
    borderRadius: 10,
    backgroundColor: '#FFF8E1',
  },
  text: { flex: 1, fontSize: 14, fontWeight: '600', color: '#5D4037' },
});

export default SourceIndividualBanner;
