<template>
  <div class="p-4" data-testid="three-statements">
    <UTabs v-model="activeTab" :items="TAB_ITEMS" :content="false" />
    <div class="mt-4">
      <BalanceSheet v-if="activeTab === 'balance-sheet'" />
      <IncomeStatement v-else-if="activeTab === 'income-statement'" />
      <CashFlow v-else-if="activeTab === 'cash-flow'" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import BalanceSheet from './BalanceSheet.vue';
import IncomeStatement from './IncomeStatement.vue';
import CashFlow from './CashFlow.vue';

const route = useRoute();
const activeTab = ref('balance-sheet');
const TAB_ITEMS = [
  { label: '资产负债表', value: 'balance-sheet' },
  { label: '利润表', value: 'income-statement' },
  { label: '现金流量表', value: 'cash-flow' },
];
watch(() => route.query.tab, tab => {
  activeTab.value = typeof tab === 'string' && ['balance-sheet', 'income-statement', 'cash-flow'].includes(tab) ? tab : 'balance-sheet';
}, { immediate: true });
</script>
