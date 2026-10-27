import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { KeyboardAvoidingView, Modal, Platform, ScrollView, StyleSheet, View } from 'react-native';
import { Button, Card, Chip, Divider, SegmentedButtons, Text, TextInput } from 'react-native-paper';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp, NativeStackScreenProps } from '@react-navigation/native-stack';
import { ScreenWrapper } from '../../components/ui/ScreenWrapper';
import FactoryChartCanvas, { type FactoryChartCanvasHandle } from '../../components/smartbi/FactoryChartCanvas';
import { useAuthStore } from '../../store/authStore';
import { askFactoryAnalysis, FactoryAnalysisApiError } from '../../services/api/smartbiFactoryAnalysis';
import { exportFactoryCharts, shareFactoryChartFile } from '../../services/api/smartbiFactoryCharts';
import type { FactoryChartExportFormat, FactoryRecommendation } from '../../types/factoryCharts';
import { buildFactoryChartExportPayload } from '../../utils/smartbiFactoryChartExport';
import type { FactoryChartPeriodSelection } from '../../utils/smartbiFactoryChartModel';
import type {
  FactoryAnalysisCatalogItem,
  FactoryAnalysisData,
  FactoryAnalysisFact,
} from '../../types/factoryAnalysis';
import type { SmartBIStackParamList } from '../../types/smartbi';

type Props = NativeStackScreenProps<SmartBIStackParamList, 'FactoryAnalysisChat'>;
type Navigation = NativeStackNavigationProp<SmartBIStackParamList, 'FactoryAnalysisChat'>;

interface ChatTurn {
  id: string;
  question?: string;
  answer?: FactoryAnalysisData;
  error?: string;
  pendingQuestion?: string;
}

interface ExportJob {
  answer: FactoryAnalysisData;
  items: FactoryRecommendation[];
  format: FactoryChartExportFormat;
  candidateId: string | null;
  scopeKey: string;
}

const DEFAULT_PERIOD: FactoryChartPeriodSelection = {
  year: new Date().getFullYear(),
  periodType: 'all',
  startMonth: 1,
  endMonth: 12,
};

function authEpoch(): string {
  const auth = useAuthStore.getState();
  return `${auth.user?.id || ''}|${auth.getFactoryId() || ''}|${auth.getUserRole() || ''}`;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '工厂经营分析请求失败，请稍后重试';
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
    if (ref.current && ready()) return ref.current;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error('图表未能挂载，无法生成真实图片');
}

function familyId(item: FactoryRecommendation): string {
  const base = item.id.split('@')[0] || item.id;
  return item.analysis?.familyId || base.split(':')[0] || item.id;
}

/** Keep all catalogue types, while an answer renders at most four chart families. */
function answerRecommendations(items: readonly FactoryRecommendation[]): FactoryRecommendation[] {
  const families = new Set<string>();
  return items.filter(item => {
    const family = familyId(item);
    if (families.has(family)) return true;
    if (families.size >= 4) return false;
    families.add(family);
    return true;
  });
}

function isPrimitive(value: unknown): value is string | number | boolean | null {
  return value === null || typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean';
}

function FactList({ facts }: { facts: readonly FactoryAnalysisFact[] }) {
  if (facts.length === 0) return <Text style={styles.muted}>当前回答没有可直接呈现的 KPI 事实。</Text>;
  return <View style={styles.factList} testID="factory-analysis-facts">
    {facts.map(fact => <View key={fact.id} style={styles.factRow}>
      <Text style={styles.factLabel}>{fact.label}</Text>
      <Text style={styles.factValue}>{isPrimitive(fact.value) ? String(fact.value ?? '—') : '—'} {fact.unit || ''}</Text>
      <Text style={styles.factSource}>来源：{fact.source || '未提供'}</Text>
    </View>)}
  </View>;
}

