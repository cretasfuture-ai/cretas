<template>
  <section class="receipt" :class="receipt.kind">
    <div class="receipt-title">
      <span>{{ receipt.kind === 'field-card' ? 'Agent 处理回执' : '已发送给 Agent' }}</span>
      <strong>{{ receipt.title }}</strong>
    </div>
    <div v-if="receipt.kind === 'product-table'" class="receipt-processes">
      <div v-if="receipt.packaging" class="receipt-packaging">
        <small>销售规格与包装换算</small><strong>{{ receipt.packaging }}</strong>
      </div>
      <div v-for="row in receipt.rows" :key="row.sequence" class="receipt-process">
        <b>{{ String(row.sequence).padStart(2, '0') }}</b>
        <span>
          <strong>{{ row.name || '待确认成品' }}</strong>
          <small>{{ row.steps || '工序链待补充' }}</small>
          <small class="route-fact">{{ row.routeShape.join('、') || '路线形态待确认' }} · {{ row.routeDetails || '路线例外待补充' }}</small>
        </span>
      </div>
    </div>
    <div v-else-if="receipt.kind === 'process-table'" class="receipt-processes">
      <div v-for="row in receipt.rows" :key="row.sequence" class="receipt-process">
        <b>{{ String(row.sequence).padStart(2, '0') }}</b>
        <span><strong>{{ row.name || '待确认工序' }}</strong><small>{{ [row.inputMaterial, row.output].filter(Boolean).join(' → ') || '投入与产出待补充' }}</small></span>
      </div>
    </div>
    <dl v-else>
      <template v-for="entry in receipt.entries" :key="entry.label">
        <dt>{{ entry.label }}</dt><dd :class="entry.state ? `is-${entry.state}` : undefined">{{ entry.value }}</dd>
      </template>
    </dl>
  </section>
</template>

<script setup lang="ts">
import type { ConversationReceipt } from '../factoryConversationBlocks'

defineProps<{ receipt: ConversationReceipt }>()
</script>

<style scoped>
.receipt { min-width: min(520px, 70vw); overflow: hidden; border-radius: 10px; background: #15558e; color: #fff; }
.receipt-title { display: flex; flex-direction: column; gap: 3px; padding: 10px 12px; border-bottom: 1px solid rgba(255,255,255,.18); }
.receipt-title span { color: #cce1f3; font-size: 10px; font-weight: 800; letter-spacing: .06em; }
.receipt-title strong { font-size: 13px; }
dl { display: grid; grid-template-columns: minmax(110px, .8fr) minmax(140px, 1.2fr); margin: 0; }
dt, dd { margin: 0; padding: 8px 12px; border-top: 1px solid rgba(255,255,255,.12); font-size: 12px; }
dt { color: #cce1f3; }
dd { font-weight: 600; }
dd.is-accepted::before, dd.is-rejected::before, dd.is-unknown::before, dd.is-conflict::before { display: inline-block; margin-right: 6px; }
dd.is-accepted::before { content: '✓'; color: #9ee2ce; }
dd.is-rejected::before, dd.is-conflict::before { content: '!'; color: #ffd39b; }
dd.is-unknown::before { content: '—'; color: #cce1f3; }
.receipt-processes { padding: 6px 10px 10px; }
.receipt-process { display: grid; grid-template-columns: 28px 1fr; gap: 8px; align-items: start; padding: 7px 2px; border-top: 1px solid rgba(255,255,255,.12); }
.receipt-process:first-child { border-top: 0; }
.receipt-process > b { color: #9ec7e8; font-size: 10px; }
.receipt-process span, .receipt-process strong, .receipt-process small { display: block; }
.receipt-process strong { font-size: 12px; }
.receipt-process small { margin-top: 2px; color: #cce1f3; font-size: 10px; }
.receipt-process small.route-fact { color: #ffe0a6; }
.receipt-packaging { padding: 8px 2px; border-bottom: 1px solid rgba(255,255,255,.12); }
.receipt-packaging small, .receipt-packaging strong { display: block; }
.receipt-packaging small { color: #cce1f3; font-size: 10px; }
.receipt-packaging strong { margin-top: 3px; font-size: 12px; line-height: 1.45; }
@media (max-width: 560px) {
  .receipt { min-width: 0; width: calc(100vw - 76px); }
  dl { grid-template-columns: 1fr; }
  dt { padding-bottom: 2px; }
  dd { padding-top: 2px; border-top: 0; }
}
</style>
