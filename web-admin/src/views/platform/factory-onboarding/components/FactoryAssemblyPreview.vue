<template>
  <section class="assembly-preview" aria-labelledby="assembly-preview-title">
    <div class="preview-heading">
      <div>
        <div class="eyebrow">装配预览 · 待你确认的建议</div>
        <h3 id="assembly-preview-title">先看全貌，再逐项看差异</h3>
        <p>下面是服务端持久化并回读的权威蓝图；配置应用与 Workflow 发布仍是两次独立人工动作。</p>
      </div>
      <div class="preview-kpis" aria-label="装配预览统计">
        <div><strong>{{ changes.length }}</strong><span>项改动</span></div>
        <div><strong>{{ readyCount }}</strong><span>项已就绪</span></div>
        <div :class="{ attention: blockedCount > 0 }"><strong>{{ blockedCount }}</strong><span>项被阻塞</span></div>
        <div><strong>{{ graphs.length }}</strong><span>条产品路线</span></div>
      </div>
    </div>

    <div
      class="blueprint-contract"
      :class="[`is-${blueprint.lifecycleState.toLowerCase()}`, { 'is-blocked': authoritativeBlockedCount > 0 }]"
    >
      <div class="blueprint-identity">
        <span class="blueprint-version">{{ blueprint.schemaVersion }} · 蓝图 v{{ blueprint.version }} · 行版本 {{ blueprint.rowVersion }}</span>
        <strong>{{ blueprint.factoryName }}</strong>
        <code>{{ blueprint.factoryId }}</code>
      </div>
      <div class="blueprint-status" role="status">
        <span class="status-dot" aria-hidden="true" />
        <div>
          <strong>{{ blueprintStatusLabel }}</strong>
          <p>{{ sourceLabel }} · {{ updatedLabel }}</p>
        </div>
      </div>
      <div class="blueprint-boundaries" aria-label="当前发布边界">
        <span>{{ configurationStateLabel }}</span>
        <span>人工验收：{{ acceptanceStateLabel }}</span>
        <span>{{ workflowStateLabel }}</span>
      </div>
      <p class="blueprint-note">作用域：{{ blueprint.scopeType }} / {{ blueprint.factoryId }}。来源、能力依赖、写入计划、验证和模拟都由服务端保存；本页不会用本地推导结果静默替代读取失败。</p>
    </div>

    <div
      class="catalog-contract"
      :class="`is-${catalogStatus.tone}`"
      role="status"
      aria-live="polite"
    >
      <div class="catalog-mark" aria-hidden="true">录</div>
      <div>
        <span>能力目录 · {{ blueprint.capabilityCatalogVersion }}</span>
        <strong>{{ catalogStatus.title }}</strong>
        <p>{{ catalogStatus.detail }}</p>
      </div>
      <code :title="blueprint.capabilityCatalogDigest">SHA-256 {{ blueprintDigest }}</code>
    </div>

    <div class="authority-reports" aria-label="权威蓝图验证与模拟状态">
      <article>
        <span>服务端验证</span>
        <strong>{{ blueprint.validation?.valid ? '通过' : blueprint.validation ? '有阻塞项' : '尚未执行' }}</strong>
        <p v-if="blueprint.validation">{{ blueprint.validation.blockingIssues.length }} 个阻塞项 · 业务写入 {{ blueprint.validation.businessWriteCount }}</p>
        <p v-else>先验证依赖，再允许零写模拟。</p>
      </article>
      <article>
        <span>零写配置预检</span>
        <strong>{{ blueprint.simulation?.safe ? '配置预检通过' : blueprint.simulation ? '存在阻断项' : '尚未执行' }}</strong>
        <p v-if="blueprint.simulation">影响 {{ blueprint.simulation.affectedModules.length }} 类 · 业务写入 {{ blueprint.simulation.businessWriteCount }} · Workflow 未仿真</p>
        <p v-else>预检复用真实应用校验；Workflow 引擎仿真仍需单独执行。</p>
      </article>
      <article>
        <span>最新操作回执</span>
        <strong>{{ blueprint.latestReceipt ? `${blueprint.latestReceipt.operationType} · ${blueprint.latestReceipt.status}` : '暂无' }}</strong>
        <p v-if="blueprint.latestReceipt">回执 {{ blueprint.latestReceipt.id }} · 业务写入 {{ blueprint.latestReceipt.businessWriteCount }}</p>
        <p v-else>每次验证、模拟、发布和应用都有独立回执。</p>
      </article>
      <article>
        <span>人工能力验收</span>
        <strong>{{ acceptanceStateLabel }}</strong>
        <p>配置技术回执与人工验收分开；Workflow 发布仍是下一道独立门禁。</p>
      </article>
    </div>

    <div class="capability-map" aria-labelledby="capability-title">
      <div class="section-title">
        <div>
          <h4 id="capability-title">平台能力装配图</h4>
          <p>能力可以组合，但依赖关系不会跳过；灰色项表示本次计划里没有配置，不代表系统不存在。</p>
        </div>
        <span class="fact-chip">{{ includedCapabilityCount }}/{{ capabilities.length }} 项纳入本次计划</span>
      </div>
      <div class="capability-grid">
        <article
          v-for="capability in capabilities"
          :key="capability.id"
          class="capability-card"
          :class="[capability.status.toLowerCase(), { included: capability.included, blocked: capability.status === 'BLOCKED' }]"
        >
          <div class="capability-state">{{ capabilityStateLabel(capability.status) }}</div>
          <strong>{{ capability.label }}</strong>
          <p>{{ capability.description }}</p>
          <span v-if="capability.missingDependencies.length" class="missing-dependency">缺少 {{ dependencyLabels(capability.missingDependencies) }}</span>
          <span v-else-if="capability.dependsOn.length">依赖已满足：{{ dependencyLabels(capability.dependsOn) }}</span>
          <span v-else>基础能力</span>
          <div class="capability-links">
            <span v-if="capability.configurationTypes.length">
              配置对象：{{ configurationTypeLabels(capability.configurationTypes) }}
            </span>
            <span v-if="capability.moduleSchemaCodes.length">
              关联模块：{{ moduleSchemaLabels(capability.moduleSchemaCodes) }}
            </span>
          </div>
          <div v-if="researchMetadataAvailable(capability)" class="research-contract">
            <div class="sector-profile-list" aria-label="适用行业与重点场景">
              <span
                v-for="profile in capability.sectorProfiles ?? []"
                :key="profile"
              >{{ sectorProfileLabel(profile) }}</span>
            </div>
            <el-popover
              placement="bottom-start"
              trigger="click"
              :width="360"
              :visible="openEvidenceCapabilityId === capability.id"
              @update:visible="setEvidencePopoverVisibility(capability.id, $event)"
            >
              <template #reference>
                <button
                  type="button"
                  class="evidence-button"
                  @keydown.esc.stop.prevent="closeEvidencePopover"
                >
                  查看依据与研究映射 · {{ capability.authorityRefs?.length ?? 0 }}/{{ capability.requirementCodes?.length ?? 0 }}
                </button>
              </template>
              <div class="evidence-popover">
                <strong>权威依据</strong>
                <ul>
                  <li v-for="reference in capability.authorityRefs ?? []" :key="reference">
                    {{ authorityReferenceLabel(reference) }}
                  </li>
                </ul>
                <strong>覆盖需求</strong>
                <ul>
                  <li v-for="requirement in capability.requirementCodes ?? []" :key="requirement">
                    {{ requirementLabel(requirement) }}
                  </li>
                </ul>
              </div>
            </el-popover>
          </div>
          <small>{{ capability.nextStep }}</small>
          <b v-if="capability.plannedCount && capability.id !== 'factory'">{{ capability.plannedCount }} 项</b>
        </article>
      </div>
    </div>

    <div v-if="bars.length" class="coverage" aria-labelledby="coverage-title">
      <div class="section-title">
        <div>
          <h4 id="coverage-title">装配覆盖图</h4>
          <p>{{ coverageTakeaway }}</p>
        </div>
        <span class="fact-chip">事实：服务端写入计划</span>
      </div>
      <div class="bar-list">
        <div v-for="bar in bars" :key="bar.group" class="bar-row">
          <span class="bar-label">{{ bar.group }}</span>
          <div class="bar-track" aria-hidden="true">
            <span class="bar-fill" :style="{ width: `${bar.percent}%` }" />
          </div>
          <strong>{{ bar.count }}</strong>
          <span class="bar-meta">新 {{ bar.create }} · 覆 {{ bar.overwrite }} · 跳 {{ bar.skip }}</span>
        </div>
      </div>
      <el-collapse class="accessible-data">
        <el-collapse-item title="查看装配覆盖表格" name="coverage-table">
          <el-table :data="bars" size="small" border>
            <el-table-column prop="group" label="类别" />
            <el-table-column prop="count" label="合计" width="76" />
            <el-table-column prop="create" label="新建" width="76" />
            <el-table-column prop="overwrite" label="覆盖" width="76" />
            <el-table-column prop="skip" label="跳过" width="76" />
          </el-table>
        </el-collapse-item>
      </el-collapse>
    </div>

    <div class="workflow-preview" aria-labelledby="workflow-title">
      <div class="section-title">
        <div>
          <h4 id="workflow-title">产品路线图</h4>
          <p v-if="graphs.length">节点、连线、分支、合流、返工和多终点由服务端保存并校验；发布前仍要在 Workflow 画布核对。</p>
          <p v-else>这次还没有形成可展示的产品工艺链。</p>
        </div>
        <span class="fact-chip">{{ hasPersistedExplicitRoutes ? '已保存路线契约' : '线性路线兼容视图' }}</span>
      </div>
      <div v-if="graphs.length" class="lane-list">
        <article v-for="graph in graphs" :key="graph.productCode" class="lane route-graph" :class="{ 'route-graph--invalid': !graph.validation.valid }">
          <div class="lane-product">
            <span>成品</span>
            <strong>{{ graph.productName }}</strong>
            <el-tag size="small" :type="graph.validation.valid ? 'success' : 'danger'" effect="plain">
              {{ graph.validation.valid ? '路线可达' : `${graph.validation.issues.length} 个结构问题` }}
            </el-tag>
          </div>
          <div class="route-graph-body">
            <div class="route-node-list" role="list" :aria-label="`${graph.productName} 路线节点`">
              <div v-for="(node, index) in graph.nodes" :key="node.id" class="process-node" role="listitem">
                <span>节点 {{ index + 1 }}</span>
                <strong>{{ node.label }}</strong>
                <small v-if="graph.entryNodeIds.includes(node.id)">起点</small>
                <small v-if="graph.terminalNodeIds.includes(node.id)">终点</small>
              </div>
            </div>
            <div class="route-edge-list" aria-label="路线连线">
              <span v-for="(edge, index) in graph.edges" :key="`${edge.fromNodeId}-${edge.toNodeId}-${index}`" class="route-edge" :class="`route-edge--${edge.type.toLowerCase()}`">
                {{ routeNodeLabel(graph, edge.fromNodeId) }} → {{ routeNodeLabel(graph, edge.toNodeId) }}
                <b>{{ routeEdgeLabel(edge.type) }}</b>
              </span>
            </div>
            <ul v-if="!graph.validation.valid" class="route-issues">
              <li v-for="issue in graph.validation.issues" :key="`${issue.code}-${issue.nodeIds.join('-')}`">
                {{ issue.message }}<span v-if="issue.nodeIds.length">：{{ issue.nodeIds.map(id => routeNodeLabel(graph, id)).join('、') }}</span>
              </li>
            </ul>
          </div>
        </article>
      </div>
    </div>

    <div class="publish-contract">
      <div class="publish-icon" aria-hidden="true">流</div>
      <div>
        <strong>Workflow 发布方式已经固定</strong>
        <p>本屏只确认配置；写入后先形成草稿与发布身份，人在画布核对节点、连线和 BOM，再单独执行“发布并生效此产品 Workflow”。</p>
      </div>
      <el-tag type="warning" effect="plain">此刻未发布</el-tag>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { FormalCapability, FormalExecution } from '@/api/factoryBlueprintActivationApi'
