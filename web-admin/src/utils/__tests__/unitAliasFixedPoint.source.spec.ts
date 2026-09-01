import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 缺陷 33 根治闸: `UNIT_ALIASES` 的每一个目标码必须是后端
 * `UnitContractServiceImpl#normalize()` 的<b>不动点</b> —— `normalize(目标码).code() === 目标码`。
 *
 * <h2>为什么是这个判据, 不是一张查找表</h2>
 *
 * `canonicalUnitCode()` 把这张表的目标码当作**内部比较键**, 而多处调用点 (采购/销售/
 * 换算 API) 把这个键**直接**当请求体字段值发出去 (不经 {@code storageUnitCode} 翻译)。
 * 如果目标码本身不是后端规范码, 后端会把它当**别名**再翻一次 —— 多数场景能翻对
 * (`box` 单独翻回 `盒`, 无歧义), 但 `pcs` 这个别名只指向「件」一个目标, 旧表却拿它
 * 同时代表 个/只/件 三个不同的后端规范码, 「个」的身份在**翻译过程本身**里就丢了,
 * 不动点判据能抓住这一类, 也能抓住"看似无歧义但大小写不同"这一类(l/mL)。
 *
 * <h2>为什么必须读 Java 源码, 不能靠记忆里的字符串比对</h2>
 *
 * 后端规范码表会变 (#2628 就整体反转过一次方向: 从"码=英文"变成"码=中文字本身")。
 * 硬编码一份"预期值"在这份测试里, 后端改了这份测试也不会知道 —— 与
 * `unitDisplayContract.spec.ts` / `unitStorageContract.spec.ts` 同一手法: 直接解析
 * `UnitContractServiceImpl.java` 的源码文本重建后端的判定逻辑。
 *
 * <h2>为什么读 unitPricing.ts 的**源码文本**, 不直接 import UNIT_ALIASES</h2>
 *
 * `UNIT_ALIASES` 是私有的, 且刻意不导出 (本文件顶部注释: "不要在这里新建一张
 * 「后端存储码」表" —— 导出它等于鼓励别的模块绕过 canonicalUnitCode() 自己读表)。
 * 读源码文本提取字面量与读 Java 源码是同一手法, 也不需要为了测试放宽封装。
 */

const UNIT_PRICING_SOURCE = readFileSync(
  resolve(__dirname, '..', 'unitPricing.ts'),
  'utf8',
);

const JAVA_AUTHORITY = readFileSync(
  resolve(
    __dirname, '..', '..', '..', '..',
    'backend', 'java', 'cretas-api', 'src', 'main', 'java', 'com', 'cretas', 'aims',
    'service', 'unit', 'impl', 'UnitContractServiceImpl.java',
  ),
  'utf8',
);

/** 从 unitPricing.ts 源码里抠出 `const UNIT_ALIASES: Record<string, string> = { ... };` 这个字面量块。 */
function extractUnitAliasesBlock(source: string): string {
  const match = source.match(/const UNIT_ALIASES: Record<string, string> = \{([\s\S]*?)\n\};/);
  if (!match) {
    throw new Error(
      '前提不成立: unitPricing.ts 里找不到 UNIT_ALIASES 字面量块 —— 这条闸没有量到任何东西, 不要把它读成「通过」。',
    );
  }
  return match[1];
}

/**
 * 从字面量块里取出所有<b>目标码</b> (每个 `key: 'value'` 的 value)。
 *
 * 🔴 只匹配 `冒号 + 引号包裹的值` —— 键既有裸标识符 (`kg`) 也有引号包裹的中文 (`'千克'`),
 * 但值<b>永远</b>是 `: '...'` 这个形状 (本文件另有一条阳性对照钉住这一点), 所以正则
 * 只要锚定"冒号紧跟单引号字符串"就只会命中值, 不会命中键。
 */
function extractTargetCodes(block: string): string[] {
  return [...block.matchAll(/:\s*'([^']+)'/g)].map((m) => m[1]);
}

/** 从 Java 源码解析 `systemAliases()`: code -> 全部写法 (含它自己)。 */
function authorityAliases(): Map<string, string[]> {
  const block = JAVA_AUTHORITY.match(/private static Map<String, String> systemAliases\(\)[\s\S]*?\n {4}}/);
  expect(block, '找不到后端 systemAliases() —— 解析失败会让下面所有断言假绿').not.toBeNull();
  const map = new Map<string, string[]>();
  for (const m of block![0].matchAll(/alias\(aliases,\s*"([^"]+)"((?:,\s*"[^"]*")+)\)/g)) {
    map.set(m[1], [...m[2].matchAll(/"([^"]*)"/g)].map((x) => x[1]));
  }
  expect(map.size, '后端别名组数解析明显不对').toBeGreaterThan(20);
  return map;
}

/** 后端 `key()`: NFKC + trim + 小写。 */
const authorityKey = (raw: string) => raw.normalize('NFKC').trim().toLowerCase();

const ALIASES = authorityAliases();

