import fs from 'fs';
import path from 'path';

const root = path.resolve(__dirname, '../../../');
const home = fs.readFileSync(path.resolve(root, 'screens/warehouse/home/WHHomeScreen.tsx'), 'utf8');

describe('仓管首页动作必须可用', () => {
  it('入库和发货各自有非空处理器和明确目的地', () => {
    expect(home).toMatch(/testID="wh-home-inbound"[\s\S]{0,240}onPress=/);
    expect(home).toMatch(/testID="wh-home-outbound"[\s\S]{0,240}onPress=/);
    expect(home).toContain("openTab('WHInboundTab')");
    expect(home).toContain("openTab('WHOutboundTab')");
  });

  it('找不到父导航时明确报错并告诉用户怎么恢复', () => {
    expect(home).toContain('仓管底部导航不可用');
    expect(home).toContain('退出账号重新登录');
  });
});
