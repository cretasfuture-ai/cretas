/**
 * SourceIndividualListScreen — 源头个体档案列表 (只读)
 *
 * 首页「源头个体」瓷砖落到这里。2026-09-15 评审: 那块瓷砖此前直达 `SourceIndividualForm` ——
 * 老板点一个名词想看「我有哪些牛」, 落进一张 10 个字段的登记表。RN 此前没有只读列表屏
 * (web-admin 有 `views/source-individuals/index.vue`, 本屏的列取自那里)。
 *
 * 出口:
 *  · 点行 → `SourceIndividualDetail` { individualId }
 *  · 右上 + / 空态「登记第一头」 → `SourceIndividualForm` (无参 = 空白建档)
 *
 * ⛔ 三态分开: 加载中 / 出错 (把错误原话显示出来 + 重试, 不留空屏) / 有数据。
 * ⛔ 两种「没有」分开说: 一条档案都没有 ⇒ 引导登记; 搜了没匹配 ⇒ 说「没匹配」, ⛔ 不引导登记。
 * ⛔ 总数「共 N 头」的 N 来自后端 `totalElements`, 前端不数 `content.length` (那只是当前页)。
 * ⛔ 标识牌编号只改**显示**分组 (`formatTagNumber`), 存储值 / 搜索关键字原样。
 * 颜色 / 间距全部走 `useTheme()`; 字号走 Paper `variant`; DNA 状态 chip 经 `DnaStatusChip`
 * (四处共用的色板, 本文件不决定色值)。
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { FlatList, RefreshControl, StyleSheet, View } from 'react-native';
import {
  ActivityIndicator,
  Appbar,
  Button,
  Divider,
  Searchbar,
  Text,
  TouchableRipple,
  useTheme,
} from 'react-native-paper';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTranslation } from 'react-i18next';

import {
  sourceIndividualApiClient,
  type SourceIndividual,
} from '../../services/api/sourceIndividualApiClient';
import { DnaStatusChip } from '../traceability/components/dnaStatus';
import type { AppTheme } from '../../theme';
import type { ProcessingStackParamList } from '../../types/navigation';

type Nav = NativeStackNavigationProp<ProcessingStackParamList, 'SourceIndividualList'>;

export const SI_LIST_TEST_IDS = {
  screen: 'source-individual-list',
  search: 'si-list-search',
  add: 'si-list-add',
  total: 'si-list-total',
  loading: 'si-list-loading',
  loadingMore: 'si-list-loading-more',
  error: 'si-list-error',
  retry: 'si-list-retry',
  empty: 'si-list-empty',
  emptyAction: 'si-list-empty-action',
  noMatch: 'si-list-no-match',
  row: (id: string) => `si-list-row-${id}`,
  rowTag: (id: string) => `si-list-row-${id}-tag`,
  rowMeta: (id: string) => `si-list-row-${id}-meta`,
  rowDna: (id: string) => `si-list-row-${id}-dna`,
} as const;

export const PAGE_SIZE = 20;

/** 关键字改了之后等这么久再打后端 —— 每敲一个字就查一次, 低端机上列表会抖。 */
export const SEARCH_DEBOUNCE_MS = 300;

/**
 * 标识牌编号的**显示**分组: 15 位纯数字每 5 位一组 (`110370281000001` → `11037 02810 00001`)。
 * 其它长度 / 含字母的原样返回 —— 不知道它的结构就不猜分组。
 * ⛔ 只用于显示; 存储、搜索、传参都用原值。
 */
export function formatTagNumber(tag: string): string {
  if (!/^\d{15}$/.test(tag)) {
    return tag;
  }
  return tag.replace(/(\d{5})(?=\d)/g, '$1 ');
}

/** 「品种 · 来源场」: 缺哪个就不渲染哪个, 两个都缺返回 null (整行不渲染, ⛔ 不显示 '-')。 */
export function breedAndFarm(item: Pick<SourceIndividual, 'breed' | 'originFarm'>): string | null {
  const parts = [item.breed, item.originFarm].filter((s): s is string => !!s && s.trim().length > 0);
  return parts.length ? parts.join(' · ') : null;
}

type LoadMode = 'replace' | 'append' | 'refresh';

