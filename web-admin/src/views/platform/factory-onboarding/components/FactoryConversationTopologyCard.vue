<template>
  <section class="topology-card" aria-labelledby="topology-card-title">
    <header class="topology-head">
      <div>
        <span class="kicker">Agent 复杂路线追问</span>
        <h3 id="topology-card-title">逐条确认 {{ draft.productName }} 的真实流向</h3>
        <p>我保留了你刚才的现场描述，但不会从一句话里猜连线。请从已确认工序中选择起点、终点和每条边。</p>
      </div>
      <el-tag type="warning" effect="plain">待显式确认</el-tag>
    </header>

    <div class="evidence-strip">
      <span>已确认形态：{{ draft.routeShapes.join('、') }}</span>
      <span>原话证据：{{ draft.routeDetails || '未提供' }}</span>
    </div>

    <div class="boundary-grid">
      <label>
        <span>整条路线起点</span>
        <el-select :model-value="draft.entry" aria-label="整条路线起点" @update:model-value="setBoundary('entry', String($event ?? ''))">
          <el-option v-for="node in draft.nodeLabels" :key="`entry-${node}`" :label="node" :value="node" />
        </el-select>
      </label>
      <label>
        <span>整条路线终点</span>
        <el-select :model-value="draft.terminal" aria-label="整条路线终点" @update:model-value="setBoundary('terminal', String($event ?? ''))">
          <el-option v-for="node in draft.nodeLabels" :key="`terminal-${node}`" :label="node" :value="node" />
        </el-select>
      </label>
    </div>

    <div class="edge-list" role="table" aria-label="复杂路线连线确认表">
      <div v-for="(row, index) in draft.rows" :key="row.id" class="edge-row" role="row">
        <div class="edge-title">
          <strong>连线 {{ String(index + 1).padStart(2, '0') }}</strong>
          <el-button text type="danger" :aria-label="`删除第 ${index + 1} 条连线`" @click="removeRow(index)">删除</el-button>
        </div>
        <label>
          <small>从</small>
          <el-select :model-value="row.from" :aria-label="`第 ${index + 1} 条连线起点`" @update:model-value="setRow(index, 'from', String($event ?? ''))">
            <el-option v-for="node in draft.nodeLabels" :key="`from-${row.id}-${node}`" :label="node" :value="node" />
          </el-select>
        </label>
        <span class="arrow" aria-hidden="true">→</span>
        <label>
          <small>到</small>
          <el-select :model-value="row.to" :aria-label="`第 ${index + 1} 条连线终点`" @update:model-value="setRow(index, 'to', String($event ?? ''))">
            <el-option v-for="node in draft.nodeLabels" :key="`to-${row.id}-${node}`" :label="node" :value="node" />
          </el-select>
        </label>
        <label>
          <small>边类型</small>
          <el-select :model-value="row.type" :aria-label="`第 ${index + 1} 条连线类型`" @update:model-value="setRow(index, 'type', String($event ?? ''))">
            <el-option v-for="option in edgeTypes" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </label>
        <label class="condition-field">
          <small>{{ row.type === 'FORWARD' || row.type === 'MERGE' ? '条件 / 说明（可选）' : '触发条件（必填）' }}</small>
          <el-input
            :model-value="row.condition"
            :placeholder="row.type === 'BRANCH' ? '例如：金检合格 / 金检不合格' : (row.type === 'REWORK' ? '例如：复检仍不合格' : '例如：主线顺序前进')"
            :aria-label="`第 ${index + 1} 条连线条件`"
            @update:model-value="setRow(index, 'condition', String($event ?? ''))"
          />
        </label>
        <label v-if="row.type === 'REWORK'" class="exit-field">
          <small>返工复检通过后的放行出口</small>
          <el-select :model-value="row.reworkExit" :aria-label="`第 ${index + 1} 条返工放行出口`" @update:model-value="setRow(index, 'reworkExit', String($event ?? ''))">
            <el-option v-for="node in draft.nodeLabels" :key="`exit-${row.id}-${node}`" :label="node" :value="node" />
          </el-select>
        </label>
      </div>
    </div>

    <el-button plain class="add-edge" @click="addRow">+ 补一条真实连线</el-button>

    <footer class="topology-actions">
      <div>
        <strong>只生成编译预览，不发布 Workflow</strong>
        <span>提交后服务端会整体校验；任何未知工序、重复边、缺条件或断路都会整批拒绝，业务写入为 0。</span>
      </div>
      <el-button type="primary" :loading="sending" :disabled="!locallyComplete" @click="$emit('submit')">
        确认这些拓扑事实并重新编译
      </el-button>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { RouteTopologyDraft, RouteTopologyEdgeType } from '../factoryConversationBlocks'

const props = defineProps<{ draft: RouteTopologyDraft; sending: boolean }>()
const emit = defineEmits<{ change: [draft: RouteTopologyDraft]; submit: [] }>()

