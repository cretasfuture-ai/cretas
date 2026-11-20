import { afterEach, describe, expect, it } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import CloseIssueDialog from '../CloseIssueDialog.vue';
import { ISSUE_CONCLUSIONS, validateClose } from '@/api/issues';

/**
 * 关闭必须选结论再写一句（DECISIONS R26/R33；结论清单沿用原型 M8）。
 * 阴性：没选结论 / 结论不在清单 / 说明不足 2 字 ⇒ 前端拦住、不发出 submit。
 * 阳性：选了结论 + 写一句 ⇒ 发出结论码与去空白后的说明。
 */
afterEach(() => { document.body.innerHTML = ''; });

describe('validateClose', () => {
  it('结论清单就是原型那三项（码与后端一致）', () => {
    expect(ISSUE_CONCLUSIONS.map((c) => [c.code, c.label])).toEqual([
      ['HANDLED', '已处理'], ['FALSE_ALARM', '误报'], ['ACCEPTED_DEVIATION', '接受偏差'],
    ]);
  });

  it('阴性：不选结论、清单外的结论、中文标签当码、说明不足 2 字、超长 都拒绝', () => {
    expect(validateClose(null, '已经处理好了')).toContain('先选结论');
    expect(validateClose('', '已经处理好了')).toContain('先选结论');
    expect(validateClose('IGNORED', '已经处理好了')).toContain('结论只能是');
    expect(validateClose('已处理', '已经处理好了')).toContain('结论只能是');
    expect(validateClose('HANDLED', '')).toContain('写一句说明');
    expect(validateClose('HANDLED', '  好  ')).toContain('写一句说明');
    expect(validateClose('HANDLED', '字'.repeat(201))).toContain('最多');
  });

  it('阳性：选了结论并写一句 ⇒ 通过', () => {
    for (const c of ISSUE_CONCLUSIONS) expect(validateClose(c.code, '客户同意')).toBe('');
  });
});

describe('CloseIssueDialog', () => {
  const open = () => mount(CloseIssueDialog, {
    attachTo: document.body,
    props: { modelValue: true, title: '缺料 · 订单 SO-1', submitting: false, error: '' },
    global: { plugins: [ElementPlus] },
  });
  const submitBtn = () => document.querySelector<HTMLButtonElement>('[data-testid="close-submit"]')!;
  const typeReason = async (text: string) => {
    const ta = (document.querySelector<HTMLTextAreaElement>('textarea[data-testid="close-reason"]')
      ?? document.querySelector<HTMLTextAreaElement>('[data-testid="close-reason"] textarea'))!;
    ta.value = text;
    ta.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();
  };

  it('阴性：只写了说明、没选结论 ⇒ 关闭按钮不可点，点了也不提交', async () => {
    const w = open();
    await flushPromises();
    await typeReason('已经和客户确认');
    expect(submitBtn().disabled).toBe(true);
    submitBtn().click();
    await flushPromises();
    expect(w.emitted('submit')).toBeUndefined();
    w.unmount();
  });

  it('阴性：选了结论、说明只有 1 个字 ⇒ 仍不可点', async () => {
    const w = open();
    await flushPromises();
    document.querySelector<HTMLElement>('[data-testid="conclusion-HANDLED"]')!.click();
    await typeReason('好');
    expect(submitBtn().disabled).toBe(true);
    w.unmount();
  });

  it('阳性：选结论 + 写一句 ⇒ 提交结论码和去空白后的说明', async () => {
    const w = open();
    await flushPromises();
    document.querySelector<HTMLElement>('[data-testid="conclusion-ACCEPTED_DEVIATION"]')!.click();
    await typeReason('  客户同意晚两天交 ');
    expect(submitBtn().disabled).toBe(false);
    submitBtn().click();
    await flushPromises();
    expect(w.emitted('submit')).toEqual([[{ conclusion: 'ACCEPTED_DEVIATION', reason: '客户同意晚两天交' }]]);
    w.unmount();
  });

  it('后端拒绝的原因写在对话框里', async () => {
    const w = open();
    await w.setProps({ error: '关闭要先选结论 (已处理 / 误报 / 接受偏差)' });
    await flushPromises();
    expect(document.querySelector('[data-testid="close-error"]')?.textContent).toContain('没有关闭');
    w.unmount();
  });
});
