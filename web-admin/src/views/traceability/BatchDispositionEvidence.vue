<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { getDispositionHistory, type DispositionHistoryRow } from '@/api/quality-disposition';
import { formatDateTime } from '@/utils/dateFormat';

const props = defineProps<{ factoryId?: string | null; batchId?: number | null }>();
const auth = useAuthStore(); const permission = usePermissionStore(); const router = useRouter();
const rows = ref<DispositionHistoryRow[]>([]); const loading = ref(false); const error = ref('');
const allowed = computed(() => permission.canAccess('quality') && Boolean(props.factoryId)
  && props.factoryId === auth.factoryId && Number.isSafeInteger(props.batchId) && Number(props.batchId) > 0);
let sequence = 0;
async function load() {
  const request = ++sequence; const actor = auth.user?.id; const factory = props.factoryId; const batch = props.batchId;
  rows.value = []; error.value = ''; loading.value = false;
  if (!allowed.value || !factory || batch == null) return;
  const current = () => request === sequence && allowed.value && factory === props.factoryId
    && batch === props.batchId && actor === auth.user?.id;
  loading.value = true;
  try {
    const history = await getDispositionHistory(factory, batch);
    if (!current()) return;
    if (history.some(row => row.batchId !== batch || !row.id || !row.inspectionId)) throw new Error('复核记录与当前批次不一致，请重新查询');
    rows.value = history.filter(row => row.requiresApproval === true);
  } catch (failure) {
    if (current()) error.value = failure instanceof Error ? failure.message : '复核历史读取失败';
  } finally { if (current()) loading.value = false; }
}
watch(() => [props.factoryId, props.batchId, auth.factoryId, auth.user?.id, allowed.value], load, { immediate: true });
onBeforeUnmount(() => { ++sequence; });
const actions: Record<string, string> = { RELEASE: '直接放行', CONDITIONAL_RELEASE: '条件放行', REWORK: '返工', SCRAP: '报废', HOLD: '待定', SPECIAL_APPROVAL: '特批' };
const statuses = { PENDING: '待审批', APPROVED: '已批准', REJECTED: '已拒绝' };
function openReview() {
  if (allowed.value) void router.push({ path: '/quality/disposition', query: { batchId: String(props.batchId) } });
}
</script>

<template>
  <section v-if="allowed" class="disposition-evidence" data-testid="disposition-evidence">
    <h3>异常复核与放行记录</h3>
    <p>原始检验结论与复核决定分别保留；请核对批准时间是否早于实际出货。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-else-if="loading">正在读取复核记录…</p>
    <el-table v-else-if="rows.length" :data="rows" size="small">
      <el-table-column label="处置动作" min-width="120"><template #default="{ row }">{{ actions[row.action] || row.actionDescription || '未注明动作' }}</template></el-table-column>
      <el-table-column label="审批状态" min-width="100"><template #default="{ row }">{{ statuses[row.approvalStatus as keyof typeof statuses] || '未注明状态' }}</template></el-table-column>
      <el-table-column prop="approverName" label="复核人" min-width="120" />
      <el-table-column label="审批时间" min-width="170"><template #default="{ row }">{{ formatDateTime(row.approvedAt) }}</template></el-table-column>
    </el-table>
    <p v-else>暂无复核申请记录</p>
    <el-button link type="primary" @click="openReview">查看本批次异常复核</el-button>
    <el-button v-if="error" link @click="load">重新读取</el-button>
  </section>
</template>

<style scoped>
.disposition-evidence { margin-top: 16px; }
h3 { margin: 0 0 8px; font-size: 14px; }
p { color: var(--el-text-color-secondary); font-size: 12px; }
</style>
