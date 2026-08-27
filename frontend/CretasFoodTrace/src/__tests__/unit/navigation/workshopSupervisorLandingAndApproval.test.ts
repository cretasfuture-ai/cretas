/**
 * 车间主管：落地屏 + 审批可达 + 首页读数三态
 *
 * 三件事各自守一条，且各带阴性对照：
 *   1. 打开 App 落在 WSHome，不是工人那份工序任务列表
 *   2. 审批屏在**主管自己的**树上注册（此前只挂在工厂管理员树，主管点了没反应）
 *   3. WSHome 的人员/设备读数是三态，读不到印 `—` 而不是 `0`
 *
 * ⚠️ 仪器纪律：本文件所有断言都跑在**剥掉注释**的源码上。
 *    这道闸的被测文件里就写着 `initialRouteName="ProcessTaskList"`(在说明为什么换掉它)，
 *    不剥注释的话第 1 条会被自己的说明文字打红 —— 本仓 2026-08-20 记过
 *    「闸把自己的注释当成被测对象」的四连事故。
 */
import fs from 'fs';
import path from 'path';

const SRC = path.resolve(__dirname, '../../..');
const HOME_STACK = path.join(SRC, 'navigation/workshop-supervisor/WSHomeStackNavigator.tsx');
const WS_HOME = path.join(SRC, 'screens/workshop-supervisor/home/WSHomeScreen.tsx');
const NAV_TYPES = path.join(SRC, 'types/navigation.ts');

/** 剥块注释与行注释；字符串里的 `//` 不管，本闸的断言都不依赖那种形状。 */
function stripComments(code: string): string {
  return code.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
}

function readCode(p: string): string {
  return stripComments(fs.readFileSync(p, 'utf8'));
}

// ============================================================
// 0. 仪器自检 —— 剥注释这件事必须真的在发生
// ============================================================
describe('gate instrument self-check', () => {
  it('strips comments, so the gate cannot match its own explanatory prose', () => {
    const count = (s: string) => (s.match(/ProcessTaskList/g) ?? []).length;
    const rawCount = count(fs.readFileSync(HOME_STACK, 'utf8'));
    const strippedCount = count(readCode(HOME_STACK));
    // 那段说明为什么换掉落地屏的注释里提到了 ProcessTaskList。
    // 剥注释必须把它拿掉 —— 否则下面的断言分不清「代码里有」和「注释里提到」。
    expect(rawCount).toBeGreaterThan(strippedCount);
    // 阴性对照: 剥完不能一个不剩 —— 它作为真实路由仍然注册着
    expect(strippedCount).toBeGreaterThan(0);
  });

  it('the stripper does not eat real code', () => {
    const code = readCode(HOME_STACK);
    expect(code).toContain('Stack.Navigator');
    expect(code).toContain('WSHomeStackNavigator');
  });
});

// ============================================================
// 1. 落地屏
// ============================================================
describe('the supervisor lands on the dashboard, not the worker task list', () => {
  const code = readCode(HOME_STACK);

  it('initialRouteName is WSHome', () => {
    expect(code).toMatch(/initialRouteName="WSHome"/);
  });

  it('WSHome is actually mounted — a landing route that is not registered would crash', () => {
    expect(code).toMatch(/name="WSHome"\s+component=\{WSHomeScreen\}/);
  });

  it('ProcessTaskList is still reachable from the same stack', () => {
    // 阴性对照: 换落地屏不能把工序任务弄丢 —— 主管仍要能一步进去
    expect(code).toMatch(/name="ProcessTaskList"/);
  });
});