function ChartList({
  items,
  onDetails,
}: {
  items: readonly FactoryRecommendation[];
  onDetails: (item: FactoryRecommendation) => void;
}) {
  return <View testID="factory-analysis-chart-list">
    {items.map(item => <Card key={item.id} style={styles.chartCard} testID={`factory-analysis-chart-${item.id}`}>
      <Card.Content>
        <Text variant="titleMedium">{item.title}</Text>
        <Text style={styles.muted}>{item.metric} · {item.domain} · 单位：{item.unit || '—'}</Text>
        <FactoryChartCanvas option={item.chart.echartsOption} title={item.title} testID={`factory-analysis-canvas-${item.id}`} />
        <View style={styles.kpiGrid}>
          {item.kpis.map(kpi => <View key={kpi.label} style={styles.kpi}><Text style={styles.muted}>{kpi.label}</Text><Text>{String(kpi.value ?? '—')} {kpi.unit}</Text></View>)}
        </View>
        <Text style={styles.sourceLine}>来源：{item.source || '未提供'} · 原始单位：{item.unit || '—'}</Text>
        <Button mode="outlined" onPress={() => onDetails(item)} disabled={!item.detailContext}>查看原始明细</Button>
      </Card.Content>
    </Card>)}
  </View>;
}

function Catalog({ catalog }: { catalog: readonly FactoryAnalysisCatalogItem[] }) {
  const [expanded, setExpanded] = useState(false);
  if (catalog.length === 0) return null;
  const visible = expanded ? catalog : catalog.slice(0, 8);
  return <View testID="factory-analysis-catalog" style={styles.catalog}>
    <View style={styles.catalogHeading}><Text variant="titleMedium">可用图表目录（{catalog.length}）</Text><Button compact onPress={() => setExpanded(value => !value)}>{expanded ? '收起目录' : '展开目录'}</Button></View>
    {visible.map(item => <View key={item.id} style={styles.catalogRow}><Text style={styles.catalogTitle}>{item.domain || '未分类'} · {item.metric || item.chartType}</Text><Text style={styles.muted}>{item.familyId} · {item.unit || '单位未提供'} · {item.sourceKey || '来源未提供'}</Text></View>)}
    {!expanded && catalog.length > visible.length && <Text style={styles.muted}>目录其余 {catalog.length - visible.length} 项未展开；回答仅渲染已选图表。</Text>}
  </View>;
}

function SubjectPicker({
  subjects,
  selected,
  onSelect,
}: {
  subjects: readonly { id: string; label: string }[];
  selected: string | null;
  onSelect: (id: string) => void;
}) {
  const [query, setQuery] = useState('');
  const visible = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase();
    return subjects.filter(item => !normalized || item.label.toLocaleLowerCase().includes(normalized)).slice(0, 8);
  }, [query, subjects]);
  return <View testID="factory-analysis-subject-picker" style={styles.subjectPicker}>
    <Text style={styles.muted}>请选择明确产品或 SKU 后继续；不会默认改为全厂范围。</Text>
    <TextInput label="搜索产品 / SKU" value={query} onChangeText={setQuery} accessibilityLabel="搜索产品 / SKU" />
    <View style={styles.chipRow}>{visible.map(item => <Chip key={item.id} selected={selected === item.id} onPress={() => onSelect(item.id)}>{item.label}</Chip>)}</View>
    {visible.length === 0 && <Text style={styles.muted}>没有匹配的授权产品。</Text>}
    {subjects.length > visible.length && <Text style={styles.muted}>最多展示 8 个匹配项，请继续搜索。</Text>}
  </View>;
}

