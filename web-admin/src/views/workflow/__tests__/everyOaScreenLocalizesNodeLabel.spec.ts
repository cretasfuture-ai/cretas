import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 缺陷 23 (2026-09-01, LIUSHANMEN 生产): 个人 OA 把节点的**英文配置 key**
 * (`LIUSHANMEN admin approval`) 直接显示给一线，看不出卡在谁那里。
 *
 * <h2>这道闸守的不是「有没有 helper」，而是「每个屏有没有接上它」</h2>
 * `nodeLabel.ts`（PR #349）早就写好了 `currentNodeText()`，单测也绿 ——
 * 而它的单测断言的字符串**恰好就是 owner 撞到的那个**。
 * 问题是**六个屏里只有 `pending.vue` 调它**，另外五个照旧渲染裸的
 * `row.currentNodeLabel`。函数写得好好的、测试也绿，生产上没人调它 ——
 * 本仓 `measurement-and-wiring.md` 的**形态 B：机制在，只是没接上**。
 *
 * ⇒ 单测覆盖不到这一类：它直接调被测函数，绕过了「谁调它」。
 *   所以这里扫**源码**，断言每个渲染 `currentNodeLabel` 的屏都经过 helper。
 *
 * ⚠️ 这是**源码闸**，判据是文本形状而非运行时行为。它抓得住「新加的第 7 个屏忘了接」，
 *   抓不住「接了但传错 row」。⛔ 不要因为它绿就跳过真实渲染的验收。
 */
const WORKFLOW_DIR = resolve(__dirname, '..');

function screenFiles(): string[] {
  return readdirSync(WORKFLOW_DIR).filter((name) => name.endsWith('.vue'));
}

/** 去掉 HTML 注释与 JS 行注释 —— 免得闸把「我自己写的说明文字」当成被测对象。
 *  （本仓踩过：一晚四次源码闸匹配到自己的注释，三次假阳性 + 一次更危险的假阴性。） */
function stripComments(source: string): string {
  return source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/^\s*\/\/.*$/gm, '');
}

describe('缺陷 23 — 每个 OA 屏都必须经 nodeLabel helper 展示当前节点', () => {
  const files = screenFiles();

  it('阳性对照：确实扫到了这批屏（不是扫了 0 个文件而恒绿）', () => {
    // 一个扫不到文件的闸会安静地天天绿。先证明仪器活着。
    expect(files.length).toBeGreaterThanOrEqual(6);
    expect(files).toContain('pending.vue');
    expect(files).toContain('my-created.vue');
  });

  it.each(screenFiles())('%s：不得直接渲染裸的 currentNodeLabel', (file) => {
    const body = stripComments(readFileSync(resolve(WORKFLOW_DIR, file), 'utf8'));

    // `{{ row.currentNodeLabel }}` / `{{row.currentNodeLabel}}` 都算违例。
    const rawRender = /\{\{\s*[A-Za-z_$][\w$]*\.currentNodeLabel\s*\}\}/.test(body);

    expect(rawRender, [
      `${file} 在模板里直接渲染了 currentNodeLabel。`,
      '那个字段在数据里可能是纯英文配置 key（生产实测 `LIUSHANMEN admin approval`），',
      '一线看不懂。请改用 `currentNodeText(row)`（见 ./nodeLabel.ts）。',
      '⛔ 不要在本文件里另写一份「像不像英文」的判断 —— helper 已经有了。',
    ].join('\n')).toBe(false);
  });

  it.each(screenFiles())('%s：用到 currentNodeLabel 的屏必须 import helper', (file) => {
    const body = stripComments(readFileSync(resolve(WORKFLOW_DIR, file), 'utf8'));
    if (!body.includes('currentNodeLabel')) return; // 这个屏不展示当前节点，与本闸无关

    expect(
      body.includes("from './nodeLabel'"),
      `${file} 引用了 currentNodeLabel 却没有 import ./nodeLabel 的 helper`,
    ).toBe(true);
  });
});
