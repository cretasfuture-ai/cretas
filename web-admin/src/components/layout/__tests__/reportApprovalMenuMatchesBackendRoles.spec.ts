/**
 * 报工审批 —— 「Tab 看得见」和「后端放行」必须是同一个判据。
 *
 * 🔴 2026-09-01 生产实测(LIUSHANMEN 真实客户租户):
 *   lsm008 质量经理 GET /process-work-reporting/pending-approval → **403**, 页面「待审批 0」
 *   lsm015 生产经理 同一 URL                                      → **200**, 「待审批 26」
 *   ⇒ 那个 0 不是空列表, 是挡在 26 条真实待办前面的伪造读数。
 *
 * <h3>为什么本页需要角色白名单而 /dashboard/production-progress 不需要</h3>
 * menuConfig 里那段 ⛔「不加 roles 白名单」的前提是
 * **「菜单闸判的模块 == 后端闸判的模块」**。本页不满足:
 *
 *   菜单侧   module: 'production'   → canAccess('production')
 *            quality_manager 的 production = 'r'(DB L1 / 两份前端 fallback / Java fallback
 *            四份一致, 且被 V20261030_11 刻意保留) ⇒ **放行**
 *   后端侧   @RequireRole({...5 个角色})            ⇒ **拒绝**(quality_manager 不在其中)
 *
 * 两侧判的根本不是同一件事: 一个判模块权限, 一个判角色名单, 中间那个差集就是
 * 「看得见点不开」。这正是那段注释自己写的另一半 ——「菜单宽、页面窄」。
 *
 * <h3>🔴 2026-09-02: 判定目标从「菜单 roles[]」搬到「tab 门禁」(coordinator 复核 #487)</h3>
 * 报工审批 + 撤回审批合并进一个 hub 之后，角色白名单**不能**再挂在整个 hub 菜单项上
 * ——「撤回审批」合并前是 module:'production' 无 roles[] 门控 (比 9 角色白名单宽,
 * 例如 dispatcher 有 production:'rw' 但不在白名单里)。把白名单套在整个 hub 上会让
 * dispatcher 连「撤回审批」都看不到 —— 是删功能，不是可登记的副作用。
 *
 * ⇒ 白名单挪进 views/production/approvalQueryTab.ts 的 `APPROVAL_TAB_ROLES`，只门控
 * hub 内部「报工审批」这一个 tab 的渲染 (v-if，见 approval-hub/index.vue)。本闸的判定
 * 对象跟着挪 —— **判据本体没变**(仍从 Java 源码推导期望值)，只是「actual」那一侧从
 * `menuConfig` 的 `item.roles` 换成 `APPROVAL_TAB_ROLES`。运行时行为 (dispatcher 能不能
 * 看到「撤回审批」tab、quality_manager 能不能看到「报工审批」tab) 由
 * approval-hub/__tests__/approvalHub.mount.spec.ts 的真实 mount 测试钉住 ——
 * 本文件只钉「声明的角色集合对不对」，不钉「声明有没有被用起来」(两者是形态 B 的
 * 「产出端有了 ≠ 消费端收得到」，必须分开验)。
 *
 * <h3>这道闸钉的是「两份一致」, 不是「各自都对」</h3>
 * 各自都对是两条独立断言, 它们同时绿也不排除两份内容不同(形态 D′)。
 * 所以下面从 **Java 源码**推导期望值, ⛔ 不在测试里手抄一份角色名单 ——
 * 手抄的那份会跟着漂, 而漂了不会有人发现。
 *
 * ⚠️ 本闸读的是源码文本, 看不见运行时: 工厂停用了 production 模块、
 *    或给某账号下了 L2/L4 DENY override, 这道闸都照绿。那两件事只能实测。
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { menuConfig } from '@/components/layout/menuConfig';
import { APPROVAL_TAB_ROLES } from '@/views/production/approvalQueryTab';

const APPROVAL_PATH = '/production/approval';

function repoRoot(): string {
  let dir = resolve(process.cwd());
  for (let i = 0; i < 6; i += 1) {
    try {
      readFileSync(join(dir, 'backend/java/cretas-api/pom.xml'), 'utf8');
      return dir;
    } catch { /* keep walking up */ }
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  throw new Error('找不到 backend/java/cretas-api —— 本闸不静默跳过');
}

function readJava(rel: string): string {
  return readFileSync(
    join(repoRoot(), 'backend/java/cretas-api/src/main/java/com/cretas/aims', rel),
    'utf8',
  );
}

function findMenuItem(nodes: any[], path: string): any | null {
  for (const node of nodes ?? []) {
    if (node?.path === path) return node;
    const hit = node?.children ? findMenuItem(node.children, path) : null;
    if (hit) return hit;
  }
  return null;
}

