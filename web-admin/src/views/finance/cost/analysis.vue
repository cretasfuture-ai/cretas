<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);

const loading = ref(false);
const costData = ref<TableRow | null>(null);
const startDate = ref('');
const endDate = ref('');

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/reports/dashboard/overview`);
    if (response.success && response.data) {
      costData.value = response.data;
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

function fmt(v: unknown): string {
  // 没拿到数据显示「—」, 不显示 0
  if (v === null || v === undefined || v === '') return '—';
  const n = Number(v);
  return Number.isFinite(n) ? n.toLocaleString() : '—';
}

const statCards = computed(() => [
  { key: 'total', title: '本月总成本', value: fmt(costData.value?.totalCost), unit: '元', icon: 'i-lucide-wallet', cls: 'text-primary bg-primary/10' },
  { key: 'material', title: '原材料成本', value: fmt(costData.value?.materialCost), unit: '元', icon: 'i-lucide-file-text', cls: 'text-success bg-success/10' },
  { key: 'labor', title: '人工成本', value: fmt(costData.value?.laborCost), unit: '元', icon: 'i-lucide-trending-up', cls: 'text-warning bg-warning/10' },
]);
</script>

<template>
  <div class="flex flex-col gap-4 p-5" data-testid="cost-analysis-page">
    <section class="flex flex-wrap items-end gap-3 rounded-lg border border-default bg-default p-4">
      <UFormField label="开始日期">
        <UInput v-model="startDate" type="date" data-testid="cost-start-date" />
      </UFormField>
      <UFormField label="结束日期">
        <UInput v-model="endDate" type="date" data-testid="cost-end-date" />
      </UFormField>
      <UButton icon="i-lucide-search" :loading="loading" data-testid="cost-query" @click="loadData">查询</UButton>
    </section>

    <template v-if="canViewPrice">
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-3" data-testid="cost-stat-cards">
        <div v-for="card in statCards" :key="card.key" class="flex items-center justify-between rounded-lg border border-default bg-default p-4" :data-testid="`cost-card-${card.key}`">
          <div class="flex flex-col gap-2">
            <span class="text-sm text-muted">{{ card.title }}</span>
            <span class="text-2xl font-semibold" :class="card.cls.split(' ')[0]">{{ card.value }}<small v-if="card.value !== '—'" class="ml-1 text-sm font-normal">{{ card.unit }}</small></span>
          </div>
          <span class="flex size-12 items-center justify-center rounded-lg text-2xl" :class="card.cls"><UIcon :name="card.icon" /></span>
        </div>
      </div>

      <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <section v-for="t in ['成本趋势分析', '成本结构分析']" :key="t" class="rounded-lg border border-default bg-default">
          <header class="border-b border-default px-4 py-3 font-medium">{{ t }}</header>
          <div class="flex h-[300px] items-center justify-center text-muted">图表加载中...</div>
        </section>
      </div>
    </template>
    <div v-else class="py-12 text-center text-muted" data-testid="cost-no-permission">您没有查看价格/成本数据的权限</div>
  </div>
</template>