import { formalAcceptanceLabel, formalConfigurationLabel, formalWorkflowLabel } from '../factoryBlueprintFormalState'
import type {
  CanonicalCapabilityCatalog,
  CanonicalFactoryBlueprint,
  CanonicalRouteGraph,
} from '@/api/factoryOnboardingApi'
import {
  coverageBars,
} from '../factoryAssemblyPresentation'

const props = defineProps<{
  blueprint: CanonicalFactoryBlueprint
  catalog?: CanonicalCapabilityCatalog | null
  catalogError?: string
  formalExecutions?: Partial<Record<FormalCapability, FormalExecution>>
}>()

const openEvidenceCapabilityId = ref<string | null>(null)
const setEvidencePopoverVisibility = (capabilityId: string, visible: boolean) => {
  openEvidenceCapabilityId.value = visible ? capabilityId : null
}
const closeEvidencePopover = () => {
  openEvidenceCapabilityId.value = null
}

const blueprint = computed(() => props.blueprint)
const changes = computed(() => blueprint.value.plannedChanges ?? [])
const bars = computed(() => coverageBars(changes.value))
const graphs = computed<CanonicalRouteGraph[]>(() => {
  if (blueprint.value.routeGraphs?.length) return blueprint.value.routeGraphs
  return (blueprint.value.workflowLanes ?? []).map(lane => ({
    productCode: lane.productCode,
    productName: lane.productName,
    nodes: lane.processNames.map((label, index) => ({ id: `process-${index + 1}`, label })),
    edges: lane.processNames.slice(1).map((_, index) => ({
      fromNodeId: `process-${index + 1}`,
      toNodeId: `process-${index + 2}`,
      type: 'FORWARD' as const,
    })),
    entryNodeIds: lane.processNames.length ? ['process-1'] : [],
    terminalNodeIds: lane.processNames.length ? [`process-${lane.processNames.length}`] : [],
    validation: {
      valid: lane.processNames.length > 0,
      issues: [] as CanonicalRouteGraph['validation']['issues'],
    },
  }))
})
const hasPersistedExplicitRoutes = computed(() =>
  (blueprint.value.sourcePayload.suggestedData.productProcesses ?? [])
    .some((route) => Array.isArray(route.routeNodes) || Array.isArray(route.routeEdges)))
