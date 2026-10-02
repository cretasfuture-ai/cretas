<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { fetchSalespersonPerformance, type SalespersonReport } from '@/api/salesPreset';
import { money, pct, reportErrorText, monthToDateRange } from './reportFormat';

const data = ref<SalespersonReport | null>(null);
const loading = ref(false);
const errorText = ref('');
const dateRange = ref<[string, string]>(monthToDateRange());

async function load(): Promise<void> {
  loading.value = true;
  errorText.value = '';
  try {
    const [start, end] = dateRange.value ?? [undefined, undefined];
    const res = await fetchSalespersonPerformance(start, end);
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
  <div class="salesperson-report">
    <div class="header">
      <h2>业绩 by 业务员</h2>
      <div class="filter">
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
        />
        <el-button type="primary" :loading="loading" @click="load">查询</el-button>
      </div>
    </div>

    <el-alert v-if="errorText" :title="errorText" type="error" show-icon :closable="false" class="block" />

    <template v-if="data">
      <el-alert
        v-if="data.unassigned.orderCount > 0"
        type="warning"
        show-icon
        :closable="false"
        class="block"
        :title="`有 ${data.unassigned.orderCount} 张订单没有填写业务员(含税 ${money(data.unassigned.totalAmountWithTax)},占 ${pct(data.unassigned.share)}),未计入下面的排行`"
        description="在销售订单上填写业务员后,这部分业绩会自动归到对应业务员名下。"
      />

      <div class="kpi-grid">
        <el-card><div class="label">订单数</div><div class="value">{{ data.summary.orderCount }}</div></el-card>
        <el-card><div class="label">已填业务员的订单</div><div class="value">{{ data.summary.assignedOrderCount }}</div></el-card>
        <el-card><div class="label">含税销售额</div><div class="value primary">{{ money(data.summary.totalAmountWithTax) }}</div></el-card>
        <el-card><div class="label">业务员人数</div><div class="value">{{ data.rank.length }}</div></el-card>
      </div>

      <el-table v-loading="loading" :data="data.rank" stripe empty-text="所选期间没有填写业务员的订单">
        <el-table-column prop="rank" label="排名" width="70" />
        <el-table-column label="业务员" min-width="140">
          <template #default="{ row }">{{ row.salespersonName ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="orderCount" label="订单数" width="90" />
        <el-table-column prop="customerCount" label="客户数" width="90" />
        <el-table-column label="含税销售额" width="160">
          <template #default="{ row }">{{ money(row.totalAmountWithTax) }}</template>
        </el-table-column>
        <el-table-column label="未税金额" width="150">
          <template #default="{ row }">{{ money(row.taxableAmount) }}</template>
        </el-table-column>
        <el-table-column label="业绩占比" width="100">
          <template #default="{ row }">{{ pct(row.share) }}</template>
        </el-table-column>
        <el-table-column label="已收款" width="150">
          <template #default="{ row }">{{ money(row.paid) }}</template>
        </el-table-column>
        <el-table-column label="未收款" width="150">
          <template #default="{ row }"><span :class="{ warn: row.unpaid > 0 }">{{ money(row.unpaid) }}</span></template>
        </el-table-column>
      </el-table>
    </template>
  </div>
</template>

<style lang="scss" scoped>
.salesperson-report {
  padding: 16px;
  .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; h2 { margin: 0; } .filter { display: flex; gap: 12px; } }
  .block { margin-bottom: 16px; }
  .kpi-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 12px; margin-bottom: 16px; }
  .label { font-size: 12px; color: #909399; }
  .value { font-size: 20px; font-weight: 600; margin-top: 4px; &.primary { color: var(--el-color-primary); } }
  .warn { color: var(--el-color-warning); }
}
</style>
