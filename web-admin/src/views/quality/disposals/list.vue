<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Search, Refresh, Check, Close } from '@element-plus/icons-vue';
import { formatAmount, formatDateTimeCell } from '@/utils/tableFormatters';
import type { TableRow } from '@/types/api';
import { getUploadUrl, putFileWithProgress } from '@/api/attachment';
import { getFactoryRecall, type FactoryRecallDetail } from '@/api/factoryRecall';
import {
  getApplicantName,
  getBatchDisplayName,
  getBatchNumber,
  getQuantityDisplay
} from './disposalReadback';
import { disposalAvailableQuantity, isEligibleFinishedGoods } from './eligibleFinishedGoods';
import DisposalVoucherLinks from './DisposalVoucherLinks.vue';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const route = useRoute();
const router = useRouter();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('quality'));

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchForm = ref({
  keyword: '',
  status: ''
});

// 新建废弃对话框
const dialogVisible = ref(false);
const dialogLoading = ref(false);
// R12: targetType 区分报损对象 —— 'MATERIAL'(原料批次, 扣 MaterialBatch) / 'FINISHED'(成品批次, 扣 FinishedGoodsBatch)。
const disposalForm = ref({
  targetType: 'MATERIAL' as 'MATERIAL' | 'FINISHED',
  batchId: '',
  // 处置方式 = 后端 disposalType, 批准只认 SCRAP/DESTROY; 过期/损坏/质量问题是原因类别, 写进原因。
  disposalType: 'SCRAP',
  reasonCategory: '',
  quantity: 0,
  reason: '',
  // 客户自有货 (代加工) 必填: 退还客户 = 签收人/日期/签收单, 报废销毁 = 同意人/日期/同意凭证。
  customerConfirmedBy: '',
  customerConfirmedAt: '',
  customerConfirmationEvidence: '',
  evidenceImages: '',  // Gap-3: 证据照片 URL (逗号分隔), 独立列 evidence_images (V20261024_15)
  notes: ''
});
const actionLoading = ref(false);
const batches = ref<TableRow[]>([]);           // 原料批次 (MaterialBatch)
const fgBatches = ref<TableRow[]>([]);          // R12: 成品批次 (FinishedGoodsBatch)
const fgLoading = ref(false);
const contextLoading = ref(false);
const contextError = ref('');
const validatedRecall = ref<FactoryRecallDetail | null>(null);
const contextRequestSequence = ref(0);
const evidenceInput = ref<HTMLInputElement | null>(null);
const confirmationInput = ref<HTMLInputElement | null>(null);
const evidenceUploading = ref(false);
const evidenceProgress = ref(0);

function routeQueryText(name: string): string {
  const value = route.query[name];
  return typeof value === 'string' ? value.trim() : '';
}

const scopedFinishedGoodsBatchId = computed(() => routeQueryText('finishedGoodsBatchId'));
const scopedDisposalId = computed(() => routeQueryText('disposalId'));
const scopedRecallId = computed(() => routeQueryText('recallId'));
const scopedBatchId = computed(() => routeQueryText('batchId'));
const createDisabled = computed(() => Boolean(
  scopedFinishedGoodsBatchId.value
  && (fgLoading.value || !fgBatches.value.some((batch) => String(batch.id) === scopedFinishedGoodsBatchId.value && isEligibleFinishedGoods(batch))),
));

// R12: 当前报损对象对应的批次下拉数据源。
const currentBatches = computed<TableRow[]>(() =>
  disposalForm.value.targetType === 'FINISHED' ? fgBatches.value : batches.value
);

// F-FP-2 防呆 + R12: 选中批次的可用量上限。
// 原料: MaterialBatch.currentQuantity/remainingQuantity/receiptQuantity。
// 成品: FinishedGoodsBatch.availableQuantity (= produced - shipped - reserved)。
const selectedBatchMax = computed<number | null>(() => {
  const b = currentBatches.value.find((x) => x.id === disposalForm.value.batchId);
  if (!b) return null;
  const avail = disposalForm.value.targetType === 'FINISHED'
    ? disposalAvailableQuantity(b)
    : ((b.currentQuantity ?? b.remainingQuantity ?? b.receiptQuantity) as number | undefined);
  return typeof avail === 'number' ? avail : null;
});

// 客户自有成品 (代加工, 只在退回/不良/过期/冻结时出现在可选列表里): 处置方式换成
// 退还客户 / 经客户同意报废, 并要求客户确认 (Steve 2026-10-28)。
const selectedCustomerOwned = computed(() => disposalForm.value.targetType === 'FINISHED'
  && currentBatches.value.some((x) => x.id === disposalForm.value.batchId && x.customerOwned === true));