const routeNodeLabel = (graph: CanonicalRouteGraph, id: string) =>
  graph.nodes.find(node => node.id === id)?.label ?? id
const routeEdgeLabel = (type: CanonicalRouteGraph['edges'][number]['type']) => ({
  FORWARD: '顺序', BRANCH: '分支', MERGE: '合流', REWORK: '返工',
})[type]
const capabilities = computed(() => blueprint.value.capabilities ?? [])
const readyCount = computed(() => capabilities.value.filter((item) => item.status === 'READY').length)
const blockedCount = computed(() => capabilities.value.filter((item) => item.status === 'BLOCKED').length)
const authoritativeBlockedCount = computed(() => blueprint.value.validation?.blockingIssues.length ?? blockedCount.value)
const acceptanceStateLabel = computed(() => formalAcceptanceLabel(blueprint.value.acceptanceState, props.formalExecutions ?? {}))
const configurationStateLabel = computed(() => formalConfigurationLabel(blueprint.value.configurationState, props.formalExecutions ?? {}))
const workflowStateLabel = computed(() => formalWorkflowLabel(blueprint.value.workflowState, props.formalExecutions ?? {}))
const includedCapabilityCount = computed(() => capabilities.value.filter((item) => item.included).length)
const blueprintDigest = computed(() => blueprint.value.capabilityCatalogDigest?.slice(0, 12) || '未锁定')
const catalogStatus = computed(() => {
  if (props.catalogError) {
    return {
      tone: 'blocked',
      title: '当前目录读取失败，后续操作将被阻断',
      detail: props.catalogError,
    }
  }
  if (!props.catalog) {
    return {
      tone: 'waiting',
      title: '正在读取服务端目录',
      detail: '不会使用浏览器内置目录替代服务端真值。',
    }
  }
  if (props.catalog.catalogVersion !== blueprint.value.capabilityCatalogVersion
    || props.catalog.digest !== blueprint.value.capabilityCatalogDigest) {
    return {
      tone: 'blocked',
      title: '目录版本或摘要不一致，禁止继续',
      detail: `服务端当前为 ${props.catalog.catalogVersion} / ${props.catalog.digest.slice(0, 12)}，请刷新并核对。`,
    }
  }
  return {
    tone: 'ready',
    title: `已锁定 ${props.catalog.capabilityCount} 项不可变能力定义`,
    detail: `服务端目录已回读；依赖、配置对象与模块关联均来自 ${props.catalog.catalogVersion}。`,
  }
})
const dependencyLabels = (ids: string[]) => ids
  .map((id) => capabilities.value.find((item) => item.id === id)?.label ?? id)
  .join('、')
