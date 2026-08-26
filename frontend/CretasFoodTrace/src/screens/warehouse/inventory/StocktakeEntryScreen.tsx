/**
 * 盘点录入屏 (SP7 R-C) —— 盲盘 (blind stocktake)
 *
 * UX Flow F4 设计回应: 单品一屏, **盲盘 + 提交后揭示**
 *   上 = 品名 + 编码大字
 *   中 = 实际数量大键盘 (≥24px) —— ⛔ 提交前不出现任何账面数字
 *   下 = [提交本品]
 *   点提交 → 立刻显示 账面 / 实盘 / 差异（红绿）→ **该品锁定, 不可再改**
 *   → 下一品 → …… → 全部录完 → 差异汇总 → 提交 → "待财务审批"
 *
 * 为什么中间那一栏原来是「账面数量（创建时可见）」:
 *   §7 用户画像是客户原话 ——「做仓管的他年纪都比较大文化素质很低」, 摩擦点 F4
 *   因此要求账面数回显 + 实时红绿差异。那个理由是真的, ⛔ 不能简单删掉反馈了事。
 *   盲盘只是把这个闭环从**填之前**挪到**填之后**: 填完立刻知道对不对,
 *   但那一品的独立性保住了。锁定是必须的 —— 看到差异再回头改, 等于没盲。
 *
 * ⚠️ 藏起来的只是界面。后端返回的是裸 JPA 实体, systemQty 仍在 HTTP 响应体里 ——
 *    **前端藏起来是 UX 纪律, 不是防舞弊。**
 *
 * UX 强制项：TouchableRipple / ≥44pt / 数字 ≥24px / 唯一大按钮 / 错误双句+保数据+重试
 */