const returningToCustomer = computed(() => disposalForm.value.disposalType === 'RETURN_TO_CUSTOMER');
watch(selectedCustomerOwned, (owned) => {
  if (!owned && returningToCustomer.value) disposalForm.value.disposalType = 'SCRAP';
});

// R12: 切换报损对象时清空已选批次, 避免跨数据源残留无效 batchId。
function handleTargetTypeChange() {
  disposalForm.value.batchId = '';
  disposalForm.value.quantity = 0;
  if (disposalForm.value.targetType === 'FINISHED') loadFgBatches();
}

onMounted(() => loadBatches());

async function validateRecallContext(expectedRequest: number, expectedRecallId: string, expectedBatchId: string) {
  if (!expectedRecallId || !factoryId.value) {
    validatedRecall.value = null;
    return;
  }
  try {
    const recall = await getFactoryRecall(factoryId.value, expectedRecallId);
    if (expectedRequest !== contextRequestSequence.value || expectedRecallId !== scopedRecallId.value) return;
    if (String(recall.id) !== expectedRecallId || (expectedBatchId && String(recall.batchId) !== expectedBatchId)) {
      contextError.value = '召回上下文与当前批次不一致，已停止操作';
      validatedRecall.value = null;
      return;
    }
    validatedRecall.value = recall;
  } catch (error) {
    if (expectedRequest === contextRequestSequence.value && expectedRecallId === scopedRecallId.value) {
      contextError.value = error instanceof Error ? error.message : '召回上下文读取失败';
      validatedRecall.value = null;
    }
  }
}

async function applyRouteContext() {
  const expectedRequest = ++contextRequestSequence.value;
  const expectedFg = scopedFinishedGoodsBatchId.value;
  const expectedRecall = scopedRecallId.value;
  const expectedBatch = scopedBatchId.value;
  contextLoading.value = true;
  contextError.value = '';
  validatedRecall.value = null;
  disposalForm.value.targetType = expectedFg ? 'FINISHED' : 'MATERIAL';
  disposalForm.value.batchId = expectedFg;
  const tasks: Promise<void>[] = [
    loadData(expectedRequest, expectedFg),
    validateRecallContext(expectedRequest, expectedRecall, expectedBatch),
  ];
  if (expectedFg) tasks.push(loadFgBatches(expectedRequest, expectedFg));
  await Promise.all(tasks);
  if (expectedRequest === contextRequestSequence.value) contextLoading.value = false;
}

watch(
  () => [factoryId.value, route.query.finishedGoodsBatchId, route.query.disposalId, route.query.recallId, route.query.batchId],
  () => { void applyRouteContext(); },
  { immediate: true },
);

async function loadData(expectedRequest = contextRequestSequence.value, expectedFg = scopedFinishedGoodsBatchId.value) {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/disposal-records`, {
      params: {
        // 后端 @RequestParam page 是 0-based; el-pagination 的 pagination.page 是 1-based → 转换 (修: 之前直传致首屏请求第2页, 列表空)
        page: Math.max(0, pagination.value.page - 1),
        size: pagination.value.size,
        status: searchForm.value.status || undefined,
        keyword: searchForm.value.keyword.trim() || undefined,
        finishedGoodsBatchId: expectedFg || undefined,
      }
    });
    if (expectedRequest !== contextRequestSequence.value || expectedFg !== scopedFinishedGoodsBatchId.value) return;
    if (response.success && response.data !== undefined) {
      // 后端 getDisposalRecords 返回扁平 data=[数组] + 同级 totalElements (非 Spring Page 信封), 兼容两种形态
      tableData.value = Array.isArray(response.data) ? response.data : (response.data.content || []);
      pagination.value.total = (response as any).totalElements ?? response.data?.totalElements ?? tableData.value.length;
      await openScopedDisposal(expectedRequest, expectedFg);
    } else if (response.success === false) {
      if (expectedRequest === contextRequestSequence.value && expectedFg === scopedFinishedGoodsBatchId.value) {
        contextError.value = response.message || '加载报损记录失败';
      }
      ElMessage.error(response.message || '加载数据失败');
    }
  } catch (error: any) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
    if (expectedRequest === contextRequestSequence.value && expectedFg === scopedFinishedGoodsBatchId.value) {
      contextError.value = error instanceof Error ? error.message : '加载报损记录失败';
    }
  } finally {
    if (expectedRequest === contextRequestSequence.value && expectedFg === scopedFinishedGoodsBatchId.value) {
      loading.value = false;
    }
  }
}

async function openScopedDisposal(expectedRequest: number, expectedFg: string) {
  const recordId = scopedDisposalId.value;
  if (!recordId || !factoryId.value) return;
  const listed = tableData.value.find((row) => String(row.id ?? row.recordId ?? '') === recordId);
  if (listed) {
    if (expectedFg && String(listed.finishedGoodsBatchId ?? '') !== expectedFg) {
      contextError.value = '报损记录与当前成品批次不一致，已停止打开';
      return;
    }
    if (expectedRequest === contextRequestSequence.value && recordId === scopedDisposalId.value) handleView(listed);
    return;
  }
  try {
    const response = await get(`/${factoryId.value}/disposal-records/${encodeURIComponent(recordId)}`);
    if (expectedRequest !== contextRequestSequence.value || recordId !== scopedDisposalId.value) return;
    if (!response.success || !response.data || String(response.data.id ?? response.data.recordId ?? '') !== recordId) {
      contextError.value = response.message || '报损记录读取失败';
      return;
    }
    const record = response.data as TableRow;
    if (expectedFg && String(record.finishedGoodsBatchId ?? '') !== expectedFg) {
      contextError.value = '报损记录与当前成品批次不一致，已停止打开';
      return;
    }
    handleView(record);
  } catch (error) {
    if (expectedRequest === contextRequestSequence.value && recordId === scopedDisposalId.value) {
      contextError.value = error instanceof Error ? error.message : '报损记录读取失败';
    }
  }
}

async function loadBatches() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/material-batches`, {
      params: { status: 'AVAILABLE', size: 100 }
    });
    if (response.success && response.data) {
      batches.value = response.data.content || response.data || [];
    }
  } catch (error: any) {
    console.error('加载批次列表失败:', error);
    if (!error?.actionHint) ElMessage.error('加载批次列表失败');
  }
}

