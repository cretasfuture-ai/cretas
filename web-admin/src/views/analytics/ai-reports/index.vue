<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { generateAIReport, getAIReport, listAIReports } from '@/api/aiReports';
import type { AIReportDetail, AIReportSummary, OperatingReportType } from '@/api/aiReports';

const auth = useAuthStore();
const permissions = usePermissionStore();
const route = useRoute();
const router = useRouter();
const reports = ref<AIReportSummary[]>([]);
const report = ref<AIReportDetail | null>(null);
const loading = ref(false);
const generating = ref(false);
const detailLoading = ref(false);
const error = ref('');
const detailError = ref('');
const filter = ref('');
const generationType = ref<OperatingReportType>('daily');
const selectedDate = ref(new Date());
const detailOpen = ref(false);
const factoryId = computed(() => auth.factoryId);
const canGenerate = computed(() => auth.factoryType === 'FACTORY' && permissions.canViewPrice);
const body = computed(() => report.value?.reportAnalysis ?? report.value?.analysis ?? '');
let readEpoch = 0;
let detailSequence = 0;
const labels: Record<string, string> = { daily: '日报', weekly: '周报', monthly: '月报', batch: '批次报告', custom: '自定义报告' };
const dateText = (value?: string) => value ? value.replace('T', ' ').slice(0, 19) : '—';
const localDate = (value: Date) => `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`;
const failureText = (failure: unknown) => failure instanceof Error ? failure.message : '报告加载失败，请重试。';
async function load() {
  if (!factoryId.value) { error.value = '请先选择工厂。'; reports.value = []; return; }
  const epoch = readEpoch;
  loading.value = true; error.value = ''; reports.value = [];
  try {
    const response = await listAIReports(factoryId.value, filter.value || undefined);
    if (!response.success || !response.data) throw new Error(response.message || '报告列表读取失败。');
    if (epoch === readEpoch) reports.value = response.data.reports;
  } catch (failure) { if (epoch === readEpoch) error.value = failureText(failure); }
  finally { if (epoch === readEpoch) loading.value = false; }
}
async function openReport(id: number, updateRoute = true) {
  if (!factoryId.value) return;
  const epoch = readEpoch;
  const sequence = ++detailSequence;
  detailOpen.value = true; detailLoading.value = true; report.value = null; detailError.value = '';
  if (updateRoute) void router.push({ name: 'AnalyticsAIReportDetail', params: { reportId: id } });
  try {
    const response = await getAIReport(factoryId.value, id);
    if (!response.success || !response.data) throw new Error(response.message || '报告详情读取失败。');
    if (epoch === readEpoch && sequence === detailSequence) report.value = response.data;
  } catch (failure) { if (epoch === readEpoch && sequence === detailSequence) detailError.value = failureText(failure); }
  finally { if (epoch === readEpoch && sequence === detailSequence) detailLoading.value = false; }
}
async function generate() {
  if (!factoryId.value || !canGenerate.value || generating.value || !selectedDate.value) return;
  const chosen = selectedDate.value;
  let start = new Date(chosen.getFullYear(), chosen.getMonth(), chosen.getDate());
  let end = new Date(start);
  if (generationType.value === 'monthly') {
    start = new Date(chosen.getFullYear(), chosen.getMonth(), 1);
    end = new Date(chosen.getFullYear(), chosen.getMonth() + 1, 0);
  } else if (generationType.value === 'weekly') end.setDate(end.getDate() + 6);
  generating.value = true;
  try {
    const response = await generateAIReport(factoryId.value, generationType.value, localDate(start), localDate(end));
    if (!response.success || !response.data) throw new Error(response.message || '报告生成请求失败。');
    await load();
    if (response.data.generationStatus === 'FAILED') ElMessage.error(response.data.errorMessage || '生成失败，原因已保存在报告历史中。');
    else ElMessage.success('报告已生成。');
    await openReport(response.data.reportId);
  } catch (failure) { ElMessage.error(failureText(failure)); }
  finally { generating.value = false; }
}
function download() {
  if (!body.value || !report.value) return;
  const url = URL.createObjectURL(new Blob([body.value], { type: 'text/markdown;charset=utf-8' }));
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = `经营报告-${report.value.reportId}.md`; anchor.click();
  URL.revokeObjectURL(url);
}
function rowClick(row: AIReportSummary) { void openReport(row.id); }
function closeDetail() {
  if (route.params.reportId) void router.replace({ name: 'AnalyticsAIReports' });
}
watch(filter, () => void load());
watch(() => route.params.reportId, (id) => { if (id && Number.isSafeInteger(Number(id))) void openReport(Number(id), false); });
watch([factoryId, () => auth.currentRole, () => auth.user?.id], () => { readEpoch += 1; detailSequence += 1; report.value = null; reports.value = []; detailOpen.value = false; void load(); });
onMounted(() => { void load(); if (route.params.reportId) void openReport(Number(route.params.reportId), false); });
</script>

