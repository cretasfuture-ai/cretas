<template>
  <section class="detail-editor" aria-labelledby="detail-editor-title">
    <div class="editor-head">
      <div>
        <div class="eyebrow">细节配置 · 仍未写入</div>
        <h3 id="detail-editor-title">检查 Agent 推演出的配置细节</h3>
        <p>这里是可选的专业复核，不是日常建厂问卷。优先回到对话纠正业务事实；只有字段级调整才在这里修改，随后必须重新校验。</p>
      </div>
      <div class="editor-stats" aria-label="配置数量">
        <span>产品 {{ suggestedData.productTypes.length }}</span>
        <span>原料 {{ suggestedData.materialTypes.length }}</span>
        <span>工序 {{ suggestedData.workProcesses?.length ?? 0 }}</span>
        <span>存放区 {{ suggestedData.warehouses?.length ?? 0 }}</span>
      </div>
    </div>

    <el-collapse v-model="openPanels" class="config-panels">
      <el-collapse-item name="catalog">
        <template #title><PanelTitle index="01" title="产品、原料与包装" :count="catalogCount" /></template>
        <p class="panel-note">编码用于系统对账，名称和单位给业务人员看；一级包装单位与换算系数必须成对填写。</p>
        <h4>产品类型</h4>
        <el-table :data="suggestedData.productTypes" border size="small" class="edit-table">
          <el-table-column label="编码" min-width="140"><template #default="{ row }"><el-input v-model="row.code" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="名称" min-width="150"><template #default="{ row }"><el-input v-model="row.name" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="业务类别" min-width="170"><template #default="{ row }"><el-select v-model="row.productCategory" @change="changed"><el-option v-for="item in productCategoryOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></template></el-table-column>
          <el-table-column label="基本单位" width="110"><template #default="{ row }"><el-input v-model="row.unit" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="一级包装" width="110"><template #default="{ row }"><el-input v-model="row.level1Unit" placeholder="如 箱" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="每包装含量" width="130"><template #default="{ row }"><el-input-number v-model="row.boxConversionCoefficient" :min="0.000001" :controls="false" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="说明" min-width="170"><template #default="{ row }"><el-input v-model="row.description" @update:model-value="changed" /></template></el-table-column>
          <el-table-column width="64" fixed="right"><template #default="{ $index }"><el-button link type="danger" @click="remove(suggestedData.productTypes, $index)">删除</el-button></template></el-table-column>
        </el-table>
        <el-button class="add-button" plain @click="addProduct">+ 添加产品</el-button>

        <h4>原料与物料</h4>
        <el-table :data="suggestedData.materialTypes" border size="small" class="edit-table">
          <el-table-column label="编码" min-width="140"><template #default="{ row }"><el-input v-model="row.code" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="名称" min-width="150"><template #default="{ row }"><el-input v-model="row.name" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="单位" width="110"><template #default="{ row }"><el-input v-model="row.unit" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="L1 分类" width="130"><template #default="{ row }"><el-input v-model="row.category" placeholder="原料/辅料/包材" @update:model-value="() => { if (isPackagingCategory(row.category)) row.storageType = null; changed() }" /></template></el-table-column>
          <el-table-column label="储存类型" width="150"><template #default="{ row }"><el-select v-model="row.storageType" :disabled="isPackagingCategory(row.category)" :placeholder="isPackagingCategory(row.category) ? '包材不适用' : '请明确选择'" clearable @change="changed"><el-option label="新鲜" value="fresh" /><el-option label="冻货" value="frozen" /><el-option label="干货" value="dry" /><el-option label="常温" value="normal" /></el-select></template></el-table-column>
          <el-table-column label="说明" min-width="170"><template #default="{ row }"><el-input v-model="row.description" @update:model-value="changed" /></template></el-table-column>
          <el-table-column width="64" fixed="right"><template #default="{ $index }"><el-button link type="danger" @click="remove(suggestedData.materialTypes, $index)">删除</el-button></template></el-table-column>
        </el-table>
        <el-button class="add-button" plain @click="addMaterial">+ 添加物料</el-button>

        <h4>原料到成品转换</h4>
        <el-table :data="conversions" border size="small" class="edit-table">
          <el-table-column label="原料编码" min-width="150"><template #default="{ row }"><el-input v-model="row.fromCode" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="成品编码" min-width="150"><template #default="{ row }"><el-input v-model="row.toCode" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="出成率" width="130"><template #default="{ row }"><el-input-number v-model="row.rate" :min="0.000001" :controls="false" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="来源" min-width="170"><template #default="{ row }"><el-select v-model="row.provenance" @change="changed"><el-option label="Agent 推演（待确认）" value="AI_INFERRED" /><el-option label="用户明确确认" value="USER_CONFIRMED" /><el-option label="按净含量/产出公式计算" value="CALCULATED" /></el-select></template></el-table-column>
          <el-table-column label="备注/公式" min-width="180"><template #default="{ row }"><el-input v-model="row.note" @update:model-value="changed" /></template></el-table-column>
          <el-table-column width="64" fixed="right"><template #default="{ $index }"><el-button link type="danger" @click="remove(conversions, $index)">删除</el-button></template></el-table-column>
        </el-table>
        <el-button class="add-button" plain @click="addConversion">+ 添加转换</el-button>
      </el-collapse-item>

      <el-collapse-item name="process">
        <template #title><PanelTitle index="02" title="工序、配方与产品路线" :count="processCount" /></template>
        <p class="panel-note">这里确定每道工序的单位、出成区间和产出类型；辅料、包材、副产物与动态字段可在“完整配置”中逐字段编辑。</p>
        <el-table :data="workProcesses" border size="small" class="edit-table">
          <el-table-column label="工序" min-width="140"><template #default="{ row }"><el-input v-model="row.name" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="类别" width="110"><template #default="{ row }"><el-input v-model="row.category" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="顺序" width="90"><template #default="{ row }"><el-input-number v-model="row.sortOrder" :min="1" :controls="false" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="投入单位" width="110"><template #default="{ row }"><el-input v-model="row.unit" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="产出单位" width="110"><template #default="{ row }"><el-input v-model="row.outputUnit" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="出成率下限" width="125"><template #default="{ row }"><el-input-number v-model="row.yieldMin" :min="0" :controls="false" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="上限" width="110"><template #default="{ row }"><el-input-number v-model="row.yieldMax" :min="0" :controls="false" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="产出类型" min-width="155"><template #default="{ row }"><el-select v-model="row.outputKind" @update:model-value="changed"><el-option label="半成品" value="SEMI_FINISHED" /><el-option label="成品" value="FINISHED_GOOD" /></el-select></template></el-table-column>
          <el-table-column width="64" fixed="right"><template #default="{ $index }"><el-button link type="danger" @click="remove(workProcesses, $index)">删除</el-button></template></el-table-column>
        </el-table>
        <el-button class="add-button" plain @click="addProcess">+ 添加工序</el-button>

        <h4>每个产品经过哪些工序</h4>
        <p class="panel-note">顺序路线适合单链生产；复杂路线可以明确配置分支、合流、返工回路和多个放行终点。切换后仍只保存草稿，不会发布 Workflow。</p>
        <article v-for="(route, index) in productProcesses" :key="`${route.productCode}-${index}`" class="route-card">
          <div class="route-card-head">
            <el-input v-model="route.productCode" aria-label="产品编码" placeholder="产品编码" @update:model-value="changed" />
            <el-switch
              :model-value="hasExplicitRoute(route)"
              active-text="复杂路线"
              inactive-text="顺序路线"
              @update:model-value="setExplicitRoute(route, $event)"
            />
            <el-button link type="danger" @click="remove(productProcesses, index)">删除产品路线</el-button>
          </div>

          <el-select
            v-if="!hasExplicitRoute(route)"
            v-model="route.processNames"
            multiple
            filterable
            allow-create
            default-first-option
            class="wide-select"
            aria-label="按顺序选择工序"
            placeholder="按顺序选择或输入工序"
            @update:model-value="changed"
          >
            <el-option v-for="name in processNames" :key="name" :label="name" :value="name" />
          </el-select>

          <div v-else class="graph-editor">
            <div class="graph-editor-title">
              <div><strong>路线节点</strong><span>节点 ID 保存后保持稳定；名称对应工序。</span></div>
              <el-button plain size="small" @click="addRouteNode(route)">+ 添加节点</el-button>
            </div>
            <div v-for="(node, nodeIndex) in route.routeNodes ?? []" :key="`${node.id}-${nodeIndex}`" class="route-node-row">
              <el-input v-model="node.id" :aria-label="`节点 ${nodeIndex + 1} ID`" placeholder="稳定 ID，如 heat-1" @update:model-value="routeNodeChanged(route)" />
              <el-select v-model="node.label" filterable allow-create default-first-option :aria-label="`节点 ${nodeIndex + 1} 工序`" placeholder="选择或输入工序" @update:model-value="routeNodeChanged(route)">
                <el-option v-for="name in processNames" :key="name" :label="name" :value="name" />
              </el-select>
              <el-button link type="danger" @click="removeRouteNode(route, nodeIndex)">删除节点</el-button>
            </div>

            <div class="graph-editor-title graph-edge-title">
              <div><strong>节点连线</strong><span>返工必须使用“返工”类型，并保留能够结束生产的放行出口。</span></div>
              <el-button plain size="small" :disabled="(route.routeNodes?.length ?? 0) < 2" @click="addRouteEdge(route)">+ 添加连线</el-button>
            </div>
            <div v-for="(edge, edgeIndex) in route.routeEdges ?? []" :key="`${edge.fromNodeId}-${edge.toNodeId}-${edgeIndex}`" class="route-edge-row">
              <el-select v-model="edge.fromNodeId" :aria-label="`连线 ${edgeIndex + 1} 起点`" placeholder="起点" @update:model-value="changed">
                <el-option v-for="node in route.routeNodes ?? []" :key="node.id" :label="node.label || node.id" :value="node.id" />
              </el-select>
              <el-select v-model="edge.type" :aria-label="`连线 ${edgeIndex + 1} 类型`" placeholder="类型" @update:model-value="changed">
                <el-option v-for="option in routeEdgeTypeOptions" :key="option.value" :label="option.label" :value="option.value" />
              </el-select>
              <el-select v-model="edge.toNodeId" :aria-label="`连线 ${edgeIndex + 1} 终点`" placeholder="终点" @update:model-value="changed">
                <el-option v-for="node in route.routeNodes ?? []" :key="node.id" :label="node.label || node.id" :value="node.id" />
              </el-select>
              <el-button link type="danger" @click="removeRouteEdge(route, edgeIndex)">删除连线</el-button>
            </div>
          </div>
        </article>
        <el-button class="add-button" plain @click="addProductProcess">+ 添加产品路线</el-button>
      </el-collapse-item>

      <el-collapse-item name="safety">
        <template #title><PanelTitle index="03" title="HACCP 与关键控制点" :count="haccp.length" /></template>
        <p class="panel-note danger-note">食品安全限值不会自动补默认值。最小值、最大值和单位缺任一项，服务端都会明确拒绝或跳过。</p>
        <el-table :data="haccp" border size="small" class="edit-table">
          <el-table-column label="控制点" min-width="140"><template #default="{ row }"><el-input v-model="row.name" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="危害类型" min-width="140"><template #default="{ row }"><el-input v-model="row.hazardType" placeholder="BIOLOGICAL" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="下限" width="120"><template #default="{ row }"><el-input-number v-model="row.criticalLimitMin" :controls="false" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="上限" width="120"><template #default="{ row }"><el-input-number v-model="row.criticalLimitMax" :controls="false" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="单位" width="100"><template #default="{ row }"><el-input v-model="row.unit" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="监控方法" min-width="190"><template #default="{ row }"><el-input v-model="row.monitoringProcedure" @update:model-value="changed" /></template></el-table-column>
          <el-table-column width="64" fixed="right"><template #default="{ $index }"><el-button link type="danger" @click="remove(haccp, $index)">删除</el-button></template></el-table-column>
        </el-table>
        <el-button class="add-button" plain @click="addHaccp">+ 添加控制点</el-button>
      </el-collapse-item>

      <el-collapse-item name="warehouse">
        <template #title><PanelTitle index="04" title="存放区与物料编码" :count="warehouseCount" /></template>
        <h4>存放区</h4>
        <el-table :data="warehouses" border size="small" class="edit-table">
          <el-table-column label="编码" min-width="130"><template #default="{ row }"><el-input v-model="row.code" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="名称" min-width="150"><template #default="{ row }"><el-input v-model="row.name" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="类型" min-width="130"><template #default="{ row }"><el-input v-model="row.type" placeholder="RAW / WIP / FINISHED" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="备注" min-width="190"><template #default="{ row }"><el-input v-model="row.remarks" @update:model-value="changed" /></template></el-table-column>
          <el-table-column width="64" fixed="right"><template #default="{ $index }"><el-button link type="danger" @click="remove(warehouses, $index)">删除</el-button></template></el-table-column>
        </el-table>
        <el-button class="add-button" plain @click="addWarehouse">+ 添加存放区</el-button>

        <h4>物料编码 L1 分段</h4>
        <div v-for="(segment, index) in codeSegments" :key="`${segment.code}-${index}`" class="pair-row">
          <el-input v-model="segment.code" placeholder="段码" @update:model-value="changed" />
          <el-input v-model="segment.label" placeholder="名称，如 原料" @update:model-value="changed" />
          <el-button link type="danger" @click="remove(codeSegments, index)">删除</el-button>
        </div>
        <el-button class="add-button" plain @click="addCodeSegment">+ 添加编码分段</el-button>
      </el-collapse-item>

      <el-collapse-item name="platform">
        <template #title><PanelTitle index="05" title="模块、阈值、账号与指标" :count="platformCount" /></template>
        <div class="platform-grid">
          <div class="config-box">
            <h4>后端功能开关</h4>
            <p>直接控制工厂设置中的 enableXxx 功能位。</p>
            <label v-for="module in moduleRows" :key="module.key" class="switch-row">
              <span>{{ module.key }}</span>
              <el-switch :model-value="module.enabled" @update:model-value="setModule(module.key, $event)" />
            </label>
            <div class="pair-row compact"><el-input v-model="newModuleKey" placeholder="新增功能位 key" /><el-button plain :disabled="!newModuleKey.trim()" @click="addModule">添加</el-button></div>
          </div>
          <div class="config-box">
            <h4>登录与默认账号</h4>
            <p>只有明确打开时才会建默认账号；写入回执会列出实际账号。</p>
            <label class="switch-row"><span>创建默认角色账号</span><el-switch v-model="suggestedData.provisionDefaultUsers" @update:model-value="changed" /></label>
          </div>
        </div>

        <h4>工厂阈值</h4>
        <div v-for="(threshold, index) in thresholds" :key="`${threshold.key}-${index}`" class="pair-row">
          <el-input v-model="threshold.key" placeholder="服务端阈值 key" @update:model-value="changed" />
          <el-input v-model="threshold.value" placeholder="值" @update:model-value="changed" />
          <el-button link type="danger" @click="remove(thresholds, index)">删除</el-button>
        </div>
        <el-button class="add-button" plain @click="addThreshold">+ 添加阈值</el-button>

        <h4>前端模块裁剪</h4>
        <div v-for="(module, index) in moduleConfigs" :key="`${module.moduleId}-${index}`" class="module-row">
          <el-input v-model="module.moduleId" placeholder="模块 ID" @update:model-value="changed" />
          <el-input v-model="module.moduleName" placeholder="显示名" @update:model-value="changed" />
          <el-switch v-model="module.enabled" inline-prompt active-text="开" inactive-text="关" @update:model-value="changed" />
          <span class="json-summary">配置 JSON {{ Object.keys(module.config ?? {}).length }} 项</span>
          <el-button link type="danger" @click="remove(moduleConfigs, index)">删除</el-button>
        </div>
        <el-button class="add-button" plain @click="addModuleConfig">+ 添加模块配置</el-button>

        <h4>不用看的默认指标</h4>
        <el-select v-model="indicatorOptOut" multiple filterable allow-create default-first-option class="wide-select" placeholder="输入指标 code 后回车" @update:model-value="changed" />
      </el-collapse-item>

      <el-collapse-item name="alerts">
        <template #title><PanelTitle index="06" title="告警与异常指标" :count="alertCount" /></template>
        <p class="panel-note">告警类型和指标名必须来自服务端目录；这里允许最终调整是否启用和阈值。</p>
        <h4>事件告警</h4>
        <el-table :data="alerts" border size="small" class="edit-table">
          <el-table-column label="告警类型" min-width="180"><template #default="{ row }"><el-input v-model="row.alertType" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="启用" width="90"><template #default="{ row }"><el-switch v-model="row.enabled" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="金额阈值" min-width="140"><template #default="{ row }"><el-input-number v-model="row.amountThreshold" :min="0" :controls="false" @update:model-value="changed" /></template></el-table-column>
          <el-table-column width="64"><template #default="{ $index }"><el-button link type="danger" @click="remove(alerts, $index)">删除</el-button></template></el-table-column>
        </el-table>
        <h4>异常指标</h4>
        <el-table :data="metricWatches" border size="small" class="edit-table">
          <el-table-column label="指标名" min-width="180"><template #default="{ row }"><el-input v-model="row.metricName" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="启用" width="90"><template #default="{ row }"><el-switch v-model="row.enabled" @update:model-value="changed" /></template></el-table-column>
          <el-table-column label="阈值" min-width="140"><template #default="{ row }"><el-input-number v-model="row.threshold" :min="0" :controls="false" @update:model-value="changed" /></template></el-table-column>
          <el-table-column width="64"><template #default="{ $index }"><el-button link type="danger" @click="remove(metricWatches, $index)">删除</el-button></template></el-table-column>
        </el-table>
      </el-collapse-item>

      <el-collapse-item name="schemas">
        <template #title><PanelTitle index="07" title="业务表单模板" :count="schemas.length" /></template>
        <div v-for="(schema, index) in schemas" :key="`${schema.entityType}-${index}`" class="schema-row">
          <el-input v-model="schema.entityType" placeholder="实体类型" @update:model-value="changed" />
          <el-input v-model="schema.entityName" placeholder="表单名称" @update:model-value="changed" />
          <el-input v-model="schema.description" placeholder="用途说明" @update:model-value="changed" />
          <span>{{ schema.fields?.length ?? 0 }} 个字段</span>
          <el-button link type="danger" @click="remove(schemas, index)">删除</el-button>
        </div>
        <el-button class="add-button" plain @click="addSchema">+ 添加表单模板</el-button>
      </el-collapse-item>

      <el-collapse-item name="advanced" @click="prepareJson">
        <template #title><PanelTitle index="08" title="完整配置（专业模式）" label="覆盖全部字段" /></template>
        <el-alert type="warning" :closable="false" show-icon title="这里是最终提交结构" description="配方、动态字段、模块 config、表单 fields 等所有嵌套细节都能在这里修改。只有点击“应用完整配置”后才会替换上方内容；随后仍须通过服务端预览校验。" />
        <el-input v-model="jsonDraft" type="textarea" :rows="18" spellcheck="false" class="json-editor" @input="jsonError = ''" />
        <p v-if="jsonError" class="json-error">{{ jsonError }}</p>
        <div class="advanced-actions">
          <el-button @click="prepareJson">恢复为当前配置</el-button>
          <el-button type="primary" @click="applyJson">应用完整配置</el-button>
        </div>
      </el-collapse-item>
    </el-collapse>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type {
  AlertItem,
  CodeSegmentSuggestion,
  ConversionItem,
  EntitySchema,
  HaccpSuggestion,
  MetricWatchItem,
  ModuleConfigSuggestion,
  ProductProcessSuggestion,
  FactoryRouteEdgeSuggestion,
  SuggestedData,
  ThresholdSuggestion,
  WarehouseSuggestion,
  WorkProcessSuggestion,
} from '@/api/factoryOnboardingApi'
import PanelTitle from './FactoryAssemblyPanelTitle.vue'