// 加载可报损成品余量；接口排除已发货、预留占用和已撤回的部分。
async function loadFgBatches(expectedRequest = contextRequestSequence.value, expectedFg = scopedFinishedGoodsBatchId.value) {
  if (!factoryId.value) return;
  fgLoading.value = true;
  fgBatches.value = [];
  try {
    const response = await get(`/${factoryId.value}/disposal-records/eligible-finished-goods`);
    if (expectedRequest !== contextRequestSequence.value || expectedFg !== scopedFinishedGoodsBatchId.value) return;
    if (response.success && Array.isArray(response.data)) {
      fgBatches.value = response.data.filter(isEligibleFinishedGoods);
      if (expectedFg && !fgBatches.value.some((batch) => String(batch.id) === expectedFg)) {
        contextError.value = '该成品批次已无可报损余量，不能新建报损申请';
      }
    } else {
      if (expectedRequest === contextRequestSequence.value && expectedFg === scopedFinishedGoodsBatchId.value) {
        contextError.value = response.message || '加载成品批次列表失败';
      }
      ElMessage.error(response.message || '加载成品批次列表失败');
    }
  } catch (error: any) {
    console.error('加载成品批次列表失败:', error);
    if (expectedRequest === contextRequestSequence.value && expectedFg === scopedFinishedGoodsBatchId.value) {
      contextError.value = error instanceof Error ? error.message : '加载成品批次列表失败';
    }
    if (!error?.actionHint) ElMessage.error('加载成品批次列表失败');
  } finally {
    if (expectedRequest === contextRequestSequence.value && expectedFg === scopedFinishedGoodsBatchId.value) {
      fgLoading.value = false;
    }
  }
}

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleRefresh() {
  searchForm.value = { keyword: '', status: '' };
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
  if (createDisabled.value) {
    ElMessage.warning(contextError.value || '该成品批次已无可报损余量，不能新建报损申请');
    return;
  }
  disposalForm.value = {
    targetType: scopedFinishedGoodsBatchId.value ? 'FINISHED' : 'MATERIAL',
    batchId: scopedFinishedGoodsBatchId.value,
    disposalType: 'SCRAP',
    reasonCategory: '',
    quantity: 0,
    reason: '',
    customerConfirmedBy: '',
    customerConfirmedAt: '',
    customerConfirmationEvidence: '',
    evidenceImages: '',
    notes: ''
  };
  if (disposalForm.value.targetType === 'FINISHED') void loadFgBatches();
  dialogVisible.value = true;
}

