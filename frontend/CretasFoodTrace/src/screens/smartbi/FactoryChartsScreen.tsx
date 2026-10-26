import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { FlatList, Modal, StyleSheet, View } from 'react-native';
import { Button, Card, Chip, SegmentedButtons, Switch, Text, TextInput } from 'react-native-paper';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ScreenWrapper } from '../../components/ui/ScreenWrapper';
import { StatusBadge } from '../../components/ui/StatusBadge';
import FactoryChartCanvas, { type FactoryChartCanvasHandle } from '../../components/smartbi/FactoryChartCanvas';
import { useAuthStore } from '../../store/authStore';
import {
  exportFactoryCharts,
  getFactoryChartSelection,
  getFactoryChartUploads,
  getFactoryRecommendations,
  saveFactoryChartSelection,
  shareFactoryChartFile,
} from '../../services/api/smartbiFactoryCharts';
import { buildFactoryChartExportPayload } from '../../utils/smartbiFactoryChartExport';
import type {
  FactoryChartExportFormat,
  FactoryChartPeriodType,
  FactoryChartPoint,
  FactoryChartSource,
  FactoryChartUpload,
  FactoryRecommendation,
  FactoryRecommendationsData,
} from '../../types/factoryCharts';
import type { SmartBIStackParamList } from '../../types/smartbi';
import {
  buildRecommendationRequest,
  normalizeSelectedIds,
  selectRecommendations,
  type FactoryChartPeriodSelection,
} from '../../utils/smartbiFactoryChartModel';

type Navigation = NativeStackNavigationProp<SmartBIStackParamList, 'FactoryCharts'>;

const currentYear = new Date().getFullYear();
const initialPeriod: FactoryChartPeriodSelection = {
  year: currentYear,
  periodType: 'all',
  startMonth: 1,
  endMonth: 12,
};

function asMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请稍后重试';
}

function readAuthEpoch(): string {
  const auth = useAuthStore.getState();
  return `${auth.user?.id || ''}|${auth.getFactoryId() || ''}|${auth.getUserRole() || ''}`;
}

async function waitForCanvasHandle(
  ref: React.MutableRefObject<FactoryChartCanvasHandle | null>,
  ready: () => boolean,
  timeoutMs: number,
  assertCurrent: () => void,
): Promise<FactoryChartCanvasHandle> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    assertCurrent();
    const handle = ref.current;
    if (handle && ready()) return handle;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error('图表未能挂载，无法生成真实图片');
}

interface ChartCardProps {
  item: FactoryRecommendation;
  mounted: boolean;
  chartRef: (value: FactoryChartCanvasHandle | null) => void;
  onPoint: (point: FactoryChartPoint) => void;
  onDetails: () => void;
}

function ChartCard({ item, mounted, chartRef, onPoint, onDetails }: ChartCardProps) {
  return (
    <Card style={styles.card} testID={`factory-chart-card-${item.id}`}>
      <Card.Content>
        <View style={styles.cardHeading}>
          <View style={styles.cardTitleBlock}>
            <Text variant="titleMedium">{item.title}</Text>
            <Text style={styles.muted}>{item.metric} · {item.domain}</Text>
          </View>
          <StatusBadge status={item.priority === 'required' ? '必选' : '可选'} variant={item.priority === 'required' ? 'info' : 'default'} />
        </View>
        <Text style={styles.reason}>{item.reason}</Text>
        {item.aiReason && <Text style={styles.aiReason}>AI：{item.aiReason}</Text>}
        {mounted ? (
          <FactoryChartCanvas
            ref={chartRef}
            option={item.chart.echartsOption}
            title={item.title}
            onPoint={onPoint}
            testID={`factory-chart-canvas-${item.id}`}
          />
        ) : (
          <View style={styles.chartPlaceholder}><Text style={styles.muted}>滚动到此处查看图表</Text></View>
        )}
        <View style={styles.kpiGrid}>
          {item.kpis.map(kpi => <View key={kpi.label} style={styles.kpi}><Text style={styles.muted}>{kpi.label}</Text><Text variant="titleSmall">{kpi.value ?? '—'} {kpi.unit}</Text></View>)}
        </View>
        <Text style={styles.sourceLine}>来源：{item.source} · 单位：{item.unit || '—'}</Text>
        {item.findings.good.length > 0 && <Text style={styles.good}>表现：{item.findings.good.join('；')}</Text>}
        {item.findings.attention.length > 0 && <Text style={styles.attention}>关注：{item.findings.attention.join('；')}</Text>}
        {item.findings.suggestions.length > 0 && <Text style={styles.suggestion}>建议：{item.findings.suggestions.join('；')}</Text>}
        <Button mode="outlined" onPress={onDetails} disabled={!item.detailContext} testID={`factory-details-${item.id}`}>查看原始明细</Button>
      </Card.Content>
    </Card>
  );
}

