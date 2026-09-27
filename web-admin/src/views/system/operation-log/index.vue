<template>
  <div class="operation-log-page">
    <div class="page-header">
      <h2>系统操作日志</h2>
      <el-button :icon="Refresh" @click="load" :loading="loading">刷新</el-button>
    </div>

    <!-- Filters -->
    <el-card class="filter-card" shadow="never">
      <el-form :inline="true" :model="filters" @submit.prevent>
        <el-form-item label="模块">
          <el-select v-model="filters.module" placeholder="全部模块" clearable filterable allow-create style="width: 170px">
            <el-option label="库存变更" value="INVENTORY" />
            <el-option label="业务请求" value="FACTORY_API" />
            <el-option label="原料" value="MATERIAL" />
            <el-option label="客户" value="CUSTOMER" />
            <el-option label="供应商" value="SUPPLIER" />
          </el-select>
        </el-form-item>
        <el-form-item label="操作">
          <el-select v-model="filters.action" placeholder="全部" clearable style="width: 130px">
            <el-option label="新增" value="CREATE" />
            <el-option label="修改" value="UPDATE" />
            <el-option label="删除" value="DELETE" />
            <el-option label="导出" value="EXPORT" />
            <el-option label="导入" value="IMPORT" />
            <el-option label="其他操作" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="用户 ID">
          <el-input-number v-model="filters.userId" :min="1" controls-position="right" style="width: 130px" />
        </el-form-item>
        <el-form-item label="记录类型">
          <el-select v-model="filters.entityType" placeholder="全部类型" clearable filterable allow-create style="width: 170px">
            <el-option v-for="(label, value) in entityLabels" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="记录 ID">
          <el-input v-model="filters.entityId" clearable style="width: 130px" />
        </el-form-item>
        <el-form-item label="时间范围">
          <el-date-picker
            v-model="dateRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始"
            end-placeholder="结束"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 360px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onSearch">查询</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Table -->
    <el-card shadow="never" style="margin-top: 12px">
      <el-table :data="logs" stripe v-loading="loading" border>
        <el-table-column prop="createdAt" label="时间" width="220">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="用户" width="140">
          <template #default="{ row }">
            <span v-if="row.username">{{ row.username }}</span>
            <span v-else-if="row.userId" class="muted">#{{ row.userId }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="模块" width="130">
          <template #default="{ row }">{{ moduleLabels[row.module] || row.module }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-tag :type="actionTagType(row.action)" size="small">{{ actionLabels[row.action] || row.action }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="业务记录" min-width="200">
          <template #default="{ row }">
            <span v-if="row.entityType">
              <strong>{{ entityLabels[simpleName(row.entityType)] || simpleName(row.entityType) }}</strong>
              <span v-if="row.entityId" class="muted"> #{{ row.entityId }}</span>
            </span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.success" type="success" size="small">成功</el-tag>
            <el-tag v-else type="danger" size="small">失败</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90">
          <template #default="{ row }">
            <span v-if="row.elapsedMs != null">{{ row.elapsedMs }} ms</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="详情" width="100">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[20, 50, 100, 200]"
        layout="total, sizes, prev, pager, next, jumper"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="load"
        @size-change="load"
      />
    </el-card>

    <!-- Detail dialog -->
    <el-dialog v-model="detailOpen" title="操作日志详情" width="780px" destroy-on-close>
      <el-alert v-if="detail?.module === 'INVENTORY'" type="info" :closable="false" show-icon
        title="此记录随库存事务提交，展示已记录的业务字段变化；同一次操作可能涉及多个批次和单据。" style="margin-bottom: 16px" />
      <div v-if="detail" class="detail-grid">
        <div><strong>时间:</strong> {{ formatTime(detail.createdAt) }}</div>
        <div><strong>用户:</strong> {{ detail.username || (detail.userId ? '#' + detail.userId : '未关联用户') }}</div>
        <div><strong>模块:</strong> {{ moduleLabels[detail.module] || detail.module }}</div>
        <div><strong>操作:</strong> <el-tag size="small">{{ actionLabels[detail.action] || detail.action }}</el-tag></div>
        <div><strong>业务记录:</strong> {{ entityLabels[simpleName(detail.entityType || '')] || detail.entityType }} #{{ detail.entityId }}</div>
        <div><strong>结果:</strong>
          <el-tag :type="detail.success ? 'success' : 'danger'" size="small">
            {{ detail.success ? '成功' : '失败' }}
          </el-tag>
        </div>
        <div><strong>耗时:</strong> {{ detail.elapsedMs == null ? '—' : detail.elapsedMs + ' ms' }}</div>
        <div><strong>IP:</strong> {{ detail.ipAddress || '—' }}</div>
        <div class="span-2"><strong>摘要:</strong> {{ detail.summary || '—' }}</div>
        <div v-if="detail.errorMessage" class="span-2" style="color: #f56c6c">
          <strong>错误:</strong> {{ detail.errorMessage }}
        </div>
      </div>

      <el-divider v-if="detail?.diff && detail.diff.length" />

      <div v-if="detail?.diff && detail.diff.length">
        <h4 style="margin: 0 0 12px">字段级变更 ({{ detail.diff.length }})</h4>
        <el-table :data="detail.diff" border size="small">
          <el-table-column label="字段" width="180">
            <template #default="{ row }">{{ fieldLabels[row.field] || row.field }}</template>
          </el-table-column>
          <el-table-column label="变更前">
            <template #default="{ row }">
              <code class="diff-val">{{ formatValue(row.from) }}</code>
            </template>
          </el-table-column>
          <el-table-column label="变更后">
            <template #default="{ row }">
              <code class="diff-val">{{ formatValue(row.to) }}</code>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <el-divider v-if="detail?.oldValue || detail?.newValue" />

      <el-collapse v-if="detail?.oldValue || detail?.newValue" accordion>
        <el-collapse-item v-if="detail.oldValue" title="变更前已记录字段">
          <pre class="json-pre">{{ JSON.stringify(detail.oldValue, null, 2) }}</pre>
        </el-collapse-item>
        <el-collapse-item v-if="detail.newValue" title="变更后已记录字段">
          <pre class="json-pre">{{ JSON.stringify(detail.newValue, null, 2) }}</pre>
        </el-collapse-item>
      </el-collapse>
    </el-dialog>
    <ActivityTimeline v-if="canViewActivity" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { operationLogApi, type OperationLog, type OperationLogFilters } from '@/api/dataCenter';
