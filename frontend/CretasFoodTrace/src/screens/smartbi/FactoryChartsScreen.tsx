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
import {
  filterSubjects,
  groupChartFamilies,
  guideSubjects,
  selectionForFamilies,
  chartVariantLabel,
  selectedFamilyIds,
} from '../../utils/smartbiFactoryChartGuide';

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
  const [guideStep, setGuideStep] = useState<1 | 2 | 3>(1);
  const [subjectChoice, setSubjectChoice] = useState<{ id: string; scope: string } | null>(null);
  const subjectScope = JSON.stringify([authEpoch, source, uploadId]);
  const analysisSubject = subjectChoice?.scope === subjectScope ? subjectChoice.id : null;
  const setAnalysisSubject = (id: string | null) => setSubjectChoice(id ? { id, scope: subjectScope } : null);
  const [subjectQuery, setSubjectQuery] = useState('');
  const [browseDomain, setBrowseDomain] = useState<string | null>(null);
  const [selectedDomain, setSelectedDomain] = useState<string | null>(null);
  const [activeFamily, setActiveFamily] = useState<string | null>(null);
  const [expandedFamily, setExpandedFamily] = useState<string | null>(null);
  const [variantChoice, setVariantChoice] = useState<Map<string, string>>(new Map());
  const selectionRef = useRef<string[] | null>(null);
  const dataRef = useRef<FactoryRecommendationsData | null>(null);
  const [guideSnapshot, setGuideSnapshot] = useState<{ scope: string; data: FactoryRecommendationsData } | null>(null);
  const selectionScopeRef = useRef('');
  const selectionScope = JSON.stringify([authEpoch, source, uploadId, analysisSubject]);
  const guideScope = JSON.stringify([authEpoch, source, uploadId]);
  const [exportPreviewId, setExportPreviewId] = useState<string | null>(null);
  const loadToken = useRef(0);
  const chartRefs = useRef<Record<string, FactoryChartCanvasHandle | null>>({});
  const exportRef = useRef<FactoryChartCanvasHandle | null>(null);
  const exportReadyId = useRef<string | null>(null);
  const exportRenderError = useRef<string | null>(null);
  const viewEpoch = useRef(0);
  const mounted = useRef(true);
  const queryKey = JSON.stringify([authEpoch, source, period, uploadId, includeOptional, useAi, analysisSubject]);
  const data = loadedKey === queryKey ? loadedData : null;
  const guideData = data || (guideSnapshot?.scope === guideScope ? guideSnapshot.data : null);
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
    const priorSelection = selectionRef.current;
    const preserveSelection = selectionScopeRef.current === selectionScope;
    setLoading(true);
    setError(null);
    setSelectionError(null);
    setData(null);
    const requestAuthEpoch = authEpoch;
    if (source === 'upload' && uploadId == null) {
      setLoading(false);
      return;
    }
    try {
      const request = buildRecommendationRequest(source, period, includeOptional, useAi, uploadId, true, analysisSubject);
      const recommendationResponse = await getFactoryRecommendations(request);
      if (!recommendationResponse.success) throw new Error(recommendationResponse.message || '推荐加载失败');
      const selectionResponse = await getFactoryChartSelection(source);
      if (!selectionResponse.success) throw new Error(selectionResponse.message || '来源选择加载失败');
      if (!mounted.current || token !== loadToken.current || requestAuthEpoch !== readAuthEpoch()) return;
      if (recommendationResponse.data.source !== source || selectionResponse.data.source !== source) throw new Error('返回的数据来源与当前选择不一致');
      if (recommendationResponse.data.analysisSubject !== analysisSubject) throw new Error('返回的分析产品与当前选择不一致');
      setData(recommendationResponse.data);
      setGuideSnapshot({ scope: guideScope, data: recommendationResponse.data });
      dataRef.current = recommendationResponse.data;
      selectionScopeRef.current = selectionScope;
      setLoadedKey(queryKey);
      const savedSelection = normalizeSelectedIds(selectionResponse.data.selectedIds);
      setSelectedIds(preserveSelection && priorSelection !== null
        ? priorSelection
        : savedSelection ?? normalizeSelectedIds(recommendationResponse.data.defaultSelection)?.slice(0, 4) ?? []);
    } catch (loadError) {
      if (mounted.current && token === loadToken.current) setError(asMessage(loadError));
    } finally {
      if (mounted.current && token === loadToken.current) setLoading(false);
    }
  }, [analysisSubject, authEpoch, guideScope, includeOptional, period, queryKey, selectionScope, source, uploadId, useAi]);

  useEffect(() => { selectionRef.current = selectedIds; }, [selectedIds]);

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
  const families = useMemo(() => groupChartFamilies(guideData?.recommendations || []), [guideData?.recommendations]);
  const subjects = useMemo(() => guideSubjects(guideData?.subjects), [guideData?.subjects]);
  const filteredSubjects = useMemo(() => filterSubjects(subjects, subjectQuery), [subjects, subjectQuery]);
  const domains = useMemo(() => [...new Set(families.map(family => family.variants[0]?.domain).filter(Boolean))] as string[], [families]);
  const browseFamilies = useMemo(() => browseDomain ? families.filter(family => family.variants.some(item => item.domain === browseDomain)) : families, [browseDomain, families]);
  const contextKey = data ? { factoryId: data.factoryId, source, periodLabel: data.period.label, authEpoch } : null;

  const changeSource = (next: FactoryChartSource) => {
    setSource(next);
    setSelectedIds(null);
    setUploadId(undefined);
    setAnalysisSubject(null);
    setSelectedDomain(null);
    setActiveFamily(null);
    setGuideStep(1);
    setSubjectQuery('');
    setVariantChoice(new Map());
  };

  const chooseSubject = (subject: string) => {
    setAnalysisSubject(subject);
    setGuideStep(3);
  };

  const generateAnalysis = () => {
    if (!data || loading) return;
    const scopedFamilies = families.filter(family => (!selectedDomain || family.variants.some(item => item.domain === selectedDomain)) && (!activeFamily || family.id === activeFamily));
    const nextFamilies = new Set(scopedFamilies.slice(0, 4).map(family => family.id));
    const nextSelection = selectionForFamilies(data?.recommendations || [], nextFamilies, variantChoice, selectedIds || []);
    selectionRef.current = nextSelection;
    setSelectedIds(nextSelection);
    viewEpoch.current += 1;
  };

  useEffect(() => {
    setSelectedDomain(null);
    setActiveFamily(null);
    setGuideStep(1);
    setSubjectQuery('');
    setVariantChoice(new Map());
  }, [authEpoch]);

  const toggleFamily = (familyId: string) => {
    const current = selectedFamilyIds(data?.recommendations || [], selectedIds);
    const next = new Set(current);
    if (next.has(familyId)) next.delete(familyId);
    else if (next.size < 4) next.add(familyId);
    setSelectedIds(selectionForFamilies(data?.recommendations || [], next, variantChoice, selectedIds || []));
  };

  const chooseVariant = (familyId: string, itemId: string) => {
    const next = new Map(variantChoice);
    next.set(familyId, itemId);
    setVariantChoice(next);
    const current = selectedFamilyIds(data?.recommendations || [], selectedIds);
    if (current.has(familyId)) setSelectedIds(selectionForFamilies(data?.recommendations || [], current, next, selectedIds || []));
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
        scopeLabel: data.analysisScopeLabel,
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
          {source === 'upload' && <View style={styles.uploadRow}>{uploads.length === 0 ? <Text style={styles.muted}>暂无可用上传文件</Text> : uploads.map(upload => <Chip key={upload.id} selected={uploadId === upload.id} onPress={() => { setSelectedIds(null); selectionRef.current = null; dataRef.current = null; setAnalysisSubject(null); setSelectedDomain(null); setActiveFamily(null); setGuideStep(1); setUploadId(upload.id); }}>{upload.fileName}</Chip>)}</View>}
          <SegmentedButtons value={period.periodType} onValueChange={value => setPeriod(current => ({ ...current, periodType: value as FactoryChartPeriodType, ...(value !== 'month_range' ? { startMonth: 1, endMonth: 12 } : {}) }))} buttons={[{ value: 'all', label: '全部历史' }, { value: 'year', label: '指定年度' }, { value: 'month_range', label: '月范围' }]} />
          <View style={styles.periodRow}>
            <TextInput label="年份" accessibilityLabel="年份" disabled={period.periodType === 'all'} value={String(period.year)} keyboardType="number-pad" onChangeText={value => setPeriod(current => ({ ...current, year: Number(value) || current.year }))} style={styles.periodInput} />
            <TextInput label="开始月" accessibilityLabel="开始月" disabled={period.periodType !== 'month_range'} value={String(period.startMonth)} keyboardType="number-pad" onChangeText={value => setPeriod(current => ({ ...current, startMonth: Number(value) || current.startMonth }))} style={styles.periodInput} />
            <TextInput label="结束月" accessibilityLabel="结束月" disabled={period.periodType !== 'month_range'} value={String(period.endMonth)} keyboardType="number-pad" onChangeText={value => setPeriod(current => ({ ...current, endMonth: Number(value) || current.endMonth }))} style={styles.periodInput} />
          </View>
          <View style={styles.switchRow}><Text>包含可选图表</Text><Switch value={includeOptional} onValueChange={setIncludeOptional} /><Text>使用 AI 排序</Text><Switch value={useAi} onValueChange={setUseAi} /></View>
          <Card style={styles.guideCard}>
            <Card.Content>
              <Text variant="titleMedium">先回答几个问题，再看分析</Text>
              <Text style={styles.muted}>分析范围：{data?.analysisScopeLabel || (analysisSubject ? '正在确认所选产品范围' : '全厂范围')}</Text>
              <Text style={styles.guideQuestion}>第 1 步：你想解决哪类经营问题？</Text>
              <View style={styles.chipRow}>
                <Chip selected={selectedDomain === null} onPress={() => { setSelectedDomain(null); setActiveFamily(null); setGuideStep(2); }}>先看整体情况</Chip>
                {domains.map(domain => <Chip key={domain} selected={domain === selectedDomain} onPress={() => { setSelectedDomain(domain); setBrowseDomain(domain); setActiveFamily(null); setGuideStep(2); }}>{domain}</Chip>)}
              </View>
              {guideStep >= 2 && <>
                <Text style={styles.guideQuestion}>第 2 步：选择一个分析主题</Text>
                <Chip selected={activeFamily === null} onPress={() => { setActiveFamily(null); setGuideStep(3); }}>推荐组合（最多 4 张）</Chip>
                <View style={styles.chipRow}>{families.filter(family => !selectedDomain || family.variants.some(item => item.domain === selectedDomain)).map(family => <Chip key={family.id} selected={activeFamily === family.id} onPress={() => { setActiveFamily(family.id); setGuideStep(3); }}>{family.label}</Chip>)}</View>
              </>}
              {guideStep >= 3 && <>
                <Text style={styles.guideQuestion}>第 3 步：选择产品（可选）</Text>
                <TextInput label="搜索产品" value={subjectQuery} onChangeText={setSubjectQuery} accessibilityLabel="搜索产品" />
                <Button onPress={() => setAnalysisSubject(null)} testID="factory-guide-all-products">全部产品 / 全厂范围</Button>
                {subjects.length > 30 && <Text style={styles.muted}>仅显示前 30 个匹配项，请搜索产品名称。</Text>}
                {filteredSubjects.length === 0 ? <Text style={styles.muted}>当前范围没有可选产品；可以直接查看分类分析。</Text> : <View style={styles.chipRow}>{filteredSubjects.map(subject => <Chip key={subject.id} selected={analysisSubject === subject.id} onPress={() => chooseSubject(subject.id)}>{subject.label}</Chip>)}</View>}
              </>}
              <Button mode="contained" onPress={generateAnalysis} loading={loading} disabled={loading || !data} testID="factory-chart-load">查看分析</Button>
              <Button onPress={() => void load()} disabled={loading} testID="factory-chart-refresh">刷新数据</Button>
            </Card.Content>
          </Card>
          {data && <View style={styles.selectionBox}><Button onPress={() => setShowSelection(value => !value)} testID="factory-chart-selection-toggle">{showSelection ? '收起分类浏览' : `按分类浏览（已选 ${selectedFamilyIds(data.recommendations, selectedIds).size} 类）`}</Button>{showSelection && <>
            {selectedFamilyIds(data.recommendations, selectedIds).size >= 4 && <Text style={styles.muted}>新组合最多选择 4 类；移除一类后可以加入其他分析。已保存的旧选择仍保留。</Text>}
            <View style={styles.chipRow}>{domains.map(domain => <Chip key={domain} selected={browseDomain === domain} onPress={() => setBrowseDomain(browseDomain === domain ? null : domain)}>{domain}</Chip>)}</View>
            {browseFamilies.map(family => <View key={family.id} style={styles.familyRow} testID={`factory-family-${family.id}`}>
              <View style={styles.familyHeading}><Text variant="titleSmall">{family.label}</Text><Button compact disabled={!selectedFamilyIds(data.recommendations, selectedIds).has(family.id) && selectedFamilyIds(data.recommendations, selectedIds).size >= 4} onPress={() => toggleFamily(family.id)}>{selectedFamilyIds(data.recommendations, selectedIds).has(family.id) ? '移除分类' : '加入分析'}</Button></View>
              {family.variants.length > 1 && <Button compact onPress={() => setExpandedFamily(expandedFamily === family.id ? null : family.id)}>切换画法 / 单位（{family.variants.length}）</Button>}
              {(family.variants.length === 1 || expandedFamily === family.id) && <View style={styles.chipRow}>{family.variants.map(item => <Chip key={item.id} selected={(variantChoice.get(family.id) || family.variants.find(value => selectedIds?.includes(value.id))?.id || family.variants[0]?.id) === item.id} onPress={() => chooseVariant(family.id, item.id)}>{chartVariantLabel(item)}</Chip>)}</View>}
            </View>)}
          </>}</View>}
          {showSelection && selectedIds !== null && <Button mode="text" onPress={() => void saveSelection()} testID="factory-chart-save-selection">保存当前选择（{selectedIds.length}）</Button>}
          {error && <View style={styles.errorBox}><Text style={styles.errorText}>{error}</Text><Button onPress={() => void load()}>重试</Button></View>}
          {selectionError && <Text style={styles.errorText}>{selectionError}</Text>}
          {exportError && <View style={styles.errorBox}><Text style={styles.errorText}>导出未完成：{exportError}</Text></View>}
          {data && <>
            <Text style={styles.context}>工厂：{data.factoryId} · 来源：{data.sourceLabel} · 周期：{data.period.label} · 排序：{data.ranking.mode === 'ai' ? 'AI' : '规则'}（{data.ranking.reason || '未提供原因'}）</Text>
            {data.unavailable.length > 0 && <Button onPress={() => setShowUnavailable(value => !value)} testID="factory-chart-unavailable-toggle">{showUnavailable ? '收起条件说明' : `查看未满足条件说明（${data.unavailable.length}）`}</Button>}
            {showUnavailable && data.unavailable.map(item => <Text key={item.id} style={styles.attention}>未提供：{item.title}（{item.missing.join('、')}）</Text>)}
            {selectedIds && selectedIds.some(id => !data.recommendations.some(item => item.id === id)) && <Text style={styles.attention}>当前周期缺少部分已选分析，已保留选择；请在分类浏览中补选可用变体。</Text>}
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
  guideCard: { borderRadius: 12, backgroundColor: '#E6F7FF' },
  guideQuestion: { marginTop: 10, color: '#1F2937', fontWeight: '600' },
  chipRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8 },
  familyRow: { marginTop: 10, padding: 10, backgroundColor: '#FFFFFF', borderRadius: 8 },
  familyHeading: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
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