const capabilityStateLabel = (status: 'NOT_STARTED' | 'READY' | 'BLOCKED') => ({
  NOT_STARTED: '本次未配置',
  READY: '依赖已满足',
  BLOCKED: '等待补齐依赖',
})[status]
const configurationTypeLabelMap: Record<string, string> = {
  FACTORY_PROFILE: '工厂资料', PRODUCT_TYPE: '产品类型', RAW_MATERIAL_TYPE: '原辅料类型',
  WORK_PROCESS: '工序', PRODUCT_PROCESS_ROUTE: '产品工艺链', BOM_RECIPE: 'BOM 配方',
  WAREHOUSE: '仓库', STORAGE_AREA: '存放区', UNIT_CONVERSION: '单位换算',
  PACKAGING_SPEC: '包装规格', USER: '用户', ROLE_PERMISSION: '角色权限',
  FACTORY_MODULE_CONFIG: '模块开关', ALERT_RULE: '告警规则', METRIC_THRESHOLD: '指标阈值',
  FORM_TEMPLATE: '表单模板', ENCODING_RULE: '编码规则', WORKFLOW_DEFINITION: 'Workflow 定义',
  SECTOR_STANDARD_PROFILE: '行业标准基线', PRODUCT_LABEL_SPEC: '标签与保质期规格',
  MATERIAL_SPECIFICATION: '物料规格', APPROVED_SUPPLIER: '批准供应商',
  INCOMING_INSPECTION: '来料检验', PROCESS_FLOW_GRAPH: '分支工艺图',
  REWORK_GENEALOGY: '返工谱系', HAZARD_ANALYSIS: '危害分析', CONTROL_MEASURE: '控制措施',
  CRITICAL_CONTROL: '关键控制', MONITORING_PLAN: '监控计划', VERIFICATION_PLAN: '验证计划',
  ALLERGEN_MATRIX: '过敏原矩阵', LABEL_CONTROL: '标签控制', LINE_CLEARANCE: '换线清场',
  SANITATION_PROGRAM: '清洗消毒程序', CIP_RECIPE: 'CIP 配方', PRE_OPERATION_RELEASE: '开工前放行',
  ENVIRONMENTAL_MONITORING_PLAN: '环境监测计划', PATHOGEN_RESULT: '病原结果',
  HYGIENIC_ZONE_GRAPH: '卫生分区图', DIRECTIONAL_FLOW: '定向流', COLD_CHAIN_CONTROL: '冷链控制',
  EQUIPMENT_MASTER: '设备档案', MAINTENANCE_PLAN: '维护计划', CALIBRATION_PLAN: '校准计划',
  QUALITY_SPECIFICATION: '质量规格', SAMPLING_PLAN: '抽样计划', PRODUCT_RELEASE: '产品放行',
  DEVIATION_CASE: '偏差案例', HOLD_DISPOSITION: '隔离处置', CAPA: 'CAPA',
  LOT_GENEALOGY: '批次谱系', RECALL_PLAN: '召回计划', MOCK_RECALL: '模拟召回',
  COMPETENCY_RECORD: '胜任力记录', ELECTRONIC_APPROVAL: '电子审批', RECORD_RETENTION: '记录留存',
  AUDIT_TRAIL: '审计轨迹', ESCALATION_POLICY: '升级策略', CONTRACT_MANUFACTURE: '委托加工',
  CUSTOMER_SUPPLIED_MATERIAL: '客供料', EMERGENCY_PLAN: '应急计划',
  UTILITY_FAILURE_PLAN: '公用工程中断计划', FOOD_DEFENSE_PLAN: '食品防护计划',
}
const moduleSchemaLabelMap: Record<string, string> = {
  bom: 'BOM', warehouse: '仓储', hr_employee: '员工', equipment: '设备',
  quality_inspection: '质量检验',
}
const configurationTypeLabels = (types: string[]) => types
  .map((type) => configurationTypeLabelMap[type] ?? type)
  .join('、')
