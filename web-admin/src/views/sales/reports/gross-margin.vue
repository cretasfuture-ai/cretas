<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { fetchGrossMargin, type GrossMarginReport } from '@/api/salesPreset';
import { money, pct, reportErrorText, monthToDateRange } from './reportFormat';

const FORBIDDEN_TEXT = '销售毛利率报表含成本数据,需要价格查看权限(工厂超管 / 财务经理 / 销售经理 / 采购经理等),请联系管理员开通';

const data = ref<GrossMarginReport | null>(null);
const loading = ref(false);
const errorText = ref('');
const dateRange = ref<[string, string]>(monthToDateRange());
const order = ref<'desc' | 'asc'>('desc');

async function load(): Promise<void> {
  loading.value = true;
  errorText.value = '';
  try {
    const [start, end] = dateRange.value ?? [undefined, undefined];
    const res = await fetchGrossMargin(start, end, order.value, 50);
    data.value = res?.data ?? null;
  } catch (e) {
    data.value = null;
    errorText.value = reportErrorText(e, FORBIDDEN_TEXT);
  } finally {
    loading.value = false;
  }
}

function marginTagType(rate: number): 'success' | 'warning' | 'danger' {
  if (rate >= 0.3) return 'success';
  if (rate >= 0.1) return 'warning';
  return 'danger';
}

onMounted(load);
</script>

<template>
  <div class="gross-margin-report">
    <div class="header">
      <h2>销售毛利率 by 产品</h2>
      <div class="filter">
        <el-radio-group v-model="order" @change="load">
          <el-radio-button value="desc">高毛利在前</el-radio-button>
          <el-radio-button value="asc">低毛利在前</el-radio-button>
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
      毛利 = 折后未税收入 − 未税成本。成本取订单明细上回填的实际成本单价;没有回填成本的明细不计入毛利,「成本覆盖」列表示这个毛利率覆盖了多少收入。
    </p>

    <el-alert v-if="errorText" :title="errorText" type="error" show-icon :closable="false" class="block" />

    <template v-if="data">
      <el-alert
        v-if="data.summary.costCoverage !== null && data.summary.costCoverage < 1"
        type="warning"
        show-icon
        :closable="false"
        class="block"
        :title="`只有 ${pct(data.summary.costCoverage)} 的销售收入有成本数据(${data.summary.costedProductCount} / ${data.summary.productCount} 个产品),毛利率只按这部分计算`"
        description="成本在生产完工后回填到订单明细;还没完工或没走生产的订单没有成本。"
      />

      <div class="kpi-grid">
        <el-card><div class="label">折后未税收入</div><div class="value">{{ money(data.summary.revenue) }}</div></el-card>
        <el-card><div class="label">有成本的收入</div><div class="value">{{ money(data.summary.costedRevenue) }}</div></el-card>
        <el-card><div class="label">成本</div><div class="value">{{ money(data.summary.cost) }}</div></el-card>
        <el-card><div class="label">毛利</div><div class="value primary">{{ money(data.summary.grossProfit) }}</div></el-card>
        <el-card><div class="label">综合毛利率</div><div class="value primary">{{ pct(data.summary.marginRate) }}</div></el-card>
      </div>

      <el-table v-loading="loading" :data="data.rank" stripe empty-text="所选期间没有销售明细">
        <el-table-column prop="rank" label="排名" width="70" />
        <el-table-column label="产品" min-width="180">
          <template #default="{ row }">{{ row.productName ?? row.productTypeId }}</template>
        </el-table-column>
        <el-table-column label="销量" width="130">
          <template #default="{ row }">{{ row.totalQty.toLocaleString() }} {{ row.unit ?? '' }}</template>
        </el-table-column>
        <el-table-column label="折后未税收入" width="150">
          <template #default="{ row }">{{ money(row.revenue) }}</template>
        </el-table-column>
        <el-table-column label="成本" width="140">
          <template #default="{ row }">{{ money(row.cost) }}</template>
        </el-table-column>
        <el-table-column label="毛利" width="140">
          <template #default="{ row }">{{ money(row.grossProfit) }}</template>
        </el-table-column>
        <el-table-column label="毛利率" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.marginRate !== null" :type="marginTagType(row.marginRate)" size="small">{{ pct(row.marginRate) }}</el-tag>
            <el-tooltip v-else content="这个产品的订单明细还没有回填成本" placement="top">
              <span class="muted">无成本</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="成本覆盖" width="100">
          <template #default="{ row }">{{ pct(row.costCoverage) }}</template>
        </el-table-column>
      </el-table>
    </template>
  </div>
</template>

<style lang="scss" scoped>
.gross-margin-report {
  padding: 16px;
  .header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 8px; h2 { margin: 0; } .filter { display: flex; gap: 12px; flex-wrap: wrap; } }
  .hint { color: #909399; font-size: 12px; margin: 0 0 16px; }
  .block { margin-bottom: 16px; }
  .kpi-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: 12px; margin-bottom: 16px; }
  .label { font-size: 12px; color: #909399; }
  .value { font-size: 20px; font-weight: 600; margin-top: 4px; &.primary { color: var(--el-color-primary); } }
  .muted { color: #c0c4cc; font-size: 12px; }
}
</style>
