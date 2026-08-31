<template>
  <div
    class="fused-strip"
    :class="[`fused-strip--${side}`, `fused-strip--${tone}`, {
      'is-collapsed': collapsed,
      'is-unmeasured': side === 'below' && ownerOffset === null,
    }]"
    :style="rootStyle"
    :data-testid="testid"
    :data-fused-side="side"
  >
    <!--
      融合附属卡片的**唯一外壳**。辅料 / 包材 / 特殊产出三类都套它 —— 收起态窄条的形状、
      展开箭头、键盘可达性、省略与 tooltip 只写一遍(形态 D: 同一个东西有两份一定会漂)。
      ⚠️ 注释放在根元素**里面**: 放外面会让组件变成 fragment(注释 + div) 两个根节点,
      于是 VTU 的 wrapper.element 指向注释, classes()/attributes() 全部读空 ——
      那是仪器读不到, 不是样式没生效(形态 A⁗)。本轮实测踩过。
    -->
    <slot name="handles" />
    <button
      type="button"
      class="fused-strip__bar nodrag"
      :class="{ 'is-collapsed': collapsed }"
      :aria-expanded="!collapsed"
      :aria-label="fullLabel"
      :title="fullLabel"
      :data-testid="`${testid}-toggle`"
      @click.stop="emit('toggle')"
    >
      <span class="fused-strip__badge" aria-hidden="true">{{ badge }}</span>
      <!-- 单行可理解 + 过长省略; 完整文案在 title / aria-label 上(§6)。 -->
      <span class="fused-strip__line">{{ summary }}</span>
      <span class="fused-strip__chevron" :class="{ 'is-open': !collapsed }" aria-hidden="true">▾</span>
    </button>
    <div v-if="!collapsed" class="fused-strip__body" :data-testid="`${testid}-body`">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { CSSProperties } from 'vue';
import { fusedStripTransform, type FusedAttachmentSide } from './workflowFusedGroups';

const props = withDefaults(defineProps<{
  side: FusedAttachmentSide;
  /** owner Cell 的 CSS 宽度 —— 融合的硬要求是**同宽**, 不是「差不多宽」。 */
  ownerWidth: number;
  /** 下方附属专用: owner 高度 + 排在自己前面那几条的高度。量不到时传 null。 */
  ownerOffset?: number | null;
  collapsed: boolean;
  /** 窄条左侧的一个字标记, 例如「辅」「包」「回」。 */
  badge: string;
  /** 单行摘要: 类型 · 名称 · 配置状态/数量。 */
  summary: string;
  /** 完整文案(tooltip + aria-label), 摘要被省略时用户仍能读到全文。 */
  fullLabel: string;
  tone?: 'aux' | 'packaging' | 'byproduct' | 'rework';
  testid: string;
}>(), { ownerOffset: null, tone: 'aux' });

const emit = defineEmits<{ toggle: [] }>();

/**
 * ⛔ 几何全部走 transform, **不改 VueFlow 的 node position**。
 *
 * - 上方附属: `translateY(-100%)` —— 位移恰好等于自身高度, 所以底边永远压在 owner 顶边上,
 *   展开/收起自己长高长矮都自动跟上, **一次测量都不需要**。
 * - 下方附属: 位移 = owner 高度(+ 前面几条), 只能实测; 量不到时不渲染出位置(见下方
 *   `is-unmeasured`), ⛔ 不用 0 顶上 —— 那会把「还没测量」画成「压在 owner 身上」。
 *
 * 这就是「展开/收起绝不改变视口」(§10)的**结构性**保证: 没有 node position 变化,
 * VueFlow 就没有任何重新 fit 的理由, 不需要事后 setViewport 去擦漂移。
 */
const rootStyle = computed<CSSProperties>(() => ({
  width: `${props.ownerWidth}px`,
  transform: fusedStripTransform(props.side, props.ownerOffset),
}));
</script>

<style scoped>
.fused-strip {
  position: relative;
  box-sizing: border-box;
  /* 无缝相接: 与 owner 相邻的那条边不留圆角, 也不留外边距。 */
  border: 1px solid var(--fused-border, #e4e7ed);
  background: var(--fused-bg, #fff);
}
.fused-strip--above { border-radius: 10px 10px 0 0; border-bottom-width: 0; }
.fused-strip--below { border-radius: 0 0 10px 10px; border-top-width: 0; }
/* 下方附属还没量到 owner 高度时先不显形 —— 显示在错误位置比暂时不显示更糟。 */
.fused-strip.is-unmeasured { visibility: hidden; }

.fused-strip--aux { --fused-border: #f3d9a4; --fused-bg: #fffaf0; --fused-ink: #8a5a17; --fused-chip: #fbe6c4; }
.fused-strip--packaging { --fused-border: #cfe3d2; --fused-bg: #f6fbf7; --fused-ink: #2f6d46; --fused-chip: #d8efdf; }
.fused-strip--byproduct { --fused-border: #f0d3a8; --fused-bg: #fffcf5; --fused-ink: #b54708; --fused-chip: #fef0c7; }
.fused-strip--rework { --fused-border: #cdd8f0; --fused-bg: #f6f8ff; --fused-ink: #33499b; --fused-chip: #dfe6fb; }

/* 收起态 = 约一行高的窄条。高度由 line-height + padding 决定(随字号/语言变),
   ⛔ 不写死 px —— 交接明确点名不要盲写 42px。 */
.fused-strip__bar {
  display: flex; align-items: center; gap: 8px;
  width: 100%; padding: 5px 10px;
  border: 0; background: transparent; cursor: pointer;
  font-size: 12px; line-height: 1.6; color: var(--fused-ink);
  text-align: left;
}
.fused-strip__bar:hover { background: rgba(0, 0, 0, 0.03); }
.fused-strip__bar:focus-visible { outline: 2px solid #1b65a8; outline-offset: -2px; }
.fused-strip__badge {
  flex: none; display: grid; place-items: center; width: 18px; height: 18px;
  border-radius: 5px; background: var(--fused-chip); font-weight: 700; font-size: 11px;
}
.fused-strip__line {
  flex: 1; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-weight: 600;
}
.fused-strip__chevron { flex: none; transition: transform 120ms ease; }
.fused-strip__chevron.is-open { transform: rotate(180deg); }
@media (prefers-reduced-motion: reduce) { .fused-strip__chevron { transition: none; } }

.fused-strip__body {
  padding: 0 10px 10px;
  border-top: 1px dashed var(--fused-border);
}
</style>