const props = defineProps<{ suggestedData: SuggestedData; schemas: EntitySchema[] }>()
const emit = defineEmits<{
  changed: []
  replace: [payload: { schemas: EntitySchema[]; suggestedData: SuggestedData }]
}>()

const openPanels = ref<string[]>(['catalog'])
const newModuleKey = ref('')
const jsonDraft = ref('')
const jsonError = ref('')

const conversions = computed(() => props.suggestedData.conversionRates ?? (props.suggestedData.conversionRates = []))
const workProcesses = computed(() => props.suggestedData.workProcesses ?? (props.suggestedData.workProcesses = []))
const productProcesses = computed(() => props.suggestedData.productProcesses ?? (props.suggestedData.productProcesses = []))
const haccp = computed(() => props.suggestedData.haccpCheckpoints ?? (props.suggestedData.haccpCheckpoints = []))
const warehouses = computed(() => props.suggestedData.warehouses ?? (props.suggestedData.warehouses = []))
const codeSegments = computed(() => props.suggestedData.codeSegments ?? (props.suggestedData.codeSegments = []))
const thresholds = computed(() => props.suggestedData.thresholds ?? (props.suggestedData.thresholds = []))
const moduleConfigs = computed(() => props.suggestedData.moduleConfigs ?? (props.suggestedData.moduleConfigs = []))
const indicatorOptOut = computed({
  get: () => props.suggestedData.indicatorOptOut ?? (props.suggestedData.indicatorOptOut = []),
  set: (value: string[]) => { props.suggestedData.indicatorOptOut = value; changed() },
})
const alerts = computed(() => props.suggestedData.alerts ?? (props.suggestedData.alerts = []))
const metricWatches = computed(() => props.suggestedData.metricWatches ?? (props.suggestedData.metricWatches = []))
const moduleRows = computed(() => Object.entries(props.suggestedData.modules ?? {}).map(([key, enabled]) => ({ key, enabled })))
const processNames = computed(() => workProcesses.value.map((p) => p.name).filter(Boolean))

