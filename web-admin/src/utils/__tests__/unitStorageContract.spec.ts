import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { canonicalUnitCode, displayUnit, storageUnitCode } from '../unitPricing';

/**
 * 单位**落库值**契约 —— 「前端发出去的那个字」必须与后端
 * `UnitContractServiceImpl#storageUnit` 落库的是同一个单位, 且**计数/包装单位不许发英文**。
 *
 * 🔴 为什么要单独一份, 而不是并进 `unitDisplayContract.spec.ts`:
 * 那一份守的是**展示**(用户眼睛看到什么), 这一份守的是**请求体**(线上发出去什么)。
 * 2026-08-18 之前两者被同一个函数 `canonicalUnitCode` 顺带管着, 而它的方向与后端**相反** ——
 * 前端 `盒 → box`, 后端 (#2628 起) `box → 盒`。于是用户在下拉里选「盒」,
 * 请求体里躺着 `box`, 靠后端写入口 (#2869) 再翻回来。中间那趟翻译不该存在:
 * 任何一处漏翻, 仓管就在发货单上读到英文 (实测 `sales_delivery_items` 至今有 2 行 `box`)。
 *
 * 本文件直接读**后端权威表源码**推出「后端会存成什么」, 所以:
 * - 后端加了新单位而前端没跟 → 红
 * - 前端把某个单位翻成了别的单位 → 红
 * - 前端把计数/包装单位发成英文 → 红
 */

/** 后端权威表: backend/java/.../service/unit/impl/UnitContractServiceImpl.java */
const AUTHORITY = readFileSync(
  resolve(
    __dirname, '..', '..', '..', '..',
    'backend', 'java', 'cretas-api', 'src', 'main', 'java', 'com', 'cretas', 'aims',
    'service', 'unit', 'impl', 'UnitContractServiceImpl.java',
  ),
  'utf8',
);

type AuthorityUnit = { code: string; dimension: string; factorToBase: string | null };

/**
 * 解析 `add(units, "kg", UnitDimension.MASS, "g", "1000", "公斤", 3)`。
 * 第 5 个参数是 factorToBase —— `null` 表示**没有普适换算**, 正是「码用中文字」的那一类。
 */
function authorityUnits(): AuthorityUnit[] {
  const block = AUTHORITY.match(/private static Map<String, CanonicalUnit> systemUnits\(\)[\s\S]*?\n {4}}/);
  expect(block, '找不到后端 systemUnits() —— 解析失败会让下面所有断言假绿').not.toBeNull();
  const units = [...block![0].matchAll(
    /add\(units,\s*"([^"]+)",\s*UnitDimension\.(\w+),\s*"[^"]*",\s*(null|"[^"]*")/g,
  )].map((m) => ({
    code: m[1],
    dimension: m[2],
    factorToBase: m[3] === 'null' ? null : m[3].slice(1, -1),
  }));
  expect(units.length, '后端至少应有 20 个内置单位, 解析明显不对').toBeGreaterThan(20);
  return units;
}

/** 解析 `alias(aliases, "盒", "盒", "box")` → code 到全部写法。 */
function authorityAliases(): Map<string, string[]> {
  const block = AUTHORITY.match(/private static Map<String, String> systemAliases\(\)[\s\S]*?\n {4}}/);
  expect(block, '找不到后端 systemAliases()').not.toBeNull();
  const map = new Map<string, string[]>();
  for (const m of block![0].matchAll(/alias\(aliases,\s*"([^"]+)"((?:,\s*"[^"]*")+)\)/g)) {
    map.set(m[1], [...m[2].matchAll(/"([^"]*)"/g)].map((x) => x[1]));
  }
  expect(map.size, '后端别名组数解析明显不对').toBeGreaterThan(20);
  return map;
}

const UNITS = authorityUnits();
const ALIASES = authorityAliases();

