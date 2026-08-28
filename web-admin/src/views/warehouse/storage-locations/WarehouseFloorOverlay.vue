<script setup lang="ts">
/**
 * 仓库平面图的两层叠加层 —— 结构层（储存区域几何）+ 标注层（自由图形/文字）。
 *
 * ── 为什么是两个 <svg> 而不是一个 ──
 * Vue Flow 的 `.vue-flow__viewport`(货架所在层) 是 z-index: 4。区域面积必须画在
 * 货架**下面**(它是底色, 不能盖住货架), 标注必须画在货架**上面**(它是画在图上的
 * 说明)。所以底层 svg 不给 z-index(auto, 落在 4 以下), 顶层 svg 给 z-index: 5。
 *
 * ── 为什么底层完全不接指针事件 ──
 * `.vue-flow__viewport` 覆盖整块画布且不是 pointer-events:none, 底层 svg 在它
 * 下面, 指针根本到不了。⇒ 底层只画像素; 一切可点的东西(区域边框把手、标签、
 * 缩放控制点、标注)都在顶层。这不是权宜之计, 是这套 z-order 的必然结果。
 *
 * ── 为什么区域的把手是「边框带 + 标签牌」而不是整块面积 ──
 * 货架就坐落在区域里面。整块面积可点 ⇒ 区域会吃掉本该落到货架上的拖拽,
 * 「货架拖不动了」。用 `pointer-events: stroke` 只让边框那一圈可抓, 内部留给货架。
 *
 * ── interactive 的取值 ──
 * 只有「有编辑权限 **且** 处于全屏」时才为 true。缩略图状态下本组件所有元素
 * pointer-events 全为 none ⇒ 页面内嵌画布的交互与本次改动之前逐字节相同
 * (货架照样拖、画布照样平移), 新东西只是多画了几笔像素。
 */
import { computed, onBeforeUnmount, ref } from 'vue';
import { useVueFlow } from '@vue-flow/core';
import type {
  WarehouseAnnotation, WarehouseFloor, WarehouseRectGeometry, WarehouseZone, WarehouseZoneShape,
} from '@/api/warehouseLayout';
import {
  annotationFromPolyline, annotationPathData, appendFreehandPoint, floorAnnotations, floorZoneShapes,
  handleAnchor, minSizeForKind, moveRect, rectFromDrag, RESIZE_HANDLES, resizeRect, screenToFlowPoint,
  type FlowPoint, type MapTool, type ResizeHandle,
} from './warehouseLayoutModel';

defineOptions({ inheritAttrs: false });

const props = defineProps<{
  floor: WarehouseFloor | null;
  zones: WarehouseZone[];
  tool: MapTool;
  /** 有编辑权限且全屏时才为 true —— 缩略图状态下本层完全不吃指针事件 */
  interactive: boolean;
  /** `zone:<zoneId>` / `annotation:<id>` / 空串 */
  selection: string;
  /** 画「区域」时画给哪个区域 */
  activeZoneId: string;
  strokeColor: string;
  showAnnotations: boolean;
}>();

const emit = defineEmits<{
  (event: 'select', value: string): void;
  (event: 'zone-shape', shape: WarehouseZoneShape): void;
  (event: 'annotation-add', annotation: WarehouseAnnotation): void;
  (event: 'annotation-change', annotation: WarehouseAnnotation): void;
  (event: 'text-request', rect: WarehouseRectGeometry): void;
  (event: 'draft-change', hasDraft: boolean): void;
}>();

const { viewport } = useVueFlow();
const surface = ref<SVGSVGElement>();

