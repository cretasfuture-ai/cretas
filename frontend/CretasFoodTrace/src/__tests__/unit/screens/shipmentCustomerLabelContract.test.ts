/**
 * 发货历史 (WHOutboundList / WHOutboundDetail) 的客户列只显示客户名, 不显示内部 id。
 * 入口确认: 两屏都注册在 WHOutboundStackNavigator, 首页发货卡经 WHHomeStack 跳 WHOutboundDetail。
 */
import { readFileSync } from 'fs';
import { join } from 'path';
import { shipmentCustomerLabel, UNKNOWN_CUSTOMER_LABEL } from '../../../screens/warehouse/outbound/shipmentCustomerLabel';

const read = (rel: string) => readFileSync(join(__dirname, '../../..', rel), 'utf8');

describe('shipmentCustomerLabel', () => {
  it('有客户名就显示客户名', () => {
    expect(shipmentCustomerLabel({ customerName: '  六膳门 ' })).toBe('六膳门');
  });
  it('没有 / 空白 / null → 未知客户, 绝不显示 id', () => {
    for (const customerName of [undefined, null, '', '   ']) {
      expect(shipmentCustomerLabel({ customerName })).toBe(UNKNOWN_CUSTOMER_LABEL);
    }
    const withId = { customerId: '3215df20-abe3-461e-9436-ece1a0cb2567', customerName: null } as { customerName: null };
    expect(shipmentCustomerLabel(withId)).not.toMatch(/3215df20/);
  });
});

describe('发货历史两屏走同一个客户名函数', () => {
  const files = [
    'screens/warehouse/outbound/WHOutboundListScreen.tsx',
    'screens/warehouse/outbound/WHOutboundDetailScreen.tsx',
  ];
  it.each(files)('%s 用 shipmentCustomerLabel, 不再拿 customerId 兜底', (f) => {
    const src = read(f);
    expect(src).toContain('shipmentCustomerLabel(shipment)');
    expect(src).not.toMatch(/customerId\s*(\|\||\?\?)/);
  });
  it('阳性对照: 上面的正则对旧写法确实匹配', () => {
    expect("customer: shipment.customerId || '未知客户'").toMatch(/customerId\s*(\|\||\?\?)/);
  });
  it('两屏确是出库栈里注册的入口', () => {
    const nav = read('navigation/warehouse/WHOutboundStackNavigator.tsx');
    expect(nav).toContain('name="WHOutboundList"');
    expect(nav).toContain('name="WHOutboundDetail"');
  });
});
