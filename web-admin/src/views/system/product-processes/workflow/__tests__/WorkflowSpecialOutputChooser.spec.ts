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
  it('恰好三类：副产 / 不良品 / 回料产出', () => {
    // 🔴 2026-09-01 这条判据当天改了**两次**，第二次是订正第一次的误读（形态 C‴）。
    //
    //   原判据：三类，不良品常驻可见但不可选。
    //   第一次改：整类删掉 —— 我把 owner 的「判废跟不入库这两个功能可以去掉」
    //             读成了「不良品这一类可以去掉」。
    //   第二次改（本条）：owner 原话「**我要的是不良品，不良品是需要的**」。
    //             要去掉的是挂在它身上的两句错话，不是这一类。
    //
    // ⇒ 判据钉的是「三类都在、都能选」，理由写在 workflowSpecialOutputs.ts。
    expect(SPECIAL_OUTPUT_OPTIONS.map((option) => option.kind))
      .toEqual(['byproduct', 'defective', 'rework']);
    const wrapper = mountChooser();
    expect(wrapper.find('[data-testid="special-output-byproduct"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="special-output-defective"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="special-output-rework"]').exists()).toBe(true);
  });

  it('⛔ 被否掉的是「判废 / 不入库」这两个说法 —— 一个字都不许回来', () => {
    // 阴性对照。⛔ 不是「不许提不良品」（那正是我上一版搞反的地方），
    // 是不许把不良品描述成「判死」或「不入库」—— 它照常报工、照常入库存着。
    const wrapper = mountChooser();
    expect(wrapper.text()).toContain('不良品');
    expect(wrapper.text()).not.toContain('判废');
    expect(wrapper.text()).not.toContain('不入库');
    const source = read('workflowSpecialOutputs.ts');
    // 描述正文里不许出现这两个词。⚠️ 注释里**可以**出现（那段注释就是在解释为什么不许），
    // 所以只扫 description 字段本身，不扫整份文件 —— 否则闸会把自己的解释当成违例
    // （本仓踩过：「闸把自己的注释当成被测对象」）。
    for (const option of SPECIAL_OUTPUT_OPTIONS) {
      expect(option.description, option.kind).not.toContain('判废');
      expect(option.description, option.kind).not.toContain('不入库');
    }
    // 定义层也不许留一个 available:false 的死条目 —— 三类都是真能选的
    expect(source).not.toContain('available');
  });

  it('不良品「照常报工入库」这句话有 Java 侧证据 —— 是去读实现，不是转述', () => {
    // 🔑 这条守的是**产品文案的真假**。UI 上写着不良品「照常报工入库存着」，
    //    它成立的唯一理由是：后端那段筛选**只把副产滤掉**，没有标记的半成品产出
    //    照常进报工选项。哪天有人给不良品也加一道过滤，这句话就变成假话，本条会红。
    const impl = readFileSync(resolve(
      __dirname, '../../../../../../..',
      'backend/java/cretas-api/src/main/java/com/cretas/aims/service/wip/impl',
      'WipInventoryServiceImpl.java'), 'utf8');
    const body = impl.slice(impl.indexOf('workflowOutputOptions('));
    // 正例：确实存在「滤掉副产」这一条
    expect(body).toContain('!byproductNodeIds.contains(p.getMaterialNodeId())');
    // 正例：不良品作为**显示标**下发（owner 2026-09-01「好的区别一下吧，补吧」）
    expect(body).toContain('.isDefective(defectiveNodeIds.contains(');

    // 🔴 阴性对照，本条的要害：不良品**只能是标，不能是筛子**。
    // ⚠️ 上一版这里写的是 `not.toMatch(/defective/i)` —— 加了显示标之后它当场红了，
    //    而产品行为一点没坏。那条断言钉的是「这个词不许出现」，太钝；
    //    真正的不变量是「这个词不许出现在 .filter(...) 里」。
    const filterLines = body.split(/\r?\n/).filter((line) => line.includes('.filter('));
    expect(filterLines.length).toBeGreaterThan(0);   // 阳性对照：确实数到了 filter 链
    for (const line of filterLines) {
      expect(line.toLowerCase(), line.trim()).not.toContain('defective');
    }
  });

  it('三类都可选，选中后带着 kind 抛给画布层', async () => {
    const wrapper = mountChooser();
    await wrapper.get('[data-testid="special-output-byproduct"]').trigger('click');
    await wrapper.get('[data-testid="special-output-defective"]').trigger('click');
    await wrapper.get('[data-testid="special-output-rework"]').trigger('click');
    expect(wrapper.emitted('choose')).toEqual([['byproduct'], ['defective'], ['rework']]);
  });

  it('副产/回料各有 Java 判据类 —— 判据是去源码树里读，不是转述', () => {
    // 形态 B⁷：引用一句注释来支撑判断之前，先让它自己说。
    // ⚠️ 三类里只有这两类需要判据类。不良品**刻意没有** —— 它走的是「没有标记的
    //    半成品产出」那条默认路径（上一条测试就是在钉这件事）。给它加一个判据类
    //    只会把它从那条路上挪走。⇒ 这里不要顺手补第三个 expect。
    const javaRoot = resolve(
      __dirname, '../../../../../../..',
      'backend/java/cretas-api/src/main/java/com/cretas/aims/service/workflow');
    expect(readFileSync(resolve(javaRoot, 'WorkflowByproductNodes.java'), 'utf8')).toContain('isByproduct');
    expect(readFileSync(resolve(javaRoot, 'WorkflowReworkNodes.java'), 'utf8')).toContain('isRework');
  });

  it('三类语义分开写，描述里各自点名，⛔ 不许互相串味', () => {
    expect(specialOutputOption('byproduct').description).toContain('入库');
    expect(specialOutputOption('rework').description).toContain('回到后续批次');
    expect(specialOutputOption('rework').description).toContain('比例');
    // 不良品 = 还没复查的那部分，且要说清它照常报工入库
    expect(specialOutputOption('defective').description).toContain('复查');
    expect(specialOutputOption('defective').description).toContain('入库');
    // 阴性对照：不良品和回料是**两个阶段**，描述不许把不良品说成「可以直接回投」——
    // 回投的前提是复查通过，说反了会让用户把没复查的东西配到工序上。
    expect(specialOutputOption('defective').description).not.toContain('确定要回到');
  });
});
