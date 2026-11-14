<script setup lang="ts">
/**
 * 工单详情里的委外工序 (DECISIONS 第十九节: 委外并入生产主链)。
 * 数据来自 GET /outsource-orders/process-links?productionPlanId=, 只读; 发料 / 收货在委外单详情里做。
 * 没有委外工序时整块不显示 (大多数计划没有委外); 加载失败要显示失败, 不能当成「没有委外」。
 */
import { ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import {
  listOutsourceProcessLinks,
  LINE_STATUS_LABEL,
  ORDER_STATUS_LABEL,
  ORDER_STATUS_TAG,
  type OutsourceProcessLink,
} from '@/api/outsource';

const props = defineProps<{ factoryId: string; planId: string }>();
const router = useRouter();

const links = ref<OutsourceProcessLink[]>([]);
const loading = ref(false);
const error = ref('');

function qty(v: number | null) {
  return v == null ? '—' : String(v);
}
function money(v: number | null) {
  return v == null ? '—' : `¥${v.toFixed(2)}`;
}

async function load() {
  if (!props.factoryId || !props.planId) return;
  loading.value = true;
  error.value = '';
  try {
    const res = await listOutsourceProcessLinks(props.factoryId, props.planId);
    if (res.success) links.value = res.data ?? [];
    else error.value = res.message || '委外工序加载失败';
  } catch (e) {
    error.value = e instanceof Error && e.message ? e.message : '委外工序加载失败';
  } finally {
    loading.value = false;
  }
}

function openOrder(orderId: string) {
  router.push({ path: '/production/outsource', query: { orderId } });
}

watch(() => [props.factoryId, props.planId], load, { immediate: true });
</script>

<template>
  <div v-if="error || links.length" class="detail-section outsource-processes" data-testid="plan-outsource-processes">
    <div class="detail-section-title">委外工序</div>
    <p v-if="error" class="state error" data-testid="plan-outsource-error">
      {{ error }}（不代表没有委外工序）
      <el-button link type="primary" @click="load">重试</el-button>
    </p>
    <el-table v-else v-loading="loading" :data="links" size="small" border>
      <el-table-column label="委外单" min-width="140">
        <template #default="{ row }">
          <el-button link type="primary" data-testid="plan-outsource-open" @click="openOrder(row.orderId)">{{ row.orderNo }}</el-button>
        </template>
      </el-table-column>
      <el-table-column label="委外厂" min-width="120">
        <template #default="{ row }">{{ row.supplierName ?? row.supplierId }}</template>
      </el-table-column>
      <el-table-column label="单据状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="ORDER_STATUS_TAG[row.orderStatus as keyof typeof ORDER_STATUS_TAG]" disable-transitions>
            {{ ORDER_STATUS_LABEL[row.orderStatus as keyof typeof ORDER_STATUS_LABEL] ?? row.orderStatus }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="工序" width="80">
        <template #default="{ row }">{{ LINE_STATUS_LABEL[row.lineStatus as keyof typeof LINE_STATUS_LABEL] ?? row.lineStatus }}</template>
      </el-table-column>
      <el-table-column label="已收合格 / 计划" width="120">
        <template #default="{ row }">{{ qty(row.receivedGoodQuantity) }} / {{ qty(row.plannedQuantity) }}</template>
      </el-table-column>
      <el-table-column label="加工费" width="90" align="right">
        <template #default="{ row }">{{ money(row.feeAmount) }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.outsource-processes { margin-top: var(--cs-space-4); }
.state { margin: 0; font-size: var(--cs-font-small); color: var(--cs-muted); }
.state.error { color: var(--cs-danger); }
</style>
