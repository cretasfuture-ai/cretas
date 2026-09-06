/**
 * T-SHORT-SHIP: RN「短交结案」按钮可见性 + 确认弹窗内容的守闸。
 *
 * 被守的行为 (形态 C —— 这些断言问的是「行为」不是「某一行长什么样」):
 *   1. 按钮只在 PARTIAL_DELIVERED × sales:read_write × 实发缺口>0 这一格出现。
 *      变异「把状态判据翻成 !== 」⇒ 第 1 组的四格矩阵必红 (见文末变异说明)。
 *   2. 缺口算法与后端 SalesServiceImpl:2529-2535 Σmax(0, ordered-delivered) 同式,
 *      且不被某一行的超发抵消。
 *   3. 确认文案里的每一个数字都来自订单明细 —— ⛔ 没有写死的示例数。
 */

import type { User } from '../../../types/auth';
import {
  canShortCloseSalesOrder,
  shortCloseSummary,
  shortCloseSubmitBlockReason,
  shortCloseConfirmText,
  SHORT_CLOSE_CONSEQUENCE_TEXT,
  SHORT_CLOSE_REASON_OPTIONS,
} from '../../../utils/salesOrderShortClose';

/** 造一个工厂账号。role 直接决定 permissionHelper 的权限矩阵行。 */
function factoryUser(role: string): User {
  return {
    id: 'u1',
    username: `f006_${role}`,
    userType: 'factory',
    factoryUser: { role, factoryId: 'F006', department: 'sales' },
  } as unknown as User;
}

/**
 * 实拍样本 SO-20260902-0001 (F006 六膳门): 美式汉堡饼 订 10 kg / 已发 9.8 kg。
 * ⚠️ 这两个数是探针在测试环境上真读到的, ⛔ 不是编的。
 */
const SHORT_ORDER = {
  status: 'PARTIAL_DELIVERED',
  items: [
    { productName: '美式汉堡饼', quantity: 10, deliveredQuantity: 9.8, unit: 'kg' },
  ],
};

describe('canShortCloseSalesOrder — 状态 × 权限 四格矩阵', () => {
  const salesManager = factoryUser('sales_manager');   // sales: read_write
  const viewer = factoryUser('viewer');                // sales: read  (⛔ 无写权)

  const cells: Array<{ name: string; status: string; user: User; expected: boolean }> = [
    { name: 'PARTIAL_DELIVERED × sales:read_write', status: 'PARTIAL_DELIVERED', user: salesManager, expected: true },
    { name: 'PARTIAL_DELIVERED × 只读角色', status: 'PARTIAL_DELIVERED', user: viewer, expected: false },
    { name: 'COMPLETED × sales:read_write', status: 'COMPLETED', user: salesManager, expected: false },
    { name: 'COMPLETED × 只读角色', status: 'COMPLETED', user: viewer, expected: false },
  ];

  it.each(cells)('$name → $expected', ({ status, user, expected }) => {
    const actual = canShortCloseSalesOrder({ ...SHORT_ORDER, status }, user);
    // 变异验证时先看这一行: 打印实际判定, 确认「变异真的打进了代码」再看断言红不红。
    // eslint-disable-next-line no-console
    console.log(`[short-close predicate] status=${status} role=${(user as any).factoryUser.role} => ${actual}`);
    expect(actual).toBe(expected);
  });

  it('只有这四格里的一格是 true —— 阴性对照: 其它状态一律不出现按钮', () => {
    const otherStatuses = ['DRAFT', 'CONFIRMED', 'PENDING_FINANCE_REVIEW', 'FINANCE_APPROVED',
      'FINANCE_REJECTED', 'PROCESSING', 'COMPLETED', 'CANCELLED', 'SHORT_CLOSED'];
    const visible = otherStatuses.filter((s) => canShortCloseSalesOrder({ ...SHORT_ORDER, status: s }, salesManager));
    expect(visible).toEqual([]);
  });

  it('缺口为 0 时不出现 —— 后端会 409 SALES_SHORT_CLOSE_NO_SHORTFALL, 按钮出现就是骗人', () => {
    const fullyShipped = {
      status: 'PARTIAL_DELIVERED',
      items: [{ productName: '美式汉堡饼', quantity: 10, deliveredQuantity: 10, unit: 'kg' }],
    };
    expect(canShortCloseSalesOrder(fullyShipped, salesManager)).toBe(false);
  });

  it('工厂老板 / 运营协调员是移动端只读观察者 —— 即便权限矩阵给了 sales:read_write 也不出现', () => {
    // factory_super_admin 在 PERMISSION_MATRIX 里是 sales: read_write, 但本屏其它写动作
    // 也对他隐藏 (isMobileBusinessObserver)。这条阴性对照防的是「短交单独开了个口子」。
    expect(canShortCloseSalesOrder(SHORT_ORDER, factoryUser('factory_super_admin'))).toBe(false);
    expect(canShortCloseSalesOrder(SHORT_ORDER, factoryUser('operations_coordinator'))).toBe(false);
  });

  it('未登录 / 订单为空一律不出现', () => {
    expect(canShortCloseSalesOrder(SHORT_ORDER, null)).toBe(false);
    expect(canShortCloseSalesOrder(null, salesManager)).toBe(false);
  });
});

