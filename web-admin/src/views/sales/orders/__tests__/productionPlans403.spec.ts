/**
 * 关联生产计划的 403 必须落进「不可见」那一态, ⛔ 不能落进「加载失败」。
 *
 * 🔴 为什么要这道闸 (2026-08-22 实测, 我自己的修复上栽的):
 * 第一版判据写成 `err.response.status === 403`(axios 形状), 而本仓请求层抛的是
 * **ApiError**: `new ApiError('权限不足', 'FORBIDDEN', 403)` —— status/code 是
 * **顶层字段**, 根本没有 `response`。⇒ 那个判据恒不成立, 403 分支一次都没进去过。
 *
 * ▎部署后我看到「红框不再弹了」差点判成修好了 —— 那是 _silent 的功劳,
 *   与这个分支无关。真正暴露它的是**按钮文案还是旧的**。
 *   ⇒ 「一个改动看起来生效了」和「这个改动生效了」是两件事。
 *
 * 这道闸直接拿**真实的 ApiError 形状**打进去, 不再靠部署后肉眼看文案。
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { ApiError } from '@/types/api';
import { productionActionState } from '../salesOrderGuards';

/**
 * 复刻 detail.vue 里 catch 分支的判据。
 *
 * ⚠️ 这是**第二份**判据(与 detail.vue 里那份同源) —— 抽不成一份是因为它嵌在
 * 组件的 catch 里。⛔ 所以下面额外钉一条: detail.vue 的源码里必须还写着同样的判据,
 * 漂了就红。
 */
function classify(err: unknown): 'forbidden' | 'failed' {
  const e = err as { status?: number; code?: string; response?: { status?: number } };
  const status = e?.status ?? e?.response?.status;
  return (status === 403 || e?.code === 'FORBIDDEN') ? 'forbidden' : 'failed';
}

const ITEMS = [{ id: '1', quantity: 1 }];

describe('关联生产计划的 403 分类', () => {
  it('🔴 请求层真实抛出的 ApiError(403) 必须判成 forbidden', () => {
    // ⚠️ 这正是 request.ts 第 363 行抛的东西, ⛔ 不是我构造的假形状
    const err = new ApiError('权限不足', 'FORBIDDEN', 403);
    expect(classify(err)).toBe('forbidden');
  });

  it('axios 形状(err.response.status)也要认 —— 不是每条路径都包成 ApiError', () => {
    expect(classify({ response: { status: 403 } })).toBe('forbidden');
  });

  it('⛔ 阴性对照: 非 403 仍然是「加载失败」, 不许被吞成权限问题', () => {
    expect(classify(new ApiError('服务器错误', 'SERVER_ERROR', 500))).toBe('failed');
    expect(classify(new Error('Network Error'))).toBe('failed');
    expect(classify(undefined)).toBe('failed');
  });

  it('🔴 detail.vue 里那份判据必须与本文件一致(⛔ 两份不许漂)', () => {
    // 形态 D: 抽不成一份(它嵌在组件的 catch 里)时, 闸要钉的是**两份一致**,
    // ⛔ 不是「各自都对」—— 各自都对同时绿也不排除两份内容不同。
    const src = readFileSync(
      resolve(__dirname, '..', 'detail.vue'), 'utf-8');
    // 阳性对照: 文件本身要读得到且够长, 否则下面的断言在读了个空串时也会红得莫名其妙
    expect(src.length, 'detail.vue 读不到或为空 —— 本次读数作废').toBeGreaterThan(1000);
    expect(src, 'detail.vue 的 403 判据漂了 —— 它必须同时认 ApiError 的顶层 status 和 axios 形状')
      .toContain('e?.status ?? e?.response?.status');
    expect(src, 'detail.vue 必须同时认 code === FORBIDDEN')
      .toContain("e?.code === 'FORBIDDEN'");
  });

  it('两态的文案必须不同, 且 403 那条**不许**说「刷新后重试」', () => {
    const forbidden = productionActionState(ITEMS, [], false, false, true);
    const failed = productionActionState(ITEMS, [], false, true, false);
    expect(forbidden.label).not.toBe(failed.label);
    // ▎权限不足时刷新一万次也没用 —— 那是把人推向无效动作的指引
    expect(forbidden.tooltip).not.toContain('刷新');
    expect(forbidden.tooltip).toContain('权限');
    // 阴性对照: 真故障那条**要**保留重试指引
    expect(failed.tooltip).toContain('刷新');
  });
});