/** getPendingApprovals 那个方法自己的 @RequireRole —— ⛔ 不能抓成同文件里别的方法的。 */
function pendingApprovalRequireRole(): string[] {
  const src = readJava('controller/ProcessWorkReportingController.java');
  const start = src.indexOf('@GetMapping("/pending-approval")');
  expect(start, '没找到 @GetMapping("/pending-approval") —— 闸自己坏了, 不是被测对象变了')
    .toBeGreaterThan(-1);
  // 只在该 mapping 到方法签名之间取, 避免越界抓到下一个方法(approve)的注解。
  const end = src.indexOf('public ApiResponse', start);
  expect(end, '没找到该端点的方法签名').toBeGreaterThan(start);

  const block = src.slice(start, end);
  const ann = block.match(/@RequireRole\(\{([^}]*)\}\)/);
  expect(ann, 'pending-approval 上读不到 @RequireRole —— 后端换了闸的形态, 本闸必须跟着改')
    .toBeTruthy();
  return [...ann![1].matchAll(/"([a-z_]+)"/g)].map((m) => m[1]);
}

/** 无条件绕过 @RequireRole 的平台角色 —— 漏掉它们会把打得开的页藏起来。 */
function platformBypassRoles(): string[] {
  const src = readJava('config/RequireRoleInterceptor.java');
  const block = src.match(/PLATFORM_ADMIN_ROLES\s*=\s*Set\.of\(([\s\S]*?)\);/);
  expect(block, '读不到 PLATFORM_ADMIN_ROLES —— 绕过名单变了形态').toBeTruthy();
  return [...block![1].matchAll(/"([a-z_]+)"/g)].map((m) => m[1]);
}

describe('报工审批 —— tab 门禁(APPROVAL_TAB_ROLES) 必须等于后端实际放行的角色全集', () => {
  it('菜单项存在(阳性对照 —— 它不在的话下面每一条都无意义)', () => {
    expect(findMenuItem(menuConfig as any[], APPROVAL_PATH)).not.toBeNull();
  });

  it('两个来源都真的解析出了角色(阳性对照 —— 空集会让下面的相等断言恒真)', () => {
    expect(pendingApprovalRequireRole().length).toBeGreaterThan(0);
    expect(platformBypassRoles().length).toBeGreaterThan(0);
  });

  it('APPROVAL_TAB_ROLES == @RequireRole ∪ PLATFORM_ADMIN_ROLES', () => {
    const expected = [...new Set([...pendingApprovalRequireRole(), ...platformBypassRoles()])].sort();
    const actual = [...APPROVAL_TAB_ROLES].sort();

    expect(
      actual,
      '「报工审批」tab 放行的角色与后端放行的角色不是同一批 ⇒ 差集里的角色要么'
        + '「看得见点不开」(tab 多), 要么「打得开却找不到入口」(tab 少)。'
        + `\n  后端: ${expected.join(', ')}\n  tab: ${actual.join(', ')}`,
    ).toEqual(expected);
  });

  it('🔴 阴性对照: quality_manager 不在名单里 —— 它正是 2026-09-01 那条缺陷的当事角色', () => {
    expect(
      APPROVAL_TAB_ROLES,
      'quality_manager 回到名单里, 就等于把「待审批 0 挡着 26 条真实待办」那条缺陷放回来了。'
        + '若确实要让质量经理审报工, 那要改的是后端 @RequireRole, 不是偷偷加回 tab 门禁。',
    ).not.toContain('quality_manager');
  });

  it('🔴 阴性对照: workshop_supervisor 在名单里 —— 加白名单不许误伤受众', () => {
    expect(
      APPROVAL_TAB_ROLES,
      'approvalQueryTab.ts 头部注释担心的正是这个: 叠角色门禁会把车间主管挡在门外。'
        + '后端放行了他, tab 门禁就必须也放行。',
    ).toContain('workshop_supervisor');
  });

  it('🔴 阴性对照: menuConfig 的 hub 菜单项不再自带 roles[] —— 白名单只能有一处来源', () => {
    const item = findMenuItem(menuConfig as any[], APPROVAL_PATH)!;
    expect(
      item.roles,
      '若这里又长出 roles[]，hub 菜单项的可见性会重新收窄成「报工审批」那 9 个角色，'
        + 'dispatcher 等能看「撤回审批」但不在白名单里的角色会连菜单项都看不到 ——'
        + '正是 2026-09-02 coordinator 复核指出的那次回归。白名单唯一来源是'
        + ' APPROVAL_TAB_ROLES，只应该门控 hub 内部的 tab 渲染。',
    ).toBeUndefined();
  });
});