const zoneShapes = computed(() => floorZoneShapes(props.floor));
const annotations = computed(() => floorAnnotations(props.floor));
const zoom = computed(() => viewport.value?.zoom || 1);
const paneTransform = computed(() => {
  const view = viewport.value ?? { x: 0, y: 0, zoom: 1 };
  return `translate(${view.x} ${view.y}) scale(${view.zoom || 1})`;
});
/** 控制点/把手在屏幕上恒定大小 —— 画布坐标里要除以缩放。 */
const handleSize = computed(() => 10 / zoom.value);
const gripWidth = computed(() => 16 / zoom.value);
const isDrawingTool = computed(() => props.interactive && props.tool !== 'select');
/**
 * 命中区域的开关。
 *
 * 🔴 内联算出来而不是写进 CSS 靠父层 class 级联 —— 级联那种写法在换了父容器、
 * 改了类名之后会**静默失效**, 而失效的长相是「这个东西点不动了」, 不报错。
 * 这里让「能不能点」与 interactive/tool 直接同源。
 *
 * ⚠️ 落笔状态(isDrawingTool)下, 已有图形一律 none —— 否则在一个已画好的
 * 矩形上面再画一笔时, 指针会被那个矩形抢走, 变成「拖动它」。
 */
const shapeHit = computed(() => (props.interactive && props.tool === 'select' ? 'auto' : 'none'));
const gripHit = computed(() => (props.interactive && props.tool === 'select' ? 'stroke' : 'none'));
const captureHit = computed(() => (isDrawingTool.value ? 'auto' : 'none'));

// ───────────────────────────── 拖拽 / 绘制状态 ─────────────────────────────

type Draft =
  | { kind: 'zone-move'; zoneId: string; origin: WarehouseZoneShape; from: FlowPoint }
  | { kind: 'zone-resize'; zoneId: string; origin: WarehouseZoneShape; handle: ResizeHandle; from: FlowPoint }
  | { kind: 'annotation-move'; origin: WarehouseAnnotation; from: FlowPoint }
  | { kind: 'annotation-resize'; origin: WarehouseAnnotation; handle: ResizeHandle; from: FlowPoint }
  | { kind: 'draw-rect'; from: FlowPoint; to: FlowPoint; moved: boolean }
  | { kind: 'draw-free'; points: number[] };

const draft = ref<Draft | null>(null);
/** 多边形是「点几下再收笔」，不是一次拖拽，所以单独存。 */
const polygonPoints = ref<number[]>([]);
const polygonCursor = ref<FlowPoint | null>(null);

const draftRect = computed<WarehouseRectGeometry | null>(() => {
  const current = draft.value;
  if (!current || current.kind !== 'draw-rect') return null;
  return rectFromDrag(current.from, current.to, minSizeForKind(shapeKindOfTool()));
});
const draftPath = computed(() => {
  const current = draft.value;
  if (current?.kind === 'draw-free') return pointsToPath(current.points, false);
  if (polygonPoints.value.length) {
    const preview = polygonCursor.value
      ? [...polygonPoints.value, polygonCursor.value.x, polygonCursor.value.y]
      : polygonPoints.value;
    return pointsToPath(preview, false);
  }
  return '';
});

function pointsToPath(flat: number[], close: boolean): string {
  const parts: string[] = [];
  for (let index = 0; index + 1 < flat.length; index += 2) {
    parts.push(`${index === 0 ? 'M' : 'L'}${flat[index]} ${flat[index + 1]}`);
  }
  if (close && parts.length > 2) parts.push('Z');
  return parts.join(' ');
}

function shapeKindOfTool(): WarehouseAnnotation['kind'] {
  return props.tool === 'ellipse' ? 'ellipse' : props.tool === 'text' ? 'text' : 'rect';
}

function toFlow(event: PointerEvent | MouseEvent): FlowPoint {
  const bounds = surface.value?.getBoundingClientRect() ?? { left: 0, top: 0 };
  return screenToFlowPoint(event.clientX, event.clientY, bounds, viewport.value ?? { x: 0, y: 0, zoom: 1 });
}

function beginDraft(next: Draft) {
  draft.value = next;
  window.addEventListener('pointermove', onWindowPointerMove);
  window.addEventListener('pointerup', onWindowPointerUp);
  emit('draft-change', true);
}

function endDraft() {
  window.removeEventListener('pointermove', onWindowPointerMove);
  window.removeEventListener('pointerup', onWindowPointerUp);
  draft.value = null;
  emit('draft-change', polygonPoints.value.length > 0);
}

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onWindowPointerMove);
  window.removeEventListener('pointerup', onWindowPointerUp);
});