const catalogCount = computed(() => props.suggestedData.productTypes.length + props.suggestedData.materialTypes.length + conversions.value.length)
const processCount = computed(() => workProcesses.value.length + productProcesses.value.length)
const warehouseCount = computed(() => warehouses.value.length + codeSegments.value.length)
const platformCount = computed(() => moduleRows.value.length + thresholds.value.length + moduleConfigs.value.length + indicatorOptOut.value.length)
const alertCount = computed(() => alerts.value.length + metricWatches.value.length)

function changed() { emit('changed') }
// Display hint only; the server validates against MaterialMainCategory.
function isPackagingCategory(category?: string | null) {
  return ['包材', '包辅材', 'PACKAGING', 'PACKAGE'].includes((category ?? '').trim().toUpperCase())
}
function remove<T>(rows: T[], index: number) { rows.splice(index, 1); changed() }
const productCategoryOptions = [
  { value: 'FINISHED_PRODUCT', label: '成品' },
  { value: 'SEMI_FINISHED', label: '半成品' },
  { value: 'CONTRACT_MANUFACTURING', label: '代工成品' },
  { value: 'CUSTOMER_MATERIAL', label: '客供料成品' },
  { value: 'DISH', label: '菜品' },
  { value: 'COMBO', label: '套餐' },
]

