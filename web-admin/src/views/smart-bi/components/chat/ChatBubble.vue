<script setup lang="ts">
import { computed } from 'vue';
import DOMPurify from 'dompurify';
import { marked } from 'marked';
import { ElAlert } from 'element-plus';
import type { ChatTurn } from '@/types/restaurant-chat';
import RestaurantChartBlock from './RestaurantChartBlock.vue';
import { sanitizeCustomerAiText } from './customerAiText';

/**
 * variant (AI 对话标准, docs/ux/ai-chat-standard):
 *  - 'legacy'   (默认): 餐饮 RestaurantChatPanel 的旧版式, 渲染必须与改前逐字相同。
 *  - 'standard': 工厂租户的新版式 —— 助手全宽无头像无气泡、用户浅色气泡 min(78%, 320px)、
 *                系统字体栈 16px/1.6、markdown 表格改成记录卡 (窄面板不横向滚动)。
 * ⛔ 新增版式只能挂在 variant 后面, 默认值不许改成 'standard'。
 */
const props = withDefaults(defineProps<{
  turn: ChatTurn;
  variant?: 'standard' | 'legacy';
}>(), {
  variant: 'legacy',
});

const isStandard = computed(() => props.variant === 'standard');

/**
 * 窄面板按手机规则 (标准 §3 / G1): markdown 表格 → 「一条记录一张卡」。
 * 输入是已经过 DOMPurify 的 HTML; 输出再过一遍 DOMPurify。
 */
function tablesToRecordCards(html: string): string {
  if (!html.includes('<table')) return html;
  const doc = new DOMParser().parseFromString(`<div id="rc-root">${html}</div>`, 'text/html');
  const root = doc.getElementById('rc-root');
  if (!root) return html;
  root.querySelectorAll('table').forEach((table) => {
    const heads = Array.from(table.querySelectorAll('thead th')).map((th) => th.textContent?.trim() ?? '');
    const rows = Array.from(table.querySelectorAll('tbody tr'));
    if (heads.length === 0 || rows.length === 0) return;
    const wrap = doc.createElement('div');
    wrap.className = 'bubble-records';
    rows.forEach((tr) => {
      const card = doc.createElement('div');
      card.className = 'bubble-record';
      Array.from(tr.children).forEach((cell, i) => {
        const line = doc.createElement('div');
        line.className = 'bubble-record-row';
        const key = doc.createElement('span');
        key.className = 'bubble-record-key';
        key.textContent = heads[i] ?? '';
        const val = doc.createElement('span');
        val.className = 'bubble-record-val';
        val.innerHTML = cell.innerHTML;
        line.append(key, val);
        card.append(line);
      });
      wrap.append(card);
    });
    table.replaceWith(wrap);
  });
  return DOMPurify.sanitize(root.innerHTML);
}

/**
 * Synthesis answers are markdown (mirrors mobile-rest-ai's renderMarkdown /
 * AIQuery.vue's renderMarkdown). Plain Java-intent turns just render as text
 * before this feature — marked+DOMPurify is a superset (plain text passes
 * through unchanged), so this is safe for both turn shapes.
 */
function renderMarkdown(text: string): string {
  if (!text) return '';
  try {
    const html = DOMPurify.sanitize(marked.parse(sanitizeCustomerAiText(text), { breaks: true, gfm: true }) as string);
    return isStandard.value ? tablesToRecordCards(html) : html;
  } catch {
    // marked can throw on pathological input; NEVER feed raw text into v-html
    // (cached answers are tenant-shared) — HTML-escape so a parse failure cannot
    // become an XSS vector. (Fable gate F1)
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }
}

/** el-alert `type` from a 反回扣 alert's honest severity level. */
function alertType(level: string): 'error' | 'warning' {
  return level === 'high' ? 'error' : 'warning';
}
</script>

