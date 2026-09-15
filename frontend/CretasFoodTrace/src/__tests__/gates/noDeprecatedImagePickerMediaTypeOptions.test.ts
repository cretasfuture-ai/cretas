/**
 * 闸: expo-image-picker 的 `mediaTypes` 不许再用已废弃的 `MediaTypeOptions` 枚举 (SDK 52 起废弃)。
 *
 * 症状 (2026-09-15 真机 logcat, 每次打开相册/相机都打一条):
 *   W/ReactNativeJS: [expo-image-picker] ImagePicker.MediaTypeOptions have been deprecated.
 *   Use ImagePicker.MediaType or an array of ImagePicker.MediaType instead.
 *
 * 核过 node_modules/expo-image-picker@16.1.4:
 * - `MediaType = 'images' | 'videos' | 'livePhotos'` 是**纯类型别名**, 运行时没有导出 ⇒ 新写法只有
 *   字面量数组 `['images']` (或单个字符串)。
 * - build/utils.js `parseMediaTypes()` 在值 === 枚举成员 (`'Images'`/`'Videos'`/`'All'`) 时 console.warn;
 *   字面量数组不触发。
 *
 * ## 两层, 各守一件事
 * 1. 源码 token 扫描: 非测试源码里 `MediaTypeOptions` 出现次数必须为 0 (剥注释后数)。
 * 2. 运行时行为: 把源码里每个 `mediaTypes:` 的字面量真喂给库的 `parseMediaTypes`, 断言不 warn;
 *    **阳性对照**: 喂枚举成员必须 warn —— 对照不响, 说明仪器死了, 第 2 层的绿没有意义。
 *
 * ## 三态
 * - walk 到的文件数 / `mediaTypes:` 站点数 / expo-image-picker 引用文件数 低于地板 ⇒ INSTRUMENT DEAD。
 *   (2026-09-15 实测: 15 站点 / 9 文件; 正则打空与「全清了」长得一样, 所以必须先证明数得到东西。)
 * - `mediaTypes:` 的值不是字面量 ⇒ 这一层**没能做决定**, 直接红, 不静默跳过 (形态 E′)。
 *
 * 剥注释后再数 —— setup.ts 的 mock 说明里就提到这个枚举名, 不剥会把自己的文案数进去
 * (setup.ts 在 __tests__ 下, walk 已排除; 但源码里别人写注释提到它也不该算违规)。
 */
import fs from 'fs';
import path from 'path';

const SRC = path.resolve(__dirname, '../..');

/** 剥注释但**保留换行数** —— 否则报出来的 file:line 会比真实行号小 (块注释被压成一个空格, 变异实测报 92 实为 107)。 */
function stripComments(src: string): string {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/^\s*\/\/.*$/gm, ' ')
    .replace(/\s\/\/.*$/gm, ' ');
}

function walk(dir: string, acc: string[] = []): string[] {
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name);
    const st = fs.statSync(full);
    if (st.isDirectory()) {
      if (name === 'node_modules' || name === '__tests__' || name === 'test' || name === 'mocks') continue;
      walk(full, acc);
    } else if (/\.(ts|tsx)$/.test(name) && !/\.(test|spec)\.tsx?$/.test(name)) {
      acc.push(full);
    }
  }
  return acc;
}

/**
 * 2026-09-15 迁移当天实测: 全树 26 个 `mediaTypes:` 站点 / 16 个引用 expo-image-picker 的文件
 * (其中 15 站点 / 9 文件是当天从枚举迁过来的, 其余餐饮屏此前已是字面量数组)。
 * 地板留出删几个屏的余量; 正则打空时读数是 0, 任何 >0 的地板都拦得住。⛔ 只许往上调。
 */
const FILE_FLOOR = 300;
const SITE_FLOOR = 20;
const IMPORTER_FLOOR = 12;

type Site = { rel: string; line: number; raw: string; value: string | string[] | null };

