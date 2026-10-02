<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { fetchGrowthRate, type GrowthReport } from '@/api/salesPreset';
import { money, signedPct, rateClass, reportErrorText, currentYearMonth } from './reportFormat';

const data = ref<GrowthReport | null>(null);
const loading = ref(false);
const errorText = ref('');
const endMonth = ref<string>(currentYearMonth());
const months = ref(12);

async function load(): Promise<void> {
  loading.value = true;
  errorText.value = '';
  try {
    const res = await fetchGrowthRate(endMonth.value, months.value);
    data.value = res?.data ?? null;
  } catch (e) {
    data.value = null;
    errorText.value = reportErrorText(e);
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="growth-report">
    <div class="header">
      <h2>月度增长率</h2>
      <div class="filter">
        <span class="filter-label">截止月份</span>
        <el-date-picker v-model="endMonth" type="month" value-format="YYYY-MM" :clearable="false" />
        <span class="filter-label">最近</span>
        <el-select v-model="months" style="width: 110px">
          <el-option :value="6" label="6 个月" />
          <el-option :value="12" label="12 个月" />
          <el-option :value="24" label="24 个月" />
        </el-select>
        <el-button type="primary" :loading="loading" @click="load">查询</el-button>
      </div>
    </div>

    <p class="hint">
      按含税销售额计算。环比 = 比上个月;同比 = 比去年同月。对比月份没有销售时无法计算,显示「—」。当月未结束时数据不完整,标注「进行中」。
    </p>

    <el-alert v-if="errorText" :title="errorText" type="error" show-icon :closable="false" class="block" />

    <el-table v-if="data" v-loading="loading" :data="[...data.months].reverse()" stripe>
      <el-table-column label="月份" width="140">
        <template #default="{ row }">
          {{ row.yearMonth }}
          <el-tag v-if="row.isPartial" size="small" type="info">进行中</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="orderCount" label="订单数" width="90" />
      <el-table-column label="含税销售额" width="160">
        <template #default="{ row }">{{ money(row.totalAmountWithTax) }}</template>
      </el-table-column>
      <el-table-column label="上月" width="150">
        <template #default="{ row }">{{ money(row.prevMonthAmountWithTax) }}</template>
      </el-table-column>
      <el-table-column label="环比 (MoM)" width="120">
        <template #default="{ row }">
          <span :class="rateClass(row.momRate)">{{ signedPct(row.momRate) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="去年同月" width="150">
        <template #default="{ row }">{{ money(row.lastYearAmountWithTax) }}</template>
      </el-table-column>
      <el-table-column label="同比 (YoY)" width="120">
        <template #default="{ row }">
          <span :class="rateClass(row.yoyRate)">{{ signedPct(row.yoyRate) }}</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style lang="scss" scoped>
.growth-report {
  padding: 16px;
  .header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 8px; h2 { margin: 0; } .filter { display: flex; gap: 8px; align-items: center; } }
  .filter-label { font-size: 13px; color: #606266; }
  .hint { color: #909399; font-size: 12px; margin: 0 0 16px; }
  .block { margin-bottom: 16px; }
  .rate-up { color: var(--el-color-success); }
  .rate-down { color: var(--el-color-danger); }
}
</style>