export default function FactoryChartsScreen() {
  const navigation = useNavigation<Navigation>();
  const { user, getFactoryId, getUserRole } = useAuthStore();
  const authEpoch = `${user?.id || ''}|${getFactoryId() || ''}|${getUserRole() || ''}`;
  const [source, setSource] = useState<FactoryChartSource>('business');
  const [period, setPeriod] = useState(initialPeriod);
  const [includeOptional, setIncludeOptional] = useState(true);
  const [useAi, setUseAi] = useState(true);
  const [uploadChoice, setUploadChoice] = useState<{ id: number; authEpoch: string } | null>(null);
  const uploadId = uploadChoice?.authEpoch === authEpoch ? uploadChoice.id : undefined;
  const setUploadId = (id: number | undefined) => setUploadChoice(id == null ? null : { id, authEpoch });
  const [loadedUploads, setUploads] = useState<FactoryChartUpload[]>([]);
  const [uploadsAuth, setUploadsAuth] = useState(authEpoch);
  const uploads = uploadsAuth === authEpoch ? loadedUploads : [];
  const [loadedData, setData] = useState<FactoryRecommendationsData | null>(null);
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<string[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectionError, setSelectionError] = useState<string | null>(null);
  const [exportError, setExportError] = useState<string | null>(null);
  const [exporting, setExporting] = useState<FactoryChartExportFormat | null>(null);
  const [mountedIds, setMountedIds] = useState<string[]>([]);
  const [showSelection, setShowSelection] = useState(false);
  const [showUnavailable, setShowUnavailable] = useState(false);
  const [exportPreviewId, setExportPreviewId] = useState<string | null>(null);
  const loadToken = useRef(0);
  const chartRefs = useRef<Record<string, FactoryChartCanvasHandle | null>>({});
  const exportRef = useRef<FactoryChartCanvasHandle | null>(null);
  const exportReadyId = useRef<string | null>(null);
  const exportRenderError = useRef<string | null>(null);
  const viewEpoch = useRef(0);
  const mounted = useRef(true);
  const queryKey = JSON.stringify([authEpoch, source, period, uploadId, includeOptional, useAi]);
  const data = loadedKey === queryKey ? loadedData : null;
  const viewKey = JSON.stringify([queryKey, selectedIds]);
  const currentViewKey = useRef(viewKey);
  currentViewKey.current = viewKey;
  const viewableItemsChanged = useCallback((info: { viewableItems: Array<{ item: FactoryRecommendation }> }) => {
    setMountedIds(info.viewableItems.map(view => view.item.id));
  }, []);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      loadToken.current += 1;
      viewEpoch.current += 1;
    };
  }, []);

  const load = useCallback(async () => {
    const token = ++loadToken.current;
    setLoading(true);
    setError(null);
    setSelectionError(null);
    setData(null);
    setSelectedIds(null);
    const requestAuthEpoch = authEpoch;
    if (source === 'upload' && uploadId == null) {
      setLoading(false);
      return;
    }
    try {
      const request = buildRecommendationRequest(source, period, includeOptional, useAi, uploadId);
      const recommendationResponse = await getFactoryRecommendations(request);
      if (!recommendationResponse.success) throw new Error(recommendationResponse.message || '推荐加载失败');
      const selectionResponse = await getFactoryChartSelection(source);
      if (!selectionResponse.success) throw new Error(selectionResponse.message || '来源选择加载失败');
      if (!mounted.current || token !== loadToken.current || requestAuthEpoch !== readAuthEpoch()) return;
      if (recommendationResponse.data.source !== source || selectionResponse.data.source !== source) throw new Error('返回的数据来源与当前选择不一致');
      setData(recommendationResponse.data);
      setLoadedKey(queryKey);
      const savedSelection = normalizeSelectedIds(selectionResponse.data.selectedIds);
      setSelectedIds(savedSelection ?? normalizeSelectedIds(recommendationResponse.data.defaultSelection));
    } catch (loadError) {
      if (mounted.current && token === loadToken.current) setError(asMessage(loadError));
    } finally {
      if (mounted.current && token === loadToken.current) setLoading(false);
    }
  }, [authEpoch, includeOptional, period, queryKey, source, uploadId, useAi]);

  useEffect(() => { void load(); return () => { loadToken.current += 1; }; }, [load]);

  useEffect(() => {
    setUploads([]);
    if (source !== 'upload') return;
    let active = true;
    const requestAuth = authEpoch;
    void getFactoryChartUploads().then(result => { if (active && mounted.current && requestAuth === readAuthEpoch()) { setUploads(result); setUploadsAuth(requestAuth); } }).catch(loadError => { if (active && mounted.current && requestAuth === readAuthEpoch()) setSelectionError(asMessage(loadError)); });
    return () => { active = false; };
  }, [source, authEpoch]);

  const visibleItems = useMemo(() => selectRecommendations(data?.recommendations || [], selectedIds), [data?.recommendations, selectedIds]);
  const contextKey = data ? { factoryId: data.factoryId, source, periodLabel: data.period.label, authEpoch } : null;

  const changeSource = (next: FactoryChartSource) => {
    setSource(next);
    setSelectedIds(null);
    setUploadId(undefined);
  };

  const saveSelection = async () => {
    const saveKey = currentViewKey.current;
    const saveAuth = authEpoch;
    try {
      await saveFactoryChartSelection(source, selectedIds || []);
      if (mounted.current && saveKey === currentViewKey.current && saveAuth === readAuthEpoch()) setSelectionError(null);
    } catch (saveError) { if (mounted.current && saveKey === currentViewKey.current && saveAuth === readAuthEpoch()) setSelectionError(asMessage(saveError)); }
  };

  const handleExport = async (format: FactoryChartExportFormat) => {
    if (!data || !contextKey) return;
    if (visibleItems.length === 0) {
      setExportError('请至少选择一个图表后再导出');
      return;
    }
    setExporting(format);
    setExportError(null);
    exportRenderError.current = null;
    const exportEpoch = viewEpoch.current;
    const exportViewKey = currentViewKey.current;
    const exportLoadToken = loadToken.current;
    const assertExportContext = () => {
      if (!mounted.current || exportEpoch !== viewEpoch.current || exportViewKey !== currentViewKey.current || exportLoadToken !== loadToken.current || authEpoch !== readAuthEpoch()) throw new Error('当前筛选或登录状态已变化，已取消导出');
      if (exportRenderError.current) throw new Error(exportRenderError.current);
    };
    try {
      const images: Record<string, string> = {};
      if (format !== 'excel') {
        for (const item of visibleItems) {
          assertExportContext();
          setExportPreviewId(item.id);
          exportRef.current = null;
          exportReadyId.current = null;
          exportRenderError.current = null;
          const previewChart = await waitForCanvasHandle(exportRef, () => exportReadyId.current === item.id, 10000, assertExportContext);
          const image = await previewChart.capturePng();
          if (!/^data:image\/png;base64,[A-Za-z0-9+/]{16,}={0,2}$/.test(image)) throw new Error(`图表「${item.title}」未返回有效图片`);
          images[item.id] = image;
          assertExportContext();
          setExportPreviewId(null);
        }
      }
      const payload = buildFactoryChartExportPayload(visibleItems, images, {
        factoryId: data.factoryId,
        source,
        sourceLabel: data.sourceLabel,
        periodLabel: data.period.label,
        selectedIds: visibleItems.map(item => item.id),
        year: period.year,
        periodType: period.periodType,
        startMonth: period.startMonth,
        endMonth: period.endMonth,
      });
      assertExportContext();
      const file = await exportFactoryCharts(format, payload);
      assertExportContext();
      await shareFactoryChartFile(file);
      assertExportContext();
    } catch (exportFailure) {
      if (mounted.current) setExportError(asMessage(exportFailure));
    } finally { if (mounted.current) { setExportPreviewId(null); setExporting(null); } }
  };

  const toggleSelection = (id: string) => {
    const allIds = data?.recommendations.map(item => item.id) || [];
    setSelectedIds(current => {
      const next = current == null ? allIds.filter(itemId => itemId !== id) : current.includes(id) ? current.filter(itemId => itemId !== id) : [...current, id];
      return next;
    });
  };

  const renderItem = ({ item }: { item: FactoryRecommendation }) => {
    const mounted = mountedIds.includes(item.id) || mountedIds.length === 0 && visibleItems.indexOf(item) < 3;
    return <ChartCard
      item={item}
      mounted={mounted}
      chartRef={value => { chartRefs.current[item.id] = value; }}
      onPoint={point => {
        if (!data?.factoryId || !item.detailContext || item.detailContext.drilldown === false || !contextKey) return;
        navigation.navigate('FactoryChartDetails', { recommendation: item, source, period, periodLabel: data.period.label, contextKey, point });
      }}
      onDetails={() => {
        if (!data?.factoryId || !item.detailContext || !contextKey) return;
        navigation.navigate('FactoryChartDetails', { recommendation: item, source, period, periodLabel: data.period.label, contextKey });
      }}
    />;
  };

  return (
    <ScreenWrapper testID="factory-charts-screen">
      <FlatList
        testID="factory-chart-list"
        data={visibleItems}
        keyExtractor={item => item.id}
        renderItem={renderItem}
        initialNumToRender={3}
        maxToRenderPerBatch={3}
        windowSize={5}
        onViewableItemsChanged={viewableItemsChanged}
        ListHeaderComponent={<View style={styles.controls}>
          <Text variant="headlineSmall">工厂经营分析</Text>
          <Text style={styles.muted}>真实业务/上传数据的推荐图表与可追溯明细</Text>
          <SegmentedButtons value={source} onValueChange={value => changeSource(value as FactoryChartSource)} buttons={[{ value: 'business', label: '业务数据' }, { value: 'upload', label: '上传数据' }]} />
          {source === 'upload' && <View style={styles.uploadRow}>{uploads.length === 0 ? <Text style={styles.muted}>暂无可用上传文件</Text> : uploads.map(upload => <Chip key={upload.id} selected={uploadId === upload.id} onPress={() => setUploadId(upload.id)}>{upload.fileName}</Chip>)}</View>}
          <SegmentedButtons value={period.periodType} onValueChange={value => setPeriod(current => ({ ...current, periodType: value as FactoryChartPeriodType, ...(value !== 'month_range' ? { startMonth: 1, endMonth: 12 } : {}) }))} buttons={[{ value: 'all', label: '全部历史' }, { value: 'year', label: '指定年度' }, { value: 'month_range', label: '月范围' }]} />
          <View style={styles.periodRow}>
            <TextInput label="年份" accessibilityLabel="年份" disabled={period.periodType === 'all'} value={String(period.year)} keyboardType="number-pad" onChangeText={value => setPeriod(current => ({ ...current, year: Number(value) || current.year }))} style={styles.periodInput} />
            <TextInput label="开始月" accessibilityLabel="开始月" disabled={period.periodType !== 'month_range'} value={String(period.startMonth)} keyboardType="number-pad" onChangeText={value => setPeriod(current => ({ ...current, startMonth: Number(value) || current.startMonth }))} style={styles.periodInput} />
            <TextInput label="结束月" accessibilityLabel="结束月" disabled={period.periodType !== 'month_range'} value={String(period.endMonth)} keyboardType="number-pad" onChangeText={value => setPeriod(current => ({ ...current, endMonth: Number(value) || current.endMonth }))} style={styles.periodInput} />
          </View>
          <View style={styles.switchRow}><Text>包含可选图表</Text><Switch value={includeOptional} onValueChange={setIncludeOptional} /><Text>使用 AI 排序</Text><Switch value={useAi} onValueChange={setUseAi} /></View>
          <Button mode="contained" onPress={() => void load()} loading={loading} disabled={loading} testID="factory-chart-load">刷新推荐</Button>
          {data && <View style={styles.selectionBox}><Button onPress={() => setShowSelection(value => !value)} testID="factory-chart-selection-toggle">{showSelection ? '收起图表目录' : `选择图表（已选 ${visibleItems.length} / 可用 ${data.recommendations.length}）`}</Button>{showSelection && <View style={styles.uploadRow}>{data.recommendations.map(item => <Chip key={item.id} selected={selectedIds === null || selectedIds.includes(item.id)} onPress={() => toggleSelection(item.id)}>{item.title}</Chip>)}</View>}</View>}
          {showSelection && selectedIds !== null && <Button mode="text" onPress={() => void saveSelection()} testID="factory-chart-save-selection">保存当前选择（{selectedIds.length}）</Button>}
          {error && <View style={styles.errorBox}><Text style={styles.errorText}>{error}</Text><Button onPress={() => void load()}>重试</Button></View>}
          {selectionError && <Text style={styles.errorText}>{selectionError}</Text>}
          {exportError && <View style={styles.errorBox}><Text style={styles.errorText}>导出未完成：{exportError}</Text></View>}
          {data && <>
            <Text style={styles.context}>工厂：{data.factoryId} · 来源：{data.sourceLabel} · 周期：{data.period.label} · 排序：{data.ranking.mode === 'ai' ? 'AI' : '规则'}</Text>
            {data.unavailable.length > 0 && <Button onPress={() => setShowUnavailable(value => !value)} testID="factory-chart-unavailable-toggle">{showUnavailable ? '收起条件说明' : `查看未满足条件说明（${data.unavailable.length}）`}</Button>}
            {showUnavailable && data.unavailable.map(item => <Text key={item.id} style={styles.attention}>未提供：{item.title}（{item.missing.join('、')}）</Text>)}
            {data.sourceErrors.map(item => <Text key={item.source} style={styles.errorText}>来源错误：{item.source} · {item.message}</Text>)}
            <View style={styles.exportRow}>{(['excel', 'pdf', 'ppt'] as FactoryChartExportFormat[]).map(format => <Button key={format} mode="outlined" compact onPress={() => void handleExport(format)} loading={exporting === format} disabled={exporting !== null}>{format.toUpperCase()}</Button>)}</View>
          </>}
        </View>}
        ListEmptyComponent={!loading && !error ? <Text style={styles.empty}>当前条件没有可展示的推荐图表。</Text> : null}
      />
      <Modal visible={exportPreviewId !== null} transparent animationType="none" onRequestClose={() => { viewEpoch.current += 1; setExportPreviewId(null); }}>
        <View style={styles.exportOverlay}>
          <View style={styles.exportPreview}>
            <Text variant="titleMedium">正在生成图表文件</Text>
            <Text>{data?.recommendations.find(item => item.id === exportPreviewId)?.title}</Text>
            {exportPreviewId && data && <FactoryChartCanvas key={`export-${queryKey}-${exportPreviewId}`} ref={exportRef} option={data.recommendations.find(item => item.id === exportPreviewId)!.chart.echartsOption} title="导出预览" onReady={() => { exportReadyId.current = exportPreviewId; }} onError={message => { exportRenderError.current = message; }} testID="factory-chart-export-preview" />}
            <Button onPress={() => { viewEpoch.current += 1; setExportPreviewId(null); }}>取消导出</Button>
          </View>
        </View>
      </Modal>
    </ScreenWrapper>
  );
}