/** `['images', 'videos']` / `'images'` → JS 值; 其它形状 → null (没能做决定) */
function parseLiteral(raw: string): string | string[] | null {
  const t = raw.trim().replace(/,$/, '').trim();
  const arr = t.match(/^\[(.*)\]$/);
  if (arr) {
    const inner = arr[1] ?? '';
    const items = inner.match(/(['"])([^'"]*)\1/g) ?? [];
    // 数组里只有字符串字面量才算「能决定」: `['images', foo]` 这种混合形状归 null
    const stripped = inner.replace(/(['"])([^'"]*)\1/g, '').replace(/[\s,]/g, '');
    if (stripped.length) return null;
    return items.map((s) => s.slice(1, -1));
  }
  const str = t.match(/^(['"])([^'"]*)\1$/);
  if (str) return str[2] ?? '';
  return null;
}

describe('expo-image-picker: MediaTypeOptions 已废弃, mediaTypes 必须用 MediaType[] 字面量', () => {
  const files = walk(SRC);
  const sites: Site[] = [];
  const deprecatedHits: string[] = [];
  let importers = 0;

  for (const f of files) {
    const rel = path.relative(SRC, f).split(path.sep).join('/');
    const code = stripComments(fs.readFileSync(f, 'utf8'));
    if (/from\s*['"]expo-image-picker['"]/.test(code)) importers += 1;
    for (const m of code.matchAll(/\bMediaTypeOptions\b/g)) {
      const line = code.slice(0, m.index).split('\n').length;
      deprecatedHits.push(`${rel}:${line}`);
    }
    // 值的形状: 数组字面量 / 字符串字面量 / 其它 (截到 , } 或行尾, 归「没能决定」)。
    // ⚠️ 不能写 `[^\n]+`: 一行写完的 `{ mediaTypes: ['images'], quality: 0.82 }` 会把后面的键也吞进来。
    for (const m of code.matchAll(/\bmediaTypes\s*:\s*(\[[^\]\n]*\]|'[^'\n]*'|"[^"\n]*"|[^,}\n]+)/g)) {
      const line = code.slice(0, m.index).split('\n').length;
      const raw = m[1] ?? '';
      sites.push({ rel, line, raw, value: parseLiteral(raw) });
    }
  }

  it('INSTRUMENT ALIVE: 扫到了足够多的文件 / mediaTypes 站点 / expo-image-picker 引用 (全 0 = 正则打空)', () => {
    expect(files.length).toBeGreaterThan(FILE_FLOOR);
    expect(sites.length).toBeGreaterThanOrEqual(SITE_FLOOR);
    expect(importers).toBeGreaterThanOrEqual(IMPORTER_FLOOR);
    console.warn(`[image-picker-gate] files=${files.length} sites=${sites.length} importers=${importers}`);
  });

  it('源码里 MediaTypeOptions 必须为 0 —— 新加一处就红 (真机每次开相册都会 warn)', () => {
    expect(deprecatedHits.map((h) => `${h} (改用 mediaTypes: ['images'] 这类 MediaType[] 字面量)`).join(', ')).toBe('');
  });

  it('每个 mediaTypes 值必须是字面量 —— 不是字面量这一层就没法判, 直接红而不是跳过', () => {
    const undecidable = sites.filter((s) => s.value === null);
    expect(undecidable.map((s) => `${s.rel}:${s.line} mediaTypes: ${s.raw.trim()}`).join(', ')).toBe('');
  });

  it('运行时: 源码里每个 mediaTypes 字面量喂给库的 parseMediaTypes 都不触发废弃 warn (含阳性对照)', () => {
    // 只有裸 'expo-image-picker' 被 setup.ts mock 掉; 子路径是真代码 (transformIgnorePatterns 放行 expo-*)
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const utils = jest.requireActual('expo-image-picker/build/utils') as {
      parseMediaTypes: (v: unknown) => string[];
    };
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const types = jest.requireActual('expo-image-picker/build/ImagePicker.types') as {
      MediaTypeOptions: Record<'All' | 'Videos' | 'Images', string>;
    };
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    try {
      // 🔴 阳性对照先行: 枚举成员必须 warn。不响 ⇒ 仪器死了, 下面的「不 warn」全部作废。
      warn.mockClear();
      utils.parseMediaTypes(types.MediaTypeOptions.Images);
      const control = warn.mock.calls.map((c) => String(c[0])).filter((s) => /MediaTypeOptions.*deprecated/.test(s));
      expect(control.length > 0 ? 'alive' : 'INSTRUMENT DEAD — 喂废弃枚举也不 warn, 库行为变了或 spy 没接上').toBe('alive');

      const offenders: string[] = [];
      for (const s of sites) {
        if (s.value === null) continue; // 上一条已经红过了, 这里不重复报
        warn.mockClear();
        const out = utils.parseMediaTypes(s.value);
        const fired = warn.mock.calls.some((c) => /MediaTypeOptions.*deprecated/.test(String(c[0])));
        if (fired) offenders.push(`${s.rel}:${s.line} mediaTypes: ${s.raw.trim()} → parseMediaTypes 打了废弃 warn`);
        // 顺手守语义: 库归一化后必须是非空 MediaType[] (空数组 = 相册里什么都选不了)
        if (!Array.isArray(out) || out.length === 0) offenders.push(`${s.rel}:${s.line} 归一化结果为空: ${JSON.stringify(out)}`);
      }
      expect(offenders.join(', ')).toBe('');
    } finally {
      warn.mockRestore();
    }
  });
});