/** 后端 `key()`: NFKC + trim + 小写。 */
const authorityKey = (raw: string) => raw.normalize('NFKC').trim().toLowerCase();

/** 别名写法 → 后端规范码; 表里没有的原样返回 (后端 storageUnit 规则 1)。 */
const ALIAS_TO_CODE = new Map<string, string>();
for (const [code, writings] of ALIASES) {
  for (const writing of writings) ALIAS_TO_CODE.set(authorityKey(writing), code);
}
function authorityCode(raw: string): string {
  const trimmed = String(raw ?? '').trim();
  if (!trimmed) return '';
  return ALIAS_TO_CODE.get(authorityKey(trimmed)) ?? trimmed;
}

const hasHan = (value: string) => /\p{Script=Han}/u.test(value);
const isLatin = (value: string) => /^[A-Za-z]+$/.test(value);

/** 所有 (写法, 所属单位) 对 —— 遍历的就是「用户/接口可能给我们什么」。 */
const ALL_WRITINGS: Array<[string, AuthorityUnit]> = UNITS.flatMap((unit) =>
  (ALIASES.get(unit.code) ?? []).map((writing) => [writing, unit] as [string, AuthorityUnit]));

const COUNTING_WRITINGS = ALL_WRITINGS.filter(
  ([, unit]) => unit.dimension === 'COUNT' || unit.dimension === 'PACKAGE');

describe('阳性对照: 仪器活着, 且被测的那一类输入真的存在', () => {
  it('解析出的写法数量与量纲分布合理', () => {
    expect(ALL_WRITINGS.length, '写法总数解析明显不对').toBeGreaterThan(40);
    expect(COUNTING_WRITINGS.length, '计数/包装写法解析明显不对').toBeGreaterThan(20);
    // 没有换算因子的那一类, 后端的码就是中文字本身 —— 本文件的全部前提
    expect(UNITS.filter((u) => u.factorToBase === null).every((u) => hasHan(u.code))).toBe(true);
    expect(UNITS.filter((u) => u.factorToBase !== null).every((u) => isLatin(u.code))).toBe(true);
  });

  it('计数/包装单位确实存在英文写法 —— 否则「不许发英文」是恒真式', () => {
    const english = COUNTING_WRITINGS.filter(([writing]) => isLatin(writing)).map(([w]) => w);
    expect(english, '后端一个英文别名都没有的话, 下面那条断言守不住任何东西').toContain('box');
    expect(english.length).toBeGreaterThan(5);
  });

  it('内部比较键与落库值确实是两个方向 —— 否则本文件没有存在的理由', () => {
    // 前端内部键把「盒」折成 box (下拉 value / 去重都用它), 而后端的码是「盒」。
    // 这条不钉死具体值, 只钉「两者不同」: 哪天内部键也改成中文, 它会红, 提醒重读 storageUnitCode。
    const divergent = COUNTING_WRITINGS.filter(
      ([writing]) => canonicalUnitCode(writing) !== authorityCode(writing));
    expect(divergent.length, '内部键已与后端同向了? 请重读 storageUnitCode 的注释').toBeGreaterThan(0);
  });
});

describe('storageUnitCode: 发出去的必须还是同一个单位', () => {
  it.each(ALL_WRITINGS.map(([writing, unit]) => [writing, unit.code]))(
    '「%s」发出去之后后端仍然读成 %s',
    (writing, code) => {
      expect(authorityCode(storageUnitCode(writing))).toBe(code);
    },
  );

  it('认不出的单位原样发出, 不猜 (禁降级)', () => {
    expect(storageUnitCode('自定义单位')).toBe('自定义单位');
    expect(storageUnitCode('   ')).toBe('');
    expect(storageUnitCode(null)).toBe('');
    expect(storageUnitCode(undefined)).toBe('');
  });
});