function AnswerCard({
  turn,
  onFollowUp,
  onSubjectSelect,
  selectedSubject,
  onClarificationRetry,
  onDetails,
  onExport,
  exporting,
}: {
  turn: ChatTurn;
  onFollowUp: (question: string) => void;
  onSubjectSelect: (id: string) => void;
  selectedSubject: string | null;
  onClarificationRetry: () => void;
  onDetails: (item: FactoryRecommendation) => void;
  onExport: (answer: FactoryAnalysisData, format: FactoryChartExportFormat) => void;
  exporting: FactoryChartExportFormat | null;
}) {
  if (!turn.answer) return null;
  const data = turn.answer;
  const charts = data.status === 'answered' ? answerRecommendations(data.recommendations) : [];
  return <Card style={styles.answerCard} testID={`factory-analysis-answer-${turn.id}`}>
    <Card.Content>
      <Text variant="titleMedium">{data.status === 'answered' ? '分析回答' : data.status === 'clarification' ? '需要补充范围' : '分析暂不可用'}</Text>
      <Text style={styles.answerText}>{data.answer}</Text>
      {data.sourceErrors.length > 0 && <View style={styles.errorBox} testID="factory-analysis-source-errors"><Text style={styles.errorTitle}>数据来源错误</Text>{data.sourceErrors.map(item => <Text key={`${item.source}-${item.message}`} style={styles.errorText}>{item.source}：{item.message}</Text>)}</View>}
      {data.status === 'clarification' && data.subjects.length > 0 && <>
        <SubjectPicker subjects={data.subjects} selected={selectedSubject} onSelect={onSubjectSelect} />
        <Button mode="contained" onPress={onClarificationRetry} disabled={!selectedSubject} testID="factory-analysis-clarification-retry">选择后继续分析</Button>
      </>}
      {data.status === 'answered' && <>
        <Text variant="titleMedium" style={styles.sectionTitle}>可信事实</Text>
        <FactList facts={data.facts} />
        {data.limitations.length > 0 && <View style={styles.limitationBox} testID="factory-analysis-limitations"><Text style={styles.errorTitle}>限制与未覆盖范围</Text>{data.limitations.map(item => <Text key={item} style={styles.muted}>• {item}</Text>)}</View>}
        {charts.length > 0 && <>
          <Text variant="titleMedium" style={styles.sectionTitle}>关联图表（最多 4 类）</Text>
          <ChartList items={charts} onDetails={onDetails} />
          <View style={styles.exportRow} testID={`factory-analysis-export-${turn.id}`}>
            <Text style={styles.muted}>导出此回答的真实图表快照：</Text>
            {(['excel', 'pdf', 'ppt'] as FactoryChartExportFormat[]).map(format => <Button key={format} mode="outlined" compact onPress={() => onExport(data, format)} loading={exporting === format} disabled={exporting !== null}>{format.toUpperCase()}</Button>)}
          </View>
        </>}
      </>}
      <Catalog catalog={data.catalog} />
      {data.followUpQuestions.length > 0 && <View style={styles.followUps} testID="factory-analysis-followups"><Text variant="titleMedium">继续追问</Text><View style={styles.chipRow}>{data.followUpQuestions.map(question => <Chip key={question} onPress={() => onFollowUp(question)}>{question}</Chip>)}</View></View>}
    </Card.Content>
  </Card>;
}

