<template>
  <div
    class="process-node"
    :class="{ selected, 'wf-dim': isConnectDimmed, 'wf-valid': isValidConnectTarget }"
    :style="processNodeStyle"
  >
    <!-- BOM 辅料投影的专用视觉锚点，只承载派生投影连线，不创建真实工艺连线。 -->
    <Handle
      type="target"
      :position="Position.Top"
      :id="AUX_OVERLAY_TARGET_HANDLE"
      :connectable="false"
    />
    <Handle
      v-for="(port, index) in inputPorts"
      :key="port.id"
      :id="port.id"
      type="target"
      :position="Position.Left"
      :style="handleStyle(index, inputPorts.length)"
    />
    <Handle
      v-for="(port, index) in outputPorts"
      :key="port.id"
      :id="port.id"
      type="source"
      :position="Position.Right"
      :style="handleStyle(index, outputPorts.length)"
    />

    <button
      v-if="canWrite"
      type="button"
      class="cell-delete nodrag"
      title="删除此工序 Cell"
      data-testid="delete-process-cell"
      @click.stop="emit('delete')"
    >✕ 删除</button>

    <div class="process-heading">
      <span class="step-mark">序</span>
      <div>
        <div class="eyebrow">工序 Cell</div>
        <div class="process-name">{{ data.processName }}</div>
      </div>
      <button
        v-if="canWrite"
        type="button"
        class="quick-edit-process nodrag"
        title="快捷编辑工序"
        aria-label="快捷编辑工序"
        data-testid="quick-edit-process"
        @click.stop="emit('editProcess')"
      >编辑工序</button>
    </div>

    <div class="system-inference" data-testid="system-inference">
      <span class="system-inference-badge">
        可报工接口 · {{ inputPorts.length }} 个投入 · {{ outputPorts.length }} 个产出
      </span>
      <el-tooltip placement="right" content="正式报工时按实际情况选择，投入和产出至少各一项">
        <span class="io-help-mark nodrag" data-testid="reporting-help">?</span>
      </el-tooltip>
    </div>

    <section class="port-section">
      <div class="section-title">
        <span class="section-label">
          投入物料
          <el-tooltip
            placement="right"
            :content="'Workflow 只声明可投入物料；主料和替代料由生产计划固定的 BOM 自动限定。'"
          ><span class="io-help-mark nodrag" data-testid="input-help">?</span></el-tooltip>
        </span>
        <el-button v-if="canWrite && allowAddInput" text size="small" type="primary" class="nodrag" @click="emit('addInput')">+ 来源 Cell（合流）</el-button>
      </div>
      <!--
        🔴 说明文字收进小问号(owner 2026-09-01:「工序里面的文字稍微节俭一点, 要不然
        整个工序 cell 太高了; 投入物料/产出物料/单位关系里面的解释直接隐藏为小问号」)。
        ⛔ 不是删掉: 这几段解释各自都在挡一个真实误解(以为这里要配比例/主副角色/数量),
        删了那些误解会回来。⇒ 换成按需查看。
      -->
      <div v-for="port in inputPorts" :key="port.id" class="port-row">
        <el-input
          class="nodrag"
          :model-value="port.materialName || '由上游 Cell 自动带入'"
          readonly
          size="small"
        />
        <span class="unit-chip" data-testid="input-unit-chip">{{ unitLabel(port.unit) }}</span>
        <!-- 主原料份额(%): owner 2026-09-02 走 (a)。多于一个投入才有「配比」可言; 单投入不显示。 -->
        <el-tooltip
          v-if="inputPorts.length > 1"
          placement="top"
          content="主原料份额(%)。填 30/70 后, 领料与报工时填任一原料的量, 其余按比例自动跳。留空=不参与推算"
        >
          <!-- 原生 input 而不是 el-input-number: 画布上有一道闸「ElInputNumber 必须为 0 个」
               守的是「这里不录投入产出数量」; 份额不是数量, 但用同一个组件会让那道闸失去意义。 -->
          <input
            class="nodrag share-input"
            type="text"
            inputmode="decimal"
            :value="port.inputShareRatio ?? ''"
            placeholder="份额%"
            data-testid="input-share"
            :aria-label="`${port.materialName || '投入'} 份额%`"
            @change="onShareInput(port.id, $event)"
          />
        </el-tooltip>
      </div>
      <div v-if="inputShareWarning" class="share-warning" data-testid="input-share-warning">{{ inputShareWarning }}</div>
    </section>

    <section class="port-section output-section">
      <div class="section-title">
        <span class="section-label">
          产出物料
          <el-tooltip placement="right" :content="OUTPUT_HELP">
            <span class="io-help-mark nodrag" data-testid="output-help">?</span>
          </el-tooltip>
        </span>
        <span v-if="canWrite" class="section-actions">
          <el-button
            text
            size="small"
            type="primary"
            class="nodrag"
            data-testid="add-output-inline"
            @click.stop="emit('addOutput')"
          >+ 产出 Cell（分流）</el-button>
        </span>
      </div>
      <div v-for="port in outputPorts" :key="port.id" class="output-entry">
        <div class="port-row output-row">
          <WorkflowSkuPicker
            class="nodrag"
            test-id="output-sku-select"
            :model-value="port.skuId || ''"
            :semi-options="semiOptions"
            :finished-options="finishedOptions"
            :disabled="!canWrite"
            placeholder="选择或现场创建产出 SKU"
            @change="(skuId) => emit('selectOutput', port.id, skuId)"
          />
          <span class="unit-chip" data-testid="output-unit-chip">{{ unitLabel(port.unit) }}</span>
        </div>
      </div>
    </section>

    <section class="unit-rule-section">
      <div class="section-title"><span>单位关系</span></div>
      <div class="unit-only-summary" data-testid="unit-only-summary">
        <strong>{{ unitRelationshipSummary }}</strong>
        <el-tooltip placement="right" :content="UNIT_HELP">
          <span class="io-help-mark nodrag" data-testid="unit-help">?</span>
        </el-tooltip>
        <small
          v-if="primaryOutputSpecification"
          :title="`成品 SKU 规格：${primaryOutputSpecification}，计划、报工与结单需要折算时自动使用。`"
        >
          规格 {{ primaryOutputSpecification }}
        </small>
      </div>
      <div class="unit-relationship-list" data-testid="unit-relationship-list">
        <div
          v-for="(port, index) in inputPorts"
          :key="`unit:${port.id}`"
          class="unit-relationship-row"
          :class="{ 'is-long-name': isLongMaterialName(port.materialName) }"
        >
          <div class="unit-port-name">
            <el-tag size="small" type="info">投入{{ index + 1 }}</el-tag>
            <span>{{ port.materialName || `投入 ${index + 1}` }}</span>
          </div>
          <div class="unit-flow-chip" data-testid="unit-flow-input">
            {{ unitLabel(port.unit) }}投入 <span>→</span> {{ unitLabel(primaryOutput?.unit) }}产出
          </div>
        </div>
        <div v-for="(port, index) in outputPorts" :key="`unit:${port.id}`" class="unit-relationship-row">
          <div class="unit-port-name">
            <el-tag size="small" type="success">产出{{ index + 1 }}</el-tag>
            <span>{{ port.materialName || `产出 ${index + 1}` }}</span>
          </div>
          <div class="unit-flow-chip" data-testid="unit-flow-output">
            报工单位：{{ unitLabel(port.unit) }}
          </div>
        </div>
      </div>
    </section>

    <!--
      §7: 每个可写工序底部**只保留一个常驻入口**。副产 / 不良品 / 回料原先是三个(或两个)
      并排的小按钮, 收敛到这里再三选一。

      ⛔ 「常驻」是硬要求, 不是样式偏好: 它是「这里可以加, 只是还没加」与「这里不能加」
         唯一的区分手段(继承自浮层时代「没有副产也派生空 cell」那条判据)。
         只允许按写权限(canWrite)收, ⛔ 不允许按「有没有特殊产出」收。
    -->
    <div v-if="canWrite" class="special-output-dock nodrag">
      <el-button
        text
        size="small"
        class="nodrag special-output-add"
        data-testid="add-special-output"
        @click.stop="emit('addSpecialOutput')"
      >+ 添加特殊产出</el-button>
      <span class="special-output-hint">副产 / 不良品 / 回料产出</span>
    </div>

    <!--
      🔴 回投关系用**同色 R 编号配对**表达，⛔ 不画回头线（已确认原型 v8：
      「回料使用同色 R 编号配对，不绘制永久回头线」）。
      一张多线路的图上，回头线会变成十几条横穿画布的线，谁连谁根本读不出来。
      这里是配对的**另一半**：回料 Cell 上有 R1，允许它回投的工序上也有 R1。
    -->
    <div v-if="reworkPairs.length > 0" class="rework-pair-row nodrag" data-testid="rework-pair-row">
      <span class="rework-pair-row__label">可接回料</span>
      <!--
        可点 —— 原型里 `.pair-chip` 是 `cursor:pointer`。点一下钉住这一对:
        画布压暗，只留这道工序和那块回料，并在**远端**打上信标。
        回投的目标往往在画布另一头，不给一个「带我去看」的动作就等于看不见。
        ⛔ 它只改高亮，一个字段都不写(与勾选目标工序严格无关)。
      -->
      <button
        v-for="pair in reworkPairs"
        :key="pair.label"
        type="button"
        class="rework-pair-chip nodrag"
        :class="{ 'is-active': activePairLabel === pair.label }"
        :style="{ background: pair.color }"
        :aria-pressed="activePairLabel === pair.label"
        :title="`${pair.label} 回料可回投到本工序 —— 点一下在画布上定位它`"
        :data-testid="`rework-pair-${pair.label}`"
        @click.stop="emit('togglePair', pair.reworkNodeId)"
      >{{ pair.label }}</button>
    </div>

    <div class="reporting-row nodrag">
      <span>本道是否报工</span>
      <el-switch
        :model-value="data.reportingRequired"
        :disabled="!canWrite"
        inline-prompt
        active-text="报工"
        inactive-text="免报"
        size="small"
        @change="(value: boolean) => emit('update', { reportingRequired: value })"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { Handle, Position } from '@vue-flow/core';
