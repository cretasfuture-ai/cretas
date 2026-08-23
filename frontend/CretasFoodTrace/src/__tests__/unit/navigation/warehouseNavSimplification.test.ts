import { readFileSync } from 'fs';
import { join } from 'path';

const SRC = join(__dirname, '../../../..', 'src');
const read = (path: string) => readFileSync(join(SRC, path), 'utf8');
const nav = read('navigation/WarehouseManagerTabNavigator.tsx');
const home = read('screens/warehouse/home/WHHomeScreen.tsx');

describe('仓管四导航与首页分流', () => {
  it('固定显示首页、入库、发货、我的，库存仍由收敛开关隐藏', () => {
    for (const tab of ['WHHomeTab', 'WHInboundTab', 'WHOutboundTab', 'WHProfileTab']) {
      expect(nav).toContain(`name="${tab}"`);
    }
    expect(nav).toMatch(/initialRouteName="WHHomeTab"/);
    expect(nav).toMatch(/!SIMPLIFIED_WAREHOUSE_NAV[\s\S]{0,200}name="WHInventoryTab"/);
    expect(nav).not.toMatch(/!SIMPLIFIED_WAREHOUSE_NAV[\s\S]{0,200}name="WHHomeTab"/);
    expect(nav).toContain("isScreenEnabled('InboundManagement')");
    expect(nav).toContain("isScreenEnabled('OutboundManagement')");
    expect(nav).toContain('inboundEnabled ? WHInboundStackNavigator : InboundUnavailableScreen');
    expect(nav).toContain('outboundEnabled ? WHOutboundStackNavigator : OutboundUnavailableScreen');
    expect(nav).toContain('testID="wh-feature-unavailable"');
  });

  it('首页只有两个大业务入口并切到对应底部导航', () => {
    expect(home).toContain('testID="wh-home-inbound"');
    expect(home).toContain('testID="wh-home-outbound"');
    expect(home).toContain("openTab('WHInboundTab')");
    expect(home).toContain("openTab('WHOutboundTab')");
    expect(home).toContain('getParent<TabNav>()');
    expect(home).not.toContain('dashboardAPI');
    expect(home).not.toContain('getMaterialBatches');
  });

  it('生产成品入库与退出入口没有因四导航改动丢失', () => {
    const inbound = read('screens/warehouse/inbound/WHInboundListScreen.tsx');
    expect(inbound).toContain('testID="wh-production-receipt-entry"');
    expect(inbound).toContain("navigation.navigate('WHTransitLedger')");
    expect(nav).toContain('name="WHProfileTab"');
  });
});

describe('采购单品名字段', () => {
  it('客户端与收货页使用后端真实字段 materialName', () => {
    const client = read('services/api/purchaseApiClient.ts');
    const receipt = read('screens/warehouse/inbound/WHReceiptCreateScreen.tsx');
    expect(client).toContain('materialName?: string');
    expect(receipt).toContain('materialName');
    expect(receipt).not.toContain('materialTypeName');
  });
});
