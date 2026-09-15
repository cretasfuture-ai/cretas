import React from 'react';
import { View, StyleSheet } from 'react-native';
import { Text, Card, Divider, TouchableRipple, useTheme } from 'react-native-paper';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTranslation } from 'react-i18next';

import type { AppTheme } from '../../../theme';
import type { ProcessingStackParamList } from '../../../types/navigation';

import type {
  LineageSection,
  LineageNode,
  LineageDepthGroup,
  SourceIndividualBrief,
} from '../../../services/api/traceabilityApiClient';
import { DnaStatusChip, DNA_STATUS_LABEL, dnaStatusLabel } from './dnaStatus';

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
 *
 * <h3>链的视觉语言（2026-09-15 polish，2026-09-16 连线改连续）</h3>
 * 「一段」= 左侧圆点 + 标题（`ChainGroup`），段内每一行 = 左 icon + 正文 + 可进时右 chevron
 * （`ChainRow`）。个体档案屏「去了哪」是同一条链的反向，**复用这两个组件**，
 * ⛔ 别在那边再抄一份样式。颜色全部走 `useTheme()`，本文件里不出现色值。
 *
 * 圆点之间的竖线是一条**从这一段圆点画到下一段圆点**的连续线（左侧一根「导轨」，
 * 线绝对定位、上下都锚在圆点中心），⛔ 不是段与段之间一小截写死高度的短线 ——
 * 2026-09-16 真机实测那截 14px 的短线与上下圆点都不相连，web 抽屉同一区块是连续线。
 *
 * <h3>措辞</h3>
 * 段标题与计数走 i18n（`traceability.lineage.*`）：「直接来源」「往上第 N 层来源」「来源 N 项」。
 * ⛔ 不再说「上游节点」「直接上游」「关联边」—— 那是图论词，老板听不懂。N 由后端算，这里只显示。
 */

export const LINEAGE_TIMELINE_TEST_ID = 'lineage-chain-timeline';
export const LINEAGE_EMPTY_TEST_ID = 'lineage-chain-empty';
export const LINEAGE_UNAVAILABLE_TEST_ID = 'lineage-chain-unavailable';
/** 段与段之间那根连续竖线的 testID（每段一根，最后一段没有 ⇒ 数量 = 段数 − 1）。 */
export const CHAIN_CONNECTOR_TEST_ID = 'chain-group-connector';

/** 空态文案 —— 「确实没有上游」。 */
export const LINEAGE_EMPTY_TEXT = '无上游血缘记录';
/** 既没有批次号、也不许回显内部主键时的诚实说法。 */
export const UNNAMED_NODE_TEXT = '未命名批次';
/** 故障文案 —— 「这一次没取到」，⛔ 与空态不是同一句话。 */
export const LINEAGE_UNAVAILABLE_TEXT = '来源链暂时取不到，请稍后重试';

/**
 * DNA 文案 / 色板已抽到 `./dnaStatus`（四处共用）。这里再导出一次是为了不打断
 * 既有 import 路径；新代码请直接从 `./dnaStatus` 取。
 */
export { DNA_STATUS_LABEL, dnaStatusLabel };

const NODE_TYPE_LABEL: Record<string, string> = {
  MATERIAL_BATCH: '原料批次',
  PRODUCTION_BATCH: '生产批次',
  FINISHED_BATCH: '成品批次',
  SHIPMENT_RECORD: '出货单',
};

/**
 * 「品种 · 来源场」那一行。DNA 状态**不在这行里** —— 它由 `DnaStatusChip` 用统一色板画，
 * 塞进文本里就没法上色，四处也就不可能同色。
 *
 * 缺哪一项就<b>不显示哪一项</b>（用 · 连接实际有的部分），
 * ⛔ 不填「未知」占位 —— 占位会让「没登记来源场」长得和「来源场就叫未知」一样。
 */
export function individualSummaryLine(si: SourceIndividualBrief): string | null {
  const parts = [si.breed, si.originFarm]
    .filter((p): p is string => !!p && `${p}`.trim().length > 0);
  return parts.length === 0 ? null : parts.join(' · ');
}

