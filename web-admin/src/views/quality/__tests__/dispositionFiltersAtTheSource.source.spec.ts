import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 「不良品处置」屏的待处置列表：**一次请求 + 由数据源过滤**，
 * 且它传的 `result` 参数在后端 controller 里**真的被声明**了。
 *
 * <h2>🔴 2026-08-28 逐屏走查抓到 —— 证据就在屏上</h2>
 *
 * <p>那个屏叫「待处置质检」，本该只有「不合格」和「有条件放行」。实拍：</p>
 *
 * <pre>
 *   检测结果列: PASS / 有条件放行 / 不合格 / PENDING
 *   而且**每条记录连备注一字不差地出现了两遍**
 * </pre>
 *
 * <p>交叉证据：「质检记录」屏说共 <b>13</b> 条、那 6 条各出现一次；
 * 这个屏渲染出约 <b>24</b> 行。</p>
 *
 * <h2>真因</h2>
 *
 * <p>前端原来发<b>两次</b>请求再拼接：</p>
 *
 * <pre>
 *   get(..., { params: { result: 'FAIL' } })
 *   get(..., { params: { result: 'CONDITIONAL' } })
 *   inspectionRows = [...failList, ...condList]
 * </pre>
 *
 * <p>而后端 <code>ProcessingController.getInspections</code> 的签名里
 * <b>根本没有 <code>result</code> 这个 @RequestParam</b>
 * （只有 factoryId / batchId / page / size）。</p>
 *
 * <p>▎<b>Spring 对未声明的请求参数静默忽略</b> —— 不报错、不警告，只是安静地不过滤。</p>
 *
 * <p>⇒ 两次调用完全相同，各拿一份<b>全量</b>，拼接后每条两遍，PASS 的行也混进来。
 * 质检主管看到的「待处置」数量因此严重偏大。</p>
 *
 * <h2>⚠️ 这道闸刻意只盯这一个端点</h2>
 *
 * <p>我试着用「前端传的 param ∖ 后端声明的 param」全量对账，量出 35 处 ——
 * <b>那个数不可用</b>：匹配器按<b>路径后缀</b>找端点，而 <code>/compare</code>、
 * <code>/export</code>、<code>/summary</code> 在不同 controller 里各有一个，全撞在一起；
 * 同时它<b>没抓到</b>这个已确证的案例（前端那处调用跨行）。
 * ⇒ 既过宽又漏，不写进判据。<b>只钉这一个读过后端签名确证过的。</b></p>
 *
 * <p>要把这一类做成可信的闸，得先有一份「前端 URL → 后端端点」的权威映射，
 * 那是另一轮的事。⛔ 不拿一个不可用的数假装覆盖了这一类。</p>
 */

const SCREEN = resolve(__dirname, '../disposition/list.vue');
const CONTROLLER = resolve(
  __dirname,
  '../../../../../backend/java/cretas-api/src/main/java/com/cretas/aims/controller/ProcessingController.java',
);

describe('不良品处置: 在数据源过滤, 且那个参数后端真的声明了', () => {
  it('阳性对照: 两个文件都读得到, 且确实是那个屏 / 那个端点', () => {
    const screen = readFileSync(SCREEN, 'utf8');
    const ctrl = readFileSync(CONTROLLER, 'utf8');
    expect(screen.length, '屏源码读成空 ⇒ 下面全是恒真式').toBeGreaterThan(2000);
    expect(screen, '应是「待处置质检」那个屏').toContain('待处置质检');
    expect(ctrl.length, 'controller 读成空').toBeGreaterThan(2000);
    expect(ctrl, 'controller 里应有这个端点 —— 找不到说明代码搬家了, 本闸路径要跟着改')
      .toContain('@GetMapping("/quality/inspections")');
  });

  it('⛔ 只发一次请求, 不再拼两份列表', () => {
    const screen = readFileSync(SCREEN, 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .split('\n').map(l => l.replace(/\/\/.*$/, '')).join('\n');
    const calls = (screen.match(/quality\/inspections/g) || []).length;
    expect(
      calls,
      '两次调用 + 拼接正是把每条渲染两遍的那个动作 —— 后端已支持逗号分隔的 result。',
    ).toBe(1);
    expect(screen, '⛔ 不许再出现 [...failList, ...condList] 这种拼接')
      .not.toMatch(/\[\s*\.\.\.\w*[Ff]ail\w*\s*,\s*\.\.\.\w*[Cc]ond\w*\s*\]/);
  });

  it('🔴 前端传的 result, 后端必须真的声明 —— Spring 静默忽略未声明的参数', () => {
    const screen = readFileSync(SCREEN, 'utf8');
    const ctrl = readFileSync(CONTROLLER, 'utf8');

    // 前端确实在传 result
    expect(screen, '这个屏应按 FAIL,CONDITIONAL 过滤').toMatch(/result:\s*'FAIL,CONDITIONAL'/);

    // 后端 getInspections 的签名里必须有 result
    const sig = ctrl.match(/@GetMapping\("\/quality\/inspections"\)[\s\S]*?\)\s*\{/)?.[0] ?? '';
    expect(sig, '取不到端点签名 ⇒ 下面那条断言不可判定').not.toBe('');

    // ⚠️ 判据必须钉在**这个参数自己**身上，而且要用**结构**判，不要用正则猜。
    //
    //   第一版: /@RequestParam\([^)]*\)[\s\S]{0,200}?String result/
    //     → 只问「前面某处有没有 @RequestParam」。签名里还有 batchId / page / size 三个，
    //       所以把 result 的注解删掉之后**正则照样匹配** —— 变异生效了，闸却不红。
    //
    //   第二版: 加上「中间只许一个可选的 @Parameter」
    //     → 在**未变异**的文件上就不匹配了：`@Parameter(description = "… (PASS / FAIL /
    //       CONDITIONAL / PENDING)", …)` 的**描述文本里含 `)`**，`[^)]*` 提前停下。
    //
    // ⇒ 第三版按**参数分隔符**切块，找含 `String result` 的那一块，断言它自己带注解。
    //   本仓的「闸用 AST 不用字符串计数」在纯文本上的对应做法。
    const paramBlocks = sig
      .slice(sig.indexOf('(') + 1)
      .split(/,\s*\n/)                      // 参数之间的分隔：逗号 + 换行
      .map((b: string) => b.trim());
    const resultBlock = paramBlocks.find((b: string) => /\bString\s+result\b/.test(b));
    expect(
      resultBlock,
      '端点签名里找不到 `String result` 这个参数 —— 前端在传它, 后端却没有。',
    ).toBeDefined();
    expect(
      resultBlock,
      '前端在传 result, 而 controller 的这个参数**没有 @RequestParam 注解**。\n' +
        'Spring 对未声明的请求参数**静默忽略** —— 不报错、不警告, 只是安静地不过滤,\n' +
        '于是「待处置质检」会显示**全部**质检记录(含 PASS), 这正是 2026-08-28 实拍到的。',
    ).toContain('@RequestParam');
  });
});
