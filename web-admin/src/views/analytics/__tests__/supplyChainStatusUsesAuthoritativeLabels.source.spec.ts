import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

import {
  MATERIAL_BATCH_STATUS_LABELS,
  PRODUCTION_BATCH_STATUS_LABELS,
  PURCHASE_ORDER_STATUS_LABELS,
  SALES_ORDER_STATUS_LABELS,
  enumLabel,
} from '@/utils/enumDisplay';

/**
 * 「进销存闭环总览」的四个 tab，各自用**权威分域表**翻译状态码。
 *
 * <h2>🔴 2026-08-28 逐屏走查抓到</h2>
 *
 * <p>采购订单 tab 的状态列里 <code>CLOSED</code> <b>原样显示成英文</b>，
 * 而同一列其他行是「已取消」「已完成」「财务已审」。</p>
 *
 * <p>真因是这个屏自己维护了一份 20 条的本地表，它的注释写着：</p>
 *
 * <blockquote>
 * backend may emit raw enums for partial-flow states … <b>真窗 verify 揭示后补</b>
 * </blockquote>
 *
 * <p>▎「揭示一个补一个」正是保证它永远不全的机制，而 <code>|| status</code> 兜底
 * 把没登记的码原样吐给用户。</p>
 *
 * <h2>把那份本地表逐码对照 Java 枚举之后</h2>
 *
 * <pre>
 *   翻译了 3 个后端**根本发不出来**的码:  DELIVERED / QUARANTINE / RECEIVED
 *     · QUARANTINE 在**任何** Java 枚举里都不存在
 *     · DELIVERED 只在 SalesDeliveryStatus(「已签收」), 而这个 tab 渲染的是**销售订单**状态
 *     · RECEIVED 只在 CustomerMaterialArrival / PurchaseInvoice / Transfer
 *   却漏掉了 1 个后端**真会发**的码:      CLOSED  (PurchaseOrderStatus 里有)
 * </pre>
 *
 * <p>▎攒了一堆猜测，仍然漏掉现实 —— 而且那三个猜测的中文和中心表**不一致**
 * （本地写「已交付」/「已收货」，中心表是「已签收」/「已收票」）。</p>
 *
 * <h2>⚠️ 为什么必须**分域**而不是一张大表</h2>
 *
 * <p><code>enumDisplay.ts</code> 自己的分层规则写着：同一个码在不同单据里含义不同
 * （<code>FINANCE_APPROVED</code> 销售单是「财务已批准」、采购单是「财务已审核」；
 * <code>REJECTED</code> 采购收货是「已退回」、别处是「已驳回」），
 * 「塞进全局表就必然有一半场景显示错的中文 —— <b>那比显示英文码更糟</b>
 * （用户不会怀疑它）」。</p>
 *
 * <p>而原来是<b>一个</b> <code>getStatusText</code> 被四个 tab 共用 ⇒ 结构上就不可能都对。</p>
 *
 * <h2>⚠️ 这道闸刻意只盯这一个屏</h2>
 *
 * <p>同一形状（<code>map[x] || x</code>，查不到就把原始码吐给用户）全仓实测 <b>188 处</b>，
 * 其中相当一部分是合法的（单位别名、省份名等原值本身就是中文）。
 * 一次性硬红 188 条会让这道闸当天被关掉 ——「宁可窄而可信，不要宽而被关掉」。
 * ⇒ 只钉这一个有实拍证据的屏；188 这个数写进 PR 让这一类可见，⛔ 不假装修了它。</p>
 */

const SCREEN = resolve(__dirname, '../SupplyChainOverview.vue');

