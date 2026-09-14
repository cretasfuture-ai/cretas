import React from 'react';
import { View, StyleSheet } from 'react-native';
import { Text, Card, Divider, Chip } from 'react-native-paper';
import { MaterialCommunityIcons } from '@expo/vector-icons';

import type {
  LineageSection,
  LineageNode,
  LineageDepthGroup,
} from '../../../services/api/traceabilityApiClient';

/**
 * 「来源链」时间线 —— 公开溯源页与内部溯源详情页<b>共用这一份</b>。
 *
 * ⛔ 不要在两个屏幕里各抄一份渲染逻辑：它们一定会漂，而漂了没有任何测试会红。
 *
 * <h3>三种状态必须分开显示</h3>
 * | 入参 | 含义 | 显示 |
 * |---|---|---|
 * | `lineage == null` | 本次没拿到（引擎不可用/组装失败） | 「来源链暂时取不到」 |
 * | `ancestors` 为空 | 确实没有上游血缘记录 | 「无上游血缘记录」 |
 * | `ancestors` 非空 | 有链 | 时间线 |
 *
 * 前两种<b>都不隐藏</b>。隐藏会让「没数据」和「功能坏了」都长成「这块不存在」。
 */

export const LINEAGE_TIMELINE_TEST_ID = 'lineage-chain-timeline';
export const LINEAGE_EMPTY_TEST_ID = 'lineage-chain-empty';
export const LINEAGE_UNAVAILABLE_TEST_ID = 'lineage-chain-unavailable';

/** 空态文案 —— 「确实没有上游」。 */
export const LINEAGE_EMPTY_TEXT = '无上游血缘记录';
/** 故障文案 —— 「这一次没取到」，⛔ 与空态不是同一句话。 */
export const LINEAGE_UNAVAILABLE_TEXT = '来源链暂时取不到，请稍后重试';

const NODE_TYPE_LABEL: Record<string, string> = {
  MATERIAL_BATCH: '原料批次',
  PRODUCTION_BATCH: '生产批次',
  FINISHED_BATCH: '成品批次',
  SHIPMENT_RECORD: '出货单',
};

const NODE_TYPE_ICON: Record<string, React.ComponentProps<typeof MaterialCommunityIcons>['name']> = {
  MATERIAL_BATCH: 'sprout',
  PRODUCTION_BATCH: 'factory',
  FINISHED_BATCH: 'package-variant-closed',
  SHIPMENT_RECORD: 'truck-delivery',
};

export interface LineageChainTimelineProps {
  lineage?: LineageSection | null;
  /** 是否展示供应商（内部页 true；公开页给 false —— 后端也不会下发，这里是第二道闸）。 */
  showSupplier?: boolean;
  title?: string;
}

/** 按 depth 从远到近排（最远的来源排最上面，读起来就是「从哪来 → 到这里」）。 */
function sortFarToNear(groups: LineageDepthGroup[]): LineageDepthGroup[] {
  return [...groups].sort((a, b) => (b.depth ?? 0) - (a.depth ?? 0));
}

function NodeRow({ node, showSupplier }: { node: LineageNode; showSupplier: boolean }) {
  const typeLabel = NODE_TYPE_LABEL[node.nodeType] ?? node.nodeType;
  const icon = NODE_TYPE_ICON[node.nodeType] ?? 'help-circle-outline';
  // resolved === false ⇒ 批次实体查不到（被软删/清理）。
  // ⛔ 不编名字：显示 id + 一句实话，让「查不到」与「叫什么」区分得开。
  const unresolved = node.resolved === false;

  return (
    <View style={styles.nodeRow} testID={`lineage-node-${node.nodeId}`}>
      <MaterialCommunityIcons name={icon} size={18} color="#666" style={styles.nodeIcon} />
      <View style={styles.nodeBody}>
        <View style={styles.nodeTitleRow}>
          <Text style={styles.nodeTitle}>
            {unresolved ? node.nodeId : (node.batchNumber || node.nodeId)}
          </Text>
          <Chip compact mode="outlined" style={styles.typeChip} textStyle={styles.typeChipText}>
            {typeLabel}
          </Chip>
        </View>
        {unresolved ? (
          <Text style={styles.nodeUnresolved}>该批次记录已不可查（血缘保留，批次已归档）</Text>
        ) : (
          <>
            {!!node.name && <Text style={styles.nodeMeta}>{node.name}</Text>}
            {showSupplier && !!node.supplierName && (
              <Text style={styles.nodeMeta}>供应商：{node.supplierName}</Text>
            )}
          </>
        )}
        {node.quantity != null && (
          <Text style={styles.nodeMeta}>
            投入量：{node.quantity}
            {node.unit || ''}
          </Text>
        )}
      </View>
    </View>
  );
}

