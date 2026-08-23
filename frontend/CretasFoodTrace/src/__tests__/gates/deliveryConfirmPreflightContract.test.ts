/**
 * 闸 —— 发货确认页必须**进屏就知道**批次分配了没有，而不是等后端 409。
 *
 * <h2>🔴 为什么有这道闸（2026-08-18 真机实测）</h2>
 * 仓管在 Mi 11 Pro 上点「确认并扣库存」，后端回：
 *
 * ```
 * 409 发货行 210（产品：叮咚好食光卤猪蹄(去大骨) 200g）未完成批次分配，无法确认发货
 * ```
 *
 * 而**这个事实在进屏那一刻就在手里** —— `getDeliveryDetail` 每一行都返回
 * `finishedGoodsBatchId`，未分配时为 null。本屏只读了 `deliveredQuantity` / `unit`，
 * 没读它，于是「该事先拦住的」变成了「提交后才报错」。
 *
 * <h2>⚠️ 顺带记一条差点写出去的错误结论</h2>
 * 第一次排查时我截图**晚了 10 秒**，红色 Snackbar 已经自动消失，
 * 我据此差点写成「前端吞掉了 409」——**那是错的**：
 * `submitConfirm` 的 catch 一直原样透传后端 message（源码注释里甚至引用了这条 409）。
 * 是**我的仪器错过了它**，不是 App 吞了。
 * ⇒ 判「界面有没有反馈」要在动作后 1~2 秒内取样；Snackbar 几秒就自动消失。
 *
 * <h2>口径</h2>
 * 这道闸扫源码结构（读没读那个字段 / 有没有拦 / 有没有把原因显示出来），
 * 它证明不了「用户真的看到了」——那要真机走一遍。
 */
import fs from 'fs';
import path from 'path';
import { shortfallOf } from '../../screens/warehouse/outbound/warehouseDeliveryConfirm.logic';

const SCREEN = path.resolve(
  __dirname,
  '../../screens/warehouse/outbound/WHDeliveryConfirmScreen.tsx',
);

function src(): string {
  return fs.readFileSync(SCREEN, 'utf8');
}

/** 剥注释 —— 注释里提到某个字段不等于代码真的读了它。 */
function code(): string {
  return src()
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '');
}

