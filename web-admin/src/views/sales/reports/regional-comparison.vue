<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { fetchRegionalComparison, type RegionalReport, type RegionLevel } from '@/api/salesPreset';
import { money, pct, signedPct, rateClass, reportErrorText, monthToDateRange } from './reportFormat';

const data = ref<RegionalReport | null>(null);
const loading = ref(false);
const errorText = ref('');
const dateRange = ref<[string, string]>(monthToDateRange());
const level = ref<RegionLevel>('province');

async function load(): Promise<void> {
  loading.value = true;
  errorText.value = '';
  try {
    const [start, end] = dateRange.value ?? [undefined, undefined];
    const res = await fetchRegionalComparison(start, end, level.value);
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
  <div class="regional-report">
    <div class="header">
      <h2>区域销售对比</h2>
      <div class="filter">
        <el-radio-group v-model="level" @change="load">
          <el-radio-button value="province">按省份</el-radio-button>
          <el-radio-button value="region">按大区</el-radio-button>
        </el-radio-group>
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

    <p class="hint">
      区域按收货地址解析:优先取订单收货地址,没有时取客户的收货地址 / 账单地址。日期范围最长 1 年,同比对照去年同期。
    </p>

    <el-alert v-if="errorText" :title="errorText" type="error" show-icon :closable="false" class="block" />

    <template v-if="data">
      <el-alert
        v-if="data.noAddress.orderCount > 0 || data.unrecognized.orderCount > 0"
        type="warning"
        show-icon
        :closable="false"
        class="block"
        :title="`已识别区域的销售额占 ${pct(data.summary.recognizedShare)},以下订单未计入区域排行`"
      >
        <div v-if="data.noAddress.orderCount > 0">
          没有地址:{{ data.noAddress.orderCount }} 张订单,含税 {{ money(data.noAddress.totalAmountWithTax) }}。在客户资料里补上收货地址即可。
        </div>
        <div v-if="data.unrecognized.orderCount > 0">
          地址识别不出省份:{{ data.unrecognized.orderCount }} 张订单,含税 {{ money(data.unrecognized.totalAmountWithTax) }}。地址以省或市开头(如「江苏省苏州市…」)即可识别。
        </div>
      </el-alert>

      <div class="kpi-grid">
        <el-card><div class="label">订单数</div><div class="value">{{ data.summary.orderCount }}</div></el-card>
        <el-card><div class="label">含税销售额</div><div class="value primary">{{ money(data.summary.totalAmountWithTax) }}</div></el-card>
        <el-card><div class="label">去年同期</div><div class="value small">{{ data.lastYearStartDate }} 至 {{ data.lastYearEndDate }}</div></el-card>
      </div>

      <el-table v-loading="loading" :data="data.regions" stripe empty-text="所选期间没有可识别区域的订单">
        <el-table-column prop="rank" label="排名" width="70" />
        <el-table-column prop="label" :label="level === 'region' ? '大区' : '省份'" min-width="140" />
        <el-table-column prop="orderCount" label="订单数" width="90" />
        <el-table-column prop="customerCount" label="客户数" width="90" />
        <el-table-column label="含税销售额" width="160">
          <template #default="{ row }">{{ money(row.totalAmountWithTax) }}</template>
        </el-table-column>
        <el-table-column label="占比" width="90">
          <template #default="{ row }">{{ pct(row.share) }}</template>
        </el-table-column>
        <el-table-column label="去年同期" width="160">
          <template #default="{ row }">{{ money(row.lastYearAmountWithTax) }}</template>
        </el-table-column>
        <el-table-column label="同比" width="110">
          <template #default="{ row }">
            <el-tooltip v-if="row.yoyRate === null" content="去年同期没有销售,无法计算同比" placement="top">
              <span class="muted">—</span>
            </el-tooltip>
            <span v-else :class="rateClass(row.yoyRate)">{{ signedPct(row.yoyRate) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </div>
</template>

<style lang="scss" scoped>
.regional-report {
  padding: 16px;
  .header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 8px; h2 { margin: 0; } .filter { display: flex; gap: 12px; flex-wrap: wrap; } }
  .hint { color: #909399; font-size: 12px; margin: 0 0 16px; }
  .block { margin-bottom: 16px; }
  .kpi-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 12px; margin-bottom: 16px; }
  .label { font-size: 12px; color: #909399; }
  .value { font-size: 20px; font-weight: 600; margin-top: 4px; &.primary { color: var(--el-color-primary); } &.small { font-size: 14px; } }
  .rate-up { color: var(--el-color-success); }
  .rate-down { color: var(--el-color-danger); }
  .muted { color: #c0c4cc; }
}
</style>
