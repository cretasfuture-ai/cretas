<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { ChatDotRound, Download, Search, Refresh } from '@element-plus/icons-vue';
import { useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import PeriodSelector, { type PeriodSelection } from '@/components/smartbi/PeriodSelector.vue';
import { getUploadHistory, type UploadHistoryItem } from '@/api/smartbi/upload';
import type { FactoryChartPeriodType, FactoryChartSource, FactoryRecommendation } from '@/api/smartbi/factory-charts';
import {
  postFactoryAnalysis,
  type FactoryAnalysisCatalogItem,
  type FactoryAnalysisData,
  type FactoryAnalysisFact,
  type FactoryAnalysisPeriodSnapshot,
  type FactoryAnalysisRequest,
} from '@/api/smartbi/factory-analysis';
import { periodSelectionToRequest } from './financialDashboardState';
import FactoryChartCanvas from './FactoryChartCanvas.vue';
import FactoryChartDetails from './FactoryChartDetails.vue';
import { buildFactoryChartExportPayload, captureFactoryChartImages, downloadFactoryChartExport } from './factoryChartExport';

interface ChatTurn {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  status?: FactoryAnalysisData['status'];
  recommendations?: FactoryRecommendation[];
  facts?: FactoryAnalysisFact[];
  limitations?: string[];
  sourceErrors?: FactoryAnalysisData['sourceErrors'];
  followUps?: string[];
  clarification?: boolean;
  clarificationHasSubjects?: boolean;
  loading?: boolean;
  question?: string;
  scopeKey?: string;
  metadata?: {
    source: FactoryChartSource;
    sourceLabel: string;
    periodLabel: string;
    period: FactoryAnalysisPeriodSnapshot;
    scopeLabel?: string;
    selectedIds: string[];
  };
}

interface FactoryRouteScope {
  source: FactoryChartSource;
  uploadId: number | null;
  period: PeriodSelection;
  analysisSubject: string | null;
}

interface AcceptedAnswerScope {
  source: FactoryChartSource;
  uploadId: number | null;
  period: FactoryAnalysisPeriodSnapshot;
  analysisSubject: string | null;
}

function queryValue(query: Record<string, unknown>, key: string): string | undefined {
  const value = query[key];
  const text = Array.isArray(value) ? value[0] : value;
  return typeof text === 'string' && text.trim() ? text.trim() : undefined;
}

function positiveInt(value: string | undefined): number | null {
  if (!value || !/^\d+$/.test(value)) return null;
  const number = Number(value);
  return Number.isSafeInteger(number) && number > 0 ? number : null;
}

function validMonth(value: string | undefined): number | null {
  const number = positiveInt(value);
  return number !== null && number <= 12 ? number : null;
}

/** Parse only the narrow scope written by FactoryChartRecommendations. */
function factoryScopeFromRoute(query: Record<string, unknown>): FactoryRouteScope {
  const routeSource = queryValue(query, 'source');
  const upload = positiveInt(queryValue(query, 'upload_id'));
  const source: FactoryChartSource = routeSource === 'upload' && upload ? 'upload' : 'business';
  const yearValue = positiveInt(queryValue(query, 'year'));
  const year = yearValue && yearValue >= 2000 && yearValue <= 2100 ? yearValue : new Date().getFullYear();
  const periodType = queryValue(query, 'period_type');
  const start = validMonth(queryValue(query, 'start_month'));
  const end = validMonth(queryValue(query, 'end_month'));
  let period: PeriodSelection = {
    type: 'year', year, value: String(year), compareEnabled: false, allHistory: true,
  };
  if (periodType === 'year') {
    period = { type: 'year', year, value: String(year), compareEnabled: false, allHistory: false };
  } else if (periodType === 'month_range' && start && end && start <= end) {
    period = {
      type: 'month_range', year, value: [`${year}-${String(start).padStart(2, '0')}`, `${year}-${String(end).padStart(2, '0')}`],
      compareEnabled: false, allHistory: false,
    };
  }
  const subject = queryValue(query, 'analysis_subject');
  return { source, uploadId: source === 'upload' ? upload : null, period, analysisSubject: subject?.slice(0, 200) || null };
}

const route = useRoute();
const authStore = useAuthStore();
const initialScope = factoryScopeFromRoute(route.query as Record<string, unknown>);
const factoryId = computed(() => authStore.factoryId || '');
const identityKey = computed(() => JSON.stringify([
  factoryId.value,
  authStore.user?.id ?? '',
  authStore.currentRole,
]));

const source = ref<FactoryChartSource>(initialScope.source);
const uploadId = ref<number | null>(initialScope.uploadId);
const uploads = ref<UploadHistoryItem[]>([]);
const uploadsLoading = ref(false);
const uploadError = ref('');
const period = ref<PeriodSelection>(initialScope.period);
const analysisSubject = ref<string | null>(initialScope.analysisSubject);
const subjectOptions = ref<Array<{ id: string; label: string }>>([]);
const question = ref('');
const pending = ref(false);
const notice = ref('');
const error = ref('');
const turns = ref<ChatTurn[]>([]);
const contextId = ref<string | null>(null);
const catalogOpen = ref(false);
const catalogSearch = ref('');
const catalog = ref<FactoryAnalysisCatalogItem[]>([]);
const requestVersion = ref(0);
const uploadLoadVersion = ref(0);
const lastQuestion = ref('');
const retryQuestion = ref('');
const detailsVisible = ref(false);
const detailsCandidate = ref<FactoryRecommendation | null>(null);
const detailsPoint = ref<{ name: string; seriesName?: string; dataIndex?: number }>();
const detailsTurnKey = ref('');
const activeTurnId = ref<string | null>(null);
const exportLoading = ref<'' | 'excel' | 'pdf' | 'ppt'>('');
const lastAcceptedScope = ref<AcceptedAnswerScope | null>(null);
const chartReadyKeys = ref(new Set<string>());
const chartReadyWaiters = new Map<string, Set<() => void>>();
let identityLoadScheduled = false;

const filteredCatalog = computed(() => {
  const needle = catalogSearch.value.trim().toLocaleLowerCase();
  if (!needle) return catalog.value;
  return catalog.value.filter(item => [item.id, item.familyId, item.chartType, item.domain, item.metric, item.unit, item.sourceKey]
    .some(value => String(value || '').toLocaleLowerCase().includes(needle)));
});
const sourceLabel = computed(() => source.value === 'business' ? '业务系统' : '上传报表');
/**
 * The server request is scoped by more than the data source. Keep the exact
 * period request and product subject in the captured key so a first request
 * cannot paint after the user changes the filter while it is in flight.
 */
const activeScopeKey = computed(() => {
  const selected = periodSelectionToRequest(period.value);
  const periodScope = 'error' in selected
    ? { type: period.value.type, year: period.value.year, value: period.value.value }
    : selected;
  return JSON.stringify({
    factoryId: factoryId.value,
    source: source.value,
    uploadId: uploadId.value ?? null,
    period: periodScope,
    analysisSubject: analysisSubject.value || null,
  });
});
const turnScopeKey = computed(() => JSON.stringify([identityKey.value, activeScopeKey.value]));
const detailsRenderKey = computed(() => `${identityKey.value}:${activeScopeKey.value}:${detailsTurnKey.value}`);

function scheduleIdentityUploadLoad() {
  if (identityLoadScheduled) return;
  identityLoadScheduled = true;
  void Promise.resolve().then(() => {
    identityLoadScheduled = false;
    if (factoryId.value) void loadUploads();
  });
}

function id(): string {
  return globalThis.crypto?.randomUUID?.() || `factory-analysis-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function requestStatus(value: unknown): number | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const record = value as { status?: unknown; response?: { status?: unknown } };
  return typeof record.status === 'number' ? record.status : typeof record.response?.status === 'number' ? record.response.status : undefined;
}

function errorMessage(value: unknown, fallback: string): string {
  if (value instanceof Error && value.message) return value.message;
  if (value && typeof value === 'object' && typeof (value as { serverMessage?: unknown }).serverMessage === 'string') {
    return String((value as { serverMessage: string }).serverMessage);
  }
  return fallback;
}

function validPeriodSnapshot(value: unknown): value is FactoryAnalysisPeriodSnapshot {
  if (!value || typeof value !== 'object') return false;
  const period = value as Partial<FactoryAnalysisPeriodSnapshot>;
  return typeof period.label === 'string' && period.label.trim().length > 0
    && Number.isSafeInteger(period.year) && period.year >= 2000 && period.year <= 2100
    && (period.periodType === 'all' || period.periodType === 'year' || period.periodType === 'month_range')
    && Number.isInteger(period.startMonth) && period.startMonth >= 1 && period.startMonth <= 12
    && Number.isInteger(period.endMonth) && period.endMonth >= period.startMonth && period.endMonth <= 12;
}

function chartReadyKey(turn: ChatTurn, item: FactoryRecommendation): string {
  return `${turn.id}:${item.id}`;
}

function clearChartReadiness() {
  chartReadyKeys.value = new Set();
  const callbacks = [...chartReadyWaiters.values()].flatMap(waiters => [...waiters]);
  chartReadyWaiters.clear();
  callbacks.forEach(callback => callback());
}

function markChartReady(turn: ChatTurn, item: FactoryRecommendation) {
  // The active turn identity is the component-generation guard. Export still
  // validates the captured scope/identity/request epoch before consuming it.
  if (turn.id !== activeTurnId.value) return;
  const key = chartReadyKey(turn, item);
  chartReadyKeys.value = new Set(chartReadyKeys.value).add(key);
  chartReadyWaiters.get(key)?.forEach(callback => callback());
}

function waitForChartsReady(turn: ChatTurn, items: readonly FactoryRecommendation[], valid: () => boolean): Promise<boolean> {
  const missing = items.map(item => chartReadyKey(turn, item)).filter(key => !chartReadyKeys.value.has(key));
  if (!missing.length) return Promise.resolve(valid());
  return new Promise(resolve => {
    let settled = false;
    const finish = (value: boolean) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      missing.forEach(key => chartReadyWaiters.get(key)?.delete(check));
      resolve(value);
    };
    const check = () => {
      if (!valid()) finish(false);
      else if (missing.every(key => chartReadyKeys.value.has(key))) finish(true);
    };
    const timeout = globalThis.setTimeout(() => finish(false), 15_000);
    missing.forEach(key => {
      const waiters = chartReadyWaiters.get(key) || new Set<() => void>();
      waiters.add(check);
      chartReadyWaiters.set(key, waiters);
    });
    check();
  });
}

function resetConversation(message = '', options: { preserveAcceptedScope?: boolean } = {}) {
  requestVersion.value += 1;
  clearChartReadiness();
  if (!options.preserveAcceptedScope) lastAcceptedScope.value = null;
  contextId.value = null;
  catalog.value = [];
  catalogOpen.value = false;
  turns.value = [];
  retryQuestion.value = '';
  lastQuestion.value = '';
  notice.value = message;
  error.value = '';
  pending.value = false;
  activeTurnId.value = null;
  exportLoading.value = '';
  detailsVisible.value = false;
  detailsCandidate.value = null;
  detailsPoint.value = undefined;
  detailsTurnKey.value = '';
}

async function loadUploads() {
  const capturedFactory = factoryId.value;
  const capturedIdentity = identityKey.value;
  const version = ++uploadLoadVersion.value;
  if (!capturedFactory) return;
  uploadsLoading.value = true;
  uploadError.value = '';
  try {
    const result = await getUploadHistory();
    if (version !== uploadLoadVersion.value || capturedFactory !== factoryId.value || capturedIdentity !== identityKey.value) return;
    if (!result.success) throw new Error('上传报表列表加载失败');
    uploads.value = result.data;
    if (source.value === 'upload' && !uploadId.value) uploadId.value = uploads.value[0]?.id ?? null;
  } catch (err) {
    if (version === uploadLoadVersion.value && capturedFactory === factoryId.value && capturedIdentity === identityKey.value) uploadError.value = errorMessage(err, '上传报表列表加载失败');
  } finally {
    if (version === uploadLoadVersion.value && capturedFactory === factoryId.value && capturedIdentity === identityKey.value) uploadsLoading.value = false;
  }
}

function onSourceChange(value: FactoryChartSource) {
  source.value = value;
  if (value === 'business') uploadId.value = null;
  else if (!uploadId.value) uploadId.value = uploads.value[0]?.id ?? null;
  analysisSubject.value = null;
  subjectOptions.value = [];
}

function periodPayload(): Pick<FactoryAnalysisRequest, 'year' | 'periodType' | 'startMonth' | 'endMonth'> | null {
  const selected = periodSelectionToRequest(period.value);
  if ('error' in selected) {
    error.value = selected.error;
    return null;
  }
  return {
    year: selected.year,
    periodType: selected.periodType as FactoryChartPeriodType,
    startMonth: selected.startMonth,
    endMonth: selected.endMonth,
  };
}

function periodChanged(value: PeriodSelection) {
  period.value = value;
  detailsVisible.value = false;
  resetConversation('期间已切换，请重新提问。');
}

function onSubjectChange(value: string | null | undefined) {
  analysisSubject.value = value || null;
  detailsVisible.value = false;
  resetConversation('产品范围已切换，请重新提问。');
}

function subjectLabel(subjectId: string | null | undefined): string {
  return subjectOptions.value.find(item => item.id === subjectId)?.label || subjectId || '全厂范围';
}

function openDetails(item: FactoryRecommendation, point?: { name?: string; seriesName?: string; dataIndex?: number }, turn?: ChatTurn) {
  if (!turn?.metadata || !validPeriodSnapshot(turn.metadata.period)) {
    error.value = '当前回答缺少有效期间，无法读取原始明细。';
    return;
  }
  detailsCandidate.value = item;
  detailsPoint.value = point?.name ? { name: point.name, seriesName: point.seriesName, dataIndex: point.dataIndex } : undefined;
  detailsTurnKey.value = turn?.scopeKey || turn?.id || '';
  detailsVisible.value = true;
}

function sourceErrorText(item: FactoryAnalysisData['sourceErrors'][number]): string {
  const value = item as { message?: unknown; error?: unknown };
  const detail = typeof value.message === 'string' && value.message.trim()
    ? value.message
    : typeof value.error === 'string' && value.error.trim() ? value.error : '读取失败';
  return `${item.source || '数据源'}：${detail}`;
}

async function sendQuestion(
  rawQuestion = question.value,
  explicitSubject = analysisSubject.value,
  forceNew = false,
  scopeOverride?: AcceptedAnswerScope,
) {
  const text = rawQuestion.trim();
  if (!text || pending.value) return;
  if (!factoryId.value) {
    error.value = '当前账号没有所属工厂，无法进行工厂分析';
    return;
  }
  const requestSource = scopeOverride?.source || source.value;
  const requestUploadId = scopeOverride?.uploadId ?? uploadId.value;
  if (requestSource === 'upload' && !requestUploadId) {
    error.value = '请选择一份已上传的报表';
    return;
  }
  const periodRequest = scopeOverride
    ? {
      year: scopeOverride.period.year,
      periodType: scopeOverride.period.periodType,
      startMonth: scopeOverride.period.startMonth,
      endMonth: scopeOverride.period.endMonth,
    }
    : periodPayload();
  if (!periodRequest) return;
  const capturedIdentity = identityKey.value;
  const capturedScope = activeScopeKey.value;
  const version = ++requestVersion.value;
  activeTurnId.value = null;
  clearChartReadiness();
  detailsVisible.value = false;
  detailsCandidate.value = null;
  detailsPoint.value = undefined;
  detailsTurnKey.value = '';
  const isFollowup = Boolean(contextId.value) && !forceNew;
  const userTurn: ChatTurn = { id: id(), role: 'user', text, question: text };
  const assistantTurn: ChatTurn = { id: id(), role: 'assistant', text: '正在读取当前授权数据并规划图表…', loading: true, question: text };
  turns.value.push(userTurn, assistantTurn);
  question.value = '';
  pending.value = true;
  error.value = '';
  notice.value = '';
  lastQuestion.value = text;
  retryQuestion.value = '';
  const payload: FactoryAnalysisRequest = isFollowup
    ? { question: text, contextId: contextId.value || undefined }
    : {
      question: text,
      source: requestSource,
      ...(requestSource === 'upload' && requestUploadId ? { uploadId: requestUploadId } : {}),
      ...periodRequest,
      analysisSubject: explicitSubject,
    };
  try {
    const result = await postFactoryAnalysis(factoryId.value, payload);
    if (version !== requestVersion.value || capturedIdentity !== identityKey.value || capturedScope !== activeScopeKey.value) return;
    if (!result.success || !result.data) throw new Error(result.message || '工厂分析未返回有效结果');
    const data = result.data;
    if (!validPeriodSnapshot(data.period)) {
      throw new Error('工厂分析响应缺少有效期间，已拒绝该结果，请重新提问。');
    }
    const acceptedSubject = data.analysisSubject || (isFollowup
      ? lastAcceptedScope.value?.analysisSubject || null
      : explicitSubject || null);
    lastAcceptedScope.value = {
      source: data.source,
      uploadId: data.source === 'upload' ? requestUploadId : null,
      period: data.period,
      analysisSubject: acceptedSubject,
    };
    // The server may rotate the opaque token on every response; always retain
    // the latest one and never construct one in the browser.
    contextId.value = data.contextId || null;
    catalog.value = data.catalog || [];
    subjectOptions.value = data.subjects || subjectOptions.value;
    if (data.analysisSubject) analysisSubject.value = data.analysisSubject;
    assistantTurn.text = data.answer || result.message || '当前分析没有可显示的结论。';
    assistantTurn.status = data.status;
    assistantTurn.recommendations = data.status === 'answered' ? (data.recommendations || []) : [];
    assistantTurn.facts = data.facts || [];
    assistantTurn.limitations = data.limitations || [];
    assistantTurn.sourceErrors = data.sourceErrors || [];
    assistantTurn.followUps = data.followUpQuestions || [];
    assistantTurn.clarification = data.status === 'clarification';
    assistantTurn.clarificationHasSubjects = assistantTurn.clarification && data.subjects.length > 0;
    assistantTurn.loading = false;
    assistantTurn.scopeKey = turnScopeKey.value;
    assistantTurn.metadata = {
      source: data.source,
      sourceLabel: data.sourceLabel || sourceLabel.value,
      periodLabel: data.period.label,
      period: data.period,
      scopeLabel: data.analysisScopeLabel,
      selectedIds: [...(data.selectedIds || [])],
    };
    activeTurnId.value = data.status === 'answered' && assistantTurn.recommendations.length ? assistantTurn.id : null;
    if (data.status === 'unavailable') notice.value = 'AI 规划不可用；未展示未经验证的图表或事实。';
  } catch (err) {
    if (version !== requestVersion.value || capturedIdentity !== identityKey.value || capturedScope !== activeScopeKey.value) return;
    assistantTurn.loading = false;
    const status = requestStatus(err);
    if (status === 410) {
      contextId.value = null;
      retryQuestion.value = text;
      assistantTurn.text = '这段分析上下文已过期，未恢复旧答案。请点击“重新发送”后重新读取当前数据。';
      notice.value = '上下文已过期，需要显式重新发送问题。';
    } else {
      assistantTurn.text = errorMessage(err, '工厂分析暂时不可用，请稍后重试。');
      error.value = assistantTurn.text;
      retryQuestion.value = text;
    }
  } finally {
    if (version === requestVersion.value && capturedIdentity === identityKey.value) pending.value = false;
  }
}

function sendFollowUp(text: string) {
  question.value = text;
  void sendQuestion(text);
}

function sendClarification(text: string) {
  // A product clarification starts a fresh trusted read. Follow-ups remain
  // the fixed {question, contextId} contract.
  contextId.value = null;
  void sendQuestion(text, analysisSubject.value, true);
}

function retryExpired() {
  const text = retryQuestion.value;
  if (!text) return;
  const acceptedScope = lastAcceptedScope.value;
  resetConversation('正在重新读取当前授权数据…', { preserveAcceptedScope: true });
  void sendQuestion(text, acceptedScope?.analysisSubject ?? analysisSubject.value, true, acceptedScope || undefined);
}

async function exportAnswer(turn: ChatTurn, format: 'excel' | 'pdf' | 'ppt') {
  const items = turn.recommendations || [];
  if (!items.length || exportLoading.value) return;
  const capturedIdentity = identityKey.value;
  const capturedScope = turn.scopeKey;
  const capturedVersion = requestVersion.value;
  if (!capturedScope || capturedScope !== turnScopeKey.value) return;
  activeTurnId.value = turn.id;
  await nextTick();
  if (capturedVersion !== requestVersion.value || capturedIdentity !== identityKey.value || capturedScope !== turnScopeKey.value) return;
  const exportScopeValid = () => capturedVersion === requestVersion.value
    && capturedIdentity === identityKey.value
    && capturedScope === turnScopeKey.value;
  if (!await waitForChartsReady(turn, items, exportScopeValid) || !exportScopeValid()) {
    if (exportScopeValid()) error.value = '图表仍在绘制，暂时无法导出，请稍后重试。';
    return;
  }
  exportLoading.value = format;
  try {
    const images = await captureFactoryChartImages(items);
    if (capturedVersion !== requestVersion.value || capturedIdentity !== identityKey.value || capturedScope !== turnScopeKey.value) return;
    const metadata = turn.metadata;
    if (!metadata || !validPeriodSnapshot(metadata.period)) {
      error.value = '当前回答缺少有效期间，无法导出。';
      return;
    }
    const payload = buildFactoryChartExportPayload(items, images, {
      factoryId: factoryId.value,
      source: metadata.source,
      sourceLabel: metadata.sourceLabel,
      scopeLabel: metadata.scopeLabel,
      periodLabel: metadata.periodLabel,
      year: metadata.period.year,
      periodType: metadata.period.periodType,
      startMonth: metadata.period.startMonth,
      endMonth: metadata.period.endMonth,
      selectedIds: metadata.selectedIds.length ? metadata.selectedIds : items.map(item => item.id),
    });
    const extension = format === 'excel' ? 'xlsx' : format === 'pdf' ? 'pdf' : 'pptx';
    if (capturedVersion !== requestVersion.value || capturedIdentity !== identityKey.value || capturedScope !== turnScopeKey.value) return;
    await downloadFactoryChartExport(format, payload, `工厂AI分析_${new Date().toISOString().slice(0, 10)}.${extension}`);
  } catch (err) {
    ElMessage.error(errorMessage(err, '导出失败，请稍后重试'));
  } finally {
    exportLoading.value = '';
  }
}

watch(identityKey, () => {
  uploadLoadVersion.value += 1;
  uploads.value = [];
  uploadId.value = null;
  uploadError.value = '';
  resetConversation('登录身份或工厂已变化，已清除旧分析上下文。');
  subjectOptions.value = [];
  analysisSubject.value = null;
  scheduleIdentityUploadLoad();
}, { flush: 'sync' });
watch([source, uploadId], () => {
  detailsVisible.value = false;
  resetConversation('数据源已变化，请重新提问。');
}, { flush: 'sync' });
loadUploads();
onBeforeUnmount(() => { requestVersion.value += 1; clearChartReadiness(); });
</script>

<template>
  <section class="factory-analysis-chat" data-testid="factory-analysis-chat">
    <header class="factory-analysis-chat__header">
      <div>
        <h2><el-icon><ChatDotRound /></el-icon> 工厂 AI 问答分析</h2>
        <p>自由提问，AI 只从当前授权图表目录选择真实分析；数值、单位和来源来自业务数据。</p>
      </div>
      <el-button :icon="Refresh" :disabled="pending" @click="resetConversation('已开启新分析上下文。')">新分析</el-button>
    </header>

    <el-card shadow="never" class="factory-analysis-chat__scope">
      <div class="scope-row">
        <label>数据源</label>
        <el-select :model-value="source" data-testid="factory-analysis-source" @update:model-value="onSourceChange">
          <el-option label="业务系统" value="business" />
          <el-option label="上传报表" value="upload" />
        </el-select>
        <template v-if="source === 'upload'">
          <label>报表</label>
          <el-select v-model="uploadId" filterable :loading="uploadsLoading" placeholder="选择已上传报表" data-testid="factory-analysis-upload">
            <el-option v-for="item in uploads" :key="item.id" :label="item.originalFileName || item.fileName || `上传 #${item.id}`" :value="item.id" />
          </el-select>
          <span v-if="uploadError" class="scope-error">{{ uploadError }}</span>
        </template>
      </div>
      <div class="scope-row">
        <label>期间</label>
        <PeriodSelector v-model="period" :show-all-history="true" :show-compare="false" data-testid="factory-analysis-period" @change="periodChanged" />
        <label>产品</label>
        <el-select-v2 :model-value="analysisSubject" :options="subjectOptions.map(item => ({ label: item.label, value: item.id }))" filterable clearable placeholder="全部产品 / 全厂范围" data-testid="factory-analysis-subject" @update:model-value="onSubjectChange" />
      </div>
      <div class="scope-hint">{{ factoryId ? `当前工厂：${factoryId} · ${subjectLabel(analysisSubject)}` : '当前账号没有所属工厂' }}</div>
    </el-card>

    <el-alert v-if="notice" type="warning" show-icon :closable="false" class="state-alert">{{ notice }}</el-alert>
    <el-alert v-if="error" type="error" show-icon :closable="false" class="state-alert">{{ error }}</el-alert>

    <div class="turns" data-testid="factory-analysis-turns">
      <article v-for="turn in turns" :key="turn.id" class="turn" :class="`turn--${turn.role}`">
        <div class="turn__label">{{ turn.role === 'user' ? '我' : 'AI 工厂分析' }}</div>
        <div v-if="turn.metadata" class="turn__scope" data-testid="factory-analysis-turn-scope">工厂{{ factoryId }} · {{ turn.metadata.sourceLabel }} · {{ turn.metadata.periodLabel }} · {{ turn.metadata.scopeLabel || '全厂范围' }}</div>
        <div class="turn__text" :data-testid="turn.role === 'assistant' ? 'factory-analysis-answer' : 'factory-analysis-question'">{{ turn.text }}</div>
        <el-skeleton v-if="turn.loading" :rows="2" animated />
        <template v-if="turn.role === 'assistant' && !turn.loading">
          <div v-if="turn.clarification && turn.clarificationHasSubjects" class="clarification-note">请从上方可搜索的产品目录中明确分析对象，再点击“按当前产品重试”。</div>
          <div v-else-if="turn.clarification" class="clarification-note">请根据上方 AI 澄清提示输入有效期间后重新提问。</div>
          <el-button v-if="turn.clarification && turn.clarificationHasSubjects && analysisSubject" size="small" type="primary" @click="sendClarification(turn.question || lastQuestion)">按当前产品重试</el-button>
          <div v-if="turn.facts?.length" class="facts" data-testid="factory-analysis-facts">
            <div v-for="fact in turn.facts" :key="fact.id" class="fact"><span>{{ fact.label }}</span><strong>{{ fact.value ?? '—' }} {{ fact.unit }}</strong><small>{{ fact.source }}</small></div>
          </div>
          <div v-if="turn.limitations?.length" class="limitations" data-testid="factory-analysis-limitations"><strong>限制与口径</strong><ul><li v-for="item in turn.limitations" :key="item">{{ item }}</li></ul></div>
          <el-alert v-if="turn.sourceErrors?.length" type="warning" :closable="false" show-icon data-testid="factory-analysis-source-errors"><template #title>部分数据源读取失败</template><ul><li v-for="item in turn.sourceErrors" :key="`${item.source}-${sourceErrorText(item)}`">{{ sourceErrorText(item) }}</li></ul></el-alert>
          <div v-if="turn.recommendations?.length" class="answer-tools"><span>本次显示 {{ turn.recommendations.length }} 张真实图表</span><el-button size="small" :loading="exportLoading === 'excel'" :icon="Download" @click="exportAnswer(turn, 'excel')">Excel</el-button><el-button size="small" :loading="exportLoading === 'pdf'" @click="exportAnswer(turn, 'pdf')">PDF</el-button><el-button size="small" :loading="exportLoading === 'ppt'" @click="exportAnswer(turn, 'ppt')">PPT</el-button></div>
          <div v-if="turn.id === activeTurnId && turn.recommendations?.length" class="answer-charts">
            <div v-for="item in turn.recommendations" :key="item.id" class="answer-chart" :data-testid="`factory-chart-card-${item.id}`">
              <div class="answer-chart__heading"><strong>{{ item.title }}</strong><span>{{ item.metric }} · {{ item.unit }} · {{ item.chart.chartType }}</span></div>
              <FactoryChartCanvas :option="item.chart.echartsOption" @ready="markChartReady(turn, item)" @click="openDetails(item, $event, turn)" />
              <el-button v-if="item.detailContext" link type="primary" @click="openDetails(item, undefined, turn)">查看原始明细</el-button>
            </div>
          </div>
          <div v-if="turn.followUps?.length" class="followups"><el-button v-for="followUp in turn.followUps" :key="followUp" size="small" plain @click="sendFollowUp(followUp)">{{ followUp }}</el-button></div>
          <el-button v-if="retryQuestion && turn === turns[turns.length - 1]" size="small" type="primary" @click="retryExpired">重新发送</el-button>
        </template>
      </article>
    </div>

    <el-card shadow="never" class="catalog-card">
      <div class="catalog-header"><strong>当前授权图表目录</strong><span>{{ catalog.length }} 类候选（回答最多 4 个不同分析族）</span><el-button text @click="catalogOpen = !catalogOpen">{{ catalogOpen ? '收起目录' : '展开目录' }}</el-button></div>
      <div v-if="catalogOpen"><el-input v-model="catalogSearch" clearable :prefix-icon="Search" placeholder="搜索分析主题、指标、单位或画法" aria-label="搜索授权图表目录" /><div class="catalog-list"><div v-for="item in filteredCatalog" :key="item.id" class="catalog-item"><strong>{{ item.metric || item.id }}</strong><span>{{ item.domain }} · {{ item.chartType }} · {{ item.unit || '单位未提供' }}</span><small>{{ item.familyId }}</small></div><el-empty v-if="!filteredCatalog.length" description="没有匹配的授权候选" :image-size="50" /></div></div>
    </el-card>

    <form class="composer" @submit.prevent="sendQuestion()">
      <el-input v-model="question" type="textarea" :rows="3" maxlength="1000" show-word-limit :disabled="pending" placeholder="例如：最近生产和质量表现有什么值得关注的变化？" data-testid="factory-analysis-input" />
      <div class="composer__footer"><span>上下文：{{ contextId ? '已建立（仅限当前工厂、用户、角色和数据源）' : '新问题' }}</span><el-button type="primary" native-type="submit" :loading="pending" data-testid="factory-analysis-submit">发送问题</el-button></div>
    </form>
    <FactoryChartDetails v-if="detailsVisible" :key="detailsRenderKey" v-model="detailsVisible" :candidate="detailsCandidate" :point="detailsPoint" />
  </section>
</template>

<style scoped lang="scss">
.factory-analysis-chat { padding: 8px 0 28px; }
.factory-analysis-chat__header { display:flex; justify-content:space-between; gap:16px; align-items:flex-start; margin-bottom:16px; }
.factory-analysis-chat__header h2 { display:flex; gap:8px; align-items:center; margin:0; font-size:20px; }
.factory-analysis-chat__header p { margin:6px 0 0; color:var(--el-text-color-secondary); font-size:13px; }
.factory-analysis-chat__scope, .catalog-card { margin-bottom:16px; border-radius:10px; }
.scope-row { display:flex; align-items:center; flex-wrap:wrap; gap:8px 12px; margin-bottom:12px; }
.scope-row label { color:var(--el-text-color-secondary); font-size:13px; white-space:nowrap; }
.scope-row .el-select { width:190px; }
.scope-row :deep(.period-selector) { flex:1; min-width:300px; }
.scope-hint, .scope-error { color:var(--el-text-color-secondary); font-size:12px; }
.scope-error { color:var(--el-color-warning); }
.state-alert { margin-bottom:12px; }
.turns { display:flex; flex-direction:column; gap:12px; margin-bottom:16px; }
.turn { padding:14px 16px; border:1px solid var(--el-border-color-lighter); border-radius:10px; background:var(--el-bg-color); }
.turn--user { background:var(--el-fill-color-extra-light); }
.turn__label { margin-bottom:6px; color:var(--el-text-color-secondary); font-size:12px; font-weight:600; }
.turn__text { white-space:pre-wrap; line-height:1.65; }
.clarification-note { margin-top:8px; color:var(--el-color-warning); font-size:13px; }
.facts { display:grid; grid-template-columns:repeat(auto-fit,minmax(180px,1fr)); gap:8px; margin-top:12px; }
.fact { display:flex; flex-direction:column; gap:3px; padding:9px; border-radius:6px; background:var(--el-fill-color-light); }
.fact strong { font-size:16px; }.fact small { color:var(--el-text-color-secondary); }
.limitations { margin-top:12px; padding:9px 12px; border-left:3px solid var(--el-color-warning); background:var(--el-color-warning-light-9); font-size:12px; }
.limitations ul, .turn :deep(.el-alert ul) { margin:5px 0 0; padding-left:18px; }
.answer-tools { display:flex; align-items:center; flex-wrap:wrap; gap:8px; margin:12px 0; color:var(--el-text-color-secondary); font-size:12px; }
.answer-charts { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; margin-top:10px; }
.answer-chart { padding:10px; border:1px solid var(--el-border-color-lighter); border-radius:8px; }
.answer-chart__heading { display:flex; justify-content:space-between; gap:8px; margin-bottom:6px; }.answer-chart__heading span { color:var(--el-text-color-secondary); font-size:12px; }
.followups { display:flex; flex-wrap:wrap; gap:8px; margin-top:12px; }
.catalog-header { display:flex; align-items:center; flex-wrap:wrap; gap:10px; }.catalog-header span { color:var(--el-text-color-secondary); font-size:12px; }.catalog-header .el-button { margin-left:auto; }
.catalog-list { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:6px; margin-top:10px; max-height:300px; overflow:auto; }
.catalog-item { display:flex; flex-direction:column; gap:2px; padding:8px; border:1px solid var(--el-border-color-lighter); border-radius:6px; font-size:12px; }.catalog-item span,.catalog-item small { color:var(--el-text-color-secondary); }
.composer { padding:12px; border:1px solid var(--el-border-color); border-radius:10px; background:var(--el-bg-color); }.composer__footer { display:flex; justify-content:space-between; align-items:center; gap:12px; margin-top:8px; color:var(--el-text-color-secondary); font-size:12px; }
@media (max-width:800px) { .answer-charts,.catalog-list { grid-template-columns:1fr; }.factory-analysis-chat__header { flex-direction:column; }.scope-row :deep(.period-selector) { min-width:100%; }.scope-row .el-select { flex:1; min-width:180px; } }
</style>