import ActivityTimeline from './ActivityTimeline.vue';

const actionLabels: Record<string, string> = { CREATE: '新增', UPDATE: '修改', DELETE: '删除', EXPORT: '导出', IMPORT: '导入', OTHER: '其他操作' };
const moduleLabels: Record<string, string> = { INVENTORY: '库存变更', FACTORY_API: '业务请求', MATERIAL: '原料', CUSTOMER: '客户', SUPPLIER: '供应商' };
const entityLabels: Record<string, string> = {
  MaterialBatch: '原料批次', FinishedGoodsBatch: '成品批次', InternalTransfer: '调拨单',
  InternalTransferItem: '调拨明细', FactoryMaterialRequisition: '领料单', FactoryMaterialRequisitionItem: '领料明细',
};
const fieldLabels: Record<string, string> = {
  recordId: '记录完整 ID', physicalQuantity: '账面实物数量',
  factoryId: '工厂 ID', batchNumber: '批次号', materialTypeId: '原料 ID', productTypeId: '产品 ID', warehouseId: '仓库 ID',
  receiptQuantity: '入库数量', usedQuantity: '已用数量', reservedQuantity: '预留数量', availableQuantity: '可用数量',
  producedQuantity: '生产入库数量', shippedQuantity: '已发货数量', quantityUnit: '数量单位', unit: '单位', status: '状态',
  ownership: '库存归属', ownerCustomerId: '货主客户 ID', sourceDocType: '来源单据类型', sourceDocId: '来源单据 ID',
  sourceSalesOrderId: '来源销售单 ID', sourceSalesOrderItemId: '来源销售明细 ID', receiptDate: '入库日期',
  productionDate: '生产日期', expireDate: '到期日期', supplierBatchNumber: '供应商批次号', deletedAt: '删除时间',
  transferNumber: '调拨单号', transferType: '调拨类型', sourceFactoryId: '调出工厂 ID', targetFactoryId: '调入工厂 ID',
  sourceWarehouseId: '调出仓库 ID', targetWarehouseId: '调入仓库 ID', productionPlanId: '生产计划 ID',
  requestedBy: '申请人 ID', requestedAt: '申请时间', approvedBy: '审批人 ID', approvedAt: '审批时间',
  shippedAt: '发货时间', receivedAt: '收货时间', confirmedAt: '确认时间', transferId: '调拨单 ID', itemType: '物料类型',
  quantity: '调拨数量', receivedQuantity: '实收数量', sourceBatchId: '来源批次 ID', targetBatchId: '目标批次 ID',
  materialPackagingSpecId: '包装规格 ID', packageQuantitySnapshot: '包装数量', packageUnitSnapshot: '包装单位',
  inventoryBaseUnitSnapshot: '库存基本单位', packageToBaseFactorSnapshot: '包装换算系数', requisitionNo: '领料单号',
  requiredDate: '需求日期', pickedBy: '领料人 ID', pickedAt: '领料时间', transferredBy: '调拨人 ID', transferredAt: '调拨时间',
  receivedBy: '收货人 ID', closedBy: '关单人 ID', closedAt: '关单时间', outboundTransferId: '出库调拨单 ID',
  returnTransferId: '退料调拨单 ID', requisitionId: '领料单 ID', bomRecipeItemId: '配方明细 ID', materialCategory: '物料类别',
  requiredQty: '需求数量', inputShareRatio: '投入份额', pickedQty: '已领数量', issuedQty: '已发数量',
  consumedQty: '消耗数量', wastageQty: '损耗数量', returnedQty: '退料数量', selfSupplied: '自备物料',
  nominalQuantity: '标称数量', counterpartyConfirmed: '对方已确认', reversalLogId: '撤回记录 ID',
};

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);
const canViewActivity = computed(() => !!authStore.factoryId &&
  ['factory_super_admin', 'permission_admin', 'super_admin', 'platform_admin', 'developer', 'platform_super_admin']
    .includes(authStore.currentRole));

