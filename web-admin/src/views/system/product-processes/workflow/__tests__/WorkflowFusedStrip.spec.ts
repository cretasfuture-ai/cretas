import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import WorkflowFusedStrip from '../WorkflowFusedStrip.vue';
import { fusedStripTransform } from '../workflowFusedGroups';

const read = (name: string): string => readFileSync(resolve(__dirname, '..', name), 'utf8');

/**
 * 🔴 源码闸必须先把**注释剥掉**再扫。
 *
 * 本仓已经栽过四次「闸把自己写的注释当成被测对象」——这条判据的说明文字里就写着
 * 「不要盲写 42px」「不调 setViewport」，不剥注释的话闸会在自己的解释上开火。
 */
function stripComments(source: string): string {
  return source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*/g, '$1');
}

function mountStrip(props: Record<string, unknown> = {}) {
  return mount(WorkflowFusedStrip, {
    props: {
      side: 'above',
      ownerWidth: 440,
      collapsed: true,
      badge: '辅',
      eyebrow: '辅料 cell',
      title: '卤制',
      summary: '2 种 · 每 1kg 投入',
      fullLabel: '展开辅料附属卡片：辅料 · 卤制 · 2 种',
      testid: 'aux-strip',
      ...props,
    },
    slots: { default: '<div class="body-content">展开态内容</div>' },
  });
}

describe('§6 收起态是原型确认的三行卡片', () => {
  it('类型标记 / 类型小标签 / 粗体名称 / 灰色细节各占一行', () => {
    // 🔴 2026-09-01 判据换代（形态 C‴：断言守的是历史，不是需求）。
    //    旧判据钉的是「一行摘要 + 展开箭头」。owner 实测反馈那个形状
    //    「文字特别挤」「按钮太小非常不明显」，已确认原型
    //    (workflow-rework-prototype) 的形状是三行卡片 + 写着字的按钮。
    //    ⇒ 三段信息各自成行, ⛔ 不许再拼回一行。
    const wrapper = mountStrip();
    expect(wrapper.get('.fused-strip__badge').text()).toBe('辅');
    expect(wrapper.get('.fused-strip__eyebrow').text()).toBe('辅料 cell');
    expect(wrapper.get('.fused-strip__name').text()).toBe('卤制');
    expect(wrapper.get('.fused-strip__summary').text()).toBe('2 种 · 每 1kg 投入');
    // 收起时展开态内容不渲染
    expect(wrapper.find('.body-content').exists()).toBe(false);
  });

  it('整条可点击展开，按钮上的字随状态变', async () => {
    const wrapper = mountStrip();
    expect(wrapper.get('.fused-strip__toggle').text()).toBe('展开');
    await wrapper.get('[data-testid="aux-strip-toggle"]').trigger('click');
    expect(wrapper.emitted('toggle')).toHaveLength(1);
    const expanded = mountStrip({ collapsed: false });
    expect(expanded.get('.fused-strip__toggle').text()).toBe('收起');
    expect(expanded.find('.body-content').exists()).toBe(true);
  });

  it('🔴 细节行为空时不渲染那一行 —— ⛔ 不留一条空白占位', () => {
    // 阴性对照：证明三行是**按内容**渲染的，不是无条件画三行。
    const wrapper = mountStrip({ summary: '' });
    expect(wrapper.find('.fused-strip__summary').exists()).toBe(false);
    expect(wrapper.get('.fused-strip__name').text()).toBe('卤制');
  });

  it('🔴 展开按钮要有可点面积和字 —— owner 实测反馈原来的 7px 箭头太小', () => {
    const css = stripComments(read('WorkflowFusedStrip.vue'));
    const rule = css.slice(css.indexOf('.fused-strip__toggle {'));
    expect(rule).toMatch(/min-width:\s*(5[0-9]|[6-9][0-9])px/);   // >= 50px
    expect(rule).toMatch(/height:\s*(2[4-9]|[3-9][0-9])px/);      // >= 24px
    expect(rule).toContain('border:');                             // 看得出是个按钮
    // ⛔ 旧的箭头不许留着 —— 留着就是两套展开控件
    expect(css).not.toContain('fused-strip__chevron');
  });

  it('过长文案由 CSS 省略，全文放 tooltip 与 aria-label', () => {
    const bar = mountStrip().get('[data-testid="aux-strip-toggle"]');
    expect(bar.attributes('title')).toBe('展开辅料附属卡片：辅料 · 卤制 · 2 种');
    expect(bar.attributes('aria-label')).toBe('展开辅料附属卡片：辅料 · 卤制 · 2 种');
    expect(bar.attributes('aria-expanded')).toBe('false');
    expect(mountStrip({ collapsed: false }).get('[data-testid="aux-strip-toggle"]')
      .attributes('aria-expanded')).toBe('true');
  });

  it('⛔ 窄条高度不写死 px —— 由 line-height + padding 决定（交接点名不要盲写 42px）', () => {
    const css = stripComments(read('WorkflowFusedStrip.vue'));
    expect(css).not.toMatch(/\.fused-strip__bar\s*\{[^}]*height:\s*\d+px/);
    expect(css).not.toContain('42px');
    // 三行各自有 line-height, 高度仍然由内容决定
    expect(css).toMatch(/\.fused-strip__name\s*\{[^}]*line-height/);
    // 阳性对照: 剥注释之后闸仍然看得见真正的 CSS —— 否则上面两条是恒真式。
    expect(css).toContain('.fused-strip__bar');
  });

  it('⛔ 不显示任何设计调试信息（原型文件名 / 截图分辨率）', () => {
    const wrapper = mountStrip();
    const text = wrapper.text();
    expect(text).not.toMatch(/\.png|\.html|1920|screenshot|prototype/i);
  });
});

