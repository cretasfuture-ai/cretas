/**
 * 仓储 App 三个幸存屏(入库列表/扫码收货/待收货任务/发货列表/发货确认/我的)
 * 视觉与信息密度精简的回归闸 (2026-08-21)。
 *
 * 设计卡: docs/superpowers/specs/2026-08-21-warehouse-app-simplification-design.md
 *
 * ⚠️ 口径（代理判据，明写出来）：沿用本目录既有惯例 —— 读源码做契约，看不见运行时行为。
 * ⚠️ 剥注释再断言 —— 本文件的说明注释里就原样引用了被测的中文串（如「下单日期」
 *    「已加载批次统计」），不剥的话会命中自己的注释（本仓 2026-08 已栽过多次同形）。
 */
import fs from 'fs';
import path from 'path';

const sourceRoot = path.resolve(__dirname, '../../../');
const read = (rel: string) => fs.readFileSync(path.resolve(sourceRoot, rel), 'utf8');

/** 剥掉块注释、JSX 注释、行注释 —— 与本目录其它闸同一套写法。 */
const strip = (src: string) =>
  src
    .replace(/\{\s*\/\*[\s\S]*?\*\/\s*\}/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1');

describe('入库列表: 真实收货状态与大号现场操作', () => {
  const code = strip(read('screens/warehouse/inbound/WHInboundListScreen.tsx'));

  it('阳性对照: 文件读得到, 且是这一屏', () => {
    expect(code.length).toBeGreaterThan(2000);
    expect(code).toContain('待收货任务');
  });

  it('底部「已加载批次统计」4 格聚合数已删除', () => {
    expect(code).not.toContain('已加载批次统计');
    expect(code).not.toContain('statsGrid');
  });

  it('扫码入库是至少 56dp 的大按钮，餐饮待验收已移除', () => {
    expect(code).toContain('testID="wh-scan-inbound"');
    expect(code).toMatch(/largeActionContent:\s*\{\s*minHeight:\s*56/);
    expect(code).not.toContain('餐饮待验收');
    expect(code).not.toContain('SupplierDeliveryList');
  });

  it('筛选只保留全部、待收货、部分入库、已收货', () => {
    for (const label of ['全部', '待收货', '部分入库', '已收货']) expect(code).toContain(label);
    expect(code).not.toContain("label: '已完成'");
    expect(code).not.toContain('质检中');
  });

  it('列表卡片用 TouchableRipple (触感反馈), 不是 TouchableOpacity', () => {
    expect(code).toContain('<TouchableRipple');
    expect(code).not.toContain('TouchableOpacity');
  });
});

describe('扫码收货填单: 订单信息卡瘦身, 厂号/产地默认收起, 照片删除按钮触摸区达标', () => {
  const code = strip(read('screens/warehouse/inbound/WHReceiptCreateScreen.tsx'));

  it('阳性对照: 文件读得到, 且是这一屏', () => {
    expect(code.length).toBeGreaterThan(2000);
    expect(code).toContain('扫码入库录入');
  });

  it('订单信息卡不再显示下单日期与明细行数 (不帮他判断"是不是这一车货")', () => {
    expect(code).not.toContain('下单日期');
    expect(code).not.toContain('明细行数');
    // 阴性对照 (故意保留): 订单号/供应商/期望交货三项仍在
    expect(code).toContain('订单号');
    expect(code).toContain('供应商');
    expect(code).toContain('期望交货');
  });

  it('厂号/产地默认收起 (可选追溯信息, 不是核心两字段任务的一部分)', () => {
    const idx = code.indexOf('traceInfoExpanded');
    expect(idx).toBeGreaterThan(-1);
    expect(code).toMatch(/traceInfoExpanded\s*,\s*setTraceInfoExpanded\]\s*=\s*useState\(false\)/);
  });

  it('照片删除按钮补了 hitSlop, 触摸区达到防呆 44pt 约束(视觉圆点仍是 22px 不变)', () => {
    const idx = code.indexOf('photoDeleteBtn');
    expect(idx).toBeGreaterThan(-1);
    expect(code).toMatch(/photoDeleteBtn[\s\S]{0,300}hitSlop=\{\{\s*top:\s*12/);
  });
});

describe('发货列表: 卡片补货品+数量摘要, 旧流程入口降级为文本链接', () => {
  const code = strip(read('screens/warehouse/outbound/WHDeliveryConfirmListScreen.tsx'));

  it('阳性对照: 文件读得到, 且是这一屏', () => {
    expect(code.length).toBeGreaterThan(2000);
    expect(code).toContain('发货确认');
  });

  it('卡片逐项显示货品名、数量和单位，并标出共几项', () => {
    expect(code).toContain('function deliveryItemLines');
    expect(code).toMatch(/deliveryItemLines\(row\)/);
    expect(code).toContain('productName');
    expect(code).toContain('计划发');
    expect(code).toContain('单位未标注');
    expect(code).toContain('共 {itemLines.length} 项');
  });

  it('「手工发货登记」不再是与卡片同权重的整行按钮', () => {
    // 阴性对照: 旧的 <Button mode="outlined" ... testID="wh-goto-manual-shipment"> 不见了
    expect(code).not.toMatch(/<Button[\s\S]{0,300}testID="wh-goto-manual-shipment"/);
    // 正面: 换成了轻量文本链接, testID 保留 (不影响其它可能的定位)
    expect(code).toMatch(/<TouchableRipple[\s\S]{0,200}testID="wh-goto-manual-shipment"/);
  });
});

describe('发货确认: 实发数量输入框放大字号(与扫码收货页同口径)', () => {
  const code = strip(read('screens/warehouse/outbound/WHDeliveryConfirmScreen.tsx'));

  it('阳性对照: 文件读得到, 且是这一屏', () => {
    expect(code.length).toBeGreaterThan(2000);
    expect(code).toContain('确认并扣库存');
  });

  it('实发数量输入用 contentStyle 放大到 24px', () => {
    expect(code).toContain('qtyInputContent');
    expect(code).toMatch(/contentStyle=\{styles\.qtyInputContent\}/);
    expect(code).toMatch(/qtyInputContent:\s*\{\s*fontSize:\s*24\s*\}/);
  });
});

describe('我的: 菜单去重(温控监控/扫码作业曾各自一行却同跳 WHSettings), 去掉写死的 badge', () => {
  const code = strip(read('screens/warehouse/profile/WHProfileScreen.tsx'));

  it('阳性对照: 文件读得到, 且是这一屏', () => {
    expect(code.length).toBeGreaterThan(2000);
    expect(code).toContain('menuSections');
  });

  it('"温控监控"/"扫码作业"两个重复入口(同跳 WHSettings)已合并', () => {
    expect(code).not.toContain('key: "temp-monitor"');
    expect(code).not.toContain('key: "scan"');
    // 阴性对照 (故意保留): 设置本身还在, 真实功能没被删
    expect(code).toContain('key: "settings"');
  });

  it('不再有写死的 badge: 4 (不返回假数据这一核心原则)', () => {
    expect(code).not.toMatch(/badge:\s*4\s*,/);
  });

  it('菜单行用 TouchableRipple', () => {
    expect(code).toMatch(/<TouchableRipple\s*\n\s*style=\{styles\.menuItem\}/);
  });
});
