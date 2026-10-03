<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { reportErrorText } from './reportFormat';
import { fetchDailyReport, type DailyReport } from '@/api/salesPreset';
import { toDateString } from '@/utils/dateFormat';

const data = ref<DailyReport | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);
const selectedDate = ref<string>(toDateString(new Date()));

function money(value: number | null | undefined): string {
  return `¥ ${Number(value ?? 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const res = await fetchDailyReport(selectedDate.value);
    data.value = res?.data;
  } catch (e) {
    error.value = reportErrorText(e);
    data.value = null;
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="daily-report">
    <div class="header">
      <h2>销售额日报</h2>
      <div class="filter">
        <el-date-picker
          v-model="selectedDate"
          type="date"
          value-format="YYYY-MM-DD"
          @change="load"
        />
      </div>
    </div>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon style="margin-bottom: 16px" />

    <div v-loading="loading" class="kpi-grid" v-if="data">
      <el-card class="kpi">
        <div class="label">日期</div>
        <div class="value">{{ data.date }}</div>
      </el-card>
      <el-card class="kpi">
        <div class="label">订单数</div>
        <div class="value">{{ data.orderCount }}</div>
      </el-card>
      <el-card class="kpi primary">
        <div class="label">含税销售额</div>
        <div class="value">{{ money(data.totalAmountWithTax) }}</div>
      </el-card>
      <el-card class="kpi">
        <div class="label">未税金额</div>
        <div class="value">{{ money(data.taxableAmount) }}</div>
      </el-card>
      <el-card class="kpi">
        <div class="label">税额</div>
        <div class="value">{{ money(data.taxAmount) }}</div>
      </el-card>
      <el-card class="kpi success">
        <div class="label">已收款</div>
        <div class="value">{{ money(data.paid) }}</div>
      </el-card>
      <el-card class="kpi warning">
        <div class="label">未收款</div>
        <div class="value">{{ money(data.unpaid) }}</div>
      </el-card>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.daily-report {
  padding: 16px;
  .header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 24px;
    h2 { margin: 0; }
  }
  .kpi-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
    gap: 16px;
  }
  .kpi {
    .label { color: #909399; font-size: 13px; margin-bottom: 8px; }
    .value { font-size: 22px; font-weight: 600; }
    &.primary .value { color: #409eff; }
    &.success .value { color: #67c23a; }
    &.warning .value { color: #e6a23c; }
  }
}
</style>