import { AUX_OVERLAY_TARGET_HANDLE } from './bomOverlay';
import WorkflowSkuPicker, { type WorkflowSkuPickerOption } from './WorkflowSkuPicker.vue';
import { workflowDisplayUnit, workflowSkuSpecificationEquation } from './workflowUnits';
import { shareSumWarning } from './workflowInputShare';
import type { ProcessNodeData } from './types';

const props = withDefaults(defineProps<{
  /** 允许回投到本工序的回料 R 角标（同色配对，见模板里的说明）。 */
  reworkPairs?: Array<{ label: string; color: string; reworkNodeId: string }>;
  /** 当前被钉住的那一对（用来把角标画成按下态）；没钉住时为 null。 */
  activePairLabel?: string | null;
  data: ProcessNodeData;
  selected?: boolean;
  canWrite: boolean;
  /** #8: 拖拽连线中源 cell 的类型；用于给非法目标 cell 灰化 */
  connectingFromKind?: '' | 'MATERIAL' | 'PROCESS';
  semiOptions: WorkflowSkuPickerOption[];
  finishedOptions: WorkflowSkuPickerOption[];
  allowAddInput?: boolean;
  skuSpecifications?: Record<string, { unit: string; gramsPerUnit: number | null }>;
}>(), { allowAddInput: true, reworkPairs: () => [], activePairLabel: null });

