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
      <span
        class="fused-strip__badge"
        :class="{ 'is-pair': !!badgeColor }"
        :style="badgeColor ? { background: badgeColor, color: '#fff' } : undefined"
        aria-hidden="true"
      >{{ badge }}</span>
      <!--
        🔴 2026-09-01 按已确认原型重做。原来是一条 21px 的细缝 + 7px 小箭头,
        owner 实测反馈「按钮太小非常不明显」「文字特别挤」。
        原型(workflow-rework-prototype/index.html)的形状是**三行卡片**:
          eyebrow(类型) / 粗体名称 / 灰色细节行, 右侧一个**写着字**的按钮。
        ⛔ 别再把它压回一行 —— 一行放不下这三段信息, 挤是必然的。
      -->
      <span class="fused-strip__text">
        <span class="fused-strip__eyebrow">{{ eyebrow }}</span>
        <span class="fused-strip__name">{{ title }}</span>
        <span v-if="summary" class="fused-strip__summary">{{ summary }}</span>
      </span>
      <span class="fused-strip__toggle" aria-hidden="true">{{ collapsed ? '展开' : '收起' }}</span>
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
  /** 第一行的类型小标签, 例如「副产 Cell」「辅料 cell」。 */
  eyebrow: string;
  /** 第二行的粗体名称 —— 这条 Cell 叫什么。 */
  title: string;
  /** 第三行的灰色细节: SKU / 数量 / 配置状态。空串则不渲染这一行。 */
  summary: string;
  /** 完整文案(tooltip + aria-label), 摘要被省略时用户仍能读到全文。 */
  fullLabel: string;
  /**
   * 回料的配对色。给了它, 左侧标记就从「回」变成同色的 R 编号 ——
   * 那是这块回料和它的目标工序之间**唯一**的可见关联(原型不画回头线)。
   */
  badgeColor?: string;
  tone?: 'aux' | 'packaging' | 'byproduct' | 'defective' | 'rework';
  testid: string;
}>(), { ownerOffset: null, tone: 'aux', badgeColor: undefined });

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
.fused-strip--defective { --fused-border: #f5c6c2; --fused-bg: #fffbfa; --fused-ink: #b42318; --fused-chip: #fee4e2; }

/*
 * 收起态 = 原型里的三行卡片, ⛔ 不是一行细缝。
 * 高度仍然由内容(line-height + padding)决定, 不写死 px —— 名称长短、语言、
 * 用户字号都会改它。原型的 42/62px 是它自己那个字号下的**结果**, 不是可以照抄的输入。
 */
.fused-strip__bar {
  display: flex; align-items: center; gap: 10px;
  width: 100%; padding: 8px 10px;
  border: 0; background: transparent; cursor: pointer;
  color: var(--fused-ink); text-align: left;
}
.fused-strip__bar:hover { background: rgba(0, 0, 0, 0.03); }
.fused-strip__bar:focus-visible { outline: 2px solid #1b65a8; outline-offset: -2px; }
.fused-strip__badge {
  flex: none; display: grid; place-items: center; width: 24px; height: 24px;
  border-radius: 6px; background: var(--fused-chip); font-weight: 700; font-size: 13px;
}
/* R 编号比单字标记窄, 给它自己的字号免得 R10 被挤出去。 */
.fused-strip__badge.is-pair { font-size: 11px; letter-spacing: -0.2px; }
.fused-strip__text {
  flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 1px;
}
/* 三行各自独立省略 —— 挤在一行里靠 ellipsis 砍掉的正是信息量最大的那一段。 */
.fused-strip__eyebrow,
.fused-strip__name,
.fused-strip__summary {
  min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.fused-strip__eyebrow { font-size: 11px; line-height: 15px; opacity: 0.75; }
.fused-strip__name { font-size: 13px; line-height: 18px; font-weight: 700; }
.fused-strip__summary { font-size: 11px; line-height: 15px; color: #66758a; }
/*
 * 展开/收起按钮 —— owner 实测反馈原来的 7px 箭头「太小非常不明显」。
 * 照原型: 有边框、有底色、**写着字**, 最小宽度保证两个汉字不换行。
 */
.fused-strip__toggle {
  flex: none; display: inline-flex; align-items: center; justify-content: center;
  min-width: 52px; height: 26px; padding: 0 10px;
  border: 1px solid #d8e4ef; border-radius: 6px; background: #fff;
  color: #49647e; font-size: 12px; font-weight: 700; white-space: nowrap;
}
.fused-strip__bar:hover .fused-strip__toggle {
  color: #1b65a8; border-color: #9dc6e8; background: #eef7ff;
}

.fused-strip__body {
  padding: 2px 10px 10px;
  border-top: 1px dashed var(--fused-border);
}
/*
 * 🔴 内部 Cell 必须**撑满**窄条宽度。
 * 实测(owner 反馈「宽度跟下面的宽度应该是一样的」): 窄条按 owner 工序宽度撑开(318px),
 * 而里面的物料 Cell 还用它自己的固定 180px ⇒ 量出来只有 152px, 不到一半。
 * 辅料没这个毛病是因为它本来就没设固定宽度。
 */
.fused-strip__body > :deep(*) { width: 100%; max-width: 100%; box-sizing: border-box; }
/*
 * 窄条**自己**已经是那个框了 —— 里面的 Cell 再画一层边框/阴影就是「盒中盒」,
 * 那正是 owner 说「特别挤」的另一半原因(两层内边距叠在一起)。
 * ⛔ 只在窄条内部去掉, 画布上独立的 Cell 不受影响。
 */
.fused-strip__body > :deep(.material-node),
.fused-strip__body > :deep(.aux-node),
.fused-strip__body > :deep(.packaging-node) {
  border: 0; border-radius: 0; box-shadow: none; background: transparent;
  padding-left: 0; padding-right: 0;
}
</style>
