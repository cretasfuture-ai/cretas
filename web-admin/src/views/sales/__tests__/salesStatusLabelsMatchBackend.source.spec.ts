/**
 * 客户 2026-07-17,「未经翻译直接引用后端英文」，复测 07-20 / 07-21 均为未解决。
 * 客户点名了三处：单据追踪、**销售付款申请**、**车辆字典**。
 *
 * 单据追踪那一处已由 #2037 (2026-07-30) 修好 —— 但那个 commit 只动了 4 个文件
 * (enumDisplay.ts / 它的测试 / approvalDirectory.ts / production/plans/list.vue)，
 * **另外两处一个字都没改**。本测试补的就是那两处。
 *
 * 两处的坏法不一样：
 *   · 销售付款申请：清单抄了 4 份，4 份都漏了后端的 `CANCELLED`，兜底 `map[s] ?? s`
 *     把英文原码直接显示出来。
 *   · 车辆字典：清单和后端枚举**两个方向都对不上** —— 后端有的 `dispatched` 表里没有
 *     (漏译成英文)，表里的 `in_use` / `retired` 后端**根本不存在**
 *     (`VehicleStatus.valueOf()` 会抛 → 400)。
 *
 * ⚠️ 本仓已有 `enumDisplayCoverage.spec.ts` 用同一招守 enumDisplay.ts。
 * 那道闸守不到这两个页面 —— 它们持有自己的私有表、不 import 那个模块。
 * 全仓这样的私有状态表共 72 份；本测试**只**守客户点名的这两份
 * (形态 E：宁可窄而可信，不要宽而被关掉)。其余 70 份未纳入，已在交付说明里登记。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const JAVA_ROOT = resolve(
  import.meta.dirname,
  '../../../../../backend/java/cretas-api/src/main/java/com/cretas/aims',
);

function readJava(relative: string): string {
  const path = resolve(JAVA_ROOT, relative);
  // 刻意不 skip —— 后端改名时必须红，静默跳过等于门禁消失。
  expect(existsSync(path), `找不到 ${relative} —— 后端文件被移动或改名了，请同步本测试`).toBe(true);
  return readFileSync(path, 'utf8');
}

/**
 * 取枚举常量名。
 *
 * ⚠️ 两个坑，都实测踩过：
 * ① 不能复用 enumDisplayCoverage.spec.ts 的 helper —— 它们的正则是
 *    `[A-Z][A-Z0-9_]*`，而 `VehicleStatus` 的常量是**小写**的，会读出空列表。
 * ② 不能用**行锚定**的正则 —— `VehicleStatus` 把四个常量写在**同一行**
 *    (`available, loading, dispatched, maintenance`)，`^...$/gm` 每行只取得到
 *    第一个，实测读出 `['available']`，于是「后端有、前端没有」恒为空 = 恒真式。
 *    ⇒ 按**逗号**切，不按行切。上面那条仪器自检就是抓这个的。
 */
function enumConstants(source: string, enumName: string): string[] {
  const start = source.indexOf(`enum ${enumName}`);
  expect(start, `${enumName} 声明找不到`).toBeGreaterThan(-1);
  const open = source.indexOf('{', start);
  const body = source.slice(open + 1);
  // 常量列表在第一个 `;` 之前；没有构造器时到 `}` 为止。
  const semi = body.indexOf(';');
  const brace = body.indexOf('}');
  const end = semi >= 0 && semi < brace ? semi : brace;
  return body
    .slice(0, end)
    // 去掉行注释、块注释和构造器实参 —— 例如 PENDING("待财务初审")
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/[^\n]*/g, '')
    .replace(/\([^)]*\)/g, '')
    .split(',')
    .map((chunk) => chunk.trim())
    .filter((chunk) => /^[A-Za-z][A-Za-z0-9_]*$/.test(chunk));
}

const vehicleSource = readJava('entity/Vehicle.java');
const paymentSource = readJava('entity/enums/PaymentRequestStatus.java');

const vehiclePage = readFileSync(
  resolve(import.meta.dirname, '../vehicles/list.vue'),
  'utf8',
);
const paymentPage = readFileSync(
  resolve(import.meta.dirname, '../payment-requests/list.vue'),
  'utf8',
);