function addProduct() { props.suggestedData.productTypes.push({ code: '', name: '', unit: '', productCategory: 'FINISHED_PRODUCT' }); changed() }
function addMaterial() { props.suggestedData.materialTypes.push({ code: '', name: '', unit: '' }); changed() }
function addConversion() { conversions.value.push({ fromCode: '', toCode: '', rate: null, provenance: 'USER_CONFIRMED' } satisfies ConversionItem); changed() }
function addProcess() { workProcesses.value.push({ name: '', sortOrder: workProcesses.value.length + 1, unit: '', outputUnit: '' } satisfies WorkProcessSuggestion); changed() }
function addProductProcess() { productProcesses.value.push({ productCode: '', processNames: [] } satisfies ProductProcessSuggestion); changed() }

const routeEdgeTypeOptions: Array<{ value: FactoryRouteEdgeSuggestion['type']; label: string }> = [
  { value: 'FORWARD', label: '顺序' },
  { value: 'BRANCH', label: '分支' },
  { value: 'MERGE', label: '合流' },
  { value: 'REWORK', label: '返工' },
]

function hasExplicitRoute(route: ProductProcessSuggestion): boolean {
  return Array.isArray(route.routeNodes) || Array.isArray(route.routeEdges)
}

function setExplicitRoute(route: ProductProcessSuggestion, enabled: boolean) {
  if (enabled) {
    const names = route.processNames.map((name) => name.trim()).filter(Boolean)
    route.routeNodes = names.map((label, index) => ({ id: `process-${index + 1}`, label }))
    route.routeEdges = names.slice(1).map((_, index) => ({
      fromNodeId: `process-${index + 1}`,
      toNodeId: `process-${index + 2}`,
      type: 'FORWARD',
    }))
  } else {
    syncRouteProcessNames(route)
    delete route.routeNodes
    delete route.routeEdges
  }
  changed()
}