<template>
  <section class="report-page">
    <header class="page-heading">
      <div><h1>AI 经营报告</h1><p>按日、周、月查看销售、毛利、应收与存货，保留取数时间和缺失说明。</p></div>
      <el-button :loading="loading" @click="load">刷新报告</el-button>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div class="report-toolbar">
      <el-select v-model="filter" aria-label="报告类型" style="width: 140px">
        <el-option label="全部报告" value="" /><el-option label="日报" value="daily" />
        <el-option label="周报" value="weekly" /><el-option label="月报" value="monthly" />
      </el-select>
      <div v-if="canGenerate" class="generation-controls">
        <el-select v-model="generationType" aria-label="生成报告类型" style="width: 120px">
          <el-option label="生成日报" value="daily" /><el-option label="生成周报" value="weekly" /><el-option label="生成月报" value="monthly" />
        </el-select>
        <el-date-picker v-model="selectedDate" :type="generationType === 'monthly' ? 'month' : 'date'"
          :placeholder="generationType === 'weekly' ? '选择周起始日' : '选择报告期间'" :clearable="false" />
        <el-button type="primary" :loading="generating" @click="generate">生成报告</el-button>
      </div>
    </div>
    <el-table v-loading="loading" :data="reports" empty-text="暂无报告" @row-click="rowClick">
      <el-table-column label="类型" width="100"><template #default="{ row }">{{ labels[row.reportType] || row.reportType }}</template></el-table-column>
      <el-table-column label="报告期间" min-width="210"><template #default="{ row }">{{ row.periodStart || '—' }} 至 {{ row.periodEnd || '—' }}</template></el-table-column>
      <el-table-column label="状态与说明" min-width="320"><template #default="{ row }">
        <el-tag :type="row.generationStatus === 'FAILED' ? 'danger' : 'success'">{{ row.generationStatus === 'FAILED' ? '生成失败' : '已生成' }}</el-tag>
        <p class="summary">{{ row.failureReason || row.financialSummaryText || row.summaryText || '打开查看全文' }}</p>
      </template></el-table-column>
      <el-table-column label="生成时间" width="180"><template #default="{ row }">{{ dateText(row.createdAt) }}</template></el-table-column>
      <el-table-column width="100"><template #default="{ row }"><el-button link type="primary" @click.stop="openReport(row.id)">查看全文</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="detailOpen" title="经营报告全文" width="min(960px, 95vw)" @closed="closeDetail">
      <div v-loading="detailLoading" class="detail-content">
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" show-icon />
        <template v-if="report">
          <p class="detail-time">生成时间：{{ dateText(report.generatedAt) }}</p>
          <el-alert v-if="report.generationStatus === 'FAILED' || !report.success" title="生成失败" :description="report.errorMessage || '报告生成失败，请重新生成。'" type="error" :closable="false" show-icon />
          <pre class="report-body">{{ body }}</pre>
        </template>
      </div>
      <template #footer><el-button v-if="body" @click="download">下载当前可见全文</el-button><el-button @click="detailOpen = false">关闭</el-button></template>
    </el-dialog>
  </section>
</template>
<style scoped>
.report-page { padding: 24px; }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 24px; }
h1 { margin: 0 0 8px; font-size: 24px; color: #172c3e; }
.page-heading p, .detail-time { color: #657786; margin: 0; line-height: 1.6; }
.report-toolbar, .generation-controls { display: flex; align-items: center; gap: 12px; }
.report-toolbar { justify-content: space-between; flex-wrap: wrap; margin: 20px 0; }
.summary { white-space: pre-wrap; max-height: 72px; overflow: hidden; color: #526170; line-height: 1.5; }
.detail-content { min-height: 100px; }
.report-body { white-space: pre-wrap; overflow-wrap: anywhere; font-family: inherit; font-size: 14px; line-height: 1.9; color: #243746; background: #f7f9fa; padding: 20px; border-radius: 8px; }
@media (max-width: 700px) { .page-heading, .generation-controls { align-items: stretch; flex-direction: column; } .report-page { padding: 16px; } }
</style>