async function submitDisposal() {
  // 防呆: 字段级提示, 明确告诉用户缺哪个 (不再笼统 "请填写完整信息")
  if (!disposalForm.value.batchId) {
    ElMessage.warning('请选择批次');
    return;
  }
  if (disposalForm.value.targetType === 'FINISHED'
      && (selectedBatchMax.value == null || selectedBatchMax.value <= 0)) {
    ElMessage.warning('该成品批次已无可报损余量，请刷新后重新选择');
    return;
  }
  if (!disposalForm.value.disposalType) {
    ElMessage.warning('请选择处置方式');
    return;
  }
  if (!disposalForm.value.reasonCategory) {
    ElMessage.warning('请选择原因类别');
    return;
  }
  if (!disposalForm.value.quantity) {
    ElMessage.warning('请输入废弃数量');
    return;
  }
  if (!disposalForm.value.reason) {
    ElMessage.warning('请输入废弃原因');
    return;
  }
  // F-FP-1 防呆: 报废必须留证据 (转录硬约束). 至少 1 个证据 URL。
  if (!disposalForm.value.evidenceImages.trim()) {
    ElMessage.warning('请上传/粘贴至少一张证据照片');
    return;
  }
  if (selectedCustomerOwned.value) {
    const who = returningToCustomer.value ? '客户签收人' : '客户同意人';
    if (!disposalForm.value.customerConfirmedBy.trim()) {
      ElMessage.warning(`客户自有货请填写${who}`);
      return;
    }
    if (!disposalForm.value.customerConfirmedAt) {
      ElMessage.warning(`客户自有货请选择${returningToCustomer.value ? '签收' : '同意'}日期`);
      return;
    }
    if (!disposalForm.value.customerConfirmationEvidence.trim()) {
      ElMessage.warning(returningToCustomer.value ? '请上传客户签收单照片' : '请上传客户同意报废的凭证');
      return;
    }
  }
  // F-FP-2 防呆: 报废数量不得超过批次可用量。
  if (selectedBatchMax.value != null && disposalForm.value.quantity > selectedBatchMax.value) {
    ElMessage.warning(`报废数量不能超过批次可用量 ${selectedBatchMax.value}`);
    return;
  }

  // Issue #811 fix — rename FE form fields to match backend wire contract
  // (CreateDisposalRecordRequest DTO). FE form uses {batchId, quantity, reason}
  // but backend expects {materialBatchId, disposalQuantity, disposalReason}.
  // evidenceImages goes to dedicated evidence_images column (V20261024_15).
  // R12: targetType 决定批次字段 —— 成品报损传 finishedGoodsBatchId (扣 FinishedGoodsBatch),
  // 原料报损传 materialBatchId (扣 MaterialBatch)。
  const payload: Record<string, unknown> = {
    disposalType: disposalForm.value.disposalType,
    disposalQuantity: disposalForm.value.quantity,
    disposalReason: `【${disposalForm.value.reasonCategory}】${disposalForm.value.reason}`,
    evidenceImages: disposalForm.value.evidenceImages.trim() || undefined,
    notes: disposalForm.value.notes.trim() || undefined
  };
  if (selectedCustomerOwned.value) {
    payload.customerConfirmedBy = disposalForm.value.customerConfirmedBy.trim();
    payload.customerConfirmedAt = disposalForm.value.customerConfirmedAt;
    payload.customerConfirmationEvidence = disposalForm.value.customerConfirmationEvidence.trim();
  }
  if (disposalForm.value.targetType === 'FINISHED') {
    payload.finishedGoodsBatchId = disposalForm.value.batchId;
  } else {
    payload.materialBatchId = disposalForm.value.batchId;
  }

  dialogLoading.value = true;
  try {
    const response = await post(`/${factoryId.value}/disposal-records`, payload);
    if (response.success) {
      ElMessage.success('创建成功，等待审批');
      dialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(response.message || '创建失败');
    }
  } catch (error: any) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', error);
  } finally {
    dialogLoading.value = false;
  }
}

async function handleApprove(row: TableRow) {
  if (actionLoading.value || !factoryId.value) return;
  const sourceFactory = factoryId.value;
  const sourceUser = authStore.user?.id;
  const expectedRequest = contextRequestSequence.value;
  const expectedFg = scopedFinishedGoodsBatchId.value;
  const isCurrent = () => expectedRequest === contextRequestSequence.value
    && expectedFg === scopedFinishedGoodsBatchId.value
    && sourceFactory === factoryId.value && sourceUser === authStore.user?.id;
  // 防呆 Rule 2: confirm 带品名/批次/数量/损失 context, 不再笼统 "确定批准?"
  const ctx = approvalContextHtml(row);
  try {
    const action = row.disposalType === 'RETURN_TO_CUSTOMER' ? '批准退还客户' : '批准报废';
    await ElMessageBox.confirm(ctx, `审批确认 — ${action}`, {
      type: 'warning',
      dangerouslyUseHTMLString: true,
      confirmButtonText: '确认批准',
      cancelButtonText: '取消'
    });
  } catch { return; }
  if (!isCurrent()) return;
  actionLoading.value = true;
  try {
    // F-BUG-1/F-BUG-4: 审批人从后端 token 取, 前端无需传 approverId/approverName。
    const response = await put(`/${sourceFactory}/disposal-records/${row.id}/approve`, {});
    if (response.success) {
      if (!isCurrent()) return;
      ElMessage.success('已批准');
      await loadData(expectedRequest, expectedFg);
      if (expectedFg && expectedRequest === contextRequestSequence.value && expectedFg === scopedFinishedGoodsBatchId.value) {
        await loadFgBatches(expectedRequest, expectedFg);
      }
    } else {
      ElMessage.error(response.message || '操作失败');
    }
  } catch (e: any) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[操作失败]', e);
  } finally {
    actionLoading.value = false;
  }
}

