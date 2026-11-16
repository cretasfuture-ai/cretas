import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 请购单详情页: 驳回后改量重提 —— 源码闸 (量模板/脚本里的字面结构, 不 grep 中文注释)。
 *
 * 守的行为: 改了数量但没点「保存数量修改」就点「提交审批」, 提交前必须先把修改保存,
 * 否则后端提交的是旧数量, 当轮快照也记旧值, 「改量重提」名存实亡 (独立审查 #1770 阻塞项)。
 */
const raw = readFileSync(resolve(__dirname, '..', 'detail.vue'), 'utf8');

function stripComments(text: string): string {
  return text
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1');
}
const src = stripComments(raw);

function fnBody(name: string): string {
  const start = src.indexOf(`async function ${name}(`);
  expect(start, `找不到 ${name}`).toBeGreaterThan(-1);
  const next = src.indexOf('\nasync function ', start + 10);
  const nextPlain = src.indexOf('\nfunction ', start + 10);
  const ends = [next, nextPlain].filter((i) => i > -1);
  return src.slice(start, ends.length ? Math.min(...ends) : undefined);
}

describe('请购详情: 提交前保存未保存的数量修改', () => {
  it('handleSubmit 在调用 submitRequisition 之前先看 hasUnsavedEdits 并 saveEdits', () => {
    const body = fnBody('handleSubmit');
    const iUnsaved = body.indexOf('hasUnsavedEdits.value');
    const iSave = body.indexOf('saveEdits()');
    const iSubmit = body.indexOf('submitRequisition(');
    expect(iUnsaved).toBeGreaterThan(-1);
    expect(iSave).toBeGreaterThan(-1);
    expect(iSubmit).toBeGreaterThan(-1);
    expect(iUnsaved).toBeLessThan(iSubmit);
    expect(iSave).toBeLessThan(iSubmit);
    // 保存失败要中止提交
    expect(body).toMatch(/!\(await saveEdits\(\)\)\)\s*return/);
  });

  it('saveEdits 把改后的数量 PUT 给后端, 失败返回 false', () => {
    const body = fnBody('saveEdits');
    expect(body).toContain('updateRequisition(');
    expect(body).toContain('editQuantities.value[idx]');
    expect(body).toContain('return false');
    expect(body).toContain('return true');
  });

  it('hasUnsavedEdits 由 editQuantities 推出, 数量输入框绑定它', () => {
    expect(src).toMatch(/const hasUnsavedEdits = computed\(/);
    expect(src).toContain('v-model="editQuantities[$index]"');
  });
});
