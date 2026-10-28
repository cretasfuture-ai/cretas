<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, ref, watch } from 'vue'
import { getFactoryReadiness, type FactoryReadinessReport, type FactoryRemedyKey } from '@/api/factoryReadiness'
import IndicatorApplicability from '@/components/indicators/IndicatorApplicability.vue'
import { useAuthStore } from '@/store/modules/auth'

type ReadinessLoader = (factoryId: string, blueprintId: string) => Promise<FactoryReadinessReport>
// The platform review host keeps the platform route; the factory's own host passes its own reader.
const props = defineProps<{
  factoryId: string; blueprintId: string; rowVersion: number; actorKey?: string; refreshKey?: number; loader?: ReadinessLoader
}>()
const report = ref<FactoryReadinessReport | null>(null)
const loading = ref(false)
const error = ref('')
const showAll = ref(false)
let sequence = 0
let alive = true
const context = () => JSON.stringify([props.factoryId, props.blueprintId, props.rowVersion, props.actorKey, props.refreshKey])
const visible = computed(() => report.value?.capabilities.filter(row => showAll.value || row.stage !== 'NOT_APPLICABLE') ?? [])
const stageLabel = { NOT_APPLICABLE: '本次不适用', BLOCKED: '需要调整', WAITING_CONFIGURATION: '待配置接通', WAITING_REVIEW: '待配置验收', CONFIGURATION_ACCEPTED: '配置已验收' }
const accepted = computed(() => report.value?.capabilities.filter(row => row.stage === 'CONFIGURATION_ACCEPTED').length ?? 0)
const stageState = { DONE: '已完成', PENDING: '未完成', UNKNOWN: '无法判断', NOT_APPLICABLE: '本次不适用' }
const domainState = { READY: '可以开展', MISSING: '还差资料', UNKNOWN: '无法判断', NOT_APPLICABLE: '本次不适用' }
const firstState = { COMPLETED: '已完成', RECORDED: '有单据，尚未完成', NONE: '尚无单据', UNKNOWN: '无法判断' }
const tagType = (state: string) => ['DONE', 'READY', 'MET', 'COMPLETED'].includes(state) ? 'success'
  : ['PENDING', 'MISSING'].includes(state) ? 'warning' : 'info'
// Existing maintenance pages; they read the signed-in account's own factory, not the reviewed one.
const remedyPath: Partial<Record<FactoryRemedyKey, string>> = {
  SUPPLIERS: '/procurement/suppliers', CUSTOMERS: '/sales/customers', MATERIAL_TYPES: '/warehouse/material-types',
  PRODUCTS: '/system/products', PRODUCT_PROCESSES: '/system/product-processes',
}
// Default warehouses have no page of their own: they sit in a sub-tab of the canvas editor, which a
// plain link cannot open. Name the exact steps and the field instead of a link that lands elsewhere.
const warehouseField: Record<string, string> = {
  PURCHASE_INBOUND_WAREHOUSE: '采购入库默认仓', WORKSHOP_WAREHOUSE: '生产成品 / 报工消耗默认仓',
  PRODUCTION_RAW_WAREHOUSE: '生产领料默认仓', SALES_OUTBOUND_WAREHOUSE: '销售出货默认仓',
}
const warehouseSteps = (code: string) => `由这家工厂的管理员登录后，依次打开「Canvas 配置编辑器」→「工厂配置」→「仓库管理」，`
  + `在默认用途里设置「${warehouseField[code] ?? '对应的默认仓'}」并保存（工厂 ${props.factoryId}）`
const authStore = useAuthStore()
const ownFactory = computed(() => Boolean(props.factoryId) && authStore.factoryId === props.factoryId)
const missingChecks = (domain: NonNullable<FactoryReadinessReport['businessReadiness']>[number]) =>
  domain.checks.filter(check => check.state !== 'MET')

