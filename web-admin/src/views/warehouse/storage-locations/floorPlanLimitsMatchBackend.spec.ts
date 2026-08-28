import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 🔴 平面图的体量上限是**两份**：前端一份（提前拦住、给人话提示），后端一份（真正的权威）。
 *
 * 「同一个东西两份，它一定会漂」—— 漂了之后的长相不是报错，而是：
 * 用户在界面上画到第 300 条被拦下（前端说上限 300），或者画到 400 条都不拦、
 * 点保存被后端一个 400 顶回来（前端根本不知道后端的上限是多少）。
 * 两种都不报错，只是难用。
 *
 * <p>这道闸钉住三种关系，各自的判据不同：
 * <ol>
 *   <li><b>两边都定义了同一个上限</b> → 必须**相等**（标注条数：300 / 300）</li>
 *   <li><b>前端更严</b> → 允许，但必须 ≤ 后端（自由笔抽稀到 400，后端放到 1000）。
 *       后端是权威上界，前端严一点只是提前拦住，不会造成"前端放行、后端拒绝"。</li>
 *   <li><b>只有后端有</b> → 登记在案（楼层数 50 / 单层区域块 200）。
 *       用户会撞上一个没有前端提示的 400，这是已知的 UX 缺口，不是数据风险。</li>
 * </ol>
 *
 * ⚠️ **代理判据，标出来**：它用正则从源码里抠常量值，不验证这些常量真的被用在校验里。
 * 已知盲区：常量还在但没人读它，闸看不出来。
 */

const REPO = resolve(process.cwd(), '..');

const BACKEND = readFileSync(
  resolve(REPO, 'backend/java/cretas-api/src/main/java/com/cretas/aims/controller/WarehouseLayoutController.java'),
  'utf8',
);

const FRONTEND = readFileSync(
  resolve(process.cwd(), 'src/views/warehouse/storage-locations/warehouseLayoutModel.ts'),
  'utf8',
);

/** Java: `private static final int NAME = 1_000;` —— 数字里可能有下划线分隔符。 */
function javaConst(name: string): number | null {
  const m = new RegExp(`${name}\\s*=\\s*([0-9_]+)`).exec(BACKEND);
  return m ? Number(m[1].replace(/_/g, '')) : null;
}

/** TS: `export const NAME = 300;` */
function tsConst(name: string): number | null {
  const m = new RegExp(`${name}\\s*=\\s*([0-9]+)`).exec(FRONTEND);
  return m ? Number(m[1]) : null;
}

describe('平面图体量上限: 前端那份不许和后端漂开', () => {
  it('阳性对照: 两边的常量都真的读到了（读不到最像「一切一致」）', () => {
    expect(javaConst('MAX_ANNOTATIONS_PER_FLOOR')).not.toBeNull();
    expect(javaConst('MAX_ANNOTATION_POINTS')).not.toBeNull();
    expect(javaConst('MAX_FLOORS')).not.toBeNull();
    expect(javaConst('MAX_ZONE_SHAPES_PER_FLOOR')).not.toBeNull();
    expect(tsConst('MAX_ANNOTATIONS_PER_FLOOR')).not.toBeNull();
    expect(tsConst('MAX_FREEHAND_POINTS')).not.toBeNull();
  });

  it('🔴 两边都定义的上限必须相等 —— 标注条数', () => {
    // 不一致的后果: 用户会在两个不同的数字上被拦, 而两边都不报错, 只是难用
    expect(tsConst('MAX_ANNOTATIONS_PER_FLOOR')).toBe(javaConst('MAX_ANNOTATIONS_PER_FLOOR'));
  });

  it('前端更严是允许的, 但不许超过后端 —— 自由笔点数', () => {
    const front = tsConst('MAX_FREEHAND_POINTS')!;
    const back = javaConst('MAX_ANNOTATION_POINTS')!;
    expect(front).toBeLessThanOrEqual(back);
  });

  it('⛔ 登记: 这两个上限只有后端有, 前端撞上去只会看到一个没有提示的 400', () => {
    // 登记是留痕不是豁免 —— 哪天前端加了对应提示, 把它挪到"必须相等"那一组。
    expect(tsConst('MAX_FLOORS')).toBeNull();
    expect(tsConst('MAX_ZONE_SHAPES_PER_FLOOR')).toBeNull();
    // 后端确实拦着, 所以这不是数据风险, 只是 UX 缺口
    expect(javaConst('MAX_FLOORS')).toBeGreaterThan(0);
    expect(javaConst('MAX_ZONE_SHAPES_PER_FLOOR')).toBeGreaterThan(0);
  });
});