/** 写法 → 后端规范码; 表里没有的原样返回 (与 UnitContractServiceImpl#normalize 的"认不出就不认"一致)。 */
const ALIAS_TO_CODE = new Map<string, string>();
for (const [code, writings] of ALIASES) {
  for (const writing of writings) ALIAS_TO_CODE.set(authorityKey(writing), code);
}

/**
 * 模拟后端 `normalize(rawUnit).code()` —— 只模拟<b>内置别名表</b>这一段
 * (不含租户自定义目录, 那部分需要 factoryId, 与本文件的判据无关: 不动点判据
 * 只约束"表里出现的目标码相对内置权威表是不是不动点", 不涉及任何租户数据)。
 */
function authorityNormalizedCode(raw: string): string | undefined {
  return ALIAS_TO_CODE.get(authorityKey(raw));
}

const BLOCK = extractUnitAliasesBlock(UNIT_PRICING_SOURCE);
const RAW_TARGETS = extractTargetCodes(BLOCK);
const UNIQUE_TARGETS = [...new Set(RAW_TARGETS)];

describe('阳性对照: 仪器活着, 提取到的是目标码而不是恒空/恒同的东西', () => {
  it('从 unitPricing.ts 源码里提取到了合理数量的目标码', () => {
    // 当前表有 11 个不同目标码 (kg/g/盒/箱/片/袋/件/个/只/l/ml); 用区间而不是钉死数量,
    // 允许以后增删条目, 但 0 个或极端值说明正则没打中源码。
    expect(RAW_TARGETS.length, '一个目标码都没提取到 —— 正则没打中 UNIT_ALIASES 字面量').toBeGreaterThan(10);
    expect(UNIQUE_TARGETS.length).toBeGreaterThan(5);
    expect(UNIQUE_TARGETS.length).toBeLessThan(RAW_TARGETS.length); // 阳性对照: 表里确实有重复目标 (多个别名折到同一码)
  });

  it('值的形状确实是「冒号+引号」—— 键不会被正则误当成值', () => {
    // 直接读一行熟悉的文本验证抽取逻辑本身: '千克': 'kg' 应该只贡献 'kg' 这一个目标,
    // 不能把键 '千克' 也当成目标码混进来。
    expect(BLOCK).toContain("'千克': 'kg'");
    expect(UNIQUE_TARGETS).toContain('kg');
    expect(UNIQUE_TARGETS).not.toContain('千克');
  });

  it('后端别名表解析成功, 且真的既有中文规范码也有英文别名', () => {
    expect(ALIASES.get('盒')).toContain('box');
    expect(ALIASES.get('件')).toContain('pcs');
    expect(authorityNormalizedCode('box')).toBe('盒');
    expect(authorityNormalizedCode('盒')).toBe('盒');
  });
});

describe('不动点判据: UNIT_ALIASES 的每个目标码, 后端 normalize() 之后必须等于它自己', () => {
  it.each(UNIQUE_TARGETS)('目标码 "%s" 是后端 normalize() 的不动点', (target) => {
    const normalized = authorityNormalizedCode(target);
    expect(
      normalized,
      `目标码 "${target}" 在后端别名表里查不到 —— 它既不是规范码也不是已知别名, 无法判定不动点`,
    ).toBeDefined();
    expect(
      normalized,
      `目标码 "${target}" 不是不动点: 后端把它 normalize 成了 "${normalized}", 不是它自己。` +
        `任何直接把 canonicalUnitCode() 的输出当请求体字段值发出去的调用点, 发的会是` +
        `"${target}" 这个别名而不是后端规范码 "${normalized}" —— 多数情况后端能认出别名` +
        `不会立刻炸, 但如果这个别名同时指向多个后端规范码 (如旧表的 'pcs'), 原始身份就丢了。`,
    ).toBe(target);
  });
});

describe('阴性对照: 手工把一个目标码改回旧的折叠写法, 上面那条判据必须能抓住它', () => {
  it("把 UNIT_ALIASES 想象成还在用旧的 'pcs' 折叠写法, 不动点判据会红", () => {
    // 不实际改文件 (那件事由变异测试脚本做, 见 PR 描述), 这里只验证判据本身的杀伤力:
    // 'pcs' 不是后端 normalize() 的不动点 (它会被翻成 '件'), 用同一套判定逻辑验证一次。
    expect(authorityNormalizedCode('pcs')).toBe('件');
    expect(authorityNormalizedCode('pcs')).not.toBe('pcs');
    // 同理旧表里 box/case/slice/bag/L/mL 也都不是不动点 —— 逐个验证判据没有遗漏。
    expect(authorityNormalizedCode('box')).not.toBe('box');
    expect(authorityNormalizedCode('case')).not.toBe('case');
    expect(authorityNormalizedCode('slice')).not.toBe('slice');
    expect(authorityNormalizedCode('bag')).not.toBe('bag');
    expect(authorityNormalizedCode('L')).not.toBe('L'); // 大小写不同, 后端 key() 小写后码是 'l'
    expect(authorityNormalizedCode('mL')).not.toBe('mL');
  });
});
