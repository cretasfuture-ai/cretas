import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 销售侧「单位进请求体」的调用点清单闸。
 *
 * 🔴 存在的理由 (硬约束 8: 改共享结构前先数, 改完再数):
 * 「把单位塞进一个要发出去的对象」在 views/sales 里有<b>多处</b>, 而 2026-08-18 之前
 * 它们各自决定发什么 —— 有的走前端内部比较键 (`canonicalUnitCodeKeepingCount`, 把「盒」翻成 `box`),
 * 有的原样透传 API 回来的中文。<b>只改一处就是现场制造一份漂移</b>:
 * 订单明细发中文而客供料还在发英文, 两条路落到同一个库里。
 *
 * 所以这里把清单显式写下来, 分两类:
 *   · TRANSLATING_SITES —— 值来自前端内部比较键, <b>必须</b>经 `storageUnitCode` 才能发出去
 *   · PASSTHROUGH_SITES —— 值直接来自 API 响应 (#2837 起后端一律回中文), 从不翻译;
 *                          它们<b>不许</b>出现 `canonicalUnitCode` 家族的调用, 否则就是新长出来的翻译
 *
 * ⛔ 新增一个会发单位的地方, 就要在这两个清单里显式选一边 —— 不登记 = 这条会红。
 */

const ORDERS = resolve(__dirname, '..');
const read = (file: string) => readFileSync(resolve(ORDERS, file), 'utf8');

const LIST = read('list.vue');
const DETAIL = read('detail.vue');
const UNIT_CONTRACT = read('salesOrderUnitContract.ts');

/** 取一个顶格函数的函数体 (到下一个顶格 `}` 为止)。找不到就让调用方的断言红。 */
function functionBody(source: string, declaration: string): string {
  const start = source.indexOf(declaration);
  if (start < 0) return '';
  const end = source.indexOf('\n}\n', start);
  if (end < 0) return '';
  return source.slice(start, end);
}

describe('阳性对照: 函数体真的被取到了', () => {
  it.each([
    ['salesOrderUnitContract.ts', UNIT_CONTRACT, 'export function canonicalSalesOrderItemPayload('],
    ['list.vue', LIST, 'function suppliedMaterialsPayload('],
    ['list.vue', LIST, 'async function handleQuickDelivery('],
    ['detail.vue', DETAIL, 'async function saveEditItems('],
  ])('%s 里的 %s 能定位到非空函数体', (_file, source, declaration) => {
    const body = functionBody(source, declaration);
    // ⚠️ 取不到时 body 是 '', 而 `expect('').not.toContain(x)` 会**恒真** ——
    // 下面所有阴性断言都建立在这一条上, 少了它整份闸假绿。
    expect(body.length, `${declaration} 没定位到 —— 函数改名了? 下面的断言全部作废`)
      .toBeGreaterThan(120);
  });
});

describe('TRANSLATING_SITES: 值来自内部比较键, 必须经 storageUnitCode 发出去', () => {
  it('订单明细 canonicalSalesOrderItemPayload', () => {
    const body = functionBody(UNIT_CONTRACT, 'export function canonicalSalesOrderItemPayload(');
    expect(body, '请求体的 unit 必须走 storageUnitCode').toContain('unit: storageUnitCode(line.unit)');
    // 阴性对照: 内部比较键不许再直接进请求体 (那正是发 `box` 的那条路)
    expect(body).not.toContain('unit: canonicalUnitCodeKeepingCount(');
    expect(body).not.toContain('unit: canonicalUnitCode(');
  });

  it('客供料 suppliedMaterialsPayload', () => {
    const body = functionBody(LIST, 'function suppliedMaterialsPayload(');
    expect(body, '请求体的 unit 必须走 storageUnitCode').toContain('unit: storageUnitCode(row.unit)');
    expect(body).not.toContain('unit: canonicalUnitCodeKeepingCount(');
  });

  it('storageUnitCode 真的被 import 进来了 —— 否则上面两条只是在比字符串', () => {
    expect(LIST).toMatch(/import \{[^}]*storageUnitCode[^}]*\} from '@\/utils\/unitPricing'/);
    expect(UNIT_CONTRACT).toMatch(/import \{[^}]*storageUnitCode[^}]*\} from '@\/utils\/unitPricing'/);
  });
});

describe('PASSTHROUGH_SITES: 值直接来自 API 响应, 从不翻译 (显式登记「有意不改」)', () => {
  /**
   * 这几处把接口回来的 `item.unit` 原样放进下一个请求体。#2837 之后接口一律回中文,
   * 而发货单行本来就是从订单行派生的 —— 订单行发中文, 它们自然是中文。
   * <b>登记是留痕, 不是豁免</b>: 下面断言它们始终不出现翻译调用, 一旦有人在这里加一层
   * `canonicalUnitCode`, 英文码就会从这条路重新流回库里 (实测 `sales_delivery_items` 至今有 2 行 `box`)。
   */
  it.each([
    ['list.vue', LIST, 'async function handleQuickDelivery('],
    ['detail.vue', DETAIL, 'async function saveEditItems('],
  ])('%s / %s 不含任何单位翻译调用', (_file, source, declaration) => {
    const body = functionBody(source, declaration);
    expect(body).not.toContain('canonicalUnitCode(');
    expect(body).not.toContain('canonicalUnitCodeKeepingCount(');
  });
});

describe('比较键那一侧刻意没动 —— 动了要连着重跑包装规格 E2E', () => {
  it('包装规格匹配仍按 canonicalUnitCode 比较', () => {
    expect(UNIT_CONTRACT).toContain('const transactionUnit = canonicalUnitCode(line.unit)');
    expect(UNIT_CONTRACT)
      .toContain('canonicalUnitCode(selected.packageUnit) !== canonicalUnitCode(line.unit)');
  });

  it('单位下拉 unitOptions 仍产出内部比较键 (el-select 的 value)', () => {
    expect(LIST).toContain('return mergeCanonicalUnitOptions(sources)');
    expect(LIST).toContain(':label="displayUnit(u)"');
  });

  it('calcBox 的两分支口径仍用普通版折叠同义单位', () => {
    expect(LIST).toMatch(/const pu = canonicalUnitCode\(p\.unit/);
  });
});
