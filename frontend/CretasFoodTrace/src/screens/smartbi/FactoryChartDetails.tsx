import React, { useCallback, useEffect, useRef, useState } from 'react';
import { FlatList, ScrollView, StyleSheet, View } from 'react-native';
import { Button, Card, Chip, Text, TextInput } from 'react-native-paper';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { ScreenWrapper } from '../../components/ui/ScreenWrapper';
import { useAuthStore } from '../../store/authStore';
import { getFactoryChartDetails } from '../../services/api/smartbiFactoryCharts';
import type { FactoryChartDetailFilter, FactoryChartDetailsData } from '../../types/factoryCharts';
import type { SmartBIStackParamList } from '../../types/smartbi';
import { appendDetailsPage, buildDetailsRequest, formatChartCell, isSameChartContext } from '../../utils/smartbiFactoryChartModel';

type Props = NativeStackScreenProps<SmartBIStackParamList, 'FactoryChartDetails'>;

function messageFor(error: unknown): string {
  return error instanceof Error ? error.message : '明细加载失败，请重试';
}

function readAuthEpoch(): string {
  const auth = useAuthStore.getState();
  return `${auth.user?.id || ''}|${auth.getFactoryId() || ''}|${auth.getUserRole() || ''}`;
}

interface DetailAttempt {
  page: number;
  filters: FactoryChartDetailFilter[];
  append: boolean;
  authEpoch: string;
}