async function load() {
  const own = ++sequence; const identity = context()
  report.value = null; error.value = ''; loading.value = false
  if (!alive || !props.factoryId || !props.blueprintId) return
  const current = () => alive && own === sequence && identity === context()
  loading.value = true
  try {
    const next = await (props.loader ?? getFactoryReadiness)(props.factoryId, props.blueprintId)
    if (!current()) return
    if (next.schemaVersion !== 'factory-readiness/v1' || next.factoryId !== props.factoryId
      || next.blueprintId !== props.blueprintId || next.rowVersion !== props.rowVersion
      || next.businessWriteCount !== 0 || next.businessAcceptanceState !== 'UNVERIFIED') {
      throw new Error('工厂或配置版本已变化，请刷新建厂资料后重试')
    }
    report.value = next
  } catch (failure) {
    if (current()) error.value = failure instanceof Error ? failure.message : '建厂验收状态读取失败'
  } finally { if (current()) loading.value = false }
}
watch(context, load, { immediate: true })
onDeactivated(() => { alive = false; ++sequence; report.value = null; loading.value = false })
onActivated(() => { if (!alive) { alive = true; void load() } })
onBeforeUnmount(() => { alive = false; ++sequence })
</script>

<template>
  <el-card class="factory-readiness" shadow="never" data-testid="factory-readiness">
    <template #header><div class="readiness-header"><strong>建厂交付核对</strong><el-button :loading="loading" @click="load">刷新核对</el-button></div></template>
    <p>依次核对配置、正式工艺、指标适用范围，再由工厂人员验收日常业务。配置通过不会自动代替业务签署。</p>
    <el-skeleton v-if="loading" :rows="3" animated />
    <el-alert v-else-if="error" type="error" :closable="false" :title="error"><el-button @click="load">重试</el-button></el-alert>
    <template v-else-if="report">
      <el-alert type="info" :closable="false" title="本页只核对状态，不应用配置，也不修改历史单据"
        :description="report.appliedVersion.boundary" />
      <div class="version-summary">
        <span>当前候选：第{{ report.blueprintVersion }}版</span>
        <span>已应用：{{ report.appliedVersion.version == null ? '尚无绑定记录' : `第${report.appliedVersion.version}版` }}</span>
        <el-tag v-if="report.appliedVersion.candidateDiffers" type="warning">候选与运行版本分开保留</el-tag>
        <span>配置已验收 {{ accepted }} 项；日常业务签署待确认</span>
      </div>
      <h4>建厂阶段</h4>
      <p>每一段读各自的记录，前一段完成不代表后一段完成。</p>
      <el-alert v-if="!report.stages?.length" type="info" :closable="false" data-testid="stages-unavailable"
        title="服务端没有返回阶段状态，无法判断资料确认、发布与业务就绪" />
      <ol v-else class="stage-list" data-testid="readiness-stages">
        <li v-for="stage in report.stages" :key="stage.key">
          <span class="stage-label">{{ stage.label }}</span>
          <el-tag size="small" :type="tagType(stage.state)">{{ stageState[stage.state] }}</el-tag>
          <span class="stage-evidence">{{ stage.evidence }}</span>
        </li>
      </ol>
      <h4>能否开展采购、生产、发货</h4>
      <el-alert v-if="!report.businessReadiness?.length" type="info" :closable="false" data-testid="business-unavailable"
        title="服务端没有返回业务就绪状态，不能据此判断可以开工" />
      <template v-else>
        <el-alert v-if="!ownFactory" type="warning" :closable="false" show-icon class="cross-factory"
          title="补齐页面只显示登录账号所属工厂的数据"
          description="请由这家工厂的管理员登录后，在下面列出的页面补齐，保存后回到这里点「刷新核对」。" />
        <div v-for="domain in report.businessReadiness" :key="domain.domain" class="domain" :data-testid="`domain-${domain.domain}`">
          <div class="domain-head">
            <strong>{{ domain.label }}</strong>
            <el-tag size="small" :type="tagType(domain.state)">{{ domainState[domain.state] }}</el-tag>
            <span class="domain-basis">{{ domain.applicabilityBasis }}</span>
          </div>
          <ul v-if="domain.applicability === 'APPLICABLE' && missingChecks(domain).length" class="missing">
            <li v-for="check in missingChecks(domain)" :key="check.code">
              <span>{{ check.label }}：{{ check.state === 'UNKNOWN' ? '无法判断' : '缺少' }}（{{ check.detail }}）</span>
              <span v-if="check.remedyKey === 'WAREHOUSE_DEFAULTS'" class="remedy" data-testid="warehouse-steps">{{ warehouseSteps(check.code) }}</span>
              <router-link v-else-if="ownFactory && remedyPath[check.remedyKey]" :to="{ path: remedyPath[check.remedyKey]! }" class="remedy">去「{{ check.remedyLabel }}」补齐</router-link>
              <span v-else class="remedy">由工厂管理员在「{{ check.remedyLabel }}」补齐</span>
            </li>
          </ul>
          <p v-if="domain.applicability !== 'NOT_APPLICABLE'" class="domain-note">
            首笔业务：{{ firstState[domain.firstRecord.state] }}，{{ domain.firstRecord.detail }}
          </p>
          <p v-if="domain.applicability !== 'NOT_APPLICABLE' && domain.notChecked.length" class="domain-note">
            未检查：{{ domain.notChecked.join('；') }}
          </p>
        </div>
      </template>
      <el-switch v-model="showAll" active-text="同时显示本次不适用项" />
      <el-table :data="visible" size="small" empty-text="本次尚未选择配置能力">
        <el-table-column prop="label" label="能力" min-width="150" />
        <el-table-column label="配置验收状态" min-width="130"><template #default="{ row }"><el-tag :type="row.stage === 'BLOCKED' ? 'danger' : row.stage === 'CONFIGURATION_ACCEPTED' ? 'success' : 'info'">{{ stageLabel[row.stage as keyof typeof stageLabel] }}</el-tag></template></el-table-column>
        <el-table-column prop="receiptCount" label="配置回执" width="90" />
        <el-table-column prop="nextStep" label="下一步" min-width="270" />
      </el-table>
      <h4>当前已生效的工艺与BOM</h4>
      <p>这里读取现有运行绑定；新蓝图预览不会替换它们，也不代表本次候选已发布。</p>
      <el-table :data="report.currentRuntime" size="small" empty-text="尚无可核对的在用成品，请先补齐基础资料">
        <el-table-column prop="productName" label="成品" min-width="140" />
        <el-table-column label="工艺版本" width="100"><template #default="{ row }">{{ row.workflowVersion == null ? '待发布' : `第${row.workflowVersion}版` }}</template></el-table-column>
        <el-table-column label="BOM版本" width="100"><template #default="{ row }">{{ row.bomVersion == null ? '待发布' : `第${row.bomVersion}版` }}</template></el-table-column>
        <el-table-column prop="reason" label="运行核对" min-width="300" />
      </el-table>
      <h4>指标口径预评估</h4>
      <IndicatorApplicability :factory-id="factoryId" :provided-report="report.plannedIndicators" />
      <el-collapse><el-collapse-item title="查看当前运行配置的指标适用性" name="current"><IndicatorApplicability :factory-id="factoryId" :provided-report="report.currentIndicators" /></el-collapse-item></el-collapse>
    </template>
  </el-card>
</template>

<style scoped>
.factory-readiness { margin-top: 20px; }
.readiness-header, .version-summary { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.readiness-header { justify-content: space-between; }
.version-summary { margin: 16px 0; font-size: 13px; }
p { color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.6; }
h4 { margin: 20px 0 8px; }
.stage-list { margin: 0; padding-left: 20px; font-size: 13px; }
.stage-list li { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 4px 0; }
.stage-label { min-width: 110px; }
.stage-evidence, .domain-basis, .domain-note { color: var(--el-text-color-secondary); font-size: 12px; }
.cross-factory { margin-bottom: 12px; }
.domain { padding: 10px 0; border-top: 1px solid var(--el-border-color-lighter); }
.domain-head { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.missing { margin: 8px 0; padding-left: 20px; font-size: 13px; }
.missing li { margin: 4px 0; }
.remedy { margin-left: 8px; font-size: 13px; }
.domain-note { margin: 4px 0; }
@media (max-width: 600px) { .readiness-header { align-items: stretch; flex-direction: column; } }
</style>
