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
 */
import React, { useCallback, useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { ActivityIndicator, Appbar, Button, Card, Chip, Divider, Text } from 'react-native-paper';
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
import { dnaStatusLabel } from '../traceability/components/LineageChainTimeline';
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

function summaryLine(t: (k: string, o?: Record<string, unknown>) => string, s: SourceIndividualSummary): string {
  const tail = tagTail(s.tagNumber);
  return joinPresent([
    s.individualCode,
    tail ? t('sourceIndividual.detail.tagShort', { tail }) : null,
    dnaStatusLabel(s.dnaVerificationStatus),
  ]) ?? s.individualCode;
}

function nodeTitle(t: (k: string) => string, node: LineageNode): string {
  // ⛔ 不拿 nodeId 当兜底 —— 与 LineageChainTimeline 同一条纪律。
  return node.batchNumber || t('sourceIndividual.detail.unnamedBatch');
}

export default function SourceIndividualDetailScreen() {
  const { t } = useTranslation('processing');
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

  if (!individualId) {
    return (
      <View style={styles.container}>
        {header(t('sourceIndividual.detail.title'))}
        <View style={styles.center} testID={SI_DETAIL_TEST_IDS.missingParam}>
          <Text variant="bodyMedium">{t('sourceIndividual.detail.missingParam')}</Text>
        </View>
      </View>
    );
  }

  if (loading) {
    return (
      <View style={styles.container}>
        {header(t('sourceIndividual.detail.title'))}
        <View style={styles.center} testID={SI_DETAIL_TEST_IDS.loading}>
          <ActivityIndicator />
        </View>
      </View>
    );
  }

  if (error || !detail) {
    return (
      <View style={styles.container}>
        {header(t('sourceIndividual.detail.title'))}
        <View style={styles.center} testID={SI_DETAIL_TEST_IDS.error}>
          <Text variant="bodyMedium" style={styles.errorText}>
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

  return (
    <View style={styles.container} testID={SI_DETAIL_TEST_IDS.screen}>
      {header(individual.individualCode)}
      <ScrollView contentContainerStyle={styles.content}>
        <IdentityCard t={t} individual={individual} />

        {/* 系谱 */}
        <Card style={styles.card}>
          <Card.Content>
            <Text variant="titleMedium" style={styles.sectionTitle}>
              {t('sourceIndividual.detail.pedigreeSection')}
            </Text>
            <Divider style={styles.divider} />
            <ParentRow
              t={t}
              testID={SI_DETAIL_TEST_IDS.sireRow}
              label={t('sourceIndividual.fields.sire')}
              summary={sire}
              registeredId={individual.sireIndividualId}
              onPress={openIndividual}
            />
            <ParentRow
              t={t}
              testID={SI_DETAIL_TEST_IDS.damRow}
              label={t('sourceIndividual.fields.dam')}
              summary={dam}
              registeredId={individual.damIndividualId}
              onPress={openIndividual}
            />
          </Card.Content>
        </Card>

        {/* 去了哪: 原料批次 → 生产批次 → 成品 → 出货 */}
        <Card style={styles.card}>
          <Card.Content>
            <Text variant="titleMedium" style={styles.sectionTitle}>
              {t('sourceIndividual.detail.whereaboutsSection')}
            </Text>
            <Divider style={styles.divider} />

            <Text style={styles.groupLabel}>{t('sourceIndividual.detail.materialBatches')}</Text>
            {batches.length === 0 ? (
              <Text style={styles.muted} testID={SI_DETAIL_TEST_IDS.batchesEmpty}>
                {t('sourceIndividual.detail.noBatches')}
              </Text>
            ) : (
              batches.map((b) => <BatchRow key={b.id} t={t} batch={b} />)
            )}

            {batches.length > 0 && !hasDownstream && (
              <Text style={styles.muted} testID={SI_DETAIL_TEST_IDS.notInProduction}>
                {t('sourceIndividual.detail.notInProduction')}
              </Text>
            )}
            {downstream.productionBatches.length > 0 && (
              <NodeGroup t={t} label={t('sourceIndividual.detail.productionBatches')} nodes={downstream.productionBatches} />
            )}
            {downstream.finishedBatches.length > 0 && (
              <NodeGroup t={t} label={t('sourceIndividual.detail.finishedBatches')} nodes={downstream.finishedBatches} />
            )}
            {downstream.shipments.length > 0 && (
              <NodeGroup t={t} label={t('sourceIndividual.detail.shipments')} nodes={downstream.shipments} />
            )}
          </Card.Content>
        </Card>

        {/* 同父后代 */}
        <Card style={styles.card}>
          <Card.Content>
            <Text variant="titleMedium" style={styles.sectionTitle}>
              {t('sourceIndividual.detail.siblingsSection')}
            </Text>
            <Divider style={styles.divider} />
            {siblingsBySire.length === 0 ? (
              <Text style={styles.muted} testID={SI_DETAIL_TEST_IDS.siblingsEmpty}>
                {individual.sireIndividualId
                  ? t('sourceIndividual.detail.noSiblings')
                  : t('sourceIndividual.detail.siblingsNoSire')}
              </Text>
            ) : (
              siblingsBySire.map((s) => (
                <IndividualRow key={s.id} t={t} testID={`si-detail-sibling-${s.id}`} summary={s} onPress={openIndividual} />
              ))
            )}
          </Card.Content>
        </Card>

        {/* 后代 —— 对种公牛/母牛这一屏最有用的那一段 */}
        <Card style={styles.card}>
          <Card.Content>
            <Text variant="titleMedium" style={styles.sectionTitle}>
              {t('sourceIndividual.detail.offspringSection')}
            </Text>
            <Divider style={styles.divider} />
            {offspring.length === 0 ? (
              <Text style={styles.muted} testID={SI_DETAIL_TEST_IDS.offspringEmpty}>
                {t('sourceIndividual.detail.noOffspring')}
              </Text>
            ) : (
              offspring.map((s) => (
                <IndividualRow key={s.id} t={t} testID={`si-detail-offspring-${s.id}`} summary={s} onPress={openIndividual} />
              ))
            )}
          </Card.Content>
        </Card>
      </ScrollView>
    </View>
  );
}

type T = (k: string, o?: Record<string, unknown>) => string;

function IdentityCard({ t, individual }: { t: T; individual: SourceIndividual }) {
  const dna = dnaStatusLabel(individual.dnaVerificationStatus);
  const line1 = joinPresent([individual.species, individual.breed, individual.sex]);
  return (
    <Card style={styles.card} testID="si-detail-identity">
      <Card.Content>
        <View style={styles.titleRow}>
          <Text variant="titleLarge" style={styles.code}>
            {individual.individualCode}
          </Text>
          {!!dna && (
            <Chip compact mode="outlined" testID="si-detail-dna">
              {dna}
            </Chip>
          )}
        </View>
        {!!individual.tagNumber && (
          <Text style={styles.meta}>
            {t('sourceIndividual.fields.tagNumber')}：{individual.tagNumber}
          </Text>
        )}
        {!!line1 && <Text style={styles.meta}>{line1}</Text>}
        {!!individual.birthDate && (
          <Text style={styles.meta}>
            {t('sourceIndividual.fields.birthDate')}：{individual.birthDate}
          </Text>
        )}
        {!!individual.originFarm && (
          <Text style={styles.meta}>
            {t('sourceIndividual.fields.originFarm')}：{individual.originFarm}
          </Text>
        )}
      </Card.Content>
    </Card>
  );
}

/**
 * 父本 / 母本一行。三种状态:
 *   有摘要          → 可点, 同屏换 id
 *   无摘要、有 id   → 「档案已不可查」(登记过、被删了) —— 灰字, 不可点
 *   无摘要、无 id   → 「未登记」 —— 灰字
 */
function ParentRow({
  t,
  testID,
  label,
  summary,
  registeredId,
  onPress,
}: {
  t: T;
  testID: string;
  label: string;
  summary: SourceIndividualSummary | null;
  registeredId?: string | null;
  onPress: (id: string) => void;
}) {
  if (summary) {
    return (
      <Pressable
        testID={testID}
        accessibilityRole="button"
        onPress={() => onPress(summary.id)}
        style={({ pressed }) => [styles.row, pressed && styles.rowPressed]}
      >
        <Text style={styles.rowLabel}>{label}</Text>
        <Text style={styles.rowValueLink}>{summaryLine(t, summary)}</Text>
      </Pressable>
    );
  }
  return (
    <View testID={testID} style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      <Text style={styles.rowValueMuted}>
        {registeredId ? t('sourceIndividual.detail.unavailable') : t('sourceIndividual.detail.notRegistered')}
      </Text>
    </View>
  );
}

function IndividualRow({
  t,
  testID,
  summary,
  onPress,
}: {
  t: T;
  testID: string;
  summary: SourceIndividualSummary;
  onPress: (id: string) => void;
}) {
  return (
    <Pressable
      testID={testID}
      accessibilityRole="button"
      onPress={() => onPress(summary.id)}
      style={({ pressed }) => [styles.listRow, pressed && styles.rowPressed]}
    >
      <Text style={styles.rowValueLink}>{summaryLine(t, summary)}</Text>
      {!!joinPresent([summary.species, summary.breed, summary.sex]) && (
        <Text style={styles.meta}>{joinPresent([summary.species, summary.breed, summary.sex])}</Text>
      )}
    </Pressable>
  );
}

function BatchRow({ t, batch }: { t: T; batch: SourceIndividualBatchSummary }) {
  const qty = batch.quantity != null ? `${batch.quantity}${batch.unit ?? ''}` : null;
  return (
    <View style={styles.listRow} testID={`si-detail-batch-${batch.id}`}>
      <Text style={styles.rowValue}>{batch.batchNumber}</Text>
      {!!joinPresent([batch.materialName, qty]) && (
        <Text style={styles.meta}>{joinPresent([batch.materialName, qty])}</Text>
      )}
      {!!batch.receiptDate && (
        <Text style={styles.meta}>
          {t('sourceIndividual.detail.receiptDate')}：{batch.receiptDate}
        </Text>
      )}
    </View>
  );
}

function NodeGroup({ t, label, nodes }: { t: T; label: string; nodes: LineageNode[] }) {
  return (
    <View style={styles.group}>
      <Text style={styles.groupLabel}>{label}</Text>
      {nodes.map((node) => (
        <View
          key={`${node.nodeType}#${node.nodeId ?? node.batchNumber ?? 'unnamed'}`}
          style={styles.listRow}
          testID={`si-detail-node-${node.nodeId ?? node.batchNumber ?? 'unnamed'}`}
        >
          <Text style={styles.rowValue}>{nodeTitle(t, node)}</Text>
          {node.resolved === false ? (
            <Text style={styles.unresolved}>{t('sourceIndividual.detail.batchUnresolved')}</Text>
          ) : (
            !!node.name && <Text style={styles.meta}>{node.name}</Text>
          )}
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F5F5F5' },
  content: { paddingVertical: 12, paddingBottom: 32 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24, gap: 12 },
  errorText: { color: '#B00020', textAlign: 'center' },
  card: { marginHorizontal: 16, marginBottom: 12, borderRadius: 12 },
  titleRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
  code: { fontWeight: '700', flexShrink: 1 },
  sectionTitle: { fontWeight: '600' },
  divider: { marginVertical: 10 },
  meta: { fontSize: 12, color: '#666', marginTop: 2 },
  muted: { fontSize: 13, color: '#888', paddingVertical: 6 },
  groupLabel: { fontSize: 12, color: '#3F51B5', fontWeight: '600', marginTop: 6, marginBottom: 2 },
  group: { marginTop: 8 },
  row: { flexDirection: 'row', alignItems: 'center', paddingVertical: 8, gap: 12 },
  rowPressed: { opacity: 0.6 },
  rowLabel: { width: 48, fontSize: 13, color: '#444' },
  rowValue: { fontSize: 14, color: '#222', fontWeight: '500' },
  rowValueLink: { fontSize: 14, color: '#1A57B5', fontWeight: '500', flexShrink: 1 },
  rowValueMuted: { fontSize: 14, color: '#999' },
  listRow: { paddingVertical: 6 },
  unresolved: { fontSize: 12, color: '#B26A00', marginTop: 2 },
});
