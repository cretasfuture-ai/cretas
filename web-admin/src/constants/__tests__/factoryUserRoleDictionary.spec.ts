import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  DELIBERATELY_NOT_SELECTABLE,
  FACTORY_USER_ROLE_CODES,
  factoryUserRoleOptions,
} from '../factoryUserRoles';
import { ROLE_LABELS } from '@/utils/enumDisplay';

/**
 * 闸: **前端的角色取值域 == `FactoryUserRole.java` 的枚举值集合。**
 *
 * ⛔ 钉的是「两份一致」, 不是「各自都对」—— 两条各自正确的断言同时绿, 并不排除
 * 两份内容不同 (本仓形态 D)。所以这里**直接读 Java 源文件**做集合比较。
 *
 * ## 为什么需要它 (2026-09-01 LIUSHANMEN 实测)
 *
 * `system/users/list.vue` 自带的 24 条 `roleOptions` 比枚举少 6 个, 而它上方
 * 就写着 `Must match FactoryUserRole enum values exactly` —— 注释拦不住漂移,
 * 同一处至少漂过两次 (Apr 20 BR-14 是上一次)。后果不是显示成英文码那么轻:
 * 缺的那 6 个角色在添加/编辑下拉里**选不到**, UI 建不出这些角色的账号。
 *
 * ## 触发面
 *
 * 本 spec 读 `entity/enums/FactoryUserRole.java`, 而 `web-admin-gate.yml` 的
 * `paths:` 已经登记了 `.../aims/entity/**` ⇒ **只改后端枚举的 PR 也会触发本闸**。
 * (元闸 `gateTriggerSurfaceCoversWhatItReads.spec.ts` 会替我们守住这一点,
 *  所以这里不新开 workflow —— ⛔ 新开一道就得自己再维护一份触发面。)
 */

const JAVA_FILE = resolve(
  __dirname,
  '../../../../backend/java/cretas-api/src/main/java/com/cretas/aims/entity/enums/FactoryUserRole.java',
);

/**
 * 取 `FactoryUserRole` 的常量名 + displayName。
 *
 * ⚠️ 与 `enumDisplayCoverage.spec.ts` 的 `topLevelEnumConstants` **不能共用**:
 * 那个的正则是 `^\s*([A-Z][A-Z0-9_]*)`, 只认大写常量, 而本枚举的常量是**小写**
 * (`platform_admin`…)。那正是那份 spec 当初放弃解析、改成手抄 prod 全集
 * (「代码里没有单一枚举可解析」) 的原因 —— 其实解析得了, 是正则不匹配。
 */
function parseJavaEnum(): { codes: string[]; displayNames: Record<string, string> } {
  expect(existsSync(JAVA_FILE), 'FactoryUserRole.java 找不到 —— 后端文件被移动或改名了, 请同步本测试的路径').toBe(true);
  // 🔴 先剥注释再切 —— 否则**注释里的分号**会把常量列表截断。
  //    实测: `yield_operator` 上方的 javadoc 写着「…引用此角色; 无此枚举值则…」,
  //    不剥注释时 split(';')[0] 只拿到前 24 个常量, 而缺的正好是本次要补的那批。
  //    (阳性对照 `> 25` 当场把它抓了出来 —— 没有它, 「前端多出 6 个」会被读成真结论。)
  const source = readFileSync(JAVA_FILE, 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/[^\r\n]*/g, '');
  const start = source.indexOf('enum FactoryUserRole');
  expect(start, 'enum FactoryUserRole 声明找不到').toBeGreaterThan(-1);
  // 常量列表在第一个 `;` 之前, 之后是字段/构造器/方法。
  const head = source.slice(source.indexOf('{', start) + 1).split(';')[0];
  const codes: string[] = [];
  const displayNames: Record<string, string> = {};
  for (const m of head.matchAll(/^\s*([a-z][a-z0-9_]*)\s*\(\s*"([^"]*)"/gm)) {
    codes.push(m[1]);
    displayNames[m[1]] = m[2];
  }
  return { codes, displayNames };
}

const { codes: JAVA_CODES, displayNames: JAVA_DISPLAY_NAMES } = parseJavaEnum();

/**
 * `ROLE_LABELS` 允许比枚举多出来的码 —— **显式登记, 每条写理由**。
 * ⛔ 登记是留痕不是豁免: 加一条之前先确认它真的不是「后端删了角色而前端忘了跟」。
 */
const LABEL_ONLY_CODES: Record<string, string> = {
  // 工作流设计器 / Canvas 预览面板里的历史审批角色码 (workflow-designer/index.vue、
  // canvas-editor/PreviewPanel.vue 都有 <el-option value="factory_admin">)。
  // 它不是 FactoryUserRole 的成员, 所以**不进账号管理下拉** (选了会 400),
  // 但历史审批配置里存着它, 渲染时需要中文 ⇒ 留在 ROLE_LABELS。
  factory_admin: '工作流/审批链里的历史角色码, 非 FactoryUserRole 成员',
};

