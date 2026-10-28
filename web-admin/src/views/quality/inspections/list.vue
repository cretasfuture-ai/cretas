<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Plus, Search, Refresh } from '@element-plus/icons-vue';
import CanvasDynamicFields from '@/components/canvas/CanvasDynamicFields.vue';
import CanvasAwareWrapper from '@/components/canvas/CanvasAwareWrapper.vue';
import type { TableRow } from '@/types/api';
import { TableFooter } from '@/components/list';
import { useListSummary } from '@/composables/useListSummary';
import { formatSummaryForAI } from '@/utils/aiSummaryContext';
import type { ListSummaryRequest } from '@/types/listSummary';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';
import DynamicQualityInspectionForm from './DynamicQualityInspectionForm.vue';
import { hasDynamicItems, type DynamicQualityForm, type DynamicQualitySubmitResponse } from './dynamicQualityInspection';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('quality'));
const { goCreate } = useCreateAndReturn();
const route = useRoute();
const router = useRouter();
const scopedBatchId = ref('');
const scopedBatch = ref<TableRow | null>(null);
const contextError = ref('');
const contextLoading = ref(false);
const hasBatchContext = computed(() => route.query.batchId != null);

function returnToTrace() {
  const batchNumber = scopedBatch.value?.batchNumber;
  if (typeof batchNumber === 'string') {
    void router.push({ path: '/production/traceability', query: { batchNumber } });
  }
}

const loading = ref(false);
const submitting = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });

const searchKeyword = ref('');
const filterResult = ref('');

// U-FOOTER-1
// KPI-vs-list drift fix (2026-07): filterResult MUST be declared BEFORE this
// computed (TDZ — see sales/shipments/list.vue Issue #716). Previously this
// was a static `{ filterConditions: {} }` so the footer "共"/"合格"/"不合格"
// always showed the ALL-result totals while loadData() sends
// `result: filterResult.value` to /processing/quality/inspections — footer
// and paginator would diverge as soon as a result filter was applied.
// NOTE: key is "result" (not "status") — matches computeQualityInspectionSummary,
// which reads filter.get("result") since this entity has no generic status field.
const summaryRequest = computed<ListSummaryRequest>(() => ({
  filterConditions: {
    ...(filterResult.value ? { result: filterResult.value } : {}),
  },
}));
const { summary: footerSummary, loading: footerLoading } = useListSummary('qualityInspection', summaryRequest);

// 新建对话框
const dialogVisible = ref(false);
const dynamicForm = ref<DynamicQualityForm | null>(null);
const dynamicFormLoading = ref(false);
const dynamicFormError = ref('');
let dynamicRequest = 0;
const dynamicDialogMode = computed(() => dynamicFormLoading.value || !!dynamicFormError.value || hasDynamicItems(dynamicForm.value));
const dialogForm = ref({
  batchId: '',
  sampleSize: null as number | null,
  passCount: null as number | null,
  failCount: null as number | null,
  result: '' as string,
  notes: '',
  customFields: {} as TableRow,
});

// 已填写的抽样数是总数；编辑合格/不合格只平衡另一结果，不反改抽样数。
// 尚未填抽样数时仍支持由两种结果计算总数；超量保留原输入并由提交校验拦截。

function onSampleChange(v: number | null | undefined) {
  if (v == null) return;
  const sample = Number(v);
  if (!isFinite(sample) || sample < 0) return;
  const pass = dialogForm.value.passCount;
  const fail = dialogForm.value.failCount;
  if (pass != null) {
    dialogForm.value.failCount = Math.max(0, sample - Number(pass));
  } else if (fail != null) {
    dialogForm.value.passCount = Math.max(0, sample - Number(fail));
  }
}

function onPassChange(v: number | null | undefined) {
  if (v == null) return;
  const pass = Number(v);
  if (!isFinite(pass) || pass < 0) return;
  const sample = dialogForm.value.sampleSize;
  const fail = dialogForm.value.failCount;
  if (sample != null) {
    dialogForm.value.failCount = Math.max(0, Number(sample) - pass);
  } else if (sample == null && fail != null) {
    dialogForm.value.sampleSize = pass + Number(fail);
  }
}