describe('销售侧私有状态表与后端枚举对齐（客户点名的两处）', () => {
  it('仪器自检：两个枚举都真的读出了常量（否则「全覆盖」是恒真式）', () => {
    const vehicle = enumConstants(vehicleSource, 'VehicleStatus');
    const payment = enumConstants(paymentSource, 'PaymentRequestStatus');
    // VehicleStatus 是小写常量 —— 读不出来说明正则又只认大写了。
    expect(vehicle, 'VehicleStatus 读出空列表 —— 提取器坏了，后面的断言全部无意义').not.toEqual([]);
    expect(payment, 'PaymentRequestStatus 读出空列表 —— 提取器坏了').not.toEqual([]);
    expect(vehicle).toContain('dispatched');
    expect(payment).toContain('CANCELLED');
  });

  it('车辆字典：statusMap 必须与 Vehicle.VehicleStatus 双向一致', () => {
    const backend = enumConstants(vehicleSource, 'VehicleStatus');

    // 从页面源码里取 statusMap 的键。
    const at = vehiclePage.indexOf('const statusMap');
    expect(at, '找不到 statusMap —— 闸没量到东西').toBeGreaterThan(-1);
    const body = vehiclePage.slice(vehiclePage.indexOf('{', at), vehiclePage.indexOf('};', at));
    const frontend = [...body.matchAll(/^\s*([a-z_]+):\s*\{/gm)].map((m) => m[1]);

    expect(frontend, 'statusMap 读出空列表 —— 提取器坏了').not.toEqual([]);

    // 方向 ①：后端有、前端没有 → 用户看到英文原码（客户报的就是这个）。
    expect(
      backend.filter((code) => !frontend.includes(code)),
      '后端会返回这些状态但前端没有中文，会原样显示英文码',
    ).toEqual([]);

    // 方向 ②：前端有、后端没有 → 选了它 VehicleStatus.valueOf() 抛 → 400。
    expect(
      frontend.filter((code) => !backend.includes(code)),
      '前端提供了后端枚举里不存在的状态，保存时会被 valueOf() 拒绝',
    ).toEqual([]);
  });

  it('车辆字典：未登记的码走 enumLabel，不许把英文原码吐给用户', () => {
    expect(vehiclePage).toContain("import { enumLabel } from '@/utils/enumDisplay'");
    // 被守的行为：兜底不是 `|| row.status`。
    expect(vehiclePage).toContain('enumLabel(row.status)');
    expect(vehiclePage, '仍在把后端英文原码当兜底显示').not.toContain('?.text || row.status');
  });

  it('销售付款申请：状态清单必须覆盖 PaymentRequestStatus 全部取值', () => {
    const backend = enumConstants(paymentSource, 'PaymentRequestStatus');

    const at = paymentPage.indexOf('const PAYMENT_REQUEST_STATUS_META');
    expect(at, '找不到 PAYMENT_REQUEST_STATUS_META —— 闸没量到东西').toBeGreaterThan(-1);
    const body = paymentPage.slice(at, paymentPage.indexOf('] as const', at));
    const frontend = [...body.matchAll(/value:\s*'([A-Z_]+)'/g)].map((m) => m[1]);

    expect(frontend, 'META 读出空列表 —— 提取器坏了').not.toEqual([]);
    expect(
      backend.filter((code) => !frontend.includes(code)),
      '后端会返回这些状态但页面清单里没有，会原样显示英文码（CANCELLED 就是这么漏的）',
    ).toEqual([]);
  });

  it('销售付款申请：兜底走 enumLabel，且清单只有一份', () => {
    expect(paymentPage).toContain("import { enumLabel } from '@/utils/enumDisplay'");
    expect(paymentPage).toContain('enumLabel(s)');
    expect(paymentPage, '仍在把后端英文原码当兜底显示').not.toContain('return map[s] ?? s');

    // 清单收敛成一份：筛选下拉与类型都从 META 推导，不许再手写第二份。
    expect(paymentPage).toContain('v-for="meta in PAYMENT_REQUEST_STATUS_META"');
    expect(paymentPage).toContain("(typeof PAYMENT_REQUEST_STATUS_META)[number]['value']");
  });
});