// ───────────────────────────── 区域：拖动 / 缩放 ─────────────────────────────

function startZoneMove(shape: WarehouseZoneShape, event: PointerEvent) {
  if (!props.interactive || props.tool !== 'select') return;
  emit('select', `zone:${shape.zoneId}`);
  beginDraft({ kind: 'zone-move', zoneId: shape.zoneId, origin: { ...shape }, from: toFlow(event) });
}

function startZoneResize(shape: WarehouseZoneShape, handle: ResizeHandle, event: PointerEvent) {
  if (!props.interactive || props.tool !== 'select') return;
  emit('select', `zone:${shape.zoneId}`);
  beginDraft({ kind: 'zone-resize', zoneId: shape.zoneId, origin: { ...shape }, handle, from: toFlow(event) });
}

function startAnnotationMove(annotation: WarehouseAnnotation, event: PointerEvent) {
  if (!props.interactive || props.tool !== 'select') return;
  emit('select', `annotation:${annotation.id}`);
  beginDraft({ kind: 'annotation-move', origin: { ...annotation }, from: toFlow(event) });
}

function startAnnotationResize(annotation: WarehouseAnnotation, handle: ResizeHandle, event: PointerEvent) {
  if (!props.interactive || props.tool !== 'select') return;
  emit('select', `annotation:${annotation.id}`);
  beginDraft({ kind: 'annotation-resize', origin: { ...annotation }, handle, from: toFlow(event) });
}

// ───────────────────────────── 画布：新建图形 ─────────────────────────────

function onSurfacePointerDown(event: PointerEvent) {
  if (!isDrawingTool.value) return;
  const point = toFlow(event);
  if (props.tool === 'polygon') {
    polygonPoints.value = [...polygonPoints.value, Math.round(point.x), Math.round(point.y)];
    polygonCursor.value = point;
    emit('draft-change', true);
    return;
  }
  if (props.tool === 'freehand') {
    beginDraft({ kind: 'draw-free', points: [Math.round(point.x), Math.round(point.y)] });
    return;
  }
  beginDraft({ kind: 'draw-rect', from: point, to: point, moved: false });
}

function onSurfacePointerMove(event: PointerEvent) {
  if (props.tool === 'polygon' && polygonPoints.value.length) polygonCursor.value = toFlow(event);
}

/** 多边形收笔：双击画布，或调用暴露出去的 finishPolygon()。 */
function onSurfaceDoubleClick() {
  if (props.tool === 'polygon') finishPolygon();
}

function onWindowPointerMove(event: PointerEvent) {
  const current = draft.value;
  if (!current) return;
  const point = toFlow(event);
  switch (current.kind) {
    case 'zone-move': {
      const moved = moveRect(current.origin, point.x - current.from.x, point.y - current.from.y);
      emit('zone-shape', { ...moved, zoneId: current.zoneId });
      break;
    }
    case 'zone-resize': {
      const resized = resizeRect(current.origin, current.handle, point.x - current.from.x, point.y - current.from.y);
      emit('zone-shape', { ...resized, zoneId: current.zoneId });
      break;
    }
    case 'annotation-move': {
      emit('annotation-change', moveRect(current.origin, point.x - current.from.x, point.y - current.from.y));
      break;
    }
    case 'annotation-resize': {
      emit('annotation-change', resizeRect(
        current.origin, current.handle, point.x - current.from.x, point.y - current.from.y,
        minSizeForKind(current.origin.kind),
      ));
      break;
    }
    case 'draw-rect': {
      const moved = Math.abs(point.x - current.from.x) > 4 || Math.abs(point.y - current.from.y) > 4;
      draft.value = { ...current, to: point, moved: current.moved || moved };
      break;
    }
    case 'draw-free': {
      draft.value = { ...current, points: appendFreehandPoint(current.points, Math.round(point.x), Math.round(point.y)) };
      break;
    }
    default:
      break;
  }
}

