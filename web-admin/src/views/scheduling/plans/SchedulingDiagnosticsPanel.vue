<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { getSchedulingReadiness, type SchedulingReadiness } from '@/api/schedulingDiagnosticsApi';

const props = defineProps<{
  factoryId?: string;
  productionLineId?: string;
  batchId?: number;
  startTime?: string;
  endTime?: string;
  scheduleId?: string;
}>();

const loading = ref(false);
const readiness = ref<SchedulingReadiness | null>(null);
const errorMessage = ref('');
let requestSequence = 0;
const record = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value);
function validReadiness(value: unknown): value is SchedulingReadiness {
  if (!record(value) || !['INPUTS_VALID_UNCONFIRMED', 'INCOMPLETE', 'INFEASIBLE'].includes(String(value.status))
    || typeof value.readyForAutomation !== 'boolean' || typeof value.reason !== 'string'
    || !Array.isArray(value.missingInputs) || !value.missingInputs.every(field => typeof field === 'string')) return false;
  for (const resource of [value.productionLine, value.batch]) {
    if (!record(resource) || (resource.id != null && typeof resource.id !== 'string')
      || typeof resource.selected !== 'boolean' || typeof resource.confirmed !== 'boolean'
      || typeof resource.source !== 'string' || typeof resource.reason !== 'string') return false;
  }
  return record(value.timeWindow) && typeof value.timeWindow.valid === 'boolean' && typeof value.timeWindow.reason === 'string'
    && record(value.probability) && typeof value.probability.origin === 'string'
    && typeof value.probability.reason === 'string' && typeof value.probability.optimization === 'boolean';
}
const load = async () => {
  const sequence = ++requestSequence;
  readiness.value = null;
  if (!props.factoryId) { loading.value = false; errorMessage.value = '尚未选择工厂，无法检查排程输入'; return; }
  loading.value = true;
  errorMessage.value = '';
  try {
    const result = await getSchedulingReadiness(props.factoryId, {
      productionLineId: props.productionLineId,
      batchId: props.batchId,
      startTime: props.startTime,
      endTime: props.endTime,
      scheduleId: props.scheduleId,
    });
    if (sequence !== requestSequence) return;
    if (result.success && validReadiness(result.data)) readiness.value = result.data;
    else if (result.success) errorMessage.value = '输入检查响应格式无效，无法确认排程输入；请重试';
    else { readiness.value = null; errorMessage.value = result.message || '输入检查暂不可读，请检查网络后重试'; }
  } catch {
    if (sequence === requestSequence) { readiness.value = null; errorMessage.value = '输入检查请求失败，请检查网络后重试；未创建或调整排程'; }
  } finally {
    if (sequence === requestSequence) loading.value = false;
  }
};
watch(() => [props.factoryId, props.productionLineId, props.batchId, props.startTime, props.endTime, props.scheduleId], load, { immediate: true });
onBeforeUnmount(() => { requestSequence += 1; });

const statusLabel = (status: SchedulingReadiness['status']) => ({
  INPUTS_VALID_UNCONFIRMED: '输入完整，等待现场确认',
  INCOMPLETE: '缺少排程输入',
  INFEASIBLE: '时间窗口不可行',
}[status]);
const sourceLabels: Record<string, string> = {
  UNAVAILABLE: '尚未生成概率', BUSINESS_INPUT: '明确输入检查', BUSINESS_CONSTRAINT: '业务条件检查',
  LOCAL_ESTIMATE: '本地估算', REMOTE_MODEL_ESTIMATED_INPUTS: '远端估算（包含历史估算输入）',
};
const sourceLabel = (source: string) => sourceLabels[source] || '来源尚未确认';
const reasonLabels: Record<string, string> = {
  INPUTS_INCOMPLETE: '缺少明确的排程输入，未计算概率',
  INVALID_NUMERIC_INPUT: '排程数量或人数无效，未计算概率',
  TENANT_SCOPED_RESOURCE_MISSING: '产线或批次不属于当前工厂，未计算概率',
  VERIFIED_MODEL_FEATURES_UNAVAILABLE: '缺少已确认的人员、设备或产品特征，未计算概率',
  COMPLETED: '该任务已完成',
  DETERMINISTIC_IMPOSSIBLE_WINDOW: '任务尚未完成且结束时间已过',
};
const reasonLabel = (reason: string) => reasonLabels[reason] || reason;
const inputLabels: Record<string, string> = {
  productionLineId: '产线', batchId: '批次', timeWindow: '开始和结束时间',
  'scheduleId:diagnostic_inputs': '已有排程的可验证输入',
  'productionLineId:tenant_mismatch_or_missing': '当前工厂可用产线',
  'batchId:tenant_mismatch_or_missing': '当前工厂可用批次',
  'productionLineId:not_schedulable': '启用状态的产线',
  'batchId:not_schedulable': '计划中批次',
};
const inputLabel = (field: string) => inputLabels[field] || '待核对输入';
</script>

<template>
  <el-card class="scheduling-diagnostics-panel" shadow="never" v-loading="loading">
    <template #header><span>排程输入检查（只读，当前批次）</span></template>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" />
    <template v-else-if="readiness">
      <el-alert :title="`${statusLabel(readiness.status)}：${readiness.reason}`" :type="readiness.readyForAutomation ? 'success' : 'warning'" :closable="false" />
      <div class="diagnostic-row"><strong>产线</strong><span>{{ readiness.productionLine.id || '未选择' }} · {{ readiness.productionLine.reason }}</span></div>
      <div class="diagnostic-row"><strong>批次</strong><span>{{ readiness.batch.id || '未选择' }} · {{ readiness.batch.reason }}</span></div>
      <div class="diagnostic-row"><strong>概率来源</strong><span>{{ sourceLabel(readiness.probability.origin) }} · {{ reasonLabel(readiness.probability.reason) }}</span></div>
      <div v-if="readiness.missingInputs.length" class="missing-inputs">缺少：{{ readiness.missingInputs.map(inputLabel).join('、') }}</div>
    </template>
    <span v-else class="empty-state">尚未取得检查结果</span>
  </el-card>
</template>

<style scoped>
.diagnostic-row { display: flex; gap: 12px; margin-top: 10px; color: var(--el-text-color-regular); }
.diagnostic-row strong { min-width: 72px; color: var(--el-text-color-primary); }
.missing-inputs { margin-top: 10px; color: var(--el-color-warning); font-size: 13px; }
.empty-state { color: var(--el-text-color-secondary); }
</style>
