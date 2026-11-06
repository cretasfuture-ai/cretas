<script setup lang="ts">
import { computed } from 'vue'
import { isSupplyImpactBoxExposure, type SupplyImpactBoxExposure, type SupplyImpactBoxSource, type SupplyImpactBoxTotals } from '@/api/supplyImpact'

const props = defineProps<{ exposure?: SupplyImpactBoxExposure | null }>()
const validExposure = computed(() => props.exposure && isSupplyImpactBoxExposure(props.exposure) ? props.exposure : null)
const invalidExposure = computed(() => props.exposure != null && !validExposure.value)

function totalLabel(totals: SupplyImpactBoxTotals): string {
  return totals.complete && totals.totalBoxQuantity !== null ? totals.totalBoxQuantity : '待核对'
}
function subtotalLabel(totals: SupplyImpactBoxTotals): string {
  return totals.knownBoxSubtotal ?? '—'
}
function sourceQuantity(source: SupplyImpactBoxSource): string {
  return source.sourceQuantity === null ? '未知' : `${source.sourceQuantity}${source.sourceUnit ? ` ${source.sourceUnit}` : ''}`
}
function boxQuantity(source: SupplyImpactBoxSource): string {
  return source.boxQuantity ?? '待核对'
}
</script>

<template>
  <section class="box-exposure" data-testid="impact-box-exposure" aria-labelledby="box-exposure-title">
    <div class="section-heading">
      <div>
        <h4 id="box-exposure-title">箱数折合暴露</h4>
        <p class="explanation">按不可变包装快照折合，仅表示已发现链路中的数量，不是现场封箱清点。</p>
      </div>
    </div>
    <div v-if="!validExposure" class="unknown-state" role="status">
      <strong>{{ invalidExposure ? '箱数折合结果无法核验' : '箱数折合暂待核对' }}</strong>
      <p>后端未提供可完整核验的包装快照与扣减链路，不补零、不使用当前箱规。</p>
    </div>
    <template v-else>
      <div class="scope-note">库存口径：在库实物含预留；折合值分别使用库存和发货记录中的包装快照。</div>
      <div class="totals-grid">
        <article class="total-card">
          <span>实物在库含预留</span>
          <strong data-testid="box-stock-total">{{ totalLabel(validExposure.stock) }}</strong>
          <small>已知来源小计：<b data-testid="box-known-subtotal">{{ subtotalLabel(validExposure.stock) }}</b></small>
          <small>已知 {{ validExposure.stock.knownSourceCount }} 个来源；未知 {{ validExposure.stock.unknownSourceCount }} 个来源</small>
        </article>
        <article class="total-card">
          <span>实际已发客户</span>
          <strong data-testid="box-shipped-total">{{ totalLabel(validExposure.shipped) }}</strong>
          <small>已知来源小计：<b data-testid="box-shipped-known-subtotal">{{ subtotalLabel(validExposure.shipped) }}</b></small>
          <small>已知 {{ validExposure.shipped.knownSourceCount }} 个来源；未知 {{ validExposure.shipped.unknownSourceCount }} 个来源</small>
        </article>
      </div>
      <div class="source-grid">
        <div>
          <h5>在库来源</h5>
          <table data-testid="box-stock-sources"><thead><tr><th>批次</th><th>原数量</th><th>包装快照</th><th>折合箱数</th></tr></thead><tbody>
            <tr v-for="source in validExposure.stockSources" :key="`stock-${source.sourceId}`"><td>{{ source.batchNumber || source.finishedGoodsBatchId }}</td><td>{{ sourceQuantity(source) }}</td><td>{{ source.packagingUnit || '未知' }} / {{ source.packagingBaseUnit || '未知' }} × {{ source.packagingFactor || '未知' }}</td><td>{{ boxQuantity(source) }}<small v-if="source.gaps.length">；{{ source.gaps.join('、') }}</small></td></tr>
            <tr v-if="!validExposure.stockSources.length"><td colspan="4">没有可展示的在库来源</td></tr>
          </tbody></table>
        </div>
        <div>
          <h5>发货来源</h5>
          <table data-testid="box-shipment-sources"><thead><tr><th>发货记录</th><th>客户</th><th>原数量</th><th>包装快照</th><th>折合箱数</th></tr></thead><tbody>
            <tr v-for="source in validExposure.shipmentSources" :key="`shipment-${source.sourceId}`"><td>{{ source.deliveryRecordId || source.deliveryItemId || source.sourceId }}</td><td>{{ source.customerName || source.customerId || '客户未知' }}</td><td>{{ sourceQuantity(source) }}</td><td>{{ source.packagingUnit || '未知' }} / {{ source.packagingBaseUnit || '未知' }} × {{ source.packagingFactor || '未知' }}</td><td>{{ boxQuantity(source) }}<small v-if="source.gaps.length">；{{ source.gaps.join('、') }}</small></td></tr>
            <tr v-if="!validExposure.shipmentSources.length"><td colspan="5">没有可展示的发货来源</td></tr>
          </tbody></table>
        </div>
      </div>
      <div v-if="validExposure.customers.length" class="customer-section">
        <h5>客户分布</h5>
        <table data-testid="box-customers"><thead><tr><th>客户</th><th>已发折合箱数</th><th>已知来源小计</th><th>未知来源</th></tr></thead><tbody>
          <tr v-for="customer in validExposure.customers" :key="customer.customerId || customer.customerName || 'unknown'"><td>{{ customer.customerName || customer.customerId || '客户未知' }}</td><td>{{ totalLabel(customer.totals) }}</td><td>{{ subtotalLabel(customer.totals) }}</td><td>{{ customer.totals.unknownSourceCount }}</td></tr>
        </tbody></table>
      </div>
      <div v-if="validExposure.gaps.length" class="gap-section" data-testid="box-exposure-gaps"><strong>数据缺口</strong><ul><li v-for="gap in validExposure.gaps" :key="`${gap.code}-${gap.message}`">{{ gap.message }}</li></ul></div>
    </template>
  </section>