export default function FactoryAnalysisChatScreen({ route }: Props) {
  const navigation = useNavigation<Navigation>();
  const identityUser = useAuthStore(state => state.user);
  const currentAuthEpoch = `${identityUser?.id || ''}|${useAuthStore.getState().getFactoryId() || ''}|${useAuthStore.getState().getUserRole() || ''}`;
  const period = useMemo<FactoryChartPeriodSelection>(() => ({
    year: route.params?.year ?? DEFAULT_PERIOD.year,
    periodType: route.params?.periodType ?? DEFAULT_PERIOD.periodType,
    startMonth: route.params?.startMonth ?? DEFAULT_PERIOD.startMonth,
    endMonth: route.params?.endMonth ?? DEFAULT_PERIOD.endMonth,
  }), [route.params]);
  const source = route.params?.source ?? 'business';
  const scopeKey = JSON.stringify([
    currentAuthEpoch,
    source,
    route.params?.uploadId ?? null,
    period,
    route.params?.analysisSubject ?? null,
  ]);
  const [messages, setMessages] = useState<ChatTurn[]>([]);
  const [input, setInput] = useState('');
  const [contextId, setContextId] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [pendingQuestion, setPendingQuestion] = useState<string | null>(null);
  const [selectedSubject, setSelectedSubject] = useState<string | null>(route.params?.analysisSubject ?? null);
  const [contextExpired, setContextExpired] = useState(false);
  const [exportJob, setExportJob] = useState<ExportJob | null>(null);
  const [exporting, setExporting] = useState<FactoryChartExportFormat | null>(null);
  const [exportError, setExportError] = useState<string | null>(null);
  const requestToken = useRef(0);
  const initialSent = useRef(false);
  const scopeKeyRef = useRef(scopeKey);
  const scopeEpochRef = useRef(0);
  const exportRef = useRef<FactoryChartCanvasHandle | null>(null);
  const exportReadyId = useRef<string | null>(null);
  const exportRenderError = useRef<string | null>(null);

  const sendQuestion = useCallback(async (rawQuestion: string, options: { analysisSubject?: string; resetContext?: boolean } = {}) => {
    const question = rawQuestion.trim();
    if (!question || loading) return;
    const requestAuth = authEpoch();
    const requestScope = scopeKeyRef.current;
    const token = ++requestToken.current;
    const requestContext = options.resetContext ? undefined : contextId || undefined;
    const requestSubject = options.analysisSubject ?? (!requestContext ? route.params?.analysisSubject : undefined);
    const turnId = `factory-analysis-${token}`;
    setLoading(true);
    setContextExpired(false);
    setPendingQuestion(null);
    setMessages(current => [...current, { id: turnId, question }]);
    try {
      const data = await askFactoryAnalysis(requestContext
        ? {
          question,
          contextId: requestContext,
          ...(requestSubject ? { analysisSubject: requestSubject } : {}),
        }
        : {
          question,
          source,
          ...(route.params?.uploadId !== undefined ? { uploadId: route.params.uploadId } : {}),
          year: period.year,
          periodType: period.periodType,
          startMonth: period.startMonth,
          endMonth: period.endMonth,
          ...(requestSubject ? { analysisSubject: requestSubject } : {}),
        });
      if (token !== requestToken.current || requestAuth !== authEpoch() || requestScope !== scopeKeyRef.current) return;
      setContextId(data.contextId || null);
      setMessages(current => current.map(item => item.id === turnId ? { ...item, answer: data } : item));
    } catch (error) {
      if (token !== requestToken.current || requestAuth !== authEpoch() || requestScope !== scopeKeyRef.current) return;
      const isExpired = error instanceof FactoryAnalysisApiError && error.status === 410;
      setContextExpired(isExpired);
      setPendingQuestion(question);
      setMessages(current => current.map(item => item.id === turnId ? { ...item, error: errorMessage(error), pendingQuestion: question } : item));
    } finally {
      if (token === requestToken.current && requestAuth === authEpoch() && requestScope === scopeKeyRef.current) setLoading(false);
    }
  }, [contextId, loading, period, route.params?.uploadId, scopeKey, source]);

  useEffect(() => {
    if (scopeKeyRef.current === scopeKey) return;
    scopeKeyRef.current = scopeKey;
    requestToken.current += 1;
    scopeEpochRef.current += 1;
    setMessages([]);
    setContextId(null);
    setLoading(false);
    setPendingQuestion(null);
    setSelectedSubject(route.params?.analysisSubject ?? null);
    setContextExpired(false);
    setInput('');
    setExportJob(null);
    setExporting(null);
    setExportError(null);
    initialSent.current = false;
  }, [scopeKey]);

  useEffect(() => {
    if (!route.params?.initialQuestion || initialSent.current) return;
    initialSent.current = true;
    void sendQuestion(route.params.initialQuestion, { analysisSubject: route.params.analysisSubject });
  }, [route.params?.analysisSubject, route.params?.initialQuestion, sendQuestion]);

  const clearConversation = () => {
    requestToken.current += 1;
    scopeEpochRef.current += 1;
    setMessages([]);
    setContextId(null);
    setPendingQuestion(null);
    setSelectedSubject(null);
    setContextExpired(false);
    setInput('');
    setExportJob(null);
    setExporting(null);
    setExportError(null);
  };

  const retryAfterClearingContext = () => {
    if (!pendingQuestion) return;
    const question = pendingQuestion;
    clearConversation();
    void sendQuestion(question, { resetContext: true });
  };

  const latestClarification = [...messages].reverse().find(turn => turn.answer?.status === 'clarification');
  const onDetails = (item: FactoryRecommendation, answer?: FactoryAnalysisData) => {
    const data = answer || [...messages].reverse().map(item => item.answer).find(value => value?.recommendations.some(candidate => candidate.id === item.id));
    if (!data?.factoryId || !item.detailContext) return;
    navigation.navigate('FactoryChartDetails', {
      recommendation: item,
      source: data.source,
      period,
      periodLabel: data.period?.label || '当前期间',
      contextKey: { factoryId: data.factoryId, source: data.source, periodLabel: data.period?.label || '当前期间', authEpoch: authEpoch() },
    });
  };

  const startExport = async (answer: FactoryAnalysisData, format: FactoryChartExportFormat) => {
    const items = answerRecommendations(answer.recommendations);
    if (items.length === 0) {
      setExportError('当前回答没有可导出的真实图表');
      return;
    }
    const exportScope = scopeKeyRef.current;
    const exportEpoch = scopeEpochRef.current;
    const exportAuth = authEpoch();
    const assertCurrent = () => {
      if (exportScope !== scopeKeyRef.current || exportEpoch !== scopeEpochRef.current || exportAuth !== authEpoch()) {
        throw new Error('当前筛选、登录状态或回答上下文已变化，已取消导出');
      }
      if (exportRenderError.current) throw new Error(exportRenderError.current);
    };
    setExportError(null);
    setExporting(format);
    const job: ExportJob = { answer, items, format, candidateId: null, scopeKey: exportScope };
    setExportJob(job);
    exportRenderError.current = null;
    try {
      const images: Record<string, string> = {};
      for (const item of items) {
        assertCurrent();
        exportRef.current = null;
        exportReadyId.current = null;
        exportRenderError.current = null;
        setExportJob(current => current ? { ...current, candidateId: item.id } : current);
        const chart = await waitForCanvasHandle(exportRef, () => exportReadyId.current === item.id, 10000, assertCurrent);
        const image = await chart.capturePng();
        assertCurrent();
        if (!/^data:image\/png;base64,[A-Za-z0-9+/]{16,}={0,2}$/.test(image)) {
          throw new Error(`图表「${item.title}」未返回有效图片`);
        }
        images[item.id] = image;
      }
      assertCurrent();
      const payload = buildFactoryChartExportPayload(items, images, {
        factoryId: answer.factoryId,
        source: answer.source,
        sourceLabel: answer.sourceLabel || answer.source,
        scopeLabel: answer.analysisScopeLabel,
        periodLabel: answer.period?.label || '当前期间',
        selectedIds: items.map(item => item.id),
        year: period.year,
        periodType: period.periodType,
        startMonth: period.startMonth,
        endMonth: period.endMonth,
      });
      const file = await exportFactoryCharts(format, payload);
      assertCurrent();
      await shareFactoryChartFile(file);
      assertCurrent();
    } catch (error) {
      if (exportScope === scopeKeyRef.current && exportEpoch === scopeEpochRef.current && exportAuth === authEpoch()) {
        setExportError(errorMessage(error));
      }
    } finally {
      if (exportScope === scopeKeyRef.current && exportEpoch === scopeEpochRef.current) {
        setExportJob(null);
        setExporting(null);
      }
    }
  };

  return <ScreenWrapper testID="factory-analysis-chat-screen" statusBarTranslucent={false}>
    <KeyboardAvoidingView style={styles.container} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView testID="factory-analysis-thread" style={styles.thread} contentContainerStyle={styles.threadContent} keyboardShouldPersistTaps="handled">
        <Text variant="headlineSmall">工厂经营分析 AI</Text>
        <Text style={styles.muted}>只基于当前工厂授权的真实图表、KPI 和来源；不会写入业务数据。</Text>
        <SegmentedButtons value={source} buttons={[{ value: 'business', label: '业务数据' }, { value: 'upload', label: '上传数据' }]} onValueChange={() => { /* source is fixed by the entered route; upload uses an explicit uploadId */ }} />
        {messages.length === 0 && <Card style={styles.welcome}><Card.Content><Text variant="titleMedium">可以问我：</Text><Text style={styles.muted}>“看一下本月产量和质量变化”或“哪些指标需要关注？”</Text></Card.Content></Card>}
        {messages.map(turn => <View key={turn.id}>
          {turn.question && <View style={styles.userQuestion}><Text style={styles.userQuestionText}>{turn.question}</Text></View>}
          {turn.error && <Card style={styles.errorCard}><Card.Content><Text style={styles.errorText}>{turn.error}</Text>{contextExpired ? <Button mode="contained" onPress={retryAfterClearingContext} testID="factory-analysis-clear-context-retry">清除上下文并重试</Button> : <Button onPress={() => void sendQuestion(turn.question || '')}>再试一次</Button>}</Card.Content></Card>}
          <AnswerCard turn={turn} onFollowUp={question => void sendQuestion(question)} onSubjectSelect={setSelectedSubject} selectedSubject={selectedSubject} onClarificationRetry={() => { if (latestClarification?.answer?.contextId && selectedSubject) { setContextId(latestClarification.answer.contextId); void sendQuestion(latestClarification.question || pendingQuestion || '', { analysisSubject: selectedSubject }); } }} onDetails={item => onDetails(item, turn.answer)} onExport={(answer, format) => void startExport(answer, format)} exporting={exporting} />
        </View>)}
        {loading && <Text style={styles.loading} testID="factory-analysis-loading">正在读取授权图表并生成分析…</Text>}
        {exportError && <View style={styles.errorBox} testID="factory-analysis-export-error"><Text style={styles.errorText}>导出未完成：{exportError}</Text></View>}
        {latestClarification && latestClarification.answer?.status === 'clarification' && <Divider />}
      </ScrollView>
      <View style={styles.inputBar}>
        <TextInput testID="factory-analysis-input" label="输入经营问题" value={input} onChangeText={setInput} multiline maxLength={1000} style={styles.input} editable={!loading} />
        <Button mode="contained" onPress={() => { const value = input; setInput(''); void sendQuestion(value); }} disabled={loading || !input.trim()} testID="factory-analysis-send">发送</Button>
        <Button mode="text" onPress={clearConversation} disabled={loading} testID="factory-analysis-clear">清空对话</Button>
      </View>
    </KeyboardAvoidingView>
    <Modal visible={exportJob !== null} transparent animationType="none" onRequestClose={() => { scopeEpochRef.current += 1; setExportJob(null); setExporting(null); }}>
      <View style={styles.exportOverlay}>
        <View style={styles.exportPreview}>
          <Text variant="titleMedium">正在生成 {exportJob?.format.toUpperCase()} 文件</Text>
          <Text style={styles.muted}>{exportJob?.items.find(item => item.id === exportJob.candidateId)?.title || '准备真实图表快照…'}</Text>
          {exportJob?.candidateId && <FactoryChartCanvas
            key={`factory-analysis-export-${exportJob.scopeKey}-${exportJob.candidateId}`}
            ref={exportRef}
            option={exportJob.items.find(item => item.id === exportJob.candidateId)?.chart.echartsOption || {}}
            title="导出预览"
            onReady={() => { exportReadyId.current = exportJob.candidateId; }}
            onError={message => { exportRenderError.current = message; }}
            testID="factory-analysis-export-preview"
          />}
          <Button onPress={() => { scopeEpochRef.current += 1; setExportJob(null); setExporting(null); }}>取消导出</Button>
        </View>
      </View>
    </Modal>
  </ScreenWrapper>;
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  thread: { flex: 1 },
  threadContent: { padding: 16, gap: 12, paddingBottom: 20 },
  muted: { color: '#667085', fontSize: 13 },
  welcome: { backgroundColor: '#F5FAFF' },
  userQuestion: { alignSelf: 'flex-end', maxWidth: '88%', borderRadius: 12, backgroundColor: '#E6F4FF', paddingHorizontal: 12, paddingVertical: 10 },
  userQuestionText: { color: '#12344D' },
  answerCard: { borderRadius: 12, backgroundColor: '#FFFFFF' },
  answerText: { marginTop: 8, color: '#1D2939', lineHeight: 22 },
  sectionTitle: { marginTop: 14, marginBottom: 6 },
  factList: { gap: 8 },
  factRow: { borderRadius: 8, backgroundColor: '#F8FAFC', padding: 10 },
  factLabel: { color: '#344054', fontWeight: '600' },
  factValue: { marginTop: 3, color: '#101828', fontSize: 16 },
  factSource: { marginTop: 3, color: '#667085', fontSize: 12 },
  limitationBox: { marginTop: 10, gap: 4, borderRadius: 8, padding: 10, backgroundColor: '#FFFAEB' },
  errorBox: { marginTop: 10, gap: 4, borderRadius: 8, padding: 10, backgroundColor: '#FFF1F0' },
  errorCard: { backgroundColor: '#FFF1F0' },
  errorTitle: { color: '#B54708', fontWeight: '700' },
  errorText: { color: '#B42318' },
  chartCard: { marginTop: 8, borderRadius: 10 },
  kpiGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8 },
  kpi: { minWidth: '45%', padding: 8, borderRadius: 8, backgroundColor: '#F8FAFC' },
  sourceLine: { marginVertical: 8, color: '#667085', fontSize: 12 },
  exportRow: { marginTop: 12, gap: 8 },
  exportOverlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)', justifyContent: 'center', padding: 20 },
  exportPreview: { width: '100%', backgroundColor: '#FFFFFF', borderRadius: 12, padding: 16, gap: 8 },
  catalog: { marginTop: 14, gap: 6 },
  catalogHeading: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  catalogRow: { borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: '#E4E7EC', paddingVertical: 6 },
  catalogTitle: { color: '#344054' },
  subjectPicker: { marginTop: 10, gap: 8 },
  chipRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  followUps: { marginTop: 14, gap: 8 },
  loading: { color: '#175CD3', paddingVertical: 8 },
  inputBar: { borderTopWidth: 1, borderTopColor: '#E4E7EC', padding: 12, gap: 8, backgroundColor: '#FFFFFF' },
  input: { maxHeight: 100, backgroundColor: '#FFFFFF' },
});