function nextRouteNodeId(route: ProductProcessSuggestion): string {
  const used = new Set((route.routeNodes ?? []).map((node) => node.id))
  let index = used.size + 1
  while (used.has(`process-${index}`)) index += 1
  return `process-${index}`
}

function syncRouteProcessNames(route: ProductProcessSuggestion) {
  route.processNames = [...new Set((route.routeNodes ?? [])
    .map((node) => node.label.trim())
    .filter(Boolean))]
}

function routeNodeChanged(route: ProductProcessSuggestion) {
  syncRouteProcessNames(route)
  changed()
}

function addRouteNode(route: ProductProcessSuggestion) {
  const nodes = route.routeNodes ?? (route.routeNodes = [])
  nodes.push({ id: nextRouteNodeId(route), label: '' })
  route.routeEdges ??= []
  changed()
}

function removeRouteNode(route: ProductProcessSuggestion, index: number) {
  const nodes = route.routeNodes ?? []
  const [removed] = nodes.splice(index, 1)
  if (removed) {
    route.routeEdges = (route.routeEdges ?? []).filter((edge) =>
      edge.fromNodeId !== removed.id && edge.toNodeId !== removed.id)
  }
  syncRouteProcessNames(route)
  changed()
}

function addRouteEdge(route: ProductProcessSuggestion) {
  const nodes = route.routeNodes ?? []
  if (nodes.length < 2) return
  const edges = route.routeEdges ?? (route.routeEdges = [])
  edges.push({
    fromNodeId: nodes[0].id,
    toNodeId: nodes[1].id,
    type: 'FORWARD',
  })
  changed()
}

