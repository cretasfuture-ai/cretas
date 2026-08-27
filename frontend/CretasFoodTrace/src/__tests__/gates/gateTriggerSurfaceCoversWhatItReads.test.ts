/**
 * 元判据（RN 侧）：**一道闸扫的东西，必须在它自己的触发面里。**
 *
 * web-admin 侧已有等价的闸（`web-admin/src/router/__tests__/gateTriggerSurfaceCoversWhatItReads.spec.ts`，
 * PR #177）。这是 RN 侧的那一道 —— 与 `screenReachability.test.ts` 同样的孪生关系。
 *
 * ## 为什么需要这一条
 *
 * RN 侧有若干「源码扫描型」测试，用 `readFileSync` 去读**后端 Java 源码**做契约比对
 * （枚举标签、字段锁、批次分配口径）。这类闸有一个静默失效的方式：
 *
 * > 闸写对了、跑起来也确实会红 —— 但**改被它守的那个文件不会触发它**。
 *
 * 于是它只在「顺带被别的改动触发」时才跑，真正该拦的那个 PR 一次都不过它。
 * memory 有同形事故：`feedback_the_gate_ran_but_was_never_triggered`。
 *
 * ## 实测（2026-08-27，建闸当天）
 *
 * `rn-gate.yml` 的 `paths:` 只有 `frontend/CretasFoodTrace/**` 和它自己，
 * 而 RN 的测试真读着 **8 个** Java 文件 —— 一个都没登记：
 *
 * ```
 *   entity/enums/MaterialBatchStatus.java   entity/enums/PurchaseOrderStatus.java
 *   entity/enums/TransferStatus.java        entity/enums/TransferType.java
 *   service/impl/MaterialBatchServiceImpl.java
 *   service/inventory/impl/SalesServiceImpl.java
 *   service/inventory/impl/SalesDeliveryBatchAllocationServiceImpl.java
 *   mapper/MaterialBatchMapper.java
 * ```
 *
 * ⇒ 一个**只改 Java 枚举**的 PR，恰好是这几道闸要守的那类漂移，
 *   而它们看不见它。
 *
 * ## ⚠️ 代理性与已知盲区（写明，不靠加启发式逼近）
 *
 *  · **按 basename 反查真实路径**，不去猜路径是怎么拼出来的 ——
 *    RN 侧三种写法都有：整条字面量、`path.resolve(backendRoot, rel)`、
 *    以及包一层 helper（`javaEnumValues('TransferStatus.java')`）。
 *    按 basename 反查对三种都成立。
 *  · **同名文件**：一个 basename 对应多个真实文件时，要求**全部**被覆盖。
 *    宁可多登记一条，也不在这里猜「大概是哪一个」。
 *  · **只管 rn-gate 这一道闸**，不做成扫全部 workflow 的通用闸 ——
 *    那需要判定「哪个 workflow 跑哪个测试」，是启发式。
 *    窄而可信 > 宽而被关掉（形态 E）。
 */

import fs from 'fs';
import path from 'path';

const RN_SRC = path.resolve(__dirname, '../..');
const REPO = path.resolve(RN_SRC, '../../..');
const WORKFLOW = path.join(REPO, '.github/workflows/rn-gate.yml');
const BACKEND = path.join(REPO, 'backend/java');

/** 剥注释 —— 否则被注释掉的引用会被当成真引用（本仓多次栽在这上面）。 */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
}