function onFailChange(v: number | null | undefined) {
  if (v == null) return;
  const fail = Number(v);
  if (!isFinite(fail) || fail < 0) return;
  const sample = dialogForm.value.sampleSize;
  const pass = dialogForm.value.passCount;
  if (sample != null) {
    dialogForm.value.passCount = Math.max(0, Number(sample) - fail);
  } else if (sample == null && pass != null) {
    dialogForm.value.sampleSize = Number(pass) + fail;
  }
}

// UX1 (2026-07-05 F006 现场确认): el-input-number 底层 <input> 聚焦时不会全选已有内容 —
// 用户点回一个已有数值的字段重新输入(如把 "10" 改成别的数)时, 新数字会插入光标处而非替换,
// 两次 "10" 会拼成 DOM 文本 "1010", 失焦后按该文本解析成 1010 (而非用户预期的 10),
// 触发"剩余 1000 件未分类"这类困惑报错(依据本仓 fool-proof-design.md: 仓管/质检员操作
// 不应依赖用户自己先清空再输入)。修复: 聚焦时 select() 全选原值, 使任何后续输入直接替换。
function selectAllOnFocus(e: FocusEvent) {
  (e.target as HTMLInputElement | null)?.select();
}

// 派生显示: 是否一致 + 提示文案
// Issue #825 fix (2026-05-17): under-balance (sum<sample) was previously ok:true
// with informational "剩余 N 件" message — user could still submit incomplete
// inspections, leaving data integrity gap (实际抽样数 != 报告的抽样数).
// Now blocks submit with sticky-style toast prompting user to fill remaining
// or reduce sampleSize. Matches over-balance (sum>sample) behavior introduced
// in #745. Customer expects 强制平衡 (May 7 data-integrity discussion spirit).
const balanceStatus = computed<{ ok: boolean; msg: string }>(() => {
  const s = dialogForm.value.sampleSize;
  const p = dialogForm.value.passCount;
  const f = dialogForm.value.failCount;
  if (s == null || p == null || f == null) {
    return { ok: true, msg: '填入任意两个,第三个自动计算' };
  }
  const sum = Number(p) + Number(f);
  if (sum > Number(s)) {
    return { ok: false, msg: `合格 ${p} + 不合格 ${f} = ${sum} > 抽样 ${s} (请调整)` };
  }
  if (sum < Number(s)) {
    return { ok: false, msg: `请填满合格+不合格数 (剩余 ${Number(s) - sum} 件未分类)` };
  }
  return { ok: true, msg: '数字一致' };
});

// 生产批次列表（用于下拉选择）
const productionBatches = ref<TableRow[]>([]);

