<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Refresh, Search } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import {
  acknowledgeFactoryRecallShipment,
  closeFactoryRecall,
  createFactoryRecall,
  createFactoryRecallReturn,
  getFactoryRecall,
  getFactoryRecallFollowUp,
  listFactoryRecalls,
  previewFactoryRecall,
  type FactoryRecallDetail,
  type FactoryRecallSummary,
  type RecallBatchContext,
  type RecallFollowUp,
  type RecallShipmentSummary,
} from '@/api/factoryRecall';

interface BatchOption {
  id: number | string;
  batchNumber?: string | null;
  productName?: string | null;
  unit?: string | null;
}

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const route = useRoute();
const router = useRouter();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('quality'));

const batchOptions = ref<BatchOption[]>([]);
const selectedBatchId = ref('');
const preview = ref<RecallBatchContext | null>(null);
const recalls = ref<FactoryRecallSummary[]>([]);
const selectedRecallId = ref('');
const detail = ref<FactoryRecallDetail | null>(null);
const recallClosed = computed(() => detail.value?.status === 'COMPLETED' || detail.value?.status === 'CLOSED');
const followUp = ref<RecallFollowUp | null>(null);
const followUpLoading = ref(false);
const followUpError = ref('');
const loading = ref(false);
const detailLoading = ref(false);
const actionLoading = ref(false);
const errorMessage = ref('');
const actionNotice = ref('');
let requestSequence = 0;
let detailRequestSequence = 0;
let followUpRequestSequence = 0;

const reasonChoice = ref('');
const customReason = ref('');
const reasonOptions = ['产品质量异常', '标签或批次信息错误', '客户投诉', '其他'];
const recallReason = computed(() => {
  const extra = customReason.value.trim();
  if (reasonChoice.value === '其他') return extra;
  return [reasonChoice.value, extra].filter(Boolean).join('：');
});

const ackDialogVisible = ref(false);
const ackShipment = ref<RecallShipmentSummary | null>(null);
const ackNote = ref('');
const returnDialogVisible = ref(false);
const returnShipment = ref<RecallShipmentSummary | null>(null);
const returnQuantity = ref<number | null>(null);
const returnReason = ref('客户召回退货');
const closeNote = ref('');

interface RecallContextSnapshot {
  requestSequence: number;
  batchId: string;
  eventId: string;
}

const statusLabels: Record<string, string> = {
  OPEN: '进行中',
  IN_PROGRESS: '进行中',
  FROZEN: '库存已隔离',
  COMPLETED: '已结案',
  CLOSED: '已结案',
  PENDING_APPROVAL: '待审批',
  DRAFT: '草稿',
  SUBMITTED: '已提交',
  PENDING: '待处理',
  APPROVED: '已批准',
  FINANCE_APPROVED: '财务已审核',
  PROCESSING: '处理中',
  RECEIVED: '已收货',
  RETURNED: '已退回',
  COMPLETED_RETURN: '已完成退货',
  REJECTED: '已拒绝',
  CANCELLED: '已取消',
};

function statusText(status: string | null | undefined): string {
  return statusLabels[String(status || '').toUpperCase()] || String(status || '未知');
}

function statusType(status: string | null | undefined): 'success' | 'warning' | 'danger' | 'info' {
  const normalized = String(status || '').toUpperCase();
  if (normalized === 'COMPLETED' || normalized === 'CLOSED') return 'success';
  if (normalized === 'FROZEN' || normalized === 'IN_PROGRESS' || normalized === 'OPEN') return 'warning';
  return 'info';
}

function validBatchId(value: unknown): string {
  return typeof value === 'string' && /^[1-9]\d*$/.test(value) ? value : '';
}

function batchLabel(batch: BatchOption): string {
  return `${batch.batchNumber || `批次 #${batch.id}`}${batch.productName ? ` · ${batch.productName}` : ''}`;
}

function defaultRecall(items: FactoryRecallSummary[]): FactoryRecallSummary | undefined {
  const activeStatuses = new Set(['OPEN', 'IN_PROGRESS', 'FROZEN', 'PENDING_APPROVAL']);
  return items.find((item) => activeStatuses.has(String(item.status || '').toUpperCase())) || items[0];
}

