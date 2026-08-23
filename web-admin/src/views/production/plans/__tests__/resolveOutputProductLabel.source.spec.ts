import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 「核对结单」终端产出行 / 「仓库确认入库」产出行此前直接把 productTypeId 打给用户。
 * 用户实拍截图：`eb0aa47b-a5dd-49dc-af20-bf48ce8e1207`，而不是「SOP-...-黄油鸡-成品800g」。
 *
 * list.vue 体量太大（5000+ 行），本目录既有测试对它一律走「读源码文本 + 抽出函数体真实执行」
 * 的路子，而不是整份 mount —— 这里延续同一套路，但把断言收紧到「解析出的名字等于期望值」，
 * 不只是「不等于 UUID」（后者对残缺实现也可能通过，见规则 A: 阳性对照必须断言到期望值本身）。
 */
describe('resolveOutputProductLabel — 终端产出/入库产出行的品名解析', () => {
  const source = readFileSync(resolve(__dirname, '../list.vue'), 'utf8');

  it('阳性对照: 源码读得到函数定义', () => {
    expect(source).toContain('function resolveOutputProductLabel(id: unknown): string {');
  });

  it('两个已知会显示 UUID 的模板位置都改用了它 (不是只加了函数没接线)', () => {
    expect(source).toContain('{{ resolveOutputProductLabel(output.productTypeId) }}');
    expect(source).toContain('{{ resolveOutputProductLabel(line.productTypeId) }}');
    // 旧的裸露写法不应该再出现
    expect(source).not.toContain("{{ output.productTypeId || '未识别 SKU' }}");
    expect(source).not.toContain('<strong>{{ line.productTypeId }}</strong>');
  });

  it('副产 SKU 落在 raw_material_types, 主产落在 product_types — 两张表都查了 (PR #2839 同款坑)', () => {
    expect(source).toContain('raw-material-types/active');
    expect(source).toContain('rawMaterialTypesForNames.value.find');
    expect(source).toContain('productTypes.value.find');
  });

  // 抽出函数体真正执行, 而不是只做字符串比对 —— 真正验证解析逻辑, 不是验证「有没有写这几个字」。
  function extractResolver(): (id: unknown) => string {
    const match = source.match(
      /function resolveOutputProductLabel\(id: unknown\): string \{([\s\S]*?)\n\}/,
    );
    if (!match) throw new Error('resolveOutputProductLabel 源码没抽到 —— 测试本身失效');
    // 函数体里唯一的 TS 类型标注是箭头函数参数 `(p: TableRow)` —— 剥掉才能喂给 new Function
    // (纯运行时 JS 不认类型标注)。剥的是类型语法, 不是逻辑, 与硬约束 5 同一个精神:
    // 不改行为, 只改能不能被当前工具解析。
    const body = match[1].replace(/\(p: TableRow\)/, '(p)');
    // eslint-disable-next-line @typescript-eslint/no-implied-eval
    const fn = new Function('productTypes', 'rawMaterialTypesForNames', 'id', body);
    return (id: unknown) =>
      fn(
        { value: [{ id: 'PT-1', name: '黄油鸡-成品800g' }] },
        { value: [{ id: 'RMT-2', name: '鸡骨架(副产)' }] },
        id,
      );
  }

  it('命中 product_types: 返回期望的那个品名 (不是随便一个非 UUID 字符串)', () => {
    const resolve = extractResolver();
    expect(resolve('PT-1')).toBe('黄油鸡-成品800g');
  });

  it('命中 raw_material_types (副产): 返回期望的那个品名', () => {
    const resolve = extractResolver();
    expect(resolve('RMT-2')).toBe('鸡骨架(副产)');
  });

  it('查不到: 给明确中文说明, 绝不回落成传入的 id 本身', () => {
    const resolve = extractResolver();
    const unresolvedId = 'eb0aa47b-a5dd-49dc-af20-bf48ce8e1207';
    const label = resolve(unresolvedId);
    expect(label).toBe('未知产品(产品档案查不到)');
    expect(label).not.toBe(unresolvedId);
    expect(label).not.toContain(unresolvedId);
  });

  it('id 本身缺失 (null/空串): 与「查不到」区分开, 不是同一句话', () => {
    const resolve = extractResolver();
    expect(resolve(null)).toBe('未识别 SKU');
    expect(resolve('')).toBe('未识别 SKU');
  });
});
