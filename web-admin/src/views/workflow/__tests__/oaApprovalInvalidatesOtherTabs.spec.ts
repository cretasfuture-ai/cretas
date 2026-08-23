import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { OA_ACTION_INVALIDATES, oaInvalidatedModules } from '@/utils/workspaceInvalidation';

/**
 * 端到端接线闸: OA 审批通过 → 财务/仓储/采购标签失效。
 *
 * 🔴 2026-08-20 owner 实测:「我在个人 OA 里点击通过以后, 再点到财务管理里面, 还是待审批;
 * 到仓储管理的入库任务与批次, 点进去没有任何东西。但我刷新了一下网页, 它就更新好了。」
 *
 * ⚠️ 三条各守一段, 缺一条这条链就在某处断掉而没人会红:
 *   ① 表本身对不对        —— 直接 import 断言 (⛔ 不用源码正则去猜 SFC 里的常量)
 *   ② 表与「点得动的业务码」对齐 —— 少一个键 = 那类单据批完照旧显示旧数据
 *   ③ pending.vue 真的调了它  —— 「机制在, 没接上」
 */
const here = dirname(fileURLToPath(import.meta.url));
const pendingRaw = readFileSync(resolve(here, '../pending.vue'), 'utf8');

/** 剥注释: 这个文件的注释里原样写着 `invalidateModules`, 不剥就是在量我自己的说明。 */
const pending = pendingRaw
  .replace(/<!--[\s\S]*?-->/g, '')
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/^\s*\/\/.*$/gm, '');

/** 前提断言: 抓不到 act() 函数体就抛 —— 否则「在 act 里」这句话无从谈起。 */
function actBody(): string {
  const matched = pending.match(/async function act\([\s\S]*?\n\}/);
  expect(matched, 'pending.vue 里找不到 async function act(...) —— 闸自己瞎了').not.toBeNull();
  return matched![0];
}

/** 前提断言: 抓不到 ACTIONABLE_MODULE_CODES 的字面量就抛。 */
function actionableCodes(): string[] {
  const matched = pending.match(/ACTIONABLE_MODULE_CODES = new Set\(\[([\s\S]*?)\]\)/);
  expect(matched, 'pending.vue 里找不到 ACTIONABLE_MODULE_CODES —— 闸自己瞎了').not.toBeNull();
  const codes = matched![1].match(/'([A-Z_]+)'/g)?.map((item) => item.replace(/'/g, '')) ?? [];
  expect(codes.length, '解析出 0 个业务码 —— 正则跟源码漂了').toBeGreaterThan(0);
  return codes;
}

describe('① OA 动作 → 业务域 的声明表', () => {
  it('采购单批完会失效财务与仓储 —— owner 实报的正是这两个', () => {
    expect(oaInvalidatedModules('PURCHASE_ORDER')).toContain('finance');
    expect(oaInvalidatedModules('PURCHASE_ORDER')).toContain('warehouse');
  });

  it('每一条都带 dashboard —— 个人 OA 自己 (/workflow/**) 的 meta.module 就是它', () => {
    Object.entries(OA_ACTION_INVALIDATES).forEach(([code, modules]) => {
      expect(modules, `${code} 漏了 dashboard`).toContain('dashboard');
    });
  });

  it('没登记的业务码兜底成 dashboard, ⛔ 不是空数组 (空 = 什么都不失效)', () => {
    expect(oaInvalidatedModules('SOMETHING_NEW')).toEqual(['dashboard']);
  });

  it('阴性对照: 声明的值必须是 router 里真实存在的 meta.module 取值', () => {
    // 发明一个 router 里没有的域名, 表看起来是对的而它匹配不到任何标签 —— 静默失效。
    const routerSource = readFileSync(resolve(here, '../../../router/index.ts'), 'utf8');
    const known = new Set(
      (routerSource.match(/module: *'[a-zA-Z0-9_-]+'/g) ?? [])
        .map((item) => item.replace(/module: *'|'/g, '')),
    );
    expect(known.size, '从 router 里一个 meta.module 都没解析出来 —— 闸自己瞎了').toBeGreaterThan(5);
    Object.values(OA_ACTION_INVALIDATES).flat().forEach((module) => {
      expect(known.has(module), `「${module}」在 router 的 meta.module 里不存在`).toBe(true);
    });
  });
});

describe('② 声明表与「点得动的业务码」不许漂 (形态 D)', () => {
  it('pending.vue 里每个可操作业务码都在 OA_ACTION_INVALIDATES 里有条目', () => {
    actionableCodes().forEach((code) => {
      expect(
        Object.prototype.hasOwnProperty.call(OA_ACTION_INVALIDATES, code),
        `「${code}」点得动通过/驳回, 却没登记它会让哪些标签过时`,
      ).toBe(true);
    });
  });
});

describe('③ pending.vue 真的把审批结果接到了失效机制上', () => {
  it('阳性对照: 读到的确实是 pending.vue', () => {
    expect(pending).toContain('workflow/instances/');
    expect(pending).toContain('ACTIONABLE_MODULE_CODES');
  });

  it('act() 里调了 workspace.invalidateModules', () => {
    expect(actBody()).toContain('workspace.invalidateModules');
  });

  it('传的是 oaInvalidatedModules(row.moduleCode), ⛔ 不是就地又拼一份域名', () => {
    expect(actBody()).toMatch(/invalidateModules\(\s*oaInvalidatedModules\(row\.moduleCode\)\s*\)/);
  });

  it('调用点在 res.success 判定之后 —— 审批失败时不该让别的标签白取一次数', () => {
    expect(actBody()).toMatch(/res\.success[\s\S]*?workspace\.invalidateModules/);
  });

  it('阴性对照: ⛔ 没有退化成「每个页面各写一个 onActivated 重取」', () => {
    expect(pending).not.toContain('onActivated');
  });
});