export default function SourceIndividualListScreen() {
  const theme = useTheme<AppTheme>();
  const navigation = useNavigation<Nav>();
  const { t } = useTranslation('processing');
  const { spacing, borderRadius } = theme.custom;

  const [keyword, setKeyword] = useState('');
  const [items, setItems] = useState<SourceIndividual[]>([]);
  const [total, setTotal] = useState<number | null>(null);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  // 后发先至的响应要丢掉: 关键字连敲两次, 第一次的结果晚到会把第二次的盖掉。
  const requestSeq = useRef(0);
  const keywordRef = useRef(keyword);
  keywordRef.current = keyword;

  const load = useCallback(
    async (targetPage: number, kw: string, mode: LoadMode) => {
      const seq = ++requestSeq.current;
      if (mode === 'replace') {
        setLoading(true);
        setError(null);
      } else if (mode === 'append') {
        setLoadingMore(true);
      } else {
        setRefreshing(true);
      }
      try {
        const result = await sourceIndividualApiClient.list({
          page: targetPage,
          size: PAGE_SIZE,
          keyword: kw.trim() ? kw.trim() : undefined,
        });
        if (seq !== requestSeq.current) return;
        setItems((prev) => (mode === 'append' ? [...prev, ...result.content] : result.content));
        setTotal(result.totalElements);
        setPage(result.page);
        setTotalPages(result.totalPages);
        setError(null);
      } catch (e) {
        if (seq !== requestSeq.current) return;
        // ⛔ 失败就把错误说出来, 不留一张空列表让人以为「一头都没登记」。
        setError(e instanceof Error ? e.message : t('sourceIndividual.list.loadFailed'));
      } finally {
        if (seq === requestSeq.current) {
          setLoading(false);
          setLoadingMore(false);
          setRefreshing(false);
        }
      }
    },
    [t]
  );

  // 首次进入 / 关键字变化 / 点了重试 ⇒ 从第 1 页重来。首次与清空关键字不等 debounce。
  useEffect(() => {
    const delay = keyword.trim() ? SEARCH_DEBOUNCE_MS : 0;
    const handle = setTimeout(() => {
      load(1, keyword, 'replace');
    }, delay);
    return () => clearTimeout(handle);
  }, [keyword, attempt, load]);

  const loadMore = useCallback(() => {
    if (loading || loadingMore || refreshing || error) return;
    if (page >= totalPages) return;
    load(page + 1, keywordRef.current, 'append');
  }, [loading, loadingMore, refreshing, error, page, totalPages, load]);

  const onRefresh = useCallback(() => {
    load(1, keywordRef.current, 'refresh');
  }, [load]);

  const openDetail = useCallback(
    (individualId: string) => {
      navigation.navigate('SourceIndividualDetail', { individualId });
    },
    [navigation]
  );

  const openForm = useCallback(() => {
    navigation.navigate('SourceIndividualForm');
  }, [navigation]);

  const hasKeyword = keyword.trim().length > 0;

  const renderRow = useCallback(
    ({ item }: { item: SourceIndividual }) => {
      const meta = breedAndFarm(item);
      return (
        <TouchableRipple
          onPress={() => openDetail(item.id)}
          testID={SI_LIST_TEST_IDS.row(item.id)}
          accessibilityRole="button"
          accessibilityLabel={t('sourceIndividual.list.openDetail', { code: item.individualCode })}
          style={[styles.row, { paddingHorizontal: spacing.l, paddingVertical: spacing.m }]}
        >
          <View style={[styles.rowInner, { gap: spacing.m }]}>
            <View style={[styles.rowText, { gap: spacing.xs }]}>
              <Text variant="titleMedium" style={{ color: theme.colors.onSurface }}>
                {item.individualCode}
              </Text>
              {!!item.tagNumber && (
                <Text
                  variant="bodyMedium"
                  style={[styles.mono, { color: theme.colors.onSurface }]}
                  testID={SI_LIST_TEST_IDS.rowTag(item.id)}
                >
                  {formatTagNumber(item.tagNumber)}
                </Text>
              )}
              {!!meta && (
                <Text
                  variant="bodySmall"
                  style={{ color: theme.colors.onSurfaceVariant }}
                  testID={SI_LIST_TEST_IDS.rowMeta(item.id)}
                >
                  {meta}
                </Text>
              )}
            </View>
            <DnaStatusChip status={item.dnaVerificationStatus} testID={SI_LIST_TEST_IDS.rowDna(item.id)} />
          </View>
        </TouchableRipple>
      );
    },
    [openDetail, spacing, t, theme.colors.onSurface, theme.colors.onSurfaceVariant]
  );

  const listEmpty = () => {
    if (loading) {
      return (
        <View style={[styles.center, { padding: spacing.xl }]} testID={SI_LIST_TEST_IDS.loading}>
          <ActivityIndicator />
        </View>
      );
    }
    if (error) {
      return (
        <View style={[styles.center, { padding: spacing.xl, gap: spacing.m }]} testID={SI_LIST_TEST_IDS.error}>
          <Text variant="bodyMedium" style={[styles.centerText, { color: theme.colors.error }]}>
            {error}
          </Text>
          <Button mode="contained" onPress={() => setAttempt((n) => n + 1)} testID={SI_LIST_TEST_IDS.retry}>
            {t('sourceIndividual.list.retry')}
          </Button>
        </View>
      );
    }
    if (hasKeyword) {
      return (
        <View style={[styles.center, { padding: spacing.xl }]} testID={SI_LIST_TEST_IDS.noMatch}>
          <Text variant="bodyMedium" style={[styles.centerText, { color: theme.colors.onSurfaceVariant }]}>
            {t('sourceIndividual.list.noMatch')}
          </Text>
        </View>
      );
    }
    return (
      <View style={[styles.center, { padding: spacing.xl, gap: spacing.m }]} testID={SI_LIST_TEST_IDS.empty}>
        <Text variant="bodyMedium" style={[styles.centerText, { color: theme.colors.onSurfaceVariant }]}>
          {t('sourceIndividual.list.emptyTitle')}
        </Text>
        <Button mode="contained" icon="plus" onPress={openForm} testID={SI_LIST_TEST_IDS.emptyAction}>
          {t('sourceIndividual.list.emptyAction')}
        </Button>
      </View>
    );
  };

  const listFooter = () =>
    loadingMore ? (
      <View style={[styles.center, { padding: spacing.l }]} testID={SI_LIST_TEST_IDS.loadingMore}>
        <ActivityIndicator />
      </View>
    ) : null;

  return (
    <View style={[styles.container, { backgroundColor: theme.colors.background }]} testID={SI_LIST_TEST_IDS.screen}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title={t('sourceIndividual.list.title')} />
        <Appbar.Action
          icon="plus"
          accessibilityLabel={t('sourceIndividual.list.add')}
          testID={SI_LIST_TEST_IDS.add}
          onPress={openForm}
        />
      </Appbar.Header>

      <View style={{ paddingHorizontal: spacing.l, paddingTop: spacing.s, paddingBottom: spacing.s, gap: spacing.s }}>
        <Searchbar
          placeholder={t('sourceIndividual.list.searchPlaceholder')}
          value={keyword}
          onChangeText={setKeyword}
          testID={SI_LIST_TEST_IDS.search}
          accessibilityLabel={t('sourceIndividual.list.searchPlaceholder')}
          style={{ borderRadius: borderRadius.m, backgroundColor: theme.colors.surface }}
        />
        {/* 总数只在真的读到时显示 —— 出错 / 加载中没有一个可信的 N。 */}
        {total !== null && !error && !loading && (
          <Text variant="labelLarge" style={{ color: theme.colors.onSurfaceVariant }} testID={SI_LIST_TEST_IDS.total}>
            {t('sourceIndividual.list.total', { count: total })}
          </Text>
        )}
      </View>

      <FlatList
        data={items}
        keyExtractor={(item) => item.id}
        renderItem={renderRow}
        ItemSeparatorComponent={Divider}
        ListEmptyComponent={listEmpty}
        ListFooterComponent={listFooter}
        onEndReached={loadMore}
        onEndReachedThreshold={0.4}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
        contentContainerStyle={[
          styles.listContent,
          { backgroundColor: theme.colors.surface, borderRadius: borderRadius.m, marginHorizontal: spacing.l, paddingBottom: spacing.xxl },
        ]}
        keyboardShouldPersistTaps="handled"
      />
    </View>
  );
}

// ⚠️ 只放布局: 颜色 / 字号 / 间距全部走 useTheme() 与 Paper 的 variant,
//    `gates/demoScreensUseThemeTokens` 守着本文件里不出现色值与 fontSize<14。
const styles = StyleSheet.create({
  container: { flex: 1 },
  listContent: { flexGrow: 1, overflow: 'hidden' },
  row: { minHeight: 64 },
  rowInner: { flexDirection: 'row', alignItems: 'center' },
  rowText: { flex: 1 },
  mono: { fontFamily: 'monospace' },
  center: { alignItems: 'center', justifyContent: 'center' },
  centerText: { textAlign: 'center' },
});