</template>

<style scoped>
.box-exposure { margin: 18px 0; padding: 16px; border: 1px solid var(--el-border-color-lighter, #edf2f7); border-radius: 10px; background: var(--el-bg-color, #fff); }
.section-heading { display: flex; justify-content: space-between; }
h4, h5, p { margin: 0; }
h4 { color: var(--el-text-color-primary, #1a2332); }
h5 { margin: 14px 0 7px; color: var(--el-text-color-regular, #303133); }
.explanation, .scope-note, .unknown-state p, small { color: var(--el-text-color-secondary, #7a8599); }
.explanation, .scope-note { margin-top: 6px; font-size: 13px; line-height: 1.5; }
.unknown-state { margin-top: 12px; padding: 14px; border: 1px solid rgb(230 162 60 / 35%); border-radius: 7px; color: var(--el-color-warning, #b88230); }
.unknown-state p { margin-top: 6px; line-height: 1.5; }
.totals-grid, .source-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 12px; }
.source-grid > div { min-width: 0; overflow-x: auto; }
.total-card { display: grid; gap: 6px; padding: 13px; border: 1px solid var(--el-border-color-lighter, #edf2f7); border-radius: 7px; }
.total-card > span { color: var(--el-text-color-secondary, #7a8599); font-size: 13px; }
.total-card > strong { color: var(--el-color-primary, #1b65a8); font-size: 22px; }
.total-card small { font-size: 12px; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { padding: 8px; border-bottom: 1px solid var(--el-border-color-lighter, #edf2f7); text-align: left; vertical-align: top; }
th { color: var(--el-text-color-secondary, #7a8599); font-weight: 500; }
td small { display: block; margin-top: 4px; }
.customer-section, .gap-section { margin-top: 12px; }
.gap-section { padding: 12px; border-radius: 7px; background: var(--el-color-warning-light-9, #fdf6ec); color: var(--el-color-warning, #b88230); }
.gap-section ul { margin: 6px 0 0; padding-left: 18px; }
@media (max-width: 760px) { .totals-grid, .source-grid { grid-template-columns: 1fr; } }
</style>