// ============================================================
// 2. 审批可达
// ============================================================
describe('approval is reachable from the supervisor tree', () => {
  const code = readCode(HOME_STACK);

  it('ProcessTaskApproval is mounted in the supervisor stack', () => {
    expect(code).toMatch(/name="ProcessTaskApproval"\s+component=\{ProcessTaskApprovalScreen\}/);
  });

  it('the screen is imported, not just named', () => {
    // 阴性对照: 光写 name= 不算 —— 原来的坏状态之一正是「有路由名、组件在别的树」
    expect(code).toMatch(/import\s+ProcessTaskApprovalScreen\s+from/);
  });

  it('the route exists in WSHomeStackParamList, so navigate() type-checks', () => {
    const types = readCode(NAV_TYPES);
    // ⛔ 不能用 indexOf('};') 找结尾 —— ParamList 里的内联对象类型
    //    (`BatchDetail: { batchId: string; readonly?: boolean };`) 自己就以 `};` 结束,
    //    会把块在第二行就截断。要的是**行首**那个 `};`。
    const m = types.match(/WSHomeStackParamList = \{([\s\S]*?)\n\};/);
    expect(m).not.toBeNull();
    const block = m![1];
    // ⛔ 不能用 toContain —— 它是子串匹配, 把名字改成 `ProcessTaskApprovalZZ`
    //    照样含有 `ProcessTaskApproval`, 于是这条断言在被改坏的代码上仍然绿(实测)。
    //    要钉的是**属性名整体**: 行首 + 冒号收尾。
    expect(block).toMatch(/^\s*ProcessTaskApproval:/m);
    // 仪器自检: 切出来的块得是整个 ParamList —— 尾部那几个路由也要在里面,
    //          否则「找到了 ProcessTaskApproval」可能只是块恰好切在它后面
    expect(block).toMatch(/^\s*ProcessTaskList:/m);
    expect(block).toMatch(/^\s*ProcessOperation:/m);
  });
});

// ============================================================
// 3. 首页读数三态
// ============================================================
describe('WSHome tells apart "did not ask" from "the answer is 0"', () => {
  const code = readCode(WS_HOME);

  it('personnel and equipment readings are nullable', () => {
    expect(code).toMatch(/type Reading = number \| null/);
    expect(code).toMatch(/onLeave:\s*Reading/);
    expect(code).toMatch(/needMaintenance:\s*Reading/);
  });

  it('the two readings the backend never provides are null, not 0', () => {
    // 这两个字段后端至今没有任何来源。以前写死成 0, 而 0 恰好是「好消息」的长相。
    expect(code).toMatch(/onLeave:\s*null/);
    expect(code).toMatch(/needMaintenance:\s*null/);
    // 阴性对照: 旧的写死 0 不许回来
    expect(code).not.toMatch(/onLeave:\s*0\s*,/);
    expect(code).not.toMatch(/needMaintenance:\s*0\s*,/);
  });

  it('a failed load clears the readings instead of leaving stale zeros on screen', () => {
    // 原来的 catch 只 setError, 四个 0 原样留在屏上 —— 与「刚读到 0」无法区分
    expect(code).toMatch(/setPersonnelStatus\(\{\s*onDuty:\s*null/);
    expect(code).toMatch(/setEquipmentStatus\(\{\s*running:\s*null/);
  });

  it('unknown readings render as a dash, and do not borrow the alert colour', () => {
    expect(code).toMatch(/function StatusValue/);
    expect(code).toMatch(/unknown \? '—' : value/);
    // 阴性对照: 没读到就不能沿用告警配色 —— 那正是它冒充真读数的方式
    expect(code).toMatch(/unknown \? '#bfbfbf' : color/);
  });

  it('every status cell goes through StatusValue — none renders the raw number', () => {
    // ⛔ 要匹配的是**渲染成子节点**的位置 `>{x}<`，不是任意位置的 `{x}` ——
    //    后者会把 `<StatusValue value={personnelStatus.onDuty} …/>` 里的 prop 表达式
    //    也数成「直接渲染」，于是这条断言在已经改对的代码上照样红(实测踩过)。
    // ⛔ 逐条收集再一次性断言, 不在第一处失败就停 —— 否则只看得到最靠前的那一处漏网
    const raw = [...code.matchAll(/>\s*\{(?:personnelStatus|equipmentStatus)\.(\w+)\}\s*</g)].map((m) => m[1]);
    expect(raw).toEqual([]);
    // 仪器自检: 六个格子确实都换成了 StatusValue, 否则上面那条可能只是「一个格子都没有」
    const cells = [...code.matchAll(/<StatusValue\s+value=\{/g)];
    expect(cells.length).toBe(6);
  });

  it('reads use ?? not || — a real 0 from the backend must not fall through', () => {
    // `summary?.x || fallback` 会把后端返回的真实 0 也当成缺失而滑到下一个候选
    expect(code).not.toMatch(/onDutyWorkers\s*\|\|/);
    expect(code).not.toMatch(/activeEquipment\s*\|\|/);
    expect(code).toMatch(/onDutyWorkers\s*\?\?/);
  });

  it('a difference is only computed when both operands were read', () => {
    expect(code).toMatch(/function diffOrUnknown/);
    expect(code).toMatch(/absent:\s*diffOrUnknown\(/);
    expect(code).toMatch(/idle:\s*diffOrUnknown\(/);
  });
});