describe('发货确认前置校验契约', () => {
  it('阳性对照: 文件读得到, 且确实是那一屏', () => {
    // 没有这一条, 下面的 toContain 在读不到文件时会一起失效, 而闸看起来仍然正常。
    expect(fs.existsSync(SCREEN)).toBe(true);
    const s = code();
    expect(s.length).toBeGreaterThan(2000);
    expect(s).toContain('confirmDelivery');   // 形状对照: 这屏确实在做发货确认
  });

  /*
   * ── 2026-08-21 翻面 ─────────────────────────────────────────────────────
   * 这三条原来钉的是**实现字面**: 读 finishedGoodsBatchId / 算 unallocated /
   * 显示「未分配批次」红卡。2026-08-20 owner 裁定「App 上不再出现批次概念」
   * (自动 FIFO), 那套实现被有意移除, 于是它们红了。
   *
   * ▎红得理直气壮 —— 断言好好的, 是**需求变了**(本仓形态 C‴)。
   * ⛔ 但需求变的是「怎么做到」, 不是「要不要做到」: 本闸守的**性质**
   *    ——「不能让人点了才被后端 409 挡回来」—— 一个字都没变。
   *
   * ⛔ 所以不是删断言, 是把守的东西从「字面」抬到「性质」, 并补阴性对照。
   * (实测这次翻面抓到了一个真缺口: 进屏已经调 recommendFifo 拿到了取货批次,
   *  够不够当场就知道, 而代码只拿它显示「搬哪一批」—— 仓管仍然是点了才被挡。)
   */

  it('接线: 进屏取推荐批次, 并把它喂给缺口判断', () => {
    const s = code();
    expect(s).toContain('recommendFifo');
    // ⚠️ 这条只证明**接上了**, ⛔ 证明不了「算得对」——
    //    下面那组行为断言才管算得对不对(见它上面的说明)。
    expect(s).toContain('shortfallOf(need, picks)');
  });

  describe('缺口判断本身（行为，⛔ 不是扫源码）', () => {
    /*
     * 🔴 这一组是被一次**失败的变异**逼出来的。
     *
     * 第一版把判断写在屏里, 闸扫源码断言 `toContain('shortfallQty')` +
     * 「有 picks.reduce 那一行」。变异: 把
     *   `shortfallQty: shortfall > 0 ? shortfall : undefined`
     * 改成 `shortfallQty: undefined`（= 退回「只显示搬哪一批, 够不够不管」）——
     * **6 条全绿**, 因为那个串和那行 reduce 都还在。
     *
     * ▎变异生效了, 只是打在了闸看不见的地方（本仓形态 C″）。
     * ⇒ 判断抽成纯函数 shortfallOf, 判据落在行为上。
     */
    it('凑不够 → 报出缺口', () => {
      expect(shortfallOf(100, [{ qty: 40 }, { qty: 30 }])).toBeCloseTo(30);
    });

    it('刚好够 / 有余 → 不报（⛔ 不许误报）', () => {
      expect(shortfallOf(100, [{ qty: 60 }, { qty: 40 }])).toBeUndefined();
      expect(shortfallOf(100, [{ qty: 500 }])).toBeUndefined();
    });

    it('一个批次都推荐不出来 → 整量都是缺口', () => {
      expect(shortfallOf(12, [])).toBeCloseTo(12);
    });

    it('浮点尾巴不许触发误报', () => {
      // 0.1+0.2 = 0.30000000000000004, 差出 -4e-17。
      // 没有容差的话这里会对着「还差约 0.00000000000000004」报警。
      expect(shortfallOf(0.3, [{ qty: 0.1 }, { qty: 0.2 }])).toBeUndefined();
    });

    it('脏输入不制造假警报', () => {
      // 需要的量本身无意义时, ⛔ 不能报「缺 NaN」。
      expect(shortfallOf(Number.NaN, [{ qty: 1 }])).toBeUndefined();
      expect(shortfallOf(0, [])).toBeUndefined();
    });
  });

  it('库存不够必须事先说出来, 并给得到手机上走得通的出路', () => {
    const s = code();
    expect(s).toContain('wh-shortfall-');           // 有可定位的 testID
    expect(s).toContain('还差约');                   // 说清差多少, ⛔ 不是「库存不足」四个字
    expect(s).toContain('去调拨');                   // 防呆 Rule 5: dead-end 必须有出路
    expect(s).toContain('goToTransfer');            // 那个按钮真的接上了
  });

  it('⛔ 有缺口不 disable 提交 —— 快照会过期, 硬拦是误伤', () => {
    /*
     * recommendFifo 是进屏那一刻的快照, 库存随时可能被别人动。
     * 硬拦会造出「明明发得了却不让发」, 而后端才是权威(它会 409)。
     * ▎一个会误报的拦截比没有拦截更糟 —— 它烧掉的是信任。
     */
    const s = code();
    const canSubmitLine = s.split('\n').find((l) => l.includes('const canSubmit'));
    expect(canSubmitLine).toBeDefined();
    expect(canSubmitLine).not.toContain('shortfall');
  });

  it('阴性对照: 批次概念⛔不许回到 App', () => {
    /*
     * 没有这一条, 上面几条可以靠「把旧的那套批次分配 UI 加回来」一起满足 ——
     * 而那正是 2026-08-20 被 owner 否掉的复杂度
     * (原话: 批次「他们自己都管不过来，我还得帮他录入」)。
     */
    const s = code();
    expect(s).not.toContain('wh-confirm-unallocated');
    expect(s).not.toContain('finishedGoodsBatchId');
  });

  it('错误分支仍然原样透传后端 message（别在加前置校验时把它改坏）', () => {
    const s = code();
    // 阴性对照: 不许把后端原话替换成通用文案
    expect(s).not.toContain("'操作失败'");
    expect(s).toContain('data?.message');
  });
});