// ==================== Reject (F-BUG-2 + 防呆 Rule 3) ====================
// 拒绝原因: 标准枚举 dropdown + "其他" 才填 textarea (替代纯自由文本, 便于统计)。
const rejectDialogVisible = ref(false);
const rejectRow = ref<TableRow | null>(null);
const rejectForm = ref({ reasonCode: '', detail: '' });
const REJECT_REASONS = [
  { value: '证据不足', label: '证据不足' },
  { value: '数量不符', label: '数量与实际不符' },
  { value: '可返工/可销售', label: '可返工或仍可销售, 无需报废' },
  { value: '需复核', label: '需现场复核' },
  { value: '其他', label: '其他' }
];

function approvalContextHtml(row: TableRow): string {
  const name = getBatchDisplayName(row);
  const batchNumber = getBatchNumber(row);
  const quantity = getQuantityDisplay(row);
  const batch = batchNumber !== '-' ? `批次 ${batchNumber}` : '';
  const qty = quantity !== '-' ? `数量 ${quantity}` : '';
  const type = getTypeText(row.disposalType as string);
  const loss = row.estimatedLoss != null ? `预估损失 ¥${row.estimatedLoss}` : '';
  const parts = [batch, qty, `类型 ${type}`, loss].filter(Boolean);
  return `<div style="line-height:1.8">
    <div><b>${name}</b></div>
    <div style="color:#606266">${parts.join(' · ')}</div>
    <div style="margin-top:6px;color:#e6a23c">批准后将扣减对应库存, 不可撤销。</div>
  </div>`;
}

function handleReject(row: TableRow) {
  if (actionLoading.value) return;
  rejectRow.value = row;
  rejectForm.value = { reasonCode: '', detail: '' };
  rejectDialogVisible.value = true;
}

async function submitReject() {
  if (!rejectForm.value.reasonCode) {
    ElMessage.warning('请选择拒绝原因');
    return;
  }
  // "其他" 必须补充说明
  if (rejectForm.value.reasonCode === '其他' && !rejectForm.value.detail.trim()) {
    ElMessage.warning('请补充拒绝原因说明');
    return;
  }
  const reason = rejectForm.value.reasonCode === '其他'
    ? rejectForm.value.detail.trim()
    : (rejectForm.value.detail.trim()
        ? `${rejectForm.value.reasonCode}: ${rejectForm.value.detail.trim()}`
        : rejectForm.value.reasonCode);

  actionLoading.value = true;
  try {
    // F-BUG-2: 走独立 reject 端点 (此前误走 approve → 反而批准+扣库存)。
    const response = await put(`/${factoryId.value}/disposal-records/${rejectRow.value?.id}/reject`, {
      reason
    });
    if (response.success) {
      ElMessage.success('已拒绝');
      rejectDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(response.message || '操作失败');
    }
  } catch (e: any) {
    console.error('[操作失败]', e);
  } finally {
    actionLoading.value = false;
  }
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    PENDING: 'warning',
    APPROVED: 'success',
    REJECTED: 'danger',
    COMPLETED: 'info'
  };
  return map[status?.toUpperCase()] || 'info';
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    PENDING: '待审批',
    APPROVED: '已批准',
    REJECTED: '已拒绝',
    COMPLETED: '已完成'
  };
  return map[status?.toUpperCase()] || status;
}

// ==================== View ====================
const viewDialogVisible = ref(false);
const viewRecord = ref<TableRow | null>(null);

function handleView(row: TableRow) {
  viewRecord.value = row;
  viewDialogVisible.value = true;
}

function openRecallContext() {
  if (!validatedRecall.value) return;
  router.push({ path: '/quality/recalls', query: { batchId: String(validatedRecall.value.batchId) } });
}

function chooseEvidencePhoto() {
  evidenceInput.value?.click();
}

function chooseConfirmationPhoto() {
  confirmationInput.value?.click();
}

function handleEvidencePhotoChange(event: Event) {
  return uploadPhotoInto(event, 'evidenceImages');
}

