import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 元判据：**一道闸扫的东西，必须在它自己的触发面里。**
 *
 * ## 为什么需要这一条
 *
 * 本仓的闸大量是「源码扫描型」—— 测试用 `readFileSync` 去读**另一个模块**的源码，
 * 断言它没长回某个形状。这类闸有一个静默失效的方式：
 *
 * > 闸写对了、跑起来也确实会红 —— 但**改被它守的那个文件不会触发它**。
 *
 * 于是它只在「顺带被别的改动触发」时才跑。真正该拦的那个 PR 一次都不过它。
 * memory 里已有一条同形事故（`feedback_the_gate_ran_but_was_never_triggered`）。
 *
 * ## 这不是假想 —— 建闸当天实测 4 处
 *
 * `web-admin-gate.yml` 的 `paths:` 登记了 3 个 backend Java 文件
 * （`UnitContractServiceImpl` / `ProductionProgressDashboardController` /
 * `ProductionModuleRegistry`），说明**这条原则本来就被认可**，只是靠人记。
 * 而同一时刻另有 4 个 Java 文件被 web-admin 的 spec 真读着、**一个都没登记**：
 *
 * ```
 *   constant/SeasoningProcessCategory.java          ← seasoningProcessCategory.spec.ts
 *   dto/bom/BomCostSummaryDTO.java                  ← bomContractsSurvivingPageDeletion.source.spec.ts
 *   service/impl/BomServiceImpl.java                       同上
 *   service/bom/impl/BomRecipeServiceImpl.java             同上
 * ```
 *
 * 两个集合**零重叠** —— 典型的形态 D：同一条原则有两份（登记的 / 实际读的），
 * 没有闸钉住就一定漂。本闸就是那道钉子。
 *
 * ## 判据
 *
 * 对 `web-admin/src` 下每个 `*.spec.ts`：它引用的每个 `.java` 文件，
 * 都必须被 `.github/workflows/web-admin-gate.yml` 的 `paths:` 里某条 glob 覆盖。
 *
 * ## ⚠️ 代理性与已知盲区（写明，不靠加启发式逼近）
 *
 *  · **按 basename 反查真实路径**，而不是解析字符串怎么拼出来的。
 *    因为路径有两种写法：整条字面量（`'../backend/java/.../Foo.java'`）和
 *    拆开拼（`join(base, rel)`，见 `productionProgressWallAccess.spec.ts:49`）。
 *    按 basename 反查对两种都成立 —— **不去猜构造方式**，是这里唯一不会漏的做法。
 *  · **同名文件**：一个 basename 在仓里对应多个真实文件时，要求**全部**被覆盖。
 *    宁可多登记一条，也不要在这里做「大概是哪一个」的猜测。
 *  · **只管 web-admin 这一道闸**。RN / Python / Java 侧的同款检查不在本闸范围 ——
 *    ⛔ 不把它做成「扫全部 workflow」的通用闸：那需要判定「哪个 workflow 跑哪个测试」，
 *    而那是启发式，正是本仓反复吃亏的东西。窄而可信 > 宽而被关掉（形态 E）。
 */

const SRC = resolve(__dirname, '../..');
const REPO = resolve(SRC, '../..');
const WORKFLOW = join(REPO, '.github/workflows/web-admin-gate.yml');

/** 剥注释 —— 否则被注释掉的路径引用会被当成真引用（本仓多次栽在这上面）。 */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
}