function onWindowPointerUp() {
  const current = draft.value;
  if (!current) return;
  if (current.kind === 'draw-rect') commitRectDraft(current);
  if (current.kind === 'draw-free') commitFreehandDraft(current);
  endDraft();
}

function commitRectDraft(current: Extract<Draft, { kind: 'draw-rect' }>) {
  // 文字标注允许「点一下」就落一个默认大小的框；图形则要求真的拖出面积，
  // ⛔ 否则误点一下就会在图上留一个 24×24 的小方块。
  if (props.tool === 'text') {
    const rect = current.moved
      ? rectFromDrag(current.from, current.to)
      : { x: Math.max(0, Math.round(current.from.x)), y: Math.max(0, Math.round(current.from.y)), width: 168, height: 40 };
    emit('text-request', rect);
    return;
  }
  if (!current.moved) return;
  const rect = rectFromDrag(current.from, current.to);
  if (props.tool === 'zone') {
    if (!props.activeZoneId) return;
    emit('zone-shape', { zoneId: props.activeZoneId, ...rect });
    emit('select', `zone:${props.activeZoneId}`);
    return;
  }
  emit('annotation-add', { id: newId(), kind: shapeKindOfTool(), ...rect, ...annotationStyle() });
}

function commitFreehandDraft(current: Extract<Draft, { kind: 'draw-free' }>) {
  const annotation = annotationFromPolyline(newId(), 'freehand', current.points, annotationStyle());
  if (annotation) emit('annotation-add', annotation);
}

function finishPolygon() {
  const points = polygonPoints.value;
  polygonPoints.value = [];
  polygonCursor.value = null;
  const annotation = annotationFromPolyline(newId(), 'polygon', points, annotationStyle());
  if (annotation) emit('annotation-add', annotation);
  emit('draft-change', false);
}

function cancelDraft() {
  polygonPoints.value = [];
  polygonCursor.value = null;
  endDraft();
}

function annotationStyle(): Partial<WarehouseAnnotation> {
  return { color: props.strokeColor, strokeWidth: 2, fill: 'none', fontSize: 16 };
}

function newId(): string {
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `annotation-${Date.now()}-${Math.round(Math.random() * 1e6)}`;
}

// ───────────────────────────── 渲染辅助 ─────────────────────────────

function zoneOf(zoneId: string): WarehouseZone | undefined {
  return props.zones.find((zone) => zone.id === zoneId);
}

/**
 * 区域块上的文字。
 *
 * ⛔ 区域被删掉但几何还在时，标成「已删除的区域」而不是显示空白 ——
 * 空白会让用户以为那是一块没命名的区域，而它其实是个孤块，应该被删掉。
 */
function zoneLabel(zoneId: string): string {
  const zone = zoneOf(zoneId);
  return zone ? `${zone.prefix} · ${zone.name}` : '已删除的区域（孤块）';
}

function isSelected(key: string): boolean {
  return props.selection === key;
}

function annotationHitPath(annotation: WarehouseAnnotation): string {
  return annotationPathData(annotation);
}

defineExpose({ finishPolygon, cancelDraft, hasPolygonDraft: () => polygonPoints.value.length > 0 });
</script>