describe('shortCloseSummary — 缺口口径与后端 Σmax(0, ordered-delivered) 同式', () => {
  it('单行短交: 订 10 / 已发 9.8 → 缺 0.2', () => {
    const s = shortCloseSummary(SHORT_ORDER.items);
    expect(s.outstanding).toBeCloseTo(0.2, 6);
    expect(s.shortLines).toHaveLength(1);
    const line = s.shortLines[0]!;
    expect(line.ordered).toBe(10);
    expect(line.delivered).toBe(9.8);
    expect(s.unit).toBe('kg');
  });

  it('某一行超发不允许抵消另一行的缺口 (每行先 clamp)', () => {
    const s = shortCloseSummary([
      { productName: 'A', quantity: 10, deliveredQuantity: 8, unit: 'kg' },   // 缺 2
      { productName: 'B', quantity: 5, deliveredQuantity: 9, unit: 'kg' },    // 超发 4, clamp 成 5
    ]);
    // 不 clamp 的话 ordered=15 / delivered=17 ⇒ outstanding=0, 按钮就消失了。
    expect(s.outstanding).toBeCloseTo(2, 6);
    expect(s.shortLines.map((l) => l.productLabel)).toEqual(['A']);
  });

  it('混单位时不给合计单位 (⛔ 不拿一个单位去标另一种的数)', () => {
    const s = shortCloseSummary([
      { productName: 'A', quantity: 10, deliveredQuantity: 8, unit: 'kg' },
      { productName: 'B', quantity: 4, deliveredQuantity: 1, unit: '箱' },
    ]);
    expect(s.unit).toBe('');
  });

  it('产品名回退链与详情表格同源: productName → productTypeName → productTypeId', () => {
    const s = shortCloseSummary([
      { productTypeName: null, productTypeId: 'eb0aa47b-uuid', quantity: 3, deliveredQuantity: 1, unit: 'kg' },
    ]);
    expect(s.shortLines[0]!.productLabel).toBe('eb0aa47b-uuid');
  });

  it('空明细不炸, 缺口为 0', () => {
    expect(shortCloseSummary(undefined).outstanding).toBe(0);
    expect(shortCloseSummary([]).shortLines).toEqual([]);
  });
});

describe('shortCloseSubmitBlockReason — 与后端 400 分支一一对应', () => {
  it('没选原因 → 拦 (后端 SALES_SHORT_CLOSE_REASON_REQUIRED)', () => {
    expect(shortCloseSubmitBlockReason({ reasonCode: '', notes: '', acknowledged: true }))
      .toBe('请选择短交结案原因');
  });

  it('OTHER 但没写说明 → 拦 (后端 SALES_SHORT_CLOSE_NOTES_REQUIRED)', () => {
    expect(shortCloseSubmitBlockReason({ reasonCode: 'OTHER', notes: '   ', acknowledged: true }))
      .toBe('选择“其他原因”时必须填写补充说明');
  });

  it('说明超 500 字 → 拦 (后端 SALES_SHORT_CLOSE_NOTES_TOO_LONG)', () => {
    expect(shortCloseSubmitBlockReason({ reasonCode: 'SOURCE_SHORTAGE', notes: 'x'.repeat(501), acknowledged: true }))
      .toBe('补充说明不能超过500字');
  });

  it('没勾确认 → 拦 (owner 口径: 必须是显式的、经过确认的点击)', () => {
    expect(shortCloseSubmitBlockReason({ reasonCode: 'SOURCE_SHORTAGE', notes: '', acknowledged: false }))
      .toBe('请先勾选确认，再提交短交结案');
  });

  it('四条都过 → 放行', () => {
    expect(shortCloseSubmitBlockReason({ reasonCode: 'CUSTOMER_WAIVED_REMAINDER', notes: '', acknowledged: true }))
      .toBeNull();
  });

  it('原因选项就是后端 CloseShortShipmentRequest.ReasonCode 的四个值, 一个不多一个不少', () => {
    expect(SHORT_CLOSE_REASON_OPTIONS.map((o) => o.value)).toEqual([
      'CUSTOMER_WAIVED_REMAINDER', 'SOURCE_SHORTAGE', 'ORDER_BALANCE_CANCELLED', 'OTHER',
    ]);
  });
});

describe('shortCloseConfirmText — 弹窗里的每个数字都来自明细', () => {
  it('单行短交样本: 逐条列出 订购/已发/短交 + 合计 + 后果说明', () => {
    const text = shortCloseConfirmText(shortCloseSummary(SHORT_ORDER.items));
    expect(text).toContain('美式汉堡饼：订购 10 kg / 已发 9.8 kg / 短交 0.2 kg');
    expect(text).toContain('合计短交 0.2 kg');
    expect(text).toContain(SHORT_CLOSE_CONSEQUENCE_TEXT);
  });

  it('只列短交的行 —— 已发满的行不进确认文案 (阴性对照)', () => {
    const text = shortCloseConfirmText(shortCloseSummary([
      { productName: '足额行', quantity: 5, deliveredQuantity: 5, unit: 'kg' },
      { productName: '短交行', quantity: 4, deliveredQuantity: 1, unit: 'kg' },
    ]));
    expect(text).toContain('短交行：订购 4 kg / 已发 1 kg / 短交 3 kg');
    expect(text).not.toContain('足额行');
  });

  it('后果说明取自后端枚举 description, 不是前端自己编的一句', () => {
    expect(SHORT_CLOSE_CONSEQUENCE_TEXT).toBe('部分发货后确认剩余数量不再发货，按短交结案');
  });
});
