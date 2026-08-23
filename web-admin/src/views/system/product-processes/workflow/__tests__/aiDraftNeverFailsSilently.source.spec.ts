/**
 * 客户原话:「AI 改完我也看不到区别」。
 *
 * 成因不是 AI 没干活, 是前端**静默退出**: 「应用 N 道工序到草稿」的两条落地函数
 * (`applyWorkflowAIDraft` / `buildWorkflowFromSpec`) 里有几条**裸 return** ——
 * 条件不满足就直接退出, 不给任何提示。用户点了按钮、页面毫无反应, 他唯一合理的
 * 解释就是「AI 没起作用」。
 *
 * 修之前逐条数过（硬约束 8: 改之前先数, 改完再数）:
 *
 *   applyWorkflowAIDraft —— 6 条提前退出
 *     ① 入口守卫(4 个原因: 无 identity / 无 sourceIdentity / 画布是别的产品 / 无写权限)  ❌ 静默 → 已修
 *     ② payload.patches 不是数组                                                    ✅ 本来就有提示
 *     ③ 补丁校验有错                                                                ✅ 本来就有提示
 *     ④ 补丁全被安全校验拒掉                                                          ✅ 本来就有提示
 *     ⑤ 用户在确认框点了取消                                                          🔵 **故意静默**(见下)
 *     ⑥ 确认之后的重查(2 个原因: 画布被切走 / 权限变化)                                  ❌ 静默 → 已修
 *
 *   buildWorkflowFromSpec —— 静默的有 2 条
 *     ⑦ 用户在确认框点了取消                                                          🔵 **故意静默**
 *     ⑧ 确认之后的重查(2 个原因)                                                      ❌ 静默 → 已修
 *
 *   ⇒ 8 条里: 3 条本来就有提示(不动), 3 条已补提示, 2 条登记为**故意静默**。
 *
 * 🔵 为什么⑤⑦故意不提示: 那是**用户自己点的「取消」**。给一个「你取消了」的弹窗
 *    属于噪音, 而且会训练用户忽略这一类提示 —— 形态 E: 会误报的提示比没有提示更糟。
 *    它们的形状是 `catch { return; }`(ElMessageBox reject), 与「条件不满足」可区分。
 *
 * ⚠️ 本闸是**棘轮**: 它数的是「没有提示的裸 return 还剩几条」, 只许减不许增。
 *    新加一条静默退出会红, 并打印它的行号。
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(import.meta.dirname, '../ProductProcessWorkflowEditor.vue'),
  'utf8',
);

/** 用大括号配对取出一个函数体（够用即可：这两个函数里没有字符串含裸大括号的情况）。 */
function functionBody(name: string): { body: string; startLine: number } {
  const at = source.indexOf(`function ${name}(`);
  expect(at, `找不到函数 ${name} —— 它被改名了, 请同步本测试`).toBeGreaterThan(-1);
  const open = source.indexOf('{', source.indexOf(')', at));
  let depth = 0;
  let i = open;
  for (; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    else if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) break;
    }
  }
  return {
    body: source.slice(open, i + 1),
    startLine: source.slice(0, open).split('\n').length,
  };
}

/**
 * 找出「没有给用户任何反馈」的裸 return。
 *
 * ⚠️ 第一版用「往前数 6 行有没有 ElMessage」当判据 —— 两头都错:
 *   · 假阳性: 把 `await buildWorkflowFromSpec(...); return;` 这条**交棒**当成静默失败;
 *   · 假阴性: `if (!identity) return;` 因为**上一个分支**的 ElMessage 恰好落在窗口里而放行。
 * 行窗口量的是「附近有没有这串字符」, 不是「这条路径有没有说话」。
 * ⇒ 改成按**所属块**判: 找到该 return 所在的那一对大括号, 只看那个块。
 */