export const NODE_TYPE_ICON: Record<string, React.ComponentProps<typeof MaterialCommunityIcons>['name']> = {
  MATERIAL_BATCH: 'sprout',
  PRODUCTION_BATCH: 'factory',
  FINISHED_BATCH: 'package-variant-closed',
  SHIPMENT_RECORD: 'truck-delivery',
};

export function nodeTypeIcon(nodeType: string): React.ComponentProps<typeof MaterialCommunityIcons>['name'] {
  return NODE_TYPE_ICON[nodeType] ?? 'help-circle-outline';
}

export interface LineageChainTimelineProps {
  lineage?: LineageSection | null;
  /** 是否展示供应商（内部页 true；公开页给 false —— 后端也不会下发，这里是第二道闸）。 */
  showSupplier?: boolean;
  title?: string;
}

/**
 * 节点的稳定标识（testID / React key 用）。
 * 公开侧 nodeId 为 null，退到 batchNumber；都没有就用类型占位 —— 只影响 key，不渲染给用户。
 */
function nodeKey(node: LineageNode): string {
  return node.nodeId || node.batchNumber || `${node.nodeType}-unknown`;
}

/** 可进的行右侧那个 chevron 的 testID —— 测试拿它守「这一行有进入的示意」。 */
export function nodeChevronTestId(node: LineageNode): string {
  return `lineage-node-chevron-${nodeKey(node)}`;
}

/** 按 depth 从远到近排（最远的来源排最上面，读起来就是「从哪来 → 到这里」）。 */
function sortFarToNear(groups: LineageDepthGroup[]): LineageDepthGroup[] {
  return [...groups].sort((a, b) => (b.depth ?? 0) - (a.depth ?? 0));
}

/**
 * 节点标题。
 *
 * 🔴 ⛔ **不要拿 nodeId 当兜底**：公开溯源页上后端已经把它置成 null（它是内部数据库主键，
 * PRODUCTION_BATCH 那一类还是顺序自增整数）。原来这里写的是
 * `node.batchNumber || node.nodeId`，等于把后端刚遮住的内部 ID 又渲染回屏幕上。
 * 没有批次号就如实说「未命名批次」——「不知道」比一个内部主键有用得多。
 */
function nodeTitle(node: LineageNode): string {
  if (node.batchNumber) {
    return node.batchNumber;
  }
  return UNNAMED_NODE_TEXT;
}

/** 这一行能不能点进「个体详情」——只看后端给没给个体主键。 */
export function individualDetailId(node: LineageNode): string | null {
  const id = node.sourceIndividual?.id;
  return typeof id === 'string' && id.length > 0 ? id : null;
}

// ─────────────────────────────────────────────────────────────────────────────
// 链的三个积木：ChainGroup / ChainRow / ChainLine。个体档案屏复用。
// ─────────────────────────────────────────────────────────────────────────────

export interface ChainGroupProps {
  /** 段标题：「直接来源」「往上第 2 层来源」「原料批次」「出货」…… */
  label: string;
  testID?: string;
  /** 最后一段下面不画连线。 */
  last?: boolean;
  children: React.ReactNode;
}

/** 圆点直径 / 连线宽度 / 圆点离段顶部的距离（对齐 labelLarge 一行 20sp 的中心）。 */
const DOT_SIZE = 8;
const LINE_WIDTH = 2;
const DOT_TOP = 6;
/** 圆点中心离段顶部的距离 —— 连线的上端锚在这里，下端越过段底边同样的距离，落在下一段的圆点中心。 */
const DOT_CENTER = DOT_TOP + DOT_SIZE / 2;

/**
 * 链上的一段：左侧一根「导轨」（圆点 primary + 连续竖线）+ 右侧正文
 * （标题 labelLarge，onSurfaceVariant；然后是段内的行）。
 *
 * 竖线**从本段圆点中心一直画到下一段圆点中心**：绝对定位在导轨里，`top` 锚在圆点中心，
 * `bottom` 为负、越过本段底边刚好到下一段的圆点中心。段有多高线就多长，
 * ⛔ 不写死高度（写死就回到「中间一小截、上下都不相连」）。最后一段不画。
 *
 * 标题不是可点元素，⛔ 不用 primary 上色（蓝色只给可点的东西）。
 */
