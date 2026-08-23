import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * 采购订单弹窗：**先显示，再取数**。
 *
 * 2026-08-21 owner 实测「点新建采购订单要 2~3 秒才有反应」。成因不是网络慢 ——
 * `openCreateDialog` 把三个下拉的取数 await 在了 `dialogVisible = true` 前面，
 * 弹窗要等三个请求全回来才出现。
 *
 * ⚠️ 同一个文件里这个结构已经炸过两次（2026-08-04 prod「点了没反应」：其中一个请求
 * 404 抛出，在 `dialogVisible = true` 之前就断了，弹窗**永远**打不开）。
 * ▎「永远打不开」和「2 秒才打开」是同一个结构缺陷的两个刻度。
 * 之前的修复只处理了「失败」那一刻度（加 catch + sticky 提示），没有处理「慢」那一刻度。
 *
 * 本闸钉的是**结构**：新建路径上，「显示弹窗」必须排在任何 await 之前。
 * 这样一来，取数无论慢还是失败，都不可能再影响弹窗出不出来。
 */

const listSource = readFileSync(
  resolve(__dirname, '../list.vue'),
  'utf-8',
);

/**
 * 剥掉注释再扫。
 *
 * ⛔ 这一步不能省：本轮的修复注释里**原样引用了** `dialogVisible = true` 和
 * `await Promise.all([三个 loader])`。不剥注释的话，下面每一条断言都会命中我自己
 * 写的说明文字 —— 本仓 2026-08-20 一晚栽了四次同样的事（三次假阳性 + 一次假阴性）。
 * ⛔ 也不要为了让闸变绿去删注释：该改的是尺子，不是被测对象。
 */
function stripComments(src: string): string {
  return src
    .replace(/<!--[\s\S]*?-->/g, '')   // HTML/template 注释
    .replace(/\/\*[\s\S]*?\*\//g, '')  // 块注释（含 JSDoc）
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1'); // 行注释（避开 http:// 里的双斜杠）
}

const code = stripComments(listSource);

/** 抽出一个具名函数的函数体（按大括号配平），抽不到就抛 —— ⛔ 不返回空串。 */
function bodyOf(name: string): string {
  const head = new RegExp(`function\\s+${name}\\s*\\(`).exec(code);
  if (!head) {
    throw new Error(
      `前提不成立: 源码里找不到 ${name}() —— 这条闸没有量到任何东西，`
      + '不要把它读成「通过」。函数可能被改名或删除了，先修这条判据。',
    );
  }
  const open = code.indexOf('{', head.index);
  let depth = 0;
  for (let i = open; i < code.length; i += 1) {
    if (code[i] === '{') depth += 1;
    else if (code[i] === '}') {
      depth -= 1;
      if (depth === 0) return code.slice(open, i + 1);
    }
  }
  throw new Error(`前提不成立: ${name}() 的大括号没有配平，函数体没抽全`);
}

describe('采购订单弹窗先显示再取数', () => {
  it('前提: 三个下拉 loader 与弹窗开关都还在（否则本文件所有断言都是空转）', () => {
    // 阳性对照。少了任何一个，说明这个文件被重构过，下面的断言可能已经在量空气。
    expect(code).toContain('function loadSuppliers(');
    expect(code).toContain('function loadMaterials(');
    expect(code).toContain('function loadSalesOrders(');
    expect(code).toContain('dialogVisible.value = true');
  });

  it('新建路径: dialogVisible = true 排在任何 await 之前', () => {
    const body = bodyOf('openCreateDialog');

    const showAt = body.indexOf('dialogVisible.value = true');
    expect(showAt, 'openCreateDialog 里没有显示弹窗这一步').toBeGreaterThanOrEqual(0);

    const firstAwait = body.indexOf('await ');
    if (firstAwait >= 0) {
      // 有 await 也行，但必须排在「显示弹窗」之后。
      expect(
        firstAwait,
        '新建路径又把 await 放回了显示弹窗之前 —— 那正是「点了没反应 / 要等两秒」的成因',
      ).toBeGreaterThan(showAt);
    }
  });

  it('没有任何路径把三个下拉阻塞在弹窗前面', () => {
    // 这个写法（不管排在哪）就是本轮修掉的那个形状，⛔ 不允许回来。
    expect(
      code,
      '又出现了阻塞式的三下拉刷新。要刷新请用 refreshDropdownSources()（不 await）',
    ).not.toMatch(/await\s+Promise\.all\(\[\s*loadSuppliers\(\)/);
  });

  it('刷新仍然发生 —— ⛔ 修「慢」不能修成「不刷新」', () => {
    /*
     * 2026-04-28 张权反馈「基础数据已新建但采购订单下拉没有选项」，那条修复的意图是
     * **每次打开对话框强制刷新**。本轮改的是「阻塞地刷新」，不是「不刷新」。
     * 这条阴性对照防止后人把问题「优化」成只在 onMounted 取一次。
     */
    expect(code).toContain('function refreshDropdownSources(');
    expect(bodyOf('openCreateDialog')).toContain('refreshDropdownSources()');
    expect(bodyOf('openEditDialog')).toContain('refreshDropdownSources()');
  });

  it('刷新期间下拉给出可见的「正在更新」状态', () => {
    // 不加这个，用户会在列表更新前看到旧选项而毫无提示。
    expect(code).toContain('dropdownsRefreshing');
    expect(listSource).toContain(':loading="dropdownsRefreshing"');
  });

  it('编辑路径: 草稿判定仍然排在显示弹窗之前', () => {
    /*
     * ⛔ 编辑路径**不能**照搬「先开弹窗」：非草稿单根本不该开，先开再关会闪一下，
     *    比慢更糟。这条钉住那个顺序不被后人"顺手统一"掉。
     */
    const body = bodyOf('openEditDialog');
    const draftCheck = body.indexOf("!== 'DRAFT'");
    const showAt = body.indexOf('dialogVisible.value = true');
    expect(draftCheck, '编辑路径的草稿判定不见了').toBeGreaterThanOrEqual(0);
    expect(showAt).toBeGreaterThanOrEqual(0);
    expect(
      draftCheck,
      '草稿判定被挪到显示弹窗之后 —— 非草稿单会先弹出来再消失',
    ).toBeLessThan(showAt);
  });
});