/** 收集 web-admin spec 里引用到的 .java basename。 */
function javaBasenamesReferenced(dir: string, acc = new Set<string>()): Set<string> {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      if (name === 'node_modules') continue;
      javaBasenamesReferenced(full, acc);
      continue;
    }
    if (!name.endsWith('.spec.ts')) continue;
    const src = stripComments(readFileSync(full, 'utf8'));
    for (const m of src.matchAll(/['"`]([^'"`]*?([A-Za-z0-9_$]+\.java))['"`]/g)) {
      acc.add(m[2]);
    }
  }
  return acc;
}

/** 在 backend/java 下反查 basename 的真实路径（相对仓库根，正斜杠）。 */
function locateJava(dir: string, want: Set<string>, acc = new Map<string, string[]>()): Map<string, string[]> {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      if (name === 'target' || name === 'node_modules') continue;
      locateJava(full, want, acc);
      continue;
    }
    if (!want.has(name)) continue;
    // ⚠️ 归一化分隔符 —— Windows 上是反斜杠，不归一化后面的 glob 比较会恒假。
    const rel = full.slice(REPO.length + 1).split('\\').join('/');
    acc.set(name, [...(acc.get(name) ?? []), rel]);
  }
  return acc;
}

/** 取 workflow 的 paths: 列表（push 与 pull_request 合起来算触发面）。 */
function workflowPaths(): string[] {
  const lines = readFileSync(WORKFLOW, 'utf8').split('\n');
  const out: string[] = [];
  let inPaths = false;
  for (const line of lines) {
    if (/^\s*paths:\s*$/.test(line)) { inPaths = true; continue; }
    if (inPaths) {
      // ⚠️ paths 块里**夹着注释行**（本仓的 web-admin-gate.yml 就有 5 行说明）。
      // 第一版把注释行当成块结束 ⇒ 3 条 Java 路径一条都没读进来，
      // 而 `PATHS.length > 2` 这个阳性对照**照样绿**（两块各 2 条 = 4 条）。
      // 那正是「阳性对照量的不是要紧的那一维」。⇒ 跳过注释与空行，不要终止。
      if (/^\s*(#.*)?$/.test(line)) continue;
      const m = /^\s*-\s*'?([^'#]+?)'?\s*(?:#.*)?$/.exec(line);
      if (m) { out.push(m[1].trim()); continue; }
      inPaths = false;
    }
  }
  return out;
}

/** glob → 正则。只需支持 `**` 与 `*` 两种，本仓的 paths 没有更复杂的形态。 */
function globMatches(glob: string, path: string): boolean {
  const rx = glob
    .split('.').join('[.]')
    .split('**').join('\u0000')
    .split('*').join('[^/]*')
    .split('\u0000').join('.*');
  return new RegExp('^' + rx + '$').test(path);
}

const BASENAMES = javaBasenamesReferenced(SRC);
const LOCATED = locateJava(join(REPO, 'backend/java'), BASENAMES);
const PATHS = workflowPaths();

const UNCOVERED = Array.from(LOCATED.entries())
  .flatMap(([, reals]) => reals)
  .filter((real) => !PATHS.some((g) => globMatches(g, real)))
  .sort();

describe('元判据：闸扫的东西必须在它的触发面里', () => {
  it('阳性对照：仪器确实读到了三样东西（任一读空都会让下面那条假绿）', () => {
    expect(BASENAMES.size, 'web-admin 的 spec 里一个 .java 引用都没扫到 —— 仪器坏了').toBeGreaterThan(0);
    expect(LOCATED.size, '扫到了 .java 名字但在 backend/java 下一个都反查不到 —— 反查坏了').toBeGreaterThan(0);
    expect(PATHS.length, 'web-admin-gate.yml 的 paths 读成空了 —— 那样「全都没覆盖」是假读数').toBeGreaterThan(2);
    // 🔴 上面那条**不够**。第一版就是它绿着而解析器是坏的：paths 块里的注释行
    //    被当成块结束, 只读到 'web-admin/**' 与工作流自身两条(两块共 4 条 > 2, 照样过)。
    //    要紧的那一维是「有没有读到 .java 那几条」——量它, 别量条数。
    expect(
      PATHS.filter((p) => p.endsWith('.java')).length,
      'paths 里一条 .java 都没读到 —— 解析器坏了, 下面的「未覆盖」全是假读数',
    ).toBeGreaterThan(0);
  });

  it('阴性对照：已登记的那三个文件判为已覆盖（证明 glob 匹配器真的会匹配）', () => {
    // 若匹配器坏了(恒不匹配), 上面那条 UNCOVERED 会把所有文件都列出来, 看着像一堆真缺口。
    // 这三条钉住「匹配器是活的」。
    const registered = [
      'backend/java/cretas-api/src/main/java/com/cretas/aims/service/unit/impl/UnitContractServiceImpl.java',
      'backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ProductionProgressDashboardController.java',
      'backend/java/cretas-api/src/main/java/com/cretas/aims/permission/ProductionModuleRegistry.java',
    ];
    for (const r of registered) {
      expect(PATHS.some((g) => globMatches(g, r)), `${r} 应判为已覆盖`).toBe(true);
    }
    // 反向: 一个明显不该被覆盖的路径不许匹配上(否则匹配器过宽, 一切皆绿)。
    expect(
      PATHS.some((g) => globMatches(g, 'backend/java/cretas-api/src/main/java/com/cretas/aims/NotReferenced.java')),
      '匹配器过宽 —— 连没被引用的文件都判成已覆盖了',
    ).toBe(false);
  });

  it('web-admin 的 spec 读的每个 Java 文件，都在 web-admin-gate 的触发面里', () => {
    expect(
      UNCOVERED,
      `以下 Java 文件被 web-admin 的 spec 读着，但改它们不会触发 web-admin-gate ——\n` +
        `  ${UNCOVERED.join('\n  ')}\n\n` +
        `⇒ 那道闸只会在「顺带被别的改动触发」时才跑，真正该拦的 PR 一次都不过它。\n` +
        `修法：把它们加进 .github/workflows/web-admin-gate.yml 的 paths:（push 与 pull_request 两处都要）。`,
    ).toEqual([]);
  });
});