const moduleSchemaLabels = (codes: string[]) => codes
  .map((code) => moduleSchemaLabelMap[code] ?? code)
  .join('、')
const sectorProfileLabelMap: Record<string, string> = {
  ALL_FOOD: '食品工厂通用',
  CHILLED_RTE_MEAT: '冷藏卤味重点',
  BAKERY: '烘焙重点',
  BEVERAGE_SAUCE: '饮料/酱料重点',
  FROZEN_PREPARED_CENTRAL_KITCHEN: '速冻/中央厨房重点',
}
const authorityReferenceLabelMap: Record<string, string> = {
  S01_GB14881_2013: 'S01 · GB 14881-2013 食品生产通用卫生规范',
  S02_PRC_FOOD_SAFETY_LAW_RESEARCHED_2026_08_27: 'S02 · 中华人民共和国食品安全法（调研于 2026-08-27）',
  S03_PRC_IMPLEMENTATION_REGULATION_RESEARCHED_2026_08_27: 'S03 · 食品安全法实施条例（调研于 2026-08-27）',
  S04_GB8957_2016: 'S04 · GB 8957-2016 糕点、面包卫生规范',
  S04_GB12695_2016: 'S04 · GB 12695-2016 饮料卫生规范',
  S04_GB31646_2018: 'S04 · GB 31646-2018 速冻食品卫生规范',
  S05_GB19295_2011: 'S05 · GB 19295-2011 速冻面米制品问答',
  S06_CXC1_1969_REV2022: 'S06 · Codex CXC 1-1969（修订至 2022）',
  S07_US_21CFR117_CROSSCHECK: 'S07 · FDA 21 CFR Part 117（交叉核验）',
}
const requirementLabelMap: Record<string, string> = {
  'factory-scope-sector': '工厂身份、范围与行业适用性',
  'product-sku-packaging': '产品、SKU 与包装标识',
  'material-specifications': '原辅料、添加剂与包材规格',
  'supplier-incoming': '供应商批准与来料检验',
  'formula-bom-rework': '配方/BOM 版本与返工谱系',
  'process-flow-segregation': '分支工艺图与隔离流向',
  'hazard-analysis': '危害分析与控制措施判定',
  'critical-limits-monitoring': '关键限值、监控、纠偏与验证',
  'deviation-capa': '偏差、隔离、处置与 CAPA',
  'allergen-label-control': '过敏原交叉接触与标签控制',
  'sanitation-cip': '清洗消毒与 CIP',
  'environmental-pathogen-monitoring': '环境与病原监测',
  'hygienic-zones-flows': '卫生分区与人/物/气流',
  'warehouse-cold-chain': '仓储、储存与冷链',
  'equipment-maintenance-calibration': '设备、维护与校准',
  'quality-sampling-release': '质量检验、抽样、留样与放行',
  'traceability-recall': '追溯、质量平衡与召回',
  'roles-training-health-approval': '角色、培训、健康与电子审批',
  'records-retention-audit': '记录、留存与审计轨迹',
  'alerts-escalation': '告警与升级',
  'contract-customer-material': '委托加工与客供料',
  'emergency-defense-utility': '召回、应急、公用工程与食品防护',
  'workflow-human-publication': 'Workflow 分离与人工发布',
}
const researchMetadataAvailable = (capability: CanonicalFactoryBlueprint['capabilities'][number]) =>
  Boolean(capability.sectorProfiles?.length
    || capability.authorityRefs?.length
    || capability.requirementCodes?.length)
