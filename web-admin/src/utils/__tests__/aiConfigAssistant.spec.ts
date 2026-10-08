import { afterEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import {
  AI_FAILURE_MESSAGE,
  AI_MODE_LABELS,
  AI_REQUEST_TIMEOUT_MS,
  AI_TIMEOUT_MESSAGE,
  DIFF_TYPE_FALLBACK_LABEL,
  DIFF_TYPE_LABELS,
  aiFailureText,
  diffTypeLabel,
  isNearBottom,
  startAiRequestTimer,
} from '../aiConfigAssistant';
import {
  avatarNodes,
  cssRules,
  declsFor,
  englishLeaks,
  fakeScrollMetrics,
  hasClockText,
  tinyFontSizes,
} from './aiPanelStdHelpers';

afterEach(() => vi.useRealTimers());

describe('startAiRequestTimer (W2: 30 秒超时)', () => {
  it('30 秒整 abort, 并标记 timedOut; 29.9 秒时还没有 (阳性 + 边界)', () => {
    vi.useFakeTimers();
    const t = startAiRequestTimer();
    vi.advanceTimersByTime(AI_REQUEST_TIMEOUT_MS - 100);
    expect(t.signal.aborted).toBe(false);
    expect(t.timedOut).toBe(false);
    vi.advanceTimersByTime(100);
    expect(t.signal.aborted).toBe(true);
    expect(t.timedOut).toBe(true);
    expect(AI_REQUEST_TIMEOUT_MS).toBe(30_000);
  });

  it('请求先完成 (done) 就不会再 abort; cancel 是 abort 但不算超时', () => {
    vi.useFakeTimers();
    const finished = startAiRequestTimer();
    finished.done();
    vi.advanceTimersByTime(60_000);
    expect(finished.signal.aborted).toBe(false);

    const cancelled = startAiRequestTimer();
    cancelled.cancel();
    expect(cancelled.signal.aborted).toBe(true);
    expect(cancelled.timedOut).toBe(false);
  });
});

describe('文案: 超时 / 失败必须说配置没有任何改动, 且不上屏英文或状态码', () => {
  it('超时 / 兜底失败文案', () => {
    expect(AI_TIMEOUT_MESSAGE).toContain('没有任何改动');
    expect(AI_FAILURE_MESSAGE).toContain('没有任何改动');
  });

  it('后端中文原因保留并补「没有任何改动」; 英文 / 状态码 / 空值一律落兜底 (阴性对照)', () => {
    expect(aiFailureText('权限不足')).toBe('权限不足。配置没有任何改动，请稍后重试。');
    expect(aiFailureText('库存不足。')).toContain('没有任何改动');
    for (const bad of ['Request failed with status code 500', 'Internal Server Error', '服务返回 500', '', null, undefined]) {
      expect(aiFailureText(bad as string | null | undefined)).toBe(AI_FAILURE_MESSAGE);
    }
    expect(englishLeaks(AI_TIMEOUT_MESSAGE + AI_FAILURE_MESSAGE)).toEqual([]);
  });
});

describe('isNearBottom (N1, 120px)', () => {
  const el = () => document.createElement('div');
  it('距底 120 以内 = 在底部; 121 = 不在 (边界两侧)', () => {
    const near = el();
    fakeScrollMetrics(near, { scrollHeight: 1000, clientHeight: 300, scrollTop: 580 }); // 距底 120
    expect(isNearBottom(near)).toBe(true);
    const far = el();
    fakeScrollMetrics(far, { scrollHeight: 1000, clientHeight: 300, scrollTop: 579 }); // 距底 121
    expect(isNearBottom(far)).toBe(false);
  });
  it('量不到容器时按在底部处理', () => {
    expect(isNearBottom(undefined)).toBe(true);
    expect(isNearBottom(null)).toBe(true);
  });
});

describe('中文译名表 (V3)', () => {
  it('模式名: 没有英文', () => {
    expect(Object.values(AI_MODE_LABELS).join('')).not.toMatch(/[A-Za-z]/);
    expect(AI_MODE_LABELS).toEqual({ autopilot: '自动配置', plan: '先出方案', action: '影响分析' });
  });

  it('diff 类型: 已登记的全是汉字; 没登记的落「配置变更」而不是原样英文', () => {
    for (const [code, label] of Object.entries(DIFF_TYPE_LABELS)) {
      expect(label, code).toMatch(/^[一-鿿]+$/);
    }
    expect(diffTypeLabel('ADD')).toBe('新增');
    expect(diffTypeLabel('UPDATE')).toBe('修改');
    expect(diffTypeLabel('REMOVE')).toBe('删除');
    expect(diffTypeLabel('SOME_FUTURE_TYPE')).toBe(DIFF_TYPE_FALLBACK_LABEL);
    expect(diffTypeLabel(undefined)).toBe(DIFF_TYPE_FALLBACK_LABEL);
  });

  it('后端 / 前端真实出现过的类型码都登记了', () => {
    for (const code of [
      'TOOL_TOGGLE', 'FIELD_CHANGE', 'TRIGGER_CHAIN_CHANGE', 'VALIDATION_RULE_CHANGE', 'DEFAULT_VALUE_CHANGE',
      'WORKFLOW_CHANGE', 'INFO', 'WORK_PROCESS_CATALOG', 'PRODUCT_WORK_PROCESS_DRAFT',
      'PRODUCT_PROCESS_WORKFLOW_SPEC', 'PRODUCT_PROCESS_WORKFLOW_PATCH', 'APPROVAL_WORKFLOW_SPEC',
    ]) {
      expect(diffTypeLabel(code), code).not.toBe(DIFF_TYPE_FALLBACK_LABEL);
    }
  });
});

describe('测试用扫描器自身 (阴性对照: 它们必须抓得到坏东西)', () => {
  it('tinyFontSizes 抓 10 / 11px, 放过 12 / 13 / 16px; 也抓 font 简写', () => {
    expect(tinyFontSizes('.a{font-size:11px}.b{font-size:10px}')).toEqual([11, 10]);
    expect(tinyFontSizes('.a{font-size:12px}.b{font-size:13px}.c{font-size:16px}')).toEqual([]);
    expect(tinyFontSizes('.a{font: 11px/1.4 sans-serif}')).toEqual([11]);
  });

  it('declsFor / cssRules 按选择器读声明, 逗号选择器会拆开', () => {
    const css = '.m.system, .m.assistant { align-self: stretch } .m.user { border-radius: 12px }';
    expect(declsFor(css, '.m.assistant')).toEqual({ 'align-self': 'stretch' });
    expect(declsFor(css, '.m.user')).toEqual({ 'border-radius': '12px' });
    expect(declsFor(css, '.nope')).toBeNull();
    expect(cssRules(css)).toHaveLength(3);
  });

  it('avatarNodes 抓得到头像节点, 空 DOM 里为 0', () => {
    const Bad = defineComponent({ render: () => h('div', [h('span', { class: 'msg-avatar' }, 'AI')]) });
    const Good = defineComponent({ render: () => h('div', [h('span', { class: 'msg' }, 'hi')]) });
    expect(avatarNodes(mount(Bad).element)).toHaveLength(1);
    expect(avatarNodes(mount(Good).element)).toHaveLength(0);
  });

  it('hasClockText 抓 HH:MM, 不抓普通数字; englishLeaks 抓英文模式名与类型码', () => {
    expect(hasClockText('12:34')).toBe(true);
    expect(hasClockText('已生成 3 项变更')).toBe(false);
    expect(englishLeaks('Autopilot Plan Action TOOL_TOGGLE')).toEqual(['Autopilot', 'Plan', 'Action', 'TOOL_TOGGLE']);
    expect(englishLeaks('自动配置 先出方案 影响分析 开关切换')).toEqual([]);
  });
});