function silentReturns(name: string): string[] {
  const { body, startLine } = functionBody(name);
  const offenders: string[] = [];
  const lineOf = (index: number) => startLine + body.slice(0, index).split('\n').length - 1;

  const re = /(^|[^\w.$])return;/g;
  let match = re.exec(body);
  while (match !== null) {
    const at = match.index + match[1].length;

    // 往回找这条 return 所属的那个 `{`（深度配对）。
    let depth = 0;
    let open = -1;
    for (let i = at; i >= 0; i -= 1) {
      if (body[i] === '}') depth += 1;
      else if (body[i] === '{') {
        if (depth === 0) { open = i; break; }
        depth -= 1;
      }
    }
    const block = open >= 0 ? body.slice(open, at) : '';
    const header = open >= 0 ? body.slice(Math.max(0, open - 60), open) : '';

    // ① 这条路径自己说了话
    const hasFeedback = /ElMessage(Box)?\s*[.({]/.test(block);
    // ② 用户主动点了取消（ElMessageBox reject）—— 故意静默，已登记
    const isUserCancel = /\bcatch\s*(\([^)]*\))?\s*$/.test(header.trim());
    // ③ 交棒给另一个同样被本闸盯着的函数 —— 不是失败路径
    const isDelegation = /await\s+buildWorkflowFromSpec\s*\(/.test(block);

    if (!hasFeedback && !isUserCancel && !isDelegation) {
      const line = body.split('\n')[lineOf(at) - startLine] ?? '';
      offenders.push(`${name} 第 ${lineOf(at)} 行: ${line.trim()}`);
    }
    match = re.exec(body);
  }
  return offenders;
}

describe('AI 生成的工序「应用到草稿」不许静默失败', () => {
  it('仪器自检：两个函数都真的被解析到了（否则「零静默」是恒真式）', () => {
    const apply = functionBody('applyWorkflowAIDraft');
    const build = functionBody('buildWorkflowFromSpec');
    expect(apply.body.length, 'applyWorkflowAIDraft 函数体为空 —— 解析器坏了').toBeGreaterThan(400);
    expect(build.body.length, 'buildWorkflowFromSpec 函数体为空 —— 解析器坏了').toBeGreaterThan(400);
    // 阳性对照: 这两个函数里确实有 return; 语句可供本闸检查。
    expect(apply.body).toContain('return;');
    expect(build.body).toContain('return;');
  });

  it('applyWorkflowAIDraft: 没有任何无提示的退出路径', () => {
    expect(
      silentReturns('applyWorkflowAIDraft'),
      '这些退出路径不给用户任何提示 —— 用户会读成「AI 没起作用」',
    ).toEqual([]);
  });

  it('buildWorkflowFromSpec: 没有任何无提示的退出路径', () => {
    expect(
      silentReturns('buildWorkflowFromSpec'),
      '这些退出路径不给用户任何提示 —— 用户会读成「AI 没起作用」',
    ).toEqual([]);
  });

  it('每条原因给的是可执行动作，不是笼统的「操作失败」', () => {
    const { body } = functionBody('aiDraftBlockReason');
    // 四类原因各自指向不同的下一步动作。
    expect(body, '没权限时要告诉他找谁').toContain('联系工厂管理员');
    expect(body, '预览历史版本时要告诉他退出预览').toContain('退出预览');
    expect(body, '加载中要告诉他等待并重试').toContain('请等加载完成后再点一次');
    expect(body, '产品不匹配时要说清是哪个产品').toContain('切换到那个产品');

    // ⛔ 阴性对照: 不许退化成一句笼统话。
    expect(body, '「操作失败」不是可执行动作').not.toContain('操作失败');
  });

  it('提示必须 sticky —— 「你点了但什么都没发生」的解释 3 秒消失等于没说', () => {
    const { body } = functionBody('applyWorkflowAIDraft');
    expect(body).toContain('duration: 0');
    expect(body).toContain('showClose: true');
  });
});