describe('角色字典: 前端取值域与 FactoryUserRole 枚举必须一致', () => {
  it('阳性对照: Java 枚举真的被解析到了 (解析空会让下面每条都假绿)', () => {
    expect(JAVA_CODES.length, 'FactoryUserRole 一个常量都没解析出来 —— 仪器坏了').toBeGreaterThan(25);
    // 量要紧的那一维: 不是「解析出几条」, 是「解析出来的是不是角色码」。
    expect(JAVA_CODES).toContain('factory_super_admin');
    expect(JAVA_CODES).toContain('yield_operator');
    expect(JAVA_DISPLAY_NAMES.yield_operator, 'displayName 没解析出来').toBe('报工操作员');
    // 常量列表不许把字段/方法名混进来 (`;` 之后的东西全是那些)。
    expect(JAVA_CODES).not.toContain('displayName');
  });

  it('前端取值域 == 枚举值集合 减去显式登记的排除项 (两份一致, 不是各自都对)', () => {
    const missing = JAVA_CODES
      .filter((c) => !(c in DELIBERATELY_NOT_SELECTABLE))
      .filter((c) => !FACTORY_USER_ROLE_CODES.includes(c as never));
    const extra = FACTORY_USER_ROLE_CODES.filter((c) => !JAVA_CODES.includes(c));
    expect(
      missing,
      `后端有而前端下拉没有, 且没登记理由 (这些角色在 UI 里选不到): ${missing.join(', ')}\n` +
        `⇒ 要么补进 FACTORY_USER_ROLE_CODES, 要么登记进 DELIBERATELY_NOT_SELECTABLE 并写清为什么。`,
    ).toEqual([]);
    expect(extra, `前端下拉有而后端枚举没有 (选了会被 Jackson 反序列化拒成 400): ${extra.join(', ')}`).toEqual([]);
  });

  it('排除项本身必须是真枚举值, 且不许和取值域重复 (⛔ 登记不是豁免)', () => {
    const excluded = Object.keys(DELIBERATELY_NOT_SELECTABLE);
    // 阳性对照: 确实登记了东西, 否则上面那条的「减去」是空操作。
    expect(excluded.length, '排除表是空的 —— 那条「减去排除项」等于没减, 本条无意义').toBeGreaterThan(0);
    for (const c of excluded) {
      expect(JAVA_CODES, `${c} 不是 FactoryUserRole 的成员, 登记它没有意义(可能是拼错了)`).toContain(c);
      expect(
        FACTORY_USER_ROLE_CODES.includes(c as never),
        `${c} 既登记成「刻意不可选」又出现在取值域里 —— 两份自相矛盾`,
      ).toBe(false);
      expect(DELIBERATELY_NOT_SELECTABLE[c].length, `${c} 的排除理由是空的`).toBeGreaterThan(10);
    }
  });

  it('被排除的角色仍然有中文, 且编辑弹窗会把当前值补进下拉', () => {
    // 排除只影响「能不能选」, ⛔ 不该影响「能不能显示」——
    // 否则存量餐饮角色账号的角色列会退回英文原码, 那是缺陷 2 的老样子。
    for (const c of Object.keys(DELIBERATELY_NOT_SELECTABLE)) {
      expect(ROLE_LABELS[c], `${c} 被排除后连中文都没了`).toBeTruthy();
    }
    const listVue = readFileSync(resolve(__dirname, '../../views/system/users/list.vue'), 'utf8');
    expect(
      listVue,
      '编辑弹窗必须用 editRoleOptions —— 直接用 roleOptions 会让存量餐饮角色账号的下拉显示空白, ' +
        '用户随手补选就静默改掉角色(缺陷 2 的次生风险)',
    ).toContain('option in editRoleOptions');
  });

  it('每个枚举角色都有中文, 且没有码会退成英文原样显示', () => {
    const noLabel = JAVA_CODES.filter((c) => !ROLE_LABELS[c]);
    expect(noLabel, `角色缺中文 (列表里会显示英文原码): ${noLabel.join(', ')}`).toEqual([]);
    // 下拉 = 枚举值 减去登记的排除项。⛔ 不写成 `<= JAVA_CODES.length` 之类的松判据:
    // 那样漏掉一个角色也过得去, 而「漏一个没人发现」正是缺陷 2 本身。
    const options = factoryUserRoleOptions();
    expect(options).toHaveLength(JAVA_CODES.length - Object.keys(DELIBERATELY_NOT_SELECTABLE).length);
    expect(options.filter((o) => o.label === o.value), '有选项的 label 退成了角色码本身').toEqual([]);
  });

  it('ROLE_LABELS 多出来的码都已显式登记 (⛔ 不许静默多出来)', () => {
    const unregistered = Object.keys(ROLE_LABELS)
      .filter((c) => !JAVA_CODES.includes(c))
      .filter((c) => !(c in LABEL_ONLY_CODES));
    expect(
      unregistered,
      `ROLE_LABELS 里这些码不在 FactoryUserRole 中且没登记理由: ${unregistered.join(', ')}\n` +
        `⇒ 要么它是后端已删的角色(该从 ROLE_LABELS 删), 要么是别处的历史码(该登记进 LABEL_ONLY_CODES)。`,
    ).toEqual([]);
    // 阴性对照: 登记表不许拿来豁免真正的枚举成员。
    for (const c of Object.keys(LABEL_ONLY_CODES)) {
      expect(JAVA_CODES, `${c} 其实是枚举成员, 不该登记成 label-only`).not.toContain(c);
    }
  });

  it('账号管理页不许再自带一份角色表 (曾经就是这么漂掉 6 个的)', () => {
    const listVue = readFileSync(resolve(__dirname, '../../views/system/users/list.vue'), 'utf8');
    expect(listVue).toContain("from '@/constants/factoryUserRoles'");
    // 页面里出现成对的 `value: 'xxx', label: '中文'` 就是又抄了一份。
    expect(
      listVue.match(/value:\s*'[a-z_]+'\s*,\s*label:\s*'/g) ?? [],
      '账号管理页又出现了手抄的角色表',
    ).toEqual([]);
  });
});
