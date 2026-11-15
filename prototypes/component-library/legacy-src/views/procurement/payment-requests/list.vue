<template>
  <div class="page-container">
    <div class="page-header">
      <h2 class="page-title">历史付款申请</h2>
      <p class="page-subtitle">仅用于查询迁移前的付款申请记录</p>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="采购付款申请旧入口已停用"
      description="现行流程为：仓库确认收货生成未清应付，财务在“应收应付”页面选择该未清项并执行付款核销。这里不会再创建、提交、审批或确认付款。"
      style="margin-bottom: 16px"
    />

    <el-card class="filter-card" shadow="never">
      <el-row :gutter="16" align="middle">
        <el-col :span="6">
          <el-select v-model="filterStatus" placeholder="状态" clearable style="width: 100%" @change="handleFilterChange">
            <el-option label="全部" value="" />
            <el-option label="待提交" value="PENDING" />
            <el-option label="财务审核中" value="FINANCE_REVIEW" />
            <el-option label="已批准" value="APPROVED" />
            <el-option label="已付款" value="PAID" />
            <el-option label="已拒绝" value="REJECTED" />
          </el-select>
        </el-col>
        <el-col :span="8">
          <el-input
            v-model="filterKeyword"
            placeholder="搜索供应商或申请单号"
            clearable
            @clear="handleFilterChange"
            @keyup.enter="handleFilterChange"
          />
        </el-col>
        <el-col :span="10">
          <el-button type="primary" @click="handleFilterChange">查询</el-button>
          <el-button @click="resetFilter">重置</el-button>
        </el-col>
      </el-row>
    </el-card>

    <el-card class="data-card" shadow="never">
      <el-table v-loading="loading" :data="tableData" stripe row-key="id" empty-text="暂无历史付款申请">
        <el-table-column label="申请单号" prop="requestNumber" width="170" />
        <el-table-column label="采购单号" prop="purchaseOrderNumber" width="170" />
        <el-table-column label="供应商" prop="supplierName" min-width="140" />
        <el-table-column label="申请金额" prop="amount" width="130" align="right">
          <template #default="{ row }">{{ row.amount == null ? '—' : formatAmount(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="状态" prop="status" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createdAt" width="180">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="备注" prop="remark" min-width="160" show-overflow-tooltip />
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="fetchData"
          @size-change="fetchData"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { get } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';

interface HistoricalPaymentRequest {
  id: string;
  requestNumber?: string;
  purchaseOrderNumber?: string;
  supplierName?: string;
  amount?: number | null;
  status?: string;
  createdAt?: string | null;
  remark?: string | null;
}

const authStore = useAuthStore();
const loading = ref(false);
const tableData = ref<HistoricalPaymentRequest[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);
const filterStatus = ref('');
const filterKeyword = ref('');

function statusLabel(status?: string): string {
  const labels: Record<string, string> = {
    PENDING: '待提交', FINANCE_REVIEW: '财务审核中', APPROVED: '已批准',
    PAID: '已付款', REJECTED: '已拒绝',
  };
  return status ? labels[status] ?? status : '—';
}

function statusTag(status?: string): '' | 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'PAID' || status === 'APPROVED') return 'success';
  if (status === 'PENDING' || status === 'FINANCE_REVIEW') return 'warning';
  if (status === 'REJECTED') return 'danger';
  return 'info';
}

function formatAmount(value: number): string {
  return `¥${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function formatDate(value?: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN') : '—';
}

async function fetchData() {
  if (!authStore.factoryId) return;
  loading.value = true;
  try {
    const params: Record<string, unknown> = { page: page.value - 1, size: pageSize.value };
    if (filterStatus.value) params.status = filterStatus.value;
    if (filterKeyword.value.trim()) params.keyword = filterKeyword.value.trim();
    const res = await get(`/${authStore.factoryId}/payment-requests`, { params });
    if (!res.success) {
      ElMessage.error(res.message || '历史付款申请加载失败');
      return;
    }
    const data = res.data as HistoricalPaymentRequest[]
      | { content?: HistoricalPaymentRequest[]; totalElements?: number }
      | null;
    tableData.value = Array.isArray(data) ? data : data?.content ?? [];
    total.value = Array.isArray(data) ? data.length : Number(data?.totalElements ?? tableData.value.length);
  } finally {
    loading.value = false;
  }
}

function handleFilterChange() {
  page.value = 1;
  void fetchData();
}

function resetFilter() {
  filterStatus.value = '';
  filterKeyword.value = '';
  handleFilterChange();
}

onMounted(fetchData);
</script>

<style scoped>
.page-container { padding: 20px; background: #f4f6f9; min-height: 100%; }
.page-header { margin-bottom: 16px; }
.page-title { margin: 0 0 6px; font-size: 20px; color: #303133; }
.page-subtitle { margin: 0; color: #909399; font-size: 14px; }
.filter-card { margin-bottom: 16px; }
.data-card { min-height: 360px; }
.pagination-wrap { display: flex; justify-content: flex-end; margin-top: 16px; }
</style>