const edgeTypes: Array<{ value: RouteTopologyEdgeType; label: string }> = [
  { value: 'FORWARD', label: '顺序前进' },
  { value: 'BRANCH', label: '条件 / 并行分支' },
  { value: 'MERGE', label: '合流' },
  { value: 'REWORK', label: '返工返回' },
]

const locallyComplete = computed(() => Boolean(props.draft.entry && props.draft.terminal && props.draft.rows.length)
  && props.draft.rows.every(row => Boolean(row.from && row.to && row.from !== row.to)
    && (row.type !== 'BRANCH' || Boolean(row.condition.trim()))
    && (row.type !== 'REWORK' || Boolean(row.condition.trim() && row.reworkExit))))

function copy(): RouteTopologyDraft {
  return { ...props.draft, routeShapes: [...props.draft.routeShapes], nodeLabels: [...props.draft.nodeLabels], rows: props.draft.rows.map(row => ({ ...row })) }
}

function setBoundary(field: 'entry' | 'terminal', value: string) {
  const next = copy()
  next[field] = value
  emit('change', next)
}

function setRow(index: number, field: 'from' | 'to' | 'type' | 'condition' | 'reworkExit', value: string) {
  const next = copy()
  if (field === 'type') next.rows[index].type = value as RouteTopologyEdgeType
  else next.rows[index][field] = value
  if (field === 'type' && value !== 'REWORK') next.rows[index].reworkExit = ''
  emit('change', next)
}

function addRow() {
  const next = copy()
  next.rows.push({
    id: `new-${Date.now()}-${next.rows.length}`,
    from: '', to: '', type: 'FORWARD', condition: '', reworkExit: '',
  })
  emit('change', next)
}

function removeRow(index: number) {
  const next = copy()
  next.rows.splice(index, 1)
  emit('change', next)
}
</script>

<style scoped>
.topology-card { container-type: inline-size; flex: 1; min-width: 0; overflow: hidden; border: 1px solid #d5bd82; border-radius: 12px; background: #fffdf8; }
.topology-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 17px 18px 13px; }
.kicker { color: #976819; font-size: 10px; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; }
h3 { margin: 4px 0 0; color: #202735; font-size: 16px; line-height: 1.4; }
p { max-width: 760px; margin: 6px 0 0; color: #687386; font-size: 12px; line-height: 1.55; }
.evidence-strip { display: grid; gap: 4px; margin: 0 18px 14px; padding: 10px 12px; border-left: 3px solid #c28b2c; background: #fff8e9; color: #5f6470; font-size: 11px; line-height: 1.45; }
.boundary-grid { display: grid; grid-template-columns: repeat(2, minmax(220px, 1fr)); gap: 12px; margin: 0 18px 14px; }
.boundary-grid label, .edge-row label { min-width: 0; }
.boundary-grid span, .edge-row small { display: block; margin-bottom: 5px; color: #596579; font-size: 11px; font-weight: 700; }
.edge-list { display: grid; gap: 10px; margin: 0 18px; }
.edge-row { display: grid; grid-template-columns: minmax(150px, 1fr) 22px minmax(150px, 1fr) minmax(140px, .8fr); gap: 10px; align-items: end; padding: 12px; border: 1px solid #e5dcc8; border-radius: 10px; background: #fff; }
.edge-title { display: flex; grid-column: 1 / -1; align-items: center; justify-content: space-between; color: #2a3342; font-size: 12px; }
.arrow { align-self: center; color: #8b6b2e; font-weight: 800; text-align: center; }
.condition-field { grid-column: 1 / span 3; }
.exit-field { grid-column: 4; }
.add-edge { margin: 12px 18px 16px; }
.topology-actions { position: sticky; bottom: 0; z-index: 1; display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 13px 18px; border-top: 1px solid #e6dcc7; background: #fffaf0; }
.topology-actions div, .topology-actions strong, .topology-actions span { display: block; }
.topology-actions strong { color: #343b48; font-size: 12px; }
.topology-actions span { margin-top: 3px; color: #6d7480; font-size: 11px; line-height: 1.45; }
@container (max-width: 820px) {
  .topology-head, .topology-actions { align-items: stretch; flex-direction: column; }
  .boundary-grid { grid-template-columns: 1fr; }
  .edge-row { grid-template-columns: minmax(0, 1fr) 20px minmax(0, 1fr); }
  .edge-row > label:nth-of-type(3), .condition-field, .exit-field { grid-column: 1 / -1; }
  .topology-actions .el-button { width: 100%; }
}
@media (max-width: 760px) {
  .topology-head, .topology-actions { align-items: stretch; flex-direction: column; }
  .boundary-grid { grid-template-columns: 1fr; margin-inline: 12px; }
  .evidence-strip, .edge-list { margin-inline: 12px; }
  .edge-row { grid-template-columns: 1fr; }
  .arrow { display: none; }
  .edge-row > label, .edge-row > label:nth-of-type(3), .condition-field, .exit-field { grid-column: 1; }
  .add-edge { margin-inline: 12px; width: calc(100% - 24px); }
  .topology-actions .el-button { width: 100%; }
}
</style>
