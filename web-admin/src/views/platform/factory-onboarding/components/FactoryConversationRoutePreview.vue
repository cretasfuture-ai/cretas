<template>
  <section class="route-compiler" aria-labelledby="route-compiler-title">
    <header class="route-compiler__head">
      <div>
        <span class="route-compiler__kicker">确定性路线编译</span>
        <h3 id="route-compiler-title">把已确认的工序事实先拼成路线预览</h3>
        <p>这里只读展示编译结果和缺口；不会创建、应用或发布 Workflow。</p>
      </div>
      <div class="route-compiler__state">
        <el-tag type="info" effect="plain">预览专用</el-tag>
        <strong>未发布 Workflow</strong>
      </div>
    </header>

    <div class="route-compiler__summary" role="status">
      <span>共 {{ summary.total }} 条产品路线</span>
      <span class="ready">{{ summary.ready }} 条路线已确定性编译</span>
      <span v-if="summary.blocked" class="blocked">{{ summary.blocked }} 条复杂拓扑待确认</span>
      <span v-if="summary.incomplete">{{ summary.incomplete }} 条事实未齐</span>
    </div>

    <article v-for="preview in previews" :key="preview.productIndex" class="route-card">
      <div class="route-card__title">
        <div>
          <span>产品 {{ String(preview.productIndex).padStart(2, '0') }}</span>
          <h4>{{ preview.productName || `第 ${preview.productIndex} 个成品（待命名）` }}</h4>
        </div>
        <el-tag :type="routePreviewStatus(preview).tone" effect="plain">
          {{ routePreviewStatus(preview).label }}
        </el-tag>
      </div>

      <div class="route-facts">
        <div>
          <small>用户确认的路线形态</small>
          <div class="fact-values">
            <span v-for="shape in preview.routeShapes" :key="shape">{{ shape }}</span>
            <em v-if="!preview.routeShapes.length">待确认</em>
          </div>
        </div>
        <div>
          <small>用户补充的现场事实</small>
          <p>{{ preview.routeDetails || '尚未补充路线例外' }}</p>
        </div>
      </div>

      <div class="route-flow" :aria-label="`${preview.productName || '产品'}${preview.previewKind === 'COMPILED_EXPLICIT' ? '工序节点目录' : '工序顺序预览'}`">
        <template v-for="(node, index) in preview.graph.nodes" :key="node.id">
          <div class="route-node">
            <span>{{ index + 1 }}</span>
            <strong>{{ node.label }}</strong>
          </div>
          <span v-if="index < preview.graph.nodes.length - 1" class="route-arrow" aria-hidden="true">
            {{ preview.previewKind === 'COMPILED_EXPLICIT' ? '·' : '→' }}
          </span>
        </template>
        <span v-if="!preview.graph.nodes.length" class="route-empty">工序顺序尚未确认</span>
      </div>

      <div v-if="preview.previewKind === 'ORDER_SKELETON' && preview.graph.nodes.length" class="skeleton-note">
        当前只画已确认的顺序骨架，不包含系统猜测的分支、合流或返工连线。
      </div>
      <div v-else-if="preview.previewKind === 'COMPILED_EXPLICIT'" class="skeleton-note explicit-note">
        上方是工序节点目录；真实连线、条件与返工出口以逐条拓扑账本为准。
      </div>

      <div v-if="preview.previewKind === 'COMPILED_EXPLICIT' && preview.edgeIntents?.length" class="edge-ledger">
        <div class="edge-ledger__head">
          <strong>用户确认的逐条拓扑</strong>
          <span>{{ preview.edgeIntents.length }} 条边 · 起止点与条件均来自确认卡</span>
        </div>
        <div v-for="(edge, index) in preview.edgeIntents" :key="`${edge.fromNodeId}-${edge.toNodeId}-${edge.type}`" class="edge-ledger__row">
          <span>{{ String(index + 1).padStart(2, '0') }}</span>
          <strong>{{ edge.fromLabel }} → {{ edge.toLabel }}</strong>
          <el-tag size="small" effect="plain">{{ edgeLabel(edge.type) }}</el-tag>
          <em>{{ edge.condition || '无额外条件' }}</em>
          <small v-if="edge.type === 'REWORK'">放行出口：{{ edge.reworkExit }}</small>
        </div>
      </div>

      <div v-if="preview.issues.length" class="route-diagnostics">
        <div class="route-diagnostics__head">
          <strong>还不能形成完整拓扑</strong>
          <span>{{ preview.issues.length }} 个明确缺口</span>
        </div>
        <ol>
          <li v-for="issue in preview.issues" :key="issue.code">
            <div><code>{{ issue.code }}</code><strong>{{ issue.message }}</strong></div>
            <p>{{ issue.followUpQuestion }}</p>
          </li>
        </ol>
      </div>

      <footer>
        <span>事实来源 {{ preview.sourceFields.length }} 项 · 拓扑{{ preview.topologyComplete ? '完整' : '未完整' }}</span>
        <strong>{{ preview.publishState === 'NOT_PUBLISHED' ? '发布写入 0' : preview.publishState }}</strong>
      </footer>
    </article>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { FactoryRouteCompilation } from '@/api/factoryOnboardingApi'
import { routePreviewStatus, summarizeRoutePreviews } from '../factoryRoutePreview'

const props = defineProps<{ previews: FactoryRouteCompilation[] }>()
const summary = computed(() => summarizeRoutePreviews(props.previews))