async function loadBatchOptions(): Promise<void> {
  if (!factoryId.value) return;
  try {
    const response = await get<{ content?: BatchOption[] } | BatchOption[]>(
      `/${factoryId.value}/processing/batches`,
      { params: { size: 200 } },
    );
    if (response.success && response.data) {
      batchOptions.value = Array.isArray(response.data) ? response.data : response.data.content || [];
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '生产批次读取失败，请重试';
  }
}

async function loadBatchContext(batchId: string): Promise<void> {
  const currentRequest = ++requestSequence;
  detailRequestSequence += 1;
  followUpRequestSequence += 1;
  selectedBatchId.value = batchId;
  detailLoading.value = false;
  closeNote.value = '';
  ackDialogVisible.value = false;
  returnDialogVisible.value = false;
  ackShipment.value = null;
  returnShipment.value = null;
  preview.value = null;
  recalls.value = [];
  detail.value = null;
  followUp.value = null;
  followUpError.value = '';
  selectedRecallId.value = '';
  errorMessage.value = '';
  actionNotice.value = '';
  if (!batchId || !factoryId.value) {
    await loadRecalls();
    return;
  }
  loading.value = true;
  try {
    const numericBatchId = Number(batchId);
    const [previewResult, recallResult] = await Promise.all([
      previewFactoryRecall(factoryId.value, numericBatchId),
      listFactoryRecalls(factoryId.value, numericBatchId),
    ]);
    if (currentRequest !== requestSequence) return;
    preview.value = previewResult;
    recalls.value = recallResult;
    const currentRecall = defaultRecall(recallResult);
    if (currentRecall) await selectRecall(currentRecall, currentRequest, batchId);
  } catch (error) {
    if (currentRequest === requestSequence) {
      errorMessage.value = error instanceof Error ? error.message : '批次召回信息加载失败，请重试';
    }
  } finally {
    if (currentRequest === requestSequence) loading.value = false;
  }
}

async function loadRecalls(): Promise<void> {
  if (!factoryId.value) return;
  const currentRequest = ++requestSequence;
  detailRequestSequence += 1;
  followUpRequestSequence += 1;
  detailLoading.value = false;
  followUp.value = null;
  followUpError.value = '';
  loading.value = true;
  errorMessage.value = '';
  try {
    const result = await listFactoryRecalls(factoryId.value);
    if (currentRequest !== requestSequence) return;
    recalls.value = result;
  } catch (error) {
    if (currentRequest === requestSequence) {
      errorMessage.value = error instanceof Error ? error.message : '召回记录加载失败，请重试';
    }
  } finally {
    if (currentRequest === requestSequence) loading.value = false;
  }
}

async function selectBatch(batchId: string): Promise<void> {
  const query = { ...route.query };
  if (batchId) query.batchId = batchId;
  else delete query.batchId;
  await router.replace({ query });
}

function captureRecallContext(): RecallContextSnapshot | null {
  if (!selectedBatchId.value || !detail.value) return null;
  return {
    requestSequence,
    batchId: selectedBatchId.value,
    eventId: String(detail.value.id),
  };
}

function isCurrentBatchContext(expectedRequest: number, expectedBatchId: string): boolean {
  return expectedRequest === requestSequence
    && selectedBatchId.value === expectedBatchId
    && String(preview.value?.batchId ?? '') === expectedBatchId;
}

function isCurrentRecallContext(snapshot: RecallContextSnapshot): boolean {
  return isCurrentBatchContext(snapshot.requestSequence, snapshot.batchId)
    && String(detail.value?.id ?? '') === snapshot.eventId;
}

async function loadRecallFollowUp(
  expectedRequest: number,
  expectedBatchId: string,
  eventId: string,
): Promise<void> {
  const followRequest = ++followUpRequestSequence;
  followUpLoading.value = true;
  followUpError.value = '';
  try {
    const result = await getFactoryRecallFollowUp(factoryId.value, eventId);
    if (followRequest !== followUpRequestSequence
      || !isCurrentBatchContext(expectedRequest, expectedBatchId)
      || String(detail.value?.id ?? '') !== eventId
      || String(result.recallId) !== eventId
      || String(result.batchId) !== expectedBatchId) return;
    followUp.value = result;
  } catch (error) {
    if (followRequest === followUpRequestSequence
      && isCurrentBatchContext(expectedRequest, expectedBatchId)
      && String(detail.value?.id ?? '') === eventId) {
      followUpError.value = error instanceof Error ? error.message : '召回后续处理读取失败，请重试';
    }
  } finally {
    if (followRequest === followUpRequestSequence) followUpLoading.value = false;
  }
}

async function selectRecall(
  item: FactoryRecallSummary | FactoryRecallDetail,
  expectedRequest = requestSequence,
  expectedBatchId = selectedBatchId.value,
): Promise<void> {
  if (!factoryId.value) return;
  const eventId = String(item.id);
  if (!expectedBatchId || expectedRequest !== requestSequence || selectedBatchId.value !== expectedBatchId) return;
  if (item.batchId != null && String(item.batchId) !== expectedBatchId) return;
  const detailRequest = ++detailRequestSequence;
  selectedRecallId.value = String(item.id);
  detailLoading.value = true;
  errorMessage.value = '';
  try {
    const result = await getFactoryRecall(factoryId.value, item.id);
    if (detailRequest !== detailRequestSequence
      || !isCurrentBatchContext(expectedRequest, expectedBatchId)
      || String(result.id) !== eventId
      || String(result.batchId) !== expectedBatchId) return;
    detail.value = result;
    preview.value = result;
    followUp.value = null;
    await loadRecallFollowUp(expectedRequest, expectedBatchId, eventId);
  } catch (error) {
    if (detailRequest === detailRequestSequence && isCurrentBatchContext(expectedRequest, expectedBatchId)) {
      errorMessage.value = error instanceof Error ? error.message : '召回详情加载失败，请重试';
    }
  } finally {
    if (detailRequest === detailRequestSequence) detailLoading.value = false;
  }
}

async function startRecall(): Promise<void> {
  if (!factoryId.value || !preview.value) return;
  if (!recallReason.value) {
    ElMessage.warning('请选择召回理由并补充必要说明');
    return;
  }
  const expectedRequest = requestSequence;
  const expectedBatchId = String(preview.value.batchId);
  actionLoading.value = true;
  errorMessage.value = '';
  try {
    const created = await createFactoryRecall(factoryId.value, {
      batchId: Number(expectedBatchId),
      reason: recallReason.value,
    });
    if (!isCurrentBatchContext(expectedRequest, expectedBatchId)) return;
    ElMessage.success('召回已发起，服务端已处理本批剩余成品隔离');
    reasonChoice.value = '';
    customReason.value = '';
    await loadBatchContext(expectedBatchId);
    if (selectedBatchId.value === expectedBatchId && preview.value?.batchId != null && expectedRequest === requestSequence) {
      await selectRecall(created, requestSequence, expectedBatchId);
    }
  } catch (error) {
    if (expectedRequest === requestSequence) {
      errorMessage.value = error instanceof Error ? error.message : '发起召回失败，请重试';
    }
  } finally {
    actionLoading.value = false;
  }
}

function openAck(shipment: RecallShipmentSummary): void {
  ackShipment.value = shipment;
  ackNote.value = '';
  ackDialogVisible.value = true;
}

async function submitAck(): Promise<void> {
  if (!factoryId.value || !detail.value || !ackShipment.value) return;
  if (!ackNote.value.trim()) {
    ElMessage.warning('请填写客户联系或回复凭据');
    return;
  }
  const context = captureRecallContext();
  if (!context) return;
  const shipmentId = ackShipment.value.shipmentId;
  actionLoading.value = true;
  try {
    const result = await acknowledgeFactoryRecallShipment(factoryId.value, context.eventId, {
      shipmentId,
      note: ackNote.value.trim(),
    });
    if (!isCurrentRecallContext(context)
      || String(result.id) !== context.eventId
      || String(result.batchId) !== context.batchId) return;
    detail.value = result;
    preview.value = result;
    await loadRecallFollowUp(context.requestSequence, context.batchId, context.eventId);
    if (!isCurrentRecallContext(context)) return;
    ackDialogVisible.value = false;
    actionNotice.value = '客户联系凭据已记录';
    ElMessage.success('客户联系凭据已记录');
  } catch (error) {
    if (isCurrentRecallContext(context)) {
      errorMessage.value = error instanceof Error ? error.message : '记录客户联系凭据失败，请重试';
    }
  } finally {
    actionLoading.value = false;
  }
}

function openReturn(shipment: RecallShipmentSummary): void {
  returnShipment.value = shipment;
  returnQuantity.value = null;
  returnReason.value = '客户召回退货';
  returnDialogVisible.value = true;
}

async function submitReturn(): Promise<void> {
  if (!factoryId.value || !detail.value || !returnShipment.value) return;
  const context = captureRecallContext();
  if (!context) return;
  const shipmentId = returnShipment.value.shipmentId;
  const quantity = Number(returnQuantity.value);
  const maximum = Number(returnShipment.value.remainingReturnQuantity);
  if (!Number.isFinite(quantity) || quantity <= 0 || quantity > maximum) {
    ElMessage.warning(`请输入 0 至 ${maximum} ${returnShipment.value.unit} 内的实际退货量`);
    return;
  }
  if (!returnReason.value.trim()) {
    ElMessage.warning('请填写退货理由');
    return;
  }
  actionLoading.value = true;
  try {
    await createFactoryRecallReturn(factoryId.value, context.eventId, {
      shipmentId,
      quantity,
      reason: returnReason.value.trim(),
    });
    if (!isCurrentRecallContext(context)) return;
    returnDialogVisible.value = false;
    actionNotice.value = '退货草稿已创建，请进入退货审批';
    ElMessage.success('退货草稿已创建，请进入退货审批');
    const event = detail.value;
    if (event) await selectRecall(event, context.requestSequence, context.batchId);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '登记退货失败，请重试';
  } finally {
    actionLoading.value = false;
  }
}

async function submitClose(): Promise<void> {
  if (!factoryId.value || !detail.value || !detail.value.canClose || recallClosed.value) return;
  const context = captureRecallContext();
  if (!context) return;
  actionLoading.value = true;
  try {
    const result = await closeFactoryRecall(factoryId.value, context.eventId, closeNote.value.trim());
    if (!isCurrentRecallContext(context)
      || String(result.id) !== context.eventId
      || String(result.batchId) !== context.batchId) return;
    detail.value = result;
    preview.value = result;
    await loadRecallFollowUp(context.requestSequence, context.batchId, context.eventId);
    if (!isCurrentRecallContext(context)) return;
    recalls.value = recalls.value.map(item => String(item.id) === context.eventId ? { ...item, ...result } : item);
    closeNote.value = '';
    ElMessage.success('召回已结案');
  } catch (error) {
    if (isCurrentRecallContext(context)) {
      errorMessage.value = error instanceof Error ? error.message : '召回结案失败，请先完成阻塞项';
    }
  } finally {
    actionLoading.value = false;
  }
}

function openTrace(): void {
  if (preview.value?.batchNumber) {
    void router.push({ path: '/production/traceability', query: { batchNumber: preview.value.batchNumber } });
  }
}

function openDisposition(): void {
  if (preview.value?.batchId != null) {
    void router.push({ path: '/quality/disposition', query: { batchId: String(preview.value.batchId) } });
  }
}

function openInspections(): void {
  if (preview.value?.batchId != null) {
    void router.push({ path: '/quality/inspections', query: { batchId: String(preview.value.batchId) } });
  }
}

function openReturnOrder(id: number | string): void {
  if (detail.value?.id == null || preview.value?.batchId == null) return;
  void router.push({
    path: `/sales/returns/${encodeURIComponent(String(id))}`,
    query: {
      recallId: String(detail.value.id),
      batchId: String(preview.value.batchId),
    },
  });
}

const followUpStatusLabels: Record<string, string> = {
  PENDING: '待处理',
  APPROVED: '已批准',
  REJECTED: '已驳回',
  MISSING: '缺少应收调整',
  NOT_DUE: '暂不应收',
  NOT_REQUIRED: '无需调整',
  INCONSISTENT: '数据不一致',
};

function followUpStatusText(status: string | null | undefined): string {
  return followUpStatusLabels[String(status || '').toUpperCase()] || statusText(status);
}

function followUpStatusType(done: boolean): 'success' | 'warning' {
  return done ? 'success' : 'warning';
}

function stockOriginText(origin: string): string {
  return origin === 'RETURNED' ? '退回库存' : '原库存';
}

function openFollowUpDisposal(stock: RecallFollowUp['stock'][number], disposalId?: number | string): void {
  if (!detail.value || stock.finishedGoodsBatchId == null) return;
  const query: Record<string, string> = {
    finishedGoodsBatchId: String(stock.finishedGoodsBatchId),
    recallId: String(detail.value.id),
    batchId: String(detail.value.batchId),
  };
  if (disposalId != null) query.disposalId = String(disposalId);
  void router.push({ path: '/quality/disposals', query });
}

function openReceivable(returnOrderId: number | string): void {
  void router.push({ path: '/finance/ar-ap', query: { returnOrderId: String(returnOrderId) } });
}

function openAdjustment(returnOrderId: number | string): void {
  void router.push({ path: '/finance/adjustments', query: { returnOrderId: String(returnOrderId) } });
}

function canOpenAdjustment(status: string): boolean {
  return ['PENDING', 'REJECTED', 'MISSING', 'INCONSISTENT'].includes(String(status || '').toUpperCase());
}

watch(
  () => [route.query.batchId, factoryId.value],
  async () => {
    const queryBatchId = validBatchId(route.query.batchId);
    selectedBatchId.value = queryBatchId;
    if (queryBatchId) await loadBatchContext(queryBatchId);
    else {
      ackDialogVisible.value = false;
      returnDialogVisible.value = false;
      ackShipment.value = null;
      returnShipment.value = null;
      preview.value = null;
      detail.value = null;
      followUp.value = null;
      followUpError.value = '';
      selectedRecallId.value = '';
      await Promise.all([loadBatchOptions(), loadRecalls()]);
    }
  },
  { immediate: true },
);

onMounted(() => {
  if (!route.query.batchId) void loadBatchOptions();
});

let activationCount = 0;
onActivated(() => {
  // KeepAlive 组件首次挂载也会触发 onActivated；首次数据由 route watcher 负责，
  // 后续再次激活才重新读回服务端事件和退货量。
  if (activationCount++ === 0) return;
  const batchId = validBatchId(route.query.batchId);
  if (batchId) void loadBatchContext(batchId);
  else void loadRecalls();
});
</script>

<template>
  <div class="recall-page">
    <el-card shadow="never" class="page-card">
      <template #header>
        <div class="page-header">
          <div>
            <div class="page-title">批次召回</div>
            <div class="page-subtitle">按真实出货和退货凭据逐单处理，不自动发送客户消息</div>
          </div>
          <el-button :icon="Refresh" :loading="loading" @click="selectedBatchId ? loadBatchContext(selectedBatchId) : loadRecalls()">刷新</el-button>
        </div>
      </template>

      <el-alert v-if="errorMessage" type="error" :closable="false" show-icon class="page-error" :title="errorMessage" />
      <el-alert v-if="actionNotice" type="success" :closable="false" show-icon class="page-notice" :title="actionNotice" />

      <div v-if="!route.query.batchId" class="batch-picker">
        <span class="picker-label">选择生产批次</span>
        <el-select
          v-model="selectedBatchId"
          filterable
          clearable
          :disabled="actionLoading"
          :prefix-icon="Search"
          placeholder="搜索批次号或品名"
          class="batch-select"
          @change="selectBatch"
        >
          <el-option v-for="batch in batchOptions" :key="String(batch.id)" :label="batchLabel(batch)" :value="String(batch.id)" />
        </el-select>
        <span class="picker-hint">选择后将由服务端返回批号、品名、库存和真实出货范围</span>
      </div>

      <el-alert v-if="preview" type="info" :closable="false" class="batch-context">
        <template #title>
          <strong>{{ preview.batchNumber }} · {{ preview.productName }}</strong>
          <span> · 剩余库存 {{ preview.remainingInventory }} {{ preview.unit }}</span>
        </template>
        <template #default>
          <el-button link type="primary" @click="openTrace">查看批次追溯</el-button>
          <el-button link type="primary" @click="openInspections">查看本批质检</el-button>
          <el-button link type="primary" @click="openDisposition">查看本批处置</el-button>
        </template>
      </el-alert>

      <el-empty v-if="!route.query.batchId && !recalls.length && !loading" description="请选择批次，或暂无召回记录" />

      <div v-if="preview" class="recall-workspace">
        <el-card shadow="never" class="start-card">
          <template #header><span class="section-title">发起召回</span></template>
          <el-form inline @submit.prevent="startRecall">
            <el-form-item label="召回理由">
              <el-select v-model="reasonChoice" placeholder="请选择中文理由" style="width: 220px">
                <el-option v-for="reason in reasonOptions" :key="reason" :label="reason" :value="reason" />
              </el-select>
            </el-form-item>
            <el-form-item label="补充说明">
              <el-input v-model="customReason" maxlength="200" show-word-limit placeholder="可补充批次异常原因" style="width: 300px" />
            </el-form-item>
            <el-form-item>
              <el-button v-if="canWrite" type="primary" :loading="actionLoading" @click="startRecall">发起并隔离剩余成品</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card shadow="never" class="event-card">
          <template #header><span class="section-title">已有召回事件</span></template>
          <el-table :data="recalls" stripe border empty-text="本批暂无召回事件，请先发起">
            <el-table-column prop="eventCode" label="事件编号" min-width="150" />
            <el-table-column label="状态" width="110" align="center">
              <template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="reason" label="理由" min-width="220" show-overflow-tooltip />
            <el-table-column label="操作" width="100" align="center">
              <template #default="{ row }"><el-button link type="primary" :disabled="actionLoading" @click="selectRecall(row)">查看处理</el-button></template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card v-if="detail" shadow="never" class="detail-card">
          <template #header>
            <div class="detail-header">
              <span class="section-title">召回处理</span>
              <el-tag :type="statusType(detail.status)">{{ statusText(detail.status) }}</el-tag>
            </div>
          </template>
          <el-steps :active="detail.status === 'COMPLETED' || detail.status === 'CLOSED' ? 3 : 1" finish-status="success" simple>
            <el-step title="库存隔离" />
            <el-step title="联系与收回" />
            <el-step title="核对与结案" />
          </el-steps>

          <el-alert v-if="detail.blockers?.length" type="warning" :closable="false" class="blocker-alert">
            <template #title>当前不能结案</template>
            <template #default>
              <div v-for="blocker in detail.blockers" :key="`${blocker.code}-${blocker.shipmentId || ''}`">{{ blocker.message }}</div>
            </template>
          </el-alert>

          <div class="shipment-list">
            <div v-for="shipment in detail.shipments" :key="shipment.shipmentId" class="shipment-row">
              <div class="shipment-main">
                <div class="shipment-title">{{ shipment.shipmentNumber }} · {{ shipment.customerName }}</div>
                <div class="shipment-boundary">
                  已发 {{ shipment.quantity }} {{ shipment.unit }} · 已完成退货 {{ shipment.returnedQuantity }} {{ shipment.unit }} · 待处理 {{ shipment.pendingReturnQuantity }} {{ shipment.unit }} · 还可登记 {{ shipment.remainingReturnQuantity }} {{ shipment.unit }}
                </div>
                <div v-if="shipment.returnOrders.length" class="return-list">
                  <span v-for="order in shipment.returnOrders" :key="order.id" class="return-item">
                    <el-button link type="primary" @click="openReturnOrder(order.id)">{{ order.returnNumber }}</el-button>
                    <el-tag size="small" effect="plain">{{ order.status === 'COMPLETED' ? '已完成' : statusText(order.status) }} · {{ order.quantity }} {{ shipment.unit }}</el-tag>
                  </span>
                </div>
              </div>
              <div class="shipment-actions">
                <el-tag v-if="shipment.acknowledged" type="success" size="small">已记录联系</el-tag>
                <el-button v-else-if="canWrite" size="small" @click="openAck(shipment)">记录客户联系</el-button>
                <el-button v-if="canWrite && shipment.remainingReturnQuantity > 0" type="primary" plain size="small" @click="openReturn(shipment)">登记实际退货</el-button>
              </div>
            </div>
          </div>

          <el-card v-if="followUp || followUpLoading || followUpError" shadow="never" class="follow-up-card">
            <template #header>
              <div class="detail-header">
                <span class="section-title">后续处理核对</span>
                <el-tag v-if="followUp" :type="recallClosed ? 'warning' : followUpStatusType(followUp.completed)">
                  {{ recallClosed ? '事件已结案，后续单独核对' : (followUp.completed ? '三项均已核对' : '仍有待办') }}
                </el-tag>
              </div>
            </template>
            <el-skeleton v-if="followUpLoading" :rows="3" animated />
            <el-alert v-else-if="followUpError" type="error" :closable="false" show-icon :title="followUpError" />
            <template v-else-if="followUp">
              <div class="follow-up-summary">
                <div class="follow-up-state">
                  <span>客户收回</span>
                  <el-tag :type="followUpStatusType(followUp.recallCompleted)" size="small">{{ followUp.recallCompleted ? '已完成' : '待处理' }}</el-tag>
                </div>
                <div class="follow-up-state">
                  <span>库存处置</span>
                  <el-tag :type="followUpStatusType(followUp.inventoryResolved)" size="small">{{ followUp.inventoryResolved ? '已核对' : '待处理' }}</el-tag>
                </div>
                <div class="follow-up-state">
                  <span>应收调整</span>
                  <el-tag :type="followUpStatusType(followUp.receivableResolved)" size="small">{{ followUp.receivableResolved ? '已核对' : '待处理' }}</el-tag>
                </div>
              </div>
              <el-alert v-if="!followUp.completed && followUp.issues.length" type="warning" :closable="false" class="follow-up-issues">
                <template #title>待办事项</template>
                <div v-for="issue in followUp.issues" :key="issue.code">{{ issue.message }}</div>
              </el-alert>
              <div v-if="followUp.stock.length" class="follow-up-section">
                <div class="follow-up-section-title">库存处置</div>
                <div v-for="stock in followUp.stock" :key="`${stock.finishedGoodsBatchId}-${stock.origin}`" class="follow-up-row">
                  <div>
                    <strong>{{ stock.batchNumber }}</strong>
                    <span class="follow-up-muted"> · {{ stockOriginText(stock.origin) }} · 剩余 {{ stock.remainingQuantity }} {{ stock.unit }} · 已审批处置 {{ stock.disposalQuantity }} {{ stock.unit }}</span>
                    <div class="follow-up-muted">数量核对：{{ stock.evidenceComplete ? '已核对' : '待核对' }}</div>
                  </div>
                  <div class="follow-up-actions">
                    <el-button v-if="stock.finishedGoodsBatchId != null" link type="primary" @click="openFollowUpDisposal(stock)">去处理</el-button>
                    <span v-for="disposal in stock.disposals" :key="disposal.id" class="follow-up-disposal">
                      <el-button link type="primary" @click="openFollowUpDisposal(stock, disposal.id)">记录 #{{ disposal.id }}</el-button>
                      <el-tag size="small" effect="plain">{{ statusText(disposal.status) }} · {{ disposal.quantity }} {{ stock.unit }}</el-tag>
                    </span>
                  </div>
                </div>
              </div>
              <div v-if="followUp.returns.length" class="follow-up-section">
                <div class="follow-up-section-title">应收调整</div>
                <div v-for="item in followUp.returns" :key="item.returnOrderId" class="follow-up-row">
                  <div>
                    <strong>{{ item.returnNumber }}</strong>
                    <span class="follow-up-muted"> · 退货 {{ item.quantity }} {{ followUp.unit }} · {{ statusText(item.status) }}</span>
                    <div class="follow-up-muted">应收：{{ followUpStatusText(item.receivableStatus) }}</div>
                    <div v-for="adjustment in item.adjustments" :key="adjustment.id" class="follow-up-muted">
                      调整 {{ adjustment.transactionNumber }} · {{ statusText(adjustment.approvalStatus) }}
                    </div>
                  </div>
                  <div class="follow-up-actions">
                    <el-button link type="primary" @click="openReceivable(item.returnOrderId)">查看应收</el-button>
                    <el-button v-if="canOpenAdjustment(item.receivableStatus)" link type="primary" @click="openAdjustment(item.returnOrderId)">查看调整</el-button>
                  </div>
                </div>
              </div>
            </template>
          </el-card>

          <div class="close-bar">
            <el-input v-model="closeNote" :disabled="recallClosed" maxlength="200" show-word-limit placeholder="结案备注（可选）" style="width: 320px" />
            <el-button v-if="canWrite" type="primary" :disabled="!detail.canClose || recallClosed" :loading="actionLoading" @click="submitClose">{{ recallClosed ? '已结案' : '确认结案' }}</el-button>
          </div>
        </el-card>
        <el-skeleton v-else-if="detailLoading" :rows="5" animated />
      </div>
    </el-card>

    <el-dialog v-model="ackDialogVisible" title="记录客户联系凭据" width="480px" destroy-on-close>
      <div class="dialog-hint">只记录人工联系或客户回复凭据，不会发送短信、邮件或站内消息。</div>
      <el-input v-model="ackNote" type="textarea" :rows="4" maxlength="500" show-word-limit placeholder="例如：2026-10-16 电话确认客户已收到通知" />
      <template #footer>
        <el-button @click="ackDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading" @click="submitAck">保存记录</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="returnDialogVisible" title="登记实际退货" width="480px" destroy-on-close>
      <div v-if="returnShipment" class="dialog-hint">
        {{ returnShipment.shipmentNumber }} · {{ returnShipment.customerName }}；最多可登记 {{ returnShipment.remainingReturnQuantity }} {{ returnShipment.unit }}。提交后进入既有退货审批，不代表已收货入库。
      </div>
      <el-form label-width="90px">
        <el-form-item label="实际退货量"><el-input-number v-model="returnQuantity" :min="0" :max="returnShipment?.remainingReturnQuantity || 0" :precision="4" /></el-form-item>
        <el-form-item label="退货理由"><el-input v-model="returnReason" maxlength="200" show-word-limit /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="returnDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading" @click="submitReturn">创建退货草稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.recall-page { padding: 20px; }
.page-card { max-width: 1440px; margin: 0 auto; }
.page-header, .detail-header, .batch-picker, .close-bar { display: flex; align-items: center; gap: 12px; }
.page-header, .detail-header { justify-content: space-between; }
.page-title { font-size: 20px; font-weight: 600; }
.page-subtitle, .picker-hint, .dialog-hint { color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.6; }
.page-error, .batch-context, .blocker-alert { margin-bottom: 16px; }
.batch-picker { flex-wrap: wrap; margin-bottom: 16px; }
.picker-label { font-weight: 600; }
.batch-select { width: 360px; }
.recall-workspace { display: flex; flex-direction: column; gap: 16px; margin-top: 16px; }
.section-title { font-weight: 600; }
.start-card :deep(.el-form) { margin-bottom: -18px; }
.shipment-list { margin-top: 20px; }
.shipment-row { display: flex; justify-content: space-between; gap: 16px; padding: 16px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.shipment-row:last-child { border-bottom: 0; }
.shipment-main { min-width: 0; }
.shipment-title { font-weight: 600; margin-bottom: 6px; }
.shipment-boundary { color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.6; }
.shipment-actions, .return-list, .return-item { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.shipment-actions { flex-shrink: 0; align-content: center; justify-content: flex-end; }
.return-list { margin-top: 8px; }
.follow-up-card { margin-top: 18px; background: var(--el-fill-color-extra-light); }
.follow-up-summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.follow-up-state { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px 12px; background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter); border-radius: 6px; }
.follow-up-issues { margin-top: 12px; }
.follow-up-section { margin-top: 16px; }
.follow-up-section-title { margin-bottom: 8px; font-weight: 600; }
.follow-up-row { display: flex; justify-content: space-between; gap: 16px; padding: 10px 0; border-top: 1px solid var(--el-border-color-lighter); }
.follow-up-muted { color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.6; }
.follow-up-actions, .follow-up-disposal { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.close-bar { justify-content: flex-end; margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--el-border-color-lighter); }
@media (max-width: 768px) {
  .recall-page { padding: 12px; }
  .shipment-row, .follow-up-row, .close-bar { align-items: stretch; flex-direction: column; }
  .shipment-actions { justify-content: flex-start; }
  .batch-select { width: 100%; }
  .follow-up-summary { grid-template-columns: 1fr; }
}
</style>