const LineageChainTimeline: React.FC<LineageChainTimelineProps> = ({
  lineage,
  showSupplier = false,
  title = '来源链',
}) => {
  const header = (
    <View style={styles.sectionHeader}>
      <MaterialCommunityIcons name="file-tree-outline" size={22} color="#3F51B5" />
      <Text variant="titleMedium" style={styles.sectionTitle}>
        {title}
      </Text>
      {!!lineage && lineage.nodeCount > 0 && (
        <Chip compact mode="flat">{lineage.nodeCount} 个上游节点</Chip>
      )}
    </View>
  );

  // ① 本次没拿到 —— 与「没有上游」是两句话。
  if (lineage == null) {
    return (
      <Card style={styles.card} testID={LINEAGE_UNAVAILABLE_TEST_ID}>
        <Card.Content>
          {header}
          <Divider style={styles.divider} />
          <Text style={styles.emptyText}>{LINEAGE_UNAVAILABLE_TEXT}</Text>
        </Card.Content>
      </Card>
    );
  }

  const groups = sortFarToNear(lineage.ancestors ?? []);

  // ② 确实没有上游。⛔ 不隐藏整张卡 —— 隐藏会让「没有」和「坏了」都变成「这块不存在」。
  if (groups.length === 0) {
    return (
      <Card style={styles.card} testID={LINEAGE_EMPTY_TEST_ID}>
        <Card.Content>
          {header}
          <Divider style={styles.divider} />
          <Text style={styles.emptyText}>{LINEAGE_EMPTY_TEXT}</Text>
        </Card.Content>
      </Card>
    );
  }

  // ③ 有链。
  return (
    <Card style={styles.card} testID={LINEAGE_TIMELINE_TEST_ID}>
      <Card.Content>
        {header}
        <Divider style={styles.divider} />
        {groups.map((group, index) => (
          <View key={`depth-${group.depth}`} testID={`lineage-depth-${group.depth}`}>
            <View style={styles.depthHeader}>
              <View style={styles.depthDot} />
              <Text style={styles.depthLabel}>
                {group.depth === 1 ? '直接上游' : `上游第 ${group.depth} 跳`}
              </Text>
            </View>
            {(group.nodes ?? []).map((node) => (
              <NodeRow key={`${node.nodeType}#${node.nodeId}`} node={node} showSupplier={showSupplier} />
            ))}
            {index < groups.length - 1 && <View style={styles.connector} />}
          </View>
        ))}
      </Card.Content>
    </Card>
  );
};

const styles = StyleSheet.create({
  card: { marginHorizontal: 16, marginBottom: 12, borderRadius: 12 },
  sectionHeader: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  sectionTitle: { flex: 1, fontWeight: '600' },
  divider: { marginVertical: 12 },
  emptyText: { color: '#888', fontSize: 13, paddingVertical: 8 },
  depthHeader: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 6 },
  depthDot: { width: 8, height: 8, borderRadius: 4, backgroundColor: '#3F51B5' },
  depthLabel: { fontSize: 12, color: '#3F51B5', fontWeight: '600' },
  connector: { width: 2, height: 14, backgroundColor: '#C5CAE9', marginLeft: 3, marginVertical: 4 },
  nodeRow: { flexDirection: 'row', gap: 8, paddingVertical: 6, paddingLeft: 16 },
  nodeIcon: { marginTop: 2 },
  nodeBody: { flex: 1 },
  nodeTitleRow: { flexDirection: 'row', alignItems: 'center', gap: 6, flexWrap: 'wrap' },
  nodeTitle: { fontSize: 14, fontWeight: '500', color: '#222' },
  typeChip: { height: 22 },
  typeChipText: { fontSize: 10, lineHeight: 12 },
  nodeMeta: { fontSize: 12, color: '#666', marginTop: 2 },
  nodeUnresolved: { fontSize: 12, color: '#B26A00', marginTop: 2 },
});

export default LineageChainTimeline;