function removeRouteEdge(route: ProductProcessSuggestion, index: number) {
  route.routeEdges?.splice(index, 1)
  changed()
}
function addHaccp() { haccp.value.push({ name: '', hazardType: '', criticalLimitMin: null, criticalLimitMax: null, unit: '' } satisfies HaccpSuggestion); changed() }
function addWarehouse() { warehouses.value.push({ code: '', name: '', type: '' } satisfies WarehouseSuggestion); changed() }
function addCodeSegment() { codeSegments.value.push({ code: '', label: '' } satisfies CodeSegmentSuggestion); changed() }
function addThreshold() { thresholds.value.push({ key: '', value: '' } satisfies ThresholdSuggestion); changed() }
function addModuleConfig() { moduleConfigs.value.push({ moduleId: '', moduleName: '', enabled: true, config: {} } satisfies ModuleConfigSuggestion); changed() }
function addSchema() { props.schemas.push({ entityType: '', entityName: '', description: '', fields: [] }); changed() }

function setModule(key: string, value: boolean) {
  props.suggestedData.modules = { ...(props.suggestedData.modules ?? {}), [key]: value }
  changed()
}

function addModule() {
  const key = newModuleKey.value.trim()
  if (!key) return
  setModule(key, true)
  newModuleKey.value = ''
}