const sectorProfileLabel = (profile: string) => sectorProfileLabelMap[profile] ?? profile
const authorityReferenceLabel = (reference: string) => authorityReferenceLabelMap[reference] ?? reference
const requirementLabel = (requirement: string) => requirementLabelMap[requirement] ?? requirement
const blueprintStatusLabel = computed(() => ({
  DRAFT: blueprint.value.validation && !blueprint.value.validation.valid
    ? `草稿验证有 ${authoritativeBlockedCount.value} 项必须修正`
    : (blockedCount.value ? `草稿有 ${blockedCount.value} 项依赖待补齐` : '权威草稿已保存，尚未验证'),
  VALIDATED: '服务端验证通过',
  PUBLISHED: '不可变版本已发布，尚未应用',
  APPLIED: '蓝图配置已应用',
  SUPERSEDED: '此版本已被后续版本替代',
  ROLLED_BACK: '此版本已确认回滚',
})[blueprint.value.lifecycleState])
const sourceLabel = computed(() => blueprint.value.source === 'ONBOARDING_QUESTIONNAIRE_AGENT_SERVER_PLAN'
  ? '问卷 + Agent 对话 + 服务端写入计划'
  : blueprint.value.source)
const updatedLabel = computed(() => blueprint.value.updatedAt
  ? new Date(blueprint.value.updatedAt).toLocaleString('zh-CN', { hour12: false })
  : '更新时间未知')
const coverageTakeaway = computed(() => {
  if (!bars.value.length) return '还没有可统计的写入计划。'
  const first = bars.value[0]
  return `本次改动最多的是“${first.group}”，共 ${first.count} 项；下方仍需逐项核对新建、覆盖和跳过。`
})
</script>