// #8: 工序 Cell 只有在「物料拖向工序(投入)」时才是合法目标；「工序拖向物料」时
// 工序互相之间非法 → 灰化。
const isValidConnectTarget = computed(() => props.connectingFromKind === 'MATERIAL');
const isConnectDimmed = computed(() => !!props.connectingFromKind && !isValidConnectTarget.value);

/** 收进问号里的两段长说明。⛔ 不要塞回模板 —— 它们是 cell 变高的主因。 */
const OUTPUT_HELP = '这里不配置产出关系、主副角色或固定比例；每次报工填写本次实际产出的 SKU 和数量。'
  + '副产同理——只在这里指定它是哪个 SKU，产出多少在报工时填。'
  + '回料同理——标记为回料的产出会保留在报工选项里，供操作员登记退回复投的数量。';
const UNIT_HELP = '这里只约定各物料的报工单位，不预设投入产出数量；'
  + '实际投入、实际产出和出成率以正式报工为准。';

const emit = defineEmits<{
  update: [patch: Partial<ProcessNodeData>];
  addInput: [];
  addOutput: [];
  /**
   * 统一的特殊产出入口 —— 画布层弹三选一(副产 / 不良品 / 回料), 选完再走
   * `addOutputToProcess` 的同一条路径。⛔ 这里不新造 addByproduct/addRework 两个函数:
   * 副产与回料仍然只是 data 上的一个标记(isByproduct / isRework), 不是新的 node kind。
   */
  addSpecialOutput: [];
  /** 点 R 角标：钉住/取消这一对（只改高亮，⛔ 不写任何字段）。 */
  togglePair: [reworkNodeId: string];
  selectOutput: [portId: string, skuId: string];
  delete: [];
  editProcess: [];
}>();