function prepareJson() {
  jsonDraft.value = JSON.stringify({ schemas: props.schemas, suggestedData: props.suggestedData }, null, 2)
  jsonError.value = ''
}

function applyJson() {
  try {
    const parsed = JSON.parse(jsonDraft.value) as { schemas?: unknown; suggestedData?: unknown }
    if (!Array.isArray(parsed.schemas) || !parsed.suggestedData || typeof parsed.suggestedData !== 'object') {
      throw new Error('顶层必须同时包含 schemas 数组和 suggestedData 对象')
    }
    const data = parsed.suggestedData as SuggestedData
    if (!Array.isArray(data.productTypes) || !Array.isArray(data.materialTypes) || !Array.isArray(data.conversionRates)) {
      throw new Error('suggestedData 必须包含 productTypes、materialTypes、conversionRates 三个数组')
    }
    emit('replace', { schemas: parsed.schemas as EntitySchema[], suggestedData: data })
    emit('changed')
  } catch (error) {
    jsonError.value = error instanceof Error ? error.message : 'JSON 格式不正确'
  }
}
</script>

<style scoped>
.detail-editor { margin: 18px 0; border: 1px solid #d8e2ec; border-radius: 10px; background: #fff; box-shadow: 0 2px 12px rgba(27,101,168,.06); overflow: hidden; }
.editor-head { display: flex; justify-content: space-between; gap: 24px; padding: 20px 24px; border-bottom: 1px solid #edf2f7; background: #f7fafc; }
.eyebrow { margin-bottom: 5px; color: #1b65a8; font-size: 12px; font-weight: 700; letter-spacing: .06em; }
.editor-head h3 { margin: 0; color: #1a2332; font-size: 18px; }
.editor-head p { max-width: 780px; margin: 7px 0 0; color: #617086; line-height: 1.6; }
.editor-stats { display: flex; align-content: flex-start; justify-content: flex-end; flex-wrap: wrap; gap: 8px; min-width: 260px; }
.editor-stats span { padding: 5px 9px; border-radius: 999px; background: #e8f2fb; color: #1b65a8; font-size: 12px; white-space: nowrap; }
.config-panels { border: 0; }
.config-panels :deep(.el-collapse-item__header) { height: auto; min-height: 58px; padding: 0 24px; border-color: #edf2f7; }
.config-panels :deep(.el-collapse-item__content) { padding: 2px 24px 24px; }
.panel-note { margin: 0 0 14px; color: #7a8599; line-height: 1.6; }
.danger-note { color: #8a5a00; }
h4 { margin: 18px 0 9px; color: #1a2332; font-size: 14px; }
.edit-table { width: 100%; }
.add-button { margin-top: 10px; }
.pair-row, .module-row, .schema-row { display: grid; align-items: center; gap: 10px; margin-bottom: 9px; }
.route-card { margin-bottom: 12px; padding: 14px; border: 1px solid #d8e2ec; border-radius: 10px; background: #fafbfd; }
.route-card-head { display: grid; grid-template-columns: minmax(160px,.55fr) minmax(220px,1fr) auto; align-items: center; gap: 12px; margin-bottom: 12px; }
.graph-editor { padding-top: 2px; border-top: 1px solid #e6edf4; }
.graph-editor-title { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin: 14px 0 9px; }
.graph-editor-title > div { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px; }
.graph-editor-title span { color: #7a8599; font-size: 12px; }
.detail-editor :deep(.el-button:focus-visible) {
  outline: 3px solid #1b65a8;
  outline-offset: 3px;
  box-shadow: 0 0 0 4px rgba(27, 101, 168, .2);
}
.graph-edge-title { margin-top: 20px; }
.route-node-row, .route-edge-row { display: grid; align-items: center; gap: 10px; margin-bottom: 9px; }
.route-node-row { grid-template-columns: minmax(150px,.45fr) minmax(210px,1fr) auto; }
.route-edge-row { grid-template-columns: minmax(160px,1fr) minmax(110px,.45fr) minmax(160px,1fr) auto; }
.pair-row { grid-template-columns: minmax(160px,.4fr) minmax(220px,1fr) auto; }
.pair-row.compact { grid-template-columns: 1fr auto; margin-top: 10px; }
.module-row { grid-template-columns: minmax(130px,.4fr) minmax(160px,.6fr) auto minmax(140px,.5fr) auto; }
.schema-row { grid-template-columns: minmax(130px,.35fr) minmax(160px,.5fr) minmax(220px,1fr) auto auto; }
.json-summary, .schema-row > span { color: #7a8599; font-size: 12px; }
.platform-grid { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 12px; }
.config-box { padding: 14px 16px; border: 1px solid #edf2f7; border-radius: 10px; background: #fafbfd; }
.config-box h4 { margin-top: 0; }
.config-box > p { color: #7a8599; font-size: 12px; }
.switch-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 38px; border-top: 1px solid #edf2f7; }
.switch-row span { overflow-wrap: anywhere; }
.wide-select { width: 100%; }
.json-editor { margin-top: 14px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
.json-editor :deep(textarea) { line-height: 1.55; }
.json-error { color: #c45656; }
.advanced-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 12px; }
@media (max-width: 900px) {
  .editor-head { display: block; padding: 18px 16px; }
  .editor-stats { justify-content: flex-start; min-width: 0; margin-top: 12px; }
  .config-panels :deep(.el-collapse-item__header) { padding: 0 16px; }
  .config-panels :deep(.el-collapse-item__content) { padding: 2px 16px 20px; }
  .platform-grid { grid-template-columns: 1fr; }
  .route-card-head, .route-node-row, .route-edge-row, .pair-row, .module-row, .schema-row { grid-template-columns: 1fr; }
  .route-node-row, .route-edge-row, .pair-row, .module-row, .schema-row { padding: 12px; border: 1px solid #edf2f7; border-radius: 10px; }
  .graph-editor-title { align-items: flex-start; }
}
</style>
