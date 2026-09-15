/**
 * SourceIndividualDetailScreen — 源头个体档案详情 (只读)
 *
 * 这头牛(猪/鸭…)是谁、父母是谁、它去了哪些批次/成品、同一头种公牛的其它后代。
 * 「从一块好肉倒推到公牛、再看这头公牛的其它后代」是选种的最小闭环。
 * 物种是字段值 —— 本屏对所有物种通用, ⛔ 不把「牛」焊进代码。
 *
 * 进入方式:
 *  · 拍照核验命中 → 「查看档案」
 *  · 溯源详情的来源链上, 挂了个体的原料批次那一行 (内部侧)
 *  · 本屏系谱 / 同父后代里的任意一行 (同屏 push 换 id)
 *
 * ⚠️ 路由参数 `individualId` 必填, 但仍用 `route.params ?? {}` 取值 ——
 *    缺参时显示一句「缺少个体 ID」, ⛔ 不 destructure 崩掉 (本仓 40 个屏踩过)。
 *
 * ⛔ 三态分开: 加载中 / 出错(把错误原话显示出来, 不留空屏) / 有数据。
 * ⛔ 「未登记」「档案已不可查」「确实没有」三种「没有」分开说, 不显示假值。
 *
 * 视觉 (2026-09-15 polish): 颜色全部走 `useTheme()`; 「去了哪」是来源链的反向,
 * 复用 `LineageChainTimeline` 导出的 ChainGroup / ChainRow / ChainLine, ⛔ 不抄一份样式;
 * DNA 状态经 `DnaStatusChip` 用四处共用的色板; 可点的行是 ≥48dp 的 List.Item / ChainRow,
 * ⛔ 不再用一行蓝字当链接。
 */
