import * as fs from 'fs';
import * as path from 'path';

/**
 * 闸：扫码入库时，已经收满/已关单的采购单要在**进屏那一刻**就拦住，
 * ⛔ 不许放行到录入表单再让仓管填完数量点提交才被后端 409 拒。
 *
 * 2026-08-20 六扇门真机实测（PO-20260820-0007，订单 2000kg 分两次各收 1000 收满）：
 * 仓管再扫一次同一张 PDF 二维码，`WHScanOperationScreen.resolveScan` 只按 orderNumber
 * 查到订单就直接跳 `WHReceiptCreate`，本页不检查订单状态/行级余量，照常渲染整张录入
 * 表单——踩的正是 fool-proof-design.md Rule 1（预先显示边界，不要事后报错）的反面例子。
 *
 * 本闸守三件事：
 *   ① 进屏后、渲染录入表单**之前**，存在一道判断「这单还能不能收」的早退（早于主 return）
 *   ② 那道判断同时看 order.status 终态 和 行级 remaining（防 status 字段滞后的兜底）
 *   ③ 单行收满时数量输入框会被禁用（不是留一个空白框等人填完再拒）
 *
 * ⚠️ 剥注释后再断言（先剥块注释、再剥行注释）—— 否则说明文字里提到的标识符会被误数进去，
 *    本仓当天已经在别的闸上栽过「量到自己的注释」这一种。
 */
describe('闸: 扫码入库进屏时先判"这单还能不能收"', () => {
  const SRC = path.join(__dirname, '..', '..', '..');
  const SCREEN = 'screens/warehouse/inbound/WHReceiptCreateScreen.tsx';
  const FILE_PATH = path.join(SRC, SCREEN);

  function readStripped(p: string): string {
    return fs
      .readFileSync(p, 'utf-8')
      .replace(/\/\*[\s\S]*?\*\//g, ' ')
      .replace(/(^|[^:])\/\/[^\n]*/g, '$1 ');
  }

  const code = readStripped(FILE_PATH);

  it('仪器自检: 判据涉及的标识符都还在源码里(不是被我删掉了才「没检测到」)', () => {
    expect(code).toContain('orderNotReceivable');
    expect(code).toContain('allLinesReceived');
    expect(code).toContain('rowFullyReceived');
    expect(code).toContain('WHReceiptCreate');
  });

  it('🔴 orderNotReceivable 的早退在主表单 return 之前(结构性: 用字符串位置判先后)', () => {
    // 主表单的 return 里带着 KeyboardAvoidingView(收货明细表单唯一用到它的地方);
    // orderNotReceivable 早退块必须出现在它前面, 否则表单已经渲染完才判断没有意义。
    const iGate = code.indexOf('if (orderNotReceivable)');
    const iMainReturn = code.indexOf('<KeyboardAvoidingView');
    expect(iGate).toBeGreaterThan(-1);
    expect(iMainReturn).toBeGreaterThan(-1);
    expect(iGate).toBeLessThan(iMainReturn);
  });

  it('判据同时看 order.status 终态和行级 remaining, 不是只信可能滞后的 status', () => {
    // 阳性对照: 三个终态字面量都出现过(哪怕只在类型声明里也至少出现一次)
    expect(code).toContain("'COMPLETED'");
    expect(code).toContain("'CLOSED'");
    expect(code).toContain("'CANCELLED'");
    // allLinesReceived 是从 order.items 逐行算的, 不是读某个直接给的聚合字段
    const idx = code.indexOf('const allLinesReceived');
    expect(idx).toBeGreaterThan(-1);
    const body = code.slice(idx, idx + 400);
    expect(body).toContain('order?.items');
    expect(body).toContain('it.quantity');
    expect(body).toContain('it.receivedQuantity');
  });

  it('单行收满时数量输入框被禁用, 不是留白让人手填', () => {
    const idx = code.indexOf('rowFullyReceived');
    expect(idx).toBeGreaterThan(-1);
    // rowFullyReceived 为真时走「本行已收满」提示分支, 数量/日期输入被整体换掉——
    // 用三元 ? ... : 结构断言, 不用具体渲染树(本仓惯例只读源码做契约, 见 receiptPhotoBeforeConfirmContract)。
    expect(code).toMatch(/rowFullyReceived\s*\?[\s\S]{0,200}本行已收满/);
  });

  it('不许用 Alert.prompt 收尾(iOS 独有, 仓管是安卓) —— 本次改动没有引入新的收尾输入', () => {
    expect(code).not.toContain('Alert.prompt');
  });
});

// ⚠️ 阴性对照（改动前的源码跑同一批断言必须红）不放进本文件的常驻用例：
// 一旦这次改动被提交，`git show HEAD:<file>` 里的 HEAD 会变成"改动后"本身，
// 对照会自己失效变成恒真式（跟本仓 measurement-and-wiring 记过的坑同形）。
// 该对照已在开发时一次性手工做过（对着上一个 commit 199ece76d8 的版本跑同一批
// 断言，全部失败——见 PR/commit message 里贴的输出），不适合做成会漂移的自动化用例。