export default function FactoryChartDetails({ route }: Props) {
  const { user, getFactoryId, getUserRole } = useAuthStore();
  const { recommendation, point, periodLabel, contextKey } = route.params;
  const detailContext = recommendation.detailContext;
  const [data, setData] = useState<FactoryChartDetailsData | null>(null);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [filterField, setFilterField] = useState<string | null>(null);
  const [filterValue, setFilterValue] = useState('');
  const [activeFilters, setActiveFilters] = useState<FactoryChartDetailFilter[]>([]);
  const requestToken = useRef(0);
  const closed = useRef(false);
  const lastAttempt = useRef<DetailAttempt | null>(null);
  const authEpoch = `${user?.id || ''}|${getFactoryId() || ''}|${getUserRole() || ''}`;

  const loadPage = useCallback(async (nextPage: number, filters: readonly FactoryChartDetailFilter[] = [], append = false) => {
    if (!detailContext?.id) {
      setError('该图表没有可用的明细上下文');
      return;
    }
    const expiresAt = Date.parse(detailContext.expiresAt);
    if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
      setError('明细上下文已过期，请返回图表页重新打开');
      return;
    }
    if (contextKey.authEpoch !== authEpoch || readAuthEpoch() !== authEpoch) {
      setData(null);
      setError('登录状态或权限已变化，请重新打开明细');
      return;
    }
    const attempt: DetailAttempt = { page: nextPage, filters: [...filters], append, authEpoch };
    lastAttempt.current = attempt;
    const token = ++requestToken.current;
    setLoading(true);
    setError(null);
    try {
      const detailPoint = detailContext.drilldown === false ? undefined : point;
      const response = await getFactoryChartDetails(buildDetailsRequest(detailContext.id, detailPoint, nextPage, filters));
      if (closed.current || token !== requestToken.current || readAuthEpoch() !== attempt.authEpoch) return;
      if (!response.success || !response.data) throw new Error(response.message || '明细加载失败');
      const next = response.data;
      const currentContext = contextKey;
      const responseContext = { ...currentContext, factoryId: next.factoryId };
      if (!isSameChartContext(currentContext, responseContext)
        || next.source !== recommendation.source
        || next.period !== periodLabel
        || next.candidateId !== recommendation.id
        || next.page !== nextPage
        || next.pageSize !== 50) {
        throw new Error('明细响应与请求分页契约不一致');
      }
      setPage(nextPage);
      setData(previous => append ? appendDetailsPage(previous, next) : { ...next, rows: [...next.rows] });
    } catch (loadError) {
      if (!closed.current && token === requestToken.current && readAuthEpoch() === attempt.authEpoch) setError(messageFor(loadError));
    } finally {
      if (!closed.current && token === requestToken.current && readAuthEpoch() === attempt.authEpoch) setLoading(false);
    }
  }, [authEpoch, contextKey, detailContext, periodLabel, point, recommendation.id, recommendation.source]);

  useEffect(() => {
    setData(null);
    setPage(1);
    setError(null);
  }, [authEpoch]);

  useEffect(() => {
    closed.current = false;
    void loadPage(1);
    return () => { closed.current = true; requestToken.current += 1; };
  }, [loadPage]);

  const applyFilter = () => {
    if (!filterField || !filterValue.trim()) {
      setActiveFilters([]);
      void loadPage(1, []);
      return;
    }
    const nextFilters = [{ field: filterField, value: filterValue.trim() }];
    setActiveFilters(nextFilters);
    void loadPage(1, nextFilters);
  };

  const retryLastAttempt = () => {
    const attempt = lastAttempt.current;
    if (attempt) void loadPage(attempt.page, attempt.filters, attempt.append);
  };

  const renderRow = ({ item, index }: { item: Record<string, unknown>; index: number }) => (
    <View style={styles.row} testID={`factory-detail-row-${index}`}>
      {data?.columns.map(column => <Text key={column} style={styles.cell} selectable>{formatChartCell(item[column])}</Text>)}
    </View>
  );

  return (
    <ScreenWrapper testID="factory-chart-details-screen">
      <View style={styles.header}>
        <Text variant="headlineSmall">{recommendation.title} · 原始明细</Text>
        <Text style={styles.muted}>来源：{contextKey.source} · 周期：{periodLabel}{detailContext?.drilldown !== false && point ? ` · 点位：${point.name}` : ''}</Text>
        {data && <Text style={styles.muted}>第 {page} 页 · 每页 {data.pageSize} 行 · 总计 {data.total} 行</Text>}
      </View>
      {data?.availableFilters.length ? <View style={styles.filters}><View style={styles.filterChips}>{data.availableFilters.map(filter => <Chip key={filter.field} selected={filterField === filter.field} onPress={() => setFilterField(filter.field)}>{filter.label}</Chip>)}</View><View style={styles.filterInput}><TextInput testID="factory-detail-filter-input" label="过滤值" value={filterValue} onChangeText={setFilterValue} style={styles.input} /><Button mode="contained" onPress={applyFilter} disabled={loading}>筛选</Button></View></View> : null}
      {error && <Card style={styles.errorCard}><Card.Content><Text style={styles.errorText}>{error}</Text><Button onPress={retryLastAttempt}>重试</Button></Card.Content></Card>}
      <ScrollView horizontal style={styles.tableViewport} contentContainerStyle={styles.tableScroll} nestedScrollEnabled>
        <View style={styles.table}>
          <View style={styles.tableHeader}>{data?.columns.map(column => <Text key={column} style={styles.headerCell}>{column}</Text>)}</View>
          <FlatList
            testID="factory-detail-table"
            style={styles.tableList}
            data={data?.rows || []}
            keyExtractor={(_, index) => `row-${page}-${index}`}
            renderItem={renderRow}
            initialNumToRender={20}
            maxToRenderPerBatch={20}
            windowSize={7}
            ListEmptyComponent={!loading && !error ? <Text style={styles.empty}>暂无明细数据</Text> : null}
          />
        </View>
      </ScrollView>
      <View style={styles.pager}><Button mode="outlined" onPress={() => void loadPage(page - 1, activeFilters)} disabled={loading || page <= 1}>上一页</Button><Button mode="outlined" onPress={() => void loadPage(page + 1, activeFilters)} disabled={loading || !data?.hasMore}>下一页</Button><Button mode="contained-tonal" onPress={() => void loadPage(page + 1, activeFilters, true)} disabled={loading || !data?.hasMore}>加载更多</Button></View>
    </ScreenWrapper>
  );
}

const styles = StyleSheet.create({
  header: { padding: 16, gap: 4 },
  muted: { color: '#6B7280', fontSize: 13 },
  filters: { paddingHorizontal: 16, paddingBottom: 8, gap: 8 },
  filterChips: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  filterInput: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  input: { flex: 1, backgroundColor: '#FFF' },
  tableHeader: { flexDirection: 'row', backgroundColor: '#E6F7FF', padding: 8, marginHorizontal: 16, gap: 8 },
  tableScroll: { minWidth: '100%' },
  tableViewport: { flex: 1 },
  table: { minWidth: 520, flex: 1 },
  tableList: { flex: 1 },
  headerCell: { width: 160, fontWeight: '700', color: '#1F2937', fontSize: 12 },
  row: { flexDirection: 'row', padding: 8, borderBottomWidth: 1, borderBottomColor: '#E5E7EB', gap: 8 },
  cell: { width: 160, color: '#374151', fontSize: 12 },
  pager: { flexDirection: 'row', justifyContent: 'center', gap: 8, padding: 12 },
  errorCard: { marginHorizontal: 16, marginBottom: 8, backgroundColor: '#FFF1F0' },
  errorText: { color: '#B42318' },
  empty: { padding: 24, textAlign: 'center', color: '#6B7280' },
});
