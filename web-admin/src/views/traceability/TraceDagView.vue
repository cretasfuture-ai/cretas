<script setup lang="ts">
/**
 * 溯源 DAG 追溯线 —— 原料批次 → 工序 → 本批次 → 质检 / 下游批次 / 出货 → 客户, 正查反查一张图。
 * 点节点 = 看详情 + 高亮它的整条上下游链路, 其余变淡。边的组装规则与缺口见 `traceGraph.ts`。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { VueFlow, Handle, Position, useVueFlow, type Edge, type Node } from '@vue-flow/core';
import { Background } from '@vue-flow/background';
import { Controls } from '@vue-flow/controls';
import type { FullTraceResponse } from '@/api/traceability';
import {
  buildTraceGraph,
  chainOf,
  dagFitZoom,
  DAG_COL_GAP,
  DAG_FONT,
  DAG_NODE_H,
  DAG_NODE_W,
  DAG_ROW_GAP,
  type TraceGraphNode,
  type TraceNodeKind,
} from './traceGraph';

import '@vue-flow/core/dist/style.css';
import '@vue-flow/core/dist/theme-default.css';
import '@vue-flow/controls/dist/style.css';

const props = defineProps<{ trace: FullTraceResponse }>();
const emit = defineEmits<{
  (e: 'open-individual', id: string): void;
  (e: 'open-evidence'): void;
  (e: 'open-quality'): void;
  (e: 'open-batch', batchNumber: string): void;
}>();

const NODE_W = DAG_NODE_W;
const NODE_H = DAG_NODE_H;
const COL_GAP = DAG_COL_GAP;
const ROW_GAP = DAG_ROW_GAP;
const FLOW_ID = 'trace-dag-flow';

const KIND_LABEL: Record<TraceNodeKind, string> = {
  upstream: '上游',
  stage: '工序',
  root: '本批次',
  quality: '质检',
  downstream: '下游批次',
  shipment: '出货',
  customer: '客户',
};

const graph = computed(() => buildTraceGraph(props.trace));
const selectedId = ref<string | null>(null);
watch(() => props.trace, () => { selectedId.value = null; });

const chain = computed(() => (selectedId.value ? chainOf(graph.value, selectedId.value) : null));

const positions = computed(() => {
  const byCol = new Map<number, TraceGraphNode[]>();
  for (const n of graph.value.nodes) {
    if (!byCol.has(n.column)) byCol.set(n.column, []);
    byCol.get(n.column)!.push(n);
  }
  const maxRows = Math.max(1, ...[...byCol.values()].map((v) => v.length));
  const totalH = maxRows * NODE_H + (maxRows - 1) * ROW_GAP;
  const map = new Map<string, { x: number; y: number }>();
  for (const [col, list] of byCol) {
    const h = list.length * NODE_H + (list.length - 1) * ROW_GAP;
    const offset = (totalH - h) / 2;
    list.forEach((n, i) => map.set(n.id, { x: col * (NODE_W + COL_GAP), y: offset + i * (NODE_H + ROW_GAP) }));
  }
  return map;
});

// —— 自适应: 默认缩放跟容器宽度走 (有下限保可读), 容器高度跟图高走 ——
const canvasEl = ref<HTMLElement | null>(null);
const containerW = ref(0);
const { setViewport } = useVueFlow(FLOW_ID);
const graphSize = computed(() => {
  let cols = 0;
  let rows = 1;
  const count = new Map<number, number>();
  for (const n of graph.value.nodes) {
    cols = Math.max(cols, n.column + 1);
    count.set(n.column, (count.get(n.column) ?? 0) + 1);
  }
  for (const c of count.values()) rows = Math.max(rows, c);
  return { w: cols * NODE_W + Math.max(0, cols - 1) * COL_GAP, h: rows * NODE_H + (rows - 1) * ROW_GAP };
});
const zoom = computed(() => dagFitZoom(containerW.value, graphSize.value.w));
const canvasHeight = computed(() => Math.min(720, Math.max(360, Math.round(graphSize.value.h * zoom.value + 40))));
function applyViewport() {
  const y = Math.max(12, (canvasHeight.value - graphSize.value.h * zoom.value) / 2);
  void setViewport({ x: 12, y, zoom: zoom.value });
}
let observer: ResizeObserver | null = null;
onMounted(() => {
  const el = canvasEl.value;
  if (!el) return;
  containerW.value = el.clientWidth;
  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver((entries) => {
      const w = Math.round(entries[0]?.contentRect.width ?? el.clientWidth);
      if (w !== containerW.value) containerW.value = w;
    });
    observer.observe(el);
  }
  void nextTick(applyViewport);
});
onBeforeUnmount(() => observer?.disconnect());
watch([zoom, canvasHeight, () => props.trace], () => { void nextTick(applyViewport); });

type NodeState = 'selected' | 'chain' | 'dim' | 'normal';
function stateOf(id: string): NodeState {
  if (!chain.value) return 'normal';
  if (id === selectedId.value) return 'selected';
  return chain.value.nodes.has(id) ? 'chain' : 'dim';
}

const flowNodes = computed<Node[]>(() =>
  graph.value.nodes.map((n) => ({
    id: n.id,
    type: 'trace',
    position: positions.value.get(n.id) ?? { x: 0, y: 0 },
    data: { node: n, state: stateOf(n.id) },
    draggable: false,
    selectable: false,
  })),
);
const flowEdges = computed<Edge[]>(() =>
  graph.value.edges.map((e) => {
    const inChain = chain.value?.edges.has(e.id) ?? false;
    const dim = !!chain.value && !inChain;
    return {
      id: e.id,
      source: e.source,
      target: e.target,
      animated: inChain,
      class: inChain ? 'trace-edge is-chain' : dim ? 'trace-edge is-dim' : 'trace-edge',
      style: { stroke: inChain ? '#2563eb' : '#94a3b8', strokeWidth: inChain ? 3 : 1.5, opacity: dim ? 0.2 : 1 },
    };
  }),
);

const selectedNode = computed(() => graph.value.nodes.find((n) => n.id === selectedId.value) ?? null);
const chainSummary = computed(() => {
  if (!selectedNode.value || !chain.value) return '';
  const upstream = new Set<string>();
  const downstream = new Set<string>();
  const sel = selectedNode.value.id;
  // 区分上下游: 沿边方向各走一遍
  const walk = (dir: 'up' | 'down', into: Set<string>) => {
    const queue = [sel];
    const seen = new Set(queue);
    while (queue.length) {
      const cur = queue.shift()!;
      for (const e of graph.value.edges) {
        const from = dir === 'down' ? e.source : e.target;
        const to = dir === 'down' ? e.target : e.source;
        if (from === cur && !seen.has(to)) { seen.add(to); into.add(to); queue.push(to); }
      }
    }
  };
  walk('up', upstream);
  walk('down', downstream);
  return `上游 ${upstream.size} 个节点，下游 ${downstream.size} 个节点`;
});

function onNodeClick(id: string) {
  selectedId.value = selectedId.value === id ? null : id;
}

const actionLabel = computed(() => {
  const t = selectedNode.value?.target;
  if (!t) return '';
  switch (t.type) {
    case 'individual': return '查看源头个体';
    case 'batch-evidence': return '查看投入产出与成本';
    case 'quality': return '查看本批次质检';
    case 'trace-batch': return `追溯批次 ${t.batchNumber}`;
  }
  return '';
});
function runAction() {
  const t = selectedNode.value?.target;
  if (!t) return;
  if (t.type === 'individual') emit('open-individual', t.id);
  else if (t.type === 'batch-evidence') emit('open-evidence');
  else if (t.type === 'quality') emit('open-quality');
  else emit('open-batch', t.batchNumber);
}

const TONE_CLASS: Record<string, string> = {
  success: 'bg-green-100 text-green-700',
  warning: 'bg-amber-100 text-amber-700',
  danger: 'bg-red-100 text-red-700',
  neutral: 'bg-slate-100 text-slate-600',
};
const STATE_CLASS: Record<NodeState, string> = {
  selected: 'border-blue-600 ring-2 ring-blue-300 shadow-md',
  chain: 'border-blue-400 shadow',
  dim: 'border-slate-200 opacity-30',
  normal: 'border-slate-300',
};
</script>

<template>
  <div data-testid="trace-dag">
    <div class="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-500" data-testid="trace-dag-columns">
      <span v-for="(c, i) in graph.columns.filter(Boolean)" :key="i">{{ c }}</span>
      <span class="ml-auto">节点上直接显示要点；点节点看完整详情并高亮上下游链路，再点一次取消</span>
    </div>
    <div
      ref="canvasEl"
      class="trace-dag-canvas w-full overflow-hidden rounded-lg border border-slate-200 bg-slate-50"
      :style="{ height: `${canvasHeight}px` }"
      data-testid="trace-dag-canvas"
    >
      <VueFlow
        :id="FLOW_ID"
        :nodes="flowNodes"
        :edges="flowEdges"
        :default-viewport="{ x: 12, y: 12, zoom }"
        :min-zoom="0.2"
        :max-zoom="1.5"
        :nodes-draggable="false"
        :nodes-connectable="false"
        :elements-selectable="false"
        @pane-click="selectedId = null"
      >
        <template #node-trace="{ id, data }">
          <div
            class="cursor-pointer overflow-hidden rounded-lg border bg-white p-2 text-left"
            :style="{ width: `${NODE_W}px`, minHeight: `${NODE_H}px` }"
            :class="STATE_CLASS[data.state as NodeState]"
            :data-testid="`dag-node-${id}`"
            :data-node-id="id"
            :data-kind="data.node.kind"
            :data-state="data.state"
            :data-linked="data.node.linked ? 'true' : 'false'"
            role="button"
            tabindex="0"
            @click.stop="onNodeClick(id)"
            @keydown.enter.prevent="onNodeClick(id)"
          >
            <Handle type="target" :position="Position.Left" />
            <div class="mb-1 flex items-center justify-between gap-1">
              <span class="truncate font-semibold text-slate-500" :style="{ fontSize: `${DAG_FONT.label}px` }" data-testid="dag-node-kind">{{ KIND_LABEL[data.node.kind as TraceNodeKind] }}</span>
              <span class="rounded px-1.5 py-0.5" :class="TONE_CLASS[data.node.tone]" :style="{ fontSize: `${DAG_FONT.label}px` }" data-testid="dag-node-status">{{ data.node.statusText }}</span>
            </div>
            <div class="truncate font-semibold text-slate-900" :style="{ fontSize: `${DAG_FONT.title}px`, lineHeight: 1.4 }" :title="data.node.title" data-testid="dag-node-title">{{ data.node.title }}</div>
            <div v-for="l in data.node.lines" :key="l.label" class="flex justify-between gap-2 text-slate-600" :style="{ fontSize: `${DAG_FONT.line}px`, lineHeight: 1.45 }" data-testid="dag-node-line">
              <span class="shrink-0">{{ l.label }}</span>
              <span class="truncate" :class="{ 'text-slate-400': l.value === '未记录' }">{{ l.value }}</span>
            </div>
            <Handle type="source" :position="Position.Right" />
          </div>
        </template>
        <Background pattern-color="#e2e8f0" :gap="20" />
        <Controls />
      </VueFlow>
    </div>

    <div
      v-if="selectedNode"
      class="mt-3 rounded-lg border border-blue-200 bg-blue-50 p-3 text-sm"
      data-testid="trace-dag-detail"
    >
      <div class="flex flex-wrap items-center gap-2">
        <strong class="text-slate-900">{{ selectedNode.title }}</strong>
        <span class="rounded px-1.5 py-0.5 text-xs" :class="TONE_CLASS[selectedNode.tone]">{{ selectedNode.statusText }}</span>
        <span class="text-xs text-slate-500" data-testid="trace-dag-chain-summary">{{ chainSummary }}</span>
        <button
          v-if="actionLabel"
          type="button"
          class="ml-auto rounded bg-blue-600 px-3 py-1 text-xs text-white hover:bg-blue-700"
          data-testid="trace-dag-action"
          @click="runAction"
        >{{ actionLabel }}</button>
      </div>
      <div class="mt-2 grid grid-cols-1 gap-x-6 gap-y-1 sm:grid-cols-2">
        <div v-for="l in selectedNode.lines" :key="l.label" class="flex gap-2">
          <span class="text-slate-500">{{ l.label }}</span>
          <span :class="{ 'text-slate-400': l.value === '未记录' }">{{ l.value }}</span>
        </div>
      </div>
      <p v-if="!selectedNode.linked" class="mt-2 text-xs text-amber-700" data-testid="trace-dag-unlinked">
        后端只记录了这一层是第几跳，没有记录它连到上一层哪个节点，所以图上不画线。
      </p>
    </div>
  </div>
</template>
