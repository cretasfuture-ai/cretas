<script setup lang="ts">
import UButton from '@nuxt/ui/components/Button.vue';
import type { StockReconciliationRow } from '@/api/smartbi/blacklake-live';

const props = withDefaults(defineProps<{ rows: StockReconciliationRow[]; snapshotAt?: string | null }>(), { snapshotAt: null });
const money = (value: number | null, unit?: string | null) => value == null ? '未提供' : `${value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}${unit ? ` ${unit}` : ''}`;
const date = (value: string | null | undefined) => value ? new Date(value).toLocaleString('zh-CN', { timeZone:'Asia/Shanghai',month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }) : '未提供';
const statusText = (status: StockReconciliationRow['status']) => ({ confirmed: '已确认', needs_review: '待核对', resolved: '余额已恢复' }[status]);
const statusClass = (status: StockReconciliationRow['status']) => `status-${status}`;
</script>
<template>
  <section class="stock-reconciliation" aria-label="库存对账">
    <header class="section-heading"><div><h2>库存对账</h2><p>按物料和仓库核对盘点、期初、收发和期末证据。</p></div><span v-if="props.snapshotAt" class="snapshot">快照 {{ date(props.snapshotAt) }}</span></header>
    <div v-if="!props.rows.length" class="empty">暂无可核对的库存记录，数据到达后会显示在这里。</div>
    <article v-for="row in props.rows" v-else :key="row.key" class="material-card">
      <div class="material-top"><div><h3>{{ row.product || '未提供物料' }} <small>{{ row.productCode || '未提供编码' }}</small></h3><p class="warehouse">{{ row.warehouse || '未提供仓库' }} · {{ row.unit || '单位未提供' }}</p></div><span :class="['status', statusClass(row.status)]">{{ statusText(row.status) }}</span></div>
      <p class="conclusion">{{ row.conclusion || '暂无法形成结论' }}</p>
      <div class="evidence"><div><b>现有证据</b><span>当前账面余额 {{ money(row.quantity, row.unit) }} · 期初 {{ money(row.openingQuantity, row.unit) }} · 采集余额 {{ money(row.closingQuantity, row.unit) }}</span></div><div><b>收发覆盖</b><span>{{ row.movementCoverage || '未提供' }} · 入 {{ money(row.inbound, row.unit) }} · 出 {{ money(row.outbound, row.unit) }}</span></div><div><b>差额</b><span>{{ money(row.difference, row.unit) }}（核对于 {{ date(row.checkedAt) }}）</span></div></div>
      <p v-if="row.possibleReasons.length" class="reasons"><b>可能原因（尚未确认）</b>：{{ row.possibleReasons.join('、') }}</p>
      <p v-else class="reasons muted">暂无已记录的可能原因。</p>
      <div class="owner"><span><b>负责人</b> {{ row.owner || '未指定' }}</span><span><b>下一步</b> {{ row.action || '待补充' }}</span></div>
      <details v-if="row.movements.length" class="movements"><summary>查看收发明细（{{ row.movements.length }} 条）</summary><table><thead><tr><th>单号</th><th>方向</th><th>业务时间</th><th>数量</th><th>审核</th><th>计入</th></tr></thead><tbody><tr v-for="movement in row.movements" :key="movement.id"><td>{{ movement.orderNo || '未提供' }}</td><td>{{ movement.direction === 'in' ? '入库' : '出库' }}</td><td>{{ date(movement.bizAt) }}</td><td>{{ money(movement.quantity, movement.unit) }}</td><td>{{ movement.approval || '未提供' }}</td><td>{{ movement.counted ? '是' : '否' }}<span v-if="movement.reason">（{{ movement.reason }}）</span></td></tr></tbody></table></details>
      <p v-if="row.afterProvided" class="after">补齐后：{{ row.afterProvided }}</p>
    </article>
  </section>
</template>
<style scoped>
.stock-reconciliation{padding:20px;border:1px solid var(--c-line,#e5eaf0);border-radius:12px;background:var(--c-surface,#fff);color:var(--c-ink,#1a2332)}.section-heading,.material-top,.owner{display:flex;justify-content:space-between;gap:16px;align-items:flex-start}.section-heading h2{font-size:18px;margin:0 0 6px}.section-heading p,.warehouse,.conclusion,.reasons,.after{font-size:13px;line-height:1.65;margin:5px 0;color:#526276}.snapshot{font-size:12px;color:#738196;white-space:nowrap}.empty{padding:28px 0;text-align:center;color:#738196;font-size:13px}.material-card{padding:18px 0;border-top:1px solid #edf1f5}.material-top h3{margin:0;font-size:15px}.material-top h3 small{font-size:12px;color:#7b8798;font-weight:400;margin-left:6px}.status{font-size:12px;padding:4px 9px;border-radius:999px;white-space:nowrap}.status-confirmed{color:#176b45;background:#e7f5ed}.status-needs_review{color:#95620e;background:#fff4d8}.status-resolved{color:#345c91;background:#eaf1fb}.conclusion{color:#1e2d40;font-weight:500}.evidence{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin:14px 0}.evidence div{padding:10px;background:#f7f9fb;border-radius:7px}.evidence b,.owner b{display:block;color:#66758a;font-size:11px;margin-bottom:3px}.evidence span{font-size:13px}.reasons{color:#86570e}.muted{color:#7b8798}.owner{justify-content:flex-start;flex-wrap:wrap;border-top:1px solid #f0f2f5;padding-top:10px}.owner span{font-size:13px}.owner b{display:inline;margin-right:5px}.movements{margin-top:14px;font-size:13px}.movements summary{cursor:pointer;color:#1b65a8}.movements table{width:100%;border-collapse:collapse;margin-top:8px}.movements th,.movements td{text-align:left;padding:8px;border-bottom:1px solid #edf1f5;font-weight:400}.movements th{color:#66758a;font-size:12px}.after{margin-top:12px;color:#35634f}.after:first-letter{}.section-heading :deep(button){font-size:12px}@media(max-width:760px){.evidence{grid-template-columns:1fr}.snapshot{white-space:normal}}
</style>
