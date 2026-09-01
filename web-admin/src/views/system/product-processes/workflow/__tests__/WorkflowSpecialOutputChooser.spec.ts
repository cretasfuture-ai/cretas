import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import WorkflowSpecialOutputChooser from '../WorkflowSpecialOutputChooser.vue';
import { SPECIAL_OUTPUT_OPTIONS, specialOutputOption } from '../workflowSpecialOutputs';

const read = (name: string): string => readFileSync(resolve(__dirname, '..', name), 'utf8');

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

describe('§7 统一入口后的选择器', () => {
  it('恰好两类：副产 / 回料产出', () => {
    // 🔴 2026-09-01 判据换代（形态 C‴：断言守的是历史，不是需求）。
    // 旧判据钉的是「恰好三类，不良品常驻可见但不可选」。owner 裁定业务上**不存在**
    // 「判废 / 不入库」这个场景 —— 车间是「复查 → 存着 → 回料直接回投」，
    // 剔出来的东西最终都走回料。所以这一类整个去掉，不是「暂时禁用」。
    expect(SPECIAL_OUTPUT_OPTIONS.map((option) => option.kind)).toEqual(['byproduct', 'rework']);
    const wrapper = mountChooser();
    expect(wrapper.find('[data-testid="special-output-byproduct"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="special-output-rework"]').exists()).toBe(true);
  });

  it('⛔ 阴性对照：不良品这一项**彻底不存在**，不是藏起来也不是置灰', () => {
    const wrapper = mountChooser();
    expect(wrapper.find('[data-testid="special-output-defective"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('不良品');
    expect(wrapper.text()).not.toContain('判废');
    // 定义层也不许留一个 available:false 的死条目
    expect(read('workflowSpecialOutputs.ts')).not.toMatch(/kind:\s*'defective'/);
    expect(read('workflowSpecialOutputs.ts')).not.toContain('available');
  });

  it('两类都可选，选中后带着 kind 抛给画布层', async () => {
    const wrapper = mountChooser();
    await wrapper.get('[data-testid="special-output-byproduct"]').trigger('click');
    await wrapper.get('[data-testid="special-output-rework"]').trigger('click');
    expect(wrapper.emitted('choose')).toEqual([['byproduct'], ['rework']]);
  });

  it('留下的两类都有后端运行时契约 —— 判据是去 Java 源码树里数，不是转述', () => {
    // 形态 B⁷：引用一句注释来支撑判断之前，先让它自己说。
    // 这两个判据类**必须存在**，否则选择器里留的就是两个没人读的假选项。
    const javaRoot = resolve(
      __dirname, '../../../../../../..',
      'backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow');
    expect(readFileSync(resolve(javaRoot, 'WorkflowByproductNodes.java'), 'utf8')).toContain('isByproduct');
    expect(readFileSync(resolve(javaRoot, 'WorkflowReworkNodes.java'), 'utf8')).toContain('isRework');
  });

  it('两类语义分开写：副产入库、回料回投，描述里各自点名', () => {
    expect(specialOutputOption('byproduct').description).toContain('入库');
    expect(specialOutputOption('rework').description).toContain('回到后续批次');
    expect(specialOutputOption('rework').description).toContain('比例');
  });
});
