import {
  THREAD_SIDE_PADDING,
  clarificationAccessibilityLabel,
  measureScrollbarWidth,
  shouldShowActionDescriptions,
  threadRightPadding,
} from '../chatThreadLayout';

describe('A1: 滚动条算进右边距, 正文列 = 屏宽 − 32 (标准 L1)', () => {
  // Codex 375×812 实测: 滚动容器 rect 375 / clientWidth 360, 左右各 16 ⇒ 正文 328
  const SCREEN = 375;
  const bodyWidth = (scrollbar: number) => SCREEN - scrollbar - THREAD_SIDE_PADDING - threadRightPadding(scrollbar);

  it('桌面 Chrome 15px 滚动条: 右内边距 1, 正文 343 (改前 328)', () => {
    expect(measureScrollbarWidth({ offsetWidth: 375, clientWidth: 360 })).toBe(15);
    expect(threadRightPadding(15)).toBe(1);
    expect(bodyWidth(15)).toBe(343);
  });

  it('阴性对照: 手机浮动滚动条 (宽 0) 不动右边距, 正文仍 343', () => {
    expect(measureScrollbarWidth({ offsetWidth: 375, clientWidth: 375 })).toBe(0);
    expect(threadRightPadding(0)).toBe(16);
    expect(bodyWidth(0)).toBe(343);
  });

  it('滚动条宽于 16: 右内边距到 0 为止, 不变负数', () => {
    expect(threadRightPadding(17)).toBe(0);
  });

  it('拿不到 DOM 节点 (原生端 / 节点缺字段): 0', () => {
    expect(measureScrollbarWidth(undefined)).toBe(0);
    expect(measureScrollbarWidth(null)).toBe(0);
    expect(measureScrollbarWidth({})).toBe(0);
  });
});

describe('A2: 同组澄清选项说明要么都有、要么都没有 (标准 F3)', () => {
  it('Codex 实测那组: 只有第 2 个有说明 ⇒ 整组不显示', () => {
    expect(shouldShowActionDescriptions([
      { label: '查询质检不良', description: null },
      { label: '新建质检记录', description: '新建一条质检记录' },
      { label: '质检综合汇总', description: null },
      { label: '重新描述' },
    ])).toBe(false);
  });

  it('每个都有且与名字不同 ⇒ 显示; 说明只是重复名字 / 空白不算有', () => {
    expect(shouldShowActionDescriptions([
      { label: 'A', description: '说明 A' },
      { label: 'B', description: '说明 B' },
    ])).toBe(true);
    expect(shouldShowActionDescriptions([
      { label: 'A', description: '说明 A' },
      { label: 'B', description: 'B' },
    ])).toBe(false);
    expect(shouldShowActionDescriptions([
      { label: 'A', description: '说明 A' },
      { label: 'B', description: '   ' },
    ])).toBe(false);
    expect(shouldShowActionDescriptions([])).toBe(false);
  });

  it('说明并进无障碍标签, 不丢', () => {
    expect(clarificationAccessibilityLabel({ label: '新建质检记录', description: '新建一条质检记录' })).toBe('新建质检记录，新建一条质检记录');
    expect(clarificationAccessibilityLabel({ label: '重新描述', description: null })).toBe('重新描述');
    expect(clarificationAccessibilityLabel({ label: 'B', description: 'B' })).toBe('B');
  });
});