function handleConfirmationPhotoChange(event: Event) {
  return uploadPhotoInto(event, 'customerConfirmationEvidence');
}

async function uploadPhotoInto(event: Event, field: 'evidenceImages' | 'customerConfirmationEvidence') {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  if (!file.type.startsWith('image/')) {
    ElMessage.error('请选择图片文件');
    return;
  }
  evidenceUploading.value = true;
  evidenceProgress.value = 0;
  try {
    const response = await getUploadUrl(file.name, file.type, factoryId.value);
    await putFileWithProgress(response.data.uploadUrl, file, (progress) => {
      evidenceProgress.value = progress;
    });
    const urls = disposalForm.value[field].split(',').map((url) => url.trim()).filter(Boolean);
    if (!urls.includes(response.data.fileUrl)) urls.push(response.data.fileUrl);
    disposalForm.value[field] = urls.join(',');
    ElMessage.success('证据照片上传成功');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '证据照片上传失败');
  } finally {
    evidenceUploading.value = false;
  }
}

function getTypeText(type: string) {
  const map: Record<string, string> = {
    EXPIRED: '过期',
    DAMAGED: '损坏',
    QUALITY_ISSUE: '质量问题',
    SCRAP: '报废',
    DESTROY: '销毁',
    RETURN_TO_CUSTOMER: '退还客户',
    MATERIAL: '原料',
    OTHER: '其他'
  };
  return map[type?.toUpperCase()] || type;
}

function getRecordNumber(row: TableRow): string | number {
  return row.recordNumber ?? row.recordNo ?? row.id ?? '-';
}

function getDisposalReason(row: TableRow): string {
  return row.reason || row.disposalReason || '-';
}

