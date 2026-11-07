<template>
  <section class="readiness-card" data-testid="plan-concurrent-readiness">
    <div class="readiness-header">
      <div>
        <p class="eyebrow">生产计划协同核算</p>
        <h3>同期可用性建议</h3>
        <p class="subtitle">只读核算，不改变排程、库存或生产状态</p>
      </div>
      <button
        class="primary-button"
        type="button"
        :disabled="loading || !hasScope"
        data-testid="plan-concurrent-readiness-refresh"
        @click="refresh"
      >
        {{ loading ? '核算中…' : '核算同期可用性' }}
      </button>
    </div>

    <p v-if="!hasScope" class="state-message warning" data-testid="readiness-no-scope">
      缺少工厂或生产计划范围，暂不请求同期核算。
    </p>
    <div v-else-if="error" class="state-message error" data-testid="readiness-error">
      <span>{{ error }}</span>
      <button class="link-button" type="button" data-testid="readiness-retry" @click="refresh">重试</button>
    </div>
    <p v-else-if="!result && !loading" class="state-message" data-testid="readiness-idle">
      点击“核算同期可用性”后读取当前计划的只读建议。
    </p>
    <p v-else-if="loading" class="state-message" data-testid="readiness-loading">正在读取服务器核算结果…</p>

    <template v-if="result">
      <div class="summary-grid">
        <div><span>生产计划</span><strong>{{ result.planNumber }}</strong></div>
        <div><span>计划状态</span><strong>{{ planStatusText(result.planStatus) }}</strong></div>
        <div><span>同期计划</span><strong>{{ result.consideredPlanCount }}</strong></div>
        <div><span>需求截止</span><strong>{{ result.demandThroughDate || '未核验' }}</strong></div>
        <div><span>核算时间</span><strong>{{ result.observedAt }}</strong></div>
        <div><span>建议性质</span><strong>只读建议</strong></div>
      </div>

      <div v-if="result.notes.length" class="notes" data-testid="readiness-notes">
        <strong>说明</strong>
        <ul><li v-for="note in result.notes" :key="note">{{ note }}</li></ul>
      </div>

      <div class="section-heading">
        <div>
          <h4>物料需求与可用量</h4>
          <p>{{ materialSummary(result.material.reason) }}</p>
        </div>
        <span class="status-pill" :class="statusClass(result.material.status)">{{ statusText(result.material.status) }}</span>
      </div>
      <div class="range-line">
        {{ rangeText(result.material.lowerPercent, result.material.upperPercent) }}
      </div>
      <div v-if="result.material.items.length" class="material-table-wrap">
        <table class="material-table">
          <thead>
            <tr>
              <th>物料</th><th>单位</th><th>需求</th><th>正式耗用</th>
              <th>本计划预留</th><th>其他未占需求</th><th>净可用</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in result.material.items" :key="item.materialTypeId">
              <td>{{ item.materialName }}</td>
              <td>{{ item.unit }}</td>
              <td>{{ quantityText(item.required) }}</td>
              <td>{{ quantityText(item.consumed) }}</td>
              <td>{{ quantityText(item.ownHeldReserved) }}</td>
              <td>{{ quantityText(item.otherUnoccupied) }}</td>
              <td>{{ quantityText(item.stockFree) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-line">当前没有可核验的物料明细。</p>

      <div class="section-heading progress-heading">
        <div>
          <h4>成品入库进度</h4>
          <p>按已确认的成品入库量核算；不等同于物料匹配或报工实时完工。</p>
        </div>
        <span class="status-pill" :class="statusClass(result.progress.status)">{{ statusText(result.progress.status) }}</span>
      </div>
      <div class="progress-grid">
        <div><span>计划数量</span><strong>{{ quantityText(result.progress.plannedQuantity, result.progress.unit) }}</strong></div>
        <div><span>已入库</span><strong>{{ quantityText(result.progress.receivedQuantity, result.progress.unit) }}</strong></div>
        <div><span>剩余</span><strong>{{ quantityText(result.progress.remainingQuantity, result.progress.unit) }}</strong></div>
        <div><span>完成比例</span><strong>{{ percentText(result.progress.completionPercent) }}</strong></div>
      </div>
      <p v-if="result.progress.reason" class="progress-reason">{{ progressReasonText(result.progress.reason) }}</p>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  getPlanConcurrentReadiness,
  parsePlanConcurrentReadiness,
  type PlanConcurrentReadiness,
} from '@/api/planConcurrentReadiness'

const props = defineProps<{ factoryId: string; planId: string }>()

const loading = ref(false)
const error = ref<string | null>(null)
const result = ref<PlanConcurrentReadiness | null>(null)
let requestSequence = 0
let disposed = false

const hasScope = computed(() => Boolean(props.factoryId.trim() && props.planId.trim()))

watch(() => [props.factoryId, props.planId], () => {
  requestSequence += 1
  loading.value = false
  result.value = null
  error.value = null
})

function errorText(value: unknown): string {
  if (value instanceof Error && value.message) return value.message
  return '同期核算读取失败，请重试。'
}

async function refresh(): Promise<void> {
  if (!hasScope.value || loading.value) return
  const requestPlanId = props.planId.trim()
  const sequence = ++requestSequence
  loading.value = true
  error.value = null
  try {
    const response = await getPlanConcurrentReadiness(props.factoryId, props.planId)
    if (response?.success !== true) throw new Error('服务器未确认同期核算结果，请重试。')
    const parsed = parsePlanConcurrentReadiness(response?.data)
    if (parsed.planId !== requestPlanId) throw new Error('服务器返回了其他生产计划的核算结果，请重试。')
    if (disposed || sequence !== requestSequence) return
    result.value = parsed
  } catch (cause) {
    if (disposed || sequence !== requestSequence) return
    result.value = null
    error.value = errorText(cause)
  } finally {
    if (!disposed && sequence === requestSequence) loading.value = false
  }
}

function quantityText(value: number | null, unit?: string | null): string {
  if (value === null) return '未核验'
  return `${value}${unit ? ` ${unit}` : ''}`
}

function percentText(value: number | null): string {
  return value === null ? '未核验' : `${value}%`
}

function rangeText(lower: number | null, upper: number | null): string {
  if (lower === null || upper === null) return '先满足其他计划时/先满足本计划时：待核验'
  return `先满足其他计划时：${lower}%；先满足本计划时：${upper}%`
}

function planStatusText(status: string): string {
  const labels: Record<string, string> = {
    PLANNED: '已计划',
    PREPARED: '草稿',
    PENDING: '待排产',
    IN_PROGRESS: '进行中',
    PAUSED: '暂停',
    PENDING_APPROVAL: '撤回待审批',
    COMPLETED: '已完成',
    CANCELLED: '已取消',
  }
  return labels[status] || '状态待核验'
}

function statusText(status: string): string {
  return status === 'VERIFIED' ? '已核算' : '待核对'
}

function materialReasonText(reason: string): string {
  const labels: Record<string, string> = {
    OK: '物料来源和可用量已核验。',
    NO_REMAINING_STANDARD_DEMAND: '没有可确认的剩余标准需求。',
    NO_TARGET_DEMAND: '本计划不新增同期物料需求。',
    UNKNOWN_COVERAGE: '部分同期计划来源未核验，不能视为确定可用。',
    INVALID_FACT: '物料事实存在异常，暂不能确认可用量。',
    MISSING_STOCK: '库存来源缺失，暂不能确认可用量。',
    UNIT_CONFLICT: '物料单位不一致，暂不能确认可用量。',
  }
  return labels[reason] || '物料核算原因待核对。'
}

function materialSummary(reason: string): string {
  return materialReasonText(reason)
}

function progressReasonText(reason: string): string {
  const labels: Record<string, string> = {
    OK: '成品入库记录已核算。',
    UNKNOWN_COVERAGE: '成品入库来源待核对。',
    INVALID_FACT: '成品入库事实存在异常，暂不能确认进度。',
    MISSING_FINISHED_GOODS: '缺少已确认的成品入库来源。',
  }
  return labels[reason] || '成品入库进度原因待核对。'
}

function statusClass(status: string): string {
  return status === 'VERIFIED' ? 'success' : status === 'UNVERIFIED' ? 'warning' : 'neutral'
}

defineExpose({ refresh })
onBeforeUnmount(() => {
  disposed = true
  requestSequence += 1
})
</script>

<style lang="scss" scoped>
.readiness-card {
  padding: 20px;
  background: #fff;
  border: 1px solid #edf2f7;
  border-radius: 10px;
  box-shadow: 0 2px 12px rgba(27, 101, 168, 0.06);
  color: #1a2332;
}

.readiness-header, .section-heading, .progress-grid, .summary-grid {
  display: flex;
  gap: 16px;
}

.readiness-header, .section-heading { align-items: flex-start; justify-content: space-between; }
.readiness-header { margin-bottom: 20px; }
.eyebrow { margin: 0 0 4px; color: #1b65a8; font-size: 12px; font-weight: 600; }
h3, h4 { margin: 0; }
h3 { font-size: 18px; }
h4 { font-size: 15px; }
.subtitle, .section-heading p, .progress-reason { margin: 6px 0 0; color: #7a8599; font-size: 13px; }
.primary-button, .link-button { cursor: pointer; border: 0; font: inherit; }
.primary-button { padding: 9px 14px; border-radius: 6px; background: #1b65a8; color: #fff; }
.primary-button:disabled { cursor: not-allowed; opacity: .55; }
.link-button { margin-left: 12px; background: transparent; color: #1b65a8; }
.state-message { padding: 16px; color: #7a8599; background: #f4f6f9; border-radius: 6px; }
.state-message.warning { color: #8a5a00; background: #fff8e6; }
.state-message.error { color: #b42318; background: #fef0f0; }
.summary-grid { margin-bottom: 20px; flex-wrap: wrap; }
.summary-grid > div, .progress-grid > div { min-width: 150px; padding: 12px; background: #f4f6f9; border-radius: 6px; }
.summary-grid span, .progress-grid span { display: block; color: #7a8599; font-size: 12px; }
.summary-grid strong, .progress-grid strong { display: block; margin-top: 4px; font-size: 15px; }
.notes { margin-bottom: 20px; padding: 12px 16px; border-left: 3px solid #e6a23c; background: #fffaf0; }
.notes ul { margin: 6px 0 0; padding-left: 18px; }
.section-heading { margin-top: 20px; }
.status-pill { padding: 3px 9px; border-radius: 12px; background: #f4f6f9; color: #7a8599; font-size: 12px; }
.status-pill.success { background: #f0f9eb; color: #529b2e; }
.status-pill.warning { background: #fdf6ec; color: #b88230; }
.range-line, .empty-line { margin: 12px 0; color: #7a8599; font-size: 13px; }
.material-table-wrap { overflow-x: auto; }
.material-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.material-table th, .material-table td { padding: 10px 8px; border-bottom: 1px solid #edf2f7; text-align: left; white-space: nowrap; }
.material-table th { color: #7a8599; font-weight: 600; }
.progress-heading { margin-top: 24px; }
.progress-grid { flex-wrap: wrap; margin-top: 12px; }
.progress-reason { padding: 10px 12px; background: #f4f6f9; }
</style>