<style scoped>
.assembly-preview { --preview-ease-out: cubic-bezier(0.23, 1, 0.32, 1); margin-bottom: 18px; border: 1px solid #d9e2ec; border-radius: 16px; background: #f8fafc; overflow: hidden; color: #172033; box-shadow: 0 2px 12px rgba(27, 101, 168, .06); }
.preview-heading { display: flex; justify-content: space-between; gap: 24px; padding: 22px 24px; background: linear-gradient(135deg, #11243f 0%, #183f5e 100%); color: white; animation: preview-enter 240ms var(--preview-ease-out) both; }
.eyebrow { margin-bottom: 6px; color: #8bd8c9; font-size: 12px; font-weight: 700; letter-spacing: .08em; }
.preview-heading h3 { margin: 0; font-size: 22px; }
.preview-heading p { margin: 8px 0 0; color: #c8d8e6; }
.preview-kpis { display: grid; grid-template-columns: repeat(4, minmax(72px, 1fr)); gap: 8px; min-width: 360px; }
.preview-kpis div { padding: 10px 12px; border: 1px solid rgba(255,255,255,.16); border-radius: 12px; background: rgba(255,255,255,.07); animation: preview-enter 220ms var(--preview-ease-out) both; }
.preview-kpis div:nth-child(2) { animation-delay: 40ms; }
.preview-kpis div:nth-child(3) { animation-delay: 80ms; }
.preview-kpis div:nth-child(4) { animation-delay: 120ms; }
.preview-kpis strong, .preview-kpis span { display: block; }
.preview-kpis strong { font-size: 24px; font-variant-numeric: tabular-nums; }
.preview-kpis span { margin-top: 3px; color: #c8d8e6; font-size: 12px; }
.preview-kpis .attention { border-color: rgba(255, 206, 119, .48); background: rgba(255, 186, 72, .12); }
.blueprint-contract { display: grid; grid-template-columns: minmax(220px, 1fr) minmax(260px, 1.15fr) auto; align-items: center; gap: 18px; margin: 18px 24px 0; padding: 16px; border: 1px solid #d7e1eb; border-radius: 12px; background: #fff; }
.blueprint-identity { min-width: 0; }
.blueprint-version { display: block; margin-bottom: 6px; color: #52657a; font-size: 11px; font-weight: 700; letter-spacing: .04em; }
.blueprint-identity strong { display: inline; font-size: 16px; }
.blueprint-identity code { margin-left: 8px; padding: 2px 6px; border-radius: 6px; background: #edf2f7; color: #536174; font-size: 11px; }
.blueprint-status { display: flex; align-items: flex-start; gap: 9px; }
.status-dot { flex: none; width: 9px; height: 9px; margin-top: 5px; border-radius: 999px; background: #2d8c7f; box-shadow: 0 0 0 4px #e7f5f1; }
.is-blocked .status-dot { background: #b7791f; box-shadow: 0 0 0 4px #fff3d8; }
.is-collecting .status-dot { background: #718096; box-shadow: 0 0 0 4px #edf2f7; }
.blueprint-status p { margin: 3px 0 0; color: #66758a; font-size: 12px; }
.blueprint-boundaries { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; }
.blueprint-boundaries span { padding: 5px 8px; border: 1px solid #efd39b; border-radius: 999px; background: #fffbef; color: #7a5514; font-size: 11px; font-weight: 700; }
.blueprint-note { grid-column: 1 / -1; margin: -3px 0 0; padding-top: 11px; border-top: 1px solid #edf2f7; color: #66758a; font-size: 12px; line-height: 1.55; }
.catalog-contract { display: grid; grid-template-columns: 36px minmax(0, 1fr) auto; align-items: center; gap: 12px; margin: 12px 24px 0; padding: 12px 14px; border: 1px solid #b8d8d1; border-radius: 12px; background: #f3fbf8; }
.catalog-contract.is-blocked { border-color: #e4c077; background: #fff9e9; }
.catalog-contract.is-waiting { border-color: #ccd8e3; background: #f7f9fb; }
.catalog-mark { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 9px; background: #dff2ed; color: #176a5d; font-weight: 800; }
.catalog-contract.is-blocked .catalog-mark { background: #ffebbd; color: #8b5b00; }
.catalog-contract span { display: block; color: #607286; font-size: 11px; font-weight: 700; }
.catalog-contract strong { display: block; margin-top: 3px; font-size: 14px; }
.catalog-contract p { margin: 3px 0 0; color: #617184; font-size: 11px; line-height: 1.45; }
.catalog-contract code { padding: 5px 8px; border-radius: 7px; background: rgba(255,255,255,.75); color: #4c6074; font-size: 11px; }
.authority-reports { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin: 12px 24px 0; }
.authority-reports article { padding: 13px 14px; border: 1px solid #dce5ed; border-radius: 12px; background: #fff; }
.authority-reports span { display: block; color: #607286; font-size: 11px; font-weight: 700; }
.authority-reports strong { display: block; margin-top: 5px; font-size: 14px; }
.authority-reports p { margin: 5px 0 0; color: #6b7788; font-size: 11px; line-height: 1.45; }
.capability-map, .coverage, .workflow-preview { padding: 20px 24px 4px; }
.section-title { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.section-title h4 { margin: 0; font-size: 16px; }
.section-title p { margin: 5px 0 0; color: #66758a; font-size: 13px; }
.fact-chip, .inference-chip { flex: none; padding: 5px 9px; border-radius: 999px; font-size: 12px; }
.fact-chip { background: #e7f5f1; color: #18705f; }
.inference-chip { background: #fff3d8; color: #8a5a00; }
.bar-list { display: grid; gap: 10px; }
.bar-row { display: grid; grid-template-columns: 112px minmax(120px, 1fr) 32px 158px; align-items: center; gap: 10px; font-size: 13px; }
.bar-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bar-track { height: 9px; border-radius: 999px; background: #e3eaf1; overflow: hidden; }
.bar-fill { display: block; height: 100%; border-radius: inherit; background: #2d8c7f; }
.bar-row strong { text-align: right; font-variant-numeric: tabular-nums; }
.bar-meta { color: #718096; font-variant-numeric: tabular-nums; }
.accessible-data { margin-top: 12px; }
.capability-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.capability-card { position: relative; min-height: 188px; padding: 13px; border: 1px solid #dce3ea; border-radius: 12px; background: #f1f4f7; color: #6c7887; animation: preview-enter 220ms var(--preview-ease-out) both; transition: transform 160ms var(--preview-ease-out), opacity 160ms var(--preview-ease-out); }
.capability-card:nth-child(2) { animation-delay: 35ms; }
.capability-card:nth-child(3) { animation-delay: 70ms; }
.capability-card:nth-child(4) { animation-delay: 105ms; }
.capability-card.included { border-color: #9fcfc5; background: #f1faf8; color: #173e37; box-shadow: inset 3px 0 #2d8c7f; }
.capability-card.blocked { border-color: #e8c980; background: #fffbef; color: #5f4519; box-shadow: inset 3px 0 #c58b28; }
.capability-state { margin-bottom: 8px; color: #7b8794; font-size: 11px; font-weight: 700; }
.capability-card.included .capability-state { color: #21776a; }
.capability-card.blocked .capability-state, .missing-dependency { color: #996515; }
.research-contract { display: grid; gap: 7px; margin: 9px 0; padding-top: 9px; border-top: 1px solid rgba(103, 128, 151, .18); }
.sector-profile-list { display: flex; flex-wrap: wrap; gap: 4px; }
.sector-profile-list span { padding: 3px 6px; border-radius: 999px; background: #e8f1f7; color: #3d607b; font-size: 10px; font-weight: 700; line-height: 1.35; }
.capability-card.included .sector-profile-list span { background: #dff2ed; color: #176a5d; }
.evidence-button { display: inline-flex; align-items: center; width: fit-content; min-height: 44px; max-width: 100%; padding: 8px 2px; border: 0; border-bottom: 1px dashed currentColor; background: transparent; color: #476981; cursor: pointer; font: inherit; font-size: 11px; line-height: 1.5; text-align: left; }
.evidence-button:focus-visible { outline: 2px solid #2d8c7f; outline-offset: 3px; border-radius: 2px; }
:global(.evidence-popover) { color: #24364a; font-size: 12px; line-height: 1.55; }
:global(.evidence-popover strong) { display: block; margin-top: 8px; }
:global(.evidence-popover strong:first-child) { margin-top: 0; }
:global(.evidence-popover ul) { margin: 5px 0 0; padding-left: 18px; }
.capability-card > strong { display: block; font-size: 14px; }
.capability-card p { min-height: 34px; margin: 5px 0 8px; font-size: 12px; line-height: 1.45; }
.capability-card span { font-size: 11px; }
.capability-links { display: grid; gap: 3px; margin-top: 7px; color: #53677c; }
.capability-links span { line-height: 1.35; }
.capability-card small { display: block; margin-top: 7px; color: #66758a; font-size: 11px; line-height: 1.4; }
.capability-card.blocked small { color: #7a5514; }
.capability-card b { position: absolute; right: 11px; bottom: 11px; color: #21776a; font-size: 12px; }
.lane-list { display: grid; gap: 12px; }
.lane { display: grid; grid-template-columns: 150px minmax(0, 1fr); gap: 14px; padding: 14px; border: 1px solid #dfe7ef; border-radius: 12px; background: white; }
.route-graph--invalid { border-color: #e9b5b5; background: #fffafa; }
.lane-product { padding-right: 14px; border-right: 1px solid #e5ebf1; }
.lane-product span, .lane-product strong { display: block; }
.lane-product span { color: #718096; font-size: 12px; }
.lane-product strong { margin-top: 5px; line-height: 1.45; }
.lane-product .el-tag { margin-top: 9px; }
.route-graph-body { min-width: 0; }
.route-node-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(116px, 1fr)); gap: 8px; }
.process-node { min-width: 0; padding: 9px 10px; border: 1px solid #b9d8d2; border-radius: 10px; background: #f1faf8; }
.process-node span { display: block; color: #2d8c7f; font-size: 11px; font-weight: 700; }
.process-node strong { display: block; margin-top: 3px; font-size: 13px; }
.process-node small { display: inline-block; margin: 6px 4px 0 0; padding: 2px 5px; border-radius: 999px; background: #dcecf8; color: #1b65a8; font-size: 9px; font-weight: 700; }
.route-edge-list { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.route-edge { padding: 5px 7px; border-radius: 7px; background: #edf2f7; color: #53677c; font-size: 10px; line-height: 1.4; }
.route-edge b { margin-left: 5px; color: #1b65a8; }
.route-edge--branch b { color: #8a5a00; }
.route-edge--merge b { color: #176a5d; }
.route-edge--rework { background: #fff3d8; }
.route-edge--rework b { color: #a45b13; }
.route-issues { margin: 10px 0 0; padding-left: 18px; color: #a13333; font-size: 11px; line-height: 1.5; }
.publish-contract { display: grid; grid-template-columns: 38px minmax(0, 1fr) auto; align-items: center; gap: 12px; margin: 18px 24px 22px; padding: 14px 16px; border: 1px solid #efd39b; border-radius: 12px; background: #fffbef; }
.publish-icon { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 10px; background: #ffe8b3; color: #8a5a00; font-weight: 800; }
.publish-contract p { margin: 4px 0 0; color: #6b5b35; font-size: 13px; line-height: 1.55; }
@keyframes preview-enter {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}
@media (hover: hover) and (pointer: fine) {
  .capability-card.included:hover { transform: translateY(-2px); }
}
@media (max-width: 900px) {
  .preview-heading { display: block; }
  .preview-kpis { margin-top: 18px; min-width: 0; }
  .blueprint-contract { grid-template-columns: 1fr 1fr; }
  .blueprint-boundaries { grid-column: 1 / -1; justify-content: flex-start; }
  .bar-row { grid-template-columns: 86px minmax(80px, 1fr) 28px; }
  .bar-meta { grid-column: 2 / 4; }
  .lane { grid-template-columns: 1fr; }
  .capability-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .lane-product { padding: 0 0 10px; border-right: 0; border-bottom: 1px solid #e5ebf1; }
  .publish-contract { grid-template-columns: 38px minmax(0, 1fr); }
  .publish-contract :deep(.el-tag) { grid-column: 2; justify-self: start; }
  .authority-reports { grid-template-columns: 1fr; }
  .catalog-contract { grid-template-columns: 36px minmax(0, 1fr); }
  .catalog-contract code { grid-column: 2; justify-self: start; }
}
@media (max-width: 520px) {
  .preview-heading, .capability-map, .coverage, .workflow-preview { padding-left: 16px; padding-right: 16px; }
  .preview-kpis { grid-template-columns: repeat(2, 1fr); }
  .preview-kpis div { padding: 8px; }
  .preview-kpis strong { font-size: 20px; }
  .publish-contract { margin-left: 16px; margin-right: 16px; }
  .blueprint-contract { display: block; margin-left: 16px; margin-right: 16px; }
  .authority-reports { margin-left: 16px; margin-right: 16px; }
  .catalog-contract { margin-left: 16px; margin-right: 16px; }
  .blueprint-status, .blueprint-boundaries { margin-top: 14px; }
  .blueprint-note { margin-top: 12px; }
  .capability-grid {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
  }
  .capability-card { min-height: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .preview-heading,
  .preview-kpis div,
  .capability-card { animation: preview-fade 120ms ease-out both; transform: none; }
  .capability-card { transition: opacity 120ms ease-out; }
  .capability-card.included:hover { transform: none; }
}
@keyframes preview-fade {
  from { opacity: 0; }
  to { opacity: 1; }
}
</style>
