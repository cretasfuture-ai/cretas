import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

import {
  RAW_WORKFLOW_OWNER_ANCHOR_PREFIX,
  isRawWorkflowOwnerAnchorName,
} from '@/constants/rawWorkflowOwnerAnchor';

/**
 * `__RAW_WORKFLOW_OWNER__` 这个前缀在两个语言里各有一份，抽不成一份 ⇒ 钉住两份一致。
 *
 * <h2>为什么会有两份</h2>
 *
 * 权威定义在 Java：`ProductProcessWorkflowServiceImpl` 为「多产出计划」建
 * inactive 内部 ProductType 锚点时写死了 `anchor.setName("__RAW_WORKFLOW_OWNER__" + id)`。
 * 前端要把这个锚点从 UI 上滤掉，就必须知道这个前缀 —— 而字符串常量跨不了语言边界。
 *
 * <h2>不钉住会怎样</h2>
 *
 * Java 改了前缀（或改成别的表达方式），前端这份**不会红、不会报错**，
 * 只会安静地不再过滤 ⇒ 客户重新看到
 * `PLAN-… · __RAW_WORKFLOW_OWNER__RMT_b25ab8b6-…`。
 * 这正是 2026-08-28 逐屏走查在 f006（真实客户）「物料需求单」上实拍到的样子。
 *
 * ⚠️ 本闸是**源码字面量比对**，不是行为验证。它挡不住「Java 换了另一种表达锚点的方式
 * 而字面量恰好还在」。它守的是最常见的那种漂：**只改一边**。
 */

const BACKEND_AUTHORITY = resolve(
  __dirname,
  '../../../../backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductProcessWorkflowServiceImpl.java',
);

describe('多产出计划锚点前缀: 前端那份必须和 Java 权威定义一致', () => {
  it('阳性对照: 真的读到了那个 Java 文件, 且它确实在造这个锚点', () => {
    const java = readFileSync(BACKEND_AUTHORITY, 'utf8');
    expect(java.length, 'Java 源码读成空 ⇒ 下面的断言是恒真式').toBeGreaterThan(1000);
    expect(java, '权威文件里应有 anchor.setName(...) —— 找不到说明代码搬家了, 本闸的路径要跟着改')
      .toMatch(/anchor\.setName\(/);
  });

  it('阴性对照: 判定函数认得出锚点, 也不误伤正常品名', () => {
    expect(isRawWorkflowOwnerAnchorName('__RAW_WORKFLOW_OWNER__RMT_b25ab8b6-a16d')).toBe(true);
    expect(isRawWorkflowOwnerAnchorName('SOP-20260817-01-黄油鸡-成品800g')).toBe(false);
    expect(isRawWorkflowOwnerAnchorName('叮咚好食光卤猪蹄(去大骨) 200g')).toBe(false);
    // ⛔ 非字符串不许当成锚点 —— null/undefined 是「没有名字」, 不是「是锚点」
    expect(isRawWorkflowOwnerAnchorName(null)).toBe(false);
    expect(isRawWorkflowOwnerAnchorName(undefined)).toBe(false);
    // ⚠️ 只认前缀, 不认「名字里碰巧含这个词」
    expect(isRawWorkflowOwnerAnchorName('产品__RAW_WORKFLOW_OWNER__尾巴')).toBe(false);
  });

  it('Java 里那个字面量, 和前端常量逐字相同', () => {
    const java = readFileSync(BACKEND_AUTHORITY, 'utf8');
    const literal = java.match(/anchor\.setName\("([^"]+)"/)?.[1];
    expect(literal, 'Java 里没找到 anchor.setName("…") 的字面量前缀').toBeTruthy();
    expect(
      literal,
      `Java 权威定义是 ${literal}, 前端常量是 ${RAW_WORKFLOW_OWNER_ANCHOR_PREFIX} —— 两份漂了。\n` +
        '改一边就要改另一边: 前端不认得这个前缀就不再过滤, 客户会重新看到\n' +
        '「PLAN-… · __RAW_WORKFLOW_OWNER__RMT_…」当成产品名。',
    ).toBe(RAW_WORKFLOW_OWNER_ANCHOR_PREFIX);
  });

  it('物料需求单确实接上了这道过滤(不是只定义了常量没人用)', () => {
    const screen = readFileSync(
      resolve(__dirname, '../../views/factory/material-requisitions/list.vue'),
      'utf8',
    );
    expect(screen, '定义了常量却没有消费端调用 = 机制在、没接上')
      .toMatch(/isRawWorkflowOwnerAnchorName\(/);
  });
});
