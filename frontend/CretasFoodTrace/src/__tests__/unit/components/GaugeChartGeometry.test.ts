import { polarToCartesian } from '../../../components/charts/GaugeChart';

/**
 * 表盘几何闸。
 *
 * 背景（2026-08-29 逐屏走查实拍）：`GaugeChart` 的极坐标换算把整张表盘**水平镜像**了 ——
 * 0 画在右端、满值画在左端，刻度「80」落在左边、「20」落在右边。
 *
 * 它之所以能长期不被发现，是因为弧、指针、刻度**都走同一个换算函数**，
 * 三者自洽地一起反着，看上去只是「一个表盘」，不像坏了。
 * 直到 KPI 屏上读出「综合评分 9.3 分 / 评级 D」而指针停在右侧绿区。
 *
 * 这道闸量的是**几何**，不是像素：0 必须在左、满值必须在右、中值必须在上。
 */
describe('GaugeChart 极坐标换算', () => {
  const CX = 100;
  const CY = 100;
  const R = 50;

  // 表盘约定: 180° 在左(9 点), 0° 在右(3 点), 90° 在正上。
  // 组件里 valueAngle = 180 - (百分比/100) * 180, 所以:
  const angleForPercent = (pct: number) => 180 - (pct / 100) * 180;

  it('0° 在右 —— 满值那一端', () => {
    const p = polarToCartesian(CX, CY, R, 0);
    expect(p.x).toBeCloseTo(CX + R, 6);
    expect(p.y).toBeCloseTo(CY, 6);
  });

  it('180° 在左 —— 零值那一端', () => {
    const p = polarToCartesian(CX, CY, R, 180);
    expect(p.x).toBeCloseTo(CX - R, 6);
    expect(p.y).toBeCloseTo(CY, 6);
  });

  it('90° 在正上（SVG 的 y 轴向下，所以是 cy - r）', () => {
    const p = polarToCartesian(CX, CY, R, 90);
    expect(p.x).toBeCloseTo(CX, 6);
    expect(p.y).toBeCloseTo(CY - R, 6);
  });

  it('🔴 这就是那次镜像: 0% 的指针必须在左半边, ⛔ 不许在右', () => {
    const tip = polarToCartesian(CX, CY, R, angleForPercent(0));
    expect(tip.x).toBeLessThan(CX);
  });

  it('🔴 100% 的指针必须在右半边', () => {
    const tip = polarToCartesian(CX, CY, R, angleForPercent(100));
    expect(tip.x).toBeGreaterThan(CX);
  });

  it('50% 的指针指向正上方', () => {
    const tip = polarToCartesian(CX, CY, R, angleForPercent(50));
    expect(tip.x).toBeCloseTo(CX, 6);
    expect(tip.y).toBeLessThan(CY);
  });

  it('百分比越大, 指针的 x 越靠右 —— 单调, ⛔ 不许反向', () => {
    const xs: number[] = [0, 10, 25, 50, 75, 90, 100].map(
      (pct) => polarToCartesian(CX, CY, R, angleForPercent(pct)).x
    );
    for (let i = 1; i < xs.length; i++) {
      const prev = xs[i - 1] as number;
      expect(xs[i] as number).toBeGreaterThan(prev);
    }
  });

  it('刻度盘上「20」比「80」更靠左 —— 实拍里正好是反的', () => {
    const x20 = polarToCartesian(CX, CY, R, angleForPercent(20)).x;
    const x80 = polarToCartesian(CX, CY, R, angleForPercent(80)).x;
    expect(x20).toBeLessThan(x80);
  });

  it('半径为 0 时退化到圆心 —— 阴性对照, 免得上面几条只是碰巧', () => {
    const p = polarToCartesian(CX, CY, 0, 137);
    expect(p.x).toBeCloseTo(CX, 6);
    expect(p.y).toBeCloseTo(CY, 6);
  });

  it('所有点都落在半径为 R 的圆上 —— 换算本身没有拉伸', () => {
    for (const deg of [0, 30, 45, 90, 135, 180]) {
      const p = polarToCartesian(CX, CY, R, deg);
      const dist = Math.hypot(p.x - CX, p.y - CY);
      expect(dist).toBeCloseTo(R, 6);
    }
  });
});
