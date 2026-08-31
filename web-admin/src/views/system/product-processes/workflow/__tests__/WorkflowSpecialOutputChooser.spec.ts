import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import WorkflowSpecialOutputChooser from '../WorkflowSpecialOutputChooser.vue';
import { SPECIAL_OUTPUT_OPTIONS, specialOutputOption } from '../workflowSpecialOutputs';

const JAVA_ROOT = resolve(
  __dirname, '../../../../../../..',
  'backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow');

function mountChooser() {
  return mount(WorkflowSpecialOutputChooser, {
    props: { visible: true },
    global: {
      stubs: {
        'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' },
        'el-button': { template: '<button><slot /></button>' },
      },
    },
  });
}

describe('§7 统一入口后的三选一', () => {
  it('恰好三类：副产 / 不良品 / 回料产出', () => {
    expect(SPECIAL_OUTPUT_OPTIONS.map((option) => option.kind))
      .toEqual(['byproduct', 'defective', 'rework']);
    const wrapper = mountChooser();
    expect(wrapper.find('[data-testid="special-output-byproduct"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="special-output-defective"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="special-output-rework"]').exists()).toBe(true);
  });

  it('选择可用的一类会带着 kind 抛给画布层', async () => {
    const wrapper = mountChooser();
    await wrapper.get('[data-testid="special-output-byproduct"]').trigger('click');
    await wrapper.get('[data-testid="special-output-rework"]').trigger('click');
    expect(wrapper.emitted('choose')).toEqual([['byproduct'], ['rework']]);
  });
});

describe('§7 不良品 fail closed —— 后端没有权威契约就不许伪装成可用', () => {
  it('不良品是**常驻可见但不可选**的一项，⛔ 不是悄悄隐藏', () => {
    // 隐藏会让「这里不能配」与「这个功能不存在」长得一模一样 —— 用户无从判断。
    const wrapper = mountChooser();
    const option = wrapper.get('[data-testid="special-output-defective"]');
    expect(option.attributes('disabled')).toBeDefined();
    expect(option.attributes('aria-disabled')).toBe('true');
  });

  it('点它不会 emit —— 不会生成任何存得下却没人读的节点', async () => {
    const wrapper = mountChooser();
    await wrapper.get('[data-testid="special-output-defective"]').trigger('click');
    expect(wrapper.emitted('choose')).toBeUndefined();
  });

  it('说出**具体缺口**，⛔ 不是一句「暂不支持」', () => {
    const wrapper = mountChooser();
    const text = wrapper.get('[data-testid="special-output-defective-blocked"]').text();
    expect(text).toContain('WorkflowByproductNodes');
    expect(text).toContain('WorkflowReworkNodes');
    expect(text).not.toContain('暂不支持');
  });

  it('🔴 这个缺口是**实测**的，不是转述：Java 侧确有副产/回料两个判据类，没有不良品的', () => {
    // 形态 B⁷：从一句注释推出行为要先验它。这里直接去后端源码树里数。
    // 如果哪天真的补了不良品的运行契约，这条会红 —— 那正是应该重新评估 available 的时刻。
    const byproduct = readFileSync(resolve(JAVA_ROOT, 'WorkflowByproductNodes.java'), 'utf8');
    const rework = readFileSync(resolve(JAVA_ROOT, 'WorkflowReworkNodes.java'), 'utf8');
    expect(byproduct).toContain('isByproduct');
    expect(rework).toContain('isRework');
    // 阴性对照：没有任何一个判据类读「不良品」标记。
    expect(byproduct).not.toContain('isDefective');
    expect(rework).not.toContain('isDefective');
    expect(specialOutputOption('defective').available).toBe(false);
    expect(specialOutputOption('byproduct').available).toBe(true);
    expect(specialOutputOption('rework').available).toBe(true);
  });

  it('可用的两类不带阻断说明（阴性对照：不是每一项都在喊缺口）', () => {
    expect(specialOutputOption('byproduct').blockedReason).toBe('');
    expect(specialOutputOption('rework').blockedReason).toBe('');
    expect(specialOutputOption('defective').blockedReason.length).toBeGreaterThan(20);
  });
});

describe('§7 三类语义分开写：不良品不能被默认当成可回投库存', () => {
  it('不良品与回料的描述互相点名对方，不混为一谈', () => {
    expect(specialOutputOption('defective').description).toContain('回料');
    expect(specialOutputOption('defective').description).toContain('不进入回投库存');
    expect(specialOutputOption('rework').description).toContain('回到后续批次');
  });
});
