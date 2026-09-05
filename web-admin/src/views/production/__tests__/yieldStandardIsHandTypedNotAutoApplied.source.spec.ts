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
 * <h2>第二条：100 不再是哨兵（2026-09-06 改写）</h2>
 *
 * <p>这一条<b>原来</b>断言「必须拦住 100」，理由是 {@code 100.00} 同时被
 * {@code ProductionPlanServiceImpl#resolveEffectiveProductYieldRate} /
 * {@code #isMainMaterialCandidate} 当成「视为未配置」的哨兵。</p>
 *
 * <p>🔴 那个哨兵已经<b>从后端拆掉</b>（RECON-LEGAL-VALUE-SENTINEL-20260906）：
 * 表头 {@code overall_yield_rate} 现在 {@code NULL} 才是「没配」，{@code 100.00}
 * 是合法的零损耗值，{@code > 100} 也合法（保水/腌制，六膳门猪舌 105–126%）。</p>
 *
 * <p>⚠️ 这是<b>形态 C‴</b>：断言没坏、也不是变异没生效，是<b>需求变了</b>。
 * 症状是「红得理直气壮」，最容易被读成「我改错了」然后把改动回退掉。
 * ⛔ 改断言不等于删断言 —— 把守的东西从「拦住 100 这个字面」抬到
 * 「有一个说得清的上限，且 100 不在拒绝之列」，并保留阴性对照钉住旧行为不回来。</p>
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

  it('有下界(>0)和一个具名的上界常量 —— 上限是防手滑, 不是工艺上限', () => {
    const code = codeOnly();
    // 阳性对照: 剥完注释还剩真代码, 否则下面几条都是恒真式
    expect(code.length, '剥注释剥过头 ⇒ 下面的断言无意义').toBeGreaterThan(1500);

    expect(code, '没有下界 ⇒ 0 或负数能提交, PG 的 chk_br_yield 会 500')
      .toMatch(/v\s*<=\s*0/);
    // 上限必须是**具名常量**: 三层(UI / DTO+服务 / DB CHECK)共用一条规则,
    // 散落的字面量会让「改一处」变成制造一份漂移(形态 D / 硬约束 8)
    expect(code, '上限写成了裸字面量或压根没有 ⇒ 三层规则对不齐时没人看得出来')
      .toMatch(/YIELD_RATE_MAX_PERCENT\s*=\s*300/);
    expect(code, '校验里没有用那个常量 ⇒ 常量只是摆设')
      .toMatch(/v\s*>\s*YIELD_RATE_MAX_PERCENT/);
  });

  it('🔴 阴性对照: 100 不再被拒 —— 那个哨兵已从后端拆掉, 教用户填 99.99 是在教错的绕法', () => {
    const code = codeOnly();
    expect(code.length).toBeGreaterThan(1500);

    expect(code, 'v === 100 的拒绝回来了 ⇒ 100% 零损耗这个合法值又被挡住了')
      .not.toMatch(/v\s*===\s*100/);
    expect(code, '「填 99.99」这条绕法回来了 ⇒ 它存在的前提(100 是哨兵)已经不成立')
      .not.toMatch(/99\.99/);
    // > 100 的旧上限也不许回来: 保水/腌制工序(六膳门猪舌 105–126%) 本来就 > 100
    expect(code, 'v > 100 的旧上限回来了 ⇒ 保水/腌制工序又填不进去了')
      .not.toMatch(/v\s*>\s*100\b/);
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
