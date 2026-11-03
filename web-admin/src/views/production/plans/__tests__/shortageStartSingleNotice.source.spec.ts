import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

// 2026-11-03 验收: 缺料拦开工 (409 SHORTAGE_CASE_OPEN) 时, 拦截器的「操作被拒绝」toast 和强制开工对话框
// 同时弹出。约定: 第一次请求声明 SHORTAGE_CASE_OPEN 由页面处理 (拦截器按 _handledErrorCodes 不弹),
// 对话框展示后端那句「缺什么」; 带原因的重试不再声明 —— 重试仍失败时要照常提示。
// 拦截器侧「声明了就不弹」的行为由 api/__tests__/request.workflowConflict.spec.ts 守。

const listSource = readFileSync(resolve(__dirname, '..', 'list.vue'), 'utf8');

function fnBody(source: string, name: string): string {
  const start = source.indexOf(`function ${name}(`);
  expect(start, `找不到 function ${name}`).toBeGreaterThan(-1);
  const end = source.indexOf('\n}', start);
  expect(end, `function ${name} 没有顶格收尾`).toBeGreaterThan(start);
  return source.slice(start, end);
}

describe('缺料拦开工只出一个提示', () => {
  it('声明的是 SHORTAGE_CASE_OPEN, 且只声明它', () => {
    expect(listSource).toContain("const SHORTAGE_HANDLED = { _handledErrorCodes: ['SHORTAGE_CASE_OPEN'] };");
  });

  it('开工: 第一次请求声明由页面处理, 并把后端说明交给对话框', () => {
    const body = fnBody(listSource, 'handleStart');
    expect(body).toContain('/start`, undefined, SHORTAGE_HANDLED)');
    expect(body).toContain('await forceStart(row, error.message)');
  });

  it('转批次: 第一次请求声明由页面处理; 带原因的重试不声明', () => {
    const body = fnBody(listSource, 'postCreateBatch');
    expect(body).toContain('return await post(url, undefined, SHORTAGE_HANDLED)');
    expect(body).toContain('askForceStartReason(error.message)');
    expect(body).toContain('return await post(url, { forceStartReason: reason })');
  });

  it('强制开工: 带原因的重试不声明, 对话框展示后端说明', () => {
    const force = fnBody(listSource, 'forceStart');
    expect(force).toContain('askForceStartReason(detail)');
    expect(force).toContain('{ forceStartReason: reason })');
    expect(force).not.toContain('SHORTAGE_HANDLED');
    expect(fnBody(listSource, 'askForceStartReason')).toContain('detail ? `${detail}');
  });
});