const processNodeStyle = { minHeight: '96px' } as const;
/**
 * 单位一律走显示名再渲染 —— 存储里 `box` 与 `盒` 都合法(后端 alias 归一到同一个码),
 * 直接渲染原始串就会在同一张画布上两种写法来回跳(Steve 实测)。见 workflowDisplayUnit。
 */
function unitLabel(value: string | null | undefined): string {
  return workflowDisplayUnit(value);
}
const inputPorts = computed(() => props.data.ports.filter((port) => port.direction === 'INPUT'));
const inputShareWarning = computed(() => shareSumWarning(inputPorts.value.map((p) => p.inputShareRatio)));
/** 只改这一条端口的份额, 其余端口原样; 通过统一的 update 出口回写(会进 nodesJson ⇒ 发布时成为新修订)。 */
function onShareInput(portId: string, event: Event): void {
  const raw = String((event.target as HTMLInputElement | null)?.value ?? '').trim();
  const parsed = raw === '' ? Number.NaN : Number(raw);
  // 空 = 不参与推算; 非数/超界 = 当作没填(⛔ 不夹逼成 0 或 100 —— 那会造出一个用户没填的份额)
  setInputShare(portId, Number.isFinite(parsed) && parsed >= 0 && parsed <= 100 ? parsed : undefined);
}
function setInputShare(portId: string, value: number | undefined): void {
  const next = value == null || !Number.isFinite(value) ? null : value;
  emit('update', {
    ports: props.data.ports.map((port) => (port.id === portId ? { ...port, inputShareRatio: next } : port)),
  });
}
const outputPorts = computed(() => props.data.ports.filter((port) => port.direction === 'OUTPUT'));
const primaryOutput = computed(() => [...outputPorts.value].sort((a, b) => a.ordinal - b.ordinal)[0]);
const unitRelationshipSummary = computed(() => {
  const inputUnits = [...new Set(inputPorts.value.map((port) => unitLabel(port.unit)).filter(Boolean))];
  const outputUnits = [...new Set(outputPorts.value.map((port) => unitLabel(port.unit)).filter(Boolean))];
  const inputLabel = inputUnits.length === 1 ? inputUnits[0] : inputUnits.join(' / ');
  const outputLabel = outputUnits.length === 1 ? outputUnits[0] : outputUnits.join(' / ');
  return `投入单位：${inputLabel || '待绑定'} · 产出单位：${outputLabel || '待绑定'}`;
});
const primaryOutputSpecification = computed(() => {
  const port = primaryOutput.value;
  if (!port) return null;
  const spec = port.skuId ? props.skuSpecifications?.[port.skuId] : undefined;
  return workflowSkuSpecificationEquation(spec?.unit || port.unit, spec?.gramsPerUnit);
});