export function ChainGroup({ label, testID, last = false, children }: ChainGroupProps) {
  const theme = useTheme<AppTheme>();
  return (
    <View testID={testID} style={styles.group}>
      <View style={styles.rail}>
        {!last && (
          <View
            testID={CHAIN_CONNECTOR_TEST_ID}
            style={[styles.connector, { backgroundColor: theme.custom.workflow.connector }]}
          />
        )}
        <View style={[styles.groupDot, { backgroundColor: theme.colors.primary }]} />
      </View>
      <View
        style={[
          styles.groupBody,
          { paddingLeft: theme.custom.spacing.s, paddingBottom: last ? 0 : theme.custom.spacing.m },
        ]}
      >
        <Text variant="labelLarge" style={{ color: theme.colors.onSurfaceVariant }}>
          {label}
        </Text>
        {children}
      </View>
    </View>
  );
}

export interface ChainRowProps {
  icon: React.ComponentProps<typeof MaterialCommunityIcons>['name'];
  /** 读屏用的 icon 说明（「原料批次」这类）—— 视觉上不再单独放一个 chip。 */
  iconLabel?: string;
  testID?: string;
  /** 给了就整行可点（TouchableRipple，≥48dp）并在右侧画 chevron；不给就是普通行。 */
  onPress?: () => void;
  chevronTestID?: string;
  children: React.ReactNode;
}

/**
 * 链上的一行：左 icon、中间正文、可进时右侧 chevron。
 * 可点的行**整行**是触控目标（minHeight 48），⛔ 不再用一行蓝字当链接。
 * 左边不再自己缩进 —— 与段标题对齐由 `ChainGroup` 的导轨 + 正文列负责。
 */
export function ChainRow({ icon, iconLabel, testID, onPress, chevronTestID, children }: ChainRowProps) {
  const theme = useTheme<AppTheme>();
  const inner = (
    <View
      style={[
        styles.row,
        {
          gap: theme.custom.spacing.m,
          paddingVertical: theme.custom.spacing.s,
        },
      ]}
    >
      <MaterialCommunityIcons
        name={icon}
        size={20}
        color={theme.colors.onSurfaceVariant}
        accessibilityLabel={iconLabel}
        style={styles.rowIcon}
      />
      <View style={styles.rowBody}>{children}</View>
      {!!onPress && (
        <MaterialCommunityIcons
          name="chevron-right"
          size={24}
          color={theme.colors.onSurfaceVariant}
          testID={chevronTestID}
        />
      )}
    </View>
  );
  if (onPress) {
    return (
      <TouchableRipple
        onPress={onPress}
        accessibilityRole="button"
        testID={testID}
        style={styles.rowTouchable}
      >
        {inner}
      </TouchableRipple>
    );
  }
  return (
    <View testID={testID} style={styles.rowTouchable}>
      {inner}
    </View>
  );
}

export interface ChainLineProps {
  /** body：正文（onSurface）；muted：次要（onSurfaceVariant）；error：「已不可查」这类（onErrorContainer）。 */
  emphasis?: 'body' | 'muted' | 'error';
  testID?: string;
  children: React.ReactNode;
}

/** 行内的一行文字 —— bodyMedium（14sp），三种强调只换颜色，⛔ 不换字号。 */
export function ChainLine({ emphasis = 'muted', testID, children }: ChainLineProps) {
  const theme = useTheme<AppTheme>();
  const color =
    emphasis === 'body'
      ? theme.colors.onSurface
      : emphasis === 'error'
        ? theme.colors.onErrorContainer
        : theme.colors.onSurfaceVariant;
  return (
    <Text variant="bodyMedium" style={{ color }} testID={testID}>
      {children}
    </Text>
  );
}

/** 行标题：批次号 —— titleSmall，onSurface。 */
export function ChainTitle({ children, testID }: { children: React.ReactNode; testID?: string }) {
  const theme = useTheme<AppTheme>();
  return (
    <Text variant="titleSmall" style={{ color: theme.colors.onSurface }} testID={testID}>
      {children}
    </Text>
  );
}

