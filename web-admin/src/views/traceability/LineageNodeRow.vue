<script setup lang="ts">
/**
 * 血缘链上的一行 —— 溯源页「来源链」与个体抽屉「去了哪」**共用这一份**。
 *
 * 左 icon(原料/生产/成品/出货)、中间正文(批次号 / 名称 / 供应商 / 投入量)、
 * 挂了个体时第二段「品种 · 来源场」+ 耳标 + DNA tag; 后端给了 `sourceIndividual.id`
 * 这一行才可点(整行是触控目标, 右侧画 chevron)。
 *
 * ⛔ 不拿 nodeId 当兜底标题, 没批次号就说「未命名批次」。
 * ⛔ 没有个体时整段不渲染 —— 调料/包材本来就没有个体, 那不是缺陷。
 */
import { computed } from 'vue';
import { ArrowRight } from '@element-plus/icons-vue';
import type { LineageNode } from '@/api/traceability';
import {
  UNNAMED_NODE_TEXT,
  dnaStatusLabel,
  dnaStatusTag,
  joinPresent,
  nodeTypeIcon,
  nodeTypeLabel,
} from './traceDisplay';

const props = withDefaults(
  defineProps<{
    node: LineageNode;
    /** 内部页 true; 抽屉里的「去了哪」不需要再念供应商。 */
    showSupplier?: boolean;
    /**
     * 是否显示「投入量」。来源链里它是本节点对下游的投入量, 有意义;
     * 抽屉「去了哪」里下游节点的 quantity 是**该批次全部**上游边的合计
     * (演示租户: 6 头牛合计 3173kg 挂在一头牛的去向下面), 会被读成「这头牛投了 3173kg」——
     * 那是误导, 不显示。
     */
    showQuantity?: boolean;
  }>(),
  { showSupplier: false, showQuantity: true }
);

const emit = defineEmits<{ (e: 'open-individual', id: string): void }>();

const title = computed(() => props.node.batchNumber || UNNAMED_NODE_TEXT);
const unresolved = computed(() => props.node.resolved === false);
const individual = computed(() => props.node.sourceIndividual ?? null);
/** 这一行能不能点进「个体抽屉」—— 只看后端给没给个体主键。 */
const detailId = computed(() => {
  const id = individual.value?.id;
  return typeof id === 'string' && id.length > 0 ? id : null;
});
const individualLine = computed(() =>
  individual.value ? joinPresent([individual.value.breed, individual.value.originFarm]) : null
);
const quantityLine = computed(() =>
  props.node.quantity != null ? `${props.node.quantity}${props.node.unit || ''}` : null
);

function open() {
  if (detailId.value) emit('open-individual', detailId.value);
}
</script>

<template>
  <div
    class="lineage-node"
    :class="{ 'is-clickable': !!detailId }"
    :role="detailId ? 'button' : undefined"
    :tabindex="detailId ? 0 : undefined"
    :data-node-type="node.nodeType"
    data-testid="lineage-node"
    @click="open"
    @keydown.enter.prevent="open"
    @keydown.space.prevent="open"
  >
    <el-icon class="lineage-node__icon" :title="nodeTypeLabel(node.nodeType)">
      <component :is="nodeTypeIcon(node.nodeType)" />
    </el-icon>

    <div class="lineage-node__body">
      <div class="lineage-node__title">{{ title }}</div>
      <div v-if="unresolved" class="lineage-node__line is-error">
        该批次记录已不可查（血缘保留，批次已归档）
      </div>
      <template v-else>
        <div v-if="node.name" class="lineage-node__line">{{ node.name }}</div>
        <div v-if="showSupplier && node.supplierName" class="lineage-node__line">
          供应商：{{ node.supplierName }}
        </div>
      </template>
      <div v-if="showQuantity && quantityLine" class="lineage-node__line">投入量：{{ quantityLine }}</div>

      <div v-if="individual" class="lineage-node__individual" data-testid="lineage-individual">
        <span v-if="individualLine" class="lineage-node__individual-line">{{ individualLine }}</span>
        <span v-if="individual.tagNumber" class="lineage-node__line">耳标：{{ individual.tagNumber }}</span>
        <el-tag
          v-if="dnaStatusLabel(individual.dnaVerificationStatus)"
          :type="dnaStatusTag(individual.dnaVerificationStatus)"
          size="small"
          round
          data-testid="lineage-dna"
        >
          {{ dnaStatusLabel(individual.dnaVerificationStatus) }}
        </el-tag>
      </div>
    </div>

    <el-icon v-if="detailId" class="lineage-node__chevron" data-testid="lineage-chevron">
      <ArrowRight />
    </el-icon>
  </div>
</template>

<style scoped lang="scss">
.lineage-node {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 8px 12px;
  border-radius: 8px;
  color: var(--el-text-color-primary);

  &.is-clickable {
    cursor: pointer;
    transition: background-color 0.2s;

    &:hover,
    &:focus-visible {
      background-color: var(--el-color-primary-light-9);
      outline: none;
    }
  }
}

.lineage-node__icon {
  flex: none;
  margin-top: 3px;
  font-size: 18px;
  color: var(--el-text-color-secondary);
}

.lineage-node__body {
  flex: 1;
  min-width: 0;
}

.lineage-node__title {
  font-weight: 600;
  font-family: var(--font-family-mono);
}

.lineage-node__line {
  font-size: var(--font-size-base);
  color: var(--el-text-color-regular);

  &.is-error {
    color: var(--el-color-danger);
  }
}

.lineage-node__individual {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 12px;
  margin-top: 4px;
}

.lineage-node__individual-line {
  color: var(--el-text-color-primary);
}

.lineage-node__chevron {
  flex: none;
  align-self: center;
  color: var(--el-text-color-secondary);
}
</style>