const loading = ref(false);
const logs = ref<OperationLog[]>([]);
const total = ref(0);
const pageNum = ref(1);    // Element pagination is 1-based; backend is 0-based
const pageSize = ref(50);

const filters = ref<OperationLogFilters>({});
const dateRange = ref<[string, string] | null>(null);

const detailOpen = ref(false);
const detail = ref<OperationLog | null>(null);

async function load() {
  if (!factoryId.value) {
    ElMessage.warning('无 factoryId,无法加载');
    return;
  }
  loading.value = true;
  try {
    const params: OperationLogFilters = {
      ...filters.value,
      page: pageNum.value - 1,
      size: pageSize.value,
    };
    if (dateRange.value && dateRange.value.length === 2) {
      params.start = dateRange.value[0];
      params.end = dateRange.value[1];
    }
    const resp = await operationLogApi.list(factoryId.value, params);
    // request.ts interceptor returns full ApiResponse<T>; .data holds the Page payload.
    logs.value = resp.data?.content || [];
    total.value = resp.data?.totalElements || 0;
  } catch (err) {
    console.error('[OperationLog] load failed', err);
    ElMessage.error('加载操作日志失败');
  } finally {
    loading.value = false;
  }
}

function onSearch() {
  pageNum.value = 1;
  load();
}

function onReset() {
  filters.value = {};
  dateRange.value = null;
  pageNum.value = 1;
  load();
}

async function openDetail(row: OperationLog) {
  detail.value = row;
  detailOpen.value = true;
  // 若 diff/oldValue 没在 list 接口返回, 拉单条
  if (row.id && (row.oldValue === undefined || row.diff === undefined)) {
    try {
      const resp = await operationLogApi.get(factoryId.value, row.id);
      if (resp.data) detail.value = resp.data;
    } catch (err) {
      console.warn('[OperationLog] fetch detail failed, using list row', err);
    }
  }
}

function actionTagType(action: string): 'success' | 'warning' | 'danger' | 'info' | '' {
  switch (action) {
    case 'CREATE': return 'success';
    case 'UPDATE': return 'warning';
    case 'DELETE': return 'danger';
    case 'EXPORT':
    case 'IMPORT': return 'info';
    default: return '';
  }
}

function simpleName(fqn: string): string {
  const dot = fqn.lastIndexOf('.');
  return dot < 0 ? fqn : fqn.substring(dot + 1);
}

function formatTime(s: string | null): string {
  if (!s) return '—';
  return s.replace('T', ' ');
}

function formatValue(v: unknown): string {
  if (v === null || v === undefined) return '(null)';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}

onMounted(load);
</script>

<style scoped>
.operation-log-page { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.page-header h2 { margin: 0; font-size: 18px; }
.filter-card :deep(.el-form-item) { margin-bottom: 8px; }
.muted { color: #909399; }
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 24px; }
.detail-grid .span-2 { grid-column: span 2; }
.diff-val { background: #f5f7fa; padding: 2px 6px; border-radius: 3px; font-family: ui-monospace, monospace; font-size: 12px; }
.json-pre { background: #f5f7fa; padding: 12px; border-radius: 4px; max-height: 320px; overflow: auto; font-size: 12px; }
</style>
