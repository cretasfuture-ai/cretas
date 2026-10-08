/**
 * 配置助手面板 (AI 对话标准) 测试共用的工具。
 *
 * jsdom 不计算 scoped CSS, 所以样式判据读 SFC 的 <style> 源文本, 用 postcss 解析成「选择器 -> 声明」再断言;
 * 每个「X 没出现」的扫描器都带一个能抓到 X 的阴性对照 (见 aiPanelStdHelpers.spec.ts)。
 */
import { readFileSync } from 'node:fs';
import { parse as parseSfc } from '@vue/compiler-sfc';
import postcss from 'postcss';

export interface SfcParts {
  source: string;
  template: string;
  css: string;
}

export function readSfc(absPath: string): SfcParts {
  const source = readFileSync(absPath, 'utf8');
  const { descriptor } = parseSfc(source);
  return {
    source,
    template: descriptor.template?.content ?? '',
    css: descriptor.styles.map((s) => s.content).join('\n'),
  };
}

export interface CssRule { selector: string; decls: Record<string, string> }

/** 把 CSS 展开成「单个选择器 -> 声明」列表 (逗号分隔的选择器拆开; @keyframes / @media 里的规则也展开) */
export function cssRules(css: string): CssRule[] {
  const out: CssRule[] = [];
  postcss.parse(css).walkRules((rule) => {
    if (rule.parent && rule.parent.type === 'atrule' && /keyframes$/i.test((rule.parent as postcss.AtRule).name)) return;
    const decls: Record<string, string> = {};
    rule.walkDecls((d) => { decls[d.prop] = d.value; });
    for (const sel of rule.selectors) out.push({ selector: sel.replace(/\s+/g, ' ').trim(), decls });
  });
  return out;
}

/** 选择器精确等于 selector 的所有规则的声明合并 (后者覆盖前者); 找不到返回 null */
export function declsFor(css: string, selector: string): Record<string, string> | null {
  const hit = cssRules(css).filter((r) => r.selector === selector);
  if (!hit.length) return null;
  return Object.assign({}, ...hit.map((r) => r.decls));
}

/** 所有以 px 声明的 font-size 数值 (含 `font:` 简写里的 px 字号) */
export function pxFontSizes(css: string): number[] {
  const sizes: number[] = [];
  postcss.parse(css).walkDecls((d) => {
    if (d.prop === 'font-size') {
      const m = /^([\d.]+)px$/.exec(d.value.trim());
      if (m) sizes.push(Number(m[1]));
    } else if (d.prop === 'font') {
      const m = /(?:^|\s)([\d.]+)px(?:\s|\/|$)/.exec(d.value);
      if (m) sizes.push(Number(m[1]));
    }
  });
  return sizes;
}

/** T3: 10-11px 的字号 (标准禁止) */
export function tinyFontSizes(css: string): number[] {
  return pxFontSizes(css).filter((n) => n >= 10 && n < 12);
}

/** DOM 里的头像节点 (L1 禁止) */
export function avatarNodes(root: ParentNode): Element[] {
  return Array.from(root.querySelectorAll('.el-avatar, img, [class*="avatar" i], [data-testid*="avatar" i]'));
}

/** L4: 独立的 HH:MM 时间文本 */
export function hasClockText(text: string): boolean {
  return /(^|\s)\d{1,2}:\d{2}(:\d{2})?(\s|$)/.test(text);
}

/** V3: 英文模式名 / 英文类型码 (大写下划线串 ≥4 位, 或 Autopilot/Plan/Action) */
export function englishLeaks(text: string): string[] {
  const hits = text.match(/\b[A-Z][A-Z_]{3,}\b|\b(?:Autopilot|Plan|Action)\b/g);
  return hits ?? [];
}

/** T1 系统字体栈 */
export const AI_FONT_STACK_SNIPPET = '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif';

/** 轻量 Element Plus 替身: 只让按钮 / 输入 / 标签 / 弹层 / 卡片 / 分段选择器按真实语义渲染, 面板本身是真挂载 */
export const ELEMENT_STUBS = {
  'el-button': {
    props: ['disabled', 'type', 'size', 'link', 'loading', 'text', 'circle'],
    emits: ['click'],
    template: '<button class="el-button" :data-size="size" :data-loading="loading ? 1 : 0" :disabled="disabled" @click="$emit(\'click\', $event)"><slot /></button>',
  },
  'el-input': {
    props: ['modelValue'],
    emits: ['update:modelValue', 'keydown'],
    template: '<textarea class="stub-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  'el-tag': { props: ['type', 'size'], template: '<span class="el-tag"><slot /></span>' },
  'el-card': { template: '<div class="el-card"><div class="el-card__header"><slot name="header" /></div><slot /></div>' },
  'el-alert': { props: ['title'], template: '<div class="el-alert">{{ title }}</div>' },
  'el-icon': { template: '<i class="el-icon"><slot /></i>' },
  'el-popover': { template: '<div class="el-popover-stub"><slot name="reference" /><div class="popover-body"><slot /></div></div>' },
  'el-dropdown': { template: '<div class="el-dropdown-stub"><slot /><slot name="dropdown" /></div>' },
  'el-dropdown-menu': { template: '<div><slot /></div>' },
  'el-dropdown-item': { template: '<div class="el-dropdown-item"><slot /></div>' },
  'el-segmented': {
    props: ['modelValue', 'options'],
    emits: ['update:modelValue'],
    template: '<div class="el-segmented-stub"><button v-for="o in options" :key="o.value" type="button" class="seg" :data-value="o.value" @click="$emit(\'update:modelValue\', o.value)">{{ o.label }}</button></div>',
  },
};

/** 一个可被 abort 的挂起请求: 返回 [promise, signal 记录], 供 W2 超时测试用 */
export function pendingUntilAborted<T>() {
  const seen: { signal?: AbortSignal } = {};
  const call = (...args: unknown[]): Promise<T> => {
    const cfg = args.find((a): a is { signal?: AbortSignal } => !!a && typeof a === 'object' && 'signal' in (a as object));
    seen.signal = cfg?.signal;
    return new Promise<T>((_, reject) => {
      cfg?.signal?.addEventListener('abort', () => reject(Object.assign(new Error('canceled'), { code: 'ERR_CANCELED' })));
    });
  };
  return { call, seen };
}

/** N1: 给滚动容器伪造尺寸 (jsdom 里全是 0) */
export function fakeScrollMetrics(el: HTMLElement, m: { scrollHeight: number; clientHeight: number; scrollTop: number }) {
  Object.defineProperty(el, 'scrollHeight', { configurable: true, value: m.scrollHeight });
  Object.defineProperty(el, 'clientHeight', { configurable: true, value: m.clientHeight });
  Object.defineProperty(el, 'scrollTop', { configurable: true, writable: true, value: m.scrollTop });
}