type LineageNav = NativeStackNavigationProp<ProcessingStackParamList>;

function NodeRow({ node, showSupplier }: { node: LineageNode; showSupplier: boolean }) {
  const navigation = useNavigation<LineageNav>();
  const theme = useTheme<AppTheme>();
  // resolved === false ⇒ 批次实体查不到（被软删/清理）。
  // ⛔ 不编名字：说一句实话，让「查不到」与「叫什么」区分得开。
  const unresolved = node.resolved === false;
  /**
   * 挂了个体、且后端给了个体主键 ⇒ 整行可点，进个体详情（系谱 + 去向）。
   * 公开侧 `sourceIndividual.id` 恒为 null（与 nodeId 同一条纪律）⇒ 不可点 ——
   * ⛔ 不拿 individualCode / tagNumber 当兜底去猜一个 id。
   */
  const detailId = individualDetailId(node);
  const onPress = detailId
    ? () => navigation.navigate('SourceIndividualDetail', { individualId: detailId })
    : undefined;

  return (
    <ChainRow
      icon={nodeTypeIcon(node.nodeType)}
      iconLabel={NODE_TYPE_LABEL[node.nodeType] ?? node.nodeType}
      testID={`lineage-node-${nodeKey(node)}`}
      onPress={onPress}
      chevronTestID={nodeChevronTestId(node)}
    >
      <ChainTitle>{nodeTitle(node)}</ChainTitle>
      {unresolved ? (
        <ChainLine emphasis="error">该批次记录已不可查（血缘保留，批次已归档）</ChainLine>
      ) : (
        <>
          {!!node.name && <ChainLine>{node.name}</ChainLine>}
          {showSupplier && !!node.supplierName && <ChainLine>供应商：{node.supplierName}</ChainLine>}
        </>
      )}
      {node.quantity != null && (
        <ChainLine>
          投入量：{node.quantity}
          {node.unit || ''}
        </ChainLine>
      )}
      {/* 源头个体：这块肉来自哪头牛。⚠️ 没有个体时整段不渲染 ——
          绝大多数原料（调料、包材）本来就没有个体，那不是缺陷。 */}
      {!!node.sourceIndividual && (
        <View testID={`lineage-individual-${nodeKey(node)}`} style={{ marginTop: theme.custom.spacing.xs }}>
          {!!individualSummaryLine(node.sourceIndividual) && (
            <ChainLine emphasis="body">{individualSummaryLine(node.sourceIndividual)}</ChainLine>
          )}
          {!!node.sourceIndividual.tagNumber && (
            <ChainLine>耳标：{node.sourceIndividual.tagNumber}</ChainLine>
          )}
          <DnaStatusChip
            status={node.sourceIndividual.dnaVerificationStatus}
            testID={`lineage-individual-dna-${nodeKey(node)}`}
            style={{ marginTop: theme.custom.spacing.xs }}
          />
        </View>
      )}
    </ChainRow>
  );
}

/**
 * 段标题的样式与溯源详情页其它段一致：左侧 primary 色 icon + titleMedium，
 * 右侧计数用 labelMedium 文本，⛔ 不再一段一个彩色圆底。
 */
export function SectionHeader({
  icon,
  title,
  trailing,
}: {
  icon: React.ComponentProps<typeof MaterialCommunityIcons>['name'];
  title: string;
  trailing?: string | null;
}) {
  const theme = useTheme<AppTheme>();
  return (
    <View style={[styles.sectionHeader, { gap: theme.custom.spacing.s }]}>
      <MaterialCommunityIcons name={icon} size={22} color={theme.colors.primary} />
      <Text variant="titleMedium" style={[styles.sectionTitle, { color: theme.colors.onSurface }]}>
        {title}
      </Text>
      {!!trailing && (
        <Text variant="labelMedium" style={{ color: theme.colors.onSurfaceVariant }}>
          {trailing}
        </Text>
      )}
    </View>
  );
}

