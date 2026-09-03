import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 步骤列表 → 画布的逃生门（客户 2026-09-03:「审批流的配置使用，拉框拉节点」）。
 *
 * <h2>在这之前它是一道单向门</h2>
 *
 * <pre>
 *   useStepList = lockDecisionType && stepFlow !== null      ← 只看图能不能线性表达
 *   StepType    = 'approval' | 'cc'                          ← 步骤列表只能加这两种
 * </pre>
 *
 * 于是一条线性审批流被永久钉在步骤列表里：想加条件分支 / 并行审批 / 汇签，
 * 界面上没有任何入口 —— 能拉节点的画布 `showInMenu: false`，只能手敲 URL。
 *
 * <h2>这道闸真正守的东西</h2>
 *
 * 🔴 **切过去时画布必须由 `stepsToGraph` 从当前步骤重建。**
 * 直接翻 `forceGraphMode` 标志位是能"跑通"的 —— 画布上有货（载入时反序列化的那份），
 * 看起来完全正常。但步骤模式的保存**刻意绕开画布**（serializeStepFlow），
 * 用户在步骤列表里刚加的那一级审批从来没写进 nodes/edges ⇒ 一切过去就消失，
 * 而且不报错。这正是 2026-09-01 事故的成因形状（静默丢配置）。
 *
 * ⇒ 用与保存同一条路径重建 ⇒ 画布上看到的就是会被保存的那个图。
 */

const SRC = resolve(__dirname, '../..');
const editor = readFileSync(resolve(SRC, 'index.vue'), 'utf-8');

/** 截取某个函数体（到下一个顶层 `\n}` 为止），用于做顺序断言而不是全文 includes。 */
function functionBody(signature: string): string {
  const start = editor.indexOf(signature);
  expect(start, `找不到函数: ${signature}`).toBeGreaterThan(-1);
  const end = editor.indexOf('\n}', start);
  expect(end, `函数 ${signature} 没有闭合`).toBeGreaterThan(start);
  return editor.slice(start, end);
}

describe('步骤列表 → 画布的逃生门', () => {
  it('useStepList 必须同时受「用户主动切画布」这个开关控制', () => {
    const body = editor.slice(editor.indexOf('const useStepList = computed('));
    expect(body.slice(0, 260)).toContain('!forceGraphMode.value');
  });

  it('🔴 切到画布必须用 stepsToGraph 重建, ⛔ 不能只翻标志位', () => {
    const body = functionBody('async function switchToGraphMode(');

    const rebuild = body.indexOf('stepsToGraph({ steps })');
    const apply = body.indexOf('applyGraphToCanvas(');
    const flip = body.indexOf('forceGraphMode.value = true');

    expect(rebuild, '必须由 stepsToGraph 从当前步骤重建').toBeGreaterThan(-1);
    expect(apply, '必须把重建结果铺到画布上').toBeGreaterThan(-1);
    expect(flip, '必须切换模式').toBeGreaterThan(-1);

    // 顺序就是这条闸的意义: 先重建、再铺、最后才切模式。
    // 反过来(先翻标志位)用户会先看到一瞬间的旧图。
    expect(rebuild).toBeLessThan(apply);
    expect(apply).toBeLessThan(flip);
  });

  it('阴性对照: 切换时不许直接读画布现有的 nodes.value', () => {
    // 画布上那份是**载入时**的图, 不含用户在步骤列表里做的改动。
    // 读它 = 静默丢配置, 而且不报错。
    const body = functionBody('async function switchToGraphMode(');
    expect(body).not.toContain('nodes.value');
  });

  it('切不回步骤列表时必须说明原因, ⛔ 不硬编码一句', () => {
    const body = functionBody('function switchBackToStepList(');
    expect(body).toContain('graphToSteps(');
    // 原因由 explainStepListFallback 算 —— 与 deserializeGraph 那条同一条纪律:
    // 错的原因会让人去找一个不存在的分支。
    expect(body).toContain('explainStepListFallback(');
  });

  it('换业务类型 / 新建草稿时, 模式选择必须跟着复位', () => {
    // 少这一步: 切到另一条本该用步骤列表的简单流程, 用户会莫名落在画布里。
    expect(functionBody('function resetEditor(')).toContain('forceGraphMode.value = false');
    expect(editor).toContain('forceGraphMode.value = false   // 新建的草稿一律从步骤列表起步');
  });

  /**
   * 🔴 这条是本次改动**自己踩出来的**回归, 不是假想。
   *
   * `.editor-body` 是 `display:flex` 的**横向**容器(palette | canvas | properties)。
   * 「回到步骤列表」那条 bar 第一版写成普通子元素 ⇒ 它成了第四个 flex 兄弟,
   * **横着占掉 434px**: 实测画布 758px → **324px**(1600px 视口下 20%)。
   * 一个本意是「让画布更好用」的改动, 反而把画布压小了一半多。
   *
   * 旁边的 .step-fallback-alert 早就用 `position: absolute` 解决过同一个问题 ——
   * 我没看它就先写了一版(形态 D: 同一个坑, 两份各自踩)。
   *
   * ⇒ 任何加进 .editor-body 的横幅都必须脱离文档流。
   */
  it('🔴 加进 .editor-body 的横幅必须绝对定位, 否则会吃掉画布宽度', () => {
    const rule = editor.slice(editor.indexOf('.graph-mode-back {'));
    const block = rule.slice(0, rule.indexOf('}'));
    expect(block, '.graph-mode-back 必须 position:absolute').toContain('position: absolute');

    // 阳性对照: 既有那条横幅确实也是这么做的 —— 证明这不是我临时发明的写法。
    const alertRule = editor.slice(editor.indexOf('.step-fallback-alert {'));
    expect(alertRule.slice(0, alertRule.indexOf('}'))).toContain('position: absolute');
  });

  it('两个方向都要有真实入口 —— 光有函数没按钮等于没做', () => {
    expect(editor).toContain('data-testid="switch-to-graph"');
    expect(editor).toContain('data-testid="switch-to-step-list"');
    // 回去的按钮只在「用户自己切过来」时出现: 图本来就表达不成线性时,
    // 那个按钮点了只会弹一句"回不去", 属于纯噪音。
    expect(editor).toContain('v-if="inForcedGraphMode && canEditDraft"');
  });
});