<template>
  <div class="chat-bubble" :class="[`bubble-${turn.role}`, { 'bubble-std': isStandard }]">
    <div v-if="turn.role === 'ai' && !isStandard" class="bubble-avatar">&#8478;</div>
    <div class="bubble-body">
      <!-- 🔒 反回扣(anti-kickback) alerts — shown ABOVE the answer (fool-proof
           Rule 1/2: visible before the narrative, never buried). -->
      <div v-if="turn.alerts && turn.alerts.length" class="bubble-alerts">
        <el-alert
          v-for="(alert, idx) in turn.alerts"
          :key="idx"
          :type="alertType(alert.level)"
          :title="alert.title"
          :description="alert.detail"
          :closable="false"
          show-icon
          class="bubble-alert-item"
        />
      </div>

      <div
        v-if="turn.role === 'ai'"
        class="bubble-content bubble-content-markdown"
        v-html="renderMarkdown(turn.content)"
      />
      <div v-else class="bubble-content">{{ turn.content }}</div>

      <div v-if="turn.charts && turn.charts.length" class="bubble-charts">
        <RestaurantChartBlock
          v-for="(chart, idx) in turn.charts"
          :key="idx"
          :chart="chart"
        />
      </div>

      <slot name="sections" />
      <slot name="followups" />
      <div v-if="turn.error" class="bubble-error">{{ turn.error }}</div>
    </div>
    <slot name="actions" />
  </div>
</template>

