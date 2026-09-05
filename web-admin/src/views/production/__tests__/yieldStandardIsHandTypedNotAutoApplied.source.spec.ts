import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 「标准出成率维护」页：标准必须是**人一个个敲进去**的，不是一键采纳系统建议。
 *
 * <h2>为什么这页需要一道源码闸</h2>
 *
 * <p>这页存在的全部理由，是 2026-09-05 删掉了
 * {@code BomYieldSuggestionServiceImpl#autoApplyIfGuarded} —— 那段代码在结单后
 * 把实测中位数<b>自动写回</b> {@code bom_recipes.overall_yield_rate}
 * （生产上改过 12 次，appliedBy=SYSTEM）。owner 裁定「标准只能人改」。</p>
 *
 * <p>⚠️ 这页最容易长回去的方式不是有人把后端那段加回来，而是**在这里加一个
 * 「一键采纳全部建议」按钮** —— 那等于把同一个缺陷换个位置重新实现一遍，
 * 而且这次是用户自己点的，看起来还很合理。</p>
 *
 * <h2>第二条：100 这个哨兵</h2>
 *
 * <p>{@code 100.00} 同时是「出成率 100%」这个<b>合法值</b>，
 * 又是 {@code ProductionPlanServiceImpl#resolveEffectiveProductYieldRate} 与
 * {@code #isMainMaterialCandidate} 里「视为未配置」的<b>哨兵</b>。
 * F006 黄油鸡就是被自学习一路推到 100 之后，缺料预警再也没按出成率折算过主料 ——
 * 不报错、不留痕。</p>
 *
 * <p>⇒ 人手填的时候必须拦住 100，否则我们只是把「机器推到哨兵值」换成了
 * 「人填到哨兵值」，症状一模一样。</p>
 */

const SCREEN = resolve(__dirname, '../YieldStandardView.vue');

function source(): string {
  return readFileSync(SCREEN, 'utf8');
}

/**
 * 剥掉注释再扫 —— ⛔ 不要在原文上直接 grep 禁词。
 *
 * <p>本仓踩过不止一次：源码闸把**闸自己所解释的那段注释**当成被测对象。
 * 这一条第一次跑就红了，而红的原因正是本文件所守的页面在它的文档注释里
 * 写着「不批量『一键采纳建议』」—— 一句声明「我们不做这个」的话，
 * 被读成了「这里做了这个」。</p>
 *
 * <p>▎<b>禁词类判据必须落在会执行的代码/会渲染的模板上，不能落在解释它的文字上。</b></p>
 */
function codeOnly(): string {
  return source()
    .replace(/<!--[\s\S]*?-->/g, ' ')   // HTML 注释 (template 段)
    .replace(/\/\*[\s\S]*?\*\//g, ' ')  // 块注释 (含 JSDoc)
    .replace(/^\s*\/\/.*$/gm, ' ');     // 行注释
}

describe('标准出成率维护页', () => {
  it('阳性对照: 真的读到了那个屏, 且它确实是这一页', () => {
    const src = source();
    // ⛔ 读成空 / 读错文件 ⇒ 下面每一条都会变成恒真式
    expect(src.length, '源码读成空 ⇒ 下面全是恒真式').toBeGreaterThan(2000);
    expect(src).toContain('标准出成率维护');
    expect(src).toContain('recalculateApply');
  });

  it('拦住 100 —— 它是「视为未配置」的哨兵值, 填进去等于把备料折算静默关掉', () => {
    const src = source();
    // 判据落在**校验逻辑**上, 不是落在某句文案上
    expect(src, '缺少对 100 的拦截 ⇒ 人可以手填到哨兵值, 与自学习推到 100 后果相同')
      .toMatch(/v\s*===\s*100/);
    // 阴性对照: 拦下来时要说清为什么 + 给真出路, ⛔ 不是干巴巴一句「不合法」
    expect(src).toContain('未配置');
    expect(src).toMatch(/99\.99/);
  });

  it('没有「一键采纳全部建议」那类批量写入口 —— 那是把已删除的自动回写换个位置重来', () => {
    const code = codeOnly();

    // 阳性对照: 剥完注释还得剩下真正的代码, 否则下面的 not.toContain 全是恒真式
    expect(code.length, '剥注释剥过头 ⇒ 下面的禁词断言无意义').toBeGreaterThan(1500);
    expect(code, '剥注释把被测的调用也剥掉了').toContain('recalculateApply');

    for (const forbidden of ['一键采纳', '全部采纳', '批量应用', '一键应用', '全部应用']) {
      expect(code, `出现了「${forbidden}」⇒ 自动回写以用户点击的形式复活了`).not.toContain(forbidden);
    }
    // recalculateApply 只允许按行调用: 传进去的数组是**单元素**字面量
    expect(code, 'recalculateApply 被传了非单行的集合 ⇒ 可能是批量写')
      .toMatch(/recalculateApply\([^)]*,\s*\[\{/);
  });

  it('系统建议只是参考列, 不预填成「当前值」', () => {
    const src = source();
    expect(src).toContain('参考');
    // 编辑框的初始值取**当前已填的标准**, ⛔ 不是取系统建议 ——
    // 预填建议值会让「人确认过」和「系统算的」在库里长得一模一样
    expect(src, '编辑框预填了系统建议 ⇒ 人只是按了回车, 与自动应用没有区别')
      .toMatch(/inputValue:[^\n]*currentYieldRate/);
    expect(src).not.toMatch(/inputValue:[^\n]*suggestedYieldRate/);
  });

  it('样本不足显示成「样本不足」而不是 0% —— 兜底默认值会把「不知道」翻译成一个读数', () => {
    const src = source();
    expect(src).toContain('样本不足');
    expect(src).toContain('尚无生产数据');
    expect(src).toContain('未填');
  });
});