function edgeLabel(type: string) {
  return { FORWARD: '顺序', BRANCH: '分支', MERGE: '合流', REWORK: '返工' }[type] ?? type
}
</script>

<style scoped lang="scss">
.route-compiler {
  container-type: inline-size;
  width: min(100%, 1080px);
  padding: 22px;
  border: 1px solid #d9e1ea;
  border-radius: 16px;
  background: #fbfcfe;
  color: #172033;
}

.route-compiler__head,
.route-card__title,
.route-diagnostics__head,
footer {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.route-compiler__kicker,
.route-card__title span {
  color: #5b6b82;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: .08em;
  text-transform: uppercase;
}

h3,
h4,
p { margin: 0; }
h3 { margin-top: 5px; font-size: 20px; line-height: 1.35; }
.route-compiler__head p { margin-top: 6px; color: #64748b; font-size: 13px; }

.route-compiler__state {
  display: grid;
  justify-items: end;
  gap: 6px;
  white-space: nowrap;
  font-size: 12px;
  color: #475569;
}

.route-compiler__summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  margin: 18px 0 12px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #eef3f8;
  color: #526176;
  font-size: 12px;
  font-weight: 600;
}
.route-compiler__summary .ready { color: #237a57; }
.route-compiler__summary .blocked { color: #a25d12; }

.route-card {
  margin-top: 12px;
  padding: 18px;
  border: 1px solid #dfe6ee;
  border-radius: 13px;
  background: #fff;
}
.route-card__title h4 { margin-top: 3px; font-size: 17px; }

.route-facts {
  display: grid;
  grid-template-columns: minmax(180px, .8fr) minmax(260px, 1.4fr);
  gap: 14px;
  margin-top: 16px;
}
.route-facts > div {
  min-width: 0;
  padding: 11px 12px;
  border-radius: 9px;
  background: #f6f8fb;
}
.route-facts small { display: block; margin-bottom: 7px; color: #68778c; }
.route-facts p { color: #334155; line-height: 1.55; overflow-wrap: anywhere; }
.fact-values { display: flex; flex-wrap: wrap; gap: 6px; }
.fact-values span {
  padding: 3px 8px;
  border: 1px solid #cdd9e6;
  border-radius: 999px;
  color: #2f526f;
  font-size: 12px;
}
.fact-values em { color: #94a3b8; font-style: normal; }

.route-flow {
  display: flex;
  align-items: stretch;
  gap: 8px;
  margin-top: 14px;
  padding: 14px;
  overflow-x: auto;
  border: 1px solid #dbe5ef;
  border-radius: 11px;
  background: #f8fafc;
}
.route-node {
  flex: 0 0 auto;
  min-width: 112px;
  padding: 9px 11px;
  border: 1px solid #c9d8e6;
  border-radius: 9px;
  background: #fff;
}
.route-node span { display: block; color: #7c8da3; font-size: 11px; }
.route-node strong { display: block; margin-top: 2px; font-size: 13px; }
.route-arrow { align-self: center; color: #71859a; font-weight: 700; }
.route-empty { color: #8795a7; font-size: 13px; }

.skeleton-note {
  margin-top: 8px;
  color: #8a5b1b;
  font-size: 12px;
}
.explicit-note { color: #347259; }

.edge-ledger { margin-top: 12px; overflow: hidden; border: 1px solid #cfe0d7; border-radius: 10px; }
.edge-ledger__head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; background: #f1f8f4; }
.edge-ledger__head span { color: #587166; font-size: 11px; }
.edge-ledger__row { display: grid; grid-template-columns: 28px minmax(180px, 1fr) auto minmax(160px, 1fr) auto; gap: 10px; align-items: center; padding: 9px 12px; border-top: 1px solid #e4eee8; font-size: 12px; }
.edge-ledger__row > span { color: #7c8d84; font-size: 10px; }
.edge-ledger__row em { color: #52645b; font-style: normal; overflow-wrap: anywhere; }
.edge-ledger__row small { color: #866126; }

.route-diagnostics {
  margin-top: 14px;
  padding: 13px 14px;
  border: 1px solid #ead7b9;
  border-radius: 10px;
  background: #fffaf2;
}
.route-diagnostics__head span { color: #926124; font-size: 12px; }
.route-diagnostics ol { margin: 10px 0 0; padding-left: 20px; }
.route-diagnostics li + li { margin-top: 9px; }
.route-diagnostics li div { display: flex; flex-wrap: wrap; align-items: baseline; gap: 7px; }
.route-diagnostics code { color: #8a5b1b; font-size: 10px; }
.route-diagnostics p { margin-top: 2px; color: #6a5132; font-size: 12px; line-height: 1.5; }

footer {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #e5eaf0;
  color: #64748b;
  font-size: 12px;
}
footer strong { color: #334155; }

@container (max-width: 760px) {
  .route-compiler__head,
  .route-card__title { align-items: stretch; flex-direction: column; }
  .route-compiler__state { justify-items: start; }
  .route-facts { grid-template-columns: 1fr; }
  .route-flow { display: grid; grid-template-columns: minmax(0, 1fr); }
  .route-node { width: 100%; min-width: 0; }
  .route-arrow { justify-self: center; transform: rotate(90deg); }
  .edge-ledger__head { align-items: flex-start; flex-direction: column; }
  .edge-ledger__row { grid-template-columns: 24px minmax(0, 1fr) auto; }
  .edge-ledger__row em, .edge-ledger__row small { grid-column: 2 / -1; }
}
</style>