describe('进销存总览: 状态列用权威分域表', () => {
  it('阳性对照: 真的读到了那个屏, 且它确实在渲染 row.status', () => {
    const src = readFileSync(SCREEN, 'utf8');
    expect(src.length, '源码读成空 ⇒ 下面全是恒真式').toBeGreaterThan(2000);
    expect(src, '这个屏应该有 4 个状态列(四个 tab 各一)')
      .toMatch(/StatusText\(row\.status\)/);
    expect((src.match(/StatusText\(row\.status\)/g) || []).length).toBe(4);
  });

  it('⛔ 不再自己维护一份本地状态表, 也不再一个函数给四个 tab 共用', () => {
    const src = readFileSync(SCREEN, 'utf8')
      .replace(/<!--[\s\S]*?-->/g, '')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .split('\n').map(l => l.replace(/\/\/.*$/, '')).join('\n');
    expect(src, '共用一个 getStatusText 在结构上就不可能四个单据都对')
      .not.toMatch(/function\s+getStatusText\s*\(/);
    // ⚠️ 判据必须是「码 → **中文**」。第一版写的是 `not.toMatch(/PARTIAL_RECEIVED:\s*'/)`,
    //   它把隔壁的 `getStatusType`(码 → el-tag 颜色 `info`/`warning`)也匹配了 ——
    //   那份表是**合法**的本地表(颜色是表现选择, 不是权威数据)。
    //   ⇒ 值里必须含中日韩字符才算「本地中文表」。**闸的正则过宽会把对的东西判成错的。**
    expect(src, '本地中文表会漂 —— 2026-08-28 实测它翻译了 3 个发不出来的码、漏了 1 个真会发的')
      .not.toMatch(/[A-Z][A-Z0-9_]{2,}:\s*'[^']*[一-鿿]/);
    for (const table of [
      'PURCHASE_ORDER_STATUS_LABELS',
      'MATERIAL_BATCH_STATUS_LABELS',
      'PRODUCTION_BATCH_STATUS_LABELS',
      'SALES_ORDER_STATUS_LABELS',
    ]) {
      expect(src, `应引用权威分域表 ${table}`).toContain(table);
    }

    // 🔴 上面那组「表名出现在文件里」对**这个方向**是恒真式: import 了不用也算数。
    //   实测: 把四个 tab 全改成调采购那一个函数, 闸纹丝不动(变异 M2 没红)。
    //   ⇒ 必须断言**四个不同的函数各被调用一次**, 而不是「表被提到过」。
    for (const fn of [
      'purchaseStatusText',
      'materialBatchStatusText',
      'productionBatchStatusText',
      'salesStatusText',
    ]) {
      // ⚠️ 用字面量拼接而不是模板串: `\b` 在模板串里是**退格字符**(U+0008), `\(` 退化成 `(`,
      //   正则于是永远不匹配 —— 而「不匹配」和「真的没调用」读数一模一样。
      //   (2026-08-28 实测: 第一版这么写, 干净文件上也红, 差点被当成判据错了。)
      const calls = (src.match(new RegExp('\\b' + fn + '\\(row\\.status\\)', 'g')) || []).length;
      expect(calls, `${fn} 应恰好被一个 tab 调用一次 —— 四个 tab 共用一个函数就回到了本 PR 修的那个结构问题`)
        .toBe(1);
    }
  });

  it('实拍那个 CLOSED 现在译得出来 —— 而它正是旧本地表漏掉的那一个', () => {
    expect(enumLabel('CLOSED', PURCHASE_ORDER_STATUS_LABELS)).toBe('已关闭');
    // 阴性对照: 同屏其他行本来就是对的, 不能因为换表而变坏
    expect(enumLabel('CANCELLED', PURCHASE_ORDER_STATUS_LABELS)).toBe('已取消');
    expect(enumLabel('COMPLETED', PURCHASE_ORDER_STATUS_LABELS)).toBe('已完成');
    expect(enumLabel('FINANCE_APPROVED', PURCHASE_ORDER_STATUS_LABELS)).toBe('财务已审核');
  });

  it('四张分域表逐值都译得出来(与各自 Java 枚举 1:1)', () => {
    // ⚠️ 这条不是重复 enumDisplayCoverage —— 那道闸守的是「中心表对得上 Java」，
    //   这条守的是「**这个屏用的这四张表**里没有译不出来的值」。
    const tables = {
      采购订单: PURCHASE_ORDER_STATUS_LABELS,
      入库记录: MATERIAL_BATCH_STATUS_LABELS,
      生产批次: PRODUCTION_BATCH_STATUS_LABELS,
      '出库/销售': SALES_ORDER_STATUS_LABELS,
    };
    const untranslated: string[] = [];
    for (const [tab, table] of Object.entries(tables)) {
      expect(Object.keys(table).length, `${tab} 的分域表是空的 ⇒ 下面的循环是恒真式`)
        .toBeGreaterThan(5);
      for (const code of Object.keys(table)) {
        const label = enumLabel(code, table);
        if (label === code) untranslated.push(`${tab}: ${code}`);
      }
    }
    expect(untranslated, '这些码会原样以英文出现在状态列里').toEqual([]);
  });
});