<style scoped>
.chat-bubble {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
}
.bubble-user {
  justify-content: flex-end;
}
.bubble-user .bubble-body {
  background: #2d4a3e;
  color: #faf7f0;
  max-width: 72%;
  padding: 12px 16px;
  border-radius: 14px 14px 4px 14px;
  font-family: 'Noto Serif SC', serif;
}
.bubble-ai {
  justify-content: flex-start;
}
.bubble-ai .bubble-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: #fefcf6;
  border: 1px solid #c9a66b;
  color: #a68449;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: 'Playfair Display', serif;
  font-weight: 900;
  font-size: 16px;
  flex-shrink: 0;
}
.bubble-ai .bubble-body {
  flex: 1;
  background: #fefcf6;
  border: 1px solid #d4cdb8;
  border-left: 4px solid #c9a66b;
  border-radius: 4px 14px 14px 14px;
  padding: 16px 20px;
}
.bubble-label {
  font-family: 'Space Mono', monospace;
  font-size: 10px;
  letter-spacing: 1.5px;
  color: #a68449;
  text-transform: uppercase;
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid #e8e1cc;
}
.bubble-content {
  font-family: 'Noto Serif SC', serif;
  font-size: 14px;
  line-height: 1.7;
  color: #3d3d3d;
}
.bubble-user .bubble-content {
  color: #faf7f0;
}
.bubble-content-markdown {
  :deep(p) { margin: 0.4em 0; }
  :deep(h1), :deep(h2), :deep(h3) { margin: 0.6em 0 0.3em; font-weight: 600; }
  :deep(h3) { font-size: 15px; }
  :deep(ul), :deep(ol) { padding-left: 1.5em; margin: 0.3em 0; }
  :deep(li) { margin: 0.15em 0; }
  :deep(strong) { font-weight: 600; color: #2d4a3e; }
  :deep(code) { background: rgba(0, 0, 0, 0.06); padding: 2px 4px; border-radius: 3px; font-size: 13px; }
  :deep(pre) { background: rgba(0, 0, 0, 0.04); padding: 8px 12px; border-radius: 6px; overflow-x: auto; }
  :deep(blockquote) { border-left: 3px solid #c9a66b; padding-left: 12px; margin: 0.4em 0; color: #6b6b6b; }
  :deep(table) { border-collapse: collapse; margin: 0.5em 0; width: 100%; }
  :deep(th), :deep(td) { border: 1px solid #d4cdb8; padding: 4px 8px; font-size: 13px; }
  :deep(th) { background: #f2ece0; }
}
.bubble-alerts {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 12px;
}
.bubble-alert-item :deep(.el-alert__title) {
  font-family: 'Noto Serif SC', serif;
  font-weight: 600;
}
.bubble-alert-item :deep(.el-alert__description) {
  font-family: 'Noto Serif SC', serif;
  font-size: 12px;
  margin-top: 2px;
}
.bubble-charts {
  display: flex;
  flex-direction: column;
}
.bubble-error {
  color: #8b1a1a;
  font-size: 12px;
  margin-top: 6px;
}

/* ───────── variant="standard" (工厂租户, AI 对话标准) ─────────
 * 以下选择器全部以 .bubble-std 打头: variant 缺省 / 'legacy' 时这个 class 不存在,
 * 餐饮 RestaurantChatPanel 完全不受影响。
 * L1 助手全宽无头像无气泡 · L2 用户浅色气泡 min(78%, 320px)/圆角 12/内边距 10px 12px
 * T1 系统字体栈 · T2 16px/1.6 · T3 小字 ≥ 12px (不出现 10-11px)
 */
.bubble-std {
  --bubble-std-font: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
  flex-direction: column;
  justify-content: flex-start;
  gap: 4px;
  margin-bottom: 16px;
  font-family: var(--bubble-std-font);
  letter-spacing: 0;
}
.bubble-std.bubble-user {
  align-items: flex-end;
}
.bubble-std.bubble-ai {
  align-items: stretch;
}
.bubble-std.bubble-user .bubble-body {
  background: var(--el-fill-color, #f0f2f5);
  color: var(--el-text-color-primary, #303133);
  max-width: min(78%, 320px);
  padding: 10px 12px;
  border-radius: 12px;
  font-family: var(--bubble-std-font);
  overflow-wrap: anywhere;
}
.bubble-std.bubble-ai .bubble-body {
  flex: none;
  width: 100%;
  min-width: 0;
  background: transparent;
  border: 0;
  border-radius: 0;
  box-shadow: none;
  padding: 0;
}
.bubble-std .bubble-content {
  font-family: var(--bubble-std-font);
  font-size: 16px;
  line-height: 1.6;
  letter-spacing: 0;
  color: var(--el-text-color-primary, #303133);
  overflow-wrap: anywhere;
}
.bubble-std.bubble-user .bubble-content {
  color: var(--el-text-color-primary, #303133);
  white-space: pre-wrap;
}
/*
 * ⚠️ 每条都写全选择器, ⛔ 不写成 `.bubble-content-markdown { :deep(ol) {...} }` 这种嵌套:
 * 嵌套会被编译成 `.bubble-content-markdown [data-v-x] ol` —— 要求 v-html 内容里有带 data-v 的节点, 而一个都没有,
 * 整块规则静默失效 (2026-10-08 本机实测: ol 的 padding-left 是 0, 序号挤到正文列左边 17px、压在面板边上;
 * 记录卡没有边框)。写全之后编译成 `.bubble-std .bubble-content-markdown[data-v-x] ol`, 才打得到 v-html 内容。
 * 上面旧版式那块嵌套同样失效, 餐饮已废弃, 不动。
 */
.bubble-std .bubble-content-markdown :deep(p) { margin: 0.4em 0; }
.bubble-std .bubble-content-markdown :deep(h1),
.bubble-std .bubble-content-markdown :deep(h2),
.bubble-std .bubble-content-markdown :deep(h3) { margin: 0.6em 0 0.3em; font-size: 18px; line-height: 1.5; font-weight: 600; }
/* 有序 / 无序列表: 序号留在正文列里 (助手正文没有内边距, 不给 padding-left 序号就跑到列外) */
.bubble-std .bubble-content-markdown :deep(ul),
.bubble-std .bubble-content-markdown :deep(ol) { margin: 0.3em 0; padding-left: 1.5em; }
.bubble-std .bubble-content-markdown :deep(li) { margin: 0.15em 0; }
.bubble-std .bubble-content-markdown :deep(strong) { font-weight: 600; color: inherit; }
.bubble-std .bubble-content-markdown :deep(code) {
  padding: 2px 4px;
  border-radius: 3px;
  background: var(--el-fill-color-light, #f5f7fa);
  font-size: 13px;
}
.bubble-std .bubble-content-markdown :deep(pre) {
  max-width: 100%;
  overflow-x: auto;
  padding: 8px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light, #f5f7fa);
}
.bubble-std .bubble-content-markdown :deep(blockquote) {
  margin: 0.4em 0;
  padding-left: 12px;
  border-left: 3px solid var(--el-border-color, #dcdfe6);
  color: var(--el-text-color-secondary, #909399);
}
.bubble-std .bubble-content-markdown :deep(.bubble-records) { display: flex; flex-direction: column; gap: 8px; margin: 0.5em 0; }
.bubble-std .bubble-content-markdown :deep(.bubble-record) {
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 8px;
  padding: 8px 12px;
}
.bubble-std .bubble-content-markdown :deep(.bubble-record-row) { display: flex; gap: 12px; justify-content: space-between; }
.bubble-std .bubble-content-markdown :deep(.bubble-record-key) { font-size: 13px; color: var(--el-text-color-secondary, #909399); flex-shrink: 0; }
.bubble-std .bubble-content-markdown :deep(.bubble-record-val) { text-align: right; min-width: 0; }
.bubble-std .bubble-alert-item :deep(.el-alert__title) {
  font-family: var(--bubble-std-font);
}
.bubble-std .bubble-alert-item :deep(.el-alert__description) {
  font-family: var(--bubble-std-font);
  font-size: 13px;
}
.bubble-std .bubble-error {
  font-size: 13px;
}
</style>