describe('§6/§10 几何：位移全部走 transform，不碰 node position', () => {
  it('上方附属用 translateY(-100%) —— 位移恰好等于自身高度，零测量', () => {
    // 这是「展开/收起结构上不可能改变视口」的承载点：没有任何值需要先量再算，
    // 也就没有任何 node position 会变。
    // ⚠️ 量的是**几何决策函数**而不是 DOM 的 style 属性：jsdom 会把 transform 静默丢掉，
    //    在 DOM 上读到的空串是仪器读不到，不是几何错了（形态 A⁗）。
    expect(fusedStripTransform('above', null)).toBe('translateY(-100%)');
    expect(fusedStripTransform('above', 999)).toBe('translateY(-100%)');
    expect(mountStrip({ side: 'above' }).classes()).toContain('fused-strip--above');
  });

  it('下方附属用实测偏移；量不到时先不显形，⛔ 不用 0 顶上', () => {
    expect(fusedStripTransform('below', 312)).toBe('translateY(312px)');
    const measured = mountStrip({ side: 'below', ownerOffset: 312 });
    expect(measured.classes()).not.toContain('is-unmeasured');

    const unmeasured = mountStrip({ side: 'below', ownerOffset: null });
    // 形态 A¹⁰：0 会把「还没量到」画成「压在 owner 身上」——那比暂时不显示更糟。
    // 所以「量不到」由一个 visibility:hidden 的类兜住，而不是靠一个假的 0 偏移。
    expect(unmeasured.classes()).toContain('is-unmeasured');
    expect(stripComments(read('WorkflowFusedStrip.vue')))
      .toMatch(/\.fused-strip\.is-unmeasured\s*\{[^}]*visibility:\s*hidden/);
  });

  it('与 owner 相接的那条边不留圆角与外边距（无缝相接的判据）', () => {
    const css = stripComments(read('WorkflowFusedStrip.vue'));
    expect(css).toMatch(/\.fused-strip--above\s*\{[^}]*border-radius:\s*10px 10px 0 0/);
    expect(css).toMatch(/\.fused-strip--above\s*\{[^}]*border-bottom-width:\s*0/);
    expect(css).toMatch(/\.fused-strip--below\s*\{[^}]*border-top-width:\s*0/);
    expect(css).not.toMatch(/\.fused-strip\s*\{[^}]*margin/);
  });

  it('⛔ 组件里没有任何 fitView / setViewport / autoLayout 调用（阴性对照）', () => {
    const source = stripComments(read('WorkflowFusedStrip.vue'));
    expect(source).not.toContain('fitView');
    expect(source).not.toContain('setViewport');
    expect(source).not.toContain('AutoLayout');
    // 阳性对照: 剥注释之后代码还在 —— 否则这三条阴性断言在一个空串上恒真。
    expect(source).toContain('fusedStripTransform');
  });
});
