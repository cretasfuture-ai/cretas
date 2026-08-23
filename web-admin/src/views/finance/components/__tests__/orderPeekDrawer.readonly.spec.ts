/**
 * 「订单速览」抽屉必须**只读**, 且**不得是路由**。
 *
 * 🔴 owner 定的形状 (2026-08-22):
 *   「导航栏里只能看到财务自己的内容, 但要让财务看到订单详情,
 *     又不能让他直接跨模块去看。」
 *
 * ▎这道闸钉的是**结构**, 不是行为:
 *   ① 抽屉里不能出现任何写动作 —— 复用销售订单详情页的方案被否掉, 正是因为那页
 *      有十几个写按钮, 逐个挡漏一个就是权限漏洞。⇒ 改成一开始就不给。
 *   ② 抽屉**不能被注册成路由** —— 一旦有路由, 它就可能出现在侧边栏 / 被手输 URL 到达,
 *      owner 那句「不能让他直接跨模块去看」当场失效。
 *
 * ⚠️ 这两条靠人自觉守不住(本仓已有大量「注释写了照样犯」的记录), 所以做成源码闸。
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const DRAWER = resolve(__dirname, '..', 'OrderPeekDrawer.vue');
const ROUTER = resolve(__dirname, '..', '..', '..', '..', 'router', 'index.ts');

const src = readFileSync(DRAWER, 'utf-8');

describe('OrderPeekDrawer 结构约束', () => {
  it('⚠️ 阳性对照: 文件读得到且不是空的(否则下面的断言全部恒真)', () => {
    expect(src.length).toBeGreaterThan(1000);
    expect(src).toContain('el-drawer');
  });

  it('⛔ 不得包含任何写动作 —— 只读是设计约束, 不是"暂时没做"', () => {
    // 写请求的**调用点**
    for (const verb of ['post(', 'put(', 'del(', 'patch(']) {
      expect(src, `抽屉里出现了写请求 ${verb} —— 它必须只读`).not.toContain(verb);
    }
    // ⚠️ 还要挡 **import** —— 只挡调用点不够:
    //    实测变异 `import { get, post }` 时这条断言纹丝不动(那是 `post ` 不是 `post(`),
    //    我差点读成「断言在守空气」。真相是变异没打中, 但断言确实也漏了这一面。
    //    ▎变异不红先分两种: 断言没用 / 变异没生效 —— 这次两个问题各占一半。
    const importLine = src.match(/import\s*\{[^}]*\}\s*from\s*'@\/api\/request'/)?.[0] ?? '';
    expect(importLine, "抽屉必须只 import get —— 引入写方法就是把口子开在这里")
      .toBe("import { get } from '@/api/request'");
    // 常见写操作动词(中文按钮名)
    for (const word of ['提交', '保存', '删除', '取消订单', '审批', '开票', '收款']) {
      expect(src, `抽屉里出现了写操作按钮「${word}」`).not.toContain(`>${word}`);
    }
  });

  it('⛔ 不得被注册成路由 —— 有路由就可能从侧边栏/手输 URL 到达', () => {
    const router = readFileSync(ROUTER, 'utf-8');
    // 阳性对照: 路由表本身读得到
    expect(router.length).toBeGreaterThan(10000);
    expect(router, 'OrderPeekDrawer 被注册进了路由表 —— 它必须只是个组件')
      .not.toContain('OrderPeekDrawer');
  });

  it('403 与真故障分成两态, 且 403 那条不许说「重试」', () => {
    // ▎权限不足时刷新一万次也没用 —— 那是把人推向无效动作的指引
    expect(src).toContain("'forbidden'");
    expect(src).toContain("'failed'");
    const forbiddenBlock = src.slice(src.indexOf("errorKind === 'forbidden'"),
                                     src.indexOf("errorKind === 'failed'"));
    expect(forbiddenBlock.length).toBeGreaterThan(50);
    expect(forbiddenBlock, '403 提示里不该出现「重试」').not.toContain('重试');
  });

  it('⛔ 金额读不到时显示「—」, 不许兜底成 0', () => {
    // ▎「我不知道」和「是 0」对看账的人是两件完全不同的事
    expect(src).not.toMatch(/toFixed\(2\)\s*:\s*['"`]?0/);
    expect(src).toContain("Number.isFinite(n) ? `¥${n.toFixed(2)}` : '—'");
  });
});