describe('storageUnitCode: 计数/包装单位不许把英文码发给后端', () => {
  it.each(COUNTING_WRITINGS.map(([writing, unit]) => [writing, unit.code]))(
    '「%s」发出去的是中文 (后端码 %s)',
    (writing) => {
      const sent = storageUnitCode(writing);
      expect(hasHan(sent), `「${writing}」发出去的是「${sent}」—— 英文码不许进请求体`).toBe(true);
      // 阴性对照单独钉一遍最典型的那个: 只写 not.toBe('box') 太容易恒真
      expect(isLatin(sent)).toBe(false);
    },
  );

  it('盒/箱/袋/件 这几个真实存量单位逐个点名 (正反两面都断言)', () => {
    expect(storageUnitCode('box')).toBe('盒');
    expect(storageUnitCode('盒')).toBe('盒');
    expect(storageUnitCode('case')).toBe('箱');
    expect(storageUnitCode('箱')).toBe('箱');
    expect(storageUnitCode('bag')).toBe('袋');
    expect(storageUnitCode('pcs')).toBe('件');
    // 阴性对照: 英文码不许回来
    expect(storageUnitCode('box')).not.toBe('box');
    expect(storageUnitCode('盒')).not.toBe('box');
  });

  it('只/个/件 是三个单位, 发出去时保用户字面 (#1976)', () => {
    expect(storageUnitCode('只')).toBe('只');
    expect(storageUnitCode('个')).toBe('个');
    expect(storageUnitCode('件')).toBe('件');
    // 遗留复合写法自带用户当初填的标签
    expect(storageUnitCode('pcs:只')).toBe('只');
  });
});

describe('storageUnitCode: 可换算的国际单位保持拉丁符号, 不连坐改中文', () => {
  const CONVERTIBLE = UNITS.filter((u) => u.factorToBase !== null);

  it.each(CONVERTIBLE.map((u) => u.code))('规范码 %s 原样发出', (code) => {
    // 后端 key() 会小写, 所以只比到大小写不敏感 (前端用 L / mL 这种国际写法)
    expect(storageUnitCode(code).toLowerCase()).toBe(code.toLowerCase());
    expect(isLatin(storageUnitCode(code))).toBe(true);
  });

  it('中文写法归一到拉丁码, 与展示层一致', () => {
    expect(storageUnitCode('公斤')).toBe('kg');
    expect(storageUnitCode('千克')).toBe('kg');
    expect(storageUnitCode('克')).toBe('g');
    expect(storageUnitCode('毫升')).toBe('mL');
  });

  /**
   * ⚠️ 已知且**有意**的例外: 斤/吨。后端的码是 `jin`/`t`, 而这里发中文 ——
   * 后端 normalize 会把它们收回 `jin`/`t`, 落库值不变 (上面那条 authorityCode 断言已覆盖)。
   * 反过来在前端把「斤」翻成 `jin` 正是 2026-07-31 那次事故, 不要做。
   */
  it('斤/吨 发中文, 但后端仍读成 jin/t', () => {
    expect(storageUnitCode('斤')).toBe('斤');
    expect(storageUnitCode('吨')).toBe('吨');
    expect(authorityCode(storageUnitCode('斤'))).toBe('jin');
    expect(authorityCode(storageUnitCode('吨'))).toBe('t');
  });
});

describe('storageUnitCode 与 displayUnit 的关系', () => {
  /**
   * 🔴 它们**不是**同一个函数, 别顺手合并。`displayUnit` 服务于眼睛,
   * 改它的动机随时会出现 (#1672 就把 `pcs` 的标签从「件」改成「只」又改回来);
   * 而 `storageUnitCode` 服务于落库, 跟着展示动会**静默改写已落库的数据**。
   * 这条断言钉住的是「它们对同一个输入可以不同」这件事本身。
   */
  it('斤/吨 上两者一致, 而这纯属巧合 —— 关系由各自的契约决定, 不是互相定义的', () => {
    expect(displayUnit('jin')).toBe('斤');
    expect(storageUnitCode('jin')).toBe('jin');
  });
});