</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">废弃处理</span>
            <span class="data-count">共 {{ pagination.total }} 条记录</span>
          </div>
          <div class="header-right">
            <el-button v-if="canWrite" data-activity-action="disposal.create.open" type="primary" :icon="Plus" :disabled="createDisabled || contextLoading || actionLoading" @click="handleCreate">
              新建申请
            </el-button>
          </div>
        </div>
      </template>

      <el-alert v-if="contextError" type="error" :closable="false" show-icon class="context-alert" :title="contextError" />
      <el-alert v-if="validatedRecall" type="info" :closable="false" class="context-alert">
        <template #title>
          当前来自召回 {{ validatedRecall.eventCode }} · 批次 {{ validatedRecall.batchNumber }}
          <el-button type="primary" link size="small" @click="openRecallContext">返回召回后续</el-button>
        </template>
      </el-alert>

      <div class="search-bar">
        <el-input
          v-model="searchForm.keyword"
          placeholder="搜索记录编号/批次号"
          :prefix-icon="Search"
          clearable
          style="width: 280px"
          @keyup.enter="handleSearch"
        />
        <el-select v-model="searchForm.status" placeholder="全部状态" clearable style="width: 150px">
          <el-option label="待审批" value="PENDING" />
          <el-option label="已批准" value="APPROVED" />
          <el-option label="已拒绝" value="REJECTED" />
          <el-option label="已完成" value="COMPLETED" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
      </div>

      <el-table :data="tableData" v-loading="loading" empty-text="暂无数据" stripe border style="width: 100%">
        <el-table-column label="记录编号" width="160">
          <template #default="{ row }">
            {{ getRecordNumber(row) }}
          </template>
        </el-table-column>
        <el-table-column label="批次号" width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ getBatchNumber(row) }}
          </template>
        </el-table-column>
        <el-table-column prop="disposalType" label="废弃类型" width="120" align="center">
          <template #default="{ row }">
            {{ getTypeText(row.disposalType) }}
          </template>
        </el-table-column>
        <el-table-column label="数量" width="100" align="right">
          <template #default="{ row }">
            {{ getQuantityDisplay(row) }}
          </template>
        </el-table-column>
        <el-table-column label="废弃原因" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            {{ getDisposalReason(row) }}
          </template>
        </el-table-column>
        <el-table-column label="申请人" width="120">
          <template #default="{ row }">
            {{ getApplicantName(row) }}
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="申请时间" width="180" :formatter="formatDateTimeCell" />
        <el-table-column label="操作" width="180" fixed="right" align="center">
          <template #default="{ row }">
            <el-button data-activity-action="disposal.view" type="primary" link size="small" @click="handleView(row)">查看</el-button>
            <el-button
              v-if="canWrite && row.status === 'PENDING'"
              data-activity-action="disposal.approve"
              type="success"
              link
              size="small"
              :icon="Check"
              :disabled="actionLoading"
              @click="handleApprove(row)"
            >批准</el-button>
            <el-button
              v-if="canWrite && row.status === 'PENDING'"
              data-activity-action="disposal.reject.open"
              type="danger"
              link
              size="small"
              :icon="Close"
              :disabled="actionLoading"
              @click="handleReject(row)"
            >拒绝</el-button>
          </template>
        </el-table-column>
      </el-table>

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

    <!-- 查看详情 -->
    <el-dialog v-model="viewDialogVisible" title="废弃处理详情" width="560px" destroy-on-close>
      <el-descriptions v-if="viewRecord" :column="1" border>
        <el-descriptions-item label="记录编号">{{ getRecordNumber(viewRecord) }}</el-descriptions-item>
        <el-descriptions-item label="批次号">{{ getBatchNumber(viewRecord) }}</el-descriptions-item>
        <el-descriptions-item label="废弃类型">{{ getTypeText(viewRecord.disposalType) }}</el-descriptions-item>
        <el-descriptions-item label="数量">{{ getQuantityDisplay(viewRecord) }}</el-descriptions-item>
        <el-descriptions-item label="废弃原因">{{ getDisposalReason(viewRecord) }}</el-descriptions-item>
        <el-descriptions-item label="申请人">{{ getApplicantName(viewRecord) }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="getStatusType(viewRecord.status)" size="small">{{ getStatusText(viewRecord.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="实际报废损失">
          {{ formatAmount(viewRecord.actualLoss) }}
          <div v-if="viewRecord.finishedGoodsBatchId" style="font-size:12px;color:#909399">按报废数量和真实单位成本计算</div>
        </el-descriptions-item>
        <el-descriptions-item label="审批人">{{ viewRecord.approverName || viewRecord.approvedByName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="审批时间">{{ formatDateTimeCell(null, null, viewRecord.approvedAt || viewRecord.approvalDate) }}</el-descriptions-item>
        <el-descriptions-item v-if="viewRecord.customerConfirmedBy" label="客户确认">
          {{ viewRecord.customerConfirmedBy }} · {{ viewRecord.customerConfirmedAt || '-' }}
          <div style="word-break:break-all;font-size:12px;color:#909399">{{ viewRecord.customerConfirmationEvidence }}</div>
        </el-descriptions-item>
        <el-descriptions-item label="证据图片">
          <template v-if="viewRecord.evidenceImages">
            <div style="word-break:break-all">{{ viewRecord.evidenceImages }}</div>
          </template>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="备注">
          <template v-if="viewRecord.notes">
            <div style="white-space:pre-wrap;word-break:break-all">{{ viewRecord.notes }}</div>
          </template>
          <span v-else>-</span>
        </el-descriptions-item>
      </el-descriptions>
      <DisposalVoucherLinks v-if="viewDialogVisible && viewRecord && factoryId && permissionStore.canAccess('finance')"
        :factory-id="factoryId" :disposal-id="String(viewRecord.id ?? viewRecord.recordId ?? '')" />
    </el-dialog>

    <!-- 新建废弃申请对话框 -->
    <el-dialog v-model="dialogVisible" title="新建废弃申请" width="500px">
      <el-form :model="disposalForm" label-width="100px">
        <!-- R12 防呆 Rule 3: 报损对象用单选, 切换批次数据源 (原料 / 成品)。 -->
        <el-form-item label="报损对象" required>
          <el-radio-group v-model="disposalForm.targetType" @change="handleTargetTypeChange">
            <el-radio-button label="MATERIAL">原料批次</el-radio-button>
            <el-radio-button label="FINISHED">成品批次</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="批次" required>
          <el-select v-model="disposalForm.batchId" placeholder="选择批次" style="width: 100%"
            :loading="disposalForm.targetType === 'FINISHED' && fgLoading">
            <!-- 成品只显示可报损余量，不展示已发货量；原料批次显批次号 + 物料名。 -->
            <el-option
              v-for="item in currentBatches"
              :key="item.id"
              :label="disposalForm.targetType === 'FINISHED'
                ? `${item.batchNumber} - ${item.productName || '成品'} (可报损余量 ${item.availableQuantity} ${item.unit || ''})${item.customerOwned ? '【客户自有】' : ''}`
                : `${item.batchNumber} - ${item.materialName || item.materialTypeName || '物料'}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-alert v-if="selectedCustomerOwned" type="warning" :closable="false" show-icon style="margin-bottom:12px"
          title="客户自有货（代加工）：只能退还客户，或经客户同意后报废/销毁；不计本厂损失，须登记客户确认" />
        <el-form-item label="处置方式" required>
          <el-select v-model="disposalForm.disposalType" placeholder="选择处置方式" style="width: 100%">
            <el-option v-if="selectedCustomerOwned" label="退还客户" value="RETURN_TO_CUSTOMER" />
            <el-option :label="selectedCustomerOwned ? '报废（客户同意）' : '报废'" value="SCRAP" />
            <el-option :label="selectedCustomerOwned ? '销毁（客户同意）' : '销毁'" value="DESTROY" />
          </el-select>
        </el-form-item>
        <template v-if="selectedCustomerOwned">
          <el-form-item :label="returningToCustomer ? '客户签收人' : '客户同意人'" required>
            <el-input v-model="disposalForm.customerConfirmedBy" maxlength="100" placeholder="客户方联系人姓名" />
          </el-form-item>
          <el-form-item :label="returningToCustomer ? '签收日期' : '同意日期'" required>
            <el-date-picker v-model="disposalForm.customerConfirmedAt" type="date" value-format="YYYY-MM-DD"
              placeholder="选择日期" style="width: 100%" />
          </el-form-item>
          <el-form-item :label="returningToCustomer ? '签收单' : '同意凭证'" required>
            <input ref="confirmationInput" type="file" accept="image/*" hidden @change="handleConfirmationPhotoChange" />
            <el-button type="primary" plain :loading="evidenceUploading" @click="chooseConfirmationPhoto">上传照片</el-button>
            <el-input v-model="disposalForm.customerConfirmationEvidence" type="textarea" :rows="2"
              placeholder="可粘贴照片 URL（多张用逗号分隔）" />
          </el-form-item>
        </template>
        <el-form-item label="原因类别" required>
          <el-select v-model="disposalForm.reasonCategory" placeholder="选择原因类别" style="width: 100%">
            <el-option label="过期" value="过期" />
            <el-option label="损坏" value="损坏" />
            <el-option label="质量问题" value="质量问题" />
            <el-option label="其他" value="其他" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量" required>
          <el-input-number
            v-model="disposalForm.quantity"
            :min="0.01"
            :max="selectedBatchMax ?? undefined"
            :step="0.01"
            :precision="2"
            style="width: 100%"
          />
          <div v-if="selectedBatchMax != null" style="font-size:12px;color:#909399;margin-top:4px">
            该批次可用量 {{ selectedBatchMax }}，报废数量不可超过此值
          </div>
        </el-form-item>
        <el-form-item label="废弃原因" required>
          <el-input v-model="disposalForm.reason" type="textarea" :rows="2" placeholder="请输入废弃原因" />
        </el-form-item>
        <el-form-item label="证据留存" required>
          <input ref="evidenceInput" type="file" accept="image/*" hidden @change="handleEvidencePhotoChange" />
          <el-button type="primary" plain :loading="evidenceUploading" @click="chooseEvidencePhoto">选择照片并上传</el-button>
          <span v-if="evidenceUploading" style="font-size:12px;color:#909399;margin-left:8px">上传中 {{ evidenceProgress }}%</span>
          <el-input
            v-model="disposalForm.evidenceImages"
            type="textarea"
            :rows="3"
            placeholder="可继续粘贴照片 URL（多张用逗号分隔）"
          />
          <div style="font-size:12px;color:#e6a23c;margin-top:4px">请上传现场照片证明，便于审批和留档（必填）</div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="disposalForm.notes" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button data-activity-action="disposal.create.submit" type="primary" :loading="dialogLoading" @click="submitDisposal">提交申请</el-button>
      </template>
    </el-dialog>

    <!-- 拒绝报废对话框 (F-BUG-2 + 防呆 Rule 3: 标准原因 dropdown) -->
    <el-dialog v-model="rejectDialogVisible" title="拒绝报废申请" width="480px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="报废记录">
          <span>{{ rejectRow ? getBatchDisplayName(rejectRow) : '-' }}
            <span v-if="rejectRow && getBatchNumber(rejectRow) !== '-'" style="color:#909399">
              ({{ getBatchNumber(rejectRow) }})
            </span>
          </span>
        </el-form-item>
        <el-form-item label="拒绝原因" required>
          <el-select v-model="rejectForm.reasonCode" placeholder="选择拒绝原因" style="width: 100%">
            <el-option v-for="r in REJECT_REASONS" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
        </el-form-item>
        <el-form-item :label="rejectForm.reasonCode === '其他' ? '说明' : '补充说明'">
          <el-input
            v-model="rejectForm.detail"
            type="textarea"
            :rows="2"
            :placeholder="rejectForm.reasonCode === '其他' ? '请填写具体拒绝原因（必填）' : '可选补充说明'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button data-activity-action="disposal.reject.confirm" type="danger" :loading="actionLoading" @click="submitReject">确认拒绝</el-button>
      </template>
    </el-dialog>
  </div>
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

.context-alert {
  margin-bottom: 12px;
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