function isLongMaterialName(name: string | undefined): boolean {
  const visualLength = Array.from(name || '').reduce(
    (total, character) => total + (/^[\x00-\x7F]$/.test(character) ? 1 : 2),
    0,
  );
  return visualLength > 24;
}

function handleStyle(index: number, count: number): Record<string, string> {
  return { top: `${((index + 1) / (count + 1)) * 100}%` };
}

</script>

<style scoped>
.rework-pair-row {
  display: flex; align-items: center; gap: 6px;
  margin: 6px 0 0; padding: 5px 8px;
  border-radius: 6px; background: #f4f7fb;
}
.rework-pair-row__label { color: #66758a; font-size: 12px; }
.rework-pair-chip {
  display: inline-grid; place-items: center; min-width: 26px; height: 22px;
  padding: 0 7px; border: 0; border-radius: 11px;
  color: #fff; font-size: 11px; font-weight: 800; letter-spacing: -0.2px;
  cursor: pointer; transition: transform 120ms ease, box-shadow 120ms ease;
}
.rework-pair-chip:hover,
.rework-pair-chip.is-active {
  transform: translateY(-1px) scale(1.06);
  box-shadow: 0 0 0 3px color-mix(in srgb, currentColor 0%, transparent);
  filter: brightness(1.06);
}
.rework-pair-chip:focus-visible { outline: 2px solid #1b65a8; outline-offset: 2px; }
@media (prefers-reduced-motion: reduce) { .rework-pair-chip { transition: none; } }
.special-output-dock {
  display: flex; align-items: center; gap: 8px;
  margin-top: 10px; padding-top: 8px; border-top: 1px dashed #dfe6f2;
}
.io-help-mark {
  display: inline-grid; place-items: center; width: 16px; height: 16px; margin-left: 6px;
  border-radius: 50%; background: #eef2f7; color: #7a8798;
  font-size: 11px; font-weight: 700; cursor: help; vertical-align: middle;
}
.io-help-mark:hover { background: #e0ebf7; color: #1b65a8; }
.share-input {
  width: 58px; margin-left: 4px; padding: 2px 6px; font-size: 11px;
  border: 1px solid #dcdfe6; border-radius: 4px; text-align: right; background: #fff;
}
.share-input:focus { outline: none; border-color: #409eff; }
.share-warning { margin: 2px 0 4px; color: #b7791f; font-size: 11px; }
.special-output-hint { color: #8a94a6; font-size: 11px; }

/* #8 拖拽连线视觉: 灰化非法目标 / 高亮合法目标 / handle 悬停显现. 仅 opacity/transform. */
.process-node { transition: opacity 150ms ease, box-shadow 150ms ease; }
.process-node.wf-dim { opacity: 0.4; cursor: not-allowed; }
.process-node.wf-valid { box-shadow: 0 0 0 2px #1b65a8, 0 0 12px rgba(27, 101, 168, 0.35); }
.process-node :deep(.vue-flow__handle) {
  width: 18px; height: 18px; opacity: 0.5; border-width: 3px;
  transform-origin: center;
  transition: opacity 150ms ease, transform 120ms ease;
}
.process-node:hover :deep(.vue-flow__handle),
.process-node.wf-valid :deep(.vue-flow__handle) { opacity: 1; }
.process-node :deep(.vue-flow__handle-left):hover {
  opacity: 1; transform: translate(-50%, -50%) scale(1.35); cursor: crosshair;
}
.process-node :deep(.vue-flow__handle-right):hover {
  opacity: 1; transform: translate(50%, -50%) scale(1.35); cursor: crosshair;
}
@media (prefers-reduced-motion: reduce) {
  .process-node, .process-node :deep(.vue-flow__handle) { transition: none; }
}
/* #9 删除 Cell 按钮 (选中/悬停时出现, 右上角) */
.cell-delete {
  position: absolute; top: -10px; right: -10px; z-index: 5;
  padding: 2px 8px; font-size: 12px; line-height: 1.4;
  color: #fff; background: #f56c6c; border: none; border-radius: 12px;
  cursor: pointer; box-shadow: 0 2px 6px rgba(245, 108, 108, 0.4);
}
.cell-delete:hover { background: #f23c3c; }
.cell-delete, .quick-edit-process { opacity: 0; pointer-events: none; transition: opacity 120ms ease; }
.process-node:hover .cell-delete,
.process-node:hover .quick-edit-process,
.process-node:focus-within .cell-delete,
.process-node:focus-within .quick-edit-process,
.process-node.selected .cell-delete,
.process-node.selected .quick-edit-process { opacity: 1; pointer-events: auto; }
@media (hover: none) {
  .process-node .cell-delete, .process-node .quick-edit-process { opacity: 1; pointer-events: auto; }
}

.process-node {
  position: relative;
  width: 440px; padding: 14px; border: 1px solid #b9d8f4; border-left: 4px solid #409eff;
  border-radius: 10px; background: #fff; box-shadow: 0 2px 12px rgba(27, 101, 168, 0.09);
}
.process-node.selected { box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.3); }
.process-heading { display: flex; align-items: flex-start; gap: 8px; }
.process-heading > div { min-width: 0; flex: 1; }
.quick-edit-process {
  min-height: 32px; flex: 0 0 auto;
  padding: 0 10px; border: 1px solid #b9d8f4; border-radius: 6px; background: #fff;
  color: #1b65a8; cursor: pointer; font-size: 12px; font-weight: 600;
}
.quick-edit-process:hover { border-color: #409eff; background: #eef6ff; }
.step-mark { display: grid; place-items: center; width: 28px; height: 28px; border-radius: 7px; color: #1b65a8; background: #eaf4ff; font-weight: 700; }
.eyebrow { color: #7a8599; font-size: 11px; }
.process-name { color: #1a2332; font-size: 15px; font-weight: 700; }
/* 长文案收进问号后不再需要换行 —— 徽标与 `?` 同一行, 省一整行高度。 */
.system-inference { display: flex; align-items: center; gap: 2px; margin-top: 6px; }
.section-label { display: inline-flex; align-items: center; }
.system-inference-badge { width: fit-content; padding: 2px 8px; border-radius: 999px; background: #eef6ff; color: #1b65a8; font-size: 11px; font-weight: 650; }
.port-section, .unit-rule-section { margin-top: 12px; padding-top: 10px; border-top: 1px solid #edf2f7; }
.section-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; color: #475467; font-size: 12px; font-weight: 650; }
/* 两个建产出按钮并排; space-between 只在"标题 vs 按钮组"之间生效, 按钮之间用 gap。 */
.section-actions { display: inline-flex; align-items: center; gap: 2px; }
/* 副产用琥珀色与主产出的蓝色区分 —— owner 要求副产和主产出在视觉上分得开。 */
.byproduct-add { color: #b54708; }
.byproduct-add:hover { color: #93370d; }
.port-row { display: grid; grid-template-columns: minmax(0, 1fr) 76px; gap: 6px; margin-top: 6px; }
.output-row { grid-template-columns: minmax(0, 1fr) 70px; }
.output-entry { margin-top: 6px; }
.output-entry .output-row { margin-top: 0; }
.output-contract-row {
  display: grid; grid-template-columns: minmax(132px, 0.8fr) minmax(190px, 1.2fr);
  align-items: center; gap: 6px; margin-top: 6px; padding: 7px 8px;
  border: 1px solid #dce8f3; border-radius: 7px; background: #f8fbff;
}
.output-contract-row label {
  display: grid; grid-template-columns: auto minmax(78px, 1fr) auto;
  align-items: center; gap: 5px; color: #475467; font-size: 12px;
}
.output-contract-row :deep(.el-select),
.output-contract-row :deep(.el-input-number) { width: 100%; }
.output-contract-help {
  margin-top: 6px; padding: 7px 8px; border-radius: 7px;
  background: #fff7e8; color: #8a6116; font-size: 11px; line-height: 1.5;
}
.port-relation { margin: 5px 0 7px; padding: 7px 8px; border-radius: 7px; background: #f7f9fc; }
.port-relation-control { display: grid; grid-template-columns: auto minmax(150px, 1fr); align-items: center; gap: 8px; color: #475467; font-size: 12px; font-weight: 650; }
.port-relation-control :deep(.el-select) { width: 100%; }
.port-relation-help { margin-top: 5px; color: #667085; font-size: 12px; line-height: 1.5; }
.port-relation-labels { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 5px; }
.quantity-rule-note {
  padding: 8px 10px; border-radius: 7px; background: #eef6ff; color: #1b65a8;
  font-size: 12px; font-weight: 600; line-height: 1.4;
}
.unit-relationship-list { display: flex; flex-direction: column; gap: 8px; margin-top: 8px; }
.unit-relationship-row {
  display: grid; grid-template-columns: minmax(0, 1fr) minmax(158px, 42%); gap: 6px;
  align-items: center; min-height: 32px; padding: 6px 8px; border: 1px solid #edf2f7; border-radius: 7px;
}
.unit-port-name { display: flex; align-items: center; gap: 6px; min-width: 0; color: #475467; font-size: 12px; }
.unit-port-name > span:last-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.unit-relationship-row.is-long-name { grid-template-columns: minmax(0, 1fr); gap: 5px; }
.unit-relationship-row.is-long-name .unit-port-name > span:last-child {
  overflow: visible; text-overflow: clip; white-space: normal; overflow-wrap: anywhere; line-height: 1.35;
}
.unit-relationship-row.is-long-name .unit-flow-chip { justify-self: end; width: min(100%, 190px); }
/* `?` 与摘要同一行, `<small>` 换行 —— 两行, 原先是三行加一段说明。 */
.unit-only-summary {
  display: flex; flex-wrap: wrap; align-items: center; column-gap: 2px; row-gap: 4px;
  padding: 9px 10px; border-radius: 7px;
  background: #f7f9fc; color: #667085; font-size: 12px; line-height: 1.45;
}
.unit-only-summary strong { color: #344054; }
.unit-only-summary small { flex-basis: 100%; color: #1b65a8; }
.unit-flow-chip {
  padding: 5px 8px; border-radius: 6px; background: #f7f9fc; color: #667085;
  font-size: 12px; font-weight: 600; text-align: center;
}
.unit-flow-chip span { color: #98a2b3; padding: 0 3px; }
.reporting-row { display: flex; align-items: center; justify-content: space-between; margin-top: 10px; color: #667085; font-size: 12px; }
.unit-chip {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 24px;
  padding: 0 8px;
  border-radius: 6px;
  background: #f0f4f9;
  color: #5b6577;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}
.edge-output-add {
  position: absolute;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 2px solid #1b65a8;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 2px 8px rgba(27, 101, 168, 0.2);
  color: #1b65a8;
  font-size: 20px;
  font-weight: 700;
  line-height: 1;
  cursor: pointer;
}
.edge-output-add:hover { background: #eaf4ff; }
:deep(.vue-flow__handle) { border-color: #fff; background: #1b65a8; }
</style>