const LineageChainTimeline: React.FC<LineageChainTimelineProps> = ({
  lineage,
  showSupplier = false,
  title = '来源链',
}) => {
  const theme = useTheme<AppTheme>();
  const { t } = useTranslation('processing');
  const cardStyle = [
    styles.card,
    { marginHorizontal: theme.custom.spacing.l, marginBottom: theme.custom.spacing.m, borderRadius: theme.custom.borderRadius.m },
  ];
  const dividerStyle = { marginVertical: theme.custom.spacing.m };
  const emptyTextStyle = { color: theme.colors.onSurfaceVariant, paddingVertical: theme.custom.spacing.s };

  // 「来源 N 项」—— N 是后端按真实节点算的 nodeCount，这里只显示，⛔ 不在前端重新数。
  const header = (
    <SectionHeader
      icon="file-tree-outline"
      title={title}
      trailing={
        !!lineage && lineage.nodeCount > 0 ? t('traceability.lineage.sourceCount', { n: lineage.nodeCount }) : null
      }
    />
  );

  // ① 本次没拿到 —— 与「没有上游」是两句话。
  if (lineage == null) {
    return (
      <Card style={cardStyle} testID={LINEAGE_UNAVAILABLE_TEST_ID}>
        <Card.Content>
          {header}
          <Divider style={dividerStyle} />
          <Text variant="bodyMedium" style={emptyTextStyle}>{LINEAGE_UNAVAILABLE_TEXT}</Text>
        </Card.Content>
      </Card>
    );
  }

  const groups = sortFarToNear(lineage.ancestors ?? []);

  // ② 确实没有上游。⛔ 不隐藏整张卡 —— 隐藏会让「没有」和「坏了」都变成「这块不存在」。
  if (groups.length === 0) {
    return (
      <Card style={cardStyle} testID={LINEAGE_EMPTY_TEST_ID}>
        <Card.Content>
          {header}
          <Divider style={dividerStyle} />
          <Text variant="bodyMedium" style={emptyTextStyle}>{LINEAGE_EMPTY_TEXT}</Text>
        </Card.Content>
      </Card>
    );
  }

  // ③ 有链。
  return (
    <Card style={cardStyle} testID={LINEAGE_TIMELINE_TEST_ID}>
      <Card.Content>
        {header}
        <Divider style={dividerStyle} />
        {groups.map((group, index) => (
          <ChainGroup
            key={`depth-${group.depth}`}
            testID={`lineage-depth-${group.depth}`}
            label={
              group.depth === 1
                ? t('traceability.lineage.directSource')
                : t('traceability.lineage.upstreamLevel', { depth: group.depth })
            }
            last={index === groups.length - 1}
          >
            {(group.nodes ?? []).map((node) => (
              <NodeRow key={`${node.nodeType}#${nodeKey(node)}`} node={node} showSupplier={showSupplier} />
            ))}
          </ChainGroup>
        ))}
      </Card.Content>
    </Card>
  );
};

const styles = StyleSheet.create({
  card: {},
  sectionHeader: { flexDirection: 'row', alignItems: 'center' },
  sectionTitle: { flex: 1 },
  group: { flexDirection: 'row', alignItems: 'stretch' },
  // 导轨：与段同高（stretch），线是绝对定位、可以越出下边界去接下一段的圆点
  rail: { width: DOT_SIZE, overflow: 'visible' },
  groupBody: { flex: 1 },
  groupDot: { width: DOT_SIZE, height: DOT_SIZE, borderRadius: DOT_SIZE / 2, marginTop: DOT_TOP },
  // 连线：横向对齐圆点中心（圆点 8 宽，线 2 宽 ⇒ 左移 3）；
  // 纵向从本段圆点中心（top）到下一段圆点中心（bottom 为负、越过本段底边同样的距离）。
  // ⛔ 不写 height —— 段有多高线就多长，这正是「连续」的全部含义。
  connector: {
    position: 'absolute',
    width: LINE_WIDTH,
    left: (DOT_SIZE - LINE_WIDTH) / 2,
    top: DOT_CENTER,
    bottom: -DOT_CENTER,
  },
  rowTouchable: { minHeight: 48, justifyContent: 'center' },
  row: { flexDirection: 'row', alignItems: 'center' },
  rowIcon: { alignSelf: 'flex-start', marginTop: 2 },
  rowBody: { flex: 1 },
});

export default LineageChainTimeline;