const styles = StyleSheet.create({
  controls: { padding: 16, gap: 12 },
  card: { marginHorizontal: 16, marginBottom: 16, borderRadius: 12 },
  cardHeading: { flexDirection: 'row', justifyContent: 'space-between', gap: 8 },
  cardTitleBlock: { flex: 1 },
  muted: { color: '#6B7280', fontSize: 13 },
  reason: { marginTop: 8, color: '#374151' },
  aiReason: { marginTop: 4, color: '#5856D6', fontSize: 13 },
  chartPlaceholder: { height: 80, marginTop: 12, backgroundColor: '#F9FAFB', borderRadius: 8, alignItems: 'center', justifyContent: 'center' },
  kpiGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 10 },
  kpi: { minWidth: '45%', backgroundColor: '#F9FAFB', borderRadius: 8, padding: 8 },
  sourceLine: { marginTop: 10, color: '#6B7280', fontSize: 12 },
  good: { marginTop: 6, color: '#16794A' },
  attention: { marginTop: 6, color: '#B54708' },
  suggestion: { marginTop: 6, color: '#175CD3' },
  uploadRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  selectionBox: { gap: 8 },
  periodRow: { flexDirection: 'row', gap: 8 },
  periodInput: { flex: 1, backgroundColor: '#FFF' },
  switchRow: { flexDirection: 'row', alignItems: 'center', gap: 8, flexWrap: 'wrap' },
  errorBox: { padding: 12, borderRadius: 8, backgroundColor: '#FFF1F0' },
  errorText: { color: '#B42318' },
  context: { color: '#374151', fontSize: 13 },
  exportRow: { flexDirection: 'row', gap: 8, flexWrap: 'wrap' },
  empty: { padding: 24, textAlign: 'center', color: '#6B7280' },
  exportOverlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)', justifyContent: 'center', padding: 20 },
  exportPreview: { width: '100%', backgroundColor: '#FFFFFF', borderRadius: 12, padding: 16, gap: 8 },
});