function javaBasenamesReferenced(dir: string, acc = new Set<string>()): Set<string> {
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name);
    if (fs.statSync(full).isDirectory()) {
      if (name === 'node_modules') continue;
      javaBasenamesReferenced(full, acc);
      continue;
    }
    if (!/\.test\.tsx?$/.test(name)) continue;
    const src = stripComments(fs.readFileSync(full, 'utf8'));
    for (const m of src.matchAll(/['"`]([^'"`]*?([A-Za-z0-9_$]+\.java))['"`]/g)) {
      const base = m[2];
      if (base) acc.add(base);
    }
  }
  return acc;
}

/** 在 backend/java 下反查 basename 的真实路径（相对仓库根，正斜杠）。 */
function locateJava(
  dir: string,
  want: Set<string>,
  acc = new Map<string, string[]>(),
): Map<string, string[]> {
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name);
    if (fs.statSync(full).isDirectory()) {
      if (name === 'target' || name === 'node_modules') continue;
      locateJava(full, want, acc);
      continue;
    }
    if (!want.has(name)) continue;
    // ⚠️ 归一化分隔符 —— Windows 上是反斜杠，不归一化后面的 glob 比较会恒假。
    //    routeReachability 建闸时就是栽在这一条上（读数 83 vs 真值 84）。
    const rel = full.slice(REPO.length + 1).split(path.sep).join('/');
    acc.set(name, [...(acc.get(name) ?? []), rel]);
  }
  return acc;
}

/**
 * 取 paths: 列表（push 与 pull_request 合起来算触发面）。
 *
 * ⚠️ 抽成接受字符串的纯函数，是为了让**阳性对照能直接测解析器本身** ——
 *    见下面 `PARSER_FIXTURE`。第一版的阳性对照断言的是
 *    「解析结果里得有至少一条 .java」，那在两种相反的情况下都会挂：
 *      (a) 解析器坏了            ⇒ 下面的「未覆盖」读数不可信
 *      (b) 触发面里真的一条都没有 ⇒ 下面的「未覆盖」读数**恰恰是对的**
 *    一条把这两种压在一起的断言，等于没有告诉你该不该信那个读数。
 */
function parsePaths(text: string): string[] {
  const lines = text.split('\n');
  const out: string[] = [];
  let inPaths = false;
  for (const line of lines) {
    if (/^\s*paths:\s*$/.test(line)) {
      inPaths = true;
      continue;
    }
    if (inPaths) {
      // ⚠️ paths 块里**可能夹着注释行**。web-admin 那道闸的第一版把注释当成块结束
      //    ⇒ 3 条 Java 路径一条都没读进来，而阳性对照 `length > 2` 照样绿。
      //    ⇒ 跳过注释与空行，不要终止。
      if (/^\s*(#.*)?$/.test(line)) continue;
      const m = /^\s*-\s*'?([^'#]+?)'?\s*(?:#.*)?$/.exec(line);
      const captured = m?.[1];
      if (captured) {
        out.push(captured.trim());
        continue;
      }
      inPaths = false;
    }
  }
  return out;
}

/** glob → 正则。只需支持 `**` 与 `*`，本仓的 paths 没有更复杂的形态。 */
function globMatches(glob: string, p: string): boolean {
  // ⛔ 不用「哨兵字符」两段式替换 —— 第一版用 NUL 当哨兵，而它**落盘成了真的
  //    0x00 字节**（不是转义序列），于是 git 把整个文件判成 binary，
  //    所有基于 grep 的闸对它整份失明。仓里已有同形事故记录。
  // ⇒ 一次 replace 带回调，从物理上没有哨兵这个东西。
  //    ⚠️ 交替里 `\\*\\*` 必须排在 `\\*` 前面，否则 `**` 会被先当成两个 `*`。
  const rx = glob.replace(/\.|\*\*|\*/g, (m) =>
    m === '.' ? '[.]' : m === '**' ? '.*' : '[^/]*',
  );
  return new RegExp('^' + rx + '$').test(p);
}

/**
 * 阳性对照专用的 fixture —— **注释行夹在两条 path 中间**，这正是 web-admin
 * 那道闸第一版栽的地方（解析器把注释当成块结束，后面的路径一条都没读进来，
 * 而当时的阳性对照 `length > 2` 照样绿）。
 * 用固定输入测解析器，读数不随仓库当前内容变化。
 */
const PARSER_FIXTURE = [
  'on:',
  '  pull_request:',
  '    paths:',
  "      - 'first/**'",
  '      # 一行注释，解析器不许在这里停',
  '',
  "      - 'second/Thing.java'   # 行尾注释",
  '  push:',
  '    branches: [main]',
].join('\n');

const BASENAMES = javaBasenamesReferenced(RN_SRC);
const LOCATED = locateJava(BACKEND, BASENAMES);
const PATHS = parsePaths(fs.readFileSync(WORKFLOW, 'utf8'));

const UNCOVERED = Array.from(LOCATED.values())
  .flat()
  .filter((real) => !PATHS.some((g) => globMatches(g, real)))
  .sort();

describe('元判据（RN）：闸扫的东西必须在它的触发面里', () => {
  it('阳性对照：解析器读得过注释行（用固定 fixture 测它自己）', () => {
    // 🔴 这里**不**断言「当前 rn-gate.yml 里有几条 .java」——
    //    那个断言在两种相反的情况下都会挂（解析器坏了 / 触发面真的没有），
    //    等于没告诉你该不该信下面的读数。用固定输入直接测解析器。
    expect(parsePaths(PARSER_FIXTURE)).toEqual(['first/**', 'second/Thing.java']);
  });

  it('阳性对照：扫描与反查都读到了东西', () => {
    expect(BASENAMES.size).toBeGreaterThan(0);
    expect(LOCATED.size).toBeGreaterThan(0);
    expect(PATHS.length).toBeGreaterThan(0);
  });

  it('阴性对照：glob 匹配器是活的，且不过宽', () => {
    // 若匹配器恒不匹配，UNCOVERED 会把所有文件都列出来，看着像一堆真缺口。
    expect(PATHS.some((g) => globMatches(g, 'frontend/CretasFoodTrace/App.tsx'))).toBe(true);
    // 反向：一个明显不在触发面里的路径不许匹配上。
    expect(PATHS.some((g) => globMatches(g, 'backend/python/main.py'))).toBe(false);
  });

  it('RN 的测试读的每个 Java 文件，都在 rn-gate 的触发面里', () => {
    expect(UNCOVERED).toEqual([]);
  });
});