async function loadProductionBatches() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/processing/batches`, { params: { size: 200 } });
    if (res.success && res.data) {
      productionBatches.value = res.data.content || res.data || [];
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '生产批次读取失败，请重试');
  }
}

// 详情抽屉
const detailVisible = ref(false);
const detailData = ref<TableRow | null>(null);

function isPassResult(result: unknown): boolean {
  return result === 'PASS' || result === 'PASSED';
}

function getResultLabel(result: unknown): string {
  if (isPassResult(result)) return '合格';
  if (result === 'CONDITIONAL') return '有条件放行';
  if (result === 'PENDING') return '待检';
  if (result === 'FAIL' || result === 'FAILED') return '不合格';
  return String(result ?? '-');
}

let contextRequest = 0;
watch(() => [route.query.batchId, factoryId.value], async () => {
  const requestId = ++contextRequest;
  scopedBatch.value = null;
  scopedBatchId.value = '';
  contextLoading.value = false;
  contextError.value = '';
  tableData.value = [];
  pagination.value.total = 0;
  pagination.value.page = 1;
  searchKeyword.value = '';
  filterResult.value = '';
  dialogVisible.value = false;
  invalidateDynamicRequest();
  if (!hasBatchContext.value) {
    void loadData();
    return;
  }
  const id = route.query.batchId;
  if (typeof id !== 'string' || !/^[1-9]\d*$/.test(id) || !factoryId.value) {
    contextError.value = '批次信息无效，请从批次追溯页面重新进入';
    return;
  }
  contextLoading.value = true;
  try {
    const response = await get<TableRow>(`/${factoryId.value}/processing/batches/${encodeURIComponent(id)}`);
    if (requestId !== contextRequest) return;
    if (!response.success || !response.data || String(response.data.id) !== id) {
      throw new Error(response.message || '未找到当前工厂的生产批次');
    }
    scopedBatchId.value = id;
    scopedBatch.value = response.data;
    productionBatches.value = [response.data];
    await loadData();
  } catch (error) {
    if (requestId === contextRequest) contextError.value = error instanceof Error ? error.message : '批次读取失败，请重试';
  } finally {
    if (requestId === contextRequest) contextLoading.value = false;
  }
}, { immediate: true });

async function loadData() {
  if (!factoryId.value) return;
  if (hasBatchContext.value && !scopedBatch.value) return;

  loading.value = true;
  const currentContext = contextRequest;
  try {
    const response = await get(`/${factoryId.value}/processing/quality/inspections`, {
      params: {
        page: pagination.value.page,
        size: pagination.value.size,
        keyword: searchKeyword.value || undefined,
        batchId: scopedBatchId.value || undefined,
        result: filterResult.value || undefined
      }
    });
    if (currentContext !== contextRequest) return;
    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载质检记录失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleRefresh() {
  searchKeyword.value = '';
  filterResult.value = '';
  pagination.value.page = 1;
  loadData();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function handleSizeChange(size: number) {
  pagination.value.size = size;
  pagination.value.page = 1;
  loadData();
}

function handleCreate() {
  if (hasBatchContext.value && !scopedBatch.value) return;
  invalidateDynamicRequest();
  dialogForm.value = { batchId: scopedBatchId.value, sampleSize: null, passCount: null, failCount: null, result: '', notes: '', customFields: {} as TableRow };
  dialogVisible.value = true;
  if (!hasBatchContext.value) void loadProductionBatches();
}

function invalidateDynamicRequest(): void {
  dynamicRequest += 1;
  dynamicFormLoading.value = false;
  dynamicForm.value = null;
  dynamicFormError.value = '';
}

async function loadDynamicForm(batchId: string): Promise<void> {
  const requestId = ++dynamicRequest;
  dynamicForm.value = null;
  dynamicFormError.value = '';
  if (!batchId || !factoryId.value) return;
  dynamicFormLoading.value = true;
  try {
    const response = await get<DynamicQualityForm>(
      `/${factoryId.value}/processing/batches/${encodeURIComponent(batchId)}/quality-form`,
    );
    if (requestId !== dynamicRequest) return;
    if (!response.success || !response.data) {
      throw new Error(response.message || '当前批次质检标准读取失败，请重试');
    }
    if (String(response.data.batchId) !== String(batchId) || !response.data.standardRevision || !Array.isArray(response.data.items)) {
      throw new Error('当前批次质检标准响应无效，请重试');
    }
    // An explicitly empty standard keeps the existing generic form. Any load
    // error remains blocking so a stale/unknown standard can never silently
    // fall back to the legacy POST path.
    dynamicForm.value = response.data.items.length > 0 ? response.data : null;
  } catch (error) {
    if (requestId !== dynamicRequest) return;
    dynamicFormError.value = error instanceof Error ? error.message : '质检标准读取失败，请重试';
  } finally {
    if (requestId === dynamicRequest) dynamicFormLoading.value = false;
  }
}

watch(() => [dialogVisible.value, dialogForm.value.batchId, factoryId.value], ([visible, batchId]) => {
  if (visible && typeof batchId === 'string' && batchId) void loadDynamicForm(batchId);
}, { flush: 'post' });

function retryDynamicForm(): void {
  if (dialogForm.value.batchId) void loadDynamicForm(dialogForm.value.batchId);
}

function closeDynamicDialog(): void {
  dialogVisible.value = false;
  invalidateDynamicRequest();
}

function onDynamicSubmitted(_response: DynamicQualitySubmitResponse): void {
  ElMessage.success('动态质检记录已创建');
  closeDynamicDialog();
  void loadData();
}

async function submitCreateForm() {
  if (!dialogForm.value.batchId) {
    ElMessage.warning('请选择生产批次');
    return;
  }
  if (!dialogForm.value.sampleSize || dialogForm.value.sampleSize <= 0) {
    ElMessage.warning('请输入抽样数量');
    return;
  }
  if (!balanceStatus.value.ok) {
    // Issue #825 fix (2026-05-17): use .error (globally overridden to sticky
    // duration:0 + showClose in main.ts) so user can't dismiss accidentally
    // while still reading the count. .warning auto-dismisses in 3s and the
    // 剩余 N 数字 was easy to miss.
    ElMessage.error(balanceStatus.value.msg);
    return;
  }
  if (!dialogForm.value.result) {
    ElMessage.warning('请选择检验结果');
    return;
  }
  submitting.value = true;
  try {
    const body: TableRow = {
      sampleSize: dialogForm.value.sampleSize,
      passCount: dialogForm.value.passCount,
      failCount: dialogForm.value.failCount,
      result: dialogForm.value.result,
      notes: dialogForm.value.notes || undefined,
      customFields: dialogForm.value.customFields,
    };
    // Inspector identity is already supplied by the existing authenticated controller.
    const response = await post(
      `/${factoryId.value}/processing/quality/inspections?batchId=${encodeURIComponent(dialogForm.value.batchId)}`,
      body
    );
    if (response.success) {
      ElMessage.success('质检记录已创建');
      dialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(response.message || '创建失败');
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '创建失败，请重试');
  } finally {
    submitting.value = false;
  }
}

function showDetail(row: TableRow) {
  detailData.value = row;
  detailVisible.value = true;
}
function reviewException(row: TableRow) {
  if (row.productionBatchId == null) return;
  void router.push({ path: '/quality/disposition', query: { batchId: String(row.productionBatchId), inspectionId: String(row.id) } });
}

function registerDefect(row: TableRow) {
  if (!row.id) return;
  void router.push({ path: '/quality/defects', query: { inspectionId: String(row.id) } });
}
</script>

<template>
  <CanvasAwareWrapper module-code="quality_inspection">
  <div class="page-wrapper">
    <el-alert v-if="contextError" :title="contextError" type="error" :closable="false" show-icon />
    <el-alert v-else-if="scopedBatch" :title="`当前批次：${scopedBatch.batchNumber} · ${scopedBatch.productName || ''}`" type="info" :closable="false">
      这里只显示本批次的质检记录。抽检结果不等同于生产良品率；出货后补录不能作为出货前放行证明。
      <el-button link type="primary" @click="returnToTrace">返回批次追溯</el-button>
    </el-alert>
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">质检记录管理</span>
            <span class="data-count">共 {{ pagination.total }} 条记录</span>
          </div>
          <div class="header-right">
            <el-button v-if="canWrite" type="primary" :icon="Plus" :disabled="contextLoading || !!contextError" @click="handleCreate">新建质检</el-button>
          </div>
        </div>
      </template>

      <div class="search-bar">
        <el-input
          v-model="searchKeyword"
          placeholder="搜索质检编号/批次号"
          :prefix-icon="Search"
          clearable
          style="width: 280px"
          @keyup.enter="handleSearch"
        />
        <el-select v-model="filterResult" placeholder="检测结果" clearable style="width: 150px">
          <el-option label="合格" value="PASS" />
          <el-option label="不合格" value="FAIL" />
          <el-option label="有条件放行" value="CONDITIONAL" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
      </div>

      <el-table :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border style="width: 100%">
        <el-table-column label="质检编号" width="160">
          <template #default="{ row }">
            {{ row.id ? row.id.substring(0, 8).toUpperCase() : '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="batchNumber" label="生产批次号" min-width="170" show-overflow-tooltip />
        <el-table-column prop="productName" label="品名" min-width="150" show-overflow-tooltip />
        <el-table-column label="工序" width="140"><template #default>未提供工序关联</template></el-table-column>
        <el-table-column prop="notes" label="备注" min-width="180" show-overflow-tooltip />
        <el-table-column prop="sampleSize" label="抽样数" width="90" align="center" />
        <!--
          原来这一列叫「质检员ID」且直接显示数字主键 —— 给用户看数据库标识。
          后端已补 inspectorName(解析不出时为 null, 不编造假名字), 这里优先显示姓名,
          回落到 ID 时带上「#」前缀, 让人一眼看出这是编号不是名字。
        -->
        <el-table-column label="质检员" width="120" align="center">
          <template #default="{ row }">
            {{ row.inspectorName || (row.inspectorId != null ? `#${row.inspectorId}` : '-') }}
          </template>
        </el-table-column>
        <el-table-column prop="result" label="检测结果" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="isPassResult(row.result) ? 'success' : (row.result === 'CONDITIONAL' ? 'warning' : 'danger')" size="small">
              {{ getResultLabel(row.result) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="qualityGrade" label="等级" width="70" align="center" />
        <el-table-column label="抽检合格率" width="110" align="center">
          <template #default="{ row }">
            {{ row.passRate != null ? row.passRate.toFixed(1) + '%' : '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="inspectionDate" label="检测日期" width="130" />
        <el-table-column label="操作" width="200" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="showDetail(row)">查看详情</el-button>
            <el-button v-if="canWrite && ['FAIL', 'FAILED', 'CONDITIONAL'].includes(String(row.result))" type="primary" link @click="reviewException(row)">异常复核</el-button>
            <el-button v-if="canWrite && row.id" type="warning" link @click="registerDefect(row)">登记不良</el-button>
          </template>
        </el-table-column>
      </el-table>

      <TableFooter v-if="!hasBatchContext"
        :stats="footerSummary?.stats ?? []"
        :loading="footerLoading"
        :show-export="false"
        @ai-analyze="() => ElMessage.info({ message: `AI 分析 (待接 SmartBI): 分析当前质检${formatSummaryForAI(footerSummary)}`, duration: 8000, showClose: true })"
      />

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <!-- 新建质检对话框 -->
    <el-dialog v-model="dialogVisible" title="新建质检记录" width="560px" :close-on-click-modal="false">
      <el-form :model="dialogForm" label-width="90px">
        <el-form-item label="生产批次" required>
          <el-select v-model="dialogForm.batchId" placeholder="选择生产批次" filterable :disabled="hasBatchContext || dynamicFormLoading" style="width: 100%">
            <el-option
              v-for="b in productionBatches"
              :key="String(b.id)"
              :label="`${b.batchNumber || b.id}${b.productName ? ' - ' + b.productName : ''}`"
              :value="String(b.id)"
            />
          </el-select>
          <UpstreamMissingHint v-if="productionBatches.length === 0" description="暂无生产批次，无法发起质检" target-module="production" require-write action-text="去生产管理" contact-text="请联系生产部门先开工生产批次" @action="goCreate('/production/plans')" />
        </el-form-item>
        <template v-if="dynamicFormLoading">
          <el-alert title="正在读取当前批次的质检标准…" type="info" :closable="false" show-icon />
          <div class="dynamic-loading" role="status">请稍候，标准读取完成后才能提交。</div>
        </template>
        <template v-else-if="dynamicFormError">
          <el-alert :title="dynamicFormError" type="error" :closable="false" show-icon />
          <div class="dynamic-error-actions">
            <span>未能确认标准版本，不能改用旧表单提交。</span>
            <el-button type="primary" @click="retryDynamicForm">重试读取标准</el-button>
          </div>
        </template>
        <DynamicQualityInspectionForm
          v-else-if="dynamicForm && hasDynamicItems(dynamicForm)"
          :factory-id="factoryId"
          :batch-id="dialogForm.batchId"
          :batch-number="scopedBatch?.batchNumber"
          :product-name="scopedBatch?.productName"
          :form="dynamicForm"
          @submitted="onDynamicSubmitted"
          @cancel="closeDynamicDialog"
        />
        <template v-else>
        <el-alert title="请记录实际抽样结果；抽样数量与生产数量分别统计，不会改写生产良品率。" type="info" :closable="false" />
        <!-- Issue #745: 抽样/合格/不合格 联动自动平衡 - 填两个第三自动算 -->
        <el-form-item label="抽样数量" required>
          <el-input-number
            v-model="dialogForm.sampleSize"
            :min="0"
            :controls="false"
            style="width: 100%"
            @change="onSampleChange"
            @focus="selectAllOnFocus" />
        </el-form-item>
        <el-form-item label="合格数" required>
          <el-input-number
            v-model="dialogForm.passCount"
            :min="0"
            :controls="false"
            style="width: 100%"
            @change="onPassChange"
            @focus="selectAllOnFocus" />
        </el-form-item>
        <el-form-item label="不合格数" required>
          <el-input-number
            v-model="dialogForm.failCount"
            :min="0"
            :controls="false"
            style="width: 100%"
            @change="onFailChange"
            @focus="selectAllOnFocus" />
        </el-form-item>
        <el-form-item label=" ">
          <el-tag
            :type="balanceStatus.ok ? 'success' : 'danger'"
            effect="plain"
            size="small">
            {{ balanceStatus.msg }}
          </el-tag>
        </el-form-item>
        <el-form-item label="检验结果" required>
          <el-select v-model="dialogForm.result" placeholder="选择检验结果" style="width: 100%">
            <el-option label="合格" value="PASS" />
            <el-option label="不合格" value="FAIL" />
            <el-option label="待复检" value="PENDING" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="dialogForm.notes" type="textarea" :rows="2" />
        </el-form-item>
        <CanvasDynamicFields v-model="dialogForm.customFields" module-code="quality_inspection" />
        </template>
      </el-form>
      <template v-if="!dynamicDialogMode" #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreateForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="质检记录详情" size="420px">
      <el-descriptions :column="1" border v-if="detailData">
        <el-descriptions-item label="质检编号">{{ detailData.id ? detailData.id.substring(0, 8).toUpperCase() : '-' }}</el-descriptions-item>
        <el-descriptions-item label="生产批次号">{{ detailData.batchNumber || '未提供' }}</el-descriptions-item>
        <el-descriptions-item label="品名">{{ detailData.productName || '未提供' }}</el-descriptions-item>
        <el-descriptions-item label="工序">未提供工序关联</el-descriptions-item>
        <el-descriptions-item label="备注">{{ detailData.notes || '-' }}</el-descriptions-item>
        <el-descriptions-item label="抽样数">{{ detailData.sampleSize ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="质检员">
          {{ detailData.inspectorName || (detailData.inspectorId != null ? `#${detailData.inspectorId}` : '-') }}
        </el-descriptions-item>
        <el-descriptions-item label="检测结果">
          <el-tag :type="isPassResult(detailData.result) ? 'success' : (detailData.result === 'CONDITIONAL' ? 'warning' : 'danger')" size="small">
            {{ getResultLabel(detailData.result) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="等级">{{ detailData.qualityGrade || '-' }}</el-descriptions-item>
        <el-descriptions-item label="抽检合格率">{{ detailData.passRate != null ? detailData.passRate.toFixed(1) + '%' : '-' }}</el-descriptions-item>
        <el-descriptions-item label="检测日期">{{ detailData.inspectionDate || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-drawer>
  </div>
  </CanvasAwareWrapper>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
}

.page-card {
  flex: 1;
  display: flex;
  flex-direction: column;

  :deep(.el-card__header) {
    padding: 16px 20px;
    border-bottom: 1px solid var(--border-color-lighter, #ebeef5);
  }

  :deep(.el-card__body) {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 20px;
  }
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;

  .header-left {
    display: flex;
    align-items: baseline;
    gap: 12px;

    .page-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-color-primary, #303133);
    }

    .data-count {
      font-size: 13px;
      color: var(--text-color-secondary, #909399);
    }
  }
}

.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.el-table {
  flex: 1;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid var(--border-color-lighter, #ebeef5);
  margin-top: 16px;
}
</style>
