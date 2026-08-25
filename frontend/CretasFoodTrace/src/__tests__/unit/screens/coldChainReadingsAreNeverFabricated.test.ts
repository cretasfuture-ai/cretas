import { readFileSync } from 'fs';
import { join } from 'path';

/**
 * 温控监控屏不许编造温度读数。
 *
 * 🔴 2026-08-26 真机实测: 这一屏显示「A区 2.5°C / B区 -18.2°C」, 带绿色「正常」徽章、
 * 24 小时趋势图、最高/最低/平均, 以及一个每秒刷新的「最后更新 01:42:40」——
 * 看上去完全是实时冷链数据。而整个文件**没有任何温度 API 调用**, 那两个数字是字面量。
 * 违反本仓核心原则「禁止降级处理 - 不返回假数据, 明确显示错误」。
 *
 * ⚠️ 它此前藏在被 `SIMPLIFIED_WAREHOUSE_NAV` 隐藏的库存 Tab 后面, 仓管够不着;
 *    2026-08-26 接通「仓库地图」之后, 本屏入口变得可达 ——
 *    **一个潜伏缺陷被新入口激活**(本仓记过多次的形态)。
 *    ⇒ 加入口的同一批改动里必须把它一起处理, 不能只加路不看路通向哪。
 */
const SRC = join(__dirname, '../../../..', 'src');
const screen = readFileSync(join(SRC, 'screens/warehouse/inventory/WHTempMonitorScreen.tsx'), 'utf8');

describe('温控监控只在真的有设备时才显示读数', () => {
  it('阳性对照: 闸读到的是那一屏本身', () => {
    // 读错文件/空串时下面的断言会假绿, 这条先炸。
    expect(screen).toContain('WHTempMonitor');
    expect(screen.length).toBeGreaterThan(3000);
  });

  it('⛔ 温区列表里不许再有硬编码的温度读数', () => {
    // 抓 `currentTemp: <数字>` / `avgTemp24h: <数字>` 这类字面量赋值。
    const fabricated = [...screen.matchAll(/(currentTemp|maxTemp24h|minTemp24h|avgTemp24h)\s*:\s*-?\d/g)]
      .map((m) => m[0]);
    expect({ fabricated }).toEqual({ fabricated: [] });
  });

  it('⛔ 没有温区时不许说「系统在线」—— 那句话本身就是个假读数', () => {
    // 「系统在线」必须被 zones.length 守着, 不能无条件渲染。
    expect(screen).toMatch(/zones\.length\s*>\s*0\s*&&[\s\S]{0,200}系统在线/);
  });

  it('空态说的是「未接入」, 而不是一屏空白让人以为坏了', () => {
    expect(screen).toContain('温控设备未接入');
    expect(screen).toContain('testID="wh-temp-monitor-empty"');
  });

  it('阴性对照: 真实的设备告警接口没有被顺手删掉', () => {
    // 这一屏只有温度是编的, 告警记录本来就是真的 —— 修假数据不许连真数据一起砍。
    expect(screen).toContain('alertApiClient.getEquipmentAlerts');
  });
});
