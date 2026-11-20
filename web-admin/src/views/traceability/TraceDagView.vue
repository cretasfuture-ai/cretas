<script setup lang="ts">
/**
 * 溯源 DAG 追溯线 —— 原料批次 → 工序 → 本批次 → 质检 / 下游批次 / 出货 → 客户, 正查反查一张图。
 * 点节点 = 看详情 + 高亮它的整条上下游链路, 其余变淡。边的组装规则与缺口见 `traceGraph.ts`。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { VueFlow, Handle, Position, useVueFlow, type Dimensions, type Edge, type Node, type NodeChange } from '@vue-flow/core';
import { Background } from '@vue-flow/background';
import { Controls, ControlButton } from '@vue-flow/controls';
import type { FullTraceResponse } from '@/api/traceability';
import { APP_SCROLL_SELECTOR } from '@/utils/returnNavigation';
import {
  buildTraceGraph,
  chainOf,
  dagFitZoom,
  DAG_COL_GAP,
  DAG_MIN_ZOOM,
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
const FIT_PADDING = 12;
const MAX_CANVAS_HEIGHT = 720;

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

const nodeSizes = ref(new Map<string, Dimensions>());
function updateNodeSizes(changes: NodeChange[]) {
  for (const change of changes) {
    if (change.type === 'remove') nodeSizes.value.delete(change.id);
    if (change.type !== 'dimensions') continue;
    const previous = nodeSizes.value.get(change.id);
    if (previous?.width !== change.dimensions.width || previous.height !== change.dimensions.height) {
      nodeSizes.value.set(change.id, change.dimensions);
    }
  }
}

const layout = computed(() => {
  const byCol = new Map<number, TraceGraphNode[]>();
  for (const n of graph.value.nodes) {
    if (!byCol.has(n.column)) byCol.set(n.column, []);
    byCol.get(n.column)!.push(n);
  }
  const columns = [...byCol.entries()].sort(([a], [b]) => a - b).map(([index, nodes]) => {
    const cards = nodes.map(({ id }) => ({
      id,
      width: nodeSizes.value.get(id)?.width || NODE_W,
      height: nodeSizes.value.get(id)?.height || NODE_H,
    }));
    const width = Math.max(...cards.map(card => card.width));
    const height = cards.reduce((sum, card) => sum + card.height, (cards.length - 1) * ROW_GAP);
    return { index, cards, width, height };
  });
  const height = Math.max(0, ...columns.map(column => column.height));
  const positions = new Map<string, { x: number; y: number }>();
  let width = 0;
  let nextColumn = 0;
  for (const column of columns) {
    const x = width + (nextColumn ? COL_GAP : 0) + (column.index - nextColumn) * (NODE_W + COL_GAP);
    let y = (height - column.height) / 2;
    for (const card of column.cards) {
      positions.set(card.id, { x, y });
      y += card.height + ROW_GAP;
    }
    width = x + column.width;
    nextColumn = column.index + 1;
  }
  return { positions, width, height };
});

// 画布视窗留在固定栏下方。图在可读下限仍放不下时，保留完整的可滚动区域。
const canvasEl = ref<HTMLElement | null>(null);
const controlsEl = ref<HTMLElement | null>(null);
const legendEl = ref<HTMLElement | null>(null);
const containerW = ref(0);
const topInset = ref(0);
const legendHeight = ref(0);
const verticalGutter = ref(0);
const horizontalScrollbar = ref(0);
const availableHeight = ref(MAX_CANVAS_HEIGHT);
const { setViewport } = useVueFlow(FLOW_ID);
const zoom = computed(() => Math.min(
  dagFitZoom(containerW.value, layout.value.width),
  dagFitZoom(Math.max(1, availableHeight.value - horizontalScrollbar.value - 2), layout.value.height),
));
const graphWidth = computed(() => Math.ceil(layout.value.width * zoom.value) + 2 * FIT_PADDING + verticalGutter.value);
const graphHeight = computed(() => Math.ceil(layout.value.height * zoom.value) + 2 * FIT_PADDING);
const canvasHeight = computed(() => Math.min(
  availableHeight.value,
  Math.max(360, graphHeight.value + horizontalScrollbar.value + 2),
));

function fixedBars(): Iterable<HTMLElement> {
  return canvasEl.value?.closest('.app-main')?.querySelectorAll<HTMLElement>(
    ':scope > .app-header, :scope > .workspace-tabs',
  ) ?? [];
}

function measureCanvas() {
  const el = canvasEl.value;
  if (!el?.clientWidth) return;
  containerW.value = el.clientWidth;
  // 横向滚到底时，滚动条预留槽不能占用尾节点的留白。
  verticalGutter.value = Math.max(0, el.offsetWidth - el.clientWidth - 2 * el.clientLeft);
  horizontalScrollbar.value = Math.max(0, el.offsetHeight - el.clientHeight - 2 * el.clientTop);
  let bottom = 0;
  for (const bar of fixedBars()) {
    const { position, top } = getComputedStyle(bar);
    if (position === 'sticky' || position === 'fixed') {
      bottom = Math.max(bottom, (parseFloat(top) || 0) + bar.getBoundingClientRect().height);
    }
  }
  const legend = legendEl.value;
  legendHeight.value = legend ? legend.offsetHeight + (parseFloat(getComputedStyle(legend).marginBottom) || 0) : 0;
  topInset.value = bottom;
  const toolbarHeight = controlsEl.value?.offsetHeight ?? 0;
  availableHeight.value = Math.max(1, Math.min(
    MAX_CANVAS_HEIGHT,
    window.innerHeight - topInset.value - legendHeight.value - toolbarHeight - 2 * FIT_PADDING,
  ));
}

function applyViewport() {
  const el = canvasEl.value;
  if (!el) return;
  el.scrollLeft = 0;
  el.scrollTop = 0;
  const y = Math.max(FIT_PADDING, (el.clientHeight - layout.value.height * zoom.value) / 2);
  void setViewport({ x: FIT_PADDING, y, zoom: zoom.value });
}

async function fitTrace() {
  measureCanvas();
  await nextTick();
  applyViewport();
  const legend = legendEl.value;
  const toolbar = controlsEl.value;
  if (!legend || !toolbar) return;

  // 页面和参考窗的滚动容器不同；直接对齐图例，避开悬浮栏遮住的区域。
  const content = legend.closest<HTMLElement>(APP_SCROLL_SELECTOR);
  const scrollPane = content && content.scrollHeight > content.clientHeight ? content : null;
  const paneBounds = scrollPane?.getBoundingClientRect();
  const safeTop = Math.max(topInset.value, paneBounds?.top ?? 0) + FIT_PADDING;
  const safeBottom = Math.min(window.innerHeight, paneBounds?.bottom ?? window.innerHeight) - FIT_PADDING;
  const top = legend.getBoundingClientRect().top;
  if (top < safeTop || toolbar.getBoundingClientRect().bottom > safeBottom) {
    (scrollPane ?? window).scrollBy({ top: top - safeTop, behavior: 'instant' });
  }
}

let observer: ResizeObserver | null = null;
onMounted(() => {
  const el = canvasEl.value;
  if (!el) return;
  measureCanvas();
  window.addEventListener('resize', measureCanvas);
  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(measureCanvas);
    observer.observe(el);
    if (controlsEl.value) observer.observe(controlsEl.value);
    if (legendEl.value) observer.observe(legendEl.value);
    for (const bar of fixedBars()) observer.observe(bar);
  }
  void nextTick(applyViewport);
});
onBeforeUnmount(() => {
  window.removeEventListener('resize', measureCanvas);
  observer?.disconnect();
});
watch([zoom, canvasHeight, () => props.trace], applyViewport, { flush: 'post' });

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
    position: layout.value.positions.get(n.id) ?? { x: 0, y: 0 },
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
    <div ref="legendEl" class="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-500" data-testid="trace-dag-columns">
      <span v-for="(c, i) in graph.columns.filter(Boolean)" :key="i">{{ c }}</span>
      <span class="ml-auto">节点上直接显示要点；点节点看完整详情并高亮上下游链路，再点一次取消</span>
    </div>
    <div
      ref="canvasEl"
      class="trace-dag-canvas w-full overflow-auto rounded-lg border border-slate-200 bg-slate-50"
      :style="{ height: `${canvasHeight}px` }"
      data-testid="trace-dag-canvas"
      role="region"
      aria-label="追溯线画布"
      tabindex="0"
    >
      <VueFlow
        :id="FLOW_ID"
        :nodes="flowNodes"
        :edges="flowEdges"
        :default-viewport="{ x: FIT_PADDING, y: FIT_PADDING, zoom }"
        :min-zoom="DAG_MIN_ZOOM"
        :max-zoom="1.5"
        :style="{ minWidth: `${graphWidth}px`, minHeight: `${graphHeight}px` }"
        :zoom-on-scroll="false"
        :prevent-scrolling="false"
        :nodes-draggable="false"
        :nodes-connectable="false"
        :elements-selectable="false"
        @init="applyViewport"
        @nodes-change="updateNodeSizes"
        @pane-click="selectedId = null"
      >
        <template #node-trace="{ id, data }">
          <div
            class="w-max cursor-pointer whitespace-nowrap rounded-lg border bg-white p-2 text-left"
            :style="{ minWidth: `${NODE_W}px`, minHeight: `${NODE_H}px` }"
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
              <span class="font-semibold text-slate-500" :style="{ fontSize: `${DAG_FONT.label}px` }" data-testid="dag-node-kind">{{ KIND_LABEL[data.node.kind as TraceNodeKind] }}</span>
              <span class="rounded px-1.5 py-0.5" :class="TONE_CLASS[data.node.tone]" :style="{ fontSize: `${DAG_FONT.label}px` }" data-testid="dag-node-status">{{ data.node.statusText }}</span>
            </div>
            <div class="font-semibold text-slate-900" :style="{ fontSize: `${DAG_FONT.title}px`, lineHeight: 1.4 }" :title="data.node.title" data-testid="dag-node-title">{{ data.node.title }}</div>
            <div v-for="l in data.node.lines" :key="l.label" class="flex justify-between gap-2 text-slate-600" :style="{ fontSize: `${DAG_FONT.line}px`, lineHeight: 1.45 }" data-testid="dag-node-line">
              <span class="shrink-0">{{ l.label }}</span>
              <span :class="{ 'text-slate-400': l.value === '未记录' }">{{ l.value }}</span>
            </div>
            <Handle type="source" :position="Position.Right" />
          </div>
        </template>
        <Background pattern-color="#e2e8f0" :gap="20" />
      </VueFlow>
    </div>
    <div ref="controlsEl" class="pt-2">
      <Controls class="trace-dag-controls">
        <template #control-fit-view>
          <ControlButton title="适应（装不下时可滚动查看）" data-testid="trace-dag-fit" @click="fitTrace">
            <svg viewBox="0 0 32 30" width="14" height="14" aria-hidden="true">
              <path fill="currentColor" d="M3 4h9v3H6v6H3zM29 4h-9v3h6v6h3zM3 26h9v-3H6v-6H3zM29 26h-9v-3h6v-6h3z" />
            </svg>
          </ControlButton>
        </template>
      </Controls>
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

<style scoped>
.trace-dag-canvas {
  scrollbar-gutter: stable;
}

.trace-dag-controls {
  position: static;
  display: flex;
  width: fit-content;
  margin: 0;
}
</style>