<template>
  <!-- 底层：只画区域底色。在 vue-flow viewport(z-4) 之下 ⇒ 货架永远压在它上面。 -->
  <svg class="vue-flow__container floor-overlay floor-overlay--base" data-testid="floor-overlay-base">
    <g :transform="paneTransform">
      <rect
        v-for="shape in zoneShapes" :key="`fill-${shape.zoneId}`"
        :x="shape.x" :y="shape.y" :width="shape.width" :height="shape.height" rx="14"
        :fill="zoneOf(shape.zoneId)?.color || '#e2e8f0'" fill-opacity="0.6"
        :stroke="isSelected(`zone:${shape.zoneId}`) ? '#2563eb' : '#94a3b8'"
        :stroke-dasharray="zoneOf(shape.zoneId) ? '7 5' : '3 3'"
        :stroke-width="isSelected(`zone:${shape.zoneId}`) ? 2.5 : 1.5"
        vector-effect="non-scaling-stroke"
      />
    </g>
  </svg>

  <!-- 顶层：标签牌、边框把手、缩放控制点、标注、正在画的草稿。 -->
  <svg
    ref="surface" class="vue-flow__container floor-overlay floor-overlay--top"
    :style="{ pointerEvents: captureHit, cursor: isDrawingTool ? 'crosshair' : 'default' }"
    data-testid="floor-overlay-top"
    @pointerdown="onSurfacePointerDown" @pointermove="onSurfacePointerMove" @dblclick="onSurfaceDoubleClick"
  >
    <g :transform="paneTransform">
      <g v-for="shape in zoneShapes" :key="`chrome-${shape.zoneId}`" class="zone-chrome">
        <!-- 边框带：pointer-events:stroke ⇒ 只有那一圈可抓，内部留给货架 -->
        <rect
          class="zone-grip" :x="shape.x" :y="shape.y" :width="shape.width" :height="shape.height" rx="14"
          fill="none" stroke="transparent" :stroke-width="gripWidth"
          :style="{ pointerEvents: gripHit, cursor: 'move' }" :data-zone-grip="shape.zoneId"
          @pointerdown.stop="startZoneMove(shape, $event)"
        />
        <rect
          class="zone-chip" :x="shape.x + 8 / zoom" :y="shape.y + 8 / zoom"
          :width="Math.min(shape.width - 16 / zoom, (zoneLabel(shape.zoneId).length * 9 + 18) / zoom)"
          :height="24 / zoom" :rx="6 / zoom" fill="#ffffff" fill-opacity="0.86"
          :style="{ pointerEvents: shapeHit, cursor: 'move' }" :data-zone-chip="shape.zoneId"
          @pointerdown.stop="startZoneMove(shape, $event)"
        />
        <text
          class="zone-chip-text" :x="shape.x + 17 / zoom" :y="shape.y + 25 / zoom"
          :font-size="13 / zoom" :fill="zoneOf(shape.zoneId) ? '#17243a' : '#b26a00'"
        >{{ zoneLabel(shape.zoneId) }}</text>
        <rect
          v-for="handle in RESIZE_HANDLES" v-show="isSelected(`zone:${shape.zoneId}`) && interactive && tool === 'select'"
          :key="`zh-${shape.zoneId}-${handle}`" class="shape-handle"
          :x="handleAnchor(shape, handle).x - handleSize / 2" :y="handleAnchor(shape, handle).y - handleSize / 2"
          :width="handleSize" :height="handleSize" fill="#2563eb" stroke="#ffffff" vector-effect="non-scaling-stroke"
          :style="{ pointerEvents: shapeHit, cursor: 'nwse-resize' }" :data-zone-handle="`${shape.zoneId}:${handle}`"
          @pointerdown.stop="startZoneResize(shape, handle, $event)"
        />
      </g>

      <template v-if="showAnnotations">
        <g v-for="annotation in annotations" :key="annotation.id" class="annotation">
          <rect
            v-if="annotation.kind === 'rect'" :x="annotation.x" :y="annotation.y"
            :width="annotation.width" :height="annotation.height" rx="4"
            :fill="annotation.fill && annotation.fill !== 'none' ? annotation.fill : 'transparent'"
            :stroke="annotation.color || '#2563eb'" :stroke-width="annotation.strokeWidth || 2"
            vector-effect="non-scaling-stroke" class="annotation-shape"
            :style="{ pointerEvents: shapeHit, cursor: 'move' }" :data-annotation="annotation.id"
            @pointerdown.stop="startAnnotationMove(annotation, $event)"
          />
          <ellipse
            v-else-if="annotation.kind === 'ellipse'"
            :cx="annotation.x + annotation.width / 2" :cy="annotation.y + annotation.height / 2"
            :rx="annotation.width / 2" :ry="annotation.height / 2"
            :fill="annotation.fill && annotation.fill !== 'none' ? annotation.fill : 'transparent'"
            :stroke="annotation.color || '#2563eb'" :stroke-width="annotation.strokeWidth || 2"
            vector-effect="non-scaling-stroke" class="annotation-shape"
            :style="{ pointerEvents: shapeHit, cursor: 'move' }" :data-annotation="annotation.id"
            @pointerdown.stop="startAnnotationMove(annotation, $event)"
          />
          <text
            v-else-if="annotation.kind === 'text'" :x="annotation.x" :y="annotation.y + (annotation.fontSize || 16)"
            :font-size="annotation.fontSize || 16" :fill="annotation.color || '#17243a'"
            class="annotation-shape annotation-text" :style="{ pointerEvents: shapeHit, cursor: 'move' }" :data-annotation="annotation.id"
            @pointerdown.stop="startAnnotationMove(annotation, $event)"
          >{{ annotation.text }}</text>
          <template v-else>
            <!-- 细线不好点中：叠一条透明的粗线专门做命中区域 -->
            <path
              :d="annotationHitPath(annotation)" fill="none"
              :stroke="annotation.color || '#2563eb'" :stroke-width="annotation.strokeWidth || 2"
              stroke-linecap="round" stroke-linejoin="round" vector-effect="non-scaling-stroke"
            />
            <path
              :d="annotationHitPath(annotation)" fill="none" stroke="transparent" :stroke-width="14 / zoom"
              class="annotation-shape" :style="{ pointerEvents: shapeHit, cursor: 'move' }" :data-annotation="annotation.id"
              @pointerdown.stop="startAnnotationMove(annotation, $event)"
            />
          </template>

          <rect
            v-show="isSelected(`annotation:${annotation.id}`)"
            :x="annotation.x" :y="annotation.y" :width="annotation.width" :height="annotation.height"
            fill="none" stroke="#2563eb" stroke-dasharray="4 3" vector-effect="non-scaling-stroke"
            class="annotation-outline"
          />
          <rect
            v-for="handle in RESIZE_HANDLES"
            v-show="isSelected(`annotation:${annotation.id}`) && interactive && tool === 'select' && annotation.kind !== 'text'"
            :key="`ah-${annotation.id}-${handle}`" class="shape-handle"
            :x="handleAnchor(annotation, handle).x - handleSize / 2" :y="handleAnchor(annotation, handle).y - handleSize / 2"
            :width="handleSize" :height="handleSize" fill="#2563eb" stroke="#ffffff" vector-effect="non-scaling-stroke"
            :style="{ pointerEvents: shapeHit, cursor: 'nwse-resize' }" :data-annotation-handle="`${annotation.id}:${handle}`"
            @pointerdown.stop="startAnnotationResize(annotation, handle, $event)"
          />
        </g>
      </template>

      <rect
        v-if="draftRect" class="draft" :x="draftRect.x" :y="draftRect.y"
        :width="draftRect.width" :height="draftRect.height" rx="6"
        fill="rgba(37,99,235,.12)" stroke="#2563eb" stroke-dasharray="5 4" vector-effect="non-scaling-stroke"
      />
      <path
        v-if="draftPath" class="draft" :d="draftPath" fill="none" stroke="#2563eb"
        stroke-width="2" stroke-linecap="round" vector-effect="non-scaling-stroke"
      />
    </g>
  </svg>
</template>

<style scoped>
/**
 * pointer-events 全部由脚本里的 gripHit / shapeHit / captureHit 计算后**内联绑定**,
 * ⛔ 不写在这里 —— 靠父层 class 级联去开关命中区域, 换个父容器就静默失效
 * (「机制在、没接上」的经典长相)。这里只放与命中无关的纯外观。
 */
.floor-overlay{position:absolute;inset:0}
.floor-overlay--base{z-index:auto}
/* 顶层必须盖过 .vue-flow__viewport(z-index:4), 标注才画得到货架上面 */
.floor-overlay--top{z-index:5}
.zone-chip-text{font-weight:650;user-select:none}
</style>