import React, { useCallback, useEffect, useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { ActivityIndicator, Appbar, Button, Card, Divider, List, Text, useTheme } from 'react-native-paper';
import { useNavigation, useRoute, type RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTranslation } from 'react-i18next';

import {
  sourceIndividualApiClient,
  type SourceIndividual,
  type SourceIndividualBatchSummary,
  type SourceIndividualDetail,
  type SourceIndividualSummary,
} from '../../services/api/sourceIndividualApiClient';
import type { LineageNode } from '../../services/api/traceabilityApiClient';
import {
  ChainGroup,
  ChainLine,
  ChainRow,
  ChainTitle,
  nodeTypeIcon,
} from '../traceability/components/LineageChainTimeline';
import { DnaStatusChip, dnaStatusLabel } from '../traceability/components/dnaStatus';
import type { AppTheme } from '../../theme';
import type { ProcessingStackParamList } from '../../types/navigation';

type Nav = NativeStackNavigationProp<ProcessingStackParamList, 'SourceIndividualDetail'>;
type Route = RouteProp<ProcessingStackParamList, 'SourceIndividualDetail'>;

export const SI_DETAIL_TEST_IDS = {
  loading: 'source-individual-detail-loading',
  error: 'source-individual-detail-error',
  missingParam: 'source-individual-detail-missing-param',
  screen: 'source-individual-detail',
  edit: 'si-detail-edit',
  sireRow: 'si-detail-sire-row',
  damRow: 'si-detail-dam-row',
  batchesEmpty: 'si-detail-batches-empty',
  notInProduction: 'si-detail-not-in-production',
  siblingsEmpty: 'si-detail-siblings-empty',
  offspringEmpty: 'si-detail-offspring-empty',
  /** 头部那颗 DNA chip —— 四处同色板的其中一处。 */
  dna: 'si-detail-dna',
  /** 行上的 DNA chip：`si-detail-dna-<row>`。⚠️ 与行本身的 testID 前缀分开 —— 既有测试按
   *  `/^si-detail-sibling-/` 数「几行」，chip 若共用前缀会把 2 行数成 4。 */
  dnaOf: (row: string) => `si-detail-dna-${row}`,
} as const;

/** 耳标末 4 位 —— 列表行上够用, 完整耳标在头部。⚠️ 耳标本身 ≤4 位就整串给。 */
function tagTail(tagNumber?: string | null): string | null {
  if (!tagNumber) {
    return null;
  }
  const trimmed = tagNumber.trim();
  return trimmed.length <= 4 ? trimmed : trimmed.slice(-4);
}

/**
 * 「品种 · 来源场 · 性别 · 出生」这类拼接行: 缺哪一项就不显示哪一项。
 * ⛔ 不填「未知」占位 —— 占位会让「没登记」长得和「就叫未知」一样。
 */
function joinPresent(parts: Array<string | null | undefined>): string | null {
  const present = parts.filter((p): p is string => !!p && p.trim().length > 0);
  return present.length === 0 ? null : present.join(' · ');
}

/**
 * 列表行的描述: 「标牌末四位 0003 · 已采样」。
 * ⚠️ DNA 文案仍在这一行里 (读屏 / 搜索都靠它); 颜色由旁边的 `DnaStatusChip` 表达。
 */
function summaryDescription(t: (k: string, o?: Record<string, unknown>) => string, s: SourceIndividualSummary): string | null {
  const tail = tagTail(s.tagNumber);
  return joinPresent([
    tail ? t('sourceIndividual.detail.tagShort', { tail }) : null,
    dnaStatusLabel(s.dnaVerificationStatus),
  ]);
}

function nodeTitle(t: (k: string) => string, node: LineageNode): string {
  // ⛔ 不拿 nodeId 当兜底 —— 与 LineageChainTimeline 同一条纪律。
  return node.batchNumber || t('sourceIndividual.detail.unnamedBatch');
}

export default function SourceIndividualDetailScreen() {
  const { t } = useTranslation('processing');
  const theme = useTheme<AppTheme>();
  const navigation = useNavigation<Nav>();
  const route = useRoute<Route>();
  // ⚠️ 不 destructure route.params 本体 —— 它可能是 undefined。
  const { individualId } = route.params ?? {};

  const [detail, setDetail] = useState<SourceIndividualDetail | null>(null);
  const [loading, setLoading] = useState(Boolean(individualId));
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!individualId) {
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    (async () => {
      try {
        const data = await sourceIndividualApiClient.getDetail(individualId);
        if (cancelled) return;
        setDetail(data);
      } catch (e) {
        if (cancelled) return;
        // ⛔ 失败就把错误说出来, 不留一张空档案让人以为「这头没有系谱」。
        setError(e instanceof Error ? e.message : t('sourceIndividual.detail.loadFailed'));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [individualId, attempt, t]);

  const openIndividual = useCallback(
    (id: string) => {
      // push 而不是 navigate: 同一个屏换 id, 返回时能一层层退回来。
      navigation.push('SourceIndividualDetail', { individualId: id });
    },
    [navigation]
  );

  const header = (title: string) => (
    <Appbar.Header>
      <Appbar.BackAction onPress={() => navigation.goBack()} />
      <Appbar.Content title={title} />
      {!!individualId && (
        <Appbar.Action
          icon="pencil-outline"
          accessibilityLabel={t('sourceIndividual.detail.edit')}
          testID={SI_DETAIL_TEST_IDS.edit}
          onPress={() => navigation.navigate('SourceIndividualForm', { individualId })}
        />
      )}
    </Appbar.Header>
  );

  const { spacing, borderRadius } = theme.custom;
  const containerStyle = [styles.container, { backgroundColor: theme.colors.background }];
  const centerStyle = [styles.center, { padding: spacing.xl, gap: spacing.m }];
  const cardStyle = [{ marginHorizontal: spacing.l, marginBottom: spacing.m, borderRadius: borderRadius.m }];
  const dividerStyle = { marginVertical: spacing.m };
  const mutedStyle = { color: theme.colors.onSurfaceVariant, paddingVertical: spacing.s };

  if (!individualId) {
    return (
      <View style={containerStyle}>
        {header(t('sourceIndividual.detail.title'))}
        <View style={centerStyle} testID={SI_DETAIL_TEST_IDS.missingParam}>
          <Text variant="bodyMedium">{t('sourceIndividual.detail.missingParam')}</Text>
        </View>
      </View>
    );
  }

  if (loading) {
    return (
      <View style={containerStyle}>
        {header(t('sourceIndividual.detail.title'))}
        <View style={centerStyle} testID={SI_DETAIL_TEST_IDS.loading}>
          <ActivityIndicator />
        </View>
      </View>
    );
  }

  if (error || !detail) {
    return (
      <View style={containerStyle}>
        {header(t('sourceIndividual.detail.title'))}
        <View style={centerStyle} testID={SI_DETAIL_TEST_IDS.error}>
          <Text variant="bodyMedium" style={[styles.errorText, { color: theme.colors.error }]}>
            {error ?? t('sourceIndividual.detail.loadFailed')}
          </Text>
          <Button mode="contained-tonal" onPress={() => setAttempt((n) => n + 1)}>
            {t('sourceIndividual.detail.retry')}
          </Button>
        </View>
      </View>
    );
  }

  const { individual, sire, dam, offspring, siblingsBySire, batches, downstream } = detail;
  const hasDownstream =
    downstream.productionBatches.length + downstream.finishedBatches.length + downstream.shipments.length > 0;

  // 「去了哪」按链的方向排: 原料批次 → 生产批次 → 成品 → 出货。只列真有内容的段。
  const whereaboutGroups: Array<{ key: string; label: string; nodes: LineageNode[] }> = [
    { key: 'production', label: t('sourceIndividual.detail.productionBatches'), nodes: downstream.productionBatches },
    { key: 'finished', label: t('sourceIndividual.detail.finishedBatches'), nodes: downstream.finishedBatches },
    { key: 'shipments', label: t('sourceIndividual.detail.shipments'), nodes: downstream.shipments },
  ].filter((g) => g.nodes.length > 0);

  return (
    <View style={containerStyle} testID={SI_DETAIL_TEST_IDS.screen}>
      {header(individual.individualCode)}
      <ScrollView contentContainerStyle={{ paddingVertical: spacing.m, paddingBottom: spacing.xxl }}>
        <IdentityCard t={t} individual={individual} cardStyle={cardStyle} />

        {/* 系谱 */}
        <Card style={cardStyle}>
          <Card.Content>
            <Text variant="titleMedium" style={{ color: theme.colors.onSurface }}>
              {t('sourceIndividual.detail.pedigreeSection')}
            </Text>
            <Divider style={dividerStyle} />
            <ParentRow
              t={t}
              testID={SI_DETAIL_TEST_IDS.sireRow}
              dnaTestID={SI_DETAIL_TEST_IDS.dnaOf('sire')}
              label={t('sourceIndividual.fields.sire')}
              summary={sire}
              registeredId={individual.sireIndividualId}
              onPress={openIndividual}
            />
            <ParentRow
              t={t}
              testID={SI_DETAIL_TEST_IDS.damRow}
              dnaTestID={SI_DETAIL_TEST_IDS.dnaOf('dam')}
              label={t('sourceIndividual.fields.dam')}
              summary={dam}
              registeredId={individual.damIndividualId}
              onPress={openIndividual}
            />
          </Card.Content>
        </Card>

        {/* 去了哪: 原料批次 → 生产批次 → 成品 → 出货 —— 来源链的反向, 同一套链的积木 */}
        <Card style={cardStyle}>
          <Card.Content>
            <Text variant="titleMedium" style={{ color: theme.colors.onSurface }}>
              {t('sourceIndividual.detail.whereaboutsSection')}
            </Text>
            <Divider style={dividerStyle} />

            <ChainGroup
              label={t('sourceIndividual.detail.materialBatches')}
              testID="si-detail-group-materials"
              last={whereaboutGroups.length === 0}
            >
              {batches.length === 0 ? (
                <Text variant="bodyMedium" style={mutedStyle} testID={SI_DETAIL_TEST_IDS.batchesEmpty}>
                  {t('sourceIndividual.detail.noBatches')}
                </Text>
              ) : (
                batches.map((b) => <BatchRow key={b.id} t={t} batch={b} />)
              )}
              {batches.length > 0 && !hasDownstream && (
                <Text variant="bodyMedium" style={mutedStyle} testID={SI_DETAIL_TEST_IDS.notInProduction}>
                  {t('sourceIndividual.detail.notInProduction')}
                </Text>
              )}
            </ChainGroup>

            {whereaboutGroups.map((g, index) => (
              <ChainGroup
                key={g.key}
                label={g.label}
                testID={`si-detail-group-${g.key}`}
                last={index === whereaboutGroups.length - 1}
              >
                {g.nodes.map((node) => (
                  <ChainRow
                    key={`${node.nodeType}#${node.nodeId ?? node.batchNumber ?? 'unnamed'}`}
                    icon={nodeTypeIcon(node.nodeType)}
                    testID={`si-detail-node-${node.nodeId ?? node.batchNumber ?? 'unnamed'}`}
                  >
                    <ChainTitle>{nodeTitle(t, node)}</ChainTitle>
                    {node.resolved === false ? (
                      <ChainLine emphasis="error">{t('sourceIndividual.detail.batchUnresolved')}</ChainLine>
                    ) : (
                      !!node.name && <ChainLine>{node.name}</ChainLine>
                    )}
                  </ChainRow>
                ))}
              </ChainGroup>
            ))}
          </Card.Content>
        </Card>

        {/* 同父后代 */}
        <Card style={cardStyle}>
          <Card.Content>
            <Text variant="titleMedium" style={{ color: theme.colors.onSurface }}>
              {t('sourceIndividual.detail.siblingsSection')}
            </Text>
            <Divider style={dividerStyle} />
            {siblingsBySire.length === 0 ? (
              <Text variant="bodyMedium" style={mutedStyle} testID={SI_DETAIL_TEST_IDS.siblingsEmpty}>
                {individual.sireIndividualId
                  ? t('sourceIndividual.detail.noSiblings')
                  : t('sourceIndividual.detail.siblingsNoSire')}
              </Text>
            ) : (
              siblingsBySire.map((s) => (
                <IndividualRow
                  key={s.id}
                  t={t}
                  testID={`si-detail-sibling-${s.id}`}
                  dnaTestID={SI_DETAIL_TEST_IDS.dnaOf(`sibling-${s.id}`)}
                  summary={s}
                  onPress={openIndividual}
                />
              ))
            )}
          </Card.Content>
        </Card>

        {/* 后代 —— 对种公牛/母牛这一屏最有用的那一段 */}
        <Card style={cardStyle}>
          <Card.Content>
            <Text variant="titleMedium" style={{ color: theme.colors.onSurface }}>
              {t('sourceIndividual.detail.offspringSection')}
            </Text>
            <Divider style={dividerStyle} />
            {offspring.length === 0 ? (
              <Text variant="bodyMedium" style={mutedStyle} testID={SI_DETAIL_TEST_IDS.offspringEmpty}>
                {t('sourceIndividual.detail.noOffspring')}
              </Text>
            ) : (
              offspring.map((s) => (
                <IndividualRow
                  key={s.id}
                  t={t}
                  testID={`si-detail-offspring-${s.id}`}
                  dnaTestID={SI_DETAIL_TEST_IDS.dnaOf(`offspring-${s.id}`)}
                  summary={s}
                  onPress={openIndividual}
                />
              ))
            )}
          </Card.Content>
        </Card>
      </ScrollView>
    </View>
  );
}

type T = (k: string, o?: Record<string, unknown>) => string;

function IdentityCard({
  t,
  individual,
  cardStyle,
}: {
  t: T;
  individual: SourceIndividual;
  cardStyle: React.ComponentProps<typeof Card>['style'];
}) {
  const theme = useTheme<AppTheme>();
  const line1 = joinPresent([individual.species, individual.breed, individual.sex]);
  const metaStyle = { color: theme.colors.onSurfaceVariant, marginTop: theme.custom.spacing.xs };
  return (
    <Card style={cardStyle} testID="si-detail-identity">
      <Card.Content>
        <View style={[styles.titleRow, { gap: theme.custom.spacing.s }]}>
          <Text variant="headlineSmall" style={[styles.code, { color: theme.colors.onSurface }]}>
            {individual.individualCode}
          </Text>
          <DnaStatusChip status={individual.dnaVerificationStatus} testID={SI_DETAIL_TEST_IDS.dna} />
        </View>
        {!!individual.tagNumber && (
          <Text variant="bodyMedium" style={metaStyle}>
            {t('sourceIndividual.fields.tagNumber')}：{individual.tagNumber}
          </Text>
        )}
        {!!line1 && (
          <Text variant="bodyMedium" style={metaStyle}>
            {line1}
          </Text>
        )}
        {!!individual.birthDate && (
          <Text variant="bodyMedium" style={metaStyle}>
            {t('sourceIndividual.fields.birthDate')}：{individual.birthDate}
          </Text>
        )}
        {!!individual.originFarm && (
          <Text variant="bodyMedium" style={metaStyle}>
            {t('sourceIndividual.fields.originFarm')}：{individual.originFarm}
          </Text>
        )}
      </Card.Content>
    </Card>
  );
}

/**
 * 父本 / 母本一行 (List.Item, ≥48dp)。三种状态:
 *   有摘要          → 可点, 同屏换 id; onSurface 字 + 右侧 chevron
 *   无摘要、有 id   → 「档案已不可查」(登记过、被删了) —— onErrorContainer 字, 不可点、无 chevron
 *   无摘要、无 id   → 「未登记」 —— onSurfaceVariant 字, 不可点、无 chevron
 */
function ParentRow({
  t,
  testID,
  dnaTestID,
  label,
  summary,
  registeredId,
  onPress,
}: {
  t: T;
  testID: string;
  dnaTestID: string;
  label: string;
  summary: SourceIndividualSummary | null;
  registeredId?: string | null;
  onPress: (id: string) => void;
}) {
  const theme = useTheme<AppTheme>();
  if (summary) {
    return (
      <List.Item
        testID={testID}
        title={`${label} ${summary.individualCode}`}
        titleStyle={{ color: theme.colors.onSurface }}
        description={summaryDescription(t, summary)}
        descriptionStyle={{ color: theme.colors.onSurfaceVariant }}
        onPress={() => onPress(summary.id)}
        right={(props) => (
          <View style={[styles.rowRight, { gap: theme.custom.spacing.s }]}>
            <DnaStatusChip status={summary.dnaVerificationStatus} testID={dnaTestID} />
            <List.Icon {...props} icon="chevron-right" />
          </View>
        )}
        style={styles.listItem}
      />
    );
  }
  const unavailable = !!registeredId;
  return (
    <List.Item
      testID={testID}
      title={label}
      titleStyle={{ color: theme.colors.onSurface }}
      description={unavailable ? t('sourceIndividual.detail.unavailable') : t('sourceIndividual.detail.notRegistered')}
      descriptionStyle={{ color: unavailable ? theme.colors.onErrorContainer : theme.colors.onSurfaceVariant }}
      style={styles.listItem}
    />
  );
}

/**
 * 后代 / 同父后代的一行 (List.Item, ≥48dp):
 * 标题 = 编码, 描述 = 「标牌末四位 0003 · 已采样」, 右侧 DNA chip (统一色板) + chevron。
 */
function IndividualRow({
  t,
  testID,
  dnaTestID,
  summary,
  onPress,
}: {
  t: T;
  testID: string;
  dnaTestID: string;
  summary: SourceIndividualSummary;
  onPress: (id: string) => void;
}) {
  const theme = useTheme<AppTheme>();
  const line2 = joinPresent([summary.species, summary.breed, summary.sex]);
  const description = joinPresent([summaryDescription(t, summary), line2]);
  return (
    <List.Item
      testID={testID}
      title={summary.individualCode}
      titleStyle={{ color: theme.colors.onSurface }}
      description={description}
      descriptionStyle={{ color: theme.colors.onSurfaceVariant }}
      onPress={() => onPress(summary.id)}
      right={(props) => (
        <View style={[styles.rowRight, { gap: theme.custom.spacing.s }]}>
          <DnaStatusChip status={summary.dnaVerificationStatus} testID={dnaTestID} />
          <List.Icon {...props} icon="chevron-right" />
        </View>
      )}
      style={styles.listItem}
    />
  );
}

/** 「去了哪」第一跳: 挂了该个体的原料批次 (不可点 —— 批次没有详情屏)。 */
function BatchRow({ t, batch }: { t: T; batch: SourceIndividualBatchSummary }) {
  const qty = batch.quantity != null ? `${batch.quantity}${batch.unit ?? ''}` : null;
  return (
    <ChainRow icon={nodeTypeIcon('MATERIAL_BATCH')} testID={`si-detail-batch-${batch.id}`}>
      <ChainTitle>{batch.batchNumber}</ChainTitle>
      {!!joinPresent([batch.materialName, qty]) && <ChainLine>{joinPresent([batch.materialName, qty])}</ChainLine>}
      {!!batch.receiptDate && (
        <ChainLine>
          {t('sourceIndividual.detail.receiptDate')}：{batch.receiptDate}
        </ChainLine>
      )}
    </ChainRow>
  );
}

// ⚠️ 这里只放布局: 颜色 / 字号 / 间距全部走 useTheme() 与 Paper 的 variant,
//    `gates/demoScreensUseThemeTokens` 守着本文件里不再出现色值与 fontSize<14。
const styles = StyleSheet.create({
  container: { flex: 1 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  errorText: { textAlign: 'center' },
  titleRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  code: { flexShrink: 1 },
  listItem: { minHeight: 48, paddingHorizontal: 0 },
  rowRight: { flexDirection: 'row', alignItems: 'center' },
});