import React, { useState, useCallback, useRef, useEffect } from 'react';
import {
  View,
  StyleSheet,
  ScrollView,
  ActivityIndicator,
  TextInput as RNTextInput,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';
import {
  Text,
  TouchableRipple,
  Divider,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { WHInventoryStackParamList } from '../../../types/navigation';
import { NeoButton } from '../../../components/ui/NeoButton';
import { NeoCard } from '../../../components/ui/NeoCard';
import { AppDialogHost, appAlert } from '../../../components/ui/AppDialog';
import {
  stocktakeApiClient,
  StocktakeDTO,
  StocktakeItemDTO,
  StocktakeItemUpdateDTO,
} from '../../../services/api/stocktakeApiClient';
// 盲盘契约: 账面数/差异只能经这几个出口露出来, JSX 里不要直接读 item.systemQty
import {
  canSubmitRow,
  isRowLocked,
  revealedDifference,
  revealedSystemQty,
  summarizeBlindCount,
} from './blindStocktake';

type NavProp = NativeStackNavigationProp<WHInventoryStackParamList>;
type RouteType = RouteProp<WHInventoryStackParamList, 'StocktakeEntry'>;

const PRIMARY = '#1890FF';
const GREEN = '#52C41A';
const RED = '#FF4D4F';
const AMBER = '#FA8C16';

function statusLabel(status: StocktakeDTO['status']): string {
  const MAP: Record<string, string> = {
    INITIATED: '已发起',
    COUNTING: '盘点中',
    PENDING_APPROVAL: '待审批',
    APPROVED: '已审批',
    APPLIED: '已生效',
    REJECTED: '已驳回',
  };
  return MAP[status] ?? status;
}

/** 每品 + 已录状态 */
interface ItemEntry {
  item: StocktakeItemDTO;
  /** 用户输入的实盘文本（允许小数） */
  inputText: string;
  /** 解析后的数值，null = 未填 */
  actualQty: number | null;
  /**
   * 已提交到后端 = 已锁定 = 账面/差异可见。三件事同一个标志, 不可分开。
   * （旧字段名是 `saved`，只表达"存过了"；盲盘下它同时是"锁了"和"可以看了"。）
   */
  committed: boolean;
  /** 账面数量 —— ⛔ 只经 revealedSystemQty() 读，不要直接渲染 */
  systemQty: number;
  /** 服务端算好的差异；null = 未提交或服务端没给 */
  differenceQty: number | null;
  differenceType: string | null;
  saving: boolean;
}

/**
 * 服务端 item → 屏幕行。
 * 服务端已有 actualQty ⇒ 这一品上一轮就提交过 ⇒ 已锁定, 账面/差异可见。
 */
function toEntry(item: StocktakeItemDTO): ItemEntry {
  const raw = item.actualQty;
  const actualQty = raw !== undefined && raw !== null && Number.isFinite(Number(raw))
    ? Number(raw)
    : null;
  const diff = item.differenceQty;
  return {
    item,
    inputText: actualQty !== null ? String(actualQty) : '',
    actualQty,
    committed: actualQty !== null,
    systemQty: Number(item.systemQty ?? 0),
    // ⛔ 不用 `?? 0` 兜底 —— 那会把「服务端没算」报成「没有差异」
    differenceQty: diff !== undefined && diff !== null ? Number(diff) : null,
    differenceType: item.differenceType ?? null,
    saving: false,
  };
}

export function StocktakeEntryScreen(): React.JSX.Element {
  const navigation = useNavigation<NavProp>();
  const route = useRoute<RouteType>();
  const { stocktakeId } = route.params;

  const [loading, setLoading] = useState(true);
  const [stocktake, setStocktake] = useState<StocktakeDTO | null>(null);
  const [entries, setEntries] = useState<ItemEntry[]>([]);
  /** 当前正在录入的品项索引（单品一屏） */
  const [currentIndex, setCurrentIndex] = useState(0);
  /** 差异汇总是否已显示（全部录完后） */
  const [diffVisible, setDiffVisible] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const inputRef = useRef<RNTextInput>(null);

  // ── 加载盘点详情 ──────────────────────────────
  const loadDetail = useCallback(async () => {
    setLoading(true);
    try {
      const res = await stocktakeApiClient.getDetail(stocktakeId);
      if (!res.success || !res.data) throw new Error('加载失败');
      setStocktake(res.data);
      setEntries((res.data.items ?? []).map(toEntry));
    } catch (err) {
      const e = err as { response?: { data?: { message?: string } } };
      appAlert(
        '加载盘点任务失败',
        e.response?.data?.message ?? '请检查网络后重试',
        [{ text: '重试', onPress: loadDetail }, { text: '返回', style: 'cancel', onPress: () => navigation.goBack() }],
      );
    } finally {
      setLoading(false);
    }
  }, [stocktakeId, navigation]);

  useEffect(() => {
    loadDetail();
  }, [loadDetail]);

  // 自动聚焦输入框
  useEffect(() => {
    if (!loading && entries.length > 0) {
      setTimeout(() => inputRef.current?.focus(), 300);
    }
  }, [loading, currentIndex, entries.length]);

  // ── 当前品项 ──────────────────────────────────
  const currentEntry = entries[currentIndex] as ItemEntry | undefined;
  const totalCount = entries.length;
  /** 进度按【已提交】算, 不按【输入框里有字】算 —— 没提交的不算数 */
  const committedCount = entries.filter((e) => e.committed).length;

  // ── 更新输入 ──────────────────────────────────
  const handleInputChange = useCallback((text: string) => {
    setEntries((prev) => {
      const copy = [...prev];
      const cur = copy[currentIndex];
      if (!cur) return prev;
      // 已提交的行锁死 —— 看到差异再回头改, 等于没盲
      if (isRowLocked(cur)) return prev;
      const trimmed = text.trim();
      const parsed = trimmed ? Number(trimmed) : null;
      copy[currentIndex] = {
        ...cur,
        inputText: text,
        actualQty: parsed !== null && Number.isFinite(parsed) ? parsed : null,
      };
      return copy;
    });
  }, [currentIndex]);

  // ── 提交当前品项（PUT /items 幂等）→ 回读服务端差异 → 锁定并揭示 ──
  const submitCurrentItem = useCallback(async (): Promise<boolean> => {
    const entry = entries[currentIndex] as ItemEntry | undefined;
    if (!entry || entry.actualQty === null || !stocktake) return false;
    if (isRowLocked(entry)) return false;
    const updates: StocktakeItemUpdateDTO[] = [{
      itemId: entry.item.id,
      actualQty: entry.actualQty,
    }];
    const setSaving = (saving: boolean) => setEntries((prev) => {
      const copy = [...prev];
      const cur = copy[currentIndex];
      if (!cur) return prev;
      copy[currentIndex] = { ...cur, saving };
      return copy;
    });
    setSaving(true);
    try {
      await stocktakeApiClient.updateItems(stocktake.id, updates);
      // PUT 只回 { success, data: null }, 差异要回读详情。
      // ⛔ 不在前端做 actualQty - systemQty —— 差异只认服务端算的那一份。
      const fresh = await stocktakeApiClient.getDetail(stocktake.id);
      const serverItem = fresh.success
        ? (fresh.data?.items ?? []).find((it) => it.id === entry.item.id)
        : undefined;
      if (!serverItem) {
        setSaving(false);
        appAlert(
          '已提交，但没读到差异',
          '实盘数量已保存，但没能读回服务端算的差异。请退出后重新进入本盘点任务核对。',
        );
        return false;
      }
      setEntries((prev) => {
        const copy = [...prev];
        const cur = copy[currentIndex];
        if (!cur) return prev;
        copy[currentIndex] = { ...toEntry(serverItem), saving: false };
        return copy;
      });
      return true;
    } catch (err) {
      const e = err as { response?: { data?: { message?: string } } };
      setSaving(false);
      appAlert('提交失败，数据已保留', e.response?.data?.message ?? '请检查网络后重试，您的数据不会丢失');
      return false;
    }
  }, [currentIndex, entries, stocktake]);

  // ── 提交本品 ──────────────────────────────────
  const handleSubmitItem = useCallback(async () => {
    if (!currentEntry) return;
    if (currentEntry.actualQty === null) {
      appAlert('请填写实盘数量', '请先填写本品的实盘数量，再点提交');
      return;
    }
    // 提交后停在本品, 让仓管看到 账面/实盘/差异 —— 反馈闭环在这里闭合。
    // ⛔ 不要顺手跳到下一品: 那就把刚揭示的差异一闪而过地吞掉了。
    await submitCurrentItem();
  }, [currentEntry, submitCurrentItem]);

  // ── 下一品 ────────────────────────────────────
  const handleNext = useCallback(() => {
    if (currentIndex < totalCount - 1) {
      setCurrentIndex((i) => i + 1);
    } else {
      setDiffVisible(true);
    }
  }, [currentIndex, totalCount]);

  // ── 上一品 ────────────────────────────────────
  const handlePrev = useCallback(() => {
    if (currentIndex > 0) setCurrentIndex((i) => i - 1);
  }, [currentIndex]);

  // ── 提交盘点 ──────────────────────────────────
  const handleSubmit = useCallback(async () => {
    if (!stocktake) return;
    const pending = entries.filter((e) => !e.committed);
    if (pending.length > 0) {
      appAlert('还有未提交品项', `${pending.length} 个品项还没提交，请先逐品填写并提交`);
      return;
    }
    // 差异统计一律基于服务端算好的 differenceQty
    const summary = summarizeBlindCount(entries);
    const diffCount = summary.shortage + summary.surplus;
    const diffDesc = diffCount > 0
      ? `${diffCount} 件存在差异，将报给财务审批`
      : '所有品项账实相符';
    appAlert(
      '确认提交盘点？',
      `${diffDesc}。提交后状态将变为"待财务审批"，您无法修改。`,
      [
        { text: '取消', style: 'cancel' },
        {
          text: '确认提交',
          onPress: async () => {
            setSubmitting(true);
            try {
              await stocktakeApiClient.submit(stocktake.id);
              appAlert(
                '盘点已提交',
                '盘点任务已提交财务审批，请等待审批结果。',
                [{ text: '确定', onPress: () => navigation.goBack() }],
              );
            } catch (err) {
              const e = err as { response?: { data?: { message?: string } } };
              appAlert('提交失败，数据已保留', e.response?.data?.message ?? '请检查网络后重试');
            } finally {
              setSubmitting(false);
            }
          },
        },
      ],
    );
  }, [stocktake, entries, navigation]);

  // ── Loading ───────────────────────────────────
  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <AppDialogHost />
        <View style={styles.header}>
          <TouchableRipple onPress={() => navigation.goBack()} style={styles.backBtn} borderless>
            <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
          </TouchableRipple>
          <Text style={styles.headerTitle}>盘点录入</Text>
          <View style={styles.headerRight} />
        </View>
        <View style={styles.centered}>
          <ActivityIndicator size="large" color={PRIMARY} />
          <Text style={styles.loadingText}>加载盘点任务...</Text>
        </View>
      </SafeAreaView>
    );
  }

  if (!stocktake || entries.length === 0) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <AppDialogHost />
        <View style={styles.header}>
          <TouchableRipple onPress={() => navigation.goBack()} style={styles.backBtn} borderless>
            <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
          </TouchableRipple>
          <Text style={styles.headerTitle}>盘点录入</Text>
          <View style={styles.headerRight} />
        </View>
        <View style={styles.centered}>
          <MaterialCommunityIcons name="package-variant" size={64} color="#ccc" />
          <Text style={styles.emptyText}>该盘点任务暂无品项</Text>
          <NeoButton variant="outline" onPress={() => navigation.goBack()} style={{ marginTop: 16 }} testID="stocktake-entry-empty-back">
            返回
          </NeoButton>
        </View>
      </SafeAreaView>
    );
  }

  // ── 差异汇总视图（全部录完后） ───────────────
  if (diffVisible) {
    // 全部基于服务端 differenceQty; ⛔ 前端不做 actualQty - systemQty
    const summary = summarizeBlindCount(entries);
    const diffEntries = entries.filter((e) => {
      const d = revealedDifference(e);
      return d !== null && d !== 0;
    });
    const shortageEntries = entries.filter((e) => {
      const d = revealedDifference(e);
      return d !== null && d < 0;
    });

    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <AppDialogHost />
        <View style={styles.header}>
          <TouchableRipple onPress={() => setDiffVisible(false)} style={styles.backBtn} borderless>
            <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
          </TouchableRipple>
          <Text style={styles.headerTitle}>差异汇总</Text>
          <View style={styles.headerRight} />
        </View>
        <ScrollView style={styles.content} contentContainerStyle={{ paddingBottom: 120 }} testID="stocktake-entry-diff-scroll">
          <NeoCard style={styles.summaryCard}>
            <Text style={styles.summaryTitle}>盘点结果：{stocktake.stocktakeNo}</Text>
            <Text style={styles.summaryPeriod}>盘点月份：{stocktake.periodMonth}</Text>
            <Divider style={{ marginVertical: 12 }} />
            <View style={styles.summaryRow}>
              <View style={styles.summaryItem}>
                <Text style={styles.summaryNum}>{totalCount}</Text>
                <Text style={styles.summaryLabel}>总品项</Text>
              </View>
              <View style={styles.summaryItem}>
                <Text style={[styles.summaryNum, { color: GREEN }]}>{summary.matched}</Text>
                <Text style={styles.summaryLabel}>账实相符</Text>
              </View>
              <View style={styles.summaryItem}>
                <Text style={[styles.summaryNum, { color: RED }]}>{summary.shortage}</Text>
                <Text style={styles.summaryLabel}>盘亏</Text>
              </View>
              <View style={styles.summaryItem}>
                <Text style={[styles.summaryNum, { color: AMBER }]}>{summary.surplus}</Text>
                <Text style={styles.summaryLabel}>盘盈</Text>
              </View>
            </View>
            {/* 「服务端没给差异」单独一栏 —— ⛔ 不许并进「账实相符」, 那等于把"不知道"报成"没问题" */}
            {summary.unknown > 0 && (
              <Text style={[styles.warningNote, { color: AMBER, marginTop: 10 }]} testID="stocktake-entry-diff-unknown">
                注：{summary.unknown} 项未取到服务端算的差异，请退出后重新进入核对，不要当成账实相符。
              </Text>
            )}
          </NeoCard>

          {diffEntries.length > 0 && (
            <NeoCard style={styles.diffListCard}>
              <Text style={styles.diffListTitle}>差异明细（共 {diffEntries.length} 项）</Text>
              {shortageEntries.length > 0 && (
                <Text style={[styles.warningNote, { color: RED, marginBottom: 8 }]}>
                  注：盘亏将记录您的工号作为责任方，请确认数据正确
                </Text>
              )}
              {diffEntries.map((e) => {
                // 这里的行已经过滤成 revealedDifference !== null, 但仍然显式取一次,
                // ⛔ 不用 `?? 0` 兜底 —— 0 是"账实相符"的真实读数, 不是"读不到"。
                const diff = revealedDifference(e) as number;
                const isShort = diff < 0;
                return (
                  <View key={e.item.id} style={styles.diffRow} testID={`stocktake-entry-diff-row-${e.item.id}`}>
                    <View style={styles.diffLeft}>
                      <Text style={styles.diffName}>{e.item.materialBatchId ?? '—'}</Text>
                      <Text style={styles.diffBatch}>账面 {revealedSystemQty(e)} → 实盘 {e.actualQty}</Text>
                    </View>
                    <Text style={[styles.diffQty, { color: isShort ? RED : AMBER }]}>
                      {diff > 0 ? '+' : ''}{diff.toFixed(2)}
                    </Text>
                  </View>
                );
              })}
            </NeoCard>
          )}

          {diffEntries.length === 0 && (
            <NeoCard style={styles.diffListCard}>
              <View style={styles.centered}>
                <MaterialCommunityIcons name="check-circle-outline" size={48} color={GREEN} />
                <Text style={{ color: GREEN, fontWeight: '600', marginTop: 8 }}>所有品项账实相符！</Text>
              </View>
            </NeoCard>
          )}
        </ScrollView>

        <View style={styles.bottomBar}>
          {/* 「返回修改」已改成「返回查看」—— 每一品提交时就锁定了, 这里没有可改的东西。
              留一个说"修改"的按钮会让人以为盘完还能回去调, 那正是盲盘要堵的口子。 */}
          <NeoButton
            variant="outline"
            onPress={() => setDiffVisible(false)}
            style={{ flex: 1 }}
            testID="stocktake-entry-diff-back"
          >
            返回查看
          </NeoButton>
          <NeoButton
            variant="primary"
            onPress={handleSubmit}
            loading={submitting}
            disabled={submitting}
            style={{ flex: 2 }}
            testID="stocktake-entry-submit"
          >
            {submitting ? '提交中...' : '提交待财务审批'}
          </NeoButton>
        </View>
      </SafeAreaView>
    );
  }

  // ── 单品录入视图 ──────────────────────────────
  const entry = entries[currentIndex] as ItemEntry | undefined;
  // Guard: entries may be empty during load transition
  if (!entry) return <SafeAreaView style={styles.container} edges={['top']}><AppDialogHost /></SafeAreaView>;
  const locked = isRowLocked(entry);
  const revealedQty = revealedSystemQty(entry);
  const revealedDiff = revealedDifference(entry);
  const canSubmit = canSubmitRow(entry);
  const isLastItem = currentIndex >= totalCount - 1;

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <AppDialogHost />
      {/* Header */}
      <View style={styles.header}>
        <TouchableRipple onPress={() => navigation.goBack()} style={styles.backBtn} borderless testID="stocktake-entry-back">
          <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
        </TouchableRipple>
        <View style={styles.headerCenter}>
          <Text style={styles.headerTitle}>盘点录入</Text>
          <Text style={styles.headerSubtitle}>{currentIndex + 1} / {totalCount}</Text>
        </View>
        <View style={styles.headerRight} />
      </View>

      {/* 进度条 */}
      <View style={styles.progressContainer}>
        <View style={[styles.progressFill, { width: `${(committedCount / totalCount) * 100}%` as unknown as number }]} />
      </View>

      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1 }}>
        <ScrollView style={styles.content} contentContainerStyle={{ paddingBottom: 120 }} testID="stocktake-entry-scroll">

          {/* 品名 + 编码大字 */}
          <NeoCard style={styles.nameCard}>
            <Text style={styles.itemName} numberOfLines={2}>
              {entry.item.materialBatchId ?? '未知批次'}
            </Text>
            {entry.item.rawMaterialTypeId && (
              <Text style={styles.itemCode}>编码：{entry.item.rawMaterialTypeId}</Text>
            )}
          </NeoCard>

          {/* ⛔ 这里曾经有一张「账面数量」大字卡片, 提交前就把账面数摆在输入框上方。
              盲盘下它整块删掉了 —— 账面数改到【提交之后】的揭示卡里显示。
              不要因为「老仓管需要参照」把它加回来: 那个需求由下面的揭示卡满足。 */}

          {/* 实际数量大键盘 */}
          <NeoCard style={styles.inputCard}>
            <Text style={styles.inputLabel}>
              {locked ? '实盘数量（已提交，不可修改）' : '实盘数量（填写实际在库数量）'}
            </Text>
            <RNTextInput
              testID="stocktake-entry-actual-qty-input"
              ref={inputRef}
              /* ⛔ 不要按「与账面是否偏离」给输入框上色 —— 那是个隐蔽的泄露:
                 就算标签删了, 变色照样把账面数透露出去（试几个数就能二分出来）。
                 上色只发生在提交之后的揭示卡里。 */
              style={[styles.bigInput, locked && styles.bigInputLocked]}
              value={entry.inputText}
              onChangeText={handleInputChange}
              editable={!locked}
              keyboardType="numeric"
              placeholder="请输入实际数量"
              placeholderTextColor="#bbb"
              returnKeyType="done"
              onSubmitEditing={handleSubmitItem}
            />
            {entry.saving && (
              <View style={styles.savingRow}>
                <ActivityIndicator size="small" color={PRIMARY} />
                <Text style={styles.savingText}>提交中...</Text>
              </View>
            )}
          </NeoCard>

          {/* 提交后的揭示卡: 账面 / 实盘 / 差异 一次给全 —— 反馈闭环在这里闭合 */}
          {locked && (
            <NeoCard style={styles.revealCard} testID="stocktake-entry-reveal">
              <View style={styles.revealHeaderRow}>
                <MaterialCommunityIcons name="lock-check" size={18} color={GREEN} />
                <Text style={styles.revealHeaderText}>本品已提交并锁定</Text>
              </View>
              <Divider style={{ marginVertical: 10 }} />
              <View style={styles.revealRow}>
                <Text style={styles.revealLabel}>账面</Text>
                <Text style={styles.revealValue} testID="stocktake-entry-reveal-system">
                  {revealedQty !== null ? revealedQty : '—'}
                </Text>
              </View>
              <View style={styles.revealRow}>
                <Text style={styles.revealLabel}>实盘</Text>
                <Text style={styles.revealValue}>{entry.actualQty !== null ? entry.actualQty : '—'}</Text>
              </View>
              <View style={styles.revealRow}>
                <Text style={styles.revealLabel}>差异</Text>
                {revealedDiff !== null ? (
                  <Text
                    testID="stocktake-entry-reveal-diff"
                    style={[
                      styles.revealValue,
                      { color: revealedDiff === 0 ? GREEN : revealedDiff < 0 ? RED : AMBER },
                    ]}
                  >
                    {revealedDiff > 0 ? '+' : ''}{revealedDiff}
                  </Text>
                ) : (
                  /* ⛔ 读不到就显示"—", 不要拿 0 顶替 —— 0 是"账实相符"的真实读数 */
                  <Text style={[styles.revealValue, { color: AMBER }]} testID="stocktake-entry-reveal-diff">
                    未取到
                  </Text>
                )}
              </View>
              {revealedDiff !== null && revealedDiff < 0 && (
                <Text style={[styles.warningNote, { color: RED, marginTop: 8 }]}>
                  注：盘亏将记录您的工号作为责任方
                </Text>
              )}
            </NeoCard>
          )}

        </ScrollView>
      </KeyboardAvoidingView>

      {/* 底部导航 */}
      <View style={styles.bottomBar}>
        <TouchableRipple
          testID="stocktake-entry-prev"
          onPress={handlePrev}
          disabled={currentIndex === 0}
          style={[styles.navBtn, currentIndex === 0 && styles.navBtnDisabled]}
          borderless
        >
          <View style={styles.navBtnInner}>
            <MaterialCommunityIcons name="chevron-left" size={24} color={currentIndex === 0 ? '#ccc' : '#333'} />
            <Text style={[styles.navBtnText, currentIndex === 0 && { color: '#ccc' }]}>上一品</Text>
          </View>
        </TouchableRipple>

        {/* 唯一大按钮: 未提交 = 提交本品; 已提交 = 下一品 / 完成录入。
            两个动作绝不合并 —— 合并就等于差异一闪而过, 揭示等于没发生。 */}
        {!locked ? (
          <NeoButton
            variant="primary"
            onPress={handleSubmitItem}
            disabled={!canSubmit || entry.saving}
            style={styles.nextBtn}
            size="large"
            testID="stocktake-entry-submit-item"
          >
            {entry.saving ? '提交中...' : '提交本品'}
          </NeoButton>
        ) : (
          <NeoButton
            variant="primary"
            onPress={handleNext}
            style={styles.nextBtn}
            size="large"
            testID="stocktake-entry-next"
          >
            {isLastItem ? '完成录入' : '下一品'}
          </NeoButton>
        )}
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F4F6F9' },
  header: {
    backgroundColor: PRIMARY,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  backBtn: { padding: 8, borderRadius: 20 },
  headerCenter: { flex: 1, alignItems: 'center' },
  headerTitle: { fontSize: 18, fontWeight: '700', color: '#fff' },
  headerSubtitle: { fontSize: 12, color: 'rgba(255,255,255,0.8)', marginTop: 2 },
  headerRight: { width: 40 },
  progressContainer: {
    height: 4,
    backgroundColor: '#d9d9d9',
  },
  progressFill: {
    height: 4,
    backgroundColor: GREEN,
  },
  content: { flex: 1 },
  centered: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24 },
  loadingText: { marginTop: 12, fontSize: 14, color: '#666' },
  emptyText: { marginTop: 12, fontSize: 16, color: '#999' },
  nameCard: {
    margin: 16,
    marginBottom: 8,
    padding: 20,
  },
  itemName: {
    fontSize: 28,
    fontWeight: '800',
    color: '#111',
    lineHeight: 36,
  },
  itemCode: {
    fontSize: 15,
    color: '#666',
    marginTop: 8,
    fontWeight: '500',
  },
  // 提交后的揭示卡 —— 账面/实盘/差异只在这里出现
  revealCard: {
    marginHorizontal: 16,
    marginBottom: 8,
    padding: 16,
  },
  revealHeaderRow: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  revealHeaderText: { fontSize: 15, fontWeight: '700', color: GREEN },
  revealRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 6,
  },
  revealLabel: { fontSize: 16, color: '#555', fontWeight: '500' },
  revealValue: { fontSize: 28, fontWeight: '800', color: '#333' },
  inputCard: {
    marginHorizontal: 16,
    marginBottom: 8,
    padding: 16,
  },
  inputLabel: { fontSize: 15, color: '#555', fontWeight: '500', marginBottom: 12 },
  bigInput: {
    fontSize: 40,
    fontWeight: '800',
    color: '#111',
    borderBottomWidth: 2,
    borderBottomColor: PRIMARY,
    paddingVertical: 8,
    textAlign: 'center',
    minHeight: 60,
  },
  // 锁定后的输入框: 灰底 + 灰边, 与"账实是否偏离"无关
  bigInputLocked: {
    color: '#888',
    backgroundColor: '#f5f5f5',
    borderBottomColor: '#d9d9d9',
  },
  savingRow: { flexDirection: 'row', alignItems: 'center', marginTop: 8, gap: 6 },
  savingText: { fontSize: 13, color: '#999' },
  bottomBar: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    backgroundColor: '#fff',
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
    paddingBottom: 28,
    gap: 12,
    borderTopWidth: 1,
    borderTopColor: '#e8e8e8',
  },
  navBtn: {
    minHeight: 44,
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#e0e0e0',
    backgroundColor: '#f5f5f5',
    justifyContent: 'center',
  },
  navBtnDisabled: { opacity: 0.4 },
  navBtnInner: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  navBtnText: { fontSize: 15, fontWeight: '600', color: '#333' },
  nextBtn: { flex: 1 },
  // Diff summary
  summaryCard: { margin: 16, marginBottom: 8, padding: 20 },
  summaryTitle: { fontSize: 18, fontWeight: '700', color: '#111' },
  summaryPeriod: { fontSize: 14, color: '#777', marginTop: 4 },
  summaryRow: { flexDirection: 'row', justifyContent: 'space-around' },
  summaryItem: { alignItems: 'center' },
  summaryNum: { fontSize: 28, fontWeight: '800', color: '#111' },
  summaryLabel: { fontSize: 13, color: '#777', marginTop: 4 },
  diffListCard: { marginHorizontal: 16, marginBottom: 8, padding: 16 },
  diffListTitle: { fontSize: 16, fontWeight: '700', color: '#111', marginBottom: 12 },
  warningNote: { fontSize: 13, lineHeight: 18 },
  diffRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f0',
  },
  diffLeft: { flex: 1 },
  diffName: { fontSize: 15, fontWeight: '600', color: '#222' },
  diffBatch: { fontSize: 12, color: '#888', marginTop: 2 },
  diffQty: { fontSize: 18, fontWeight: '800', marginLeft: 12 },
});

export default StocktakeEntryScreen;
